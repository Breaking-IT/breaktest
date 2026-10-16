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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ArchiveCleanupTest extends JMeterTestCase {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(RecordingStorageMode.class)
    void cleanupRepairsStaleReferencesAndKeepsLinksFromEveryOwner(RecordingStorageMode mode) throws Exception {
        var archive = RecordedExchangeStore.fromHar("""
                {"log":{"entries":[
                {"request":{"url":"https://example.invalid/first"},"response":{"content":{"text":"first"}}},
                {"request":{"url":"https://example.invalid/second"},"response":{"content":{"text":"second"}}},
                {"request":{"url":"https://example.invalid/orphan"},"response":{"content":{"text":"orphan"}}}
                ]}}
                """.getBytes(StandardCharsets.UTF_8), "test.har");
        JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), archive.checksum(), archive.entries());
        TestPlan stale = new TestPlan();
        stale.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        stale.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, "stale-checksum");
        stale.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, archive.exchangeIds().get(0));
        TestPlan current = new TestPlan();
        current.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        current.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());
        current.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, archive.exchangeIds().get(1));
        HashTree tree = new org.apache.jorphan.collections.ListedHashTree();
        tree.add(stale);
        tree.add(current);
        var prepared = ArchiveCleanup.prepare(tree, mode, true);
        assertEquals("stale-checksum", stale.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY));
        assertEquals(mode == RecordingStorageMode.NONE ? 0 : 2, prepared.retainedExchanges());
        prepared.apply();
        assertEquals(current.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY),
                stale.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY));
        if (mode == RecordingStorageMode.NONE) {
            assertEquals("", stale.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
            assertEquals("", current.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        } else {
            var bundle = JmxArchiveEntryStore.findBundle(archive.manifestEntryName(),
                    stale.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY)).orElseThrow();
            var cleaned = RecordedExchangeStore.cleanArchive(archive.manifestEntryName(), bundle, RecordingStorageMode.ALL, null);
            assertTrue(cleaned.resolveExchange(archive.exchangeIds().get(0)).isPresent());
            assertTrue(cleaned.resolveExchange(archive.exchangeIds().get(1)).isPresent());
            assertFalse(cleaned.resolveExchange(archive.exchangeIds().get(2)).isPresent());
        }
    }

    @Test
    void staleReferenceToMissingExchangeRemainsAConflict() throws Exception {
        var archive = RecordedExchangeStore.fromHar("""
                {"log":{"entries":[{"request":{"url":"https://example.invalid/api"},
                "response":{"content":{"text":"recorded-response"}}}]}}
                """.getBytes(StandardCharsets.UTF_8), "test.har");
        JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), archive.checksum(), archive.entries());
        TestPlan stale = new TestPlan();
        stale.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        stale.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, "stale-checksum");
        stale.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, "exchange-from-older-version");
        TestPlan current = new TestPlan();
        current.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        current.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());
        current.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, archive.exchangeIds().get(0));
        HashTree tree = new HashTree();
        tree.add(stale);
        tree.add(current);
        assertThrows(java.io.IOException.class, () -> ArchiveCleanup.prepare(tree, RecordingStorageMode.ALL, true));
        assertEquals("exchange-from-older-version", stale.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
    }

    @Test
    void differentAvailableVersionsRemainAConflict() throws Exception {
        var archive = RecordedExchangeStore.fromHar("""
                {"log":{"entries":[{"request":{"url":"https://example.invalid/api"},
                "response":{"content":{"text":"recorded-response"}}}]}}
                """.getBytes(StandardCharsets.UTF_8), "test.har");
        var reduced = RecordedExchangeStore.cleanArchive(archive.manifestEntryName(), archive.entries(),
                RecordingStorageMode.NONE, null);
        JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), archive.checksum(), archive.entries());
        JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), reduced.checksum(), reduced.entries());
        HashTree tree = new HashTree();
        for (String checksum : new String[] {archive.checksum(), reduced.checksum()}) {
            TestPlan owner = new TestPlan();
            owner.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
            owner.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, checksum);
            tree.add(owner);
        }
        assertThrows(java.io.IOException.class, () -> ArchiveCleanup.prepare(tree, RecordingStorageMode.ALL, true));
    }

    @Test
    void unlinkedRecordingIsNotAssumedToBeOrphaned() throws Exception {
        var archive = RecordedExchangeStore.fromHar("""
                {"log":{"entries":[{"request":{"url":"https://example.invalid/api"},
                "response":{"content":{"text":"unconverted recording"}}}]}}
                """.getBytes(StandardCharsets.UTF_8), "test.har");
        JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), archive.checksum(), archive.entries());
        TestPlan owner = new TestPlan();
        owner.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        owner.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());
        HashTree tree = new HashTree();
        tree.add(owner);
        var cleanup = ArchiveCleanup.prepare(tree, RecordingStorageMode.ALL, true);
        assertEquals(1, cleanup.unlinkedRecordings());
        assertEquals(1, cleanup.retainedExchanges());
        cleanup.apply();
        assertEquals(archive.manifestEntryName(), owner.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        var explicitRemoval = ArchiveCleanup.prepare(tree, RecordingStorageMode.NONE, true);
        assertEquals(0, explicitRemoval.retainedExchanges());
        explicitRemoval.apply();
        assertEquals("", owner.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
    }

    @Test
    void orphanCleanupRespectsInheritedManifestReferences() throws Exception {
        var archive = RecordedExchangeStore.fromHar("""
                {"log":{"entries":[
                {"request":{"url":"https://example.invalid/keep"},"response":{"content":{"text":"keep"}}},
                {"request":{"url":"https://example.invalid/remove"},"response":{"content":{"text":"remove"}}}
                ]}}
                """.getBytes(StandardCharsets.UTF_8), "test.har");
        JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), archive.checksum(), archive.entries());
        TestPlan container = new TestPlan();
        container.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        container.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());
        org.apache.jmeter.config.Arguments sampler = new org.apache.jmeter.config.Arguments();
        sampler.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, archive.exchangeIds().get(0));
        HashTree tree = new HashTree();
        tree.add(container).add(sampler);
        ArchiveCleanup.prepare(tree, RecordingStorageMode.ALL, true).apply();
        assertEquals(archive.exchangeIds().get(0), sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        String checksum = container.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY);
        var bundle = JmxArchiveEntryStore.findBundle(archive.manifestEntryName(), checksum).orElseThrow();
        var cleaned = RecordedExchangeStore.cleanArchive(archive.manifestEntryName(), bundle, RecordingStorageMode.ALL, null);
        assertEquals(1, cleaned.exchangeCount());
        assertTrue(cleaned.resolveExchange(archive.exchangeIds().get(0)).isPresent());
        assertFalse(cleaned.resolveExchange(archive.exchangeIds().get(1)).isPresent());
    }

    @Test
    void previewDoesNotMutateAndRemovingRecordingsPreservesSharedFilesOnSave() throws Exception {
        var archive = RecordedExchangeStore.fromHar("""
                {"log":{"entries":[{"request":{"url":"https://example.invalid/api"},
                "response":{"content":{"text":"recorded-response"}}}]}}
                """.getBytes(StandardCharsets.UTF_8), "test.har");
        JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), archive.checksum(), archive.entries());
        TestPlan plan = new TestPlan();
        plan.setProperty("TestElement.gui_class", "org.apache.jmeter.control.gui.TestPlanGui");
        plan.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        plan.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());
        plan.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, archive.exchangeIds().get(0));
        byte[] shared = {1, 2, 3};
        ArchiveFiles.put(plan, "upload.bin", shared, false);
        HashTree tree = new HashTree();
        tree.add(plan);
        var prepared = ArchiveCleanup.prepare(tree, RecordingStorageMode.NONE, true);
        assertEquals(archive.manifestEntryName(), plan.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        assertTrue(prepared.bytesRemoved() > 0);
        assertTrue(prepared.removedEntries() > 0);
        prepared.apply();
        assertEquals("", plan.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        assertEquals("", plan.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        Path file = directory.resolve("cleaned.jmx");
        try (OutputStream output = Files.newOutputStream(file)) {
            SaveService.saveTree(tree, output);
        }
        assertArrayEquals(shared, SaveService.readArchiveEntry(file.toFile(), "files/upload.bin").orElseThrow());
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().startsWith("recordings/")));
        }
        TestPlan loaded = (TestPlan) SaveService.loadTree(file.toFile()).getArray()[0];
        assertEquals("", loaded.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        assertTrue(ArchiveFiles.references(loaded).containsKey("files/upload.bin"));
    }
}
