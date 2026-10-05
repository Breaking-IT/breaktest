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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordedSseEvent;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class HarSseTest {
    private static final String HAR = """
            {"log":{"_breaktest":{"transactions":[
                {"id":"t1","name":"Open connections"},{"id":"t2","name":"Send chat message"}]},
              "entries":[{"startedDateTime":"2027-01-15T08:00:00.300Z","time":8000,
                "request":{"method":"GET","url":"https://example.test/events"},
                "response":{"status":200,"content":{"mimeType":"text/event-stream"}},
                "timings":{"wait":50,"receive":7950},"_resourceType":"EventSource",
                "_breaktest":{"transactionId":"t1","transactionName":"Open connections",
                  "sse":{"captureEnd":"stream-ended","messagesTruncated":true,"droppedMessages":2}},
                "_serverSentEvents":[
                  {"type":"receive","time":1800000000.4,"eventName":"ready","eventId":"evt-100",
                   "data":"connected","_breaktest":{"transactionId":"t1"}},
                  {"type":"receive","time":1800000005.18,"eventName":"chat-message","eventId":"evt-101",
                   "data":"First line\\nSecond line","_breaktest":{"transactionId":"t2"}}]}]}}
            """;

    @Test
    void importsHttpStreamAndPreservesEventsTransactionsAndCaptureMetadata() throws Exception {
        byte[] har = HAR.getBytes(StandardCharsets.UTF_8);
        var entries = HarParser.parse(har);
        assertTrue(entries.get(0).isServerSentEvents());
        assertEquals(50, entries.get(0).getEndMs() - entries.get(0).getStartMs());
        var tree = new HarConverter(entries, new HarImportOptions(), "events.har", "checksum")
                .convert(Set.of("example.test"));
        HTTPSamplerProxy sampler = find(tree);
        assertTrue(sampler instanceof org.apache.jmeter.protocol.sse.SseSampler);
        assertEquals(org.apache.jmeter.protocol.sse.SseSamplerGui.class.getName(),
                sampler.getPropertyAsString(org.apache.jmeter.testelement.TestElement.GUI_CLASS));
        assertTrue(sampler.isSseEnabled());
        assertEquals("sse-1", sampler.getSseSessionName());
        assertEquals("/events", sampler.getPath());
        var archive = RecordedExchangeStore.fromHar(har, "events.har");
        var exchange = archive.resolveExchange(archive.exchangeIds().get(0)).orElseThrow();
        var events = RecordedSseEvent.fromExchange(exchange);
        assertEquals(2, events.size());
        assertEquals(0, new BigDecimal("100").compareTo(events.get(0).relativeTimeMs()));
        assertEquals("ready", events.get(0).eventName());
        assertEquals("evt-101", events.get(1).eventId());
        assertEquals("First line\nSecond line", events.get(1).data());
        assertEquals("t2", events.get(1).transactionId());
        assertEquals("Send chat message", events.get(1).transactionName());
        assertTrue(exchange.path("sse").path("messagesTruncated").asBoolean());
        assertEquals(2, exchange.path("sse").path("droppedMessages").asInt());
    }

    @Test
    void detectsContentTypeWithoutRecorderExtensionsAndKeepsZeroTimingStreams() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode har = (ObjectNode) json.readTree(HAR);
        ObjectNode entry = (ObjectNode) har.path("log").path("entries").get(0);
        entry.remove(java.util.List.of("_resourceType", "_breaktest", "_serverSentEvents", "timings"));
        entry.put("time", 0);
        ((ObjectNode) entry.path("response")).remove("content");
        ((ObjectNode) entry.path("response")).putArray("headers").addObject()
                .put("name", "CONTENT-TYPE").put("value", "text/event-stream; charset=utf-8");
        var entries = HarParser.parse(json.writeValueAsBytes(har));
        assertTrue(entries.get(0).isServerSentEvents());
        assertTrue(find(new HarConverter(entries, new HarImportOptions(), "events.har", "checksum")
                .convert(Set.of("example.test"))).isSseEnabled());
    }

    @Test
    void numbersOnlyImportedSseSessionsAndRestartsForEachConversion() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode har = (ObjectNode) json.readTree(HAR);
        ObjectNode stream = (ObjectNode) har.path("log").path("entries").get(0);
        var requests = ((ObjectNode) har.path("log")).putArray("entries");
        ObjectNode homepage = stream.deepCopy();
        homepage.remove(java.util.List.of("_resourceType", "_serverSentEvents", "_breaktest"));
        ((ObjectNode) homepage.path("request")).put("url", "https://example.test/");
        ((ObjectNode) homepage.path("response").path("content")).put("mimeType", "text/html");
        homepage.put("startedDateTime", "2027-01-15T08:00:00Z");
        requests.add(homepage);
        ObjectNode excluded = stream.deepCopy();
        ((ObjectNode) excluded.path("request")).put("url", "https://excluded.test/events");
        requests.add(excluded);
        requests.add(stream);
        requests.add(homepage.deepCopy().put("startedDateTime", "2027-01-15T08:00:01Z"));
        requests.add(stream.deepCopy().put("startedDateTime", "2027-01-15T08:00:02Z"));
        var converter = new HarConverter(HarParser.parse(json.writeValueAsBytes(har)),
                new HarImportOptions(), "events.har", "checksum");
        for (int attempt = 0; attempt < 2; attempt++) {
            var imported = new java.util.ArrayList<HTTPSamplerProxy>();
            collectSse(converter.convert(Set.of("example.test")), imported);
            assertEquals(java.util.List.of("sse-1", "sse-2"),
                    imported.stream().map(HTTPSamplerProxy::getSseSessionName).toList());
            assertEquals(java.util.List.of("2", "4"), imported.stream().map(sampler -> sampler.getPropertyAsString(
                    org.apache.jmeter.gui.util.RecordedHarExchangeResolver.HAR_ENTRY_INDEX)).toList(),
                    "Session numbering must not change the original recording references");
        }
    }

    private static void collectSse(HashTree tree, java.util.List<HTTPSamplerProxy> result) {
        for (Object element : tree.list()) {
            if (element instanceof org.apache.jmeter.protocol.sse.SseSampler sampler) {
                result.add(sampler);
            }
            collectSse(tree.getTree(element), result);
        }
    }

    private static HTTPSamplerProxy find(HashTree tree) {
        for (Object element : tree.list()) {
            if (element instanceof HTTPSamplerProxy sampler) {
                return sampler;
            }
            HTTPSamplerProxy nested = find(tree.getTree(element));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
