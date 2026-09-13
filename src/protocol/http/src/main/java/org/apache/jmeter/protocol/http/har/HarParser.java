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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicHeaderValueParser;
import org.apache.hc.core5.http.message.ParserCursor;
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
    private static final Pattern MULTIPART_BOUNDARY = Pattern.compile(
            "(?:^|;)\\s*boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);

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
        return parseRecording(content).entries();
    }

    public record Recording(List<HarEntry> entries, HarUploadCapture.Result uploads) {
    }

    public static Recording parseRecording(byte[] content) throws IOException {
        ensureRawHarContent(content);
        JsonNode root = MAPPER.readTree(content);
        JsonNode entriesNode = root.path("log").path("entries");
        if (!entriesNode.isArray()) {
            throw new IOException("Not a valid HAR file: missing log.entries array");
        }
        List<HarEntry> entries = new ArrayList<>(entriesNode.size());
        Set<String> observedNetworkRequests = new HashSet<>();
        int index = 0;
        for (JsonNode entryNode : entriesNode) {
            HarEntry entry = parseEntry(entryNode, index);
            String requestKey = entry.getMethod() + '\n' + entry.getUrl();
            if (entry.getFromCache() == null
                    && observedNetworkRequests.contains(requestKey)
                    && looksLikeUnmarkedMemoryCacheReuse(entryNode, entry)) {
                entry.setFromCache("memory");
            } else if (entry.getFromCache() == null) {
                observedNetworkRequests.add(requestKey);
            }
            entries.add(entry);
            index++;
        }
        return new Recording(entries, HarUploadCapture.read(root.path("log"), entries));
    }

    /**
     * Older Chromium recorder builds missed Network.requestServedFromCache. In
     * that case CDP reports a reused GET with only contextual headers, no
     * transferred body bytes, and the previously cached decoded content size.
     */
    private static boolean looksLikeUnmarkedMemoryCacheReuse(JsonNode entryNode, HarEntry entry) {
        if (!"GET".equals(entry.getMethod()) || entry.getResponseStatus() != 200) {
            return false;
        }
        JsonNode response = entryNode.path("response");
        if (!response.has("bodySize")
                || response.path("bodySize").asLong(-1) != 0
                || response.path("content").path("size").asLong(-1) <= 0) {
            return false;
        }
        List<NameValue> headers = entry.getRequestHeaders();
        if (headers.size() > 2) {
            return false;
        }
        for (NameValue header : headers) {
            String name = header.getName();
            if (!"referer".equalsIgnoreCase(name) && !"origin".equalsIgnoreCase(name)) {
                return false;
            }
        }
        return true;
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
        entry.setPostData(parsePostData(request));

        JsonNode response = entryNode.path("response");
        entry.setResponseStatus(response.path("status").asInt(0));
        entry.setResponseRedirectUrl(response.path("redirectURL").asText(""));
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

    private static PostData parsePostData(JsonNode requestNode) {
        JsonNode postDataNode = requestNode.path("postData");
        if (postDataNode.isMissingNode() || postDataNode.isNull()) {
            return null;
        }
        String mimeType = postDataNode.path("mimeType").asText("");
        String text = postDataNode.has("text") ? postDataNode.get("text").asText("") : null;
        List<NameValue> params = new ArrayList<>();
        JsonNode paramsNode = postDataNode.path("params");
        if (paramsNode.isArray()) {
            for (JsonNode item : paramsNode) {
                String value = item.path("value").asText("");
                String fileName = item.path("fileName").asText("");
                byte[] fileContent = !fileName.isBlank()
                                && item.hasNonNull("value")
                                && !"(binary)".equalsIgnoreCase(value.trim())
                        ? value.getBytes(StandardCharsets.UTF_8)
                        : null;
                params.add(new NameValue(
                        item.path("name").asText(""),
                        value,
                        fileName,
                        item.path("contentType").asText(""),
                        fileContent));
            }
        }
        if (text != null && isMultipart(mimeType)) {
            List<NameValue> multipartParams = parseMultipart(
                    mimeType, text, hasCompletePostData(requestNode, text));
            if (multipartParams.stream().anyMatch(NameValue::isFileUpload)) {
                params = mergeRecordedFileContent(multipartParams, params);
            }
        }
        return new PostData(mimeType, text, params);
    }

    static boolean isMultipart(String mimeType) {
        return mimeType != null
                && mimeType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    private static List<NameValue> parseMultipart(String mimeType, String body, boolean contentComplete) {
        Matcher matcher = MULTIPART_BOUNDARY.matcher(mimeType);
        if (!matcher.find()) {
            return List.of();
        }
        String boundary = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        List<NameValue> params = new ArrayList<>();
        for (String rawPart : body.split(Pattern.quote("--" + boundary), -1)) {
            String part = removeLeadingLineBreak(rawPart);
            if (part.isEmpty() || part.startsWith("--")) {
                continue;
            }
            int separator = part.indexOf("\r\n\r\n");
            int separatorLength = 4;
            if (separator < 0) {
                separator = part.indexOf("\n\n");
                separatorLength = 2;
            }
            if (separator < 0) {
                continue;
            }
            String headers = part.substring(0, separator);
            String content = removeTrailingLineBreak(part.substring(separator + separatorLength));
            String disposition = headerValue(headers, "content-disposition");
            if (disposition == null) {
                continue;
            }
            HeaderElement[] elements = BasicHeaderValueParser.INSTANCE.parseElements(
                    disposition, new ParserCursor(0, disposition.length()));
            if (elements.length == 0) {
                continue;
            }
            NameValuePair name = elements[0].getParameterByName("name");
            NameValuePair fileName = elements[0].getParameterByName("filename");
            String recordedFileName = fileName == null ? "" : fileName.getValue();
            params.add(new NameValue(
                    name == null ? "" : name.getValue(),
                    content,
                    recordedFileName,
                    headerValue(headers, "content-type"),
                    fileName != null && contentComplete && !"(binary)".equalsIgnoreCase(content.trim())
                            ? content.getBytes(StandardCharsets.UTF_8)
                            : null));
        }
        return params;
    }

    private static List<NameValue> mergeRecordedFileContent(
            List<NameValue> multipartParams, List<NameValue> recordedParams) {
        List<NameValue> merged = new ArrayList<>(multipartParams.size());
        for (NameValue multipartParam : multipartParams) {
            if (!multipartParam.isFileUpload() || multipartParam.hasFileContent()) {
                merged.add(multipartParam);
                continue;
            }
            NameValue recorded = recordedParams.stream()
                    .filter(NameValue::isFileUpload)
                    .filter(candidate -> candidate.getName().equals(multipartParam.getName()))
                    .filter(candidate -> candidate.getFileName().equals(multipartParam.getFileName()))
                    .filter(NameValue::hasFileContent)
                    .findFirst()
                    .orElse(null);
            merged.add(recorded == null
                    ? multipartParam
                    : new NameValue(
                            multipartParam.getName(),
                            multipartParam.getValue(),
                            multipartParam.getFileName(),
                            multipartParam.getContentType(),
                            recorded.getFileContent()));
        }
        return merged;
    }

    private static boolean hasCompletePostData(JsonNode requestNode, String text) {
        long expectedSize = requestNode.path("bodySize").asLong(-1);
        for (JsonNode header : requestNode.path("headers")) {
            if ("content-length".equalsIgnoreCase(header.path("name").asText(""))) {
                try {
                    expectedSize = Math.max(expectedSize, Long.parseLong(header.path("value").asText("")));
                } catch (NumberFormatException ignored) {
                    // An invalid recorded length gives us no completeness evidence.
                }
                break;
            }
        }
        return expectedSize < 0
                || text.getBytes(StandardCharsets.UTF_8).length >= expectedSize;
    }

    private static String headerValue(String headers, String requestedName) {
        for (String line : headers.split("\\r?\\n")) {
            int separator = line.indexOf(':');
            if (separator > 0 && requestedName.equalsIgnoreCase(line.substring(0, separator).trim())) {
                return line.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static String removeLeadingLineBreak(String value) {
        if (value.startsWith("\r\n")) {
            return value.substring(2);
        }
        return value.startsWith("\n") ? value.substring(1) : value;
    }

    private static String removeTrailingLineBreak(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
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
