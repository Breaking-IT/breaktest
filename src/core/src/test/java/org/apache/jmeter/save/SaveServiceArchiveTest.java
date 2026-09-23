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

import org.apache.jmeter.JMeter;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaveServiceArchiveTest extends JMeterTestCase implements JMeterSerialTest {

    @TempDir
    Path tempDir;

    @Test
    void atomicSavePreservesPermissionsAndNewFilesRespectUmask() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileAttributeView(tempDir,
                java.nio.file.attribute.PosixFileAttributeView.class) != null);
        Path target = tempDir.resolve("permissions.jmx");
        Files.writeString(target, "old plan");
        for (String mode : List.of("rw-r--r--", "rw-rw----", "rw-------")) {
            var permissions = java.nio.file.attribute.PosixFilePermissions.fromString(mode);
            Files.setPosixFilePermissions(target, permissions);
            SaveService.saveTreeToFile(new HashTree(), target);
            assertEquals(permissions, Files.getPosixFilePermissions(target));
        }
        Path ordinary = tempDir.resolve("ordinary");
        Files.createFile(ordinary);
        Path fresh = tempDir.resolve("fresh.jmx");
        SaveService.saveTreeToFile(new HashTree(), fresh);
        assertEquals(Files.getPosixFilePermissions(ordinary), Files.getPosixFilePermissions(fresh));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void guiClassAvailabilityIsOnlyRequiredForGuiLoads(boolean nonGui) throws Exception {
        Path file = tempDir.resolve("runtime-without-gui.jmx");
        Files.writeString(file, """
                <jmeterTestPlan version="1.2" properties="5.0">
                  <hashTree>
                    <TestPlan guiclass="unavailable.plugin.Editor" testclass="TestPlan" testname="Runnable"/>
                    <hashTree/>
                  </hashTree>
                </jmeterTestPlan>
                """);
        String previous = System.getProperty(JMeter.JMETER_NON_GUI);
        try {
            System.setProperty(JMeter.JMETER_NON_GUI, Boolean.toString(nonGui));
            Object element = SaveService.loadTree(file.toFile()).getArray()[0];
            assertEquals(nonGui, element instanceof org.apache.jmeter.testelement.TestPlan);
            assertEquals(!nonGui, element instanceof org.apache.jmeter.testelement.MissingTestElement);
            assertEquals("unavailable.plugin.Editor", ((TestElement) element).getPropertyAsString(nonGui
                    ? TestElement.GUI_CLASS : org.apache.jmeter.testelement.MissingTestElement.MISSING_GUI_CLASS));
        } finally {
            restoreNonGuiProperty(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void authoringAttachmentsAreSkippedButRuntimeReferencesSurvive(boolean nonGui) throws Exception {
        var plan = new org.apache.jmeter.testelement.TestPlan();
        plan.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        String har = "har/authoring-" + nonGui + ".har";
        String rules = "correlation/rules-" + nonGui + ".json";
        // Runtime inputs must not be filtered by an authoring-looking path prefix.
        String runtime = "har/runtime-" + nonGui + ".csv";
        plan.setProperty(JmxArchiveEntryStore.HAR_FILENAME_PROPERTY, har);
        plan.setProperty(JmxArchiveEntryStore.HAR_MD5_PROPERTY, "test");
        plan.setProperty(JmxArchiveEntryStore.CORRELATION_RULES_FILENAME_PROPERTY, rules);
        plan.setProperty(JmxArchiveEntryStore.CORRELATION_RULES_CHECKSUM_PROPERTY, "test");
        plan.setProperty(JmxArchiveEntryStore.CSV_ENTRY_PROPERTY, runtime);
        plan.setProperty(JmxArchiveEntryStore.CSV_CHECKSUM_PROPERTY, "test");
        byte[] content = "attachment".getBytes(StandardCharsets.UTF_8);
        for (String entry : List.of(har, rules, runtime)) {
            JmxArchiveEntryStore.register(entry, "test", content);
        }
        Path file = tempDir.resolve("attachments.jmx");
        HashTree tree = new HashTree(plan);
        ThreadGroup recordingOwner = new ThreadGroup();
        recordingOwner.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        recordingOwner.setProperty(JmxArchiveEntryStore.HAR_FILENAME_PROPERTY, runtime);
        recordingOwner.setProperty(JmxArchiveEntryStore.HAR_MD5_PROPERTY, "test");
        tree.getTree(plan).add(recordingOwner);
        Files.write(file, saveTree(tree));
        for (String entry : List.of(har, rules, runtime)) {
            JmxArchiveEntryStore.register(entry, "test", new byte[0]);
        }
        String previous = System.getProperty(JMeter.JMETER_NON_GUI);
        try {
            System.setProperty(JMeter.JMETER_NON_GUI, Boolean.toString(nonGui));
            SaveService.loadTree(file.toFile());
        } finally {
            restoreNonGuiProperty(previous);
        }
        for (String entry : List.of(har, rules)) {
            assertArrayEquals(nonGui ? new byte[0] : content,
                    JmxArchiveEntryStore.find(entry, "test").orElseThrow());
        }
        assertArrayEquals(content, JmxArchiveEntryStore.find(runtime, "test").orElseThrow());
    }

    private static void restoreNonGuiProperty(String previous) {
        if (previous == null) {
            System.clearProperty(JMeter.JMETER_NON_GUI);
        } else {
            System.setProperty(JMeter.JMETER_NON_GUI, previous);
        }
    }

    @Test
    void failedSerializationPreservesExistingFileAndRemovesTemporaryFile() throws Exception {
        Path target = tempDir.resolve("existing.jmx");
        byte[] original = "previous saved plan".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);
        var broken = new org.apache.jmeter.testelement.TestPlan() {
            @Override
            public Object clone() {
                throw new NoClassDefFoundError("Simulated unavailable application class");
            }
        };
        HashTree tree = new HashTree();
        tree.add(broken);
        assertThrows(NoClassDefFoundError.class, () -> SaveService.saveTreeToFile(tree, target));
        assertArrayEquals(original, Files.readAllBytes(target));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of(target), files.toList());
        }
        SaveService.saveTreeToFile(new HashTree(), target);
        try (var zip = new java.util.zip.ZipFile(target.toFile())) {
            assertNotNull(zip.getEntry(SaveService.TEST_PLAN_ZIP_ENTRY));
        }
    }

    @Test
    void loadingSharedFilesDecompressesEachEntryOnlyOnce() throws Exception {
        var plan = new org.apache.jmeter.testelement.TestPlan();
        plan.setProperty("TestElement.gui_class", "org.apache.jmeter.control.gui.TestPlanGui");
        byte[] content = "name\nAlice\n".getBytes(StandardCharsets.UTF_8);
        ArchiveFiles.put(plan, "once.csv", content, false);
        HashTree tree = new HashTree();
        tree.add(plan);
        Path path = tempDir.resolve("read-once.jmx");
        try (var output = Files.newOutputStream(path)) {
            SaveService.saveTree(tree, output);
        }
        var reads = new java.util.HashMap<String, Integer>();
        try (var zip = new java.util.zip.ZipFile(path.toFile()) {
            @Override
            public java.io.InputStream getInputStream(ZipEntry entry) throws IOException {
                reads.merge(entry.getName(), 1, Integer::sum);
                return super.getInputStream(entry);
            }
        }) {
            SaveService.loadTreeFromZip(path.toFile(), zip);
        }
        assertEquals(java.util.Map.of(SaveService.TEST_PLAN_ZIP_ENTRY, 1, "files/once.csv", 1), reads);
        assertArrayEquals(content, JmxArchiveEntryStore.find("files/once.csv", ArchiveFiles.checksum(content))
                .orElseThrow());
    }

    @Test
    void saveTreeWritesArchiveInBlocksWithoutClosingCallerStream() throws Exception {
        byte[] content = new byte[1024 * 1024];
        new java.util.Random(42).nextBytes(content);
        var plan = new org.apache.jmeter.testelement.TestPlan();
        ArchiveFiles.put(plan, "bulk-save.bin", content, false);
        HashTree tree = new HashTree();
        tree.add(plan);
        var output = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(int value) {
                throw new AssertionError("Archive data must be forwarded in blocks");
            }

            @Override
            public void close() {
                throw new AssertionError("The caller owns the output stream");
            }
        };
        SaveService.saveTree(tree, output);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(SaveService.TEST_PLAN_ZIP_ENTRY, zip.getNextEntry().getName());
            zip.closeEntry();
            assertEquals("files/bulk-save.bin", zip.getNextEntry().getName());
            assertArrayEquals(content, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }

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
    void saveTreeConvertsRuntimeFunctionPropertiesToPortableStrings() throws Exception {
        ThreadGroup threadGroup = new ThreadGroup();
        FunctionProperty condition = new FunctionProperty(
                "IfController.condition", new CompoundVariable("${getAccessToken}"));
        threadGroup.setProperty(condition);
        CollectionProperty conditions = new CollectionProperty();
        conditions.setName("IfController.conditions");
        conditions.addProperty(new FunctionProperty(
                "IfController.condition.operand1", new CompoundVariable("${getAccessToken}")));
        threadGroup.setProperty(conditions);
        ListedHashTree tree = new ListedHashTree();
        tree.add(threadGroup);

        byte[] archive = saveTree(tree);
        String xml = new String(readEntry(archive, SaveService.TEST_PLAN_ZIP_ENTRY).orElseThrow(),
                StandardCharsets.UTF_8);

        assertFalse(xml.contains(FunctionProperty.class.getName()));
        assertTrue(xml.contains("<stringProp name=\"IfController.condition\">${getAccessToken}</stringProp>"));
        assertTrue(xml.contains(
                "<stringProp name=\"IfController.condition.operand1\">${getAccessToken}</stringProp>"));
        assertTrue(threadGroup.getProperty("IfController.condition") instanceof FunctionProperty,
                "saving must not mutate the live test tree");
    }

    @Test
    void saveTreePersistsFunctionPropertyOverrideInsteadOfStaleExpression() throws Exception {
        ThreadGroup threadGroup = new ThreadGroup();
        FunctionProperty condition = new FunctionProperty(
                "IfController.condition", new CompoundVariable("${legacyCondition}"));
        condition.setRunningVersion(true);
        condition.setObjectValue("");
        threadGroup.setProperty(condition);
        ListedHashTree tree = new ListedHashTree();
        tree.add(threadGroup);

        String xml = new String(readEntry(saveTree(tree), SaveService.TEST_PLAN_ZIP_ENTRY).orElseThrow(),
                StandardCharsets.UTF_8);

        assertFalse(xml.contains(FunctionProperty.class.getName()));
        assertTrue(xml.contains("<stringProp name=\"IfController.condition\"/>"));
    }

    @Test
    void saveTreeRejectsFunctionPropertyThatHasLostItsExpression() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setProperty(new FunctionProperty("IfController.condition", null));
        ListedHashTree tree = new ListedHashTree();
        tree.add(threadGroup);

        IOException error = assertThrows(IOException.class, () -> saveTree(tree));

        assertTrue(error.getMessage().contains("IfController.condition"));
        assertTrue(error.getMessage().contains("lost its original expression"));
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
    void customCorrelationRulesSurviveArchiveSaveLoadAndResave() throws Exception {
        String entryName = "correlations/custom-predefined-rules.json";
        String checksum = "rules-checksum";
        byte[] rules = "{\"format\":\"breaktest-predefined-correlations-v1\",\"groups\":[]}".getBytes(
                StandardCharsets.UTF_8);
        JmxArchiveEntryStore.register(entryName, checksum, rules);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        threadGroup.setProperty(JmxArchiveEntryStore.CORRELATION_RULES_FILENAME_PROPERTY, entryName);
        threadGroup.setProperty(JmxArchiveEntryStore.CORRELATION_RULES_CHECKSUM_PROPERTY, checksum);
        HashTree tree = new ListedHashTree();
        tree.add(threadGroup);

        Path input = tempDir.resolve("custom-correlations.jmx");
        Files.write(input, saveTree(tree));
        byte[] resaved = saveTree(SaveService.loadTree(input.toFile()));

        assertArrayEquals(rules, readEntry(resaved, entryName).orElseThrow());
    }

    @Test
    void recordingDiskFallbackRejectsMismatchedAndIncompleteBundles() throws Exception {
        byte[] har = ("{\"log\":{\"entries\":[{\"request\":{\"method\":\"GET\","
                + "\"url\":\"https://example.invalid/api\"},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"body\"}}}]}}")
                        .getBytes(StandardCharsets.UTF_8);
        var recording = RecordedExchangeStore.fromHar(har, "source.har");
        Path file = tempDir.resolve("recording.jmx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(recording.manifestEntryName()));
            zip.write(recording.entries().get(recording.manifestEntryName()));
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> SaveService.readRecordingBundle(
                file.toFile(), recording.manifestEntryName(), "wrong-checksum"));
        assertThrows(IOException.class, () -> SaveService.readRecordingBundle(
                file.toFile(), recording.manifestEntryName(), recording.checksum()));
        assertTrue(SaveService.readRecordingBundle(
                file.toFile(), "recordings/manifests/absent.json", "").isEmpty());
        assertTrue(SaveService.readRecordingBundle(
                tempDir.resolve("absent.jmx").toFile(), recording.manifestEntryName(), recording.checksum()).isEmpty());
        Path legacy = tempDir.resolve("legacy.jmx");
        Files.writeString(legacy, "<?xml version=\"1.0\"?><jmeterTestPlan/>");
        assertTrue(SaveService.readRecordingBundle(
                legacy.toFile(), recording.manifestEntryName(), recording.checksum()).isEmpty());
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingRecordingOnlyWarnsInGuiWhileRuntimeFilesStillLoad(boolean nonGui) throws Exception {
        var recording = RecordedExchangeStore.fromHar(
                "{\"log\":{\"entries\":[]}}".getBytes(StandardCharsets.UTF_8), "source.har");
        JmxArchiveEntryStore.registerBundle(
                recording.manifestEntryName(), recording.checksum(), recording.entries());
        HashTree tree = treeWithRecordingReference(recording);
        var plan = new org.apache.jmeter.testelement.TestPlan();
        plan.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        byte[] content = "name\nAlice\n".getBytes(StandardCharsets.UTF_8);
        String filename = "nongui-" + nonGui + ".csv";
        ArchiveFiles.put(plan, filename, content, false);
        tree.add(plan);
        byte[] saved = saveTree(tree);
        Path file = tempDir.resolve("missing-recording.jmx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String entry : List.of(SaveService.TEST_PLAN_ZIP_ENTRY, "files/" + filename)) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(readEntry(saved, entry).orElseThrow());
                zip.closeEntry();
            }
        }
        JmxArchiveEntryStore.register("files/" + filename, ArchiveFiles.checksum(content), new byte[0]);
        String previous = System.getProperty(JMeter.JMETER_NON_GUI);
        List<String> messages = new ArrayList<>();
        try {
            System.setProperty(JMeter.JMETER_NON_GUI, Boolean.toString(nonGui));
            loadTreeCapturingMessages(file, messages);
        } finally {
            if (previous == null) {
                System.clearProperty(JMeter.JMETER_NON_GUI);
            } else {
                System.setProperty(JMeter.JMETER_NON_GUI, previous);
            }
        }
        assertEquals(!nonGui, messages.stream()
                .anyMatch(message -> message.contains("Unable to cache linked archive attachment")));
        assertArrayEquals(content, JmxArchiveEntryStore.find(
                "files/" + filename, ArchiveFiles.checksum(content)).orElseThrow());
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
