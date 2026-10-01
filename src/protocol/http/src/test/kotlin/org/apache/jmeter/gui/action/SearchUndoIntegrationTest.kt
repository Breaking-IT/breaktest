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

package org.apache.jmeter.gui.action

import io.mockk.every
import io.mockk.mockk
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.control.gui.TransactionControllerGui
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.MainFrame
import org.apache.jmeter.gui.RowField
import org.apache.jmeter.gui.SearchArea
import org.apache.jmeter.gui.tree.JMeterTreeListener
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.protocol.http.control.Header
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.threads.gui.ThreadGroupGui
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath

class SearchUndoIntegrationTest {
    private fun withPlan(test: (GuiPackage, JMeterTreeModel, JMeterTreeNode) -> Unit) {
        val field = GuiPackage::class.java.getDeclaredField("guiPack").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            SwingUtilities.invokeAndWait {
                val model = JMeterTreeModel(
                    (org.apache.jmeter.control.gui.TestPlanGui().createTestElement() as TestPlan).apply { name = "Plan" }
                )
                val tree = JTree(model)
                val listener = JMeterTreeListener(model).apply { setJTree(tree) }
                GuiPackage.initInstance(listener, model)
                val gui = GuiPackage.getInstance()
                val frame = mockk<MainFrame>(relaxed = true)
                every { frame.tree } returns tree
                gui.mainFrame = frame
                val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
                val group = JMeterTreeNode(
                    ThreadGroup().apply {
                        name = "Group"
                        setProperty(TestElement.GUI_CLASS, ThreadGroupGui::class.java.name)
                    },
                    model
                )
                model.insertNodeInto(group, plan, 0)
                val controller = JMeterTreeNode(
                    TransactionController().apply {
                        name = "Transaction"
                        setProperty(TestElement.GUI_CLASS, TransactionControllerGui::class.java.name)
                    },
                    model
                )
                model.insertNodeInto(controller, group, 0)
                for (name in listOf("First", "Second")) {
                    val sampler = HTTPSamplerProxy().apply {
                        this.name = name
                        setProperty(TestElement.GUI_CLASS, HttpTestSampleGui::class.java.name)
                        domain = "example.test"
                        path = "/original"
                        addArgument("remove", "original")
                        addArgument("keep", "a%20b")
                        setNativeHeaders(listOf(Header("X-Remove", "original"), Header("X-Keep", "keep")))
                    }
                    model.insertNodeInto(JMeterTreeNode(sampler, model), controller, controller.childCount)
                }
                listener.setSelectionPathWithoutEdit(TreePath(controller.getChildAt(0).let { it as JMeterTreeNode }.path))
                // Initialize every element through its editor, just like a loaded GUI plan.
                for (node in model.getNodesOfType(TestElement::class.java).filter { !it.isRoot }) {
                    val editor = gui.getGui(node.testElement)
                    editor.clearGui()
                    editor.configure(node.testElement)
                    editor.modifyTestElement(node.testElement)
                }
                gui.refreshCurrentGui()
                gui.updateCurrentNode()
                gui.setDirty(false)
                gui.addUndoHistory("Fixture")
                test(gui, model, controller)
            }
        } finally {
            field.set(null, previous)
        }
    }

    private fun samplers(model: JMeterTreeModel): List<HTTPSamplerProxy> =
        model.getNodesOfType(HTTPSamplerProxy::class.java).map { it.testElement as HTTPSamplerProxy }

    @Test
    fun `row removal undoes and redoes both requests without losing order or settings`() = withPlan { gui, model, _ ->
        val nodes = model.getNodesOfType(HTTPSamplerProxy::class.java)
        val rows = SearchTreeDialog.matchingRows(
            nodes, SearchTreeDialog.SearchScope(null),
            setOf(SearchArea.HEADERS, SearchArea.PARAMETERS), RawTextSearcher(true, "original"), RowField.VALUE
        )
        val session = gui.testPlanSession
        assertEquals(4, SearchTreeDialog.editWithUndo(gui, "Remove rows", { count -> count > 0 }) { SearchTreeDialog.removeRows(rows) })
        assertTrue(samplers(model).all { it.arguments.argumentCount == 1 && it.nativeHeaderList.size == 1 })
        assertTrue(gui.isDirty)
        gui.undo()
        assertTrue(!gui.isDirty)
        assertEquals(session, gui.testPlanSession)
        assertEquals(listOf("First", "Second"), samplers(model).map { it.name })
        for (sampler in samplers(model)) {
            assertEquals(listOf("remove", "keep"), (0 until sampler.arguments.argumentCount).map { sampler.arguments.getArgument(it).name })
            assertEquals("a%20b", sampler.arguments.getArgument(1).value)
            assertEquals(listOf("X-Remove", "X-Keep"), sampler.nativeHeaderList.map { it.name })
            assertEquals("/original", sampler.path)
        }
        gui.redo()
        assertTrue(gui.isDirty)
        gui.updateCurrentNode()
        assertTrue(samplers(model).all { it.arguments.argumentCount == 1 && it.nativeHeaderList.single().name == "X-Keep" })
    }

    @Test
    fun `replace all is one undo step and open editor cannot restore old values`() = withPlan { gui, model, _ ->
        val changes = model.getNodesOfType(HTTPSamplerProxy::class.java).flatMap {
            SearchTreeDialog.replacementChanges(
                it, Pattern.compile("original"), "changed", false,
                setOf(SearchArea.PATH, SearchArea.HEADERS, SearchArea.PARAMETERS), RowField.ALL
            )
        }
        assertEquals(6, SearchTreeDialog.editWithUndo(gui, "Replace all", { count -> count > 0 }) { SearchTreeDialog.applyChanges(changes) })
        gui.updateCurrentNode()
        assertTrue(samplers(model).all { it.path == "/changed" })
        gui.undo()
        assertTrue(samplers(model).all { it.path == "/original" && it.arguments.getArgument(0).value == "original" })
        gui.redo()
        assertTrue(samplers(model).all { it.path == "/changed" && it.nativeHeaderList[0].value == "changed" })
    }

    @Test
    fun `element removal and controller cleanup restore together`() = withPlan { gui, model, controller ->
        SearchTreeDialog.editWithUndo(gui, "Remove elements and cleanup", { count -> count > 0 }) {
            model.getNodesOfType(HTTPSamplerProxy::class.java).forEach { SearchTreeDialog.removeMatchingNode(gui, it) }
            val empty = SearchTreeDialog.emptiedControllers(setOf(controller), model.root as JMeterTreeNode)
            assertEquals(listOf(controller), empty)
            empty.forEach { SearchTreeDialog.removeMatchingNode(gui, it) }
            3
        }
        assertTrue(samplers(model).isEmpty())
        gui.undo()
        assertEquals(1, model.getNodesOfType(TransactionController::class.java).size)
        assertEquals(listOf("First", "Second"), samplers(model).map { it.name })
        gui.redo()
        assertTrue(model.getNodesOfType(TransactionController::class.java).isEmpty())
        assertTrue(samplers(model).isEmpty())
    }

    @Test
    fun `column restricted replacement preserves the other column through undo`() = withPlan { gui, model, _ ->
        val node = model.getNodesOfType(HTTPSamplerProxy::class.java).first()
        val sampler = node.testElement as HTTPSamplerProxy
        val nameChanges = SearchTreeDialog.replacementChanges(
            node, Pattern.compile("remove", Pattern.CASE_INSENSITIVE), "renamed", false,
            setOf(SearchArea.HEADERS, SearchArea.PARAMETERS), RowField.NAME
        )
        assertEquals(
            2,
            SearchTreeDialog.editWithUndo(gui, "Rename rows", { count -> count > 0 }) {
                SearchTreeDialog.applyChanges(nameChanges)
            }
        )
        assertEquals("renamed", sampler.arguments.getArgument(0).name)
        assertEquals("original", sampler.arguments.getArgument(0).value)
        assertEquals("X-renamed", sampler.nativeHeaderList[0].name)
        gui.undo()
        assertEquals("remove", samplers(model).first().arguments.getArgument(0).name)
        gui.redo()
        assertEquals("renamed", samplers(model).first().arguments.getArgument(0).name)
    }

    @Test
    fun `HTTP defaults rows remove and undo with defaults editor open`() = withPlan { gui, model, controller ->
        val defaults = org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui().createTestElement()
        val args = org.apache.jmeter.config.Arguments().apply {
            addArgument(org.apache.jmeter.protocol.http.util.HTTPArgument("remove", "target"))
            addArgument(org.apache.jmeter.protocol.http.util.HTTPArgument("keep", "value"))
        }
        defaults.setProperty(org.apache.jmeter.testelement.property.TestElementProperty("HTTPsampler.Arguments", args))
        defaults.setProperty(
            org.apache.jmeter.testelement.property.CollectionProperty(
                HTTPSamplerProxy.HEADERS, listOf(Header("X-Remove", "target"), Header("X-Keep", "value"))
            )
        )
        val node = JMeterTreeNode(defaults, model)
        model.insertNodeInto(node, controller.parent as JMeterTreeNode, 0)
        gui.treeListener.setSelectionPathWithoutEdit(TreePath(node.path))
        gui.refreshCurrentGui()
        gui.updateCurrentNode()
        val rows = SearchTreeDialog.matchingRows(
            listOf(node), SearchTreeDialog.SearchScope(null), setOf(SearchArea.HEADERS, SearchArea.PARAMETERS),
            RawTextSearcher(true, "target"), RowField.VALUE
        )
        assertEquals(
            2,
            SearchTreeDialog.editWithUndo(gui, "Remove defaults rows", { count -> count > 0 }) {
                SearchTreeDialog.removeRows(rows)
            }
        )
        gui.updateCurrentNode()
        assertEquals(2, org.apache.jmeter.gui.RemovableRow.forElement(gui.currentElement).size)
        gui.undo()
        assertEquals(4, org.apache.jmeter.gui.RemovableRow.forElement(gui.currentElement).size)
        gui.redo()
        gui.updateCurrentNode()
        assertEquals(2, org.apache.jmeter.gui.RemovableRow.forElement(gui.currentElement).size)
    }

    @Test
    fun `duplicate group scope stays on the selected occurrence across undo redo`() = withPlan { gui, model, controller ->
        val firstGroup = controller.parent as JMeterTreeNode
        val secondGroup = JMeterTreeNode(firstGroup.testElement.clone() as TestElement, model)
        model.insertNodeInto(secondGroup, firstGroup.parent as JMeterTreeNode, 1)
        val secondRequest = JMeterTreeNode(samplers(model).first().clone() as TestElement, model)
        model.insertNodeInto(secondRequest, secondGroup, 0)
        val firstOption = SearchTreeDialog.ScopeOption("Group", firstGroup)
        val secondOption = SearchTreeDialog.ScopeOption("Group", secondGroup)
        val changes = SearchTreeDialog.replacementChanges(secondRequest, Pattern.compile("original"), "changed", false)
        SearchTreeDialog.editWithUndo(gui, "Replace in second group", { count -> count > 0 }) {
            SearchTreeDialog.applyChanges(changes)
        }
        gui.undo()
        var groups = model.getNodesOfType(ThreadGroup::class.java)
        assertEquals(listOf("Group", "Group"), groups.map { it.name })
        assertTrue(firstOption.resolve(model) === groups[0])
        assertTrue(secondOption.resolve(model) === groups[1])
        gui.redo()
        groups = model.getNodesOfType(ThreadGroup::class.java)
        assertTrue(firstOption.resolve(model) === groups[0])
        assertTrue(secondOption.resolve(model) === groups[1])
        val rows = SearchTreeDialog.matchingRows(
            model.getNodesOfType(HTTPSamplerProxy::class.java), SearchTreeDialog.SearchScope(secondOption.resolve(model)),
            setOf(SearchArea.PARAMETERS), RawTextSearcher(true, "keep"), RowField.NAME
        )
        assertEquals(
            1,
            SearchTreeDialog.editWithUndo(gui, "Remove second group row", { count -> count > 0 }) {
                SearchTreeDialog.removeRows(rows)
            }
        )
        assertEquals(listOf(2, 2, 1), samplers(model).map { it.arguments.argumentCount })
        gui.undo()
        assertEquals(listOf(2, 2, 2), samplers(model).map { it.arguments.argumentCount })
        assertTrue(secondOption.resolve(model) === model.getNodesOfType(ThreadGroup::class.java)[1])
    }

    @Test
    fun `empty selection adds no undo step and preserves redo`() = withPlan { gui, model, _ ->
        val node = model.getNodesOfType(HTTPSamplerProxy::class.java).first()
        val changes = SearchTreeDialog.replacementChanges(node, Pattern.compile("original"), "changed", false)
        SearchTreeDialog.editWithUndo(gui, "Replace", { count -> count > 0 }) { SearchTreeDialog.applyChanges(changes) }
        gui.undo()
        assertTrue(gui.canRedo())
        gui.updateCurrentNode()
        SearchTreeDialog.editWithUndo(gui, "Empty removal", { count -> count > 0 }) { SearchTreeDialog.removeRows(emptyList()) }
        assertTrue(gui.canRedo())
        gui.redo()
        assertEquals("/changed", samplers(model).first().path)
    }
}
