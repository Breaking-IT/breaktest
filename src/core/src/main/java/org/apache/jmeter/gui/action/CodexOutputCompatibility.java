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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.jmeter.ai.gui.AiCliProcess;

/** Negotiate output before execution; never retry an agent that may already have edited a plan. */
final class CodexOutputCompatibility implements AutoCloseable {
    private final List<String> command;
    private final Path finalReport;

    private CodexOutputCompatibility(List<String> command, Path finalReport) {
        this.command = List.copyOf(command);
        this.finalReport = finalReport;
    }

    static CodexOutputCompatibility prepare(List<String> command, File directory) throws IOException {
        return configure(command, probeHelp(command.get(0), directory));
    }

    static CodexOutputCompatibility configure(List<String> original, String help) throws IOException {
        List<String> command = new ArrayList<>(original);
        Path report = null;
        // Require a separate final-report channel as protection against JSON schema changes.
        if (help.contains("--output-last-message")) {
            report = Files.createTempFile("breaktest-codex-final-", ".txt");
            report.toFile().deleteOnExit();
            int beforePrompt = command.size() - 1;
            command.add(beforePrompt++, "--output-last-message");
            command.add(beforePrompt++, report.toString());
            if (help.contains("--json")) {
                command.add(beforePrompt, "--json");
            }
        }
        return new CodexOutputCompatibility(command, report);
    }

    private static String probeHelp(String executable, File directory) throws IOException {
        Path capture = Files.createTempFile("breaktest-codex-help-", ".txt");
        Process probe = null;
        try {
            var launcher = AiCliProcess.prepare(List.of(executable, "exec", "--help", ""),
                    AiCliProcess.PromptStyle.POSITIONAL);
            probe = new ProcessBuilder(launcher.command()).directory(directory).redirectErrorStream(true)
                    .redirectOutput(capture.toFile()).start();
            if (probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                return Files.readString(capture, StandardCharsets.UTF_8);
            }
            return "";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted checking Codex output capabilities", ex);
        } finally {
            if (probe != null && probe.isAlive()) {
                probe.destroyForcibly();
            }
            delete(capture);
        }
    }

    List<String> command() {
        return command;
    }

    void recoverFinalReport(AiRunOutput output, Consumer<String> activity) throws IOException {
        if (finalReport == null) {
            return;
        }
        String text = Files.readString(finalReport, StandardCharsets.UTF_8);
        if (!text.isBlank()) {
            boolean needsDisplay = !output.hasCompletionStatus();
            output.startFinalResponseBlock();
            for (String line : text.split("\\R")) {
                output.captureFinalResponse(line);
                if (needsDisplay) {
                    activity.accept("Codex: " + line);
                }
            }
        }
    }

    @Override
    public void close() {
        if (finalReport != null) {
            delete(finalReport);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            path.toFile().deleteOnExit();
        }
    }
}
