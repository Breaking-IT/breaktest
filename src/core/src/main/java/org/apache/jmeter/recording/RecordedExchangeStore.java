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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.JmxArchiveEntryStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the versioned BreakTest recording format stored inside a
 * compressed JMX archive. Metadata stays as compact JSON, while request and
 * response bodies are content-addressed ZIP entries so captures can share
 * identical payloads.
 */
public final class RecordedExchangeStore {

    public static final String MANIFEST_PROPERTY = "BreakTest.recording.manifest"; // $NON-NLS-1$
    public static final String CHECKSUM_PROPERTY = "BreakTest.recording.checksum"; // $NON-NLS-1$
    public static final String EXCHANGE_ID_PROPERTY = "BreakTest.recording.exchangeId"; // $NON-NLS-1$

    private static final String MANIFEST_FORMAT = "breaktest-recording"; // $NON-NLS-1$
    private static final String EXCHANGES_FORMAT = "breaktest-recording-exchanges"; // $NON-NLS-1$
    private static final String BODY_REFERENCE = "_breaktestBodyRef"; // $NON-NLS-1$
    private static final String BODY_ENCODING = "_breaktestBodyEncoding"; // $NON-NLS-1$
    private static final String BASE64_ENCODING = "base64"; // $NON-NLS-1$
    private static final String UTF8_ENCODING = "utf8"; // $NON-NLS-1$
    private static final Pattern STATIC_URL = Pattern.compile(
            "(?:\\.|/)(?:css|js|mjs|map|png|jpe?g|gif|svg|webp|avif|ico|woff2?|ttf|otf|eot|wasm|"
                    + "mp3|mp4|webm|ogg|wav)(?:[?#/]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final int FORMAT_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordedExchangeStore() {
    }

    /** Creates a one-capture native recording archive from filtered HAR content. */
    public static Archive fromHar(byte[] harContent, String sourceName) throws IOException {
        JsonNode parsed = JSON.readTree(harContent);
        JsonNode harEntries = parsed.path("log").path("entries"); // $NON-NLS-1$ //$NON-NLS-2$
        if (!harEntries.isArray()) {
            throw new IOException("Not a valid HAR file: missing log.entries array"); // $NON-NLS-1$
        }

        String recordingId = UUID.randomUUID().toString();
        String captureId = UUID.randomUUID().toString();
        String manifestEntryName = "recordings/manifests/" + recordingId + ".json"; // $NON-NLS-1$ //$NON-NLS-2$
        String exchangesEntryName = "recordings/captures/" + captureId + "/exchanges.json"; // $NON-NLS-1$

        Map<String, byte[]> archiveEntries = new LinkedHashMap<>();
        List<String> exchangeIds = new ArrayList<>();
        ArrayNode exchanges = JSON.createArrayNode();
        for (JsonNode harEntry : harEntries) {
            ObjectNode exchange = JSON.createObjectNode();
            String exchangeId = UUID.randomUUID().toString();
            exchange.put("id", exchangeId); // $NON-NLS-1$
            if (harEntry.hasNonNull("startedDateTime")) { // $NON-NLS-1$
                exchange.set("startedDateTime", harEntry.get("startedDateTime")); // $NON-NLS-1$ //$NON-NLS-2$
            }
            exchange.set("request", harEntry.path("request").deepCopy()); // $NON-NLS-1$ //$NON-NLS-2$
            exchange.set("response", harEntry.path("response").deepCopy()); // $NON-NLS-1$ //$NON-NLS-2$
            externalizeBody(exchange.path("request").path("postData"), archiveEntries); // $NON-NLS-1$ //$NON-NLS-2$
            externalizeBody(exchange.path("response").path("content"), archiveEntries); // $NON-NLS-1$ //$NON-NLS-2$
            exchanges.add(exchange);
            exchangeIds.add(exchangeId);
        }

        ObjectNode exchangesDocument = JSON.createObjectNode();
        exchangesDocument.put("format", EXCHANGES_FORMAT); // $NON-NLS-1$
        exchangesDocument.put("version", FORMAT_VERSION); // $NON-NLS-1$
        exchangesDocument.put("captureId", captureId); // $NON-NLS-1$
        exchangesDocument.set("exchanges", exchanges); // $NON-NLS-1$
        archiveEntries.put(exchangesEntryName, JSON.writeValueAsBytes(exchangesDocument));

        ObjectNode capture = JSON.createObjectNode();
        capture.put("id", captureId); // $NON-NLS-1$
        capture.put("source", "har"); // $NON-NLS-1$ //$NON-NLS-2$
        capture.put("sourceName", sourceName == null ? "" : sourceName); // $NON-NLS-1$ //$NON-NLS-2$
        capture.put("createdAt", Instant.now().toString()); // $NON-NLS-1$
        capture.put("exchanges", exchangesEntryName); // $NON-NLS-1$

        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("format", MANIFEST_FORMAT); // $NON-NLS-1$
        manifest.put("version", FORMAT_VERSION); // $NON-NLS-1$
        manifest.put("recordingId", recordingId); // $NON-NLS-1$
        manifest.put("activeCaptureId", captureId); // $NON-NLS-1$
        manifest.putArray("captures").add(capture); // $NON-NLS-1$
        byte[] manifestContent = JSON.writeValueAsBytes(manifest);
        archiveEntries.put(manifestEntryName, manifestContent);

        return new Archive(manifestEntryName, sha256Hex(manifestContent), archiveEntries, exchangeIds, captureId);
    }

