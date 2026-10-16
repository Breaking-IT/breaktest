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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;

/**
 * Archive-local history. Snapshots contain XML and attachment checksums. Content is
 * shared with the latest plan's ordinary entries, or stored once in versions/blobs/.
 * Rewriting history retains only blobs referenced by a surviving snapshot.
 */
public final class ArchiveVersions {
    public static final String DIRECTORY = "versions/";
    static final String BLOBS = DIRECTORY + "blobs/";
    private static final String MANIFEST = "__breaktest_version_attachments_v1.properties";
    private Path source;

    public record Version(String entry, Instant date, long size) { }

    private static final class Snapshot {
        private final byte[] xml;
        private final Map<String, String> attachments;

        private Snapshot(byte[] xml, Map<String, String> attachments) {
            this.xml = xml;
            this.attachments = attachments;
        }
    }

    @FunctionalInterface
    private interface Content {
        InputStream open() throws IOException;
    }

    public void setSource(Path source) {
        this.source = source.toAbsolutePath();
    }

    public List<Version> list() throws IOException {
        List<Version> versions = new ArrayList<>();
        if (source == null) {
            return versions;
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (isVersion(entry.getName()) && !entry.isDirectory()) {
                    versions.add(new Version(entry.getName(), Instant.ofEpochMilli(entry.getTime()), entry.getSize()));
                }
            }
        }
        versions.sort(Comparator.comparing(Version::date).reversed());
        return versions;
    }

    private static boolean isVersion(String name) {
        return name.startsWith(DIRECTORY) && !name.startsWith(BLOBS) && name.endsWith(".jmx")
                && JmxArchiveEntryStore.isSafeEntryName(name);
    }

    static String digest(byte[] bytes) throws IOException {
        return digest(new ByteArrayInputStream(bytes));
    }

    private static String digest(InputStream input) throws IOException {
        try (DigestInputStream stream = new DigestInputStream(input, MessageDigest.getInstance("SHA-256"))) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(stream.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Copies history and backs up the saved latest plan, sharing content already written by SaveService. */
    public void writeTo(ZipOutputStream output, Set<String> latestContent) throws IOException {
        if (source == null) {
            return;
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Catalog catalog = new Catalog(zip);
            Set<String> written = new HashSet<>(latestContent);
            writeHistory(zip, output, catalog, written, Set.of());
            ZipEntry previous = zip.getEntry(SaveService.TEST_PLAN_ZIP_ENTRY);
            String name = DIRECTORY + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".jmx";
            Snapshot snapshot = new Snapshot(read(zip, SaveService.TEST_PLAN_ZIP_ENTRY), catalog.latest);
            writeSnapshot(output, name, previous.getTime(), snapshot);
            writeContent(output, snapshot, catalog, written);
        }
    }

    public HashTree open(String name) throws IOException {
        if (source == null || !isVersion(name)) {
            throw new IOException("Invalid archive version");
        }
        Path temporary = Files.createTempFile("breaktest-open-version-", ".jmx");
        try {
            try (ZipFile zip = new ZipFile(source.toFile());
                    ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                Catalog catalog = new Catalog(zip);
                Snapshot snapshot = readSnapshot(zip, name, catalog);
                write(output, SaveService.TEST_PLAN_ZIP_ENTRY, snapshot.xml);
                for (Map.Entry<String, String> attachment : snapshot.attachments.entrySet()) {
                    output.putNextEntry(new ZipEntry(attachment.getKey()));
                    catalog.copy(attachment.getValue(), output);
                    output.closeEntry();
                }
            }
            HashTree tree = SaveService.loadTree(temporary.toFile());
            if (tree.size() != 1 || !(tree.getArray()[0] instanceof TestPlan plan)) {
                throw new IOException("Version does not contain a test plan");
            }
            plan.setArchiveVersions(this);
            return tree;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Deletes selected snapshots and unreferenced blobs, preserving the latest plan's ordinary entries. */
    public void delete(Set<String> names) throws IOException {
        if (source == null || names.stream().anyMatch(name -> !isVersion(name))) {
            throw new IOException("Invalid archive version selection");
        }
        Path temporary = Files.createTempFile(source.getParent(), ".breaktest-versions-", ".jmx");
        try {
            try (ZipFile zip = new ZipFile(source.toFile());
                    ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                Catalog catalog = new Catalog(zip);
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    if (!entry.getName().startsWith(DIRECTORY)) {
                        ZipEntry copy = new ZipEntry(entry.getName());
                        if (entry.getTime() >= 0) {
                            copy.setTime(entry.getTime());
                        }
                        output.putNextEntry(copy);
                        try (InputStream input = zip.getInputStream(entry)) {
                            input.transferTo(output);
                        }
                        output.closeEntry();
                    }
                }
                writeHistory(zip, output, catalog, new HashSet<>(catalog.latest.values()), names);
            }
            if (Files.getFileAttributeView(source, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(source));
            }
            try {
                Files.move(temporary, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeHistory(ZipFile zip, ZipOutputStream output, Catalog catalog,
            Set<String> written, Set<String> excluded) throws IOException {
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (isVersion(entry.getName()) && !entry.isDirectory() && !excluded.contains(entry.getName())) {
                Snapshot snapshot = readSnapshot(zip, entry.getName(), catalog);
                writeSnapshot(output, entry.getName(), entry.getTime(), snapshot);
                writeContent(output, snapshot, catalog, written);
            }
        }
    }

    private static void writeContent(ZipOutputStream output, Snapshot snapshot, Catalog catalog,
            Set<String> written) throws IOException {
        for (String hash : snapshot.attachments.values()) {
            if (written.add(hash)) {
                output.putNextEntry(new ZipEntry(BLOBS + hash));
                catalog.copy(hash, output);
                output.closeEntry();
            }
        }
    }

    private static Snapshot readSnapshot(ZipFile zip, String name, Catalog catalog) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ZipEntry version = zip.getEntry(name);
        if (version == null) {
            throw new IOException("Version no longer exists: " + name);
        }
        try (ZipInputStream input = new ZipInputStream(zip.getInputStream(version))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    if (!JmxArchiveEntryStore.isSafeEntryName(entry.getName())
                            || entries.put(entry.getName(), input.readAllBytes()) != null) {
                        throw new IOException("Invalid version entry: " + entry.getName());
                    }
                }
            }
        }
        byte[] xml = entries.remove(SaveService.TEST_PLAN_ZIP_ENTRY);
        if (xml == null) {
            throw new IOException("Version is missing testplan.jmx: " + name);
        }
        Map<String, String> attachments = new LinkedHashMap<>();
        byte[] manifest = entries.remove(MANIFEST);
        if (manifest != null) {
            Properties references = new Properties();
            references.loadFromXML(new ByteArrayInputStream(manifest));
            for (String entry : references.stringPropertyNames()) {
                String hash = references.getProperty(entry);
                if (!JmxArchiveEntryStore.isSafeEntryName(entry) || entry.startsWith(DIRECTORY)
                        || SaveService.TEST_PLAN_ZIP_ENTRY.equals(entry) || !hash.matches("[a-f0-9]{64}")) {
                    throw new IOException("Invalid version attachment: " + entry);
                }
                attachments.put(entry, hash);
            }
            if (!entries.isEmpty()) {
                throw new IOException("Unexpected embedded version attachments: " + name);
            }
        } else {
            // Migrate the former self-contained snapshots on the next save or deletion.
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (!entry.getKey().startsWith(DIRECTORY)) {
                    byte[] content = entry.getValue();
                    String hash = digest(content);
                    attachments.put(entry.getKey(), hash);
                    catalog.contents.putIfAbsent(hash, () -> new ByteArrayInputStream(content));
                }
            }
        }
        return new Snapshot(xml, attachments);
    }

    private static void writeSnapshot(ZipOutputStream output, String name, long time, Snapshot snapshot)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream nested = new ZipOutputStream(bytes)) {
            write(nested, SaveService.TEST_PLAN_ZIP_ENTRY, snapshot.xml);
            Properties references = new Properties();
            references.putAll(snapshot.attachments);
            ByteArrayOutputStream manifest = new ByteArrayOutputStream();
            references.storeToXML(manifest, "Attachment names mapped to SHA-256 content");
            write(nested, MANIFEST, manifest.toByteArray());
        }
        ZipEntry entry = new ZipEntry(name);
        if (time >= 0) {
            entry.setTime(time);
        }
        output.putNextEntry(entry);
        output.write(bytes.toByteArray());
        output.closeEntry();
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException("Missing archive entry: " + name);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static void write(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    /** Keep existing blobs on disk; only stream their contents when needed. */
    private static final class Catalog {
        private final Map<String, Content> contents = new HashMap<>();
        private final Map<String, String> latest = new LinkedHashMap<>();

        private Catalog(ZipFile zip) throws IOException {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || SaveService.TEST_PLAN_ZIP_ENTRY.equals(name)) {
                    continue;
                }
                if (name.startsWith(BLOBS)) {
                    String hash = name.substring(BLOBS.length());
                    if (hash.matches("[a-f0-9]{64}")) {
                        contents.putIfAbsent(hash, () -> zip.getInputStream(entry));
                    }
                } else if (!name.startsWith(DIRECTORY)) {
                    if (!JmxArchiveEntryStore.isSafeEntryName(name)) {
                        throw new IOException("Invalid archive attachment: " + name);
                    }
                    String hash = digest(zip.getInputStream(entry));
                    latest.put(name, hash);
                    contents.put(hash, () -> zip.getInputStream(entry));
                }
            }
        }

        private void copy(String hash, ZipOutputStream output) throws IOException {
            Content content = contents.get(hash);
            if (content == null) {
                throw new IOException("Missing version attachment content: " + hash);
            }
            try (InputStream input = content.open()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                DigestInputStream checked = new DigestInputStream(input, digest);
                checked.transferTo(output);
                if (!hash.equals(HexFormat.of().formatHex(digest.digest()))) {
                    throw new IOException("Corrupt version attachment content: " + hash);
                }
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
