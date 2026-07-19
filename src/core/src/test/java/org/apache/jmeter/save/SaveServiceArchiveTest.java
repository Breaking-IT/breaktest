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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveServiceArchiveTest extends JMeterTestCase {

    @TempDir
    Path tempDir;

    @Test
    void saveTreeCreatesZipWithMainTestPlan() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        SaveService.saveTree(new HashTree(), output);

        byte[] archive = output.toByteArray();
        assertEquals('P', archive[0]);
        assertEquals('K', archive[1]);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry = zip.getNextEntry();
            assertNotNull(entry);
            assertEquals(SaveService.TEST_PLAN_ZIP_ENTRY, entry.getName());
            assertEquals(ZipEntry.DEFLATED, entry.getMethod());
            assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("<jmeterTestPlan"));
            assertNull(zip.getNextEntry());
        }
    }

    @Test
    void loadTreeReadsCompressedJmxWhenMainTestPlanIsNotFirstEntry() throws Exception {
        byte[] testPlanXml = savedTestPlanXml();
        Path file = tempDir.resolve("compressed.jmx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write("Bundled project file".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(SaveService.TEST_PLAN_ZIP_ENTRY));
            zip.write(testPlanXml);
            zip.closeEntry();
        }

        List<String> messages = new ArrayList<>();
        HashTree loaded = loadTreeCapturingMessages(file, messages);

        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("Loading JMX archive: ")));
        assertFalse(messages.stream().anyMatch(message -> message.startsWith("Loading file: ")));
    }

    @Test
    void loadTreeStillReadsLegacyXmlJmx() throws Exception {
        Path file = tempDir.resolve("legacy.jmx");
        Files.write(file, savedTestPlanXml());

        List<String> messages = new ArrayList<>();
        HashTree loaded = loadTreeCapturingMessages(file, messages);

        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("Loading file: ")));
        assertFalse(messages.stream().anyMatch(message -> message.startsWith("Loading JMX archive: ")));
    }

    @Test
    void loadTreeRejectsZipWithoutMainTestPlan() throws Exception {
        Path file = tempDir.resolve("invalid.jmx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("other.jmx"));
            zip.write(savedTestPlanXml());
            zip.closeEntry();
        }

        IOException error = assertThrows(IOException.class, () -> SaveService.loadTree(file.toFile()));

        assertTrue(error.getMessage().contains(SaveService.TEST_PLAN_ZIP_ENTRY));
        assertFalse(error.getMessage().isBlank());
    }

    @Test
    void saveTreeEmbedsHarUntilItsLastThreadGroupReferenceIsRemoved() throws Exception {
        String entryName = "har/0123456789abcdef/recording.har";
        String checksum = "0123456789abcdef";
        byte[] har = "{\"log\":{\"entries\":[]}}".getBytes(StandardCharsets.UTF_8);
        JmxArchiveEntryStore.register(entryName, checksum, har);

        byte[] withTwoReferences = saveTree(treeWithHarReferences(2, entryName, checksum));
        assertArrayEquals(har, readEntry(withTwoReferences, entryName).orElseThrow());
        assertEquals(1, countEntries(withTwoReferences, entryName));

        byte[] withOneReference = saveTree(treeWithHarReferences(1, entryName, checksum));
        assertArrayEquals(har, readEntry(withOneReference, entryName).orElseThrow());

        byte[] withoutReferences = saveTree(new ListedHashTree());
        assertTrue(readEntry(withoutReferences, entryName).isEmpty());
    }

    @Test
    void loadTreeKeepsEmbeddedHarAvailableForTheNextSave() throws Exception {
        String entryName = "har/load-resave/recording.har";
        String checksum = "load-resave";
        byte[] har = "{\"log\":{\"entries\":[]}}".getBytes(StandardCharsets.UTF_8);
        HashTree originalTree = treeWithHarReferences(1, entryName, checksum);
        byte[] testPlanXml = readEntry(saveTree(originalTree), SaveService.TEST_PLAN_ZIP_ENTRY).orElseThrow();
        Path input = tempDir.resolve("embedded-har.jmx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry(SaveService.TEST_PLAN_ZIP_ENTRY));
            zip.write(testPlanXml);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(har);
            zip.closeEntry();
        }

        HashTree loaded = SaveService.loadTree(input.toFile());
        byte[] resaved = saveTree(loaded);

        assertArrayEquals(har, readEntry(resaved, entryName).orElseThrow());
    }

    @Test
    void nativeRecordingBundleSurvivesSaveLoadAndIsRemovedWithItsReference() throws Exception {
        byte[] har = ("{\"log\":{\"entries\":[{"
                + "\"request\":{\"method\":\"POST\",\"url\":\"https://example.invalid/api\","
                + "\"postData\":{\"text\":\"shared-body\"}},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"shared-body\"}}}]}}")
                        .getBytes(StandardCharsets.UTF_8);
        RecordedExchangeStore.Archive recording = RecordedExchangeStore.fromHar(har, "source.har");
        assertEquals(3, recording.entries().size());
        JmxArchiveEntryStore.registerBundle(
                recording.manifestEntryName(), recording.checksum(), recording.entries());

        byte[] saved = saveTree(treeWithRecordingReference(recording));
        for (var entry : recording.entries().entrySet()) {
            assertArrayEquals(entry.getValue(), readEntry(saved, entry.getKey()).orElseThrow());
        }

        Path input = tempDir.resolve("native-recording.jmx");
        Files.write(input, saved);
        byte[] resaved = saveTree(SaveService.loadTree(input.toFile()));
        for (var entry : recording.entries().entrySet()) {
            assertArrayEquals(entry.getValue(), readEntry(resaved, entry.getKey()).orElseThrow());
        }

        byte[] withoutReference = saveTree(new ListedHashTree());
        for (String entryName : recording.entries().keySet()) {
            assertTrue(readEntry(withoutReference, entryName).isEmpty());
        }
    }

    @Test
    void createsSafeUniqueHarEntryNames() {
        assertEquals(
                "har/abcdef1234/my_recording.har",
                JmxArchiveEntryStore.createHarEntryName("../my recording.har", "ABCDEF1234"));
        assertFalse(JmxArchiveEntryStore.isSafeEntryName("../recording.har"));
        assertFalse(JmxArchiveEntryStore.isSafeEntryName("/recording.har"));
    }

    private static HashTree treeWithHarReferences(int count, String entryName, String checksum) {
        ListedHashTree tree = new ListedHashTree();
        for (int i = 0; i < count; i++) {
            ThreadGroup threadGroup = new ThreadGroup();
            threadGroup.setName("Thread Group " + i);
            threadGroup.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
            threadGroup.setProperty(JmxArchiveEntryStore.HAR_FILENAME_PROPERTY, entryName);
            threadGroup.setProperty(JmxArchiveEntryStore.HAR_MD5_PROPERTY, checksum);
            tree.add(threadGroup);
        }
        return tree;
    }

    private static HashTree treeWithRecordingReference(RecordedExchangeStore.Archive recording) {
        ListedHashTree tree = new ListedHashTree();
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        threadGroup.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, recording.manifestEntryName());
        threadGroup.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, recording.checksum());
        tree.add(threadGroup);
        return tree;
    }

    private static byte[] saveTree(HashTree tree) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveTree(tree, output);
        return output.toByteArray();
    }

    private static Optional<byte[]> readEntry(byte[] archive, String entryName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return Optional.of(zip.readAllBytes());
                }
            }
        }
        return Optional.empty();
    }

    private static int countEntries(byte[] archive, String entryName) throws IOException {
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static HashTree loadTreeCapturingMessages(Path file, List<String> messages) throws IOException {
        AbstractAppender appender = new AbstractAppender(
                "test-save-service-load-messages", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(SaveService.class);
        logger.addAppender(appender);
        try {
            return SaveService.loadTree(file.toFile());
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    private static byte[] savedTestPlanXml() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveTree(new HashTree(), output);
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(output.toByteArray()))) {
            assertNotNull(zip.getNextEntry());
            return zip.readAllBytes();
        }
    }
}
