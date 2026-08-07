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

package org.apache.jmeter.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.jmeter.save.LoadedTreePostProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ClassScanCacheTest {

    @TempDir
    Path tempDir;

    private Path searchDir;
    private Path cacheFile;

    @BeforeEach
    public void setUp() throws Exception {
        Path props = Files.createTempFile(tempDir, "test", ".properties");
        JMeterUtils.loadJMeterProperties(props.toString());
        searchDir = Files.createDirectory(tempDir.resolve("ext"));
        cacheFile = tempDir.resolve("class-scan.cache");
        JMeterUtils.setProperty("search_paths", searchDir.toString());
        JMeterUtils.setProperty("classfinder.cache.file", cacheFile.toString());
        JMeterUtils.setJMeterHome(tempDir.toString());
        ClassScanCache.reset();
    }

    @AfterEach
    public void tearDown() {
        ClassScanCache.reset();
    }

    @Test
    public void scanResultIsPersistedAndReusedFromCacheFile() throws Exception {
        List<String> first = ClassScanCache.findClassesThatExtend(LoadedTreePostProcessor.class);
        assertTrue(cacheFile.toFile().isFile(), "cache file should be written after a scan");

        // Overwrite the cached entry with a marker value; if the next lookup returns the
        // marker, it was served from the cache file rather than a fresh scan
        Properties props = new Properties();
        try (var in = Files.newInputStream(cacheFile)) {
            props.load(in);
        }
        assertEquals(ClassScanCache.computeFingerprint(), props.getProperty("fingerprint"),
                "cache file should record the current search path fingerprint");
        props.setProperty("service." + LoadedTreePostProcessor.class.getName(), "cached.marker.Class");
        try (OutputStream out = Files.newOutputStream(cacheFile)) {
            props.store(out, null);
        }
        ClassScanCache.reset();

        List<String> cached = ClassScanCache.findClassesThatExtend(LoadedTreePostProcessor.class);
        assertEquals(List.of("cached.marker.Class"), cached);

        // Changing the search path contents must invalidate the cache and re-scan
        Files.write(searchDir.resolve("new-plugin.jar"), new byte[] {1, 2, 3});
        List<String> rescanned = ClassScanCache.findClassesThatExtend(LoadedTreePostProcessor.class);
        assertEquals(first, rescanned, "stale marker entry should be dropped after jars change");
    }

    @Test
    public void fingerprintChangesWhenSearchPathContentChanges() throws Exception {
        String before = ClassScanCache.computeFingerprint();
        Files.write(searchDir.resolve("added.jar"), new byte[] {1});
        String after = ClassScanCache.computeFingerprint();
        assertNotEquals(before, after, "fingerprint should change when a jar is added");
    }

    @Test
    public void cacheCanBeDisabled() throws Exception {
        JMeterUtils.setProperty("classfinder.cache", "false");
        try {
            ClassScanCache.findClassesThatExtend(LoadedTreePostProcessor.class);
            assertTrue(!cacheFile.toFile().exists(), "no cache file should be written when disabled");
        } finally {
            JMeterUtils.setProperty("classfinder.cache", "true");
        }
    }
}
