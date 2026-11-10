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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.jmeter.util.JMeterUtils;

final class AgentBridgeCommand {
    private static final String ARGUMENTS_FILE_PREFIX = "breaktest-agent-arguments-";

    private AgentBridgeCommand() {
    }

    static Instructions resolveInstructions() {
        return resolveInstructions(File.pathSeparatorChar == ';');
    }

    static Instructions resolveInstructions(boolean windows) {
        Path argumentsFile = argumentsFile();
        String command = resolveCommand(windows);
        if (windows) {
            String argumentsPath = quoteWindows(argumentsFile.toAbsolutePath().toString());
            return new Instructions(
                    command,
                    "write the JSON arguments object to `" + argumentsFile.toAbsolutePath()
                            + "` using your file-editing capability, then run `" + command
                            + " <tool-name> --arguments-file " + argumentsPath
                            + "`; overwrite that file before every bridge call",
                    "The start is already announced. Report later milestones by writing "
                            + "`{\"level\":\"info\",\"message\":\"...\"}` to `"
                            + argumentsFile.toAbsolutePath() + "`, then running `" + command
                            + " agent_activity --arguments-file " + argumentsPath + "`");
        }
        return new Instructions(
                command,
                "run `" + command + " <tool-name> '<json-arguments>'`; for multi-item JSON, or JSON containing "
                        + "quotes, pass `-` and a heredoc in the same shell call: `" + command
                        + " <tool-name> - <<'JSON'`, the JSON on the following lines, then `JSON`",
                "The start is already announced. Report later milestones with `" + command
                        + " agent_activity '{\"level\":\"info\",\"message\":\"...\"}'`, in the same shell command as "
                        + "the next tool call where possible");
    }

    static void deleteArgumentsFile() {
        try {
            Files.deleteIfExists(argumentsFile());
        } catch (IOException ignored) {
            // Best-effort cleanup of a short-lived file written by the external coding agent.
        }
    }

    /**
     * The same for every tool, Codex included. Codex used to be pointed at an installed
     * breaktest-jmeter-repair skill; this prompt is self-contained now, and a skill written for an
     * older workflow only added tens of kilobytes of conflicting instructions to every request.
     */
    static String harnessBootstrap(String toolDisplayName) {
        return "This run uses " + toolDisplayName + ". Do not look for or invoke a breaktest-jmeter-repair skill "
                + "and do not require a registered BreakTest MCP server. Use the bundled bridge below as the "
                + "authoritative BreakTest tool interface.";
    }

    private static String resolveCommand(boolean windows) {
        String configured = JMeterUtils.getProperty("breaktest.agent.tool");
        if (configured != null && !configured.isBlank()) {
            if (windows && configured.toLowerCase(Locale.ROOT).endsWith(".ps1")) {
                return powershellFileCommand(new File(configured).getAbsolutePath());
            }
            return shellCommand(new File(configured).getAbsolutePath(), windows);
        }
        String launcher = windows ? "bin/breaktest-agent-tool.ps1" : "bin/breaktest-agent-tool";
        File candidate = new File(JMeterUtils.getJMeterHome(), launcher);
        if (candidate.isFile()) {
            if (windows) {
                return powershellFileCommand(candidate.getAbsolutePath());
            }
            return shellCommand(candidate.getAbsolutePath(), windows);
        }
        return shellCommand(windows ? "breaktest-agent-tool.cmd" : "breaktest-agent-tool", windows);
    }

    static String shellCommand(String command, boolean windows) {
        if (windows) {
            return "& '" + command.replace("'", "''") + "'";
        }
        return "'" + command.replace("'", "'\"'\"'") + "'";
    }

    private static String powershellFileCommand(String path) {
        return "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "
                + quoteWindows(path);
    }

    private static String quoteWindows(String value) {
        return "\"" + value + "\"";
    }

    private static Path argumentsFile() {
        return Path.of(System.getProperty("java.io.tmpdir"),
                ARGUMENTS_FILE_PREFIX + ProcessHandle.current().pid() + ".json");
    }

    record Instructions(
            String command,
            String bridgeCall,
            String startActivity) {
    }
}
