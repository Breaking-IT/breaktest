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
import org.junit.jupiter.api.Assertions.assertNull
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

    // The repair planner hoists literalVariants() out of its per-response scan and
    // calls the variant-list overload, so the two overloads have to agree.
    private fun preferredOccurrenceByLiteral(response: String, literal: String): Pair<*, *>? {
        val method = BreakTestAgentGuiService::class.java
            .getDeclaredMethod("preferredLiteralOccurrence", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(BreakTestAgentGuiService, response, literal) as Pair<*, *>?
    }

    private fun preferredOccurrenceByVariants(response: String, literal: String): Pair<*, *>? {
        val variants = BreakTestAgentGuiService::class.java
            .getDeclaredMethod("literalVariants", String::class.java)
            .apply { isAccessible = true }
            .invoke(BreakTestAgentGuiService, literal)
        val method = BreakTestAgentGuiService::class.java
            .getDeclaredMethod("preferredLiteralOccurrence", String::class.java, List::class.java)
        method.isAccessible = true
        return method.invoke(BreakTestAgentGuiService, response, variants) as Pair<*, *>?
    }

    private fun boundaryDerivedRegex(response: String, literal: String): String? {
        val method = BreakTestAgentGuiService::class.java.getDeclaredMethod(
            "boundaryDerivedRegex", String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(BreakTestAgentGuiService, response, literal, response.indexOf(literal)) as String?
    }

    @Test
    fun `derived regex captures the value and not the json key`() {
        val response = """{"clientID":"l7xxab12cd34ef56","clientSecret":"s3cr3t"}"""
        val regex = boundaryDerivedRegex(response, "l7xxab12cd34ef56")

        // The previous quote..quote fallback emitted "([^"]+)", which matches the
        // object but captures clientID, so the extractor resolved to the key name.
        assertNotEquals(""""([^"]+)"""", regex)
        assertEquals(
            "l7xxab12cd34ef56",
            AgentRegexSupport.oroFirstCapture(requireNotNull(regex), response),
            "derived regex captured the wrong value: $regex",
        )
    }

    @Test
    fun `derived regex handles a repeated value shape`() {
        val response = """{"a":{"id":"tok-111"},"b":{"id":"tok-222"}}"""
        val regex = boundaryDerivedRegex(response, "tok-222")
        assertEquals(
            "tok-222",
            AgentRegexSupport.oroFirstCapture(requireNotNull(regex), response),
            "derived regex captured the wrong occurrence: $regex",
        )
    }

    @Test
    fun `no regex is derived when none can capture the literal`() {
        // Nothing usable precedes the literal, so planning must decline rather than
        // emit a pattern that captures something else.
        assertNull(boundaryDerivedRegex("tok-999", "tok-999"))
    }

    @Test
    fun `both preferred-occurrence overloads select the same match`() {
        val body = "{\"pageId\":\"abc-123\",\"mail\":\"user%40example.com\"}"
        val response = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=abc-123\r\nLocation: /next\r\n\r\n$body"
        val cases = listOf(
            "abc-123", // present in both the header block and the body
            "user@example.com", // only present in its URL-encoded form
            "user%40example.com", // only present in its raw form
            "/next", // header-only
            "not-in-this-response", // absent
        )
        for (literal in cases) {
            assertEquals(
                preferredOccurrenceByLiteral(response, literal),
                preferredOccurrenceByVariants(response, literal),
                "overloads disagree for '$literal'",
            )
        }
    }

    @Test
    fun `header block wins over a later body occurrence`() {
        val response = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=abc-123\r\n\r\n{\"pageId\":\"abc-123\"}"
        val occurrence = preferredOccurrenceByVariants(response, "abc-123")
        val index = occurrence?.first as Int
        assertTrue(index < response.indexOf("\r\n\r\n"), "expected the header-block match, got index $index")
    }
}
