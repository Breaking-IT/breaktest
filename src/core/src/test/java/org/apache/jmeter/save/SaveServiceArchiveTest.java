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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jorphan.collections.HashTree;
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

        HashTree loaded = SaveService.loadTree(file.toFile());

        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadTreeStillReadsLegacyXmlJmx() throws Exception {
        Path file = tempDir.resolve("legacy.jmx");
        Files.write(file, savedTestPlanXml());

        HashTree loaded = SaveService.loadTree(file.toFile());

        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
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
