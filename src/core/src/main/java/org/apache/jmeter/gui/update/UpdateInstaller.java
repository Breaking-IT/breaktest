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

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JDK-only helper that installs a staged update after the GUI process exits.
 * It deliberately has no dependencies on the rest of BreakTest so a copy of
 * the current core JAR can run while the installed core JAR is replaced.
 */
public final class UpdateInstaller {
    private static final Set<String> PRESERVED_SETTINGS = Set.of(
            "bin/user.properties", "bin/system.properties");
    private static final Set<String> UNIX_LAUNCHERS = Set.of(
            "breaktest", "breaktest-agent-mcp", "breaktest-agent-tool", "mirror-server");
    private static final String MANAGED_FILES = ".breaktest-managed-files";
    private static final Pattern JAR_VERSION_SUFFIX = Pattern.compile("-(?:\\d)[0-9A-Za-z._-]*\\.jar$");

    private UpdateInstaller() {
    }

    /** Copies the helper JAR and launches an installer process that waits for this JVM. */
    public static Path launch(Path workspace, Path stagedRoot, Path home,
            Path workingDirectory, List<String> restartCommand) throws IOException {
        Path helperSource;
        try {
            helperSource = Path.of(UpdateInstaller.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Could not locate the updater runtime", e);
        }
        if (!Files.isRegularFile(helperSource) || !helperSource.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Self-update is only available from an installed BreakTest distribution");
        }

        Path helperJar = workspace.resolve("breaktest-updater.jar");
        Files.copy(helperSource, helperJar, StandardCopyOption.REPLACE_EXISTING);
        Path logFile = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("breaktest-update-" + ProcessHandle.current().pid() + ".log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(helperJar.toString());
        command.add(UpdateInstaller.class.getName());
        command.add("--wait-pid");
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add("--home");
        command.add(home.toAbsolutePath().normalize().toString());
        command.add("--staged");
        command.add(stagedRoot.toAbsolutePath().normalize().toString());
        command.add("--workspace");
        command.add(workspace.toAbsolutePath().normalize().toString());
        command.add("--work-dir");
        command.add(workingDirectory.toAbsolutePath().normalize().toString());
        command.add("--");
        command.addAll(restartCommand);

        new ProcessBuilder(command)
                .redirectOutput(logFile.toFile())
                .redirectErrorStream(true)
                .start();
        return logFile;
    }

    /** Entry point used only by the copied helper JAR. */
    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            ProcessHandle.of(parsed.waitPid()).ifPresent(process -> process.onExit().join());
            install(parsed.stagedRoot(), parsed.home());
            ProcessBuilder restart = new ProcessBuilder(parsed.restartCommand());
            if (Files.isDirectory(parsed.workingDirectory())) {
                restart.directory(parsed.workingDirectory().toFile());
            }
            restart.start();
            cleanupWorkspace(parsed.workspace());
        } catch (Exception e) {
            e.printStackTrace(System.err); // NOSONAR The installer log is the only available diagnostic channel
            System.exit(1); // NOSONAR Standalone helper process
        }
    }

