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
import org.apache.jmeter.control.GenericController
import org.apache.jmeter.control.LoopController
import org.apache.jmeter.control.TestFragmentController
import org.apache.jmeter.engine.PreCompiler
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.test.samplers.CollectSamplesListener
import org.apache.jmeter.test.samplers.ThreadSleep
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.JMeterContextService
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jorphan.collections.HashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

class ScenarioPlanMigrationTest : JMeterTestCase() {
    private val listener = CollectSamplesListener()
    private val variables = Arguments().apply { name = "Variables" }
    private val fragment = TestFragmentController().apply {
        name = "Fragment"
        isEnabled = false // as created by the GUI
    }
    private val fragmentModule = GenericController().apply {
        name = "Fragment module"
        setProperty(CollectionProperty("ModuleController.node_path", listOf("Test Plan", "Test Plan", "Fragment", "Step")))
    }
    private val moduleController = GenericController().apply {
        name = "Module"
        setProperty(CollectionProperty("ModuleController.node_path", listOf("Test Plan", "Test Plan", "Browse", "Step")))
    }

    private fun legacyPlan() = testTree {
        TestPlan::class {
            +variables
            +listener
            ThreadGroup::class {
                name = "Browse"
                numThreads = 2
                rampUp = 0
                setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP)
                setSamplerController(LoopController().apply { loops = 3 })
                ThreadSleep::class { duration = 0.seconds }
            }
            ThreadGroup::class {
                name = "Old"
                isEnabled = false
                ThreadSleep::class { duration = 0.seconds }
            }
            fragment {
                +moduleController
                +fragmentModule
            }
        }
    }

    private fun children(tree: HashTree, parent: Any = tree.array[0]): List<Any> = tree.getTree(parent).list().toList()

    @Test
    fun `legacy plan is organised in sections`() {
        val migrated = ScenarioPlanMigration.migrate(legacyPlan())
        val plan = children(migrated)
        val planTree = migrated.getTree(migrated.array[0])

        assertEquals(
            listOf(ListenersSection::class, ScenariosSection::class, ProfilesSection::class, TestFragmentsSection::class, ThreadGroupsSection::class),
            plan.map { it::class }
        ) { "No Non-Test Elements section without such elements" }
        assertSame(fragment, children(planTree, plan[3]).single()) {
            "Test Fragments stay wrapped: Include Controllers use the first fragment of a file"
        }
        assertSame(listener, children(planTree, plan[0]).single())
        val shared = children(planTree, plan[2]).single() as SharedProfile
        assertSame(variables, children(planTree.getTree(plan[2]), shared).single()) { "Test-level configuration is shared" }

        val threadGroups = children(planTree, plan[4]).map { it as ThreadGroup }
        assertEquals(listOf("Browse", "Old"), threadGroups.map { it.name })
        val browse = threadGroups[0]
        assertTrue(browse.getPropertyAsString(AbstractThreadGroup.NUM_THREADS).isEmpty()) { "Workload moves to the scenario" }
        assertFalse(browse.isValidationStopOnError) { "Start next loop is not a stop" }

        val scenario = children(planTree, plan[1]).single() as Scenario
        assertTrue(scenario.isEnabled)
        val workloads = scenario.workloads
        assertEquals(listOf("Browse", "Old"), workloads.map { it.name })
        assertEquals(listOf(true, false), workloads.map { it.isEnabled })
        assertEquals(browse.threadGroupId, workloads[0].threadGroupId)
        assertEquals(AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP, workloads[0].getPropertyAsString(AbstractThreadGroup.ON_SAMPLE_ERROR))

        assertEquals(
            listOf("Test Plan", "Test Plan", (plan[4] as ThreadGroupsSection).name, "Browse", "Step"),
            (moduleController.getProperty("ModuleController.node_path") as CollectionProperty).map { it.stringValue }
        )
        assertEquals(
            listOf("Test Plan", "Test Plan", (plan[3] as TestFragmentsSection).name, "Fragment", "Step"),
            (fragmentModule.getProperty("ModuleController.node_path") as CollectionProperty).map { it.stringValue }
        )
        assertFalse(ScenarioPlanMigration.needsMigration(migrated))
    }

    @Test
    fun `old plans keep thread group listeners, open model settings and consecutive runs`() {
        val threadGroupListener = org.apache.jmeter.reporters.ResultCollector().apply { name = "Browse results" }
        val tree = testTree {
            TestPlan::class {
                isSerialized = true
                org.apache.jmeter.threads.openmodel.OpenModelThreadGroup::class {
                    name = "Arrivals"
                    scheduleString = "rate(2/sec) even_arrivals(1 min)"
                    +threadGroupListener
                }
            }
        }
        val migrated = ScenarioPlanMigration.migrate(tree)
        val plan = migrated.array[0] as TestPlan
        val planTree = migrated.getTree(plan)
        val threadGroupsSection = planTree.list().filterIsInstance<ThreadGroupsSection>().single()
        val threadGroup = planTree.getTree(threadGroupsSection).list().single() as ThreadGroup
        assertSame(threadGroupListener, planTree.getTree(threadGroupsSection).getTree(threadGroup).list().single()) {
            "A listener inside a thread group stays in that thread group"
        }
        val scenario = planTree.getTree(planTree.list().filterIsInstance<ScenariosSection>().single())
            .list().single() as Scenario
        val workload = scenario.workloads.single()
        assertEquals(ThreadGroup.MODEL_OPEN, workload.getPropertyAsString(ThreadGroup.MODEL))
        assertEquals("rate(2/sec) even_arrivals(1 min)", workload.getPropertyAsString(ThreadGroup.OPEN_MODEL_SCHEDULE))
        assertTrue(scenario.isRunConsecutively) { "Running thread groups one after another is a scenario setting" }
        assertFalse(plan.isSerialized)
    }

    @Test
    fun `plans with sections in an earlier order or outdated fixed names are normalized`() {
        val shared = SharedProfile().apply { name = "Shared" }
        val tree = testTree {
            TestPlan::class {
                ThreadGroupsSection::class {}
                ScenariosSection::class {}
                ProfilesSection::class { +shared }
                ListenersSection::class {}
                TestFragmentsSection::class {}
            }
        }
        assertTrue(ScenarioPlanMigration.needsNormalizing(tree))
        val normalized = ScenarioPlanMigration.normalize(tree)
        assertEquals(
            listOf(ListenersSection::class, ScenariosSection::class, ProfilesSection::class, TestFragmentsSection::class, ThreadGroupsSection::class),
            children(normalized).map { it::class }
        )
        assertFalse(shared.name == "Shared") { "The fixed Shared Profile gets its current name" }
        assertFalse(ScenarioPlanMigration.needsNormalizing(normalized))
    }

    @Test
    fun `thread groups with the same name get unique names and ids`() {
        val tree = testTree {
            TestPlan::class {
                ThreadGroup::class { name = "Browse" }
                ThreadGroup::class { name = "Browse" }
            }
        }
        val migrated = ScenarioPlanMigration.migrate(tree)
        val planTree = migrated.getTree(migrated.array[0])
        val threadGroups = planTree.getTree(planTree.list().filterIsInstance<ThreadGroupsSection>().single())
            .list().map { it as ThreadGroup }
        assertEquals(listOf("Browse", "Browse (2)"), threadGroups.map { it.name })
        assertEquals(listOf("browse", "browse-2"), threadGroups.map { it.threadGroupId })
        val scenario = planTree.getTree(planTree.list().filterIsInstance<ScenariosSection>().single()).list().single() as Scenario
        assertEquals(listOf("browse", "browse-2"), scenario.workloads.map { it.threadGroupId }) {
            "Each row runs its own thread group"
        }
    }

    @Test
    fun `fragment run as a whole by a Module Controller stays wrapped`() {
        val fragment = TestFragmentController().apply { name = "Checkout" }
        val module = GenericController().apply {
            name = "Run checkout"
            setProperty(CollectionProperty("ModuleController.node_path", listOf("Test Plan", "Test Plan", "Checkout")))
        }
        val tree = testTree {
            TestPlan::class {
                ThreadGroup::class { +module }
                fragment { GenericController::class { name = "Pay" } }
            }
        }
        val migrated = ScenarioPlanMigration.migrate(tree)
        val planTree = migrated.getTree(migrated.array[0])
        val fragments = planTree.list().filterIsInstance<TestFragmentsSection>().single()
        assertSame(fragment, planTree.getTree(fragments).list().single()) {
            "The Module Controller needs the fragment itself, not its content"
        }
        assertEquals(
            listOf("Test Plan", "Test Plan", fragments.name, "Checkout"),
            (module.getProperty("ModuleController.node_path") as CollectionProperty).map { it.stringValue }
        )
    }

    @Test
    fun `workbench content of very old plans is kept`() {
        val knowledge = org.apache.jmeter.ai.knowledge.BreakTestAiKnowledge().apply { name = "Notes" }
        val saved = org.apache.jmeter.config.ConfigTestElement().apply { name = "Saved defaults" }
        val tree = org.apache.jorphan.collections.ListedHashTree()
        tree.add(TestPlan("Test Plan"))
        tree.add(org.apache.jmeter.testelement.WorkBench().apply { name = "WorkBench" }).apply {
            add(knowledge)
            add(saved)
        }

        val migrated = ScenarioPlanMigration.migrate(tree)
        assertEquals(2, migrated.array.size) { "The WorkBench next to the test plan must survive migration" }

        val model = org.apache.jmeter.gui.tree.JMeterTreeModel(TestPlan("Root"))
        org.apache.jmeter.gui.GuiPackage.initInstance(org.apache.jmeter.gui.tree.JMeterTreeListener(model), model)
        try {
            model.addSubTree(migrated, model.root as org.apache.jmeter.gui.tree.JMeterTreeNode, false)
            for (element in listOf(knowledge, saved)) {
                assertTrue(model.getNodeOf(element) != null) { "${element.name} from the WorkBench must not be lost" }
            }
            // Loading moves WorkBench content that is not a non-test element into a disabled fragment
            val fragments = model.getNodesOfType(TestFragmentsSection::class.java).single()
            assertEquals(
                listOf("WorkBench Test Fragment"),
                (0 until fragments.childCount).map { (fragments.getChildAt(it) as org.apache.jmeter.gui.tree.JMeterTreeNode).name }
            )
        } finally {
            val field = org.apache.jmeter.gui.GuiPackage::class.java.getDeclaredField("guiPack")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    @Test
    fun `migration keeps which value variables start with`() {
        fun udv(value: String) = org.apache.jmeter.config.Arguments().apply { addArgument("host", value) }
        fun migrate(sharedLast: Boolean): Pair<SharedProfile, List<String>> {
            val tree = testTree {
                TestPlan::class {
                    if (!sharedLast) {
                        +udv("shared.example")
                    }
                    ThreadGroup::class { +udv("group.example") }
                    if (sharedLast) {
                        +udv("shared.example")
                    }
                }
            }
            val changed = mutableListOf<String>()
            val migrated = ScenarioPlanMigration.migrate(tree, changed)
            val planTree = migrated.getTree(migrated.array[0])
            val profiles = planTree.list().filterIsInstance<ProfilesSection>().single()
            return planTree.getTree(profiles).list().filterIsInstance<SharedProfile>().single() to changed
        }
        val (sharedFirst, noChange) = migrate(sharedLast = false)
        assertFalse(sharedFirst.isOverridingThreadGroupVariables) { "The thread group value won in the old plan" }
        assertEquals(listOf<String>(), noChange)
        val (sharedAfter, stillNoChange) = migrate(sharedLast = true)
        assertTrue(sharedAfter.isOverridingThreadGroupVariables) { "The later test plan value won in the old plan" }
        assertEquals(listOf<String>(), stillNoChange)
    }

    @Test
    fun `the last definition of a variable decides, also when it is defined before and after a thread group`() {
        fun udv(vararg pairs: Pair<String, String>) = Arguments().apply { pairs.forEach { (n, v) -> addArgument(n, v) } }
        fun migrate(tree: HashTree): Pair<SharedProfile, List<String>> {
            val changed = mutableListOf<String>()
            val migrated = ScenarioPlanMigration.migrate(tree, changed)
            val planTree = migrated.getTree(migrated.array[0])
            val profiles = planTree.list().filterIsInstance<ProfilesSection>().single()
            return planTree.getTree(profiles).list().filterIsInstance<SharedProfile>().single() to changed
        }
        val (shared, changed) = migrate(
            testTree {
                TestPlan::class {
                    +udv("host" to "before.example")
                    ThreadGroup::class { +udv("host" to "group.example") }
                    +udv("host" to "after.example")
                }
            }
        )
        assertTrue(shared.isOverridingThreadGroupVariables) { "The definition after the thread group won in the old plan" }
        assertEquals(listOf<String>(), changed)

        val (mixed, reported) = migrate(
            testTree {
                TestPlan::class {
                    +udv("host" to "before.example")
                    ThreadGroup::class { +udv("host" to "group.example", "port" to "8080") }
                    +udv("port" to "443")
                }
            }
        )
        assertFalse(mixed.isOverridingThreadGroupVariables)
        assertEquals(listOf("port"), reported) { "A value that one setting cannot keep is reported" }
    }

    @Test
    fun `module controllers keep their target when thread groups with the same name are renamed`() {
        val module = GenericController().apply {
            name = "Run step"
            setProperty(CollectionProperty("ModuleController.node_path", listOf("Test Plan", "Test Plan", "Browse", "Step")))
        }
        val tree = testTree {
            TestPlan::class {
                ThreadGroup::class {
                    name = "Browse"
                    +module
                }
                ThreadGroup::class {
                    name = "Browse"
                    GenericController::class { name = "Step" }
                }
            }
        }
        val migrated = ScenarioPlanMigration.migrate(tree)
        val planTree = migrated.getTree(migrated.array[0])
        val threadGroups = planTree.list().filterIsInstance<ThreadGroupsSection>().single()
        assertEquals(
            listOf("Test Plan", "Test Plan", threadGroups.name, "Browse (2)", "Step"),
            (module.getProperty("ModuleController.node_path") as CollectionProperty).map { it.stringValue }
        ) { "The path points to the thread group that has the step, now named Browse (2)" }
    }

    @Test
    fun `variables in disabled parts of an old plan do not decide which value wins`() {
        fun plan() = testTree {
            TestPlan::class {
                +Arguments().apply { addArgument("host", "before.example") }
                ThreadGroup::class {
                    name = "First"
                    +Arguments().apply { addArgument("host", "group.example") }
                }
                +Arguments().apply { addArgument("host", "after.example") }
                ThreadGroup::class {
                    name = "Second"
                    GenericController::class {
                        isEnabled = false
                        +Arguments().apply { addArgument("host", "disabled.example") }
                    }
                }
            }
        }
        fun host(tree: HashTree): String? {
            ScenarioResolver.resolve(JMeter.convertSubTree(tree, false)).traverse(PreCompiler())
            return JMeterContextService.getContext().variables.get("host")
        }
        assertEquals("after.example", host(plan())) { "The old plan" }
        val changed = mutableListOf<String>()
        val migrated = ScenarioPlanMigration.migrate(plan(), changed)
        assertEquals(listOf<String>(), changed)
        assertEquals("after.example", host(migrated))
    }

    @Test
    fun `migrated plan runs like the original`() {
        val migrated = ScenarioPlanMigration.migrate(legacyPlan())
        StandardJMeterEngine().apply {
            configure(JMeter.convertSubTree(migrated, false))
            runTest()
            awaitTermination(Duration.ofSeconds(10))
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (listener.events.size < 6 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        Thread.sleep(200)
        assertEquals(mapOf("Browse" to 6), listener.events.groupingBy { it.threadGroup }.eachCount())
    }
}
