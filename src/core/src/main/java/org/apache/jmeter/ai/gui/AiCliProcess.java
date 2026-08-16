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

package org.apache.jmeter.ai.gui;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prepares a non-interactive AI CLI process, including Windows batch launchers.
 */
public final class AiCliProcess {
    public enum PromptStyle {
        CODEX,
        POSITIONAL,
        COPILOT,
        GEMINI
    }

    private static final boolean IS_WINDOWS = File.pathSeparatorChar == ';';

    private final List<String> command;
    private final String prompt;

    private AiCliProcess(List<String> command, String prompt) {
        this.command = List.copyOf(command);
        this.prompt = prompt;
    }

    public static AiCliProcess prepare(List<String> configuredCommand, PromptStyle promptStyle) {
        return prepare(configuredCommand, promptStyle, System.getenv(), IS_WINDOWS);
    }

    static AiCliProcess prepare(
            List<String> configuredCommand,
            PromptStyle promptStyle,
            Map<String, String> environment,
            boolean windows) {
        if (configuredCommand.size() < 2) {
            throw new IllegalArgumentException("AI CLI command must end with a prompt");
        }
        List<String> command = new ArrayList<>(configuredCommand);
        String prompt = command.remove(command.size() - 1);
        switch (promptStyle) {
        case CODEX -> {
            // `codex exec -` explicitly reads the prompt from stdin.
            command.add("-");
        }
        case POSITIONAL -> {
            // Claude's -p is print mode; the other positional CLIs infer pipe mode.
        }
        case COPILOT -> removeTrailingOption(command, "-p");
        case GEMINI -> removeTrailingOption(command, "--prompt");
        }
        return new AiCliProcess(windowsBatchCommand(command, environment, windows), prompt);
    }

    public List<String> command() {
        return command;
    }

    public Process start(File workingDirectory) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory);
        processBuilder.redirectErrorStream(true);
        return processBuilder.start();
    }

    public void writePrompt(Process process) throws IOException {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void removeTrailingOption(List<String> command, String option) {
        int last = command.size() - 1;
        if (last < 0 || !option.equals(command.get(last))) {
            throw new IllegalArgumentException("Expected " + option + " immediately before the prompt");
        }
        command.remove(last);
    }

    static List<String> windowsBatchCommand(
            List<String> command,
            Map<String, String> environment,
            boolean windows) {
        if (!windows || command.isEmpty()) {
            return List.copyOf(command);
        }
        String executable = resolveWindowsExecutable(command.get(0), environment);
        String lower = executable.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".cmd") && !lower.endsWith(".bat")) {
            return List.copyOf(command);
        }

        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, executable);
        String invocation = resolved.stream()
                .map(AiCliProcess::quoteCmdArgument)
                .collect(Collectors.joining(" "));
        String commandInterpreter = environment.getOrDefault("ComSpec", "cmd.exe");
        // The extra outer quotes preserve a quoted executable path with cmd.exe /s /c.
        return List.of(commandInterpreter, "/d", "/s", "/c", '"' + invocation + '"');
    }

    private static String resolveWindowsExecutable(String executable, Map<String, String> environment) {
        try {
            List<String> candidates = windowsCandidates(executable, environment.get("PATHEXT"));
            if (hasPathSeparator(executable)) {
                for (String candidate : candidates) {
                    Path path = Path.of(candidate);
                    if (Files.isRegularFile(path)) {
                        return path.toAbsolutePath().normalize().toString();
                    }
                }
                return executable;
            }

            String pathValue = environment.get("PATH");
            if (pathValue != null) {
                for (String directory : pathValue.split(Pattern.quote(File.pathSeparator), -1)) {
                    Path parent = directory.isEmpty() ? Path.of(".") : Path.of(directory);
                    for (String candidate : candidates) {
                        Path path = parent.resolve(candidate);
                        if (Files.isRegularFile(path)) {
                            return path.toAbsolutePath().normalize().toString();
                        }
                    }
                }
            }
        } catch (InvalidPathException | SecurityException ignored) {
            // Let ProcessBuilder report the invalid or inaccessible command.
        }
        return executable;
    }

    private static List<String> windowsCandidates(String executable, String pathExtensions) {
        List<String> candidates = new ArrayList<>();
        candidates.add(executable);
        String extensions = pathExtensions == null || pathExtensions.isBlank()
                ? ".COM;.EXE;.BAT;.CMD"
                : pathExtensions;
        String lower = executable.toLowerCase(Locale.ROOT);
        for (String extension : extensions.split(";")) {
            String normalized = extension.startsWith(".") ? extension : "." + extension;
            if (!lower.endsWith(normalized.toLowerCase(Locale.ROOT))) {
                candidates.add(executable + normalized);
            }
        }
        return candidates;
    }

    private static boolean hasPathSeparator(String executable) {
        return executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0;
    }

    private static String quoteCmdArgument(String argument) {
        return '"' + argument.replace("\"", "\"\"") + '"';
    }
}
