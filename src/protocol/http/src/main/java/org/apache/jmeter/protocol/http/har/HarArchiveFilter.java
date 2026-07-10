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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Filters an imported HAR down to the entries represented by generated samplers. */
final class HarArchiveFilter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STATIC_URL = Pattern.compile(
            "(?:\\.|/)(?:css|js|mjs|map|png|jpe?g|gif|svg|webp|avif|ico|woff2?|ttf|otf|eot|wasm|"
                    + "mp3|mp4|webm|ogg|wav)(?:[?#/]|$)",
            Pattern.CASE_INSENSITIVE);

    private HarArchiveFilter() {
    }

    static Optional<RecordedExchangeStore.Archive> filterAndRelink(
            byte[] originalHar, HashTree convertedTree, String originalName,
            RecordingStorageMode storageMode) throws IOException {
        Set<Integer> referencedIndexes = new LinkedHashSet<>();
        collectReferencedIndexes(convertedTree, referencedIndexes);
        if (storageMode == RecordingStorageMode.NONE) {
            relink(convertedTree, Map.of(), null);
            return Optional.empty();
        }

        JsonNode parsed = JSON.readTree(originalHar);
        if (!(parsed instanceof ObjectNode root) || !(root.path("log") instanceof ObjectNode log)) { // $NON-NLS-1$
            throw new IOException("Not a valid HAR file: missing log object"); // $NON-NLS-1$
        }
        JsonNode entriesNode = log.path("entries"); // $NON-NLS-1$
        if (!(entriesNode instanceof ArrayNode entries)) {
            throw new IOException("Not a valid HAR file: missing log.entries array"); // $NON-NLS-1$
        }
        Set<Integer> missing = new LinkedHashSet<>();
        for (int referencedIndex : referencedIndexes) {
            if (referencedIndex < 0 || referencedIndex >= entries.size()) {
                missing.add(referencedIndex);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Generated samplers reference missing HAR entries: " + missing); // $NON-NLS-1$
        }

        Map<Integer, Integer> remappedIndexes = new LinkedHashMap<>();
        ArrayNode filteredEntries = JSON.createArrayNode();
        for (int originalIndex = 0; originalIndex < entries.size(); originalIndex++) {
            if (referencedIndexes.contains(originalIndex)) {
                JsonNode entry = entries.get(originalIndex);
                boolean staticResource = isStaticResource(entry);
                if (storageMode == RecordingStorageMode.OMIT_STATICS && staticResource) {
                    continue;
                }
                remappedIndexes.put(originalIndex, filteredEntries.size());
                filteredEntries.add(storageMode == RecordingStorageMode.OMIT_STATIC_BODIES
                        && staticResource ? withoutBodies(entry) : entry);
            }
        }

        if (filteredEntries.isEmpty()) {
            relink(convertedTree, remappedIndexes, null);
            return Optional.empty();
        }
        log.set("entries", filteredEntries); // $NON-NLS-1$
        byte[] filteredContent = JSON.writeValueAsBytes(root);
        RecordedExchangeStore.Archive archive = RecordedExchangeStore.fromHar(filteredContent, originalName);
        relink(convertedTree, remappedIndexes, archive);
        return Optional.of(archive);
    }

    private static void collectReferencedIndexes(HashTree tree, Set<Integer> indexes) throws IOException {
        if (tree == null) {
            return;
        }
        for (Object item : tree.list()) {
            if (item instanceof TestElement element) {
                String index = element.getPropertyAsString(RecordedHarExchangeResolver.HAR_ENTRY_INDEX);
                if (!index.isEmpty()) {
                    try {
                        indexes.add(Integer.parseInt(index));
                    } catch (NumberFormatException e) {
                        throw new IOException("Generated sampler has an invalid HAR entry index: " + index, e);
                    }
                }
            }
            collectReferencedIndexes(tree.getTree(item), indexes);
        }
    }

    private static void relink(HashTree tree, Map<Integer, Integer> remappedIndexes,
            RecordedExchangeStore.Archive archive) throws IOException {
        if (tree == null) {
            return;
        }
        for (Object item : tree.list()) {
            if (item instanceof TestElement element) {
                String index = element.getPropertyAsString(RecordedHarExchangeResolver.HAR_ENTRY_INDEX);
                if (!index.isEmpty()) {
                    int originalIndex;
                    try {
                        originalIndex = Integer.parseInt(index);
                    } catch (NumberFormatException e) {
                        throw new IOException("Generated sampler has an invalid HAR entry index: " + index, e);
                    }
                    Integer remappedIndex = remappedIndexes.get(originalIndex);
                    if (remappedIndex == null) {
                        clearSamplerRecordingMetadata(element);
                    } else {
                        element.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY,
                                archive.exchangeIds().get(remappedIndex));
                        clearLegacySamplerMetadata(element);
                    }
                }
                if (!element.getPropertyAsString(RecordedHarExchangeResolver.HAR_FILENAME).isEmpty()
                        || !element.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty()) {
                    element.removeProperty(RecordedHarExchangeResolver.HAR_FILENAME);
                    element.removeProperty(RecordedHarExchangeResolver.HAR_MD5);
                    if (archive == null) {
                        element.removeProperty(RecordedExchangeStore.MANIFEST_PROPERTY);
                        element.removeProperty(RecordedExchangeStore.CHECKSUM_PROPERTY);
                    } else {
                        element.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
                        element.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());
                    }
                }
            }
            relink(tree.getTree(item), remappedIndexes, archive);
        }
    }

    private static void clearSamplerRecordingMetadata(TestElement element) {
        element.removeProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY);
        clearLegacySamplerMetadata(element);
    }

    private static void clearLegacySamplerMetadata(TestElement element) {
        element.removeProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX);
        element.removeProperty(RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME);
        element.removeProperty(RecordedHarExchangeResolver.HAR_REQUEST_METHOD);
        element.removeProperty(RecordedHarExchangeResolver.HAR_REQUEST_URL);
    }

    private static JsonNode withoutBodies(JsonNode entry) {
        ObjectNode copy = entry.deepCopy();
        if (copy.path("request") instanceof ObjectNode request) { // $NON-NLS-1$
            request.remove("postData"); // $NON-NLS-1$
        }
        if (copy.path("response").path("content") instanceof ObjectNode content) { // $NON-NLS-1$ //$NON-NLS-2$
            content.remove(List.of("text", "encoding")); // $NON-NLS-1$ //$NON-NLS-2$
        }
        return copy;
    }

    private static boolean isStaticResource(JsonNode entry) {
        String url = entry.path("request").path("url").asText(); // $NON-NLS-1$ //$NON-NLS-2$
        if (STATIC_URL.matcher(url).find()) {
            return true;
        }
        JsonNode content = entry.path("response").path("content"); // $NON-NLS-1$ //$NON-NLS-2$
        String mimeType = content.path("mimeType").asText(); // $NON-NLS-1$
        if (mimeType.isEmpty()) {
            mimeType = responseContentType(entry.path("response").path("headers")); // $NON-NLS-1$ //$NON-NLS-2$
        }
        String mediaType = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); // $NON-NLS-1$
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

    private static String responseContentType(JsonNode headers) {
        if (headers.isArray()) {
            for (JsonNode header : headers) {
                if ("content-type".equalsIgnoreCase(header.path("name").asText())) { // $NON-NLS-1$ //$NON-NLS-2$
                    return header.path("value").asText(); // $NON-NLS-1$
                }
            }
        }
        return ""; // $NON-NLS-1$
    }

}
