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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.swing.JOptionPane;

import org.apache.jmeter.save.SaveService;
import org.apache.jorphan.collections.HashTree;

/** Converts a legacy XML JMX into BreakTest's archive JMX format safely. */
final class BreakTestJmxUpgrade {
    private static final String BACKUP_SUFFIX = ".jmeter-backup-"; // $NON-NLS-1$

    private BreakTestJmxUpgrade() {
    }

    static boolean isNativeBreakTestJmx(Path file) throws IOException {
        try (ZipFile archive = new ZipFile(file.toFile())) {
            ZipEntry testPlan = archive.getEntry(SaveService.TEST_PLAN_ZIP_ENTRY);
            return testPlan != null && !testPlan.isDirectory();
        } catch (ZipException ex) {
            return false;
        }
    }

    static boolean isUpgradeConfirmed(int dialogChoice) {
        return dialogChoice == JOptionPane.YES_OPTION;
    }

    static Path nextBackupPath(Path source) {
        String filename = source.getFileName().toString();
        String baseName = filename.endsWith(Save.JMX_FILE_EXTENSION)
                ? filename.substring(0, filename.length() - Save.JMX_FILE_EXTENSION.length())
                : filename;
        Path directory = source.toAbsolutePath().getParent();
        for (int number = 1; ; number++) {
            Path candidate = directory.resolve(baseName + BACKUP_SUFFIX + String.format("%03d", number)
                    + Save.JMX_FILE_EXTENSION);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Retains an exact, JMeter-compatible copy before atomically replacing the original with an archive JMX.
     * The caller must obtain explicit user confirmation before invoking this method.
     */
    static Path upgrade(Path source, HashTree tree) throws IOException {
        return upgrade(source, output -> SaveService.saveTree(tree, output));
    }

    static Path upgradeIfConfirmed(int dialogChoice, Path source, HashTree tree) throws IOException {
        return isUpgradeConfirmed(dialogChoice) ? upgrade(source, tree) : null;
    }

    static Path upgrade(Path source, ArchiveWriter writer) throws IOException {
        Path backup = createBackup(source);
        Path parent = source.toAbsolutePath().getParent();
        Path temporary = Files.createTempFile(parent, "." + source.getFileName() + ".breaktest-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                writer.write(output);
            }
            moveReplacing(source, temporary);
            return backup;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path createBackup(Path source) throws IOException {
        while (true) {
            Path backup = nextBackupPath(source);
            try {
                Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
                return backup;
            } catch (FileAlreadyExistsException ex) {
                // Another BreakTest instance chose the same available name. Pick the next one.
            }
        }
    }

    private static void moveReplacing(Path source, Path temporary) throws IOException {
        try {
            Files.move(temporary, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface ArchiveWriter {
        void write(OutputStream output) throws IOException;
    }
}
