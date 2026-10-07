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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class HarCacheImportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode recording() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"log":{
                  "creator":{"name":"BreakTest Browser Recorder","version":"1.3.0"},
                  "_breaktest":{"recordedWith":"chrome.debugger"},
                  "entries":[{
                    "startedDateTime":"2026-10-07T10:42:47.494Z",
                    "time":0.045,
                    "serverIPAddress":"192.0.2.1",
                    "connection":"123",
                    "timings":{"blocked":0,"dns":-1,"connect":-1,"ssl":-1,
                               "send":0.02,"wait":30.125,"receive":0},
                    "_breaktest":{"timingSource":"network","failed":false,"incomplete":false},
                    "request":{"method":"GET","url":"https://example.test/assets/script.js",
                               "httpVersion":"http/2.0","headers":[{"name":"Referer","value":"https://example.test/"}]},
                    "response":{"status":200,"bodySize":0,"_transferSize":1182,
                                "content":{"size":1182,"mimeType":"application/javascript"}}
                  }]
                }}
                """);
    }

    private static ObjectNode entry(ObjectNode recording) {
        return (ObjectNode) recording.path("log").path("entries").get(0);
    }

    private static List<HarEntry> parse(ObjectNode recording) throws Exception {
        return HarParser.parse(MAPPER.writeValueAsBytes(recording));
    }

    private static List<HTTPSamplerProxy> samplers(List<HarEntry> entries) {
        HashTree tree = new HarConverter(entries, new HarImportOptions(), "cache.har", "test")
                .convert(Set.of("example.test"));
        List<HTTPSamplerProxy> result = new ArrayList<>();
        collect(tree, result);
        return result;
    }

    private static void collect(HashTree tree, List<HTTPSamplerProxy> result) {
        for (Object element : tree.list()) {
            if (element instanceof HTTPSamplerProxy sampler) {
                result.add(sampler);
            }
            collect(tree.getTree(element), result);
        }
    }

    @Test
    void skipsLegacyCacheHitWithoutEarlierUrlOccurrence() throws Exception {
        List<HarEntry> entries = parse(recording());
        assertEquals("memory", entries.get(0).getFromCache());
        assertEquals(0, samplers(entries).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "disk", "firefox-cache"})
    void skipsExplicitCacheMarkersFromNewRecorders(String marker) throws Exception {
        ObjectNode recording = recording();
        ObjectNode entry = entry(recording);
        entry.put("_fromCache", marker);
        entry.put("serverIPAddress", "");
        entry.put("connection", "");
        ((ObjectNode) entry.path("_breaktest")).put("timingSource", "cache");
        ((ObjectNode) entry.path("response")).put("_transferSize", 0);
        ((ObjectNode) entry.path("timings")).put("blocked", 0.045).put("send", 0).put("wait", 0);
        List<HarEntry> entries = parse(recording);
        assertEquals(marker, entries.get(0).getFromCache());
        assertEquals(0, samplers(entries).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherCreator", "otherBackend", "missingTransfer", "zeroTransfer", "unknownTransfer",
            "differentSize", "positiveBody", "emptyContent", "consistentTiming", "rounding", "missingTime",
            "missingWait", "noTimingSource", "failed", "incomplete", "post", "notModified", "networkHeaders"})
    void retainsAmbiguousAndNetworkRequests(String variation) throws Exception {
        ObjectNode recording = recording();
        ObjectNode entry = entry(recording);
        ObjectNode response = (ObjectNode) entry.path("response");
        ObjectNode metadata = (ObjectNode) entry.path("_breaktest");
        switch (variation) {
        case "otherCreator" -> ((ObjectNode) recording.path("log").path("creator")).put("name", "Other recorder");
        case "otherBackend" -> ((ObjectNode) recording.path("log").path("_breaktest")).put("recordedWith", "firefox");
        case "missingTransfer" -> response.remove("_transferSize");
        case "zeroTransfer" -> response.put("_transferSize", 0);
        case "unknownTransfer" -> response.put("_transferSize", -1);
        case "differentSize" -> ((ObjectNode) response.path("content")).put("size", 2000);
        case "positiveBody" -> response.put("bodySize", 1182);
        case "emptyContent" -> ((ObjectNode) response.path("content")).put("size", 0);
        case "consistentTiming" -> entry.put("time", 40);
        case "rounding" -> entry.put("time", 30);
        case "missingTime" -> entry.remove("time");
        case "missingWait" -> ((ObjectNode) entry.path("timings")).remove("wait");
        case "noTimingSource" -> metadata.remove("timingSource");
        case "failed" -> metadata.put("failed", true);
        case "incomplete" -> metadata.put("incomplete", true);
        case "post" -> ((ObjectNode) entry.path("request")).put("method", "POST");
        case "notModified" -> response.put("status", 304);
        case "networkHeaders" -> ((ObjectNode) entry.path("request").path("headers").get(0)).put("name", "If-None-Match");
        default -> throw new IllegalArgumentException(variation);
        }
        List<HarEntry> entries = parse(recording);
        assertNull(entries.get(0).getFromCache(), variation);
        assertEquals(1, samplers(entries).size(), variation);
    }

    @Test
    void zeroTransferWithoutCacheMarkerStaysReplayableEvenForRepeatedUrl() throws Exception {
        ObjectNode recording = recording();
        ((ObjectNode) entry(recording).path("response")).put("_transferSize", 0);
        recording.withObject("log").withArray("entries").add(entry(recording).deepCopy());
        List<HarEntry> entries = parse(recording);
        assertNull(entries.get(0).getFromCache());
        assertNull(entries.get(1).getFromCache());
        assertEquals(2, samplers(entries).size());
    }
}
