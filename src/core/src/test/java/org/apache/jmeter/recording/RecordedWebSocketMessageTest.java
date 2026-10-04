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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class RecordedWebSocketMessageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode entry() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"startedDateTime":"2026-10-03T20:31:13.246Z",
                 "request":{"method":"GET","url":"wss://example.test/client"},
                 "response":{"status":101},
                 "_breaktest":{"webSocket":{"messagesTruncated":true,"droppedMessages":2}},
                 "_webSocketMessages":[
                   {"type":"send","time":1791059473.44524,"opcode":1,"data":"hello"},
                   {"type":"receive","time":1791059474.194004,"opcode":2,"data":"e30e","_encoding":"base64"},
                   {"type":"send","time":1791059474.194306,"opcode":2,"data":"ApEG"}
                 ]}
                """);
    }

    @Test
    void storesBase64AndPreservesSubMillisecondRelativeTimesAndOrder() throws Exception {
        ObjectNode source = entry();
        ((ObjectNode) source.path("_webSocketMessages").get(0))
                .put("data", "héllo" + (char) 30);
        byte[] har = JSON.writeValueAsBytes(JSON.createObjectNode()
                .set("log", JSON.createObjectNode().set("entries", JSON.createArrayNode().add(source))));
        RecordedExchangeStore.Archive archive = RecordedExchangeStore.fromHar(har, "websocket.har");
        var stored = archive.resolveExchange(archive.exchangeIds().get(0)).orElseThrow();
        assertTrue(stored.path("webSocket").path("messagesTruncated").asBoolean());
        var messages = RecordedWebSocketMessage.fromExchange(stored);
        assertEquals(3, messages.size());
        assertEquals(0, new BigDecimal("199.24").compareTo(messages.get(0).relativeTimeMs()));
        assertEquals(0, new BigDecimal("948.004").compareTo(messages.get(1).relativeTimeMs()));
        assertEquals("send", messages.get(0).direction());
        assertEquals("receive", messages.get(1).direction());
        assertEquals(Base64.getEncoder().encodeToString(("héllo" + (char) 30).getBytes(StandardCharsets.UTF_8)),
                messages.get(0).data());
        assertEquals("héllo" + (char) 30, messages.get(0).text());
        assertEquals("7b 7d 1e", messages.get(1).hex());
        assertEquals("02 91 06", messages.get(2).hex());
        assertEquals(RecordedWebSocketMessage.fromHar(source), messages);
        assertEquals("base64", stored.path("webSocketMessages").get(0).path("_encoding").asText());
    }

    @Test
    void handlesEmptyAndBase64TextFrames() throws Exception {
        ObjectNode source = entry();
        source.withArray("_webSocketMessages").removeAll();
        assertTrue(RecordedWebSocketMessage.fromHar(source).isEmpty());
        source.withArray("_webSocketMessages").addObject().put("type", "send")
                .put("time", new BigDecimal("1791059473.246")).put("opcode", 1)
                .put("data", "aGk=").put("_encoding", "base64");
        assertEquals("hi", RecordedWebSocketMessage.fromHar(source).get(0).text());
    }

    @Test
    void rejectsCorruptBinaryRatherThanSilentlyChangingItsBytes() throws Exception {
        ObjectNode source = entry();
        ((ObjectNode) source.path("_webSocketMessages").get(1)).put("data", "%%%");
        assertThrows(IllegalArgumentException.class, () -> RecordedWebSocketMessage.fromHar(source));
    }
}
