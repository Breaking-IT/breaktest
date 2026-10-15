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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.services.FileServer;
import org.snakeyaml.engine.v2.api.ConstructNode;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.JsonSchema;


/** Loads YAML object members as variables, advancing one record per thread iteration. */
@GUIMenuSortOrder(7)
@TestElementMetadata(labelResource = "displayName")
public class YAMLDataSet extends StructuredDataSet {
    private static final long serialVersionUID = 1L;
    private static final ConstructNode RAW_NUMBER =
            node -> new StructuredDataVariables.RawNumber(((ScalarNode) node).getValue());

    @Override
    protected List<Map<String, String>> loadDataRecords() throws IOException {
        return parseRecords(readYamlContent());
    }

    @Override
    protected String recordSource() throws IOException {
        return isUseYamlFromArchive() ? "archive:" + archiveEntry()
                : resolveYamlFile().toRealPath().toString();
    }

    static List<Map<String, String>> parseRecords(byte[] content) throws IOException {
        String yaml = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
        try {
            if (yaml.startsWith("\uFEFF")) {
                yaml = yaml.substring(1);
            }
            LoadSettings settings = LoadSettings.builder()
                    .setLabel("YAML Data Set")
                    .setSchema(new JsonSchema())
                    .setAllowDuplicateKeys(false)
                    // Numbers keep their source text so values such as 10.00 or 1e5 are not reformatted.
                    .setTagConstructors(Map.of(Tag.INT, RAW_NUMBER, Tag.FLOAT, RAW_NUMBER))
                    .build();
            List<Map<String, String>> loaded = new ArrayList<>();
            int[] remaining = {100_000};
            for (Object document : new Load(settings).loadAllFromString(yaml)) {
                validateValue(document, Collections.newSetFromMap(new IdentityHashMap<>()), remaining, 0);
                List<?> values = document instanceof List<?> list ? list : Collections.singletonList(document);
                for (Object value : values) {
                    if (!(value instanceof Map<?, ?> object)) {
                        throw new IllegalArgumentException("Expected a YAML mapping or a list of mappings");
                    }
                    Map<String, String> record = new LinkedHashMap<>();
                    object.forEach((key, member) -> StructuredDataVariables.flatten(record, (String) key, member));
                    loaded.add(record);
                }
            }
            if (loaded.isEmpty()) {
                throw new IllegalArgumentException("YAML must contain at least one record");
            }
            return loaded;
        } catch (YamlEngineException | IllegalArgumentException e) {
            throw new IOException("Invalid YAML data: " + e.getMessage(), e);
        }
    }

    private static void validateValue(Object value, Set<Object> ancestors, int[] remaining, int depth) {
        if (--remaining[0] < 0 || depth > 100) {
            throw new IllegalArgumentException("YAML data exceeds 100,000 expanded values or 100 nesting levels");
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (!ancestors.add(value)) {
                throw new IllegalArgumentException("Recursive YAML aliases are not supported");
            }
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, member) -> {
                    if (!(key instanceof String)) {
                        throw new IllegalArgumentException("YAML variable and nested mapping keys must be strings");
                    }
                    validateValue(member, ancestors, remaining, depth + 1);
                });
            } else {
                for (Object member : (List<?>) value) {
                    validateValue(member, ancestors, remaining, depth + 1);
                }
            }
            ancestors.remove(value);
        } else if (value != null && !(value instanceof String) && !(value instanceof Boolean)
                && !(value instanceof StructuredDataVariables.RawNumber)) {
            throw new IllegalArgumentException("Unsupported YAML value type: " + value.getClass().getSimpleName());
        } else if (value instanceof StructuredDataVariables.RawNumber number
                && !number.text().matches("[-+]?[0-9.eE+-]+")) {
            throw new IllegalArgumentException("Non-finite YAML numbers are not supported");
        }
    }

    public List<String> readFirstSample(int sampleCount) throws IOException {
        return readFirstSample(sampleCount, readYamlContent());
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

    Path resolveYamlFile() {
        if (getFilename().isBlank()) {
            throw new IllegalArgumentException("Filename must not be empty");
        }
        return FileServer.getFileServer().resolveFile(getFilename().trim()).toPath();
    }

    String archiveEntry() {
        return ArchiveFiles.entryName(getFilename());
    }

    byte[] readYamlContent() throws IOException {
        return isUseYamlFromArchive() ? ArchiveFiles.read(archiveEntry()) : Files.readAllBytes(resolveYamlFile());
    }

    void storeArchivedYaml(byte[] content) throws IOException {
        parseRecords(content);
        if (ArchiveFiles.currentPlan() == null) {
            throw new IOException("No test plan is available for the YAML archive");
        }
        String entry = isUseYamlFromArchive() ? archiveEntry() : ArchiveFiles.importEntryName(getFilename());
        ArchiveFiles.put(ArchiveFiles.currentPlan(), entry, content, isUseYamlFromArchive());
        setFilename(entry.substring("files/".length()));
        setUseYamlFromArchive(true);
    }

    @Override
    protected String archiveProperty() {
        return "useYamlFromArchive";
    }

    public boolean isUseYamlFromArchive() {
        return getPropertyAsBoolean(archiveProperty());
    }

    public void setUseYamlFromArchive(boolean useArchive) {
        setProperty("useYamlFromArchive", useArchive, false);
    }

}
