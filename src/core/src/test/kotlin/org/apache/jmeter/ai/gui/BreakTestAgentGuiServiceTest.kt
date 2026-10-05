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
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.config.ConfigTestElement
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeListener
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTree
import javax.swing.tree.TreePath

class BreakTestAgentGuiServiceTest {
    @AfterEach
    fun resetGuiPackage() {
        val field: Field = GuiPackage::class.java.getDeclaredField("guiPack")
        field.isAccessible = true
        field.set(null, null)
    }

    class RecordingSampler : org.apache.jmeter.samplers.AbstractSampler() {
        override fun sample(entry: org.apache.jmeter.samplers.Entry?): org.apache.jmeter.samplers.SampleResult =
            org.apache.jmeter.samplers.SampleResult()
    }

    @Test
    fun `recording tools expose and search timed websocket messages`(@TempDir directory: Path) {
        val recording = org.apache.jmeter.recording.RecordedExchangeStore.fromHar(
            """
            {"log":{"entries":[{"startedDateTime":"2021-01-01T00:00:00Z",
              "request":{"method":"GET","url":"wss://example.test/chat"},"response":{"status":101},
              "_webSocketMessages":[
                {"type":"receive","time":1609459200.125,"opcode":1,"data":"server-issued-token"},
                {"type":"send","time":1609459200.250,"opcode":2,"data":"AP9BQkM="}]}]}}
            """.trimIndent().toByteArray(),
            "socket.har"
        )
        org.apache.jmeter.save.JmxArchiveEntryStore.registerBundle(
            recording.manifestEntryName(), recording.checksum(), recording.entries(),
        )
        val model = JMeterTreeModel(TestPlan("Plan"))
        GuiPackage.initInstance(JMeterTreeListener(model).apply { setJTree(JTree(model)) }, model)
        GuiPackage::class.java.getDeclaredField("testPlanFile").apply { isAccessible = true }
            .set(GuiPackage.getInstance(), directory.resolve("plan.jmx").toString())
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val group = JMeterTreeNode(
            ThreadGroup().apply {
                setProperty(org.apache.jmeter.recording.RecordedExchangeStore.MANIFEST_PROPERTY, recording.manifestEntryName())
                setProperty(org.apache.jmeter.recording.RecordedExchangeStore.CHECKSUM_PROPERTY, recording.checksum())
            },
            model
        )
        model.insertNodeInto(group, plan, 0)
        val sampler = RecordingSampler().apply {
            name = "Socket"
            setProperty(org.apache.jmeter.recording.RecordedExchangeStore.EXCHANGE_ID_PROPERTY, recording.exchangeIds()[0])
            setProperty("sessionName", "websocket-1")
        }
        model.insertNodeInto(JMeterTreeNode(sampler, model), group, 0)
        val json = ObjectMapper()
        val details = invokePrivateResult(
            "getRecordedHarExchangeOpenPlan",
            json.readTree(
                """{"targetSamplerIndex":0,"messageOffset":1,"messageLimit":1,"messageByteLimit":10}""",
            )
        ) as Map<*, *>
        assertEquals(true, details["hasExchange"])
        val recordingDetails = details["recordedWebSocket"] as Map<*, *>
        assertEquals(2, recordingDetails["messageCount"])
        val message = (recordingDetails["messages"] as List<*>).single() as Map<*, *>
        assertEquals("send", message["direction"])
        assertEquals("00 ff 41 42 43", (message["payload"] as Map<*, *>)["hex"])
        for (
            (query, surface, index) in listOf(
                Triple("server-issued-token", "recorded_websocket_text", 0),
                Triple("00 ff", "recorded_websocket_hex", 1),
                Triple("ABC", "recorded_websocket_ascii", 1),
            )
        ) {
            val result = invokePrivateResult("searchRecordedHarOpenPlan", json.createObjectNode().put("query", query)) as Map<*, *>
            val match = (result["matches"] as List<*>).single() as Map<*, *>
            assertEquals(surface, match["surface"])
            assertEquals(index, match["messageIndex"])
            assertEquals("websocket-1", match["sessionName"])
            assertTrue(match.containsKey("relativeTimeMs"))
        }
    }

