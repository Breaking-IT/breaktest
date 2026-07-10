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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateInstallerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesRuntimeWhilePreservingSettingsAndPlugins() throws Exception {
        Path home = temporaryDirectory.resolve("installed");
        write(home, "bin/ApacheJMeter.jar", "old launcher");
        write(home, "bin/user.properties", "theme=custom");
        write(home, "bin/system.properties", "proxy=custom");
        write(home, "lib/dependency-1.0.jar", "old dependency");
        write(home, "lib/custom-jdbc-driver-4.2.jar", "custom driver");
        write(home, "lib/ext/ApacheJMeter_core.jar", "old core");
        write(home, "lib/ext/ApacheJMeter_obsolete.jar", "obsolete core");
        write(home, "lib/ext/company-plugin.jar", "plugin");

        Path staged = temporaryDirectory.resolve("staged");
        write(staged, "bin/ApacheJMeter.jar", "new launcher");
        write(staged, "bin/user.properties", "shipped default");
        write(staged, "bin/system.properties", "shipped default");
        write(staged, "bin/breaktest.sh", "#!/bin/sh\r\necho updated\r\n");
        write(staged, "lib/dependency-2.0.jar", "new dependency");
        write(staged, "lib/ext/ApacheJMeter_core.jar", "new core");

        UpdateInstaller.install(staged, home);

        assertEquals("new launcher", Files.readString(home.resolve("bin/ApacheJMeter.jar")));
        assertEquals("new core", Files.readString(home.resolve("lib/ext/ApacheJMeter_core.jar")));
        assertEquals("theme=custom", Files.readString(home.resolve("bin/user.properties")));
        assertEquals("proxy=custom", Files.readString(home.resolve("bin/system.properties")));
        assertEquals("plugin", Files.readString(home.resolve("lib/ext/company-plugin.jar")));
        assertFalse(Files.exists(home.resolve("lib/dependency-1.0.jar")));
        assertFalse(Files.exists(home.resolve("lib/ext/ApacheJMeter_obsolete.jar")));
        assertTrue(Files.exists(home.resolve("lib/dependency-2.0.jar")));
        assertEquals("custom driver", Files.readString(home.resolve("lib/custom-jdbc-driver-4.2.jar")));
        assertTrue(Files.exists(home.resolve(".breaktest-managed-files")));
        assertFalse(Files.readString(home.resolve("bin/breaktest.sh")).contains("\r"));

        Path nextStaged = temporaryDirectory.resolve("next-staged");
        write(nextStaged, "bin/ApacheJMeter.jar", "next launcher");
        write(nextStaged, "lib/dependency-3.0.jar", "next dependency");
        write(nextStaged, "lib/ext/ApacheJMeter_core.jar", "next core");
        UpdateInstaller.install(nextStaged, home);

        assertFalse(Files.exists(home.resolve("bin/breaktest.sh")));
        assertFalse(Files.exists(home.resolve("lib/dependency-2.0.jar")));
        assertEquals("custom driver", Files.readString(home.resolve("lib/custom-jdbc-driver-4.2.jar")));
        assertEquals("plugin", Files.readString(home.resolve("lib/ext/company-plugin.jar")));
        assertEquals("theme=custom", Files.readString(home.resolve("bin/user.properties")));
    }

    @Test
    void refusesToModifySourceCheckout() throws Exception {
        Path home = temporaryDirectory.resolve("checkout");
        Files.createDirectories(home.resolve(".git"));
        Path staged = temporaryDirectory.resolve("staged-checkout");
        write(staged, "bin/ApacheJMeter.jar", "launcher");
        write(staged, "lib/ext/ApacheJMeter_core.jar", "core");

        assertThrows(IOException.class, () -> UpdateInstaller.install(staged, home));
    }

    @Test
    void refusesToFollowSymlinkInsideInstallation() throws Exception {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));
        Path home = temporaryDirectory.resolve("symlink-install");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(home);
        Files.createDirectories(outside);
        Files.createSymbolicLink(home.resolve("bin"), outside);
        Path staged = temporaryDirectory.resolve("staged-symlink");
        write(staged, "bin/ApacheJMeter.jar", "launcher");
        write(staged, "lib/ext/ApacheJMeter_core.jar", "core");

        assertThrows(IOException.class, () -> UpdateInstaller.install(staged, home));
        assertFalse(Files.exists(outside.resolve("ApacheJMeter.jar")));
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
