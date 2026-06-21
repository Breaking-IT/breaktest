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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;

import org.apache.jmeter.ai.gui.AiAutoScriptingLogWindow;
import org.apache.jmeter.ai.gui.BreakTestAgentGuiService;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/**
 * Starts a local Codex repair session for the currently open BreakTest plan.
 */
@AutoService(Command.class)
public class CodexAiRepair extends AbstractAction {
    private static final Logger log = LoggerFactory.getLogger(CodexAiRepair.class);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Set<String> COMMANDS;
    private static final boolean SHOW_RAW_CODEX_OUTPUT =
            JMeterUtils.getPropDefault("breaktest.codex.show_raw_output", false);

    static {
        Set<String> commands = new HashSet<>();
        commands.add(ActionNames.CODEX_AI_REPAIR);
        COMMANDS = Collections.unmodifiableSet(commands);
    }

    @Override
    public void doAction(ActionEvent e) {
        if (RUNNING.get()) {
            postActivity("AI Auto Scripting is already running.");
            return;
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            gui.updateCurrentNode();
        }
        AiRunRequest request = showStartDialog(gui);
        if (request == null) {
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            postActivity("AI Auto Scripting is already running.");
            return;
        }

        try {
            AiAutoScriptingLogWindow.showWindow();
            AiAutoScriptingLogWindow.startRun();
            BreakTestAgentGuiService.start();
            String backupPath = BreakTestAgentGuiService.createBackupForOpenPlan();
            AiRunRequest runRequest = request.withBackupPath(backupPath);

            Thread worker = new Thread(() -> runCodex(runRequest), "BreakTest AI Auto Scripting");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception ex) {
            RUNNING.set(false);
            log.warn("Could not start AI Auto Scripting", ex);
            postActivity("AI Auto Scripting failed to start: " + ex.getMessage());
            AiAutoScriptingLogWindow.finishRun("Failed to start");
        }
    }

    @Override
    public Set<String> getActionNames() {
        return COMMANDS;
    }

    private static void runCodex(AiRunRequest request) {
        try {
            File workingDirectory = codexWorkingDirectory();
            List<String> command = codexCommand(request, workingDirectory);
            postActivity("Starting AI Auto Scripting.");
            postActivity("Command: " + commandSummary(command));
            if (request.hasUserInput()) {
                postActivity("Included extra user instructions from the start dialog.");
            }
            postActivity("Working directory: " + workingDirectory.getPath());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            process.getOutputStream().close();
            streamOutput(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                postActivity("AI Auto Scripting finished successfully.");
                AiAutoScriptingLogWindow.finishRun("Finished successfully");
            } else {
                postActivity("AI Auto Scripting exited with code " + exitCode + ".");
                AiAutoScriptingLogWindow.finishRun("Exited with code " + exitCode);
            }
        } catch (Exception ex) {
            log.warn("AI Auto Scripting failed", ex);
            postActivity("AI Auto Scripting failed: " + ex.getMessage());
            AiAutoScriptingLogWindow.finishRun("Failed");
        } finally {
            RUNNING.set(false);
        }
    }

    private static List<String> codexCommand(AiRunRequest request, File workingDirectory) {
        List<String> command = new ArrayList<>();
        command.add(JMeterUtils.getPropDefault("breaktest.codex.command", "codex"));
        command.add("--ask-for-approval");
        command.add(JMeterUtils.getPropDefault("breaktest.codex.approval", "never"));
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--sandbox");
        command.add(JMeterUtils.getPropDefault("breaktest.codex.sandbox", "danger-full-access"));
        command.add("--cd");
        command.add(workingDirectory.getPath());
        command.add("-c");
        command.add("mcp_servers.breaktest.enabled=false");

        String model = JMeterUtils.getProperty("breaktest.codex.model");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }

