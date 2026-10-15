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

package org.apache.jmeter.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.services.FileServer;

import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

/** Loads JSON object members as variables, advancing one record per thread iteration. */
@GUIMenuSortOrder(6)
@TestElementMetadata(labelResource = "displayName")
public class JSONDataSet extends StructuredDataSet {
    private static final long serialVersionUID = 1L;

    @Override
    protected List<Map<String, String>> loadDataRecords() throws IOException {
        return parseRecords(readJsonContent());
    }

    @Override
    protected String recordSource() throws IOException {
        return isUseJsonFromArchive() ? "archive:" + archiveEntry()
                : resolveJsonFile().toRealPath().toString();
    }

    static List<Map<String, String>> parseRecords(byte[] content) throws IOException {
        String json = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
        try {
            if (json.startsWith("\uFEFF")) {
                json = json.substring(1);
            }
            Object document = new JSONParser(JSONParser.MODE_STRICTEST | JSONParser.ACCEPT_TAILLING_SPACE).parse(json);
            List<?> values = document instanceof List<?> list ? list : Collections.singletonList(document);
            List<Map<String, String>> loaded = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> object)) {
                    throw new IllegalArgumentException("Expected a JSON object or an array of objects");
                }
                Map<String, String> record = new LinkedHashMap<>();
                object.forEach((key, member) -> StructuredDataVariables.flatten(record, (String) key, member));
                loaded.add(record);
            }
            if (loaded.isEmpty()) {
                throw new IllegalArgumentException("JSON record array must not be empty");
            }
            return loaded;
        } catch (ParseException | IllegalArgumentException e) {
            throw new IOException("Invalid JSON data: " + e.getMessage(), e);
        }
    }

    public List<String> readFirstSample(int sampleCount) throws IOException {
        return readFirstSample(sampleCount, readJsonContent());
    }

    List<String> readFirstSample(int sampleCount, byte[] content) throws IOException {
        List<Map<String, String>> samples = parseRecords(content);
        Set<String> names = new LinkedHashSet<>();
        samples.forEach(record -> names.addAll(record.keySet()));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(sampleCount, samples.size()); i++) {
            Map<String, String> record = samples.get(i);
            for (String name : names) {
                lines.add("${" + getVariablePrefix() + name + "} = " + record.getOrDefault(name, StructuredDataVariables.missingValue(name)));
            }
            lines.add("------------");
        }
        return lines;
    }

    Path resolveJsonFile() {
        if (getFilename().isBlank()) {
            throw new IllegalArgumentException("Filename must not be empty");
        }
        return FileServer.getFileServer().resolveFile(getFilename().trim()).toPath();
    }

    String archiveEntry() {
        return ArchiveFiles.entryName(getFilename());
    }

    byte[] readJsonContent() throws IOException {
        return isUseJsonFromArchive() ? ArchiveFiles.read(archiveEntry()) : Files.readAllBytes(resolveJsonFile());
    }

    void storeArchivedJson(byte[] content) throws IOException {
        parseRecords(content);
        if (ArchiveFiles.currentPlan() == null) {
            throw new IOException("No test plan is available for the JSON archive");
        }
        String entry = isUseJsonFromArchive() ? archiveEntry() : ArchiveFiles.importEntryName(getFilename());
        ArchiveFiles.put(ArchiveFiles.currentPlan(), entry, content, isUseJsonFromArchive());
        setFilename(entry.substring("files/".length()));
        setUseJsonFromArchive(true);
    }

    public boolean isUseJsonFromArchive() {
        return getPropertyAsBoolean("useJsonFromArchive");
    }

    public void setUseJsonFromArchive(boolean useArchive) {
        setProperty("useJsonFromArchive", useArchive, false);
    }

}
