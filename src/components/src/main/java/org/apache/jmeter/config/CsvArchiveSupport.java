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

    static byte[] read(String entry, String checksum) throws IOException {
        if (entry.startsWith("files/") && ArchiveFiles.currentPlan() != null
                && ArchiveFiles.currentPlan().getProperty(ArchiveFiles.PROPERTY)
                        instanceof MapProperty) {
            return ArchiveFiles.read(entry);
        }
        return JmxArchiveEntryStore.find(entry, checksum)
                .orElseThrow(() -> new IOException("CSV is not available in the JMX archive: " + entry));
    }

    static Path materialize(String entry, String checksum) throws IOException {
        if (entry.startsWith("files/") && ArchiveFiles.currentPlan() != null
                && ArchiveFiles.currentPlan().getProperty(ArchiveFiles.PROPERTY)
                        instanceof MapProperty) {
            return ArchiveFiles.resolve(entry);
        }
        return ArchiveFiles.materialize(entry, checksum);
    }
}