    static void install(Path stagedRoot, Path home) throws IOException {
        Path staged = stagedRoot.toAbsolutePath().normalize();
        Path targetHome = home.toAbsolutePath().normalize();
        validateInstallPaths(staged, targetHome);

        Path backup = targetHome.resolve(".breaktest-update-backup-" + UUID.randomUUID());
        Set<Path> backedUp = new HashSet<>();
        List<Path> installed = new ArrayList<>();
        Files.createDirectories(backup);
        try {
            backupPreviouslyManagedFiles(targetHome, backup, backedUp);
            backupStaleRuntimeJars(staged, targetHome, backup, backedUp);
            List<String> newManifest = new ArrayList<>();
            try (var files = Files.walk(staged)) {
                for (Path source : files.sorted().toList()) {
                    if (!Files.isRegularFile(source)) {
                        continue;
                    }
                    Path relative = staged.relativize(source).normalize();
                    newManifest.add(unixPath(relative));
                    Path destination = confinedResolve(targetHome, relative);
                    if (shouldPreserve(relative, destination)) {
                        continue;
                    }
                    backupExisting(targetHome, backup, relative, backedUp);
                    Files.createDirectories(destination.getParent());
                    copy(source, destination);
                    prepareUnixLauncher(relative, destination);
                    installed.add(relative);
                }
            }
            Path manifestRelative = Path.of(MANAGED_FILES);
            backupExisting(targetHome, backup, manifestRelative, backedUp);
            Files.write(targetHome.resolve(manifestRelative), newManifest, StandardCharsets.UTF_8);
            installed.add(manifestRelative);
        } catch (Exception e) {
            rollback(targetHome, backup, installed);
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not install the BreakTest update", e);
        }
        try {
            deleteRecursively(backup);
        } catch (IOException e) {
            // The update is committed. A leftover backup is safer than rolling back with a partial backup.
        }
    }

    private static void validateInstallPaths(Path staged, Path home) throws IOException {
        if (Files.exists(home.resolve(".git"))) {
            throw new IOException("Refusing to self-update a source checkout");
        }
        if (!UpdateService.hasLauncherJar(staged)
                || !Files.isRegularFile(staged.resolve("lib/ext/ApacheJMeter_core.jar"))) {
            throw new IOException("The staged update is not a BreakTest binary distribution");
        }
        if (!Files.isDirectory(home) || !Files.isWritable(home)) {
            throw new IOException("The BreakTest installation directory is not writable");
        }
    }

    private static boolean shouldPreserve(Path relative, Path destination) {
        String path = unixPath(relative);
        if (PRESERVED_SETTINGS.contains(path) && Files.exists(destination)) {
            return true;
        }
        if (!path.startsWith("lib/ext/") || !Files.exists(destination)) {
            return false;
        }
        String name = relative.getFileName().toString();
        return !name.startsWith("ApacheJMeter");
    }

