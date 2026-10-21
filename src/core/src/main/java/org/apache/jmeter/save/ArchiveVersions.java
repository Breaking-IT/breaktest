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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Archive-local history. Snapshots contain XML and attachment checksums. Content is
 * shared with the latest plan's ordinary entries, or stored once in versions/blobs/.
 * Rewriting history retains only blobs referenced by a surviving snapshot. Versions
 * whose content is missing or corrupt are carried over unchanged rather than blocking a save.
 */
public final class ArchiveVersions {
    private static final Logger log = LoggerFactory.getLogger(ArchiveVersions.class);
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

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String digest(InputStream input) throws IOException {
        try (DigestInputStream stream = new DigestInputStream(input, sha256())) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(stream.getMessageDigest().digest());
        }
    }

    /**
     * Copies history and backs up the previously saved plan unless it equals the plan being saved,
     * sharing content already written by SaveService. An unreadable source archive only skips history:
     * nothing has been written yet, so the latest plan is still saved.
     *
     * @param latestXml SHA-256 of the testplan.jmx being saved
     * @param latestContent entries being saved, mapped to their SHA-256 content
     */
    public void writeTo(ZipOutputStream output, String latestXml, Map<String, String> latestContent)
            throws IOException {
        if (source == null) {
            return;
        }
        ZipFile zip;
        try {
            zip = new ZipFile(source.toFile());
        } catch (IOException e) {
            log.warn("Saving without version history; cannot read {}: {}", source, e.toString());
            return;
        }
        try (zip) {
            // A readable archive can still contain damaged entries. Fail the atomic save
            // rather than replace that archive and silently discard all its history.
            Catalog catalog = new Catalog(zip);
            byte[] previousXml = read(zip, SaveService.TEST_PLAN_ZIP_ENTRY);
            Set<String> written = new HashSet<>(latestContent.values());
            writeHistory(zip, output, catalog, written, Set.of());
            if (digest(previousXml).equals(latestXml) && catalog.latest.equals(latestContent)) {
                return; // Saving an unchanged plan would only duplicate the latest as a version.
            }
            ZipEntry previous = zip.getEntry(SaveService.TEST_PLAN_ZIP_ENTRY);
            String name = DIRECTORY + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".jmx";
            Snapshot snapshot = new Snapshot(previousXml, catalog.latest);
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
                        copyEntry(zip, entry, output);
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
        boolean unknownDependencies = false;
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (!isVersion(entry.getName()) || entry.isDirectory() || excluded.contains(entry.getName())) {
                continue;
            }
            Snapshot snapshot;
            try {
                snapshot = readSnapshot(zip, entry.getName(), catalog);
            } catch (IOException e) {
                log.warn("Keeping unreadable archive version {} unchanged: {}", entry.getName(), e.getMessage());
                copyEntry(zip, entry, output);
                unknownDependencies = true;
                continue;
            }
            if (snapshot.attachments.values().stream().allMatch(hash -> catalog.contents.containsKey(hash)
                    || written.contains(hash))) {
                writeSnapshot(output, entry.getName(), entry.getTime(), snapshot);
            } else {
                // Keep it, and any damaged content, so it can still be inspected or deleted explicitly.
                log.warn("Keeping archive version {} unchanged; its attachment content is missing or corrupt",
                        entry.getName());
                copyEntry(zip, entry, output);
            }
            writeContent(output, snapshot, catalog, written);
        }
        if (unknownDependencies) {
            // We cannot prove any content is unused while a retained manifest is unreadable.
            // Include old latest attachments too: the new latest may replace or remove them.
            for (String hash : catalog.contents.keySet()) {
                if (written.add(hash)) {
                    output.putNextEntry(new ZipEntry(BLOBS + hash));
                    catalog.copy(hash, output);
                    output.closeEntry();
                }
            }
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.getName().startsWith(BLOBS) && !entry.isDirectory()) {
                    String hash = entry.getName().substring(BLOBS.length());
                    if (!catalog.damagedWritten.contains(hash) && written.add(hash)) {
                        copyEntry(zip, entry, output);
                    }
                }
            }
        }
    }

    private static void writeContent(ZipOutputStream output, Snapshot snapshot, Catalog catalog,
            Set<String> written) throws IOException {
        for (String hash : snapshot.attachments.values()) {
            if (!catalog.contents.containsKey(hash) && !written.contains(hash)) {
                catalog.copyDamaged(hash, output);
            } else if (written.add(hash)) {
                output.putNextEntry(new ZipEntry(BLOBS + hash));
                catalog.copy(hash, output);
                output.closeEntry();
            }
        }
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, ZipOutputStream output) throws IOException {
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
        /** Verified content by SHA-256. */
        private final Map<String, Content> contents = new HashMap<>();
        private final Map<String, String> latest = new LinkedHashMap<>();
        /** Blobs whose content no longer matches their name. */
        private final Map<String, ZipEntry> damaged = new HashMap<>();
        private final Set<String> damagedWritten = new HashSet<>();
        private final ZipFile zip;

        private Catalog(ZipFile zip) throws IOException {
            this.zip = zip;
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || SaveService.TEST_PLAN_ZIP_ENTRY.equals(name)) {
                    continue;
                }
                if (name.startsWith(BLOBS)) {
                    String hash = name.substring(BLOBS.length());
                    if (!hash.matches("[a-f0-9]{64}")) {
                        continue;
                    }
                    if (hash.equals(digest(zip.getInputStream(entry)))) {
                        contents.putIfAbsent(hash, () -> zip.getInputStream(entry));
                    } else {
                        damaged.put(hash, entry);
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
                throw new IOException((damaged.containsKey(hash) ? "Corrupt" : "Missing")
                        + " version attachment content: " + hash);
            }
            try (InputStream input = content.open()) {
                MessageDigest digest = sha256();
                DigestInputStream checked = new DigestInputStream(input, digest);
                checked.transferTo(output);
                if (!hash.equals(HexFormat.of().formatHex(digest.digest()))) {
                    throw new IOException("Corrupt version attachment content: " + hash);
                }
            }
        }

        /** Carries a damaged blob over unchanged for the versions kept alongside it. */
        private void copyDamaged(String hash, ZipOutputStream output) throws IOException {
            ZipEntry entry = damaged.get(hash);
            if (entry != null && damagedWritten.add(hash)) {
                copyEntry(zip, entry, output);
            }
        }
    }
}
