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

import org.apache.jmeter.recording.RecordedWebSocketMessage
import org.apache.jmeter.samplers.SampleResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Base64

class AgentWebSocketSupportTest {
    @Test
    fun `correlation hints require an earlier incoming byte match`() {
        fun message(time: Int, direction: String, opcode: Int, text: String) = RecordedWebSocketMessage(
            BigDecimal(time), direction, opcode, Base64.getEncoder().encodeToString(text.toByteArray()),
        )
        val result = AgentWebSocketSupport.correlationHints(
            listOf(
                message(1, "send", 1, "token-123456789"),
                message(2, "receive", 2, "token-123456789"),
                message(3, "send", 2, "token-123456789"),
            )
        )
        val candidate = (result["candidates"] as List<*>).single() as Map<*, *>
        assertEquals(1, candidate["sourceMessageIndex"])
        assertEquals(2, candidate["targetMessageIndex"])
        assertEquals("token-123456789", candidate["literal"])
        assertEquals(0, candidate["sourceByteOffset"])
    }

    @Test
    fun `recorded evidence pages messages and preserves bytes and timing`() {
        val messages = listOf(
            RecordedWebSocketMessage(BigDecimal("1.125"), "receive", 2, "AP9B"),
            RecordedWebSocketMessage(BigDecimal("2"), "send", 1, "aGk="),
        )
        val first = AgentWebSocketSupport.recorded(messages, 0, 1, 2)
        assertEquals(2, first["messageCount"])
        assertEquals(1, first["nextMessageOffset"])
        val message = (first["messages"] as List<*>).single() as Map<*, *>
        assertEquals("receive", message["direction"])
        assertEquals(BigDecimal("1.125"), message["relativeTimeMs"])
        val payload = message["payload"] as Map<*, *>
        assertEquals(3, payload["byteLength"])
        assertEquals(true, payload["truncated"])
        assertEquals("00 ff", payload["hex"])
        assertArrayEquals(byteArrayOf(0, -1), Base64.getDecoder().decode(payload["base64"] as String))
        val second = AgentWebSocketSupport.recorded(messages, 1, 1, 50)
        assertNull(second["nextMessageOffset"])
        assertEquals("hi", ((second["messages"] as List<*>).single() as Map<*, *>)["text"])
        assertTrue((AgentWebSocketSupport.recorded(messages, 20, 1, 50)["messages"] as List<*>).isEmpty())
    }

    @Test
    fun `validation binary evidence is hex with explicit encoding and truncation`() {
        val sample = SampleResult().apply {
            dataType = SampleResult.BINARY
            responseData = byteArrayOf(0, -1, 65, 66)
        }
        val summary = AgentSampleSummary.from(0, sample, AgentRunOptions(responseBodyLimit = 6))
        assertEquals("00 ff", summary.responseBody)
        assertEquals("hex", summary.responseBodyEncoding)
        assertEquals(4, summary.responseByteLength)
        assertTrue(summary.responseBodyTruncated)
        val full = AgentSampleSummary.from(0, sample, AgentRunOptions(responseBodyLimit = -1))
        assertEquals("00 ff 41 42", full.responseBody)
        assertFalse(full.responseBodyTruncated)
    }
}
