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

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArchiveBrowserTest {
    @Test
    void showsOnlyImmediateChildrenAndKeepsFullPathsForActions() {
        Map<String, Object> files = Map.of(
                "testplan.jmx", 100L,
                "files/data.csv", 20L,
                "files/nested/input.csv", 30L,
                "versions/old.jmx", 40L,
                "versions/blobs/abc", 50L,
                "versions/blobs/def", 60L);
        assertEquals(Set.of("testplan.jmx", "files/", "versions/"),
                ArchiveBrowser.children(files, "").keySet());
        assertEquals(Map.of("files/data.csv", 20L, "files/nested/", "Folder"),
                ArchiveBrowser.children(files, "files/"));
        assertEquals(Map.of("versions/old.jmx", 40L, "versions/blobs/", "Folder"),
                ArchiveBrowser.children(files, "versions/"));
        assertEquals(Map.of("versions/blobs/abc", 50L, "versions/blobs/def", 60L),
                ArchiveBrowser.children(files, "versions/blobs/"));
        assertTrue(ArchiveBrowser.children(files, "absent/").isEmpty());
    }

    @Test
    void handlesExplicitDirectoryEntriesAndUnavailableFiles() {
        Map<String, Object> files = Map.of("files/", 0L, "files/missing.csv", "Unavailable", "files2/other", 1L);
        assertEquals(Map.of("files/missing.csv", "Unavailable"), ArchiveBrowser.children(files, "files/"));
        assertEquals(Set.of("files/", "files2/"), ArchiveBrowser.children(files, "").keySet());
    }
}