    /**
     * Stores a replayed sample in a native recording archive. When an exchange
     * with the same id is already present in the replay capture, it is replaced.
     * Older captures remain available as fallbacks for all other samplers.
     *
     * @param manifestEntryName existing manifest entry, or an empty string for a new recording
     * @param existingEntries all entries belonging to the existing recording, or an empty map
     * @param exchangeId stable sampler exchange id, or an empty string to create one
     * @param sampleResult replayed request and response
     * @return updated recording archive
     */
    public static Archive storeReplay(String manifestEntryName, Map<String, byte[]> existingEntries,
            String exchangeId, SampleResult sampleResult) throws IOException {
        if (sampleResult == null) {
            throw new IllegalArgumentException("Sample result must not be null"); // $NON-NLS-1$
        }
        String stableExchangeId = exchangeId == null || exchangeId.isEmpty()
                ? UUID.randomUUID().toString()
                : exchangeId;
        return storeReplays(
                manifestEntryName, existingEntries, Map.of(stableExchangeId, sampleResult), RecordingStorageMode.ALL);
    }

    /** Stores or replaces multiple replayed samples in one archive update. */
    public static Archive storeReplays(String manifestEntryName, Map<String, byte[]> existingEntries,
            Map<String, ? extends SampleResult> sampleResults) throws IOException {
        return storeReplays(manifestEntryName, existingEntries, sampleResults, RecordingStorageMode.ALL);
    }

