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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AiCliProcessTest {

    @Test
    void promptIsMovedFromCommandLineToStdinForEachCliSyntax() {
        AiCliProcess codex = AiCliProcess.prepare(List.of("missing-codex", "exec", "prompt"),
                AiCliProcess.PromptStyle.CODEX);
        AiCliProcess positional = AiCliProcess.prepare(List.of("missing-pi", "--print", "prompt"),
                AiCliProcess.PromptStyle.POSITIONAL);
        AiCliProcess copilot = AiCliProcess.prepare(List.of("missing-copilot", "--model", "x", "-p", "prompt"),
                AiCliProcess.PromptStyle.COPILOT);
        AiCliProcess gemini = AiCliProcess.prepare(List.of("missing-gemini", "--prompt", "prompt"),
                AiCliProcess.PromptStyle.GEMINI);

        assertEquals(List.of("missing-codex", "exec", "-"), codex.command());
        assertEquals(List.of("missing-pi", "--print"), positional.command());
        assertEquals(List.of("missing-copilot", "--model", "x"), copilot.command());
        assertEquals(List.of("missing-gemini"), gemini.command());
        assertFalse(codex.command().contains("prompt"));
        assertFalse(positional.command().contains("prompt"));
        assertFalse(copilot.command().contains("prompt"));
        assertFalse(gemini.command().contains("prompt"));
    }

    @Test
    void windowsBatchFileUnderPathWithSpacesUsesCommandInterpreter(@TempDir Path tempDirectory)
            throws Exception {
        Path script = Files.createDirectories(tempDirectory.resolve("CLI tools with spaces")).resolve("dummy.cmd");
        Files.writeString(script, "@echo off\r\n", StandardCharsets.UTF_8);

        List<String> wrapped = AiCliProcess.windowsBatchCommand(
                List.of(script.toString(), "argument with spaces"),
                Map.of("ComSpec", "C:\\Windows\\System32\\cmd.exe"),
                true);

        assertEquals("C:\\Windows\\System32\\cmd.exe", wrapped.get(0));
        assertEquals(List.of("/d", "/s", "/c"), wrapped.subList(1, 4));
        assertEquals("\"\"" + script.toAbsolutePath().normalize()
                + "\" \"argument with spaces\"\"", wrapped.get(4));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void launchesDummyWindowsCliFromPathWithSpacesAndPipesPrompt(@TempDir Path tempDirectory)
            throws Exception {
        Path script = Files.createDirectories(tempDirectory.resolve("CLI tools with spaces")).resolve("dummy.cmd");
        Files.writeString(script, """
                @echo off
                set /p "PROMPT="
                echo %~1^|%PROMPT%
                """, StandardCharsets.UTF_8);

        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("PATH", script.getParent().toString());
        environment.put("PATHEXT", ".CMD;.EXE");
        AiCliProcess command = AiCliProcess.prepare(
                List.of("dummy", "argument with spaces", "-p", "prompt over stdin\n"),
                AiCliProcess.PromptStyle.COPILOT,
                environment,
                true);
        Process process = command.start(tempDirectory.toFile());
        command.writePrompt(process);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

        assertEquals(0, process.waitFor());
        assertEquals("argument with spaces|prompt over stdin", output);
    }
}
