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

package org.apache.jmeter.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.jmeter.control.ForkController
import org.apache.jmeter.control.LoopController
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.gui.util.MenuFactory
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler
import org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler
import org.apache.jmeter.testelement.TestPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class AgentElementCatalogTest : JMeterTestCase() {
    private val json = ObjectMapper()

    @Test
    fun `live tools build and configure an empty plan by returned node ids`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
        SwingUtilities.invokeAndWait {
            val model = org.apache.jmeter.gui.tree.JMeterTreeModel()
            val listener = org.apache.jmeter.gui.tree.JMeterTreeListener(model).apply { setJTree(javax.swing.JTree(model)) }
            org.apache.jmeter.gui.GuiPackage.initInstance(listener, model)
            val gui = org.apache.jmeter.gui.GuiPackage.getInstance()
            org.apache.jmeter.gui.GuiPackage::class.java.getDeclaredField("testPlanFile").apply { isAccessible = true }
                .set(gui, directory.resolve("new.jmx").toString())
            val method = org.apache.jmeter.ai.gui.BreakTestAgentGuiService::class.java.getDeclaredMethod(
                "handleTool", String::class.java, com.fasterxml.jackson.databind.JsonNode::class.java,
            ).apply { isAccessible = true }
            fun call(tool: String, arguments: String): com.fasterxml.jackson.databind.JsonNode = json.valueToTree(
                method.invoke(org.apache.jmeter.ai.gui.BreakTestAgentGuiService, tool, json.readTree(arguments)),
            )
            try {
                val group = call("add_element_open_plan", """{"elementId":"org.apache.jmeter.threads.gui.ThreadGroupGui","name":"Generated"}""")
                val groupId = group.path("nodeId").asText()
                val send = call(
                    "add_element_open_plan",
                    """{
                    "elementId":"org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler",
                    "targetNodeId":"$groupId","properties":{"sessionName":"station","payload":"original"}
                }"""
                )
                val sendId = send.path("nodeId").asText()
                assertTrue(send.path("element").path("properties").isMissingNode)
                assertEquals("payload", send.path("element").path("configuredProperties").last().asText())
                val largePayload = "segment-data-".repeat(2000)
                val compact = call(
                    "configure_element_open_plan",
                    json.createObjectNode().put("targetNodeId", sendId).apply {
                        putObject("properties").put("payload", largePayload)
                    }.toString(),
                )
                assertTrue(compact.toString().length < 1000)
                assertEquals(largePayload, (model.getNodesOfType(WebSocketSendWaitSampler::class.java).single().testElement as WebSocketSendWaitSampler).payload)
                val verbose = call("configure_element_open_plan", """{"targetNodeId":"$sendId","compact":false}""")
                assertTrue(verbose.path("element").path("properties").any { it.path("value").asText() == largePayload })

                call("configure_element_open_plan", """{"targetNodeId":"$sendId","properties":{"payload":"changed"}}""")
                assertEquals("changed", (model.getNodesOfType(WebSocketSendWaitSampler::class.java).single().testElement as WebSocketSendWaitSampler).payload)
                assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                    call("configure_element_open_plan", """{"targetNodeId":"$sendId","properties":{"payload":"broken","unknown":true}}""")
                }
                assertEquals("changed", (model.getNodesOfType(WebSocketSendWaitSampler::class.java).single().testElement as WebSocketSendWaitSampler).payload)
                assertTrue(gui.isDirty)
                assertTrue(java.nio.file.Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().contains("ai-backup") } })
            } finally {
                org.apache.jmeter.gui.GuiPackage::class.java.getDeclaredField("guiPack").apply { isAccessible = true }.set(null, null)
            }
        }
    }

    @Test
    fun `field backed TestBean settings survive preparation`() {
        SwingUtilities.invokeAndWait {
            val element = AgentElementCatalog.create("org.apache.jmeter.config.CSVDataSet")
            val changed = AgentElementCatalog.configured(
                element,
                json.readTree("""{"filename":"stations.csv","delimiter":";","variableNames":"stationId"}""")
            )
            assertEquals("stations.csv", changed.getPropertyAsString("filename"))
            org.apache.jmeter.testbeans.TestBeanHelper.prepare(changed)
            assertEquals("stations.csv", (changed as org.apache.jmeter.config.CSVDataSet).filename)
            assertEquals(";", changed.delimiter)
        }
    }

    @Test
    fun `installed catalog generates native websocket and heartbeat hierarchy`() {
        SwingUtilities.invokeAndWait {
            fun create(query: String, className: String, properties: String = "{}") =
                AgentElementCatalog.configured(
                    AgentElementCatalog.create(
                        AgentElementCatalog.list(query).single {
                            it["elementId"].toString().endsWith(".$className")
                        }["elementId"] as String
                    ),
                    json.readTree(properties),
                )
            val group = create("Thread", "ThreadGroupGui", """{"numThreads":1,"rampUp":0}""")
            val connect = create("WebSocket", "WebSocketConnectSampler", """{"sessionName":"station","url":"ws://localhost:8080/station"}""")
            val send = create("WebSocket", "WebSocketSendWaitSampler", """{"sessionName":"station","payload":"hello","action":"Send and Wait"}""")
            val fork = create("Fork", "ForkControllerGui", """{"iterationEndAction":"WAIT","errorAction":"STOP_FORK"}""")
            val loop = create("Loop", "LoopControlPanel", """{"loops":3}""")
            val conditional = create("If", "IfControllerPanel", """{"condition":"true"}""")
            val whileLoop = create("While", "WhileControllerGui", """{"condition":"false"}""")
            val parallel = create("Parallel", "ParallelControllerGui")
            val heartbeat = create("WebSocket", "WebSocketSendWaitSampler", """{"sessionName":"station","payload":"heartbeat","action":"Send only"}""")
            // Search the stable class identifier; the display label is localized.
            val timer = create("ConstantTimer", "ConstantTimerGui", """{"delay":"1000"}""")
            val close = create("WebSocket", "WebSocketCloseSampler", """{"sessionName":"station"}""")
            val root = JMeterTreeNode(TestPlan(), null)
            fun child(parent: JMeterTreeNode, element: org.apache.jmeter.testelement.TestElement): JMeterTreeNode {
                assertTrue(MenuFactory.canAddTo(parent, element), element.javaClass.name)
                return JMeterTreeNode(element, null).also { parent.add(it) }
            }
            val thread = child(root, group)
            child(thread, connect)
            child(thread, send)
            val background = child(child(thread, fork), loop)
            child(child(background, heartbeat), timer)
            child(thread, conditional)
            child(thread, whileLoop)
            child(thread, parallel)
            child(thread, close)
            assertEquals("station", (connect as WebSocketConnectSampler).sessionName)
            assertEquals("Send and Wait", (send as WebSocketSendWaitSampler).action)
            assertEquals(ForkController.IterationEndAction.WAIT, (fork as ForkController).iterationEndAction)
            assertEquals(3, (loop as LoopController).loops)
            assertEquals("Send only", (heartbeat as WebSocketSendWaitSampler).action)
            assertFalse(MenuFactory.canAddTo(root, send))
            assertTrue((AgentElementCatalog.describe(send)["constraints"] as List<*>).isNotEmpty())
        }
    }

    @Test
    fun `invalid settings and unavailable classes do not mutate original`() {
        val original = WebSocketSendWaitSampler().apply { payload = "original" }
        assertThrows(IllegalStateException::class.java) {
            AgentElementCatalog.configured(original, json.readTree("""{"payload":"changed","unknownSetting":true}"""))
        }
        assertEquals("original", original.payload)
        assertThrows(IllegalArgumentException::class.java) {
            AgentElementCatalog.configured(original, json.readTree("""{"action":"unsupported"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) { AgentElementCatalog.create("java.lang.String") }
    }
}
