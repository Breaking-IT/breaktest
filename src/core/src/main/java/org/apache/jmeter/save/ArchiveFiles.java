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

package org.apache.jmeter.save;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.MapProperty;

/** Shared files owned by a test plan and persisted in its JMX archive. */
public final class ArchiveFiles {
    public static final String PROPERTY = "BreakTest.archive.files";
    private static volatile TestPlan activePlan;
    private static final Map<String, Path> LOCAL_FILES = new HashMap<>();

    private ArchiveFiles() {
    }

    public static void activate(TestPlan plan) {
        activePlan = plan;
    }

    public static TestPlan currentPlan() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            // The invisible tree root can retain a previous TestPlan after Open/Close.
            JMeterTreeNode node = (JMeterTreeNode) gui.getTreeModel().getTestPlan().getArray()[0];
            return (TestPlan) node.getTestElement();
        }
        return activePlan;
    }

    public static Map<String, String> references(TestElement element) {
        Map<String, String> result = new LinkedHashMap<>();
        if (element.getProperty(PROPERTY) instanceof MapProperty map) {
            Map<?, ?> values = (Map<?, ?>) map.getObjectValue();
            values.forEach((name, value) -> result.put(name.toString(),
                    ((org.apache.jmeter.testelement.property.JMeterProperty) value).getStringValue()));
        }
        return result;
    }

    public static String entryName(String name) {
        String entry = name.startsWith("files/") ? name : "files/" + name;
        if (!JmxArchiveEntryStore.isSafeEntryName(entry) || entry.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Invalid archive filename: " + name);
        }
        return entry;
    }

    public static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void put(TestPlan plan, String name, byte[] content, boolean replace) {
        String entry = entryName(name);
        String checksum = checksum(content);
        Map<String, String> references = references(plan);
        String old = references.get(entry);
        if (!replace && old != null && !old.equals(checksum)) {
            throw new IllegalArgumentException("A different file already uses " + entry + ". Use a unique filename.");
        }
        JmxArchiveEntryStore.register(entry, checksum, content);
        references.put(entry, checksum);
        MapProperty property = new MapProperty();
        property.setName(PROPERTY);
        references.forEach((path, digest) -> property.addProperty(
                new org.apache.jmeter.testelement.property.StringProperty(path, digest)));
        plan.setProperty(property);
    }

    public static byte[] read(String name) throws IOException {
        TestPlan plan = currentPlan();
        String entry = entryName(name);
        String checksum = plan == null ? null : references(plan).get(entry);
        if (checksum == null) {
            throw new IOException("File is not available in the JMX archive: " + entry);
        }
        return JmxArchiveEntryStore.find(entry, checksum)
                .orElseThrow(() -> new IOException("Archive file content is unavailable: " + entry));
    }

    public static Path resolve(String name) throws IOException {
        return materialize(entryName(name), read(name));
    }

    public static synchronized Path materialize(String entry, byte[] content) throws IOException {
        String key = entry + ":" + checksum(content);
        Path path = LOCAL_FILES.get(key);
        if (path == null || !Files.exists(path)) {
            Path directory = Files.createTempDirectory("breaktest-archive-");
            directory.toFile().deleteOnExit();
            path = directory.resolve(Path.of(entry).getFileName());
            Files.write(path, content);
            path.toFile().deleteOnExit();
            LOCAL_FILES.put(key, path);
        }
        return path;
    }
}