    @Test
    fun `repair paths omit hidden root and resolve both visible and legacy paths`() {
        val model = JMeterTreeModel(TestPlan("Example Plan"))
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val group = JMeterTreeNode(ThreadGroup().apply { name = "Example Plan" }, model)
        val request = JMeterTreeNode(ConfigTestElement().apply { name = "/api/resources" }, model)
        model.insertNodeInto(group, plan, 0)
        model.insertNodeInto(request, group, 0)

        assertEquals("Example Plan", invokePrivateResult("nodePath", plan))
        val path = invokePrivateResult("nodePath", request) as String
        assertEquals("Example Plan / Example Plan / /api/resources", path)
        val matching = BreakTestAgentGuiService::class.java.getDeclaredMethod(
            "matchingNodesByPath", org.apache.jorphan.collections.HashTree::class.java, String::class.java,
        ).apply { isAccessible = true }
        for (requestedPath in listOf(path, "Example Plan / $path")) {
            assertEquals(listOf(request), matching.invoke(BreakTestAgentGuiService, model.testPlan, requestedPath))
        }
    }

    @Test
    fun `repair paths preserve detached test plan and repeated names`() {
        val plan = JMeterTreeNode(TestPlan("Example Plan"), null)
        val group = JMeterTreeNode(ThreadGroup().apply { name = "Example Plan" }, null)
        plan.add(group)

        assertEquals("Example Plan", invokePrivateResult("nodePath", plan))
        assertEquals("Example Plan / Example Plan", invokePrivateResult("nodePath", group))
    }

    @Test
    fun `HTTP2 pseudo headers do not become csrf values while multiline body values remain`() {
        for (newline in listOf("\n", "\r\n")) {
            val request = listOf(
                "POST /api/csrf HTTP/2", ":path: /api/csrf", ":scheme: https", ":authority: example.test",
                "X-CSRF-Token: real-csrf-value", "", "{\"paymentToken\":", "\"payment-real-value\"}",
            ).joinToString(newline)
            val candidates = invokePrivateResult("harRequestCandidates", request) as List<*>
            val literals = candidates.map { ObjectMapper().valueToTree<JsonNode>(it).path("literal").asText() }
            assertFalse(literals.contains("scheme:"))
            assertFalse(literals.contains("authority:"))
            assertTrue(literals.contains("real-csrf-value"))
            assertTrue(literals.contains("payment-real-value"))
        }
    }

    @Test
    fun `planner includes csrf and conditional cache headers with response evidence`() {
        val request = "GET /api HTTP/1.1\r\nX-CSRF-Token: csrf-rotating-value\r\nIf-None-Match: \"etag-value-123\"\r\n\r\n"
        val candidates = invokePrivateResult("harRequestCandidates", request) as List<*>
        val mapper = ObjectMapper()
        val etag = candidates.first { mapper.valueToTree<JsonNode>(it).path("kind").asText() == "cache-validator" }!!
        val csrf = candidates.first { mapper.valueToTree<JsonNode>(it).path("kind").asText() == "csrf-token" }!!
        val response = "HTTP/1.1 200 OK\r\nETag: \"etag-value-123\"\r\nX-CSRF-Token: csrf-rotating-value\r\n\r\n{}"
        for ((candidate, value) in listOf(etag to "etag-value-123", csrf to "csrf-rotating-value")) {
            val regex = invokePrivateResult("regexForHarCandidate", response, candidate, value, response.indexOf(value)) as String
            assertEquals(value, AgentRegexSupport.oroFirstCapture(regex, response))
        }
    }

