/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.scenario

import org.apache.jmeter.JMeter
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.config.ConfigTestElement
import org.apache.jmeter.control.LoopController
import org.apache.jmeter.engine.JMeterEngineException
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.test.samplers.CollectSamplesListener
import org.apache.jmeter.test.samplers.ThreadSleep
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.SetupThreadGroup
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.treebuilder.TreeBuilder
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

class ScenarioResolverTest : JMeterTestCase() {
    private val browse = ThreadGroup().apply {
        name = "Browse"
        setThreadGroupId("browse-id")
    }

    private val checkout = ThreadGroup().apply {
        name = "Checkout"
        setThreadGroupId("checkout-id")
    }

    private fun workload(name: String, threadGroup: AbstractThreadGroup, threads: Int, loops: Int = 1) =
        ScenarioWorkload().apply {
            this.name = name
            setThreadGroupId(threadGroup.threadGroupId)
            setProperty(AbstractThreadGroup.NUM_THREADS, threads)
            setProperty(ThreadGroup.RAMP_TIME, 0)
            val loopController = LoopController().apply { this.loops = loops }
            setProperty(TestElementProperty(AbstractThreadGroup.MAIN_CONTROLLER, loopController))
        }

    private fun scenario(name: String, vararg workloads: ScenarioWorkload, enabled: Boolean = true) =
        Scenario(name).apply {
            isEnabled = enabled
            setWorkloads(workloads.toList())
        }

    private fun plan(scenarios: TreeBuilder.() -> Unit, extra: TreeBuilder.() -> Unit = {}): ListedHashTree =
        testTree {
            TestPlan::class {
                ScenariosSection::class {
                    scenarios()
                }
                ThreadGroupsSection::class {
                    browse {
                        ThreadSleep::class { duration = 0.seconds }
                    }
                    checkout {
                        ThreadSleep::class { duration = 0.seconds }
                    }
                }
                extra()
            }
        }

    private fun convertAndResolve(tree: HashTree): HashTree =
        ScenarioResolver.resolve(JMeter.convertSubTree(tree, false))

    private fun planChildren(tree: HashTree): List<Any> = tree.getTree(tree.array[0]).list().toList()

    @Test
    fun `plan without sections is returned unchanged`() {
        val tree = testTree {
            TestPlan::class {
                ThreadGroup::class {}
            }
        }
        assertSame(tree, ScenarioResolver.resolve(tree))
    }