    /** Stores, filters, or removes multiple replayed samples in one archive update. */
    public static Archive storeReplays(String manifestEntryName, Map<String, byte[]> existingEntries,
            Map<String, ? extends SampleResult> sampleResults, RecordingStorageMode storageMode) throws IOException {
        if (sampleResults == null || sampleResults.isEmpty()
                || sampleResults.entrySet().stream().anyMatch(
                        entry -> entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null)) {
            throw new IllegalArgumentException("Replay samples and exchange ids must not be empty"); // $NON-NLS-1$
        }
        if (storageMode == null) {
            throw new IllegalArgumentException("Recording storage mode must not be null"); // $NON-NLS-1$
        }
        Map<String, ? extends SampleResult> storedSamples = sampleResults.entrySet().stream()
                .filter(entry -> storageMode != RecordingStorageMode.NONE)
                .filter(entry -> storageMode != RecordingStorageMode.OMIT_STATICS
                        || !isStaticResource(entry.getValue()))
                .collect(LinkedHashMap::new,
                        (entries, entry) -> entries.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
        Map<String, byte[]> archiveEntries = new LinkedHashMap<>(existingEntries);
        ObjectNode manifest;
        String recordingManifestEntry = manifestEntryName;
        if (recordingManifestEntry == null || recordingManifestEntry.isEmpty()) {
            String recordingId = UUID.randomUUID().toString();
            recordingManifestEntry = "recordings/manifests/" + recordingId + ".json"; // $NON-NLS-1$ //$NON-NLS-2$
            manifest = JSON.createObjectNode();
            manifest.put("format", MANIFEST_FORMAT); // $NON-NLS-1$
            manifest.put("version", FORMAT_VERSION); // $NON-NLS-1$
            manifest.put("recordingId", recordingId); // $NON-NLS-1$
            manifest.putArray("captures"); // $NON-NLS-1$
        } else {
            if (!JmxArchiveEntryStore.isSafeEntryName(recordingManifestEntry)) {
                throw new IOException("Invalid recording manifest entry: " + recordingManifestEntry); // $NON-NLS-1$
            }
            byte[] manifestContent = archiveEntries.get(recordingManifestEntry);
            if (manifestContent == null) {
                throw new IOException("Recording manifest was not found: " + recordingManifestEntry); // $NON-NLS-1$
            }
            JsonNode parsedManifest = JSON.readTree(manifestContent);
            requireFormat(parsedManifest, MANIFEST_FORMAT, "recording manifest"); // $NON-NLS-1$
            if (!(parsedManifest instanceof ObjectNode objectManifest)
                    || !objectManifest.path("captures").isArray()) { // $NON-NLS-1$
                throw new IOException("Invalid recording manifest: captures must be an array"); // $NON-NLS-1$
            }
            manifest = objectManifest.deepCopy();
        }

        removeExchanges(manifest, archiveEntries, sampleResults.keySet());
        if (storedSamples.isEmpty()) {
            if (manifest.path("captures").isEmpty()) { // $NON-NLS-1$
                throw new IllegalArgumentException("The selected storage mode does not retain any replay samples"); // $NON-NLS-1$
            }
            String now = Instant.now().toString();
            manifest.put("updatedAt", now); // $NON-NLS-1$
            byte[] manifestContent = JSON.writeValueAsBytes(manifest);
            archiveEntries.put(recordingManifestEntry, manifestContent);
            retainReferencedEntries(recordingManifestEntry, manifestContent, archiveEntries);
            return new Archive(recordingManifestEntry, sha256Hex(manifestContent), archiveEntries,
                    List.of(), manifest.path("activeCaptureId").asText()); // $NON-NLS-1$
        }

        ObjectNode replayCapture = activeReplayCapture(manifest);
        String captureId;
        String exchangesEntryName;
        ObjectNode exchangesDocument;
        if (replayCapture == null) {
            captureId = UUID.randomUUID().toString();
            exchangesEntryName = "recordings/captures/" + captureId + "/exchanges.json"; // $NON-NLS-1$ //$NON-NLS-2$
            exchangesDocument = JSON.createObjectNode();
            exchangesDocument.put("format", EXCHANGES_FORMAT); // $NON-NLS-1$
            exchangesDocument.put("version", FORMAT_VERSION); // $NON-NLS-1$
            exchangesDocument.put("captureId", captureId); // $NON-NLS-1$
            exchangesDocument.putArray("exchanges"); // $NON-NLS-1$

            replayCapture = JSON.createObjectNode();
            replayCapture.put("id", captureId); // $NON-NLS-1$
            replayCapture.put("source", "replay"); // $NON-NLS-1$ //$NON-NLS-2$
            replayCapture.put("sourceName", "Visual Tree replay"); // $NON-NLS-1$ //$NON-NLS-2$
            replayCapture.put("createdAt", Instant.now().toString()); // $NON-NLS-1$
            replayCapture.put("exchanges", exchangesEntryName); // $NON-NLS-1$
            ((ArrayNode) manifest.path("captures")).add(replayCapture); // $NON-NLS-1$
        } else {
            captureId = replayCapture.path("id").asText(); // $NON-NLS-1$
            exchangesEntryName = replayCapture.path("exchanges").asText(); // $NON-NLS-1$
            if (!JmxArchiveEntryStore.isSafeEntryName(exchangesEntryName)) {
                throw new IOException("Invalid recording exchanges entry: " + exchangesEntryName); // $NON-NLS-1$
            }
            exchangesDocument = loadExchangesDocument(exchangesEntryName, archiveEntries);
        }

        ArrayNode exchanges = (ArrayNode) exchangesDocument.path("exchanges"); // $NON-NLS-1$
        for (Map.Entry<String, ? extends SampleResult> sample : storedSamples.entrySet()) {
            ObjectNode exchange = replayExchange(sample.getKey(), sample.getValue());
            if (storageMode == RecordingStorageMode.OMIT_STATIC_BODIES && isStaticResource(sample.getValue())) {
                ((ObjectNode) exchange.path("request")).remove("postData"); // $NON-NLS-1$ //$NON-NLS-2$
                if (exchange.path("response").path("content") instanceof ObjectNode content) { // $NON-NLS-1$ //$NON-NLS-2$
                    content.remove(List.of("text", "encoding")); // $NON-NLS-1$ //$NON-NLS-2$
                }
            }
            externalizeBody(exchange.path("request").path("postData"), archiveEntries); // $NON-NLS-1$ //$NON-NLS-2$
            externalizeBody(exchange.path("response").path("content"), archiveEntries); // $NON-NLS-1$ //$NON-NLS-2$
            exchanges.add(exchange);
        }
        archiveEntries.put(exchangesEntryName, JSON.writeValueAsBytes(exchangesDocument));

        String now = Instant.now().toString();
        replayCapture.put("updatedAt", now); // $NON-NLS-1$
        manifest.put("activeCaptureId", captureId); // $NON-NLS-1$
        manifest.put("updatedAt", now); // $NON-NLS-1$
        byte[] manifestContent = JSON.writeValueAsBytes(manifest);
        archiveEntries.put(recordingManifestEntry, manifestContent);

        retainReferencedEntries(recordingManifestEntry, manifestContent, archiveEntries);

        List<String> exchangeIds = new ArrayList<>();
        for (JsonNode storedExchange : exchanges) {
            exchangeIds.add(storedExchange.path("id").asText()); // $NON-NLS-1$
        }
        return new Archive(recordingManifestEntry, sha256Hex(manifestContent),
                archiveEntries, exchangeIds, captureId);
    }

    private static void removeExchanges(
            ObjectNode manifest, Map<String, byte[]> archiveEntries, Set<String> exchangeIds) throws IOException {
        for (JsonNode capture : manifest.path("captures")) { // $NON-NLS-1$
            String exchangesEntryName = capture.path("exchanges").asText(); // $NON-NLS-1$
            ObjectNode exchangesDocument = loadExchangesDocument(exchangesEntryName, archiveEntries);
            ArrayNode exchanges = (ArrayNode) exchangesDocument.path("exchanges"); // $NON-NLS-1$
            for (int i = exchanges.size() - 1; i >= 0; i--) {
                if (exchangeIds.contains(exchanges.get(i).path("id").asText())) { // $NON-NLS-1$
                    exchanges.remove(i);
                }
            }
            archiveEntries.put(exchangesEntryName, JSON.writeValueAsBytes(exchangesDocument));
        }
    }

    private static ObjectNode loadExchangesDocument(
            String exchangesEntryName, Map<String, byte[]> archiveEntries) throws IOException {
        if (!JmxArchiveEntryStore.isSafeEntryName(exchangesEntryName)) {
            throw new IOException("Invalid recording exchanges entry: " + exchangesEntryName); // $NON-NLS-1$
        }
        byte[] exchangesContent = archiveEntries.get(exchangesEntryName);
        if (exchangesContent == null) {
            throw new IOException("Recording exchanges entry was not found: " + exchangesEntryName); // $NON-NLS-1$
        }
        JsonNode parsedExchanges = JSON.readTree(exchangesContent);
        requireFormat(parsedExchanges, EXCHANGES_FORMAT, "recording exchanges"); // $NON-NLS-1$
        if (!(parsedExchanges instanceof ObjectNode objectExchanges)
                || !objectExchanges.path("exchanges").isArray()) { // $NON-NLS-1$
            throw new IOException("Invalid recording exchanges: exchanges must be an array"); // $NON-NLS-1$
        }
        return objectExchanges.deepCopy();
    }

    private static void retainReferencedEntries(
            String manifestEntryName, byte[] manifestContent, Map<String, byte[]> archiveEntries) throws IOException {
        Set<String> referencedEntries = referencedEntryNames(
                manifestContent, entryName -> Optional.ofNullable(archiveEntries.get(entryName)));
        archiveEntries.keySet().retainAll(referencedEntries);
        archiveEntries.put(manifestEntryName, manifestContent);
    }

    private static ObjectNode activeReplayCapture(ObjectNode manifest) {
        String activeCaptureId = manifest.path("activeCaptureId").asText(); // $NON-NLS-1$
        for (JsonNode capture : manifest.path("captures")) { // $NON-NLS-1$
            if (activeCaptureId.equals(capture.path("id").asText()) // $NON-NLS-1$
                    && "replay".equals(capture.path("source").asText()) // $NON-NLS-1$ //$NON-NLS-2$
                    && capture instanceof ObjectNode replayCapture) {
                return replayCapture;
            }
        }
        return null;
    }

    private static ObjectNode replayExchange(String exchangeId, SampleResult result) {
        ObjectNode exchange = JSON.createObjectNode();
        exchange.put("id", exchangeId); // $NON-NLS-1$
        if (result.getStartTime() > 0) {
            exchange.put("startedDateTime", Instant.ofEpochMilli(result.getStartTime()).toString()); // $NON-NLS-1$
        }

        String method = reflectedString(result, "getHTTPMethod"); // $NON-NLS-1$
        ObjectNode request = exchange.putObject("request"); // $NON-NLS-1$
        request.put("method", method); // $NON-NLS-1$
        request.put("url", result.getUrlAsString()); // $NON-NLS-1$
        request.put("httpVersion", requestHttpVersion(result)); // $NON-NLS-1$
        ArrayNode requestHeaders = request.putArray("headers"); // $NON-NLS-1$
        appendHeaders(requestHeaders, result.getRequestHeaders());
        String cookies = reflectedString(result, "getCookies"); // $NON-NLS-1$
        if (!cookies.isEmpty() && !hasHeader(requestHeaders, "Cookie")) { // $NON-NLS-1$
            addHeader(requestHeaders, "Cookie", cookies); // $NON-NLS-1$
        }
        String requestBody = reflectedString(result, "getQueryString"); // $NON-NLS-1$
        if (!requestBody.isEmpty() && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) { // $NON-NLS-1$ //$NON-NLS-2$
            ObjectNode postData = request.putObject("postData"); // $NON-NLS-1$
            postData.put("mimeType", headerValue(requestHeaders, "Content-Type")); // $NON-NLS-1$ //$NON-NLS-2$
            postData.put("text", requestBody); // $NON-NLS-1$
        }

        ObjectNode response = exchange.putObject("response"); // $NON-NLS-1$
        response.put("status", parseStatus(result.getResponseCode())); // $NON-NLS-1$
        response.put("statusText", nullToEmpty(result.getResponseMessage())); // $NON-NLS-1$
        response.put("httpVersion", responseHttpVersion(result)); // $NON-NLS-1$
        ArrayNode responseHeaders = response.putArray("headers"); // $NON-NLS-1$
        appendHeaders(responseHeaders, result.getResponseHeaders());
        response.put("redirectURL", headerValue(responseHeaders, "Location")); // $NON-NLS-1$ //$NON-NLS-2$
        ObjectNode content = response.putObject("content"); // $NON-NLS-1$
        content.put("size", result.getResponseData().length); // $NON-NLS-1$
        content.put("mimeType", nullToEmpty(result.getContentType())); // $NON-NLS-1$
        if (SampleResult.BINARY.equals(result.getDataType())) {
            content.put("text", Base64.getEncoder().encodeToString(result.getResponseData())); // $NON-NLS-1$
            content.put("encoding", BASE64_ENCODING); // $NON-NLS-1$
        } else {
            content.put("text", result.getResponseDataAsString()); // $NON-NLS-1$
        }
        return exchange;
    }

    /** Returns whether a replay result represents a browser-style static resource. */
    public static boolean isStaticResource(SampleResult result) {
        if (result == null) {
            return false;
        }
        if (STATIC_URL.matcher(result.getUrlAsString()).find()) {
            return true;
        }
        String contentType = nullToEmpty(result.getContentType());
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); // $NON-NLS-1$
        return mediaType.startsWith("image/") // $NON-NLS-1$
                || mediaType.startsWith("font/") // $NON-NLS-1$
                || mediaType.startsWith("audio/") // $NON-NLS-1$
                || mediaType.startsWith("video/") // $NON-NLS-1$
                || mediaType.equals("text/css") // $NON-NLS-1$
                || mediaType.contains("javascript") // $NON-NLS-1$
                || mediaType.contains("ecmascript") // $NON-NLS-1$
                || mediaType.contains("font-woff") // $NON-NLS-1$
                || mediaType.equals("application/wasm") // $NON-NLS-1$
                || mediaType.contains("source-map"); // $NON-NLS-1$
    }

