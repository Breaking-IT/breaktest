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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class RecordedExchangeStoreTest {

    @Test
    void replayOverlayReplacesOneSamplerAndPreservesImportedFallbacks() throws Exception {
        byte[] har = ("{\"log\":{\"entries\":["
                + "{\"request\":{\"method\":\"GET\",\"url\":\"https://example.invalid/one\"},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"original-one\"}}},"
                + "{\"request\":{\"method\":\"GET\",\"url\":\"https://example.invalid/two\"},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"original-two\"}}}]}}")
                        .getBytes(StandardCharsets.UTF_8);
        RecordedExchangeStore.Archive imported = RecordedExchangeStore.fromHar(har, "source.har");
        String firstExchangeId = imported.exchangeIds().get(0);
        String secondExchangeId = imported.exchangeIds().get(1);

        RecordedExchangeStore.Archive replayed = RecordedExchangeStore.storeReplay(
                imported.manifestEntryName(), imported.entries(), firstExchangeId, replayResult("replayed-one"));

        assertEquals("replayed-one", replayed.resolveExchange(firstExchangeId).orElseThrow()
                .path("response").path("content").path("text").asText());
        assertEquals("original-two", replayed.resolveExchange(secondExchangeId).orElseThrow()
                .path("response").path("content").path("text").asText());

        RecordedExchangeStore.Archive overwritten = RecordedExchangeStore.storeReplay(
                replayed.manifestEntryName(), replayed.entries(), firstExchangeId, replayResult("newest-one"));

        assertEquals("newest-one", overwritten.resolveExchange(firstExchangeId).orElseThrow()
                .path("response").path("content").path("text").asText());
        assertEquals(replayed.entries().size(), overwritten.entries().size());
        assertFalse(overwritten.entries().values().stream()
                .anyMatch(bytes -> "replayed-one".equals(new String(bytes, StandardCharsets.UTF_8))));
    }

    @Test
    void replayCreatesRecordingWithoutHarImport() throws Exception {
        RecordedExchangeStore.Archive recording = RecordedExchangeStore.storeReplays(
                "", Map.of(), Map.of(
                        "first-exchange", replayResult("captured-response"),
                        "second-exchange", replayResult("second-response")));

        assertEquals(2, recording.exchangeCount());
        assertTrue(recording.manifestEntryName().startsWith("recordings/manifests/"));
        assertEquals("POST", recording.resolveExchange("first-exchange").orElseThrow()
                .path("request").path("method").asText());
        assertEquals("request-body", recording.resolveExchange("first-exchange").orElseThrow()
                .path("request").path("postData").path("text").asText());
        assertEquals("captured-response", recording.resolveExchange("first-exchange").orElseThrow()
                .path("response").path("content").path("text").asText());
        assertEquals("second-response", recording.resolveExchange("second-exchange").orElseThrow()
                .path("response").path("content").path("text").asText());
    }

    @Test
    void replayStorageModesFilterStaticBodiesAndExchanges() throws Exception {
        HttpLikeSampleResult dynamic = replayResult("dynamic-body");
        HttpLikeSampleResult staticResult = replayResult("static-body");
        staticResult.setURL(URI.create("https://example.invalid/assets/application.js").toURL());
        staticResult.setContentType("application/javascript");
        Map<String, SampleResult> samples = Map.of("dynamic", dynamic, "static", staticResult);

        RecordedExchangeStore.Archive withoutStaticBodies = RecordedExchangeStore.storeReplays(
                "", Map.of(), samples, RecordingStorageMode.OMIT_STATIC_BODIES);

        assertEquals("dynamic-body", withoutStaticBodies.resolveExchange("dynamic").orElseThrow()
                .path("response").path("content").path("text").asText());
        assertFalse(withoutStaticBodies.resolveExchange("static").orElseThrow()
                .path("response").path("content").has("text"));
        assertFalse(withoutStaticBodies.resolveExchange("static").orElseThrow()
                .path("request").has("postData"));

        RecordedExchangeStore.Archive withoutStatics = RecordedExchangeStore.storeReplays(
                withoutStaticBodies.manifestEntryName(), withoutStaticBodies.entries(), samples,
                RecordingStorageMode.OMIT_STATICS);

        assertTrue(withoutStatics.resolveExchange("static").isEmpty());
        assertEquals("dynamic-body", withoutStatics.resolveExchange("dynamic").orElseThrow()
                .path("response").path("content").path("text").asText());

        RecordedExchangeStore.Archive none = RecordedExchangeStore.storeReplays(
                withoutStatics.manifestEntryName(), withoutStatics.entries(), samples, RecordingStorageMode.NONE);

        assertTrue(none.resolveExchange("dynamic").isEmpty());
        assertTrue(none.resolveExchange("static").isEmpty());
    }

    private static HttpLikeSampleResult replayResult(String responseBody) throws Exception {
        HttpLikeSampleResult result = new HttpLikeSampleResult();
        result.setURL(URI.create("https://example.invalid/one").toURL());
        result.setRequestHeaders("Content-Type: application/json\nX-Request: replay");
        result.setResponseHeaders("HTTP/1.1 201 Created\nContent-Type: application/json");
        result.setResponseCode("201");
        result.setResponseMessage("Created");
        result.setContentType("application/json; charset=UTF-8");
        result.setDataType(SampleResult.TEXT);
        result.setDataEncoding(StandardCharsets.UTF_8.name());
        result.setResponseData(responseBody, StandardCharsets.UTF_8.name());
        return result;
    }

    public static final class HttpLikeSampleResult extends SampleResult {
        private static final long serialVersionUID = 1L;

        public String getHTTPMethod() {
            return "POST";
        }

        public String getQueryString() {
            return "request-body";
        }

        public String getCookies() {
            return "session=replayed";
        }
    }
}
