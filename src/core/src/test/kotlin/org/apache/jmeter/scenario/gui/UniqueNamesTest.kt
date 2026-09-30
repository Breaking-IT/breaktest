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

package org.apache.jmeter.scenario.gui

import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeListener
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.scenario.Profile
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.ScenarioWorkload
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class UniqueNamesTest : JMeterTestCase() {
    private val model = JMeterTreeModel()
    private val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode

    private fun added(element: org.apache.jmeter.testelement.TestElement): JMeterTreeNode = model.addComponent(element, plan)

    @Test
    fun `thread group ids are readable`() {
        assertEquals("checkout-flow", AbstractThreadGroup.readableId("Checkout flow"))
        assertEquals("uc1-browse-products", AbstractThreadGroup.readableId(" UC1_Browse products! "))
        assertEquals("thread-group", AbstractThreadGroup.readableId("  "))
    }

    @Test
    fun `thread groups get unique names and readable unique ids`() {
        val first = ThreadGroup().apply { name = "Browse" }
        val second = ThreadGroup().apply { name = "Browse" }
        added(first)
        added(second)
        assertEquals(listOf("Browse", "Browse (2)"), listOf(first.name, second.name))
        assertEquals(listOf("browse", "browse-2"), listOf(first.threadGroupId, second.threadGroupId))
    }

    @Test
    fun `renaming a clashing insert does not send change events for a row the tree does not know yet`() {
        added(ThreadGroup().apply { name = "Browse" })
        val events = mutableListOf<String>()
        model.addTreeModelListener(object : javax.swing.event.TreeModelListener {
            override fun treeNodesChanged(e: javax.swing.event.TreeModelEvent) {
                events += "changed"
            }

            override fun treeNodesInserted(e: javax.swing.event.TreeModelEvent) {
                events += "inserted " + (e.children.single() as JMeterTreeNode).name
            }

            override fun treeNodesRemoved(e: javax.swing.event.TreeModelEvent) {
                events += "removed"
            }

            override fun treeStructureChanged(e: javax.swing.event.TreeModelEvent) {
                events += "structure"
            }
        })

        added(ThreadGroup().apply { name = "Browse" })

        assertEquals(listOf("inserted Browse (2)"), events) {
            "A change event before the insertion is announced corrupts the tree: rows go blank and selection stalls"
        }
    }

    @Test
    fun `a copied thread group gets its own id and a moved one keeps it`() {
        val original = ThreadGroup().apply { name = "Browse" }
        val node = added(original)
        val copy = original.clone() as ThreadGroup
        added(copy)
        assertEquals("browse-2", copy.threadGroupId)

        model.removeNodeFromParent(node)
        added(original)
        assertEquals("browse", original.threadGroupId) { "Scenario rows keep referring to a moved thread group" }
    }

    @Test
    fun `profiles and scenarios get unique names, also when renamed`() {
        val acceptance = Profile("acceptance")
        val other = Profile("production")
        added(acceptance)
        val otherNode = added(other)
        added(Profile("acceptance")).also { assertEquals("acceptance (2)", it.name) }
        val defaultScenario = model.getNodesOfType(Scenario::class.java).single().name // a new plan has one scenario
        added(Scenario(defaultScenario)).also { assertEquals("$defaultScenario (2)", it.name) }

        other.name = "acceptance"
        UniqueNames.apply(model, otherNode)
        assertEquals("acceptance (3)", other.name) { "Renaming to a used name keeps names unique" }
    }

    @Test
    fun `scenario rows follow a renamed profile`() {
        val scenario = model.getNodesOfType(Scenario::class.java).single().testElement as Scenario
        scenario.setWorkloads(
            listOf(
                ScenarioWorkload().apply { profile = "acceptance" },
                ScenarioWorkload().apply { profile = "\${__P(profile,acceptance)}" },
            )
        )
        UniqueNames.profileRenamed(model, "acceptance", "acc")
        assertEquals(listOf("acc", "\${__P(profile,acceptance)}"), scenario.workloads.map { it.profile }) {
            "Rows naming the profile follow; expressions are left as written"
        }
    }

    @Test
    fun `a thread group without id gets one no other thread group has`() {
        added(ThreadGroup().apply { name = "Browse" })
        val withoutId = ThreadGroup().apply { name = "Browse" }
        assertNotEquals("browse", UniqueNames.threadGroupId(model, withoutId))
    }

    @Test
    fun `a disabled profile is not the default of a run`() {
        added(Profile("acceptance").apply { isDefault = true })
        added(Profile("production"))
        val previous = GuiPackage.getInstance()
        try {
            GuiPackage.initInstance(JMeterTreeListener(model), model)
            model.getNodesOfType(Profile::class.java).first { it.name == "acceptance" }.isEnabled = false
            assertEquals("production", ScenarioWorkloadGui.defaultProfileName())
        } finally {
            val field = GuiPackage::class.java.getDeclaredField("guiPack")
            field.isAccessible = true
            field.set(null, previous)
        }
    }
}
