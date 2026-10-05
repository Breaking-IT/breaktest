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
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Captured event, including the transaction active when it arrived. */
public record RecordedSseEvent(BigDecimal relativeTimeMs, String eventName, String eventId,
        String data, String transactionId, String transactionName) {
    public static List<RecordedSseEvent> fromHar(JsonNode entry) {
        var start = HarTimestamp.parse(entry.path("startedDateTime").asText());
        if (start.isEmpty() || !entry.path("_serverSentEvents").isArray()) {
            return List.of();
        }
        var instant = start.get();
        BigDecimal seconds = BigDecimal.valueOf(instant.getEpochSecond())
                .add(BigDecimal.valueOf(instant.getNano(), 9));
        List<RecordedSseEvent> events = new ArrayList<>();
        for (JsonNode event : entry.path("_serverSentEvents")) {
            if (!event.path("time").isNumber() || !event.path("data").isTextual()
                    || !"receive".equals(event.path("type").asText("receive"))) {
                continue;
            }
            events.add(new RecordedSseEvent(event.path("time").decimalValue().subtract(seconds).movePointRight(3),
                    event.path("eventName").asText("message"), event.path("eventId").asText(),
                    event.path("data").asText(), event.path("_breaktest").path("transactionId").asText(),
                    event.path("_breaktest").path("transactionName").asText()));
        }
        return List.copyOf(events);
    }

    public static void copyToArchive(JsonNode entry, ArrayNode target, JsonNode transactions) {
        for (RecordedSseEvent event : fromHar(entry)) {
            String name = event.transactionName();
            if (name.isEmpty() && !event.transactionId().isEmpty()) {
                for (JsonNode transaction : transactions) {
                    if (event.transactionId().equals(transaction.path("id").asText())) {
                        name = transaction.path("name").asText();
                        break;
                    }
                }
            }
            target.addObject().put("relativeTimeMs", event.relativeTimeMs())
                    .put("eventName", event.eventName()).put("eventId", event.eventId()).put("data", event.data())
                    .put("transactionId", event.transactionId()).put("transactionName", name);
        }
    }

    public static List<RecordedSseEvent> fromExchange(JsonNode entry) {
        if (!entry.has("serverSentEvents")) {
            return fromHar(entry);
        }
        List<RecordedSseEvent> events = new ArrayList<>();
        for (JsonNode event : entry.path("serverSentEvents")) {
            events.add(new RecordedSseEvent(event.path("relativeTimeMs").decimalValue(),
                    event.path("eventName").asText("message"), event.path("eventId").asText(),
                    event.path("data").asText(), event.path("transactionId").asText(),
                    event.path("transactionName").asText()));
        }
        return List.copyOf(events);
    }
}
