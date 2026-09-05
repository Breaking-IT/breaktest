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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvArchiveSupportTest extends JMeterTestCase implements JMeterSerialTest {
    @TempDir
    Path directory;

    @AfterEach
    void closeFiles() throws IOException {
        FileServer.getFileServer().closeFiles();
    }

    @Test
    void archiveSurvivesMovingWithoutOriginalAndSupportsPreviewAndExecution() throws Exception {
        byte[] content = "name,value\r\nAlice,1\r\nBob,2\r\n".getBytes(StandardCharsets.UTF_8);
        Path source = directory.resolve("source.csv");
        Files.write(source, content);
        CSVDataSet csv = new CSVDataSet();
        csv.setFilename(source.toString());
        csv.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        csv.setProperty("filename", source.toString());
        csv.setProperty("delimiter", ",");
        csv.storeArchivedCsv(csv.readCsvContent());
        assertTrue(csv.isUseCsvFromArchive());
        assertEquals("files/source.csv", csv.getCsvArchiveEntry());
        Path archive = directory.resolve("portable.jmx");
        HashTree tree = new HashTree();
        tree.add(csv);
        try (OutputStream output = Files.newOutputStream(archive)) {
            SaveService.saveTree(tree, output);
        }
        assertArrayEquals(content, SaveService.readArchiveEntry(archive.toFile(), csv.getCsvArchiveEntry()).orElseThrow());
        Files.delete(source);
        Path moved = directory.resolve("moved");
        Files.createDirectory(moved);
        archive = Files.move(archive, moved.resolve("portable.jmx"));
        // Replace the cached bytes to prove loading restores the archive attachment.
        JmxArchiveEntryStore.register(csv.getCsvArchiveEntry(), csv.getCsvArchiveChecksum(), new byte[0]);
        CSVDataSet loaded = (CSVDataSet) SaveService.loadTree(archive.toFile()).getArray()[0];
        TestBeanHelper.prepare(loaded);
        assertTrue(loaded.isUseCsvFromArchive());
        assertArrayEquals(content, loaded.readCsvContent());
        assertTrue(loaded.readFirstSample(1).contains("${name} = Alice"));
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        loaded.iterationStart(null);
        assertEquals("Alice", variables.get("name"));
        loaded.iterationStart(null);
        assertEquals("Bob", variables.get("name"));
    }

    @Test
    void editingArchiveLeavesExternalSourceAndOtherCopiesIntact() throws Exception {
        Path source = directory.resolve("data.csv");
        Files.writeString(source, "name\nAlice\n");
        CSVDataSet csv = new CSVDataSet();
        csv.setFilename(source.toString());
        assertFalse(csv.isUseCsvFromArchive());
        csv.storeArchivedCsv(csv.readCsvContent());
        String oldEntry = csv.getCsvArchiveEntry();
        String oldChecksum = csv.getCsvArchiveChecksum();
        CsvFileEditor editor = CsvFileEditor.fromBytes(Path.of(oldEntry), "UTF-8", csv.readCsvContent());
        csv.storeArchivedCsv(editor.encode(editor.getContent().replace("Alice", "Bob")));
        assertEquals(oldEntry, csv.getCsvArchiveEntry());
        assertEquals("name\nBob\n", new String(csv.readCsvContent(), StandardCharsets.UTF_8));
        assertEquals("name\nAlice\n", Files.readString(source));
        assertEquals("name\nAlice\n", new String(CsvArchiveSupport.read(oldEntry, oldChecksum), StandardCharsets.UTF_8));
        csv.setUseCsvFromArchive(false);
        assertEquals("name\nAlice\n", new String(csv.readCsvContent(), StandardCharsets.UTF_8));
    }

    @Test
    void conflictingFilenamesAreRejectedButIdenticalContentsCanBeReused() throws Exception {
        CSVDataSet first = new CSVDataSet();
        first.setFilename("first/data.csv");
        byte[] content = "name\nAlice\n".getBytes(StandardCharsets.UTF_8);
        first.storeArchivedCsv(content);
        String entry = CsvArchiveSupport.entryName("second/data.csv");
        assertEquals("files/data.csv", entry);
        CsvArchiveSupport.validateFilename(entry, CsvArchiveSupport.checksum(content), List.of(first));
        byte[] different = "name\nBob\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> CsvArchiveSupport.validateFilename(entry, CsvArchiveSupport.checksum(different), List.of(first)));
        CSVDataSet second = new CSVDataSet();
        second.setFilename("second/data.csv");
        second.storeArchivedCsv(different);
        HashTree tree = new HashTree();
        tree.add(first);
        tree.add(second);
        assertThrows(IllegalArgumentException.class, () -> SaveService.saveTree(tree, new ByteArrayOutputStream()));
    }

    @Test
    void unavailableArchiveDoesNotFallBackToExternalCsv() throws Exception {
        Path source = directory.resolve("external.csv");
        Files.writeString(source, "name\nAlice\n");
        CSVDataSet csv = new CSVDataSet();
        csv.setFilename(source.toString());
        csv.setUseCsvFromArchive(true);
        csv.setCsvArchiveEntry("csv/missing/data.csv");
        assertThrows(IOException.class, csv::readCsvContent);
        assertThrows(IOException.class, () -> csv.readFirstSample(1));
    }
}
