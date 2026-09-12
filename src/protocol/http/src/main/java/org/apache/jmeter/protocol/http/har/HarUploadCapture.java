/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.save.ArchiveFiles;

import com.fasterxml.jackson.databind.JsonNode;

/** Optional browser capture inventory. Selection events are not request mappings. */
public final class HarUploadCapture {
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    static final int MAX_RECORDS = 1000;

    public record Result(boolean present, List<NameValue> resources, List<String> warnings) {
        static Result empty() {
            return new Result(false, List.of(), List.of());
        }
    }

    private record Capture(JsonNode metadata, NameValue resource) {
    }

    private HarUploadCapture() {
    }

    static Result read(JsonNode log, List<HarEntry> entries) {
        JsonNode inventory = log.path("_breaktest").path("uploadCapture");
        if (inventory.isMissingNode()) {
            return Result.empty();
        }
        List<String> warnings = new ArrayList<>();
        if (!inventory.path("version").isIntegralNumber() || !inventory.path("version").canConvertToInt()
                || inventory.path("version").asInt() != 1) {
            leaveUnresolved(entries);
            return new Result(true, List.of(), List.of("Unsupported uploadCapture version; embedded files were not imported."));
        }
        if (!inventory.path("files").isArray()) {
            leaveUnresolved(entries);
            return new Result(true, List.of(), List.of("uploadCapture.files is not an array; embedded files were not imported."));
        }
        Map<String, NameValue> resources = new LinkedHashMap<>();
        List<Capture> captures = new ArrayList<>();
        long total = 0;
        int count = 0;
        for (JsonNode file : inventory.path("files")) {
            if (++count > MAX_RECORDS) {
                warnings.add("Upload capture exceeds the 1000-record limit. Automatic association is disabled.");
                // Uninspected records could contain competing candidates.
                leaveUnresolved(entries);
                return new Result(true, List.copyOf(resources.values()), List.copyOf(warnings));
            }
            String label = file.path("fileName").asText("unnamed file");
            NameValue resource = null;
            try {
                if (!file.path("fileName").isTextual() || file.path("fileName").asText().isBlank()) {
                    throw new IllegalArgumentException("missing filename");
                }
                if (!"complete".equals(file.path("status").asText())) {
                    throw new IllegalArgumentException("content unavailable (status: " + file.path("status").asText("missing") + ")");
                }
                if (!"base64".equals(file.path("encoding").asText()) || !file.path("content").isTextual()) {
                    throw new IllegalArgumentException("missing content or unsupported encoding");
                }
                long size = file.path("size").asLong(-1);
                String encoded = file.path("content").textValue();
                if (!file.path("size").isIntegralNumber() || !file.path("size").canConvertToLong() || size < 0) {
                    throw new IllegalArgumentException("missing or invalid decoded size");
                }
                if (size > MAX_FILE_BYTES || encoded.length() > ((long) MAX_FILE_BYTES + 2) / 3 * 4
                        || total + size > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException("capture size limit exceeded (16 MiB/file, 64 MiB/import)");
                }
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("malformed base64 content", ex);
                }
                if (bytes.length != size) {
                    throw new IllegalArgumentException("decoded size does not match declared size");
                }
                total += bytes.length;
                String name = safeName(label);
                String path = resourceName(name, bytes, resources);
                resource = new NameValue(file.path("fieldName").asText(""), "", label,
                        file.path("mimeType").asText("application/octet-stream"), bytes, path);
                resources.putIfAbsent(path, resource);
            } catch (IllegalArgumentException ex) {
                warnings.add(label + ": " + ex.getMessage() + ". Supply the original file manually.");
            }
            captures.add(new Capture(file, resource));
        }
        Set<String> assigned = new LinkedHashSet<>();
        for (HarEntry entry : entries) {
            if (entry.getPostData() == null || !HarParser.isMultipart(entry.getPostData().getMimeType())) {
                continue;
            }
            List<NameValue> params = entry.getPostData().getParams();
            for (int i = 0; i < params.size(); i++) {
                NameValue param = params.get(i);
                if (!param.isFileUpload()) {
                    continue;
                }
                List<Capture> candidates = captures.stream()
                        .filter(capture -> matches(capture.metadata(), param, entry))
                        .toList();
                Map<String, NameValue> choices = new LinkedHashMap<>();
                boolean unavailable = false;
                for (Capture candidate : candidates) {
                    if (candidate.resource() == null) {
                        unavailable = true;
                    } else {
                        choices.put(candidate.resource().getResourceName(), candidate.resource());
                    }
                }
                if (!unavailable && choices.size() == 1) {
                    NameValue chosen = choices.values().iterator().next();
                    params.set(i, new NameValue(param.getName(), "", param.getFileName(),
                            param.getContentType().isBlank() ? chosen.getContentType() : param.getContentType(),
                            chosen.getFileContent(), chosen.getResourceName()));
                    assigned.add(chosen.getResourceName());
                } else {
                    // CDP commonly records empty multipart parts even for nonempty files.
                    // Never turn that omission into a bogus empty upload when inventory matching fails.
                    params.set(i, unresolved(param));
                    warnings.add("Request " + (entry.getOriginalIndex() + 1) + ", field " + param.getName() + ", file "
                            + param.getFileName() + ": " + (choices.size() > 1 || unavailable ? "ambiguous/unavailable" : "no confirmed")
                            + " capture association. Choose the file manually in the request's Files tab.");
                }
            }
        }
        for (NameValue resource : resources.values()) {
            if (!assigned.contains(resource.getResourceName())) {
                warnings.add("Unassigned capture: " + resource.getFileName()
                        + "; it may not have been submitted. Map it manually if needed. "
                        + "Archive location if saved: files/" + resource.getResourceName());
            }
        }
        return new Result(true, List.copyOf(resources.values()), List.copyOf(warnings));
    }

    private static String resourceName(String name, byte[] bytes, Map<String, NameValue> resources) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        String candidate = name;
        int suffix = 2;
        while (resources.containsKey(candidate)) {
            if (Arrays.equals(bytes, resources.get(candidate).getFileContent())) {
                return candidate;
            }
            candidate = stem + "-" + suffix++ + extension;
        }
        return candidate;
    }

    private static String safeName(String filename) {
        String name = HarEntry.localFileName(filename).replaceAll("[^a-zA-Z0-9._ -]", "_");
        if (name.length() > 120) {
            name = name.substring(name.length() - 120);
        }
        try {
            ArchiveFiles.entryName(name);
            return name;
        } catch (IllegalArgumentException ex) {
            return "upload.bin";
        }
    }

    private static NameValue unresolved(NameValue param) {
        return new NameValue(param.getName(), "", param.getFileName(), param.getContentType(), null,
                safeName(param.getFileName()));
    }

    private static void leaveUnresolved(List<HarEntry> entries) {
        for (HarEntry entry : entries) {
            if (entry.getPostData() != null && HarParser.isMultipart(entry.getPostData().getMimeType())) {
                entry.getPostData().getParams().replaceAll(param -> param.isFileUpload() ? unresolved(param) : param);
            }
        }
    }

    private static boolean matches(JsonNode file, NameValue param, HarEntry entry) {
        if (param.getName().isBlank()
                || !HarEntry.localFileName(file.path("fileName").asText("")).equals(HarEntry.localFileName(param.getFileName()))
                || !file.path("fieldName").asText("").equals(param.getName())) {
            return false;
        }
        // Time/page evidence can exclude candidates, but never select the nearest event:
        // earlier files can be reused, including across transaction boundaries.
        try {
            Instant captured = Instant.parse(file.path("capturedDateTime").asText(""));
            Instant requested = Instant.parse(entry.getStartedDateTime());
            if (captured.isAfter(requested)) {
                return false;
            }
        } catch (DateTimeParseException ex) {
            // Missing supporting evidence is not a mapping on its own.
        }
        String page = entry.getRequestHeaders().stream()
                .filter(header -> "referer".equalsIgnoreCase(header.getName()))
                .map(NameValue::getValue).findFirst().orElse("");
        String capturePage = file.path("pageUrl").asText("");
        String transaction = file.path("transactionId").asText("");
        // A matching transaction supports a match even if navigation changed the page.
        boolean sameTransaction = !transaction.isEmpty() && transaction.equals(entry.getTransactionId());
        return sameTransaction || page.isEmpty() || capturePage.isEmpty() || page.equals(capturePage);
    }
}
