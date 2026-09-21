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

import java.io.IOException;
import java.nio.file.Path;

import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.MapProperty;

/**
 * Resolves shared CSV files by name. Per-element checksums are a compatibility
 * fallback only for older plans that do not have a shared-file index.
 */
final class CsvArchiveSupport {

    private CsvArchiveSupport() {
    }

    static String checksum(byte[] content) {
        return ArchiveFiles.checksum(content);
    }

    static String entryName(String filename) {
        return ArchiveFiles.importEntryName(filename);
    }

    private static boolean usesPlanIndex(String entry, TestPlan plan) {
        return entry.startsWith("files/") && plan != null
                && plan.getProperty(ArchiveFiles.PROPERTY) instanceof MapProperty;
    }

    static byte[] read(String entry, String checksum) throws IOException {
        if (usesPlanIndex(entry, ArchiveFiles.currentPlan())) {
            return ArchiveFiles.read(entry);
        }
        return JmxArchiveEntryStore.find(entry, checksum)
                .orElseThrow(() -> new IOException("CSV is not available in the JMX archive: " + entry));
    }

    /**
     * Whether the archive holds no entry of this name, as opposed to holding one whose content
     * cannot be read. Only a genuinely absent entry may be created from the editor: offering to
     * create an unreadable one would let a later save replace real content with an empty file.
     */
    static boolean isAbsent(String entry, String checksum) {
        TestPlan plan = ArchiveFiles.currentPlan();
        if (usesPlanIndex(entry, plan)) {
            try {
                // Match the name normalization ArchiveFiles.read applies before its own lookup.
                return !ArchiveFiles.references(plan).containsKey(ArchiveFiles.entryName(entry));
            } catch (IllegalArgumentException ex) {
                // An unusable entry name is an error to report, never a file to create.
                return false;
            }
        }
        return JmxArchiveEntryStore.find(entry, checksum).isEmpty();
    }

    static Path materialize(String entry, String checksum) throws IOException {
        if (usesPlanIndex(entry, ArchiveFiles.currentPlan())) {
            return ArchiveFiles.resolve(entry);
        }
        return ArchiveFiles.materialize(entry, checksum);
    }
}
