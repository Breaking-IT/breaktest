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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Captured WebSocket frame with a base64 payload and a connection-relative timestamp. */
public record RecordedWebSocketMessage(BigDecimal relativeTimeMs, String direction, int opcode, String data) {
    private static final Logger LOG = LoggerFactory.getLogger(RecordedWebSocketMessage.class);

    public RecordedWebSocketMessage {
        relativeTimeMs = relativeTimeMs.stripTrailingZeros();
    }

    public String text() {
        return new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
    }

    public String hex() {
        return HexFormat.ofDelimiter(" ").formatHex(Base64.getDecoder().decode(data));
    }

    /** Decode only enough base64 for a table preview, independent of the full payload size. */
    public String preview() {
        int limit = 200;
        int byteLimit = opcode == 1 ? (limit + 1) * 4 : (limit + 2) / 3 + 1;
        int encodedLimit = ((byteLimit + 2) / 3) * 4;
        byte[] prefix = Base64.getDecoder().decode(data.substring(0, Math.min(data.length(), encodedLimit)));
        String value = opcode == 1 ? new String(prefix, StandardCharsets.UTF_8)
                : HexFormat.ofDelimiter(" ").formatHex(prefix);
        return value.length() > limit ? value.substring(0, limit) + "…" : value;
    }

    public static List<RecordedWebSocketMessage> fromHar(JsonNode entry) {
        JsonNode messages = entry.path("_webSocketMessages");
        if (!messages.isArray() || messages.isEmpty()) {
            return List.of();
        }
        var parsedStart = HarTimestamp.parse(entry.path("startedDateTime").asText());
        if (parsedStart.isEmpty()) {
            LOG.warn("Skipping recorded WebSocket messages with an invalid connection timestamp");
            return List.of();
        }
        var start = parsedStart.get();
        BigDecimal startSeconds = BigDecimal.valueOf(start.getEpochSecond())
                .add(BigDecimal.valueOf(start.getNano(), 9));
        List<RecordedWebSocketMessage> result = new ArrayList<>();
        int skipped = 0;
        for (JsonNode message : messages) {
            String direction = message.path("type").asText();
            if ((!"send".equals(direction) && !"receive".equals(direction))
                    || !message.path("time").isNumber()
                    || (!message.path("data").isTextual() && !message.path("data").isMissingNode())) {
                skipped++;
                continue;
            }
            try {
                int opcode = message.path("opcode").asInt(1);
                String value = message.path("data").asText("");
                // Chromium HAR exports encode non-text frames as base64, with or without _encoding.
                byte[] bytes = "base64".equals(message.path("_encoding").asText()) || opcode != 1
                        ? Base64.getDecoder().decode(value) : value.getBytes(StandardCharsets.UTF_8);
                result.add(new RecordedWebSocketMessage(
                        message.path("time").decimalValue().subtract(startSeconds).movePointRight(3),
                        direction, opcode, Base64.getEncoder().encodeToString(bytes)));
            } catch (IllegalArgumentException invalid) {
                skipped++;
            }
        }
        if (skipped > 0) {
            LOG.warn("Skipped {} invalid recorded WebSocket messages", skipped);
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
