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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveFilesTest extends JMeterTestCase implements JMeterSerialTest {
    @TempDir
    Path directory;

    @AfterEach
    void reset() {
        ArchiveFiles.activate(null);
    }

    @Test
    void indexedArchiveDoesNotReimportEntriesRemovedFromIndex() throws Exception {
        TestPlan plan = new TestPlan();
        plan.setProperty("TestElement.gui_class", "org.apache.jmeter.control.gui.TestPlanGui");
        ArchiveFiles.remove(plan, "removed.bin");
        HashTree tree = new HashTree();
        tree.add(plan);
        var original = new java.io.ByteArrayOutputStream();
        SaveService.saveTree(tree, original);
        Path file = directory.resolve("with-unindexed-entry.jmx");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file));
                var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                input.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("files/removed.bin"));
            zip.write(new byte[] {1});
            zip.closeEntry();
        }
        TestPlan loaded = (TestPlan) SaveService.loadTree(file.toFile()).getArray()[0];
        assertEquals(java.util.Map.of(), ArchiveFiles.references(loaded));
    }

    @Test
    void materializationCacheUsesVersionAndDoesNotReadPayloadOnHit() throws Exception {
        String entry = "files/cache-test.bin";
        byte[] content = {1, 2, 3};
        String version = ArchiveFiles.checksum(content);
        JmxArchiveEntryStore.register(entry, version, content);
        Path first = ArchiveFiles.materialize(entry, version);
        // Altering the backing cache after materialization must not cause a cache-hit reread or rehash.
        JmxArchiveEntryStore.register(entry, version, new byte[] {9});
        org.junit.jupiter.api.Assertions.assertSame(first, ArchiveFiles.materialize(entry, version));
        assertArrayEquals(content, Files.readAllBytes(first));
        String updatedVersion = ArchiveFiles.checksum(new byte[] {4});
        JmxArchiveEntryStore.register(entry, updatedVersion, new byte[] {4});
        assertArrayEquals(new byte[] {4}, Files.readAllBytes(ArchiveFiles.materialize(entry, updatedVersion)));
    }

    @Test
    void deletionPersistsDespiteLegacyCsvReference() throws Exception {
        TestPlan plan = new TestPlan();
        plan.setProperty("TestElement.gui_class", "org.apache.jmeter.control.gui.TestPlanGui");
        byte[] content = {7};
        ArchiveFiles.put(plan, "delete-me.csv", content, false);
        plan.setProperty(JmxArchiveEntryStore.CSV_ENTRY_PROPERTY, "files/delete-me.csv");
        plan.setProperty(JmxArchiveEntryStore.CSV_CHECKSUM_PROPERTY, ArchiveFiles.checksum(content));
        ArchiveFiles.remove(plan, "delete-me.csv");
        HashTree tree = new HashTree();
        tree.add(plan);
        Path path = directory.resolve("deleted.jmx");
        try (OutputStream output = Files.newOutputStream(path)) {
            SaveService.saveTree(tree, output);
        }
        org.junit.jupiter.api.Assertions.assertTrue(SaveService.readArchiveEntry(path.toFile(), "files/delete-me.csv").isEmpty());
        TestPlan reopened = (TestPlan) SaveService.loadTree(path.toFile()).getArray()[0];
        assertEquals(java.util.Map.of(), ArchiveFiles.references(reopened));
    }

    @Test
    void missingSharedFileFailsBeforeWritingArchive() throws Exception {
        TestPlan plan = new TestPlan();
        var index = new org.apache.jmeter.testelement.property.MapProperty();
        index.setName(ArchiveFiles.PROPERTY);
        index.addProperty(new org.apache.jmeter.testelement.property.StringProperty("files/missing.bin", "missing-version"));
        plan.setProperty(index);
        HashTree tree = new HashTree();
        tree.add(plan);
        var output = new java.io.ByteArrayOutputStream();
        assertThrows(IOException.class, () -> SaveService.saveTree(tree, output));
        assertEquals(0, output.size());
    }

    @Test
    void guiReopenUsesVisiblePlanInsteadOfStaleInvisibleRoot() throws Exception {
        java.lang.reflect.Field field = org.apache.jmeter.gui.GuiPackage.class.getDeclaredField("guiPack");
        field.setAccessible(true);
        Object previousGui = field.get(null);
        try {
            TestPlan oldPlan = new TestPlan("Old plan");
            ArchiveFiles.put(oldPlan, "old.csv", new byte[] {1}, false);
            var model = new org.apache.jmeter.gui.tree.JMeterTreeModel(oldPlan);
            org.apache.jmeter.gui.GuiPackage.initInstance(
                    new org.apache.jmeter.gui.tree.JMeterTreeListener(model), model);
            TestPlan reopened = new TestPlan("Reopened plan");
            byte[] content = "name\nAlice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ArchiveFiles.put(reopened, "accounts.csv", content, false);
            model.clearTestPlan(reopened);
            // getNodesOfType finds the old hidden root first, reproducing the GUI reopen state.
            assertEquals(oldPlan, model.getNodesOfType(TestPlan.class).get(0).getTestElement());
            org.junit.jupiter.api.Assertions.assertSame(reopened, ArchiveFiles.currentPlan());
            assertArrayEquals(content, ArchiveFiles.read("accounts.csv"));
            ArchiveFiles.put(ArchiveFiles.currentPlan(), "accounts.csv", new byte[] {2}, true);
            assertArrayEquals(new byte[] {2}, ArchiveFiles.read("accounts.csv"));
            assertThrows(IOException.class, () -> ArchiveFiles.read("old.csv"));
        } finally {
            field.set(null, previousGui);
        }
    }

    @Test
    void reopeningOlderCsvArchiveRebuildsSharedFileIndex() throws Exception {
        TestPlan plan = new TestPlan();
        plan.setProperty("TestElement.gui_class", "org.apache.jmeter.control.gui.TestPlanGui");
        byte[] bytes = "name\nAlice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String entry = "files/legacy.csv";
        String checksum = ArchiveFiles.checksum(bytes);
        // This is the older format: a CSV attachment reference without a shared-file index.
        plan.setProperty(JmxArchiveEntryStore.CSV_ENTRY_PROPERTY, entry);
        plan.setProperty(JmxArchiveEntryStore.CSV_CHECKSUM_PROPERTY, checksum);
        JmxArchiveEntryStore.register(entry, checksum, bytes);
        HashTree tree = new HashTree();
        tree.add(plan);
        Path archive = directory.resolve("legacy.jmx");
        try (OutputStream output = Files.newOutputStream(archive)) {
            SaveService.saveTree(tree, output);
        }
        TestPlan loaded = (TestPlan) SaveService.loadTree(archive.toFile()).getArray()[0];
        assertEquals(checksum, ArchiveFiles.references(loaded).get(entry));
        ArchiveFiles.activate(loaded);
        assertArrayEquals(bytes, ArchiveFiles.read(entry));
        // A second save/reopen retains the rebuilt index.
        HashTree updated = new HashTree();
        updated.add(loaded);
        try (OutputStream output = Files.newOutputStream(archive)) {
            SaveService.saveTree(updated, output);
        }
        TestPlan reloaded = (TestPlan) SaveService.loadTree(archive.toFile()).getArray()[0];
        assertEquals(checksum, ArchiveFiles.references(reloaded).get(entry));
    }

    @Test
    void unreferencedBinaryFileSurvivesArchiveReloadAndKeepsFilename() throws Exception {
        TestPlan plan = new TestPlan();
        plan.setProperty("TestElement.gui_class", "org.apache.jmeter.control.gui.TestPlanGui");
        byte[] bytes = {0, 1, -1, 13, 10};
        ArchiveFiles.put(plan, "upload.bin", bytes, false);
        Path archive = directory.resolve("portable.jmx");
        HashTree tree = new HashTree();
        tree.add(plan);
        try (OutputStream output = Files.newOutputStream(archive)) {
            SaveService.saveTree(tree, output);
        }
        assertArrayEquals(bytes, SaveService.readArchiveEntry(archive.toFile(), "files/upload.bin").orElseThrow());
        JmxArchiveEntryStore.register("files/upload.bin", ArchiveFiles.checksum(bytes), new byte[0]);
        TestPlan loaded = (TestPlan) SaveService.loadTree(archive.toFile()).getArray()[0];
        ArchiveFiles.activate(loaded);
        assertArrayEquals(bytes, ArchiveFiles.read("upload.bin"));
        Path local = ArchiveFiles.resolve("files/upload.bin");
        assertEquals("upload.bin", local.getFileName().toString());
        assertArrayEquals(bytes, Files.readAllBytes(local));
        assertEquals(local.toFile(), FileServer.getFileServer().resolveFile("archive:upload.bin"));
        ArchiveFiles.activate(new TestPlan());
        assertThrows(IOException.class, () -> ArchiveFiles.resolve("upload.bin"));
    }

    @Test
    void duplicateNamesAndTraversalAreRejected() {
        TestPlan plan = new TestPlan();
        ArchiveFiles.put(plan, "data.csv", new byte[] {1}, false);
        ArchiveFiles.put(plan, "data.csv", new byte[] {1}, false);
        assertThrows(IllegalArgumentException.class,
                () -> ArchiveFiles.put(plan, "data.csv", new byte[] {2}, false));
        for (String invalid : new String[] {"../outside", "/tmp/file", "files/../../outside", "C:/file", "a\\b"}) {
            assertThrows(IllegalArgumentException.class, () -> ArchiveFiles.entryName(invalid));
        }
    }
}