    private static void backupPreviouslyManagedFiles(Path home, Path backup, Set<Path> backedUp) throws IOException {
        Path manifest = home.resolve(MANAGED_FILES);
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.contains("\\")) {
                continue;
            }
            Path relative = Path.of(line).normalize();
            Path destination = confinedResolve(home, relative);
            if (!shouldPreserve(relative, destination)) {
                backupExisting(home, backup, relative, backedUp);
            }
        }
    }

    private static void backupStaleRuntimeJars(Path staged, Path home, Path backup, Set<Path> backedUp)
            throws IOException {
        List<Path> roots = List.of(
                home.resolve("lib"), home.resolve("lib/api"), home.resolve("lib/junit"),
                home.resolve("lib/opt"), home.resolve("lib/ext"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var children = Files.list(root)) {
                for (Path child : children.filter(Files::isRegularFile).toList()) {
                    String name = child.getFileName().toString();
                    boolean coreExtension = root.endsWith("ext") && name.startsWith("ApacheJMeter");
                    boolean managedJar = !root.endsWith("ext") && name.endsWith(".jar")
                            && hasReplacement(staged, home.relativize(child));
                    if (coreExtension || managedJar) {
                        backupExisting(home, backup, home.relativize(child), backedUp);
                    }
                }
            }
        }
        // The launcher may exist under its new and/or legacy name; back up both
        for (String launcherName : new String[] {"bin/breaktest.jar", "bin/ApacheJMeter.jar"}) {
            Path launcher = home.resolve(launcherName);
            if (Files.exists(launcher)) {
                backupExisting(home, backup, home.relativize(launcher), backedUp);
            }
        }
    }

    private static boolean hasReplacement(Path staged, Path existingRelative) throws IOException {
        Path stagedDirectory = staged.resolve(existingRelative).getParent();
        if (!Files.isDirectory(stagedDirectory)) {
            return false;
        }
        String existingKey = jarArtifactKey(existingRelative.getFileName().toString());
        try (var candidates = Files.list(stagedDirectory)) {
            return candidates.filter(Files::isRegularFile)
                    .map(path -> jarArtifactKey(path.getFileName().toString()))
                    .anyMatch(existingKey::equals);
        }
    }

    private static String jarArtifactKey(String name) {
        return JAR_VERSION_SUFFIX.matcher(name).replaceFirst("");
    }

    private static void backupExisting(Path home, Path backup, Path relative, Set<Path> backedUp)
            throws IOException {
        if (!backedUp.add(relative)) {
            return;
        }
        Path source = confinedResolve(home, relative);
        if (!Files.exists(source)) {
            return;
        }
        Path destination = confinedResolve(backup, relative);
        Files.createDirectories(destination.getParent());
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void rollback(Path home, Path backup, List<Path> installed) throws IOException {
        IOException failure = null;
        for (int i = installed.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(confinedResolve(home, installed.get(i)));
            } catch (IOException e) {
                failure = e;
            }
        }
        if (Files.exists(backup)) {
            try (var files = Files.walk(backup)) {
                for (Path source : files.sorted().toList()) {
                    if (!Files.isRegularFile(source)) {
                        continue;
                    }
                    Path destination = confinedResolve(home, backup.relativize(source));
                    Files.createDirectories(destination.getParent());
                    Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            deleteRecursively(backup);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void copy(Path source, Path destination) throws IOException {
        CopyOption[] options = {StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES};
        Files.copy(source, destination, options);
    }

    private static void prepareUnixLauncher(Path relative, Path destination) throws IOException {
        if (isWindows() || relative.getNameCount() != 2 || !"bin".equals(relative.getName(0).toString())) {
            return;
        }
        String name = relative.getFileName().toString();
        if (!name.endsWith(".sh") && !UNIX_LAUNCHERS.contains(name)) {
            return;
        }
        byte[] original = Files.readAllBytes(destination);
        String normalized = new String(original, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Files.writeString(destination, normalized, StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(destination);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(destination, permissions);
        } catch (UnsupportedOperationException e) {
            destination.toFile().setExecutable(true, false);
        }
    }

    private static Path confinedResolve(Path root, Path relative) throws IOException {
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root)) {
            throw new IOException("Update path escapes its installation directory");
        }
        Path current = root;
        Path insideRoot = root.relativize(result);
        for (int i = 0; i < insideRoot.getNameCount() - 1; i++) {
            current = current.resolve(insideRoot.getName(i));
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Update path contains a symbolic-link directory: " + current);
            }
        }
        return result;
    }

    private static String unixPath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void cleanupWorkspace(Path workspace) {
        try {
            deleteRecursively(workspace.resolve("extracted"));
            try (var children = Files.list(workspace)) {
                for (Path child : children.toList()) {
                    if (!child.getFileName().toString().equals("breaktest-updater.jar")) {
                        deleteRecursively(child);
                    }
                }
            }
        } catch (IOException e) {
            // A later update check can clean stale temporary files.
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var files = Files.walk(path)) {
            for (Path item : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private record Arguments(long waitPid, Path home, Path stagedRoot, Path workspace,
            Path workingDirectory, List<String> restartCommand) {
        private static Arguments parse(String[] args) {
            if (args.length < 12 || !"--wait-pid".equals(args[0]) || !"--home".equals(args[2])
                    || !"--staged".equals(args[4]) || !"--workspace".equals(args[6])
                    || !"--work-dir".equals(args[8]) || !"--".equals(args[10])) {
                throw new IllegalArgumentException("Invalid updater arguments");
            }
            List<String> restart = List.of(args).subList(11, args.length);
            if (restart.isEmpty()) {
                throw new IllegalArgumentException("Missing restart command");
            }
            return new Arguments(Long.parseLong(args[1]), Path.of(args[3]), Path.of(args[5]),
                    Path.of(args[7]), Path.of(args[9]), restart);
        }
    }
}
