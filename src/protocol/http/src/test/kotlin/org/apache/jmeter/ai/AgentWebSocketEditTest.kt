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
import org.apache.jmeter.config.ConfigTestElement
import org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler
import org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentWebSocketEditTest : org.apache.jmeter.junit.JMeterTestCase() {
    private val json = ObjectMapper()

    @Test
    fun `native tables configure protocol headers and HTTP bodies and survive serialization`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
        val connect = AgentElementCatalog.configured(
            WebSocketConnectSampler(), json.readTree("{}"),
            json.readTree("""{"headers":[{"name":"Sec-WebSocket-Protocol","value":"ocpp1.6"}]}"""),
        ) as WebSocketConnectSampler
        assertEquals("ocpp1.6", connect.headers.single().value)
        val http = AgentElementCatalog.configured(
            org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(),
            json.readTree("""{"method":"POST","postBodyRaw":true}"""),
            json.readTree("""{"arguments":[{"name":"","value":"{\"hello\":true}","alwaysEncoded":false}]}"""),
        ) as org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
        assertEquals("{\"hello\":true}", http.arguments.getArgument(0).value)
        assertTrue(http.postBodyRaw)
        val manager = org.apache.jmeter.protocol.http.control.HeaderManager().apply { name = "Headers" }
        val configured = AgentElementCatalog.configured(
            manager, json.readTree("{}"),
            json.readTree("""{"headers":[{"name":"Content-Type","value":"application/json"}]}""")
        ) as org.apache.jmeter.protocol.http.control.HeaderManager
        assertEquals("Headers", configured.name)
        assertEquals("application/json", configured.getHeader(0).value)
        assertEquals(0, manager.size())
        connect.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS, "org.apache.jmeter.testbeans.gui.TestBeanGUI")
        http.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui")
        val tree = org.apache.jorphan.collections.ListedHashTree().apply { add(connect); add(http) }
        val output = java.io.ByteArrayOutputStream()
        org.apache.jmeter.save.SaveService.saveTree(tree, output)
        val file = directory.resolve("generated.jmx")
        java.nio.file.Files.write(file, output.toByteArray())
        val restored = org.apache.jmeter.save.SaveService.loadTree(file.toFile())
        assertEquals("ocpp1.6", restored.list().filterIsInstance<WebSocketConnectSampler>().single().headers.single().value)
        val original = connect.headers.single().value
        assertThrows(IllegalStateException::class.java) {
            AgentElementCatalog.configured(
                connect, json.readTree("{}"),
                json.readTree("""{"headers":[{"name":"X","value":"changed"},{"unknown":true}]}""")
            )
        }
        assertEquals(original, connect.headers.single().value)
    }

    @Test
    fun `repair configures an unsolicited binary message capture`() {
        val handler = org.apache.jmeter.protocol.websocket.sampler.WebSocketMatchController()
        AgentWebSocketSupport.update(
            handler,
            json.readTree(
                """{
            "matchMode":"Binary sequence (hex)","matchValue":"41 42", "saveMessageVariable":"incomingHex"
        }"""
            )
        )
        assertEquals("Binary sequence (hex)", handler.matchMode)
        assertEquals("41 42", handler.matchValue)
        assertEquals("incomingHex", handler.saveMessageVariable)
        assertEquals("Send and Wait", AgentWebSocketSupport.settings(WebSocketSendWaitSampler())?.get("action"))
    }

    @Test
    fun `repair edits wait settings and payload without changing replay timing`() {
        val send = WebSocketSendWaitSampler().apply { sendOffset = "250" }
        AgentWebSocketSupport.update(
            send,
            json.readTree(
                """{
            "payload":"00 ff 41", "binary":true, "action":"Send and Wait",
            "waitMode":"Binary message containing hex sequence", "responseBinary":"41", "waitTimeout":1500
        }"""
            )
        )
        assertEquals("00 ff 41", send.payload)
        assertTrue(send.binary)
        assertEquals("250", send.sendOffset)
        assertEquals("Send and Wait", send.action)
        assertEquals("41", send.responseBinary)
        assertEquals(1500, send.waitTimeout)
        assertEquals("00 ff 41", AgentWebSocketSupport.settings(send)?.get("payload"))
    }

    @Test
    fun `invalid or inapplicable settings leave element unchanged`() {
        val send = WebSocketSendWaitSampler().apply { payload = "original" }
        for (
            invalid in listOf(
                """{"payload":"changed","waitTimeout":-1}""",
                """{"payload":"changed","action":"anything"}""",
                """{"payload":"changed","binary":"true"}""",
                """{"payload":"changed","url":"wss://example.test"}""",
            )
        ) {
            assertThrows(IllegalArgumentException::class.java) { AgentWebSocketSupport.update(send, json.readTree(invalid)) }
            assertEquals("original", send.payload)
        }
        assertThrows(IllegalStateException::class.java) {
            AgentWebSocketSupport.update(ConfigTestElement(), json.readTree("""{"payload":"x"}"""))
        }
        val connect = WebSocketConnectSampler()
        AgentWebSocketSupport.update(connect, json.readTree("""{"url":"wss://example.test/chat","sessionName":"chat"}"""))
        assertEquals("chat", connect.sessionName)
        assertEquals("wss://example.test/chat", connect.url)
    }
}