    private static String reflectedString(SampleResult result, String methodName) {
        try {
            Method method = result.getClass().getMethod(methodName);
            Object value = method.invoke(result);
            return value == null ? "" : value.toString(); // $NON-NLS-1$
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return ""; // $NON-NLS-1$
        }
    }

    private static void appendHeaders(ArrayNode headers, String headerText) {
        if (headerText == null || headerText.isEmpty()) {
            return;
        }
        for (String line : headerText.split("\\r?\\n")) { // $NON-NLS-1$
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            addHeader(headers, line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }
    }

    private static void addHeader(ArrayNode headers, String name, String value) {
        ObjectNode header = headers.addObject();
        header.put("name", name); // $NON-NLS-1$
        header.put("value", value); // $NON-NLS-1$
    }

    private static boolean hasHeader(ArrayNode headers, String name) {
        return !headerValue(headers, name).isEmpty();
    }

    private static String headerValue(ArrayNode headers, String name) {
        for (JsonNode header : headers) {
            if (name.equalsIgnoreCase(header.path("name").asText())) { // $NON-NLS-1$
                return header.path("value").asText(); // $NON-NLS-1$
            }
        }
        return ""; // $NON-NLS-1$
    }

    private static String requestHttpVersion(SampleResult result) {
        String samplerData = result.getSamplerData();
        if (samplerData != null) {
            for (String line : samplerData.split("\\r?\\n")) { // $NON-NLS-1$
                if (line.startsWith("HTTP/")) { // $NON-NLS-1$
                    return line.split("\\s+", 2)[0]; // $NON-NLS-1$
                }
            }
        }
        return responseHttpVersion(result);
    }

    private static String responseHttpVersion(SampleResult result) {
        String headers = result.getResponseHeaders();
        if (headers != null && headers.startsWith("HTTP/")) { // $NON-NLS-1$
            return headers.split("\\s+", 2)[0]; // $NON-NLS-1$
        }
        return ""; // $NON-NLS-1$
    }

    private static int parseStatus(String responseCode) {
        try {
            return Integer.parseInt(responseCode);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value; // $NON-NLS-1$
    }

    /** Resolves an exchange from a native recording manifest and its archive entries. */
    public static Optional<JsonNode> resolveExchange(
            byte[] manifestContent, String exchangeId, EntryLoader entryLoader) throws IOException {
        if (exchangeId == null || exchangeId.isEmpty()) {
            return Optional.empty();
        }
        JsonNode manifest = JSON.readTree(manifestContent);
        requireFormat(manifest, MANIFEST_FORMAT, "recording manifest"); // $NON-NLS-1$
        JsonNode captures = manifest.path("captures"); // $NON-NLS-1$
        if (!captures.isArray()) {
            throw new IOException("Invalid recording manifest: captures must be an array"); // $NON-NLS-1$
        }
        for (JsonNode capture : capturesInResolutionOrder(manifest, captures)) {
            String exchangesEntryName = capture.path("exchanges").asText(); // $NON-NLS-1$
            if (!JmxArchiveEntryStore.isSafeEntryName(exchangesEntryName)) {
                throw new IOException("Invalid recording exchanges entry: " + exchangesEntryName); // $NON-NLS-1$
            }
            byte[] exchangesContent = entryLoader.load(exchangesEntryName)
                    .orElseThrow(() -> new IOException(
                            "Recording exchanges entry was not found: " + exchangesEntryName)); // $NON-NLS-1$
            JsonNode exchangesDocument = JSON.readTree(exchangesContent);
            requireFormat(exchangesDocument, EXCHANGES_FORMAT, "recording exchanges"); // $NON-NLS-1$
            for (JsonNode candidate : exchangesDocument.path("exchanges")) { // $NON-NLS-1$
                if (exchangeId.equals(candidate.path("id").asText())) { // $NON-NLS-1$
                    ObjectNode exchange = candidate.deepCopy();
                    hydrateBody(exchange.path("request").path("postData"), entryLoader); // $NON-NLS-1$ //$NON-NLS-2$
                    hydrateBody(exchange.path("response").path("content"), entryLoader); // $NON-NLS-1$ //$NON-NLS-2$
                    return Optional.of(exchange);
                }
            }
        }
        return Optional.empty();
    }

    private static List<JsonNode> capturesInResolutionOrder(JsonNode manifest, JsonNode captures) {
        String activeCaptureId = manifest.path("activeCaptureId").asText(); // $NON-NLS-1$
        List<JsonNode> ordered = new ArrayList<>();
        for (JsonNode capture : captures) {
            if (activeCaptureId.equals(capture.path("id").asText())) { // $NON-NLS-1$
                ordered.add(capture);
                break;
            }
        }
        for (JsonNode capture : captures) {
            if (!activeCaptureId.equals(capture.path("id").asText())) { // $NON-NLS-1$
                ordered.add(capture);
            }
        }
        return ordered;
    }

    /** Returns every dependent ZIP entry referenced by a manifest. */
    public static Set<String> referencedEntryNames(byte[] manifestContent, EntryLoader entryLoader)
            throws IOException {
        JsonNode manifest = JSON.readTree(manifestContent);
        requireFormat(manifest, MANIFEST_FORMAT, "recording manifest"); // $NON-NLS-1$
        Set<String> references = new LinkedHashSet<>();
        for (JsonNode capture : manifest.path("captures")) { // $NON-NLS-1$
            String exchangesEntryName = capture.path("exchanges").asText(); // $NON-NLS-1$
            if (!JmxArchiveEntryStore.isSafeEntryName(exchangesEntryName)) {
                throw new IOException("Invalid recording exchanges entry: " + exchangesEntryName); // $NON-NLS-1$
            }
            references.add(exchangesEntryName);
            byte[] exchangesContent = entryLoader.load(exchangesEntryName)
                    .orElseThrow(() -> new IOException(
                            "Recording exchanges entry was not found: " + exchangesEntryName)); // $NON-NLS-1$
            JsonNode exchangesDocument = JSON.readTree(exchangesContent);
            requireFormat(exchangesDocument, EXCHANGES_FORMAT, "recording exchanges"); // $NON-NLS-1$
            for (JsonNode exchange : exchangesDocument.path("exchanges")) { // $NON-NLS-1$
                collectBodyReference(exchange.path("request").path("postData"), references); // $NON-NLS-1$ //$NON-NLS-2$
                collectBodyReference(exchange.path("response").path("content"), references); // $NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return Collections.unmodifiableSet(references);
    }

    public static boolean isManifestEntry(String entryName) {
        return entryName != null
                && entryName.startsWith("recordings/manifests/") // $NON-NLS-1$
                && entryName.endsWith(".json"); // $NON-NLS-1$
    }

    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); // $NON-NLS-1$
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e); // $NON-NLS-1$
        }
    }

