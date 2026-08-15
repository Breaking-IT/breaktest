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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.apache.jmeter.util.JMeterUtils;

final class AiCliAvailability {
    private static final Map<String, String> DEFAULT_COMMANDS = Map.of(
            "codex", "codex",
            "claude", "claude",
            "cursor", "cursor-agent",
            "gemini", "gemini",
            "pi", "pi",
            "opencode", "opencode",
            "copilot", "copilot");
    private static final boolean IS_WINDOWS = File.pathSeparatorChar == ';';

    private AiCliAvailability() {
    }

    static String displayName(String toolId, String displayName) {
        return isAvailable(toolId) ? displayName : displayName + " (unavailable)";
    }

    static boolean isAvailable(String toolId) {
        String defaultCommand = DEFAULT_COMMANDS.get(toolId);
        if (defaultCommand == null) {
            return false;
        }
        String command = JMeterUtils.getPropDefault("breaktest." + toolId + ".command", defaultCommand);
        return isExecutable(command);
    }

    static <T> T[] sortAvailableFirst(
            T[] tools,
            Function<T, String> toolId,
            Function<T, String> displayName) {
        Arrays.sort(tools, Comparator
                .comparingInt((T tool) -> isAvailable(toolId.apply(tool)) ? 0 : 1)
                .thenComparing(tool -> displayName.apply(tool), String.CASE_INSENSITIVE_ORDER));
        return tools;
    }

    static boolean isExecutable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            if (command.indexOf('/') >= 0 || command.indexOf('\\') >= 0) {
                return isRunnable(Path.of(command));
            }
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                return false;
            }
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
                Path parent = directory.isEmpty() ? Path.of(".") : Path.of(directory);
                for (String candidate : commandCandidates(command)) {
                    if (isRunnable(parent.resolve(candidate))) {
                        return true;
                    }
                }
            }
        } catch (InvalidPathException | SecurityException ignored) {
            // Match ProcessBuilder behavior: an invalid or inaccessible command cannot launch.
        }
        return false;
    }

    private static List<String> commandCandidates(String command) {
        List<String> candidates = new ArrayList<>();
        candidates.add(command);
        if (!IS_WINDOWS) {
            return candidates;
        }
        String pathExtensions = System.getenv("PATHEXT");
        if (pathExtensions == null || pathExtensions.isBlank()) {
            pathExtensions = ".COM;.EXE;.BAT;.CMD";
        }
        String lowerCommand = command.toLowerCase(Locale.ROOT);
        for (String extension : pathExtensions.split(";")) {
            String normalized = extension.startsWith(".") ? extension : "." + extension;
            if (!lowerCommand.endsWith(normalized.toLowerCase(Locale.ROOT))) {
                candidates.add(command + normalized);
            }
        }
        return candidates;
    }

    private static boolean isRunnable(Path path) {
        return Files.isRegularFile(path) && (IS_WINDOWS || Files.isExecutable(path));
    }
}