    @Test
    fun `enabled scenario becomes one thread group per workload with sections flattened`() {
        val variables = Arguments().apply { name = "Variables" }
        val listener = CollectSamplesListener()
        val tree = plan(
            scenarios = {
                +scenario("Stress", workload("Stress browse", browse, 50), enabled = false)
                +scenario(
                    "Load",
                    workload("", browse, 10).apply { setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTEST) },
                    workload("", browse, 3),
                    workload("Checkout load", checkout, 2).apply { setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTEST) },
                )
            },
            extra = {
                ProfilesSection::class {
                    SharedProfile::class { +variables }
                }
                ListenersSection::class { +listener }
            }
        )

        val resolved = convertAndResolve(tree)
        val children = planChildren(resolved)
        val threadGroups = children.filterIsInstance<AbstractThreadGroup>()

        assertEquals(listOf("Browse", "Browse (2)", "Checkout load"), threadGroups.map { it.name })
        assertEquals(listOf(10, 3, 2), threadGroups.map { it.numThreads })
        assertEquals(listOf(true, false, true), threadGroups.map { it.onErrorStopTest }) {
            "Error handling comes from each workload"
        }
        assertTrue(children.any { it is Arguments && it.name == "Variables" }) {
            "Shared configuration should move to the test plan level: $children"
        }
        assertTrue(children.any { it === listener }) { "Listeners should move to the test plan level: $children" }
        assertTrue(children.none { it is TestPlanSection }) { "Sections should be removed: $children" }
        assertEquals(0, browse.numThreads) { "Resolving must not modify the configured thread group" }
        val browseInstances = threadGroups.take(2)
        val sleeps = browseInstances.map { resolved.getTree(resolved.array[0]).getTree(it).list().single() }
        assertTrue(sleeps[0] !== sleeps[1]) { "Each workload should get its own copy of the script" }
    }

    @Test
    fun `test fragments never run by themselves`() {
        val tree = plan(
            scenarios = { +scenario("Scenario", workload("Browse", browse, 1)) },
            extra = {
                TestFragmentsSection::class {
                    org.apache.jmeter.control.GenericController::class { name = "Reusable login" }
                }
            }
        )
        val children = planChildren(convertAndResolve(tree))
        assertTrue(children.none { it is org.apache.jmeter.control.GenericController && it !is AbstractThreadGroup }) {
            "Fragment controllers must not become test-level elements: $children"
        }
    }

    @Test
    fun `setUp thread group keeps its type`() {
        val setup = SetupThreadGroup().apply {
            name = "Login"
            setThreadGroupId("setup-id")
        }
        val tree = testTree {
            TestPlan::class {
                ScenariosSection::class {
                    +scenario("Scenario", workload("Login", setup, 1))
                }
                ThreadGroupsSection::class { +setup }
            }
        }
        assertTrue(planChildren(convertAndResolve(tree)).single() is SetupThreadGroup)
    }

    @Test
    fun `a chosen scenario runs even when it is not the active one`() {
        val stress = scenario("Stress", workload("Stress browse", browse, 50), enabled = false)
        val tree = plan(
            scenarios = {
                +scenario("Load", workload("Load browse", browse, 10))
                +stress
            }
        )
        val resolved = ScenarioResolver.resolve(JMeter.convertSubTree(tree, false), stress)
        val threadGroup = planChildren(resolved).filterIsInstance<AbstractThreadGroup>().single()
        assertEquals("Stress browse", threadGroup.name)
        assertEquals(50, threadGroup.numThreads)
    }

    @Test
    fun `no enabled scenario is rejected`() {
        val tree = plan(
            scenarios = {
                +scenario("Scenario", workload("Browse", browse, 1), enabled = false)
            }
        )
        val e = assertThrows<ScenarioException> { convertAndResolve(tree) }
        assertEquals("No scenario is enabled. Enable the scenario you want to run.", e.message)
    }

    @Test
    fun `more than one enabled scenario is rejected`() {
        val tree = plan(
            scenarios = {
                +scenario("Load", workload("Browse", browse, 1))
                +scenario("Stress", workload("Browse", browse, 1))
            }
        )
        val e = assertThrows<ScenarioException> { convertAndResolve(tree) }
        assertEquals("Only one scenario can be enabled, but these are: 'Load', 'Stress'.", e.message)
    }

    @Test
    fun `workload referencing a disabled thread group is rejected`() {
        browse.isEnabled = false
        val tree = plan(
            scenarios = {
                +scenario("Load", workload("Browse load", browse, 1))
            }
        )
        val e = assertThrows<ScenarioException> { convertAndResolve(tree) }
        assertEquals(
            "'Browse load' in scenario 'Load' uses a thread group that does not exist or is disabled.",
            e.message
        )
    }

    @Test
    fun `scenario without workloads is rejected`() {
        val tree = plan(
            scenarios = {
                +scenario("Empty", workload("Browse", browse, 1).apply { isEnabled = false })
            }
        )
        val e = assertThrows<ScenarioException> { convertAndResolve(tree) }
        assertEquals("Scenario 'Empty' has no enabled thread groups.", e.message)
    }

    @Test
    fun `validation flattening ignores scenarios`() {
        val tree = plan(
            scenarios = {
                +scenario("Scenario", workload("Browse", browse, 5))
            }
        )
        val flat = ScenarioResolver.flattenIgnoringScenarios(JMeter.convertSubTree(tree, false))
        assertEquals(listOf(browse, checkout), planChildren(flat))
    }

    private fun variables(vararg pairs: Pair<String, String>) = Arguments().apply {
        pairs.forEach { (name, value) -> addArgument(name, value) }
    }

    private fun profilesPlan(planVariables: Arguments = Arguments(), vararg workloads: ScenarioWorkload) =
        testTree {
            TestPlan::class {
                setUserDefinedVariables(planVariables)
                ScenariosSection::class {
                    +scenario("Load", *workloads)
                }
                ThreadGroupsSection::class {
                    browse {
                        RecordVariable::class { variable = "host" }
                    }
                }
                ProfilesSection::class {
                    SharedProfile::class {
                        +variables("port" to "8080")
                    }
                    Profile::class {
                        name = "acceptance"
                        isDefault = true
                        +variables("host" to "acc.example.com", "url" to "https://\${host}:\${port}")
                        ConfigTestElement::class { name = "Acceptance defaults" }
                    }
                    Profile::class {
                        name = "production"
                        +variables("host" to "www.example.com")
                    }
                }
            }
        }

    @Test
    fun `each workload gets the configuration and variables of its profile`() {
        val tree = profilesPlan(
            Arguments(),
            workload("Acceptance", browse, 1).apply { profile = "acceptance" },
            workload("Production", browse, 1).apply { profile = "production" },
            workload("Use default", browse, 1),
        )
        val resolved = convertAndResolve(tree)
        val planTree = resolved.getTree(resolved.array[0])
        val threadGroups = planTree.list().filterIsInstance<AbstractThreadGroup>()

        assertEquals(
            listOf(
                mapOf("host" to "acc.example.com", "url" to "https://acc.example.com:8080"),
                mapOf("host" to "www.example.com"),
                mapOf("host" to "acc.example.com", "url" to "https://acc.example.com:8080"),
            ),
            threadGroups.map { it.profileVariables }
        ) { "A thread group set to 'Use default' runs with the default profile, acceptance" }
        assertEquals(
            listOf("host" to "acc.example.com"),
            listOf("host" to threadGroups[2].profileVariables["host"])
        )
        assertEquals(
            listOf("Acceptance defaults", "Record"),
            planTree.getTree(threadGroups[0]).list().map { (it as TestElement).name }
        ) { "Profile configuration comes first in the thread group and User Defined Variables are removed" }
        assertTrue(planTree.list().filterIsInstance<Arguments>().any { it.argumentsAsMap == mapOf("port" to "8080") }) {
            "Shared configuration applies to the whole test"
        }
    }

    @Test
    fun `profile name can come from a test plan variable`() {
        val tree = profilesPlan(
            variables("environment" to "production"),
            workload("Browse", browse, 1).apply { profile = "\${environment}" },
        )
        val threadGroup = planChildren(convertAndResolve(tree)).filterIsInstance<AbstractThreadGroup>().single()
        assertEquals(mapOf("host" to "www.example.com"), threadGroup.profileVariables)
    }

    @Test
    fun `profile variables can set the workload`() {
        val tree = profilesPlan(
            Arguments(),
            workload("Acceptance", browse, 1).apply {
                profile = "acceptance"
                setProperty(AbstractThreadGroup.NUM_THREADS, "\${users}")
            },
        )
        tree.getTree(tree.array[0]).list().filterIsInstance<ProfilesSection>().forEach { section ->
            tree.getTree(tree.array[0]).getTree(section).list().filterIsInstance<Profile>()
                .first { it.name == "acceptance" }
                .let { profile ->
                    tree.getTree(tree.array[0]).getTree(section).getTree(profile).add(variables("users" to "2"))
                }
        }
        val threadGroup = planChildren(convertAndResolve(tree)).filterIsInstance<AbstractThreadGroup>().single()
        assertEquals(2, threadGroup.numThreads) { "Thread count is read before threads get their variables" }
    }

    @Test
    fun `shared variables are evaluated once for the whole run`() {
        val tree = profilesPlan(
            variables("environment" to "acc"),
            workload("Acceptance", browse, 1).apply { profile = "acceptance" },
        )
        val sharedTree = tree.getTree(tree.array[0]).let { planTree ->
            val section = planTree.list().filterIsInstance<ProfilesSection>().single()
            planTree.getTree(section).let { it.getTree(it.list().filterIsInstance<SharedProfile>().single()) }
        }
        val original = variables("base" to "https://\${environment}.example.com")
        sharedTree.add(original)

        val resolved = convertAndResolve(tree)
        val shared = planChildren(resolved).filterIsInstance<Arguments>()
            .single { it.argumentsAsMap.containsKey("base") }
        assertEquals("https://acc.example.com", shared.argumentsAsMap["base"]) {
            "The engine gets the value computed for the profiles, so functions are not evaluated a second time"
        }
        assertEquals("https://\${environment}.example.com", original.argumentsAsMap["base"]) {
            "The edited test plan keeps its expressions"
        }
    }

    @Test
    fun `agent validation runs each thread group once instead of the scenario load`() {
        val tree = profilesPlan(
            Arguments(),
            workload("Stress", browse, 50, loops = 5).apply { profile = "production" },
        )
        val result = org.apache.jmeter.ai.AgentValidationRunner().run(tree)
        assertEquals(1, result.samples.size) { "Validation must not start the load of the active scenario" }
    }

    @Test
    fun `thread groups that are not BreakTest's own keep their settings`() {
        val arrivals = org.apache.jmeter.threads.openmodel.OpenModelThreadGroup().apply {
            name = "Plugin-like"
            threadGroupId = "plugin-id"
            scheduleString = "rate(1/sec) even_arrivals(1 min)"
        }
        val tree = testTree {
            TestPlan::class {
                ScenariosSection::class { +scenario("Load", workload("Plugin-like", arrivals, 50)) }
                ThreadGroupsSection::class {
                    arrivals { ThreadSleep::class { duration = 0.seconds } }
                }
            }
        }
        val instance = planChildren(convertAndResolve(tree)).filterIsInstance<AbstractThreadGroup>().single()
        assertTrue(instance.getPropertyAsString(AbstractThreadGroup.NUM_THREADS).isEmpty()) {
            "The scenario row must not override the settings of the thread group"
        }
        assertEquals("rate(1/sec) even_arrivals(1 min)", instance.getPropertyAsString(ThreadGroup.OPEN_MODEL_SCHEDULE))
    }

    @Test
    fun `the scenario decides whether thread groups run one after another`() {
        val tree = plan(
            scenarios = {
                +scenario("Load", workload("Browse", browse, 1)).apply { isRunConsecutively = true }
            }
        )
        val original = tree.array[0] as TestPlan
        val resolved = convertAndResolve(tree)
        assertTrue((resolved.array[0] as TestPlan).isSerialized)
        assertFalse(original.isSerialized) { "The edited test plan is not changed" }
    }

    @Test
    fun `profile variables override test plan variables`() {
        val listener = CollectSamplesListener()
        val tree = profilesPlan(
            variables("host" to "plan.example.com"),
            workload("Acceptance", browse, 1).apply { profile = "acceptance" },
        )
        tree.getTree(tree.array[0]).add(listener)
        runAndWait(tree, listener, 1)
        assertEquals(
            mapOf("Acceptance" to "acc.example.com"),
            listener.events.associate { it.threadGroup to it.result.responseDataAsString }
        )
    }

    @Test
    fun `listeners inside a thread group receive its samples`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val results = dir.resolve("results.csv").toFile()
        val threadGroupListener = org.apache.jmeter.reporters.ResultCollector().apply {
            name = "Browse results"
            filename = results.absolutePath
        }
        val tree = testTree {
            TestPlan::class {
                ScenariosSection::class { +scenario("Load", workload("Browse", browse, 2, loops = 2)) }
                ThreadGroupsSection::class {
                    browse {
                        ThreadSleep::class { duration = 0.seconds }
                        +threadGroupListener
                    }
                }
            }
        }
        val engine = StandardJMeterEngine()
        engine.configure(JMeter.convertSubTree(tree, false))
        engine.runTest()
        engine.awaitTermination(Duration.ofSeconds(10))
        val deadline = System.currentTimeMillis() + 10_000
        while ((!results.exists() || results.readLines().size < 5) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(4, results.readLines().drop(1).size) { "2 threads x 2 loops, plus the header: ${results.readText()}" }
    }

    private fun runAndWait(tree: HashTree, listener: CollectSamplesListener, samples: Int) {
        val engine = StandardJMeterEngine()
        try {
            engine.configure(JMeter.convertSubTree(tree, false))
            engine.runTest()
            engine.awaitTermination(Duration.ofSeconds(10))
            val deadline = System.currentTimeMillis() + 10_000
            while (listener.events.size < samples && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } finally {
            engine.stopTest(true)
        }
    }

    @Test
    fun `the default profile of a run can be chosen, as with --profile`() {
        val tree = profilesPlan(
            Arguments(),
            workload("Use default", browse, 1),
            workload("Always acceptance", browse, 1).apply { profile = "acceptance" },
        )
        val resolved = ScenarioResolver.resolve(JMeter.convertSubTree(tree, false), null, "production")
        val threadGroups = planChildren(resolved).filterIsInstance<AbstractThreadGroup>()
        assertEquals(
            listOf("www.example.com", "acc.example.com"),
            threadGroups.map { it.profileVariables["host"] }
        ) { "Only thread groups set to 'Use default' follow the default profile of the run" }
    }

    @Test
    fun `an unknown default profile for the run is rejected`() {
        val tree = profilesPlan(Arguments(), workload("Use default", browse, 1))
        val e = assertThrows<ScenarioException> {
            ScenarioResolver.resolve(JMeter.convertSubTree(tree, false), null, "staging")
        }
        assertEquals(
            "The run uses profile 'staging', which does not exist or is disabled. Profiles: 'acceptance', 'production'.",
            e.message
        )
    }

    @Test
    fun `without a profile marked default the first profile is the default`() {
        val tree = testTree {
            TestPlan::class {
                ScenariosSection::class { +scenario("Load", workload("Use default", browse, 1)) }
                ThreadGroupsSection::class { browse { RecordVariable::class { variable = "host" } } }
                ProfilesSection::class {
                    Profile::class {
                        name = "first"
                        +variables("host" to "first.example.com")
                    }
                    Profile::class {
                        name = "second"
                        +variables("host" to "second.example.com")
                    }
                }
            }
        }
        val threadGroup = planChildren(convertAndResolve(tree)).filterIsInstance<AbstractThreadGroup>().single()
        assertEquals("first.example.com", threadGroup.profileVariables["host"])
    }

    @Test
    fun `a scenario is found by name, also when it is not the enabled one`() {
        val stress = scenario("Stress", workload("Stress browse", browse, 50), enabled = false)
        val tree = plan(
            scenarios = {
                +scenario("Load", workload("Load browse", browse, 10))
                +stress
            }
        )
        assertSame(stress, ScenarioResolver.findScenario(tree, "Stress"))
        val e = assertThrows<ScenarioException> { ScenarioResolver.findScenario(tree, "Soak") }
        assertEquals("There is no scenario 'Soak'. Scenarios: 'Load', 'Stress'.", e.message)
    }

    private fun startingHost(shared: SharedProfile, profile: Profile?): String {
        val listener = CollectSamplesListener()
        val tree = testTree {
            TestPlan::class {
                +listener
                ScenariosSection::class { +scenario("Load", workload("Browse", browse, 1)) }
                ThreadGroupsSection::class {
                    browse {
                        +variables("host" to "group.example")
                        RecordVariable::class { variable = "host" }
                    }
                }
                ProfilesSection::class {
                    shared { +variables("host" to "shared.example") }
                    profile?.invoke { +variables("host" to "profile.example") }
                }
            }
        }
        runAndWait(tree, listener, 1)
        return listener.events.single().result.responseDataAsString
    }

    @Test
    fun `the shared profile decides whether its variables override those of thread groups`() {
        assertEquals("group.example", startingHost(SharedProfile(), null)) { "By default the thread group wins" }
        assertEquals(
            "shared.example",
            startingHost(SharedProfile().apply { isOverridingThreadGroupVariables = true }, null)
        )
    }

    @Test
    fun `a profile decides whether its variables override those of thread groups`() {
        assertEquals(
            "profile.example",
            startingHost(SharedProfile(), Profile("acceptance").apply { isDefault = true })
        ) { "By default an environment profile wins" }
        assertEquals(
            "group.example",
            startingHost(
                SharedProfile(),
                Profile("acceptance").apply {
                    isDefault = true
                    isOverridingThreadGroupVariables = false
                }
            )
        )
    }

    @Test
    fun `workload settings use the thread group value when the profile does not override it`() {
        fun threads(vararg profileVariables: Pair<String, String>): Int {
            val tree = testTree {
                TestPlan::class {
                    ScenariosSection::class {
                        +scenario(
                            "Load",
                            workload("Browse", browse, 1).apply { setProperty(AbstractThreadGroup.NUM_THREADS, "\${users}") }
                        )
                    }
                    ThreadGroupsSection::class {
                        browse {
                            +variables("users" to "2")
                        }
                    }
                    ProfilesSection::class {
                        Profile::class {
                            name = "acceptance"
                            isDefault = true
                            isOverridingThreadGroupVariables = false
                            +variables(*profileVariables)
                        }
                    }
                }
            }
            // As the engine does before it starts the thread groups
            val run = convertAndResolve(tree).also { it.traverse(org.apache.jmeter.engine.PreCompiler()) }
            val threadGroup = planChildren(run).filterIsInstance<AbstractThreadGroup>().single()
            threadGroup.isRunningVersion = true
            return threadGroup.numThreads
        }
        assertEquals(2, threads("users" to "50"))
        assertEquals(2, threads("users" to "50", "host" to "acc.example")) {
            "An unrelated profile variable must not make the profile value win"
        }
    }

    @Test
    fun `unknown profile is rejected`() {
        val tree = profilesPlan(Arguments(), workload("Browse", browse, 1).apply { profile = "staging" })
        val e = assertThrows<ScenarioException> { convertAndResolve(tree) }
        assertEquals(
            "'Browse' in scenario 'Load' uses profile 'staging', which does not exist or is disabled. " +
                "Profiles: 'acceptance', 'production'.",
            e.message
        )
    }

    @Test
    fun `validation uses the default profile`() {
        val tree = profilesPlan(Arguments(), workload("Browse", browse, 1))
        val flat = ScenarioResolver.flattenIgnoringScenarios(JMeter.convertSubTree(tree, false))
        val threadGroup = planChildren(flat).filterIsInstance<AbstractThreadGroup>().single()
        assertEquals("acc.example.com", threadGroup.profileVariables["host"])
        assertEquals(0, browse.profileVariables.size) { "The edited thread group must not change" }
    }

    @Test
    fun `threads see the variables of their own profile`() {
        val listener = CollectSamplesListener()
        val tree = profilesPlan(
            Arguments(),
            workload("Acceptance", browse, 1).apply { profile = "acceptance" },
            workload("Production", browse, 1).apply { profile = "production" },
        )
        tree.getTree(tree.array[0]).add(listener)
        val engine = StandardJMeterEngine()
        try {
            engine.configure(JMeter.convertSubTree(tree, false))
            engine.runTest()
            engine.awaitTermination(Duration.ofSeconds(10))
            val deadline = System.currentTimeMillis() + 10_000
            while (listener.events.size < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } finally {
            engine.stopTest(true)
        }
        assertEquals(
            mapOf("Acceptance" to "acc.example.com", "Production" to "www.example.com"),
            listener.events.associate { it.threadGroup to it.result.responseDataAsString }
        )
    }

    @Test
    fun `engine runs the enabled scenario`() {
        val listener = CollectSamplesListener()
        val tree = plan(
            scenarios = {
                +scenario("Scenario", workload("Browse steady", browse, 2, loops = 2), workload("Browse spike", browse, 1))
            },
            extra = {
                ListenersSection::class { +listener }
            }
        )
        val engine = StandardJMeterEngine()
        try {
            engine.configure(JMeter.convertSubTree(tree, false))
            engine.runTest()
            engine.awaitTermination(Duration.ofSeconds(10))
            // Wait for the started threads, not just the engine thread that starts them
            val deadline = System.currentTimeMillis() + 10_000
            while (listener.events.size < 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            Thread.sleep(200)
        } finally {
            engine.stopTest(true)
        }
        val samplesPerGroup = listener.events.groupingBy { it.threadGroup }.eachCount()
        assertEquals(mapOf("Browse steady" to 4, "Browse spike" to 1), samplesPerGroup)
    }

    @Test
    fun `engine reports scenario errors`() {
        val tree = plan(scenarios = {})
        val engine = StandardJMeterEngine()
        engine.configure(JMeter.convertSubTree(tree, false))
        val e = assertThrows<JMeterEngineException> { engine.runTest() }
        assertEquals("No scenario is enabled. Enable the scenario you want to run.", e.message)
    }
}