    private static void externalizeBody(JsonNode bodyNode, Map<String, byte[]> archiveEntries) {
        if (!(bodyNode instanceof ObjectNode body) || !body.has("text")) { // $NON-NLS-1$
            return;
        }
        String text = body.path("text").asText(); // $NON-NLS-1$
        String originalEncoding = body.path("encoding").asText(); // $NON-NLS-1$
        byte[] bodyBytes;
        String storedEncoding;
        if (BASE64_ENCODING.equalsIgnoreCase(originalEncoding)) {
            try {
                bodyBytes = Base64.getDecoder().decode(text);
                storedEncoding = BASE64_ENCODING;
            } catch (IllegalArgumentException e) {
                bodyBytes = text.getBytes(StandardCharsets.UTF_8);
                storedEncoding = UTF8_ENCODING;
            }
        } else {
            bodyBytes = text.getBytes(StandardCharsets.UTF_8);
            storedEncoding = UTF8_ENCODING;
        }
        String bodyEntryName = "recordings/bodies/" + sha256Hex(bodyBytes); // $NON-NLS-1$
        archiveEntries.putIfAbsent(bodyEntryName, bodyBytes);
        body.remove(List.of("text", "encoding")); // $NON-NLS-1$ //$NON-NLS-2$
        body.put(BODY_REFERENCE, bodyEntryName);
        body.put(BODY_ENCODING, storedEncoding);
    }

