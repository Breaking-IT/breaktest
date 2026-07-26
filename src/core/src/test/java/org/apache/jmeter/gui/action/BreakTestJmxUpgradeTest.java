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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import javax.swing.JOptionPane;

import org.apache.jmeter.save.SaveService;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BreakTestJmxUpgradeTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsLegacyXmlAndNativeBreakTestArchives() throws Exception {
        Path legacy = tempDir.resolve("legacy.jmx");
        Files.write(legacy, legacyXml());
        Path nativeJmx = tempDir.resolve("native.jmx");
        Files.write(nativeJmx, nativeArchive());

        assertFalse(BreakTestJmxUpgrade.isNativeBreakTestJmx(legacy));
        assertTrue(BreakTestJmxUpgrade.isNativeBreakTestJmx(nativeJmx));
    }

    @Test
    void onlyAnExplicitYesDecisionAllowsConversion() {
        assertTrue(BreakTestJmxUpgrade.isUpgradeConfirmed(JOptionPane.YES_OPTION));
        assertFalse(BreakTestJmxUpgrade.isUpgradeConfirmed(JOptionPane.NO_OPTION));
        assertFalse(BreakTestJmxUpgrade.isUpgradeConfirmed(JOptionPane.CLOSED_OPTION));
    }

    @Test
    void decliningConversionLeavesAllFilesUntouched() throws Exception {
        Path source = tempDir.resolve("plan.jmx");
        byte[] original = legacyXml();
        Files.write(source, original);

        assertNull(BreakTestJmxUpgrade.upgradeIfConfirmed(JOptionPane.NO_OPTION, source, new HashTree()));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertFalse(Files.exists(tempDir.resolve("plan.jmeter-backup-001.jmx")));
    }

    @Test
    void upgradeKeepsExactJMeterCompatibleBackupAndReplacesOriginalWithNativeArchive() throws Exception {
        Path source = tempDir.resolve("plan.jmx");
        byte[] original = legacyXml();
        Files.write(source, original);

        Path backup = BreakTestJmxUpgrade.upgrade(source, new HashTree());

        assertEquals(tempDir.resolve("plan.jmeter-backup-001.jmx"), backup);
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertFalse(BreakTestJmxUpgrade.isNativeBreakTestJmx(backup));
        assertTrue(BreakTestJmxUpgrade.isNativeBreakTestJmx(source));
        assertTrue(SaveService.loadTree(backup.toFile()).isEmpty());
        assertTrue(SaveService.loadTree(source.toFile()).isEmpty());
    }

    @Test
    void backupNamesAvoidExistingBackups() throws Exception {
        Path source = tempDir.resolve("plan.jmx");
        Files.write(source, legacyXml());
        Files.writeString(tempDir.resolve("plan.jmeter-backup-001.jmx"), "first");

        Path backup = BreakTestJmxUpgrade.upgrade(source, new HashTree());

        assertEquals(tempDir.resolve("plan.jmeter-backup-002.jmx"), backup);
        assertEquals("first", Files.readString(tempDir.resolve("plan.jmeter-backup-001.jmx")));
    }

    @Test
    void failedConversionNeverOverwritesOriginal() throws Exception {
        Path source = tempDir.resolve("plan.jmx");
        byte[] original = legacyXml();
        Files.write(source, original);

        assertThrows(IOException.class, () -> BreakTestJmxUpgrade.upgrade(source, output -> {
            throw new IOException("simulated archive write failure");
        }));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(original, Files.readAllBytes(tempDir.resolve("plan.jmeter-backup-001.jmx")));
    }

    private static byte[] nativeArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveTree(new HashTree(), output);
        return output.toByteArray();
    }

    private static byte[] legacyXml() throws IOException {
        try (ZipInputStream archive = new ZipInputStream(new java.io.ByteArrayInputStream(nativeArchive()))) {
            archive.getNextEntry();
            return archive.readAllBytes();
        }
    }
}
