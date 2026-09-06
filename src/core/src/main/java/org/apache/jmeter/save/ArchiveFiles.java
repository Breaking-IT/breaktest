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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.StringProperty;

/** Shared files owned by a test plan and persisted in its JMX archive. */
public final class ArchiveFiles {
    public static final String PROPERTY = "BreakTest.archive.files";
    private static volatile TestPlan activePlan;
    private static final Map<String, Path> LOCAL_FILES = new ConcurrentHashMap<>();

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
                    ((JMeterProperty) value).getStringValue()));
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

    /** Imports the basename of a local path without silently renaming it. */
    public static String importEntryName(String filename) {
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return entryName(name);
    }

    public static void remove(TestPlan plan, String name) {
        Map<String, String> entries = references(plan);
        entries.remove(entryName(name));
        setReferences(plan, entries);
    }

    private static void setReferences(TestPlan plan, Map<String, String> references) {
        MapProperty property = new MapProperty();
        property.setName(PROPERTY);
        references.forEach((path, digest) -> property.addProperty(
                new StringProperty(path, digest)));
        plan.setProperty(property);
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
        setReferences(plan, references);
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
        TestPlan plan = currentPlan();
        String entry = entryName(name);
        String digest = plan == null ? null : references(plan).get(entry);
        if (digest == null) {
            throw new IOException("File is not available in the JMX archive: " + entry);
        }
        return materialize(entry, digest);
    }

    /** Cache hits use the stored version, without loading or hashing the payload. */
    public static Path materialize(String entry, String digest) throws IOException {
        String key = entry + ":" + digest;
        try {
            return LOCAL_FILES.computeIfAbsent(key, ignored -> {
                try {
                    byte[] content = JmxArchiveEntryStore.find(entry, digest)
                            .orElseThrow(() -> new IOException("Archive file content is unavailable: " + entry));
                    Path directory = Files.createTempDirectory("breaktest-archive-");
                    directory.toFile().deleteOnExit();
                    Path path = directory.resolve(Path.of(entry).getFileName());
                    try {
                        Files.write(path, content);
                    } catch (IOException failure) {
                        Files.deleteIfExists(path);
                        Files.deleteIfExists(directory);
                        throw failure;
                    }
                    path.toFile().deleteOnExit();
                    return path;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
