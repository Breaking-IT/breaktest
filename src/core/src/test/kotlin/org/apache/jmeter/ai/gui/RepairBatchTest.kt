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

package org.apache.jmeter.ai.gui

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeListener
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.swing.JTree

class RepairBatchTest : JMeterTestCase() {
    private val mapper = ObjectMapper()

    @AfterEach
    fun resetGui() {
        GuiPackage::class.java.getDeclaredField("guiPack").apply { isAccessible = true }.set(null, null)
    }

    private fun setup(directory: Path): Arguments {
        val model = JMeterTreeModel(TestPlan("Root"))
        GuiPackage.initInstance(JMeterTreeListener(model).apply { setJTree(JTree(model)) }, model)
        val gui = GuiPackage.getInstance()
        GuiPackage::class.java.getDeclaredField("testPlanFile").apply { isAccessible = true }
            .set(gui, directory.resolve("trial.jmx").toString())
        val root = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val group = JMeterTreeNode(ThreadGroup().apply { name = "Selected" }, model)
        model.insertNodeInto(group, root, root.childCount)
        val values = Arguments().apply { name = "Values"; addArgument("one", "recorded-first"); addArgument("two", "recorded-second") }
        model.insertNodeInto(JMeterTreeNode(values, model), group, 0)
        return values
    }

    private fun invoke(name: String, argument: JsonNode): JsonNode {
        val method = BreakTestAgentGuiService::class.java.getDeclaredMethod(name, JsonNode::class.java)
            .apply { isAccessible = true }
        return mapper.valueToTree(method.invoke(BreakTestAgentGuiService, argument))
    }

    private fun batch(second: String): JsonNode = mapper.valueToTree(
        mapOf(
            "replacements" to listOf(
                mapOf("threadGroupName" to "Selected", "literal" to "recorded-first", "replacement" to "new-first"),
                mapOf("threadGroupName" to "Selected", "literal" to second, "replacement" to "new-second"),
            )
        )
    )

    @Test
    fun `ordering proposals preserve inherited configs and only hoist direct parallel sources`(@TempDir directory: Path) {
        setup(directory)
        val model = GuiPackage.getInstance().treeModel
        val group = model.getNodesOfType(ThreadGroup::class.java).first()
        val parallel = JMeterTreeNode(org.apache.jmeter.control.ParallelController(), model)
        model.insertNodeInto(parallel, group, group.childCount)
        val source = JMeterTreeNode(org.apache.jmeter.ai.ScriptRepairSampler("Source"), model)
        model.insertNodeInto(source, parallel, 0)
        val method = BreakTestAgentGuiService::class.java.getDeclaredMethod("proposedSourceOrdering", JMeterTreeNode::class.java)
            .apply { isAccessible = true }
        val ordering = method.invoke(BreakTestAgentGuiService, source)
        assertTrue(ordering != null)
        val apply = BreakTestAgentGuiService::class.java.getDeclaredMethod("applySuggestedOrdering", Map::class.java)
            .apply { isAccessible = true }
        apply.invoke(BreakTestAgentGuiService, mapOf("sourceOrdering" to ordering))
        assertTrue(source.parent === group)
        assertTrue(group.getIndex(source) < group.getIndex(parallel))
        // Reusing the same source for another value must not move it twice.
        apply.invoke(BreakTestAgentGuiService, mapOf("sourceOrdering" to ordering))
        model.removeNodeFromParent(source)
        model.insertNodeInto(source, parallel, 0)
        model.insertNodeInto(JMeterTreeNode(Arguments(), model), parallel, 1)
        assertEquals(null, method.invoke(BreakTestAgentGuiService, source))
    }

    @Test
    fun `preflight is scoped read only and contains full review packet`(@TempDir directory: Path) {
        val values = setup(directory)
        val gui = GuiPackage.getInstance()
        val legacy = org.apache.jmeter.ai.knowledge.BreakTestAiKnowledge().apply {
            knowledgeJson = "legacy-not-json-should-never-enter-the-prompt"
        }
        val root = (gui.treeModel.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        gui.treeModel.insertNodeInto(JMeterTreeNode(legacy, gui.treeModel), root, root.childCount)
        val count = gui.treeModel.getNodesOfType(org.apache.jmeter.testelement.TestElement::class.java).size
        val packet = mapper.readTree(BreakTestAgentGuiService.prepareScriptRepair("Selected"))
        assertTrue(packet.has("repairPlan"))
        assertTrue(packet.path("repairPlan").path("reviewOnly").asBoolean())
        assertTrue(packet.path("dynamicAudit").path("compact").asBoolean())
        assertTrue(!packet.has("knowledge"))
        assertTrue(!packet.toString().contains("legacy-not-json"))
        assertEquals("legacy-not-json-should-never-enter-the-prompt", legacy.knowledgeJson)
        assertEquals(count, gui.treeModel.getNodesOfType(org.apache.jmeter.testelement.TestElement::class.java).size)
        assertEquals("recorded-first", values.getArgument(0).value)
    }

    @Test
    fun `substring proposals are flagged only within the same source and scope`() {
        fun action(id: String, literal: String, source: String = "s1", scope: String = "scope1") = mapOf(
            "id" to id, "applyArguments" to mapOf("literal" to literal, "sourceNodeId" to source, "scopeNodePath" to scope)
        )
        val short = action("short", "abc-123")
        val full = action("full", "token_abc-123")
        val otherSource = action("other-source", "other_abc-123", source = "s2")
        val otherScope = action("other-scope", "other_abc-123", scope = "scope2")
        val method = BreakTestAgentGuiService::class.java.getDeclaredMethod("overlappingRepairActions", Map::class.java, List::class.java)
            .apply { isAccessible = true }
        assertEquals(listOf("full"), method.invoke(BreakTestAgentGuiService, short, listOf(short, full, otherSource, otherScope)))
        assertEquals(emptyList<String>(), method.invoke(BreakTestAgentGuiService, full, listOf(short, full)))
    }

    @Test
    fun `correlation batch refuses unverified evidence before editing`(@TempDir directory: Path) {
        val values = setup(directory)
        org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            invoke(
                "applyCorrelationBatchOpenPlan",
                mapper.valueToTree(
                    mapOf(
                        "correlations" to listOf(
                            mapOf(
                                "literal" to "recorded-first", "evidenceSource" to "recorded_response",
                                "evidence" to "recorded-first", "allowUnmatchedEvidence" to true
                            ),
                        )
                    )
                )
            )
        }
        assertEquals("recorded-first", values.getArgument(0).value)
    }

    @Test
    fun `batch replaces independent values with one compact result`(@TempDir directory: Path) {
        val values = setup(directory)
        val result = invoke("replaceLiteralsOpenPlan", batch("recorded-second"))
        assertEquals("applied", result.path("status").asText())
        assertEquals(2, result.path("appliedCount").asInt())
        assertEquals("new-first", values.getArgument(0).value)
        assertEquals("new-second", values.getArgument(1).value)
    }

    @Test
    fun `failed second replacement restores first replacement`(@TempDir directory: Path) {
        setup(directory)
        val result = invoke("replaceLiteralsOpenPlan", batch("absent"))
        assertEquals("failed", result.path("status").asText())
        assertEquals(1, result.path("failedIndex").asInt())
        assertTrue(result.path("rolledBack").asBoolean())
        val restored = GuiPackage.getInstance().treeModel.getNodesOfType(Arguments::class.java)
            .map { it.testElement as Arguments }.first { it.name == "Values" }
        assertEquals("recorded-first", restored.getArgument(0).value)
        assertEquals("recorded-second", restored.getArgument(1).value)
    }
}