    private static void hydrateBody(JsonNode bodyNode, EntryLoader entryLoader) throws IOException {
        if (!(bodyNode instanceof ObjectNode body) || !body.hasNonNull(BODY_REFERENCE)) {
            return;
        }
        String bodyEntryName = body.path(BODY_REFERENCE).asText();
        if (!JmxArchiveEntryStore.isSafeEntryName(bodyEntryName)) {
            throw new IOException("Invalid recording body entry: " + bodyEntryName); // $NON-NLS-1$
        }
        byte[] bodyBytes = entryLoader.load(bodyEntryName)
                .orElseThrow(() -> new IOException("Recording body entry was not found: " + bodyEntryName)); // $NON-NLS-1$
        if (BASE64_ENCODING.equals(body.path(BODY_ENCODING).asText())) {
            body.put("text", Base64.getEncoder().encodeToString(bodyBytes)); // $NON-NLS-1$
            body.put("encoding", BASE64_ENCODING); // $NON-NLS-1$
        } else {
            body.put("text", new String(bodyBytes, StandardCharsets.UTF_8)); // $NON-NLS-1$
            body.remove("encoding"); // $NON-NLS-1$
        }
        body.remove(List.of(BODY_REFERENCE, BODY_ENCODING));
    }

    private static void collectBodyReference(JsonNode body, Set<String> references) throws IOException {
        String entryName = body.path(BODY_REFERENCE).asText();
        if (entryName.isEmpty()) {
            return;
        }
        if (!JmxArchiveEntryStore.isSafeEntryName(entryName)) {
            throw new IOException("Invalid recording body entry: " + entryName); // $NON-NLS-1$
        }
        references.add(entryName);
    }

