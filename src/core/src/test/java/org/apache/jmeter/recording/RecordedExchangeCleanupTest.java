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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RecordedExchangeCleanupTest {
    private static RecordedExchangeStore.Archive recording() throws Exception {
        return RecordedExchangeStore.fromHar("""
                {"log":{"entries":[
                  {"request":{"url":"https://example.invalid/site.css","headers":[{"name":"X-Test","value":"ok"}],
                    "postData":{"text":"static-request"}},
                   "response":{"content":{"mimeType":"text/css","text":"shared-body"}}},
                  {"request":{"url":"https://example.invalid/api"},
                   "response":{"content":{"mimeType":"application/json","text":"shared-body"}}},
                  {"request":{"url":"https://example.invalid/removed"},
                   "response":{"content":{"text":"orphan-body"}}}
                ]}}
                """.getBytes(StandardCharsets.UTF_8), "test.har");
    }

    @Test
    void staticBodyCleanupKeepsHeadersAndSharedDynamicBodies() throws Exception {
        var original = recording();
        var cleaned = RecordedExchangeStore.cleanArchive(original.manifestEntryName(), original.entries(),
                RecordingStorageMode.OMIT_STATIC_BODIES, null);
        var staticExchange = cleaned.resolveExchange(original.exchangeIds().get(0)).orElseThrow();
        assertEquals(1, staticExchange.path("request").path("headers").size());
        assertFalse(staticExchange.path("request").has("postData"));
        assertFalse(staticExchange.path("response").path("content").has("text"));
        assertEquals("shared-body", cleaned.resolveExchange(original.exchangeIds().get(1)).orElseThrow()
                .path("response").path("content").path("text").asText());
        assertTrue(original.resolveExchange(original.exchangeIds().get(0)).orElseThrow().path("request").has("postData"));
        assertFalse(cleaned.entries().containsKey("recordings/bodies/"
                + RecordedExchangeStore.sha256Hex("static-request".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void removingStaticsKeepsNonStaticExchanges() throws Exception {
        var original = recording();
        var cleaned = RecordedExchangeStore.cleanArchive(original.manifestEntryName(), original.entries(),
                RecordingStorageMode.OMIT_STATICS, null);
        assertFalse(cleaned.resolveExchange(original.exchangeIds().get(0)).isPresent());
        assertEquals(2, cleaned.exchangeCount());
        assertEquals("shared-body", cleaned.resolveExchange(original.exchangeIds().get(1)).orElseThrow()
                .path("response").path("content").path("text").asText());
    }

    @Test
    void orphanCleanupRemovesUnusedExchangesAndUnreferencedBlobs() throws Exception {
        var original = recording();
        var entries = new LinkedHashMap<>(original.entries());
        entries.put("recordings/bodies/unreferenced", new byte[100]);
        String liveId = original.exchangeIds().get(1);
        var cleaned = RecordedExchangeStore.cleanArchive(original.manifestEntryName(), entries,
                RecordingStorageMode.ALL, Set.of(liveId));
        assertEquals(1, cleaned.exchangeCount());
        assertTrue(cleaned.resolveExchange(liveId).isPresent());
        assertFalse(cleaned.resolveExchange(original.exchangeIds().get(2)).isPresent());
        assertFalse(cleaned.entries().containsKey("recordings/bodies/unreferenced"));
        assertEquals(1, cleaned.entries().keySet().stream().filter(name -> name.startsWith("recordings/bodies/")).count());
    }

    @Test
    void orphanCleanupDropsEmptyReplayCaptureAndKeepsImportedFallback() throws Exception {
        var original = recording();
        var sample = new org.apache.jmeter.samplers.SampleResult();
        sample.setURL(java.net.URI.create("https://example.invalid/removed").toURL());
        sample.setResponseCode("200");
        sample.setResponseData("replayed-orphan", "UTF-8");
        var replayed = RecordedExchangeStore.storeReplay(original.manifestEntryName(), original.entries(),
                original.exchangeIds().get(2), sample);
        String liveId = original.exchangeIds().get(1);
        var cleaned = RecordedExchangeStore.cleanArchive(replayed.manifestEntryName(), replayed.entries(),
                RecordingStorageMode.ALL, Set.of(liveId));
        assertEquals("shared-body", cleaned.resolveExchange(liveId).orElseThrow()
                .path("response").path("content").path("text").asText());
        assertEquals(1, cleaned.entries().keySet().stream().filter(name -> name.endsWith("exchanges.json")).count());
        assertFalse(cleaned.resolveExchange(original.exchangeIds().get(2)).isPresent());
    }

    @Test
    void noneRemovesEveryExchangeAndBody() throws Exception {
        var original = recording();
        var cleaned = RecordedExchangeStore.cleanArchive(original.manifestEntryName(), original.entries(),
                RecordingStorageMode.NONE, null);
        assertEquals(0, cleaned.exchangeCount());
        assertEquals(Set.of(original.manifestEntryName()), cleaned.entries().keySet());
    }
}
