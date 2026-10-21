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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipFile;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveVersionsTest extends JMeterTestCase implements JMeterSerialTest {
    @TempDir
    Path directory;

    private static TestPlan testPlan(String name) {
        TestPlan plan = new TestPlan(name);
        plan.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS,
                "org.apache.jmeter.control.gui.TestPlanGui");
        plan.setProperty(org.apache.jmeter.testelement.TestElement.TEST_CLASS, TestPlan.class.getName());
        return plan;
    }

    private static TestPlan plan(HashTree tree) {
        return (TestPlan) tree.getArray()[0];
    }

    @Test
    void saveOpenSaveAsAndBulkDeletePreserveLatestAndHistory() throws Exception {
        Path file = directory.resolve("plan.jmx");
        TestPlan original = testPlan("First");
        HashTree tree = new ListedHashTree(original);
        SaveService.saveTreeToFile(tree, file);
        assertTrue(original.getArchiveVersions().list().isEmpty());
        original.setName("Second");
        SaveService.saveTreeToFile(tree, file);
        var versions = original.getArchiveVersions().list();
        assertEquals(1, versions.size());
        assertTrue(versions.get(0).size() > 0);
        assertNotNull(versions.get(0).date());
        byte[] beforeOpen = Files.readAllBytes(file);
        HashTree opened = original.getArchiveVersions().open(versions.get(0).entry());
        assertEquals("First", plan(opened).getName());
        assertArrayEquals(beforeOpen, Files.readAllBytes(file));
        SaveService.saveTreeToFile(opened, file);
        TestPlan latest = plan(SaveService.loadTree(file.toFile()));
        assertEquals("First", latest.getName());
        assertEquals(2, latest.getArchiveVersions().list().size());
        assertTrue(latest.getArchiveVersions().list().stream().anyMatch(version -> {
            try {
                return "Second".equals(plan(latest.getArchiveVersions().open(version.entry())).getName());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }));
        Path copy = directory.resolve("copy.jmx");
        SaveService.saveTreeToFile(new ListedHashTree(latest), copy);
        assertEquals(3, latest.getArchiveVersions().list().size());
        Set<String> selected = latest.getArchiveVersions().list().stream()
                .map(ArchiveVersions.Version::entry).collect(java.util.stream.Collectors.toSet());
        byte[] latestXml = SaveService.readArchiveEntry(copy.toFile(), SaveService.TEST_PLAN_ZIP_ENTRY).orElseThrow();
        latest.getArchiveVersions().delete(selected);
        assertTrue(latest.getArchiveVersions().list().isEmpty());
        assertArrayEquals(latestXml,
                SaveService.readArchiveEntry(copy.toFile(), SaveService.TEST_PLAN_ZIP_ENTRY).orElseThrow());
        assertEquals(2, plan(SaveService.loadTree(file.toFile())).getArchiveVersions().list().size());
        assertThrows(java.io.IOException.class,
                () -> latest.getArchiveVersions().delete(Set.of("testplan.jmx")));
    }

    @Test
    void snapshotsKeepOriginalAttachmentsWithoutRecursiveHistory() throws Exception {
        Path file = directory.resolve("attachments.jmx");
        TestPlan original = testPlan("Original");
        String entry = "files/data.csv";
        byte[] data = "name\nAlice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String checksum = ArchiveFiles.checksum(data);
        JmxArchiveEntryStore.register(entry, checksum, data);
        org.apache.jmeter.testelement.property.MapProperty index =
                new org.apache.jmeter.testelement.property.MapProperty();
        index.setName(ArchiveFiles.PROPERTY);
        index.addProperty(entry, new org.apache.jmeter.testelement.property.StringProperty(entry, checksum));
        original.setProperty(index);
        HashTree tree = new ListedHashTree(original);
        SaveService.saveTreeToFile(tree, file);
        original.removeProperty(ArchiveFiles.PROPERTY);
        SaveService.saveTreeToFile(tree, file);
        SaveService.saveTreeToFile(tree, file);
        for (var version : original.getArchiveVersions().list()) {
            Path snapshot = directory.resolve("snapshot.jmx");
            Files.write(snapshot, SaveService.readArchiveEntry(file.toFile(), version.entry()).orElseThrow());
            try (ZipFile zip = new ZipFile(snapshot.toFile())) {
                assertFalse(zip.stream().anyMatch(item -> item.getName().startsWith(ArchiveVersions.DIRECTORY)));
            }
        }
        var oldest = original.getArchiveVersions().list().stream()
                .filter(version -> {
                    try {
                        return ArchiveFiles.references(plan(original.getArchiveVersions().open(version.entry())))
                                .containsKey(entry);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }).findFirst().orElseThrow();
        TestPlan restored = plan(original.getArchiveVersions().open(oldest.entry()));
        assertEquals(checksum, ArchiveFiles.references(restored).get(entry));
        assertArrayEquals(data, JmxArchiveEntryStore.find(entry, checksum).orElseThrow());
    }

    @Test
    void failedSaveDoesNotAdvanceHistory() throws Exception {
        Path file = directory.resolve("safe.jmx");
        TestPlan original = testPlan("Saved");
        HashTree tree = new ListedHashTree(original);
        SaveService.saveTreeToFile(tree, file);
        byte[] saved = Files.readAllBytes(file);
        original.setUnavailableArchiveFiles(Set.of("files/missing.csv"));
        original.setProperty(new org.apache.jmeter.testelement.property.MapProperty(
                ArchiveFiles.PROPERTY, new java.util.HashMap<>()));
        assertThrows(java.io.IOException.class, () -> SaveService.saveTreeToFile(tree, file));
        assertArrayEquals(saved, Files.readAllBytes(file));
        assertTrue(original.getArchiveVersions().list().isEmpty());
    }
    private static void attach(TestPlan plan, byte[] content) {
        String entry = "files/data.csv";
        String hash = ArchiveFiles.checksum(content);
        JmxArchiveEntryStore.register(entry, hash, content);
        var index = new org.apache.jmeter.testelement.property.MapProperty();
        index.setName(ArchiveFiles.PROPERTY);
        index.addProperty(entry, new org.apache.jmeter.testelement.property.StringProperty(entry, hash));
        plan.setProperty(index);
    }

    private static long blobs(Path file) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.stream().filter(entry -> entry.getName().startsWith(ArchiveVersions.BLOBS)).count();
        }
    }

    private static Set<String> versionsNamed(ArchiveVersions versions, String name) throws Exception {
        Set<String> result = new java.util.HashSet<>();
        for (var version : versions.list()) {
            if (name.equals(plan(versions.open(version.entry())).getName())) {
                result.add(version.entry());
            }
        }
        return result;
    }

    @Test
    void sharesUnchangedContentAndCollectsOnlyUnreferencedCopies() throws Exception {
        Path file = directory.resolve("shared.jmx");
        TestPlan current = testPlan("A");
        byte[] a = "name\nAlice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = "name\nBob\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        attach(current, a);
        HashTree tree = new ListedHashTree(current);
        SaveService.saveTreeToFile(tree, file);
        SaveService.saveTreeToFile(tree, file);
        SaveService.saveTreeToFile(tree, file);
        assertEquals(0, blobs(file), "Unchanged content is shared directly with the latest entry");
        assertEquals(2, current.getArchiveVersions().list().size());
        for (var version : current.getArchiveVersions().list()) {
            Path snapshot = directory.resolve("small-snapshot.jmx");
            Files.write(snapshot, SaveService.readArchiveEntry(file.toFile(), version.entry()).orElseThrow());
            try (ZipFile zip = new ZipFile(snapshot.toFile())) {
                assertEquals(2, zip.size(), "Snapshots contain XML and a reference manifest only");
                assertNull(zip.getEntry("files/data.csv"));
            }
        }
        current.setName("B");
        attach(current, b);
        SaveService.saveTreeToFile(tree, file);
        SaveService.saveTreeToFile(tree, file);
        assertEquals(1, blobs(file), "All A versions share one old copy");
        assertArrayEquals(a, SaveService.readArchiveEntry(file.toFile(), ArchiveVersions.BLOBS
                + ArchiveVersions.digest(a)).orElseThrow());
        assertArrayEquals(b, SaveService.readArchiveEntry(file.toFile(), "files/data.csv").orElseThrow());
        current.setName("No file");
        current.removeProperty(ArchiveFiles.PROPERTY);
        SaveService.saveTreeToFile(tree, file);
        assertEquals(2, blobs(file), "Removed files remain available to their versions");
        ArchiveVersions versions = current.getArchiveVersions();
        Set<String> aVersions = versionsNamed(versions, "A");
        assertEquals(3, aVersions.size());
        String oneA = aVersions.iterator().next();
        versions.delete(Set.of(oneA));
        assertEquals(2, blobs(file), "Other A versions still need A");
        aVersions.remove(oneA);
        versions.delete(aVersions);
        assertEquals(1, blobs(file));
        String bVersion = versionsNamed(versions, "B").iterator().next();
        HashTree restored = versions.open(bVersion);
        assertEquals(ArchiveFiles.checksum(b), ArchiveFiles.references(plan(restored)).get("files/data.csv"));
        SaveService.saveTreeToFile(restored, file);
        assertEquals(0, blobs(file), "Restored latest content is shared with B history again");
        assertArrayEquals(b, SaveService.readArchiveEntry(file.toFile(), "files/data.csv").orElseThrow());
        versions.delete(versions.list().stream().map(ArchiveVersions.Version::entry)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, blobs(file));
        assertArrayEquals(b, SaveService.readArchiveEntry(file.toFile(), "files/data.csv").orElseThrow());
    }

    private static void rewrite(Path file, String replace, byte[] content) throws Exception {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                try (var input = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        if (content == null) {
            entries.remove(replace);
        } else {
            entries.put(replace, content);
        }
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    @Test
    void migratesLegacySelfContainedVersionsAndPreservesTheirFiles() throws Exception {
        Path file = directory.resolve("legacy.jmx");
        TestPlan current = testPlan("Old");
        byte[] a = {1, 2, 3};
        byte[] b = {4, 5, 6};
        attach(current, a);
        SaveService.saveTreeToFile(new ListedHashTree(current), file);
        byte[] legacy = Files.readAllBytes(file);
        current = testPlan("Latest");
        attach(current, b);
        SaveService.saveTreeToFile(new ListedHashTree(current), file);
        String legacyName = ArchiveVersions.DIRECTORY + "legacy.jmx";
        rewrite(file, legacyName, legacy);
        assertEquals("Old", plan(current.getArchiveVersions().open(legacyName)).getName());
        SaveService.saveTreeToFile(new ListedHashTree(current), file);
        assertEquals(1, blobs(file));
        TestPlan old = plan(current.getArchiveVersions().open(legacyName));
        assertEquals(ArchiveFiles.checksum(a), ArchiveFiles.references(old).get("files/data.csv"));
        assertFalse(java.util.Arrays.equals(legacy,
                SaveService.readArchiveEntry(file.toFile(), legacyName).orElseThrow()));
        current.getArchiveVersions().delete(Set.of(legacyName));
        assertEquals(0, blobs(file));
        assertArrayEquals(b, SaveService.readArchiveEntry(file.toFile(), "files/data.csv").orElseThrow());
    }

    @Test
    void missingOrCorruptHistoricalContentFailsWithoutReplacingArchive() throws Exception {
        for (boolean missing : new boolean[]{true, false}) {
            Path file = directory.resolve("broken-" + missing + ".jmx");
            TestPlan current = testPlan("Old");
            byte[] a = {7, 8, 9};
            attach(current, a);
            SaveService.saveTreeToFile(new ListedHashTree(current), file);
            current.removeProperty(ArchiveFiles.PROPERTY);
            SaveService.saveTreeToFile(new ListedHashTree(current), file);
            String name = current.getArchiveVersions().list().get(0).entry();
            rewrite(file, ArchiveVersions.BLOBS + ArchiveVersions.digest(a), missing ? null : new byte[]{0});
            byte[] damaged = Files.readAllBytes(file);
            assertThrows(java.io.IOException.class, () -> current.getArchiveVersions().open(name));
            assertThrows(java.io.IOException.class,
                    () -> SaveService.saveTreeToFile(new ListedHashTree(current), file));
            assertArrayEquals(damaged, Files.readAllBytes(file));
            assertThrows(java.io.IOException.class, () -> current.getArchiveVersions().delete(Set.of()));
            assertArrayEquals(damaged, Files.readAllBytes(file));
            current.getArchiveVersions().delete(Set.of(name));
            assertEquals(0, blobs(file), "A broken version can be removed explicitly");
        }
    }

    @Test
    void recordingBundlesShareAndRestoreAllTheirEntries() throws Exception {
        byte[] har = ("{\"log\":{\"entries\":[{"
                + "\"request\":{\"method\":\"POST\",\"url\":\"https://example.invalid/api\","
                + "\"postData\":{\"text\":\"shared-body\"}},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"shared-body\"}}}]}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var recording = org.apache.jmeter.recording.RecordedExchangeStore.fromHar(har, "source.har");
        JmxArchiveEntryStore.registerBundle(recording.manifestEntryName(), recording.checksum(), recording.entries());
        TestPlan current = testPlan("Recording");
        current.setProperty(org.apache.jmeter.recording.RecordedExchangeStore.MANIFEST_PROPERTY,
                recording.manifestEntryName());
        current.setProperty(org.apache.jmeter.recording.RecordedExchangeStore.CHECKSUM_PROPERTY, recording.checksum());
        Path file = directory.resolve("recording.jmx");
        HashTree tree = new ListedHashTree(current);
        SaveService.saveTreeToFile(tree, file);
        SaveService.saveTreeToFile(tree, file);
        assertEquals(0, blobs(file));
        String version = current.getArchiveVersions().list().get(0).entry();
        current.removeProperty(org.apache.jmeter.recording.RecordedExchangeStore.MANIFEST_PROPERTY);
        current.removeProperty(org.apache.jmeter.recording.RecordedExchangeStore.CHECKSUM_PROPERTY);
        SaveService.saveTreeToFile(tree, file);
        assertEquals(recording.entries().size(), blobs(file));
        HashTree restored = current.getArchiveVersions().open(version);
        Path copy = directory.resolve("restored-recording.jmx");
        SaveService.saveTreeToFile(restored, copy);
        assertEquals(0, blobs(copy));
        for (var entry : recording.entries().entrySet()) {
            assertArrayEquals(entry.getValue(), SaveService.readArchiveEntry(copy.toFile(), entry.getKey()).orElseThrow());
        }
    }

    @Test
    void replacingSharedFileKeepsReferencesAndPreviouslySavedContents() throws Exception {
        Path file = directory.resolve("replace.jmx");
        TestPlan current = testPlan("CSV plan");
        byte[] oldContent = {1, 2, 3};
        byte[] newContent = {4, 5, 6};
        ArchiveFiles.put(current, "data.csv", oldContent, false);
        current.setProperty("fileReference", "${__archiveFile(data.csv)}");
        current.setProperty(JmxArchiveEntryStore.CSV_ENTRY_PROPERTY, "files/data.csv");
        current.setProperty(JmxArchiveEntryStore.CSV_CHECKSUM_PROPERTY, ArchiveFiles.checksum(oldContent));
        HashTree tree = new ListedHashTree(current);
        SaveService.saveTreeToFile(tree, file);
        byte[] saved = Files.readAllBytes(file);
        ArchiveFiles.put(current, "files/data.csv", newContent, true);
        assertArrayEquals(saved, Files.readAllBytes(file), "Replacement is staged until Save");
        assertEquals(Set.of("files/data.csv"), ArchiveFiles.references(current).keySet());
        assertEquals("${__archiveFile(data.csv)}", current.getPropertyAsString("fileReference"));
        SaveService.saveTreeToFile(tree, file);
        assertArrayEquals(newContent, SaveService.readArchiveEntry(file.toFile(), "files/data.csv").orElseThrow());
        var versions = current.getArchiveVersions();
        HashTree old = versions.open(versions.list().get(0).entry());
        assertEquals(ArchiveFiles.checksum(oldContent), ArchiveFiles.references(plan(old)).get("files/data.csv"));
        assertEquals("${__archiveFile(data.csv)}", plan(old).getPropertyAsString("fileReference"));
        Path restored = directory.resolve("restored.csv.jmx");
        SaveService.saveTreeToFile(old, restored);
        assertArrayEquals(oldContent, SaveService.readArchiveEntry(restored.toFile(), "files/data.csv").orElseThrow());
    }

}