    private static void requireFormat(JsonNode document, String expectedFormat, String description)
            throws IOException {
        if (!expectedFormat.equals(document.path("format").asText()) // $NON-NLS-1$
                || document.path("version").asInt(-1) != FORMAT_VERSION) { // $NON-NLS-1$
            throw new IOException("Unsupported " + description + " format or version"); // $NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Loader for another entry in the same JMX archive recording bundle. */
    @FunctionalInterface
    public interface EntryLoader {
        Optional<byte[]> load(String entryName) throws IOException;
    }

    /** Immutable group of ZIP entries produced for one recording manifest. */
    public static final class Archive {
        private final String manifestEntryName;
        private final String checksum;
        private final Map<String, byte[]> entries;
        private final List<String> exchangeIds;
        private final String captureId;

        private Archive(String manifestEntryName, String checksum, Map<String, byte[]> entries,
                List<String> exchangeIds, String captureId) {
            this.manifestEntryName = manifestEntryName;
            this.checksum = checksum;
            this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            this.exchangeIds = List.copyOf(exchangeIds);
            this.captureId = captureId;
        }

        public String manifestEntryName() {
            return manifestEntryName;
        }

        public String checksum() {
            return checksum;
        }

        public Map<String, byte[]> entries() {
            return entries;
        }

        public List<String> exchangeIds() {
            return exchangeIds;
        }

        public String captureId() {
            return captureId;
        }

        public int exchangeCount() {
            return exchangeIds.size();
        }

        public byte[] manifestContent() {
            return entries.get(manifestEntryName);
        }

        public Optional<JsonNode> resolveExchange(String exchangeId) throws IOException {
            return RecordedExchangeStore.resolveExchange(
                    manifestContent(), exchangeId, entryName -> Optional.ofNullable(entries.get(entryName)));
        }
    }
}
