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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class HarArchiveFilterTest extends JMeterTestCase {

    private static final String HAR = "{\"log\":{\"version\":\"1.2\",\"entries\":["
            + entry("2026-01-01T00:00:00Z", "https://api.example.com/first", null) + ','
            + entry("2026-01-01T00:00:01Z", "https://excluded.example.com/large.js", null) + ','
            + entry("2026-01-01T00:00:02Z", "https://api.example.com/cached", "memory") + ','
            + entry("2026-01-01T00:00:03Z", "https://api.example.com/site.css", null) + ','
            + entry("2026-01-01T00:00:04Z", "https://api.example.com/second", null)
            + "]}}";

    @Test
    void importsWebSocketConnectionAndLinksItsRecordedMessages() throws Exception {
        byte[] har = """
                {"log":{"entries":[{
                  "startedDateTime":"2026-10-03T20:31:13.246Z","time":0,
                  "request":{"method":"GET","url":"wss://api.example.com/client?hub=live",
                    "headers":[{"name":"Connection","value":"Upgrade"},
                      {"name":"Sec-WebSocket-Key","value":"old-key"},
                      {"name":"Origin","value":"https://example.com"}]},
                  "response":{"status":101,"headers":[
                    {"name":"Upgrade","value":"websocket"},
                    {"name":"Set-Cookie","value":"first=1"},
                    {"name":"Set-Cookie","value":"second=2"}]},
                  "_breaktest":{"webSocket":{"closed":true,"closeInitiator":"client",
                    "closeInitiatedTime":1791059475,"closedTime":1791059475.1}},
                  "_webSocketMessages":[
                    {"type":"send","time":1791059473.44524,"opcode":1,"data":"hello"},
                    {"type":"receive","time":1791059474.194004,"opcode":2,"data":"e30e","_encoding":"base64"}]
                }]}}
                """.getBytes(StandardCharsets.UTF_8);
        HashTree tree = convert(har);
        var sampler = find(tree, org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler.class);
        assertEquals("wss://api.example.com/client?hub=live", sampler.getUrl());
        assertEquals(List.of("Origin"), sampler.getHeaders().stream().map(header -> header.getName()).toList());
        var archive = HarArchiveFilter.filterAndRelink(har, tree, "socket.har", RecordingStorageMode.ALL)
                .orElseThrow();
        String id = sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY);
        var exchange = archive.resolveExchange(id).orElseThrow();
        var headers = exchange.path("response").path("headers");
        assertEquals(3, headers.size());
        assertEquals("first=1", headers.get(1).path("value").asText());
        assertEquals("second=2", headers.get(2).path("value").asText());
        assertEquals("client", exchange.path("webSocket").path("closeInitiator").asText());
        var messages = org.apache.jmeter.recording.RecordedWebSocketMessage
                .fromExchange(archive.resolveExchange(id).orElseThrow());
        assertEquals(2, messages.size());
        assertEquals("hello", messages.get(0).text());
        assertEquals("7b 7d 1e", messages.get(1).hex());

        HashTree withoutRecording = convert(har);
        HarArchiveFilter.filterAndRelink(har, withoutRecording, "socket.har", RecordingStorageMode.NONE);
        var unlinked = find(withoutRecording, org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler.class);
        assertEquals("", unlinked.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
    }

    @Test
    void keepsOnlyEntriesThatProducedSamplersAndRemapsTheirIndexes() throws Exception {
        byte[] originalHar = harContent();
        HashTree tree = convert(originalHar);

        RecordedExchangeStore.Archive archive =
                HarArchiveFilter.filterAndRelink(originalHar, tree, "source.har",
                        RecordingStorageMode.ALL).orElseThrow();

        assertEquals(3, archive.exchangeCount());
        assertEquals("https://api.example.com/first", exchange(archive, 0).path("request").path("url").asText());
        assertEquals("https://api.example.com/site.css", exchange(archive, 1).path("request").path("url").asText());
        assertEquals("https://api.example.com/second", exchange(archive, 2).path("request").path("url").asText());
        assertEquals(3, archive.entries().keySet().stream()
                .filter(name -> name.startsWith("recordings/bodies/"))
                .count());

        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(tree, HTTPSamplerProxy.class, samplers);
        assertEquals(3, samplers.size());
        assertEquals(archive.exchangeIds().get(0),
                samplers.get(0).getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        assertEquals(archive.exchangeIds().get(1),
                samplers.get(1).getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        assertEquals(archive.exchangeIds().get(2),
                samplers.get(2).getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        assertEquals("", samplers.get(0).getPropertyAsString(RecordedHarExchangeResolver.HAR_ENTRY_INDEX));

        ThreadGroup threadGroup = find(tree, ThreadGroup.class);
        assertEquals(archive.checksum(),
                threadGroup.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY));
        assertEquals(archive.manifestEntryName(),
                threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        assertEquals("", threadGroup.getPropertyAsString(RecordedHarExchangeResolver.HAR_FILENAME));
    }

    @Test
    void omitsBodiesForStaticResourcesButKeepsTheirExchangeMetadata() throws Exception {
        byte[] originalHar = harContent();
        HashTree tree = convert(originalHar);

        RecordedExchangeStore.Archive archive =
                HarArchiveFilter.filterAndRelink(originalHar, tree, "source.har",
                        RecordingStorageMode.OMIT_STATIC_BODIES).orElseThrow();

        assertEquals(3, archive.exchangeCount());
        assertTrue(exchange(archive, 0).path("response").path("content").has("text"));
        JsonNode staticEntry = exchange(archive, 1);
        assertFalse(staticEntry.path("request").has("postData"));
        assertFalse(staticEntry.path("response").path("content").has("text"));
        assertFalse(staticEntry.path("response").path("content").has("encoding"));
        assertEquals("text/css", staticEntry.path("response").path("content").path("mimeType").asText());
    }

    @Test
    void omitsStaticExchangesAndClearsTheirSamplerLink() throws Exception {
        byte[] originalHar = harContent();
        HashTree tree = convert(originalHar);
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(tree, HTTPSamplerProxy.class, samplers);
        HTTPSamplerProxy staticSampler = samplers.get(1);

        RecordedExchangeStore.Archive archive =
                HarArchiveFilter.filterAndRelink(originalHar, tree, "source.har",
                        RecordingStorageMode.OMIT_STATICS).orElseThrow();

        assertEquals(2, archive.exchangeCount());
        assertEquals("https://api.example.com/first", exchange(archive, 0).path("request").path("url").asText());
        assertEquals("https://api.example.com/second", exchange(archive, 1).path("request").path("url").asText());
        assertEquals("", staticSampler.getPropertyAsString(RecordedHarExchangeResolver.HAR_ENTRY_INDEX));
        assertEquals("", staticSampler.getPropertyAsString(RecordedHarExchangeResolver.HAR_REQUEST_URL));
        assertEquals("", staticSampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        assertEquals(archive.exchangeIds().get(1),
                samplers.get(2).getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
    }

    @Test
    void noneRemovesAllRecordingLinksAndProducesNoArchiveEntry() throws Exception {
        byte[] originalHar = harContent();
        HashTree tree = convert(originalHar);
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(tree, HTTPSamplerProxy.class, samplers);

        assertTrue(HarArchiveFilter.filterAndRelink(originalHar, tree, "source.har",
                RecordingStorageMode.NONE).isEmpty());

        for (HTTPSamplerProxy sampler : samplers) {
            assertEquals("", sampler.getPropertyAsString(RecordedHarExchangeResolver.HAR_ENTRY_INDEX));
            assertEquals("", sampler.getPropertyAsString(RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME));
            assertEquals("", sampler.getPropertyAsString(RecordedHarExchangeResolver.HAR_REQUEST_METHOD));
            assertEquals("", sampler.getPropertyAsString(RecordedHarExchangeResolver.HAR_REQUEST_URL));
            assertEquals("", sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        }
        ThreadGroup threadGroup = find(tree, ThreadGroup.class);
        assertEquals("", threadGroup.getPropertyAsString(RecordedHarExchangeResolver.HAR_FILENAME));
        assertEquals("", threadGroup.getPropertyAsString(RecordedHarExchangeResolver.HAR_MD5));
        assertEquals("", threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        assertEquals("", threadGroup.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY));
    }

    @Test
    void estimatesCompressedStoredSizeForEveryStorageMode() throws Exception {
        byte[] originalHar = harContent();
        Map<RecordingStorageMode, Long> estimates = HarArchiveFilter.estimateStoredSizes(
                originalHar, convert(originalHar), "source.har");

        assertTrue(estimates.get(RecordingStorageMode.ALL) > 0);
        assertTrue(estimates.get(RecordingStorageMode.OMIT_STATIC_BODIES)
                <= estimates.get(RecordingStorageMode.ALL));
        assertTrue(estimates.get(RecordingStorageMode.OMIT_STATICS)
                <= estimates.get(RecordingStorageMode.OMIT_STATIC_BODIES));
        assertEquals(0L, estimates.get(RecordingStorageMode.NONE));
    }

    private static JsonNode exchange(RecordedExchangeStore.Archive archive, int index) throws Exception {
        return archive.resolveExchange(archive.exchangeIds().get(index)).orElseThrow();
    }

    private static byte[] harContent() {
        return HAR.getBytes(StandardCharsets.UTF_8);
    }

    private static HashTree convert(byte[] originalHar) throws Exception {
        List<HarEntry> entries = HarParser.parse(originalHar);
        return new HarConverter(entries, new HarImportOptions(), "source.har", "original-md5")
                .convert(Set.of("api.example.com"));
    }

    private static String entry(String startedDateTime, String url, String fromCache) {
        String cache = fromCache == null ? "" : ",\"_fromCache\":\"" + fromCache + "\"";
        boolean staticResource = url.endsWith(".css");
        String postData = staticResource
                ? ",\"postData\":{\"mimeType\":\"text/plain\",\"text\":\"static-request-body\"}"
                : "";
        String mimeType = staticResource ? "text/css" : "application/json";
        String encoding = staticResource ? "\"encoding\":\"base64\"," : "";
        String responseBody = staticResource ? "bGFyZ2Utc3RhdGljLXJlc3BvbnNlLWJvZHk="
                : "large-response-body-for-filtering";
        return "{\"startedDateTime\":\"" + startedDateTime + "\",\"time\":10,"
                + "\"serverIPAddress\":\"127.0.0.1\"" + cache + ','
                + "\"request\":{\"method\":\"GET\",\"url\":\"" + url
                + "\",\"httpVersion\":\"HTTP/1.1\",\"headers\":[]" + postData + "},"
                + "\"response\":{\"status\":200,\"headers\":[],"
                + "\"content\":{\"mimeType\":\"" + mimeType
                + "\"," + encoding + "\"text\":\"" + responseBody + "\"}},"
                + "\"timings\":{\"wait\":10}}";
    }

    private static <T> T find(HashTree tree, Class<T> type) {
        for (Object item : tree.list()) {
            if (type.isInstance(item)) {
                return type.cast(item);
            }
            T found = find(tree.getTree(item), type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static <T> void collect(HashTree tree, Class<T> type, List<T> target) {
        for (Object item : tree.list()) {
            if (type.isInstance(item)) {
                target.add(type.cast(item));
            }
            collect(tree.getTree(item), type, target);
        }
    }
}
