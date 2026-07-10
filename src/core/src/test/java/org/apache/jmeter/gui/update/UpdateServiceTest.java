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

package org.apache.jmeter.gui.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class UpdateServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void comparesCalendarVersionsNumerically() {
        assertTrue(UpdateService.compareVersions("2026.10.1", "2026.9.30") > 0);
        assertTrue(UpdateService.compareVersions("v2026.07.11", "2026.07.10") > 0);
        assertTrue(UpdateService.compareVersions("2026.07.10", "2026.7.10-SNAPSHOT") > 0);
        assertTrue(UpdateService.compareVersions("2026.07.10", "2026.07.10-RC1") > 0);
        assertEquals(0, UpdateService.compareVersions("2026.07.10", "2026.7.10"));
    }

    @Test
    void doesNotCheckForUpdatesOutsideGuiMode() throws Exception {
        UpdateService service = new UpdateService(HttpClient.newHttpClient(),
                URI.create("https://updates.invalid/latest"), "2026.07.10",
                Preferences.userNodeForPackage(UpdateServiceTest.class), Duration.ofHours(24), () -> false);

        assertTrue(service.checkNow().get(1, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void selectsBinaryZipAndChecksumFromLatestRelease() throws Exception {
        String json = """
                {
                  "tag_name": "2026.07.11",
                  "html_url": "https://github.com/Breaking-IT/breaktest/releases/tag/2026.07.11",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    {"name":"breaktest-2026.07.11.zip","browser_download_url":"https://github.com/a.zip",
                     "size":42,"digest":"sha256:abcd"},
                    {"name":"breaktest-2026.07.11.zip.sha512",
                     "browser_download_url":"https://github.com/a.zip.sha512","size":155}
                  ]
                }
                """;

        ReleaseInfo release = UpdateService.parseRelease(JSON.readTree(json), "2026.07.10").orElseThrow();

        assertEquals("2026.07.11", release.version());
        assertEquals("breaktest-2026.07.11.zip", release.archive().name());
        assertEquals("breaktest-2026.07.11.zip.sha512", release.checksum().name());
    }

    @Test
    void ignoresCurrentOrPrereleaseVersions() throws Exception {
        String current = """
                {"tag_name":"2026.07.10","draft":false,"prerelease":false,"assets":[]}
                """;
        String prerelease = """
                {"tag_name":"2026.07.11","draft":false,"prerelease":true,"assets":[]}
                """;

        assertTrue(UpdateService.parseRelease(JSON.readTree(current), "2026.07.10").isEmpty());
        assertTrue(UpdateService.parseRelease(JSON.readTree(prerelease), "2026.07.10").isEmpty());
    }

    @Test
    void rejectsVersionTagThatCouldEscapeStagingDirectory() throws Exception {
        String malicious = """
                {"tag_name":"../../escape","draft":false,"prerelease":false,"assets":[]}
                """;

        assertThrows(IOException.class,
                () -> UpdateService.parseRelease(JSON.readTree(malicious), "2026.07.10"));
    }

    @Test
    void extractsOnlyPathsInsideStagingDirectory() throws Exception {
        Path archive = temporaryDirectory.resolve("valid.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(zip, "breaktest-new/bin/ApacheJMeter.jar", "launcher");
            add(zip, "breaktest-new/lib/ext/ApacheJMeter_core.jar", "core");
        }

        Path root = UpdateService.extractZip(archive, temporaryDirectory.resolve("valid"));

        assertEquals("launcher", Files.readString(root.resolve("bin/ApacheJMeter.jar")));
    }

    @Test
    void rejectsZipSlipEntry() throws Exception {
        Path archive = temporaryDirectory.resolve("malicious.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(zip, "../outside.txt", "not allowed");
        }

        assertThrows(IOException.class,
                () -> UpdateService.extractZip(archive, temporaryDirectory.resolve("malicious")));
    }

    private static void add(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
