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

package org.apache.jmeter.control.gui

import org.apache.jmeter.JMeter
import org.apache.jmeter.control.LoopController
import org.apache.jmeter.control.ModuleController
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.sampler.DebugSampler
import org.apache.jmeter.scenario.ListenersSection
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.ScenarioWorkload
import org.apache.jmeter.scenario.TestFragmentsSection
import org.apache.jmeter.scenario.ThreadGroupsSection
import org.apache.jmeter.test.samplers.CollectSamplesListener
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class MoveToTestFragmentsTest : JMeterTestCase() {
    private val model = JMeterTreeModel()

    private fun node(type: Class<*>) = model.getNodesOfType(type).single()

    private fun add(element: TestElement, parent: JMeterTreeNode): JMeterTreeNode =
        JMeterTreeNode(element, model).also { model.insertNodeInto(it, parent, parent.childCount) }

    @Test
    fun `transaction moves to test fragments and a module controller runs it in its place`() {
        val threadGroup = ThreadGroup().apply {
            name = "Browse"
            threadGroupId = "browse-id"
            setSamplerController(LoopController().apply { loops = 1 })
        }
        val threadGroupNode = add(threadGroup, node(ThreadGroupsSection::class.java))
        val before = add(DebugSampler().apply { name = "Before" }, threadGroupNode)
        val transactionNode = add(TransactionController().apply { name = "Login" }, threadGroupNode)
        add(DebugSampler().apply { name = "Login page" }, transactionNode)
        val after = add(DebugSampler().apply { name = "After" }, threadGroupNode)
        (node(Scenario::class.java).testElement as Scenario).setWorkloads(
            listOf(
                ScenarioWorkload().apply {
                    name = "Browse"
                    threadGroupId = "browse-id"
                    copyWorkloadFrom(threadGroup)
                    setProperty(org.apache.jmeter.threads.AbstractThreadGroup.NUM_THREADS, 1)
                }
            )
        )
        val listener = CollectSamplesListener()
        add(listener, node(ListenersSection::class.java))

        val fragments = node(TestFragmentsSection::class.java)
        val moduleNode = MoveToTestFragments.moveToTestFragments(model, transactionNode, fragments)

        assertSame(fragments, transactionNode.parent) { "The transaction is moved to Test Fragments" }
        assertEquals(listOf(before, moduleNode, after), (0 until threadGroupNode.childCount).map { threadGroupNode.getChildAt(it) }) {
            "The Module Controller takes the place of the transaction"
        }
        val module = moduleNode.testElement as ModuleController
        assertEquals("Login", module.name)
        assertSame(transactionNode, module.selectedNode)

        val tree = JMeter.convertSubTree(model.testPlan, false)
        StandardJMeterEngine().apply {
            configure(tree)
            runTest()
            awaitTermination(Duration.ofSeconds(10))
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (listener.events.none { it.result.sampleLabel == "After" } && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        val labels = listener.events.map { it.result.sampleLabel }
        assertTrue(labels.containsAll(listOf("Before", "Login page", "After"))) {
            "The thread group still runs the transaction through the Module Controller: $labels"
        }
    }

    @Test
    fun `moved transaction gets a unique name in test fragments`() {
        val fragments = node(TestFragmentsSection::class.java)
        add(TransactionController().apply { name = "Login" }, fragments)
        val threadGroupNode = add(ThreadGroup().apply { name = "Browse" }, node(ThreadGroupsSection::class.java))
        val transactionNode = add(TransactionController().apply { name = "Login" }, threadGroupNode)

        val moduleNode = MoveToTestFragments.moveToTestFragments(model, transactionNode, fragments)

        assertEquals("Login (2)", transactionNode.name)
        assertEquals("Login", moduleNode.name)
        assertSame(transactionNode, (moduleNode.testElement as ModuleController).selectedNode)
    }
}
