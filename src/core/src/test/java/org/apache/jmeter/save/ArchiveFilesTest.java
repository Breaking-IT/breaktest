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