    @Test
    fun `start reclaims a stale descriptor and replaces a closed listener`(@TempDir tempDir: Path) {
        val descriptorProperty = "breaktest.agent.descriptor"
        val socketProperty = "breaktest.agent.socket"
        val previousDescriptor = System.getProperty(descriptorProperty)
        val previousSocket = System.getProperty(socketProperty)
        val descriptor = tempDir.resolve("agent.json")
        val socket = tempDir.resolve("agent.sock")
        System.setProperty(descriptorProperty, descriptor.toString())
        System.setProperty(socketProperty, socket.toString())

        try {
            BreakTestAgentGuiService.start()
            val firstDetails = ObjectMapper().readTree(descriptor.toFile())
            val pinnedDescriptor = BreakTestAgentGuiService.createRunDescriptor()

            Files.writeString(
                descriptor,
                """{"host":"127.0.0.1","port":9,"socketPath":"/stale.sock","token":"stale"}""",
            )
            // A second GUI can replace discovery, but an active run retains its exact connection.
            assertEquals(firstDetails, ObjectMapper().readTree(pinnedDescriptor))
            assertNotEquals(descriptor.toFile(), pinnedDescriptor)
            Files.delete(pinnedDescriptor.toPath())
            BreakTestAgentGuiService.start()
            val reclaimedDetails = ObjectMapper().readTree(descriptor.toFile())

            assertEquals(firstDetails.path("port").asInt(), reclaimedDetails.path("port").asInt())
            assertEquals(firstDetails.path("token").asText(), reclaimedDetails.path("token").asText())

            val serverField = BreakTestAgentGuiService::class.java.getDeclaredField("serverSocket")
                .apply { isAccessible = true }
            (serverField.get(BreakTestAgentGuiService) as ServerSocket).close()
            BreakTestAgentGuiService.start()
            val restartedDetails = ObjectMapper().readTree(descriptor.toFile())

            assertNotEquals(firstDetails.path("token").asText(), restartedDetails.path("token").asText())
            assertTrue(!(serverField.get(BreakTestAgentGuiService) as ServerSocket).isClosed)
        } finally {
            invokePrivate("closeListeners")
            Files.deleteIfExists(descriptor)
            restoreSystemProperty(descriptorProperty, previousDescriptor)
            restoreSystemProperty(socketProperty, previousSocket)
        }
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
    fun `action snapshot restores the same dirty plan and preserves earlier edits`() {
        val model = JMeterTreeModel(TestPlan("Root"))
        val listener = JMeterTreeListener(model).apply { setJTree(JTree(model)) }
        GuiPackage.initInstance(listener, model)
        val gui = GuiPackage.getInstance()
        val testPlan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val threadGroup = node(ThreadGroup().apply { name = "Thread Group" }, model)
        val earlierEdit = node(Arguments().apply { name = "Earlier successful AI edit" }, model)
        model.insertNodeInto(threadGroup, testPlan, testPlan.childCount)
        model.insertNodeInto(earlierEdit, threadGroup, threadGroup.childCount)
        listener.setSelectionPathWithoutEdit(TreePath(earlierEdit.path))
        val originalPlanPath = "C:\\Users\\tester\\Test Plan.jmx"
        GuiPackage::class.java.getDeclaredField("testPlanFile")
            .apply { isAccessible = true }
            .set(gui, originalPlanPath)
        gui.setDirty(true)

        val snapshotTree = BreakTestAgentGuiService::class.java.getDeclaredMethod(
            "cloneOpenPlanTree",
            org.apache.jorphan.collections.HashTree::class.java,
        ).apply { isAccessible = true }
            .invoke(BreakTestAgentGuiService, gui.treeModel.testPlan)
        val stateClass = BreakTestAgentGuiService::class.java.declaredClasses
            .single { it.simpleName == "RepairActionState" }
        val capture = stateClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance("before", 3, 1, snapshotTree, originalPlanPath, true)
        val failedEdit = node(Arguments().apply { name = "Failed action damage" }, model)
        model.insertNodeInto(failedEdit, threadGroup, threadGroup.childCount)

        val restore = BreakTestAgentGuiService::class.java.getDeclaredMethod(
            "restoreRepairActionState",
            capture.javaClass,
            String::class.java,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val result = restore.invoke(BreakTestAgentGuiService, capture, "test rollback") as Map<String, Any?>

        val restoredNames = gui.treeModel.getNodesOfType(Arguments::class.java)
            .map { it.testElement.name }
        assertEquals("action-snapshot", result["method"])
        assertEquals(listOf("Earlier successful AI edit"), restoredNames)
        assertEquals(originalPlanPath, gui.testPlanFile)
        assertTrue(gui.isDirty)
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

    private fun restoreSystemProperty(name: String, value: String?) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
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
        val body = "{\"resourceId\":\"abc-123\",\"mail\":\"user%40example.com\"}"
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
        val response = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=abc-123\r\n\r\n{\"resourceId\":\"abc-123\"}"
        val occurrence = preferredOccurrenceByVariants(response, "abc-123")
        val index = occurrence?.first as Int
        assertTrue(index < response.indexOf("\r\n\r\n"), "expected the header-block match, got index $index")
    }
}
