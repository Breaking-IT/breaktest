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

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses a HAR (HTTP Archive) document into a list of {@link HarEntry}.
 * Faithful port of the field access done by the BreakTest Python
 * {@code har2jmx.py} converter, including the queue/blocking start offset used
 * for parallel-request grouping.
 */
public final class HarParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HarParser() {
    }

    /**
     * Parse raw HAR content into entries, tagging each with its original index.
     * Entries are returned in file order.
     *
     * @param content raw bytes of a {@code .har} file
     * @return the parsed entries
     * @throws IOException if the content cannot be read or is not valid HAR
     */
    public static List<HarEntry> parse(byte[] content) throws IOException {
        ensureRawHarContent(content);
        JsonNode root = MAPPER.readTree(content);
        JsonNode entriesNode = root.path("log").path("entries");
        if (!entriesNode.isArray()) {
            throw new IOException("Not a valid HAR file: missing log.entries array");
        }
        List<HarEntry> entries = new ArrayList<>(entriesNode.size());
        int index = 0;
        for (JsonNode entryNode : entriesNode) {
            entries.add(parseEntry(entryNode, index));
            index++;
        }
        return entries;
    }

    private static void ensureRawHarContent(byte[] content) throws IOException {
        if (content.length >= 2 && (content[0] & 0xff) == 0x1f && (content[1] & 0xff) == 0x8b) {
            throw new IOException("Compressed HAR files are not supported. Export an uncompressed .har file.");
        }
        if (isZipSignature(content)) {
            throw new IOException("ZIP files are not supported. Export an uncompressed .har file.");
        }
    }

    private static boolean isZipSignature(byte[] content) {
        if (content.length < 4 || (content[0] & 0xff) != 0x50 || (content[1] & 0xff) != 0x4b) {
            return false;
        }
        int third = content[2] & 0xff;
        int fourth = content[3] & 0xff;
        return (third == 0x03 && fourth == 0x04)
                || (third == 0x05 && fourth == 0x06)
                || (third == 0x07 && fourth == 0x08);
    }

    private static HarEntry parseEntry(JsonNode entryNode, int index) {
        HarEntry entry = new HarEntry();
        entry.setOriginalIndex(index);

        JsonNode request = entryNode.path("request");
        entry.setMethod(request.path("method").asText("GET"));
        entry.setUrl(request.path("url").asText(""));
        entry.setProtocol(resolveProtocol(entryNode, request));
        if (entryNode.hasNonNull("_fromCache")) {
            entry.setFromCache(entryNode.get("_fromCache").asText());
        }
        if (entryNode.hasNonNull("serverIPAddress")) {
            entry.setServerIpAddress(entryNode.get("serverIPAddress").asText());
        }

        String startedDateTime = entryNode.path("startedDateTime").asText("");
        entry.setStartedDateTime(startedDateTime);
        JsonNode breakTest = entryNode.path("_breaktest");
        entry.setTransactionId(firstText(
                breakTest.path("transactionId"), entryNode.path("_breaktestTransactionId")));
        entry.setTransactionName(firstText(
                breakTest.path("transactionName"), entryNode.path("_breaktestTransactionName")));
        double started = parseStartedMillis(startedDateTime);
        double time = entryNode.path("time").asDouble(0);
        JsonNode timings = entryNode.path("timings");
        double offset = requestStartOffsetMs(entryNode, timings);
        double startMs = started + offset;
        double endMs = started + time;
        if (endMs < startMs) {
            endMs = startMs;
        }
        entry.setStartMs(startMs);
        entry.setEndMs(endMs);
        entry.setHasPositiveTiming(hasPositiveTiming(time, timings));

        readNameValues(request.path("headers"), entry.getRequestHeaders());
        readNameValues(request.path("queryString"), entry.getQueryString());
        entry.setPostData(parsePostData(request.path("postData")));

        JsonNode response = entryNode.path("response");
        entry.setResponseStatus(response.path("status").asInt(0));
        readNameValues(response.path("headers"), entry.getResponseHeaders());
        entry.setResponseContentText(response.path("content").path("text").asText(""));
        return entry;
    }

    private static String firstText(JsonNode primary, JsonNode fallback) {
        String value = primary.asText("");
        return value.isEmpty() ? fallback.asText("") : value;
    }

    /** Mirrors get_har_protocol: first non-empty of _protocol/protocol/httpVersion, lower-cased. */
    private static String resolveProtocol(JsonNode entryNode, JsonNode request) {
        String[] candidates = {
                entryNode.path("_protocol").asText(""),
                entryNode.path("protocol").asText(""),
                request.path("httpVersion").asText(""),
        };
        for (String candidate : candidates) {
            if (!candidate.isEmpty()) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    /** Mirrors request_start_offset_ms: the max (not sum) of the queue/blocking candidates. */
    private static double requestStartOffsetMs(JsonNode entryNode, JsonNode timings) {
        double[] candidates = {
                timings.path("blocked").asDouble(0),
                timings.path("_blocked_queueing").asDouble(0),
                timings.path("blocked_queueing").asDouble(0),
                timings.path("queueing").asDouble(0),
                timings.path("_queueing").asDouble(0),
                entryNode.path("_blocked_queueing").asDouble(0),
                entryNode.path("blocked_queueing").asDouble(0),
        };
        double max = 0;
        for (double candidate : candidates) {
            if (candidate > max) {
                max = candidate;
            }
        }
        return max;
    }

    private static boolean hasPositiveTiming(double time, JsonNode timings) {
        if (time > 0) {
            return true;
        }
        String[] keys = {"dns", "connect", "ssl", "send", "wait", "receive"};
        for (String key : keys) {
            if (timings.path(key).asDouble(0) > 0) {
                return true;
            }
        }
        return false;
    }

    private static void readNameValues(JsonNode arrayNode, List<NameValue> target) {
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                target.add(new NameValue(item.path("name").asText(""), item.path("value").asText("")));
            }
        }
    }

    private static PostData parsePostData(JsonNode postDataNode) {
        if (postDataNode.isMissingNode() || postDataNode.isNull()) {
            return null;
        }
        String mimeType = postDataNode.path("mimeType").asText("");
        String text = postDataNode.has("text") ? postDataNode.get("text").asText("") : null;
        List<NameValue> params = new ArrayList<>();
        readNameValues(postDataNode.path("params"), params);
        return new PostData(mimeType, text, params);
    }

    /** Parse a HAR ISO-8601 timestamp to epoch millis, tolerating a missing zone offset. */
    private static double parseStartedMillis(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
