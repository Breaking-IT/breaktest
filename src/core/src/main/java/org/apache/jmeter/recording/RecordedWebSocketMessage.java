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

package org.apache.jmeter.recording;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Captured WebSocket frame with a base64 payload and a connection-relative timestamp. */
public record RecordedWebSocketMessage(BigDecimal relativeTimeMs, String direction, int opcode, String data) {
    public RecordedWebSocketMessage {
        relativeTimeMs = relativeTimeMs.stripTrailingZeros();
    }

    public String text() {
        return new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
    }

    public String hex() {
        return HexFormat.ofDelimiter(" ").formatHex(Base64.getDecoder().decode(data));
    }

    public static List<RecordedWebSocketMessage> fromHar(JsonNode entry) {
        JsonNode messages = entry.path("_webSocketMessages");
        if (!messages.isArray() || messages.isEmpty()) {
            return List.of();
        }
        Instant start = Instant.parse(entry.path("startedDateTime").asText());
        BigDecimal startSeconds = BigDecimal.valueOf(start.getEpochSecond())
                .add(BigDecimal.valueOf(start.getNano(), 9));
        List<RecordedWebSocketMessage> result = new ArrayList<>();
        for (JsonNode message : messages) {
            String direction = message.path("type").asText();
            if (!"send".equals(direction) && !"receive".equals(direction)) {
                throw new IllegalArgumentException("Unknown recorded WebSocket message direction: " + direction);
            }
            if (!message.path("time").isNumber()) {
                throw new IllegalArgumentException("Recorded WebSocket message has no numeric timestamp");
            }
            int opcode = message.path("opcode").asInt(1);
            String value = message.path("data").asText("");
            // Chromium HAR exports encode non-text frames as base64, with or without _encoding.
            byte[] bytes = "base64".equals(message.path("_encoding").asText()) || opcode != 1
                    ? Base64.getDecoder().decode(value) : value.getBytes(StandardCharsets.UTF_8);
            result.add(new RecordedWebSocketMessage(
                    message.path("time").decimalValue().subtract(startSeconds).movePointRight(3),
                    direction, opcode, Base64.getEncoder().encodeToString(bytes)));
        }
        return List.copyOf(result);
    }

    public static void copyToArchive(JsonNode entry, ArrayNode target) {
        for (RecordedWebSocketMessage message : fromHar(entry)) {
            target.addObject()
                    .put("relativeTimeMs", message.relativeTimeMs())
                    .put("type", message.direction())
                    .put("opcode", message.opcode())
                    .put("data", message.data())
                    .put("_encoding", "base64");
        }
    }

    public static List<RecordedWebSocketMessage> fromExchange(JsonNode entry) {
        if (!entry.has("webSocketMessages")) {
            return fromHar(entry);
        }
        List<RecordedWebSocketMessage> result = new ArrayList<>();
        for (JsonNode message : entry.path("webSocketMessages")) {
            result.add(new RecordedWebSocketMessage(message.path("relativeTimeMs").decimalValue(),
                    message.path("type").asText(), message.path("opcode").asInt(), message.path("data").asText()));
        }
        return List.copyOf(result);
    }
}
