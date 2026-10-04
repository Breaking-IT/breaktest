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

import com.fasterxml.jackson.databind.JsonNode
import org.apache.jmeter.recording.RecordedWebSocketMessage
import org.apache.jmeter.testelement.TestElement
import java.util.Base64
import java.util.HexFormat

/** Byte-safe, bounded evidence shared by recording inspection and validation. */
public object AgentWebSocketSupport {
    public fun payload(bytes: ByteArray, byteLimit: Int): Map<String, Any?> {
        val shown = bytes.copyOfRange(0, minOf(bytes.size, byteLimit.coerceIn(0, 65536)))
        return mapOf(
            "byteLength" to bytes.size,
            "shownBytes" to shown.size,
            "truncated" to (shown.size < bytes.size),
            "base64" to Base64.getEncoder().encodeToString(shown),
            "hex" to HexFormat.ofDelimiter(" ").formatHex(shown),
            "ascii" to shown.map { if ((it.toInt() and 255) in 32..126) it.toInt().toChar() else '.' }.joinToString(""),
        )
    }

    public fun recorded(messages: List<RecordedWebSocketMessage>, offset: Int, limit: Int, byteLimit: Int): Map<String, Any?> {
        val start = offset.coerceIn(0, messages.size)
        val end = minOf(messages.size, start + limit.coerceIn(1, 200))
        return mapOf(
            "messageCount" to messages.size,
            "messageOffset" to start,
            "nextMessageOffset" to end.takeIf { it < messages.size },
            "messages" to (start until end).map { index ->
                val message = messages[index]
                val bytes = Base64.getDecoder().decode(message.data())
                mapOf(
                    "messageIndex" to index,
                    "relativeTimeMs" to message.relativeTimeMs(),
                    "direction" to message.direction(),
                    "opcode" to message.opcode(),
                    "contentType" to if (message.opcode() == 1) "text" else "binary",
                    "text" to if (message.opcode() == 1) message.text().take(byteLimit.coerceIn(0, 65536)) else null,
                    "payload" to payload(bytes, byteLimit),
                )
            },
        )
    }

    /** Exact repeated byte strings are evidence candidates, not proof of a dynamic protocol field. */
    public fun correlationHints(messages: List<RecordedWebSocketMessage>): Map<String, Any?> {
        val candidates = mutableListOf<Map<String, Any?>>()
        val sources = mutableMapOf<String, Pair<Int, Int>>()
        val token = Regex("[A-Za-z0-9_-]{12,256}")
        val bounded = messages.take(500)
        var truncated = messages.size > bounded.size
        for ((index, message) in bounded.withIndex()) {
            if (message.opcode() !in setOf(1, 2)) continue
            val bytes = Base64.getDecoder().decode(message.data())
            if (bytes.size > 65536) truncated = true
            val data = String(bytes, 0, minOf(bytes.size, 65536), Charsets.ISO_8859_1)
            for (match in token.findAll(data)) {
                val literal = match.value
                if (literal.none { it.isDigit() }) continue
                if (message.direction() == "receive") {
                    sources[literal] = index to match.range.first
                } else if (message.direction() == "send") {
                    val source = sources[literal] ?: continue
                    if (messages[source.first].relativeTimeMs() >= message.relativeTimeMs()) continue
                    candidates += mapOf(
                        "sourceMessageIndex" to source.first, "targetMessageIndex" to index,
                        "sourceByteOffset" to source.second, "targetByteOffset" to match.range.first,
                        "literal" to literal, "hex" to HexFormat.ofDelimiter(" ").formatHex(literal.toByteArray(Charsets.US_ASCII))
                    )
                    if (candidates.size >= 100) return mapOf("candidates" to candidates, "truncated" to true)
                }
            }
        }
        return mapOf(
            "candidates" to candidates, "truncated" to truncated,
            "guidance" to "Exact earlier-received byte matches only. Verify that the value is dynamic and decode/re-encode binary protocol fields; do not blindly replace bytes or attach message extractors to Connect."
        )
    }

    /** Validate all edits before changing the element; core does not depend on the HTTP module. */
    public fun update(element: TestElement, arguments: JsonNode): List<String> {
        val fields = when (element.javaClass.name) {
            "org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler" ->
                setOf("sessionName", "url", "timeout", "textFilter", "binaryFilter")
            "org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler" ->
                setOf("sessionName", "payload", "binary", "action", "timeout", "waitMode", "waitTimeout", "responsePattern", "responseBinary")
            "org.apache.jmeter.protocol.websocket.sampler.WebSocketCloseSampler" -> setOf("sessionName", "timeout")
            "org.apache.jmeter.protocol.websocket.sampler.WebSocketMatchController" ->
                setOf("matchMode", "matchValue", "saveMessageVariable")
            else -> error("Target is not a supported WebSocket element")
        }
        val routing = setOf(
            "targetNodeId", "targetNodePath", "targetSamplerIndex", "targetSamplerLabel",
            "targetOccurrenceIndex", "occurrenceIndex", "threadGroupName", "scopeNodePath"
        )
        val edits = arguments.properties().asSequence().filter { it.key !in routing }.toList()
        require(edits.isNotEmpty()) { "Specify at least one WebSocket setting" }
        for ((key, value) in edits) {
            require(key in fields) { "Unsupported setting $key for ${element.javaClass.simpleName}" }
            when (key) {
                "binary" -> require(value.isBoolean) { "$key must be boolean" }
                "timeout", "waitTimeout" -> require(value.isIntegralNumber && value.canConvertToInt() && value.asInt() > 0) {
                    "$key must be a positive integer"
                }
                else -> require(value.isTextual) { "$key must be text" }
            }
            if (key == "action") require(value.asText() in setOf("Send only", "Send and Wait")) { "Invalid action" }
            if (key == "waitMode") require(
                value.asText() in setOf(
                    "Next message", "Message matching regular expression",
                    "Binary message containing hex sequence"
                )
            ) { "Invalid waitMode" }
            if (key == "matchMode") require(value.asText() in setOf("Exact text", "Text regular expression", "Binary sequence (hex)")) {
                "Invalid matchMode"
            }
            if (key == "sessionName") require(value.asText().isNotBlank()) { "sessionName must not be blank" }
        }
        for ((key, value) in edits) {
            when {
                value.isBoolean -> element.setProperty(key, value.asBoolean())
                value.isIntegralNumber -> element.setProperty(key, value.asInt())
                else -> element.setProperty(key, value.asText())
            }
        }
        return edits.map { it.key }
    }

    public fun settings(element: TestElement): Map<String, Any?>? {
        if (!element.javaClass.name.startsWith("org.apache.jmeter.protocol.websocket.sampler.")) return null
        val keys = listOf(
            "sessionName", "url", "action", "binary", "payload", "timeout", "waitMode",
            "waitTimeout", "responsePattern", "responseBinary", "sendOffset", "closeOffset",
            "matchMode", "matchValue", "saveMessageVariable"
        )
        return keys.associateWith { key ->
            val getter = "get" + key.replaceFirstChar { it.uppercase() }
            val value = runCatching { element.javaClass.getMethod(getter).invoke(element)?.toString() }.getOrNull()
                ?: element.getPropertyAsString(key)
            value.take(4096)
        } +
            ("payloadTruncated" to (element.getPropertyAsString("payload").length > 4096))
    }
}
