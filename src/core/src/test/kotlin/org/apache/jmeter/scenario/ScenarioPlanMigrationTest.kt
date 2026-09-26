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
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.test.samplers.CollectSamplesListener
import org.apache.jmeter.test.samplers.ThreadSleep
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.threads.AbstractThreadGroup
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
            listOf(ScenariosSection::class, ThreadGroupsSection::class, ListenersSection::class, ProfilesSection::class, TestFragmentsSection::class),
            plan.map { it::class }
        ) { "No Non-Test Elements section without such elements" }
        assertEquals(listOf(moduleController, fragmentModule), children(planTree, plan[4]).map { it as Any }) {
            "Fragment content moves directly into the Test Fragments section"
        }
        assertSame(listener, children(planTree, plan[2]).single())
        val shared = children(planTree, plan[3]).single() as SharedProfile
        assertSame(variables, children(planTree.getTree(plan[3]), shared).single()) { "Test-level configuration is shared" }

        val threadGroups = children(planTree, plan[1]).map { it as ThreadGroup }
        assertEquals(listOf("Browse", "Old"), threadGroups.map { it.name })
        val browse = threadGroups[0]
        assertTrue(browse.getPropertyAsString(AbstractThreadGroup.NUM_THREADS).isEmpty()) { "Workload moves to the scenario" }
        assertFalse(browse.isValidationStopOnError) { "Start next loop is not a stop" }

        val scenario = children(planTree, plan[0]).single() as Scenario
        assertTrue(scenario.isEnabled)
        val workloads = scenario.workloads
        assertEquals(listOf("Browse", "Old"), workloads.map { it.name })
        assertEquals(listOf(true, false), workloads.map { it.isEnabled })
        assertEquals(browse.threadGroupId, workloads[0].threadGroupId)
        assertEquals(AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP, workloads[0].getPropertyAsString(AbstractThreadGroup.ON_SAMPLE_ERROR))

        assertEquals(
            listOf("Test Plan", "Test Plan", (plan[1] as ThreadGroupsSection).name, "Browse", "Step"),
            (moduleController.getProperty("ModuleController.node_path") as CollectionProperty).map { it.stringValue }
        )
        assertEquals(
            listOf("Test Plan", "Test Plan", (plan[4] as TestFragmentsSection).name, "Step"),
            (fragmentModule.getProperty("ModuleController.node_path") as CollectionProperty).map { it.stringValue }
        )
        assertFalse(ScenarioPlanMigration.needsMigration(migrated))
    }

    @Test
    fun `fragment whose content clashes with other fragments stays wrapped`() {
        val first = TestFragmentController().apply { name = "Fragment A" }
        val second = TestFragmentController().apply { name = "Fragment B" }
        val tree = testTree {
            TestPlan::class {
                first { GenericController::class { name = "Login" } }
                second { GenericController::class { name = "Login" } }
            }
        }
        val migrated = ScenarioPlanMigration.migrate(tree)
        val planTree = migrated.getTree(migrated.array[0])
        val fragments = planTree.list().filterIsInstance<TestFragmentsSection>().single()
        assertEquals(
            listOf("Login", "Fragment B"),
            planTree.getTree(fragments).list().map { (it as org.apache.jmeter.testelement.TestElement).name }
        )
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
