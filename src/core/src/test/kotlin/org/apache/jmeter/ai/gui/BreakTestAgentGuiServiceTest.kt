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
import org.apache.jmeter.ai.AgentRegexSupport
import org.apache.jmeter.config.ConfigTestElement
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeListener
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import javax.swing.JTree
import javax.swing.tree.TreePath

class BreakTestAgentGuiServiceTest {
    @AfterEach
    fun resetGuiPackage() {
        val field: Field = GuiPackage::class.java.getDeclaredField("guiPack")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `deleting selected child keeps the rest of the open plan attached`() {
        val model = JMeterTreeModel(TestPlan("Root"))
        val listener = JMeterTreeListener(model).apply { setJTree(JTree(model)) }
        GuiPackage.initInstance(listener, model)
        val gui = GuiPackage.getInstance()
        val testPlan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val threadGroup = node(ThreadGroup().apply { name = "Thread Group" }, model)
        val sampler = node(ConfigTestElement().apply { name = "Sampler" }, model)
        val extractor = node(ConfigTestElement().apply { name = "AI Extractor" }, model)
        val untouched = node(ConfigTestElement().apply { name = "Untouched" }, model)
        model.insertNodeInto(threadGroup, testPlan, testPlan.childCount)
        model.insertNodeInto(sampler, threadGroup, threadGroup.childCount)
        model.insertNodeInto(extractor, sampler, sampler.childCount)
        model.insertNodeInto(untouched, sampler, sampler.childCount)
        listener.setSelectionPathWithoutEdit(TreePath(extractor.path))

        invokePrivate("moveSelectionOutsideDeletedNodes", gui, listOf(extractor))
        invokePrivate("removeTreeNode", gui, extractor)

        assertSame(sampler, listener.currentNode)
        assertEquals(1, testPlan.childCount)
        assertSame(threadGroup, testPlan.getChildAt(0))
        assertEquals(1, sampler.childCount)
        assertSame(untouched, sampler.getChildAt(0))
        assertTrue(threadGroup.isNodeDescendant(untouched))
    }

    @Test
    fun `repair actions conflict when they replace the same literal in the same scope`() {
        fun action(scope: String, literal: String = "recorded-state") = mapOf(
            "applyArguments" to mapOf(
                "scopeNodePath" to scope,
                "literal" to literal,
            ),
        )

        val first = invokePrivateResult("repairActionConflictKey", action("Test Plan / Thread Group"))
        val duplicate = invokePrivateResult("repairActionConflictKey", action("Test Plan / Thread Group"))
        val encodedDuplicate = invokePrivateResult(
            "repairActionConflictKey",
            action("Test Plan / Thread Group", "recorded%2Dstate"),
        )
        val otherScope = invokePrivateResult("repairActionConflictKey", action("Test Plan / Other"))

        assertEquals(first, duplicate)
        assertEquals(first, encodedDuplicate)
        assertNotEquals(first, otherScope)
    }

    @Test
    fun `refresh with a lost plan filename reports a useful error instead of null pointer`() {
        val model = JMeterTreeModel(TestPlan("Root"))
        val listener = JMeterTreeListener(model).apply { setJTree(JTree(model)) }
        GuiPackage.initInstance(listener, model)

        val failure = assertThrows(InvocationTargetException::class.java) {
            invokePrivateResult("refreshOpenPlanFromFile", ObjectMapper().createObjectNode())
        }

        assertEquals("The open plan must be saved before it can be refreshed", failure.cause?.message)
    }

    @Test
    fun `planner derives a native regex for a bare quoted response token`() {
        val response = "HTTP/1.1 200 OK\nContent-Type: application/json\n\n\"dynamic-token\""
        val literal = "dynamic-token"

        val regex = invokePrivateResult(
            "boundaryDerivedRegex",
            response,
            literal,
            response.indexOf(literal),
        ) as String

        assertTrue(AgentRegexSupport.oroMatches(regex, response))
    }

    private fun node(element: org.apache.jmeter.testelement.TestElement, model: JMeterTreeModel) =
        JMeterTreeNode(element, model)

    private fun invokePrivate(name: String, vararg arguments: Any) {
        invokePrivateResult(name, *arguments)
    }

    private fun invokePrivateResult(name: String, vararg arguments: Any): Any? {
        val parameterTypes = arguments.map { argument ->
            when (argument) {
                is GuiPackage -> GuiPackage::class.java
                is JMeterTreeNode -> JMeterTreeNode::class.java
                is List<*> -> List::class.java
                is Map<*, *> -> Map::class.java
                is JsonNode -> JsonNode::class.java
                is Int -> Int::class.javaPrimitiveType!!
                else -> argument::class.java
            }
        }.toTypedArray()
        val method = BreakTestAgentGuiService::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(BreakTestAgentGuiService, *arguments)
    }
}
