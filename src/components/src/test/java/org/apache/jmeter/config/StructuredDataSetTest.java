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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StructuredDataSetTest extends JMeterTestCase implements JMeterSerialTest {
    @TempDir
    Path directory;

    private StructuredDataSet dataset(String format, int count) throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add("XML".equals(format) ? "<row><id>" + i + "</id></row>" : "{\"id\":" + i + "}");
        }
        String content = "XML".equals(format) ? "<rows>" + String.join("", rows) + "</rows>"
                : "[" + String.join(",", rows) + "]";
        Path file = directory.resolve("data." + format.toLowerCase(java.util.Locale.ROOT));
        Files.writeString(file, content);
        StructuredDataSet data = switch (format) {
            case "JSON" -> new JSONDataSet();
            case "XML" -> new XMLDataSet();
            default -> new YAMLDataSet();
        };
        data.setFilename(file.toString());
        data.setRecycle(false);
        return data;
    }

    private String read(StructuredDataSet prototype, ThreadGroup group) throws Exception {
        // Separate actual threads exercise JMeter's thread-local context, as during a run.
        try (var executor = Executors.newSingleThreadExecutor()) {
            return executor.submit(() -> {
                JMeterContextService.getContext().setThreadGroup(group);
                JMeterVariables variables = new JMeterVariables();
                JMeterContextService.getContext().setVariables(variables);
                StructuredDataSet copy = (StructuredDataSet) prototype.clone();
                copy.iterationStart(null);
                return variables.get(copy.getVariablePrefix() + "id");
            }).get();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "XML", "YAML"})
    void allThreadsSharePositionAndPrefixesDoNotSplitIt(String format) throws Exception {
        StructuredDataSet data = dataset(format, 2);
        assertEquals("shareMode.all", data.getShareMode());
        assertEquals("1", read(data, new ThreadGroup()));
        data.setVariablePrefix("other.");
        assertEquals("2", read(data, new ThreadGroup()));
        assertEquals("<EOF>", read(data, new ThreadGroup()));
        data.setRecycle(true);
        assertEquals("1", read(data, new ThreadGroup()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "XML", "YAML"})
    void groupModeSharesWithinGroupButNotAcrossIdenticallyConfiguredGroups(String format) throws Exception {
        StructuredDataSet data = dataset(format, 3);
        data.setShareMode("shareMode.group");
        ThreadGroup first = new ThreadGroup();
        ThreadGroup second = new ThreadGroup();
        assertEquals("1", read(data, first));
        assertEquals("2", read(data, first));
        assertEquals("1", read(data, second));
        assertEquals("3", read(data, first));
        assertEquals("2", read(data, second));
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "XML", "YAML"})
    void threadModeStartsEachThreadAtFirstRecord(String format) throws Exception {
        StructuredDataSet data = dataset(format, 2);
        data.setShareMode("shareMode.thread");
        ThreadGroup group = new ThreadGroup();
        assertEquals("1", read(data, group));
        assertEquals("1", read(data, group));
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertEquals(List.of("1", "2"), executor.submit(() -> {
                var variables = new JMeterVariables();
                JMeterContextService.getContext().setVariables(variables);
                StructuredDataSet first = (StructuredDataSet) data.clone();
                StructuredDataSet second = (StructuredDataSet) data.clone();
                first.iterationStart(null);
                String one = variables.get("id");
                second.iterationStart(null);
                return List.of(one, variables.get("id"));
            }).get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "XML", "YAML"})
    void customIdentifiersShareAcrossGroupsAndIsolateOtherIdentifiers(String format) throws Exception {
        StructuredDataSet data = dataset(format, 3);
        data.setShareMode("customers");
        assertEquals("1", read(data, new ThreadGroup()));
        assertEquals("2", read(data, new ThreadGroup()));
        data.setShareMode("admins");
        assertEquals("1", read(data, new ThreadGroup()));
        data.setShareMode("customers");
        assertEquals("3", read(data, new ThreadGroup()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "XML", "YAML"})
    void concurrentAllocationDoesNotDuplicateOrSkipRecords(String format) throws Exception {
        StructuredDataSet data = dataset(format, 200);
        List<Callable<List<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                var variables = new JMeterVariables();
                JMeterContextService.getContext().setVariables(variables);
                StructuredDataSet copy = (StructuredDataSet) data.clone();
                List<String> received = new ArrayList<>();
                for (int j = 0; j < 25; j++) {
                    copy.iterationStart(null);
                    received.add(variables.get("id"));
                }
                return received;
            });
        }
        List<String> received = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var future : executor.invokeAll(tasks)) {
                received.addAll(future.get());
            }
        }
        assertEquals(200, new HashSet<>(received).size());
        assertTrue(received.contains("1"));
        assertTrue(received.contains("200"));
        assertEquals("<EOF>", read(data, new ThreadGroup()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "XML", "YAML"})
    void archivedRecordsSharePositionWithoutOriginalFile(String format) throws Exception {
        StructuredDataSet data = dataset(format, 2);
        Path file = Path.of(data.getFilename());
        ArchiveFiles.activate(new TestPlan());
        data.testStarted();
        try {
            byte[] content = Files.readAllBytes(file);
            if (data instanceof JSONDataSet json) {
                json.storeArchivedJson(content);
            } else if (data instanceof XMLDataSet xml) {
                xml.storeArchivedXml(content);
            } else if (data instanceof YAMLDataSet yaml) {
                yaml.storeArchivedYaml(content);
            }
            Files.delete(file);
            assertEquals("1", read(data, new ThreadGroup()));
            assertEquals("2", read(data, new ThreadGroup()));
            assertEquals("<EOF>", read(data, new ThreadGroup()));
        } finally {
            data.testEnded();
            ArchiveFiles.activate(null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "XML", "YAML"})
    void nextRunResetsPositionAndReloadsFile(String format) throws Exception {
        StructuredDataSet data = dataset(format, 2);
        data.testStarted();
        try {
            assertEquals("1", read(data, new ThreadGroup()));
            assertEquals("2", read(data, new ThreadGroup()));
        } finally {
            data.testEnded();
        }
        Files.writeString(Path.of(data.getFilename()), "XML".equals(format)
                ? "<rows><row><id>updated</id></row></rows>" : "[{\"id\":\"updated\"}]");
        data.testStarted("remote");
        try {
            assertEquals("updated", read(data, new ThreadGroup()));
        } finally {
            data.testEnded("remote");
        }
    }
}