        command.add(prompt(request));
        return command;
    }

    private static String commandSummary(List<String> command) {
        if (command.isEmpty()) {
            return "";
        }
        List<String> summary = new ArrayList<>(command);
        summary.set(summary.size() - 1, "[built-in prompt]");
        return String.join(" ", summary);
    }

    private static File codexWorkingDirectory() {
        String configured = JMeterUtils.getProperty("breaktest.codex.cwd");
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        File jmeterHome = new File(JMeterUtils.getJMeterHome());
        if (jmeterHome.isDirectory()) {
            return jmeterHome;
        }
        return new File(".").getAbsoluteFile();
    }

    private static String prompt(AiRunRequest request) {
        String configured = JMeterUtils.getProperty("breaktest.codex.prompt");
        if (configured != null && !configured.isBlank()) {
            return configured + userInstructionBlock(request);
        }
        GuiPackage gui = GuiPackage.getInstance();
        String testPlanFile = gui != null ? gui.getTestPlanFile() : null;
        String selectedNode = null;
        if (gui != null && gui.getTreeListener() != null && gui.getTreeListener().getCurrentNode() != null) {
            selectedNode = gui.getTreeListener().getCurrentNode().getName();
        }
        return """
                Use $breaktest-jmeter-repair.

                Repair and harden the currently open BreakTest/JMeter GUI plan through the BreakTest MCP server.

                Requirements:
                - First call agent_activity so progress is visible in the BreakTest AI Auto Scripting window.
                - A GUI backup is created before this run starts. Use it only as a rollback safety net.
                - Use the supported BreakTest agent bridge command when native MCP tools are unavailable: `./bin/breaktest-agent-tool <tool-name> '<json-arguments>'`.
                - Prefer GUI-backed MCP tools: inspect_open_plan, validate_open_plan, agent_activity, get_ai_knowledge_open_plan, update_ai_knowledge_open_plan, list_agent_changes_open_plan, and open-plan edit tools.
                - Use live open-plan edit tools for supported changes: apply_boundary_correlation_open_plan, apply_regex_correlation_open_plan, replace_literal_open_plan, add_response_assertion_open_plan, and set_redirect_mode_open_plan.
                - The GUI edit tools create elements through BreakTest/JMeter GUI components. If a live edit tool fails, report it with agent_activity and continue with other safe GUI-backed edits.
                - First call `./bin/breaktest-agent-tool agent_activity '{"level":"info","message":"Starting AI Auto Scripting"}'` so progress is visible in the BreakTest AI Auto Scripting window.
                - Keep the open BreakTest test plan as the live edit surface so changes are visible in the GUI.
                - Do not start a separate MCP server by hand. Use the supported BreakTest agent bridge command for validation, inspection, and edits.
                - Read BreakTest AI Knowledge before repairing. Reuse applicable confirmed project patterns for correlations, assertions, variables, timestamps, dependencies, and randomization.
                - After a validated successful repair, update BreakTest AI Knowledge with only reusable learnings that were confirmed by inspection or validation. Include what thread group/transaction proved the pattern.
                - Work from top to bottom through the selected thread group. For each transaction, review the previous transaction's extracted values before blaming the current failing request.
                - A failing callback, payment, basket, or order request can be a symptom of earlier static data. Check upstream requests first for stale login state, redirect/session/correlation cookies, request verification tokens, anti-forgery values, product/ticket/order IDs, and timestamps.
                - Validate with `validate_open_plan` in bounded runs with maxSamples and stopOnFirstFailure.
                - A first validation failure is a starting point, not the full scope. If validation blocks early, continue by inspecting the whole open plan and auditing later transactions statically.
                - Use `plan.samplers[*].transactionName` and `plan.dynamicValueCandidates[*].transactionName` from inspect/validate results to group work by transaction and sampler order.
                - Use `plan.dynamicValueCandidates` as a structured checklist for hard-coded UUIDs, IDs, tokens, hashes, nonces, and epoch timestamps in requests that were not reached by validation.
                - For each dynamic value candidate, decide whether it has enough evidence for a live correlation edit, should become a runtime-generated value, or must be reported as a candidate needing source-response evidence.
                - Do not stop solely because the first failing request lacks a confirmed source response. Continue reviewing other requests and transactions, apply safe GUI-backed correlations/assertions/replacements where the source/target/literal/pattern is known, and include unresolved candidates in the audit.
                - Prefer regular expression extractors for dynamic values when a regex can robustly capture the value. Use boundary extractors when boundaries are clearer.
                - For extractors whose variable is required by later requests, set failOnNoMatch=true so missing correlations fail clearly.
                - Add meaningful response assertions as transactions become understandable. Replace literals with `${variable}` references, User Defined Variables, or runtime functions where appropriate.
                - Automatic redirects can hide the intermediate response that issues dynamic values. If a sampler's automatic redirects prevent seeing or extracting code/state/location/header values, adjust redirect settings in the open plan to make intermediate responses observable when safe.
                - Keep progress updates concise: report validations, edits, blockers, and audit results. Do not echo prompts, code, full XML, or long tool payloads.
                - Review all transactions, POST bodies, URL/query/path values, cookies, and headers for dynamic UUIDs, IDs, random strings, timestamps, csrf/requestverification tokens, and bearer/access tokens.
                - Detect millisecond epoch timestamps, typically 13-digit current-era values. Check whether they came from an earlier response and should be correlated, or whether they should be generated at runtime as the current epoch timestamp in milliseconds.
                - Prefer regex extractors for correlations. Use JSON extractors only when JSON-path extraction is more robust.
                - Replace identified username/password values with User Defined Variables near the top of the test plan.
                - Ensure every transaction has at least one meaningful assertion on text or response data that identifies the expected page, transaction, or API response.
                - Do not use weak single-word assertions unless the word is genuinely unique and scenario-significant. Prefer a significant sentence, phrase, HTML fragment, JSON field/value, or XML fragment. For example, after login assert on text only visible after successful login; after purchase assert on confirmation text such as "Thank you for your purchase".
                - Keep static browser assets parallel only when independent; keep dependent API/REST/XHR calls sequential.
                - Randomize scenario data where practical by selecting from prior responses instead of replaying fixed IDs.
                - Do not disable samplers merely to make the test green.

                Before finishing, call list_agent_changes_open_plan and provide:
                - an audit table with transaction name, assertion status, dynamic values reviewed, correlations added, and remaining blockers.
                - a concise change list matching the BreakTest AI Auto Scripting table.

                Context:
                - Open plan file: %s
                - Backup before live edits: %s
                - Selected node: %s
                %s
                """.formatted(
                testPlanFile == null ? "(unknown)" : testPlanFile,
                request.backupPath().isBlank() ? "(unknown)" : request.backupPath(),
                selectedNode == null ? "(unknown)" : selectedNode,
                userInstructionBlock(request)
        );
    }

    private static String userInstructionBlock(AiRunRequest request) {
        return """

                User-provided start dialog instructions:
                - Focus/scope: %s
                - Extra instructions:
                %s
                """.formatted(
                request.focus().isBlank() ? "(none provided)" : request.focus(),
                request.instructions().isBlank() ? "(none provided)" : indent(request.instructions())
        );
    }

    private static AiRunRequest showStartDialog(GuiPackage gui) {
        String selectedNode = null;
        if (gui != null && gui.getTreeListener() != null && gui.getTreeListener().getCurrentNode() != null) {
            selectedNode = gui.getTreeListener().getCurrentNode().getName();
        }
        String testPlanFile = gui != null ? gui.getTestPlanFile() : null;

        JTextField focus = new JTextField(selectedNode == null ? "" : selectedNode);
        JTextArea instructions = new JTextArea(8, 56);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);

        JPanel fields = new JPanel(new BorderLayout(0, 4));
        fields.add(new JLabel("Focus / scope"), BorderLayout.NORTH);
        fields.add(focus, BorderLayout.CENTER);

        JPanel instructionsPanel = new JPanel(new BorderLayout(0, 4));
        instructionsPanel.add(new JLabel("Extra instructions, things to focus on, or things to do"), BorderLayout.NORTH);
        instructionsPanel.add(
                new JScrollPane(
                        instructions,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                ),
                BorderLayout.CENTER
        );

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.add(fields, BorderLayout.NORTH);
        body.add(instructionsPanel, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.setPreferredSize(new Dimension(620, 310));
        panel.add(new JLabel("Plan: " + (testPlanFile == null ? "(unsaved)" : testPlanFile)), BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);

        int choice = JOptionPane.showOptionDialog(
                gui == null ? null : gui.getMainFrame(),
                panel,
                "Start AI Auto Scripting",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                new Object[] { "Start", "Cancel" },
                "Start"
        );
        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }
        return new AiRunRequest(focus.getText().trim(), instructions.getText().trim());
    }

    private static String indent(String text) {
        return "  " + text.replace("\r", "").replace("\n", "\n  ");
    }

    private static void streamOutput(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            CodexOutputFilter filter = new CodexOutputFilter();
            String line;
            while ((line = reader.readLine()) != null) {
                String display = filter.displayLine(line);
                if (display != null) {
                    postActivity("Codex: " + display);
                }
            }
        }
    }

    private static void postActivity(String message) {
        AiAutoScriptingLogWindow.append(message);
    }

    private static final class AiRunRequest {
        private final String focus;
        private final String instructions;
        private final String backupPath;

        private AiRunRequest(String focus, String instructions) {
            this(focus, instructions, "");
        }

        private AiRunRequest(String focus, String instructions, String backupPath) {
            this.focus = focus == null ? "" : focus;
            this.instructions = instructions == null ? "" : instructions;
            this.backupPath = backupPath == null ? "" : backupPath;
        }

        private AiRunRequest withBackupPath(String backupPath) {
            return new AiRunRequest(focus, instructions, backupPath);
        }

        private String focus() {
            return focus;
        }

        private String instructions() {
            return instructions;
        }

        private String backupPath() {
            return backupPath;
        }

        private boolean hasUserInput() {
            return !focus.isBlank() || !instructions.isBlank();
        }
    }

    private static class CodexOutputFilter {
        private boolean finalResponseStarted;
        private boolean skipNextTokenCount;
        private boolean suppressToolOutput;

        String displayLine(String line) {
            String display = null;
            String trimmed = line.trim();
            if (!line.isBlank()) {
                if (SHOW_RAW_CODEX_OUTPUT) {
                    display = line;
                } else if (isToolOutputBoundary(trimmed)) {
                    finalResponseStarted = false;
                    suppressToolOutput = true;
                } else if (trimmed.equals("codex")) {
                    finalResponseStarted = true;
                    suppressToolOutput = false;
                    display = "Final response:";
                } else if (trimmed.equals("tokens used")) {
                    skipNextTokenCount = true;
                } else if (skipNextTokenCount) {
                    skipNextTokenCount = false;
                } else if (!suppressToolOutput) {
                    display = displayFilteredLine(trimmed);
                }
            }
            return display;
        }

        private String displayFilteredLine(String trimmed) {
            String display = null;
            if (trimmed.startsWith("ERROR") || trimmed.startsWith("Error") || trimmed.contains("error:")) {
                display = trimmed;
            } else if (!trimmed.startsWith("WARN") && !trimmed.contains(" WARN ")
                    && finalResponseStarted
                    && !trimmed.startsWith("tokens used")
                    && !trimmed.matches("[0-9]+(\\.[0-9]+)?")) {
                display = trimmed;
            }
            return display;
        }

        private static boolean isToolOutputBoundary(String line) {
            return line.equals("exec")
                    || line.startsWith("exec ")
                    || line.startsWith("succeeded in ")
                    || line.startsWith("failed in ")
                    || line.startsWith("mcp:")
                    || line.startsWith("tool call ")
                    || line.startsWith("Mcp error:");
        }
    }
}
