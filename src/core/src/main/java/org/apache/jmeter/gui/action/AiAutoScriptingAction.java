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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultEditorKit;
import javax.swing.tree.TreePath;

import org.apache.jmeter.ai.gui.AiAutoScriptingLogWindow;
import org.apache.jmeter.ai.gui.BreakTestAgentGuiService;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.service.AutoService;

/**
 * Starts a local AI Auto Scripting session for the currently open BreakTest plan.
 */
@AutoService(Command.class)
public class AiAutoScriptingAction extends AbstractAction {
    private static final Logger log = LoggerFactory.getLogger(AiAutoScriptingAction.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<Process> CURRENT_PROCESS = new AtomicReference<>();
    private static final Set<String> COMMANDS;
    private static final boolean SHOW_RAW_OUTPUT =
            JMeterUtils.getPropDefault("breaktest.ai.show_raw_output",
                    JMeterUtils.getPropDefault("breaktest.codex.show_raw_output", false));

    static {
        Set<String> commands = new HashSet<>();
        commands.add(ActionNames.AI_AUTO_SCRIPTING);
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
        selectThreadGroup(gui, request.threadGroupNode());
        if (!RUNNING.compareAndSet(false, true)) {
            postActivity("AI Auto Scripting is already running.");
            return;
        }

        try {
            AiAutoScriptingLogWindow.showLog();
            AiAutoScriptingLogWindow.setStopHandler(AiAutoScriptingAction::stopCurrentRun);
            AiAutoScriptingLogWindow.startRun();
            BreakTestAgentGuiService.start();
            String backupPath = BreakTestAgentGuiService.createBackupForOpenPlan();
            String repairTargetPath = switch (request.editSurface()) {
                case NON_GUI -> BreakTestAgentGuiService.createRepairCloneForOpenPlan();
                case LIVE_GUI -> "";
            };
            AiRunRequest runRequest = request.withPaths(backupPath, repairTargetPath);

            Thread worker = new Thread(() -> runAiAutoScripting(runRequest), "BreakTest AI Auto Scripting");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception ex) {
            RUNNING.set(false);
            STOP_REQUESTED.set(false);
            CURRENT_PROCESS.set(null);
            AiAutoScriptingLogWindow.setStopHandler(null);
            log.warn("Could not start AI Auto Scripting", ex);
            postActivity("AI Auto Scripting failed to start: " + ex.getMessage());
            AiAutoScriptingLogWindow.finishRun("Failed to start");
        }
    }

    @Override
    public Set<String> getActionNames() {
        return COMMANDS;
    }

    private static void runAiAutoScripting(AiRunRequest request) {
        Instant started = Instant.now();
        AiRunOutput output = new AiRunOutput();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            File workingDirectory = aiWorkingDirectory(request.tool());
            List<String> command = aiCommand(request, workingDirectory);
            BreakTestAgentGuiService.setActiveAgentLabel(request.tool().displayName());
            postActivity("Starting AI Auto Scripting.");
            postActivity("AI tool: " + request.tool().displayName()
                    + " (dangerous local-agent settings approved in the start dialog).");
            postActivity(AiEngineDescription.describe(request.tool().id(), request.tool().displayName()));
            postActivity("Edit surface: " + request.editSurface().displayName());
            postActivity("Run limits: max runtime " + request.maxRuntimeSeconds()
                    + "s, similar retry limit " + request.maxSimilarRetries()
                    + ", add assertions " + (request.addAssertions() ? "yes" : "no") + ".");
            postActivity("AI telemetry: initial prompt " + payloadSize(prompt(request)));
            postActivity("Command: " + commandSummary(command));
            if (request.hasUserInput()) {
                String instructions = request.instructions().strip();
                if (instructions.length() > 1_500) {
                    instructions = instructions.substring(0, 1_500) + "... (truncated)";
                }
                postActivity("User instructions: " + instructions);
            }
            if (!request.repairTargetPath().isBlank()) {
                postActivity("Repair target: " + request.repairTargetPath());
                postActivity("Repair summary: " + repairSummaryPath(request));
            }
            postActivity("Working directory: " + workingDirectory.getPath());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            CURRENT_PROCESS.set(process);
            startTimeoutWatchdog(process, request, timedOut);
            if (STOP_REQUESTED.get()) {
                process.destroy();
            }
            process.getOutputStream().close();
            output = streamOutput(process.getInputStream(), request.tool());
            int exitCode = process.waitFor();
            boolean stopped = STOP_REQUESTED.get();
            if (timedOut.get()) {
                postActivity("AI Auto Scripting stopped after reaching the maximum runtime.");
                AiAutoScriptingLogWindow.finishRun("Timed out");
            } else if (stopped) {
                postActivity("AI Auto Scripting stopped by user.");
                AiAutoScriptingLogWindow.finishRun("Stopped");
            } else if (exitCode == 0) {
                boolean endedWithoutChanges = request.editSurface() == AiEditSurface.LIVE_GUI
                        && request.mode() == AiRunMode.FULL_SCRIPT_REPAIR
                        && AiAutoScriptingLogWindow.changes().isEmpty();
                if (output.hasRepairBlocker()) {
                    postActivity("AI Auto Scripting finished with blockers.");
                    AiAutoScriptingLogWindow.finishRun("Finished with blockers");
                } else if (endedWithoutChanges) {
                    // Weaker models sometimes reply with plain text mid-run; non-interactive
                    // CLIs (codex exec, opencode run) then exit 0 with nothing done.
                    postActivity("AI Auto Scripting ended without making any changes. "
                            + "The agent likely stopped early (a plain reply without a tool call ends the run); "
                            + "try again or use a stronger model/reasoning setting.");
                    AiAutoScriptingLogWindow.finishRun("Ended without changes");
                } else {
                    postActivity("AI Auto Scripting finished successfully.");
                    AiAutoScriptingLogWindow.finishRun("Finished successfully");
                }
            } else {
                postActivity("AI Auto Scripting exited with code " + exitCode + ".");
                AiAutoScriptingLogWindow.finishRun("Exited with code " + exitCode);
            }
            if (!stopped && !timedOut.get()) {
                importRepairSummary(request);
            }
            postCompletionSummary(request, exitCode, Duration.between(started, Instant.now()), output);
            if (!stopped && !timedOut.get()) {
                offerToLoadRepairClone(request, exitCode);
            }
        } catch (Exception ex) {
            if (timedOut.get()) {
                postActivity("AI Auto Scripting stopped after reaching the maximum runtime.");
                AiAutoScriptingLogWindow.finishRun("Timed out");
            } else if (STOP_REQUESTED.get()) {
                postActivity("AI Auto Scripting stopped by user.");
                AiAutoScriptingLogWindow.finishRun("Stopped");
            } else {
                log.warn("AI Auto Scripting failed", ex);
                postActivity("AI Auto Scripting failed: " + ex.getMessage());
                AiAutoScriptingLogWindow.finishRun("Failed");
            }
            postCompletionSummary(request, -1, Duration.between(started, Instant.now()), output);
        } finally {
            CURRENT_PROCESS.set(null);
            STOP_REQUESTED.set(false);
            RUNNING.set(false);
            AiAutoScriptingLogWindow.setStopHandler(null);
        }
    }

    private static void startTimeoutWatchdog(Process process, AiRunRequest request, AtomicBoolean timedOut) {
        Thread watchdog = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(request.maxRuntimeSeconds());
                if (process.isAlive() && CURRENT_PROCESS.get() == process) {
                    timedOut.set(true);
                    STOP_REQUESTED.set(true);
                    postActivity("Maximum AI Auto Scripting runtime reached ("
                            + request.maxRuntimeSeconds() + "s); stopping local AI process.");
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS) && process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "BreakTest AI Auto Scripting Timeout");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void stopCurrentRun() {
        if (!RUNNING.get()) {
            postActivity("No AI Auto Scripting run is active.");
            return;
        }
        if (!STOP_REQUESTED.compareAndSet(false, true)) {
            postActivity("AI Auto Scripting stop is already requested.");
            return;
        }
        postActivity("Stopping AI Auto Scripting...");
        AiAutoScriptingLogWindow.finishRun("Stopping...");
        Process process = CURRENT_PROCESS.get();
        if (process == null) {
            return;
        }
        process.destroy();
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS) && process.isAlive()) {
                    postActivity("AI Auto Scripting did not stop gracefully; forcing local AI process to exit.");
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }, "BreakTest AI Auto Scripting Stopper");
        killer.setDaemon(true);
        killer.start();
    }

    private static List<String> aiCommand(AiRunRequest request, File workingDirectory) {
        return switch (request.tool()) {
            case CODEX -> codexCommand(request, workingDirectory);
            case OPENCODE -> opencodeCommand(request, workingDirectory);
            case CLAUDE -> claudeCommand(request);
            case COPILOT -> copilotCommand(request, workingDirectory);
        };
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

        String model = modelProperty("breaktest.codex");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }

        command.add(prompt(request));
        return command;
    }

    private static List<String> opencodeCommand(AiRunRequest request, File workingDirectory) {
        List<String> command = new ArrayList<>();
        command.add(JMeterUtils.getPropDefault("breaktest.opencode.command", "opencode"));
        command.add("run");
        command.add("--dir");
        command.add(workingDirectory.getPath());
        command.add("--dangerously-skip-permissions");

        String model = modelProperty("breaktest.opencode");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }

        String agent = JMeterUtils.getProperty("breaktest.opencode.agent");
        if (agent != null && !agent.isBlank()) {
            command.add("--agent");
            command.add(agent);
        }

        command.add(prompt(request));
        return command;
    }

    private static List<String> claudeCommand(AiRunRequest request) {
        List<String> command = new ArrayList<>();
        command.add(JMeterUtils.getPropDefault("breaktest.claude.command", "claude"));
        command.add("--dangerously-skip-permissions");

        String model = modelProperty("breaktest.claude");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }

        String agent = JMeterUtils.getProperty("breaktest.claude.agent");
        if (agent != null && !agent.isBlank()) {
            command.add("--agent");
            command.add(agent);
        }

        String maxTurns = JMeterUtils.getProperty("breaktest.claude.max_turns");
        if (maxTurns != null && !maxTurns.isBlank()) {
            command.add("--max-turns");
            command.add(maxTurns);
        }

        command.add("-p");
        command.add(prompt(request));
        return command;
    }

    private static List<String> copilotCommand(AiRunRequest request, File workingDirectory) {
        List<String> command = new ArrayList<>();
        command.add(JMeterUtils.getPropDefault("breaktest.copilot.command", "copilot"));
        // Copilot CLI has no --cd flag; it uses the process working directory,
        // which the launcher already sets on the ProcessBuilder. --add-dir keeps
        // that directory trusted even when it sits outside the current repository.
        command.add("--add-dir");
        command.add(workingDirectory.getPath());
        command.add("--allow-all-tools");
        command.add("--allow-all-paths");
        command.add("--no-ask-user");
        if (!SHOW_RAW_OUTPUT) {
            // Suppress stats and decoration so the activity log sees plain agent
            // text. Live progress still arrives through the BreakTest bridge.
            command.add("-s");
        }

        String model = modelProperty("breaktest.copilot");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }

        String agent = JMeterUtils.getProperty("breaktest.copilot.agent");
        if (agent != null && !agent.isBlank()) {
            command.add("--agent");
            command.add(agent);
        }

        command.add("-p");
        command.add(prompt(request));
        return command;
    }

    private static String modelProperty(String prefix) {
        return JMeterUtils.getProperty(prefix + ".model");
    }

    private static String payloadSize(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        int approxTokens = Math.max(1, text.length() / 4);
        return humanBytes(bytes) + ", " + text.length() + " chars, approx " + approxTokens + " tokens";
    }

    private static String humanBytes(int bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static String repairSummaryPath(AiRunRequest request) {
        if (request.repairTargetPath().isBlank()) {
            return "";
        }
        return request.repairTargetPath() + ".ai-summary.json";
    }

    private static String commandSummary(List<String> command) {
        if (command.isEmpty()) {
            return "";
        }
        List<String> summary = new ArrayList<>(command);
        summary.set(summary.size() - 1, "[built-in prompt]");
        return String.join(" ", summary);
    }

    private static File aiWorkingDirectory(AiTool tool) {
        String configured = JMeterUtils.getProperty(tool.cwdProperty());
        if ((configured == null || configured.isBlank()) && tool != AiTool.CODEX) {
            configured = JMeterUtils.getProperty("breaktest.codex.cwd");
        }
        if (configured == null || configured.isBlank()) {
            configured = JMeterUtils.getProperty("breaktest.ai.cwd");
        }
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null && gui.getTestPlanFile() != null && !gui.getTestPlanFile().isBlank()) {
            File planDirectory = new File(gui.getTestPlanFile()).getAbsoluteFile().getParentFile();
            if (planDirectory != null && planDirectory.isDirectory()) {
                return planDirectory;
            }
        }
        File jmeterHome = new File(JMeterUtils.getJMeterHome());
        if (jmeterHome.isDirectory()) {
            return jmeterHome;
        }
        return new File(".").getAbsoluteFile();
    }

    private static String bridgeCommand() {
        String configured = JMeterUtils.getProperty("breaktest.agent.tool");
        if (configured != null && !configured.isBlank()) {
            return new File(configured).getAbsolutePath();
        }
        File candidate = new File(JMeterUtils.getJMeterHome(), "bin/breaktest-agent-tool");
        if (candidate.isFile()) {
            return candidate.getAbsolutePath();
        }
        return "breaktest-agent-tool";
    }

    private static String prompt(AiRunRequest request) {
        String configured = JMeterUtils.getProperty("breaktest.codex.prompt");
        if (configured != null && !configured.isBlank()) {
            return configured + userInstructionBlock(request);
        }
        GuiPackage gui = GuiPackage.getInstance();
        String testPlanFile = gui != null ? gui.getTestPlanFile() : null;
        if (request.editSurface().fileBacked()) {
            return fileBackedRepairPrompt(request, testPlanFile);
        }
        if (request.mode() == AiRunMode.SPECIFIC_REQUEST) {
            return specificRequestPrompt(request, testPlanFile);
        }
        return AiPrompts.render(AiPrompts.LIVE_GUI_REPAIR, Map.ofEntries(
                Map.entry("BRIDGE", bridgeCommand()),
                Map.entry("THREAD_GROUP_NAME", request.threadGroupName()),
                Map.entry("THREAD_GROUP_PATH", request.threadGroupPath()),
                Map.entry("RUN_OPTIONS", runOptionsInstruction(request)),
                Map.entry("TEST_PLAN_FILE", testPlanFile == null ? "(unknown)" : testPlanFile),
                Map.entry("BACKUP_PATH", orUnknown(request.backupPath())),
                Map.entry("USER_INSTRUCTIONS", userInstructionBlock(request))
        ));
    }

    private static String specificRequestPrompt(AiRunRequest request, String testPlanFile) {
        return AiPrompts.render(AiPrompts.SPECIFIC_REQUEST, Map.ofEntries(
                Map.entry("BRIDGE", bridgeCommand()),
                Map.entry("THREAD_GROUP_NAME", request.threadGroupName()),
                Map.entry("THREAD_GROUP_PATH", request.threadGroupPath()),
                Map.entry("TEST_PLAN_FILE", testPlanFile == null ? "(unknown)" : testPlanFile),
                Map.entry("BACKUP_PATH", orUnknown(request.backupPath())),
                Map.entry("USER_INSTRUCTIONS", userInstructionBlock(request))
        ));
    }

    private static String fileBackedRepairPrompt(AiRunRequest request, String testPlanFile) {
        String taskScope = AiPrompts.fragment(request.mode() == AiRunMode.SPECIFIC_REQUEST
                ? "taskScope.specificRequest"
                : "taskScope.fullRepair");
        return AiPrompts.render(AiPrompts.FILE_BACKED_REPAIR, Map.ofEntries(
                Map.entry("BRIDGE", bridgeCommand()),
                Map.entry("TASK_SCOPE", taskScope),
                Map.entry("THREAD_GROUP_NAME", request.threadGroupName()),
                Map.entry("THREAD_GROUP_PATH", request.threadGroupPath()),
                Map.entry("REPAIR_TARGET", orMissing(request.repairTargetPath(), "repair target path")),
                Map.entry("REPAIR_SUMMARY_PATH", orMissing(repairSummaryPath(request), "repair summary path")),
                Map.entry("RUN_OPTIONS", runOptionsInstruction(request)),
                Map.entry("TEST_PLAN_FILE", testPlanFile == null ? "(unknown)" : testPlanFile),
                Map.entry("BACKUP_PATH", orUnknown(request.backupPath())),
                Map.entry("USER_INSTRUCTIONS", userInstructionBlock(request))
        ));
    }

    private static String userInstructionBlock(AiRunRequest request) {
        return AiPrompts.render(AiPrompts.USER_INSTRUCTIONS, Map.ofEntries(
                Map.entry("TOOL", request.tool().displayName()),
                Map.entry("MODE", request.mode().displayName()),
                Map.entry("EDIT_SURFACE", request.editSurface().displayName()),
                Map.entry("ADD_ASSERTIONS", request.addAssertions() ? "yes" : "no"),
                Map.entry("MAX_RUNTIME_SECONDS", Integer.toString(request.maxRuntimeSeconds())),
                Map.entry("MAX_SIMILAR_RETRIES", Integer.toString(request.maxSimilarRetries())),
                Map.entry("THREAD_GROUP_NAME",
                        request.threadGroupName().isBlank() ? "(none selected)" : request.threadGroupName()),
                Map.entry("EXTRA_INSTRUCTIONS",
                        request.instructions().isBlank() ? "(none provided)" : indent(request.instructions()))
        ));
    }

    private static String runOptionsInstruction(AiRunRequest request) {
        return AiPrompts.render(AiPrompts.RUN_OPTIONS, Map.of(
                "MAX_RUNTIME_SECONDS", Integer.toString(request.maxRuntimeSeconds()),
                "MAX_SIMILAR_RETRIES", Integer.toString(request.maxSimilarRetries()),
                "ASSERTION_INSTRUCTION",
                AiPrompts.fragment(request.addAssertions() ? "assertions.enabled" : "assertions.disabled")
        ));
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "(unknown)" : value;
    }

    private static String orMissing(String value, String label) {
        return value == null || value.isBlank() ? "(missing " + label + ")" : value;
    }

    private static AiRunRequest showStartDialog(GuiPackage gui) {
        List<ThreadGroupChoice> threadGroups = enabledThreadGroups(gui);
        if (threadGroups.isEmpty()) {
            JOptionPane.showMessageDialog(
                    gui == null ? null : gui.getMainFrame(),
                    "No enabled Thread Groups are available for AI Auto Scripting (Beta).",
                    "Start AI Auto Scripting (Beta)",
                    JOptionPane.WARNING_MESSAGE
            );
            return null;
        }
        String testPlanFile = gui != null ? gui.getTestPlanFile() : null;

        JComboBox<AiTool> aiTool = new JComboBox<>(aiToolChoices());
        aiTool.setSelectedItem(defaultAiTool());

        JComboBox<ThreadGroupChoice> threadGroup = new JComboBox<>(
                threadGroups.toArray(new ThreadGroupChoice[0])
        );
        ThreadGroupChoice defaultThreadGroup = defaultThreadGroup(threadGroups, currentThreadGroupNode(gui));
        threadGroup.setSelectedItem(defaultThreadGroup);

        JRadioButton fullRepair = new JRadioButton("Full script repair", true);
        JRadioButton specificRequest = new JRadioButton("Specific request");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(fullRepair);
        modeGroup.add(specificRequest);
        JPanel modePanel = new JPanel(new BorderLayout(0, 4));
        modePanel.add(new JLabel("Mode"), BorderLayout.NORTH);
        JPanel modeChoices = new JPanel(new BorderLayout(0, 2));
        modeChoices.add(fullRepair, BorderLayout.NORTH);
        modeChoices.add(specificRequest, BorderLayout.CENTER);
        modePanel.add(modeChoices, BorderLayout.CENTER);

        JRadioButton liveGui = new JRadioButton("GUI mode", defaultEditSurface() == AiEditSurface.LIVE_GUI);
        JRadioButton nonGui = new JRadioButton("Non-GUI mode", defaultEditSurface() == AiEditSurface.NON_GUI);
        ButtonGroup surfaceGroup = new ButtonGroup();
        surfaceGroup.add(liveGui);
        surfaceGroup.add(nonGui);
        JPanel surfacePanel = new JPanel(new BorderLayout(0, 4));
        surfacePanel.add(new JLabel("Edit surface"), BorderLayout.NORTH);
        JPanel surfaceChoices = new JPanel(new GridLayout(0, 1, 0, 2));
        surfaceChoices.add(liveGui);
        surfaceChoices.add(nonGui);
        surfacePanel.add(surfaceChoices, BorderLayout.CENTER);

        JCheckBox addAssertions = new JCheckBox("Add assertions after repair succeeds", true);
        JTextField maxRuntimeSeconds = integerTextField("1800", 6);
        JTextField maxSimilarRetries = integerTextField("5", 3);
        JPanel limitsPanel = new JPanel(new BorderLayout(0, 4));
        limitsPanel.add(new JLabel("Repair options"), BorderLayout.NORTH);
        JPanel limitsFields = new JPanel(new GridLayout(0, 1, 0, 2));
        limitsFields.add(compactIntegerInputRow("Maximum runtime (seconds)", maxRuntimeSeconds));
        limitsFields.add(compactIntegerInputRow("Similar retry limit", maxSimilarRetries));
        limitsPanel.add(addAssertions, BorderLayout.CENTER);
        limitsPanel.add(limitsFields, BorderLayout.SOUTH);

        Runnable updateModeOptions = () -> addAssertions.setEnabled(fullRepair.isSelected());
        fullRepair.addActionListener(event -> updateModeOptions.run());
        specificRequest.addActionListener(event -> updateModeOptions.run());
        updateModeOptions.run();

        JTextArea instructions = new JTextArea(8, 56);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                DefaultEditorKit.insertBreakAction
        );

        JPanel fields = compactComboPanel("Thread group", threadGroup);
        JPanel toolPanel = compactComboPanel("AI tool", aiTool);

        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.add(toolPanel, BorderLayout.NORTH);
        top.add(fields, BorderLayout.CENTER);
        JPanel choicesPanel = new JPanel(new BorderLayout(0, 8));
        choicesPanel.add(modePanel, BorderLayout.NORTH);
        choicesPanel.add(surfacePanel, BorderLayout.CENTER);
        choicesPanel.add(limitsPanel, BorderLayout.SOUTH);
        top.add(choicesPanel, BorderLayout.SOUTH);

        JPanel instructionsPanel = new JPanel(new BorderLayout(0, 4));
        instructionsPanel.add(new JLabel("Add instructions"), BorderLayout.NORTH);
        JScrollPane instructionsScrollPane = new JScrollPane(
                instructions,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        instructionsScrollPane.setPreferredSize(new Dimension(720, 180));
        instructionsScrollPane.setMinimumSize(new Dimension(500, 140));
        instructionsPanel.add(instructionsScrollPane, BorderLayout.CENTER);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.add(top, BorderLayout.NORTH);
        body.add(instructionsPanel, BorderLayout.CENTER);

        JCheckBox dangerApproved = new JCheckBox(
                "I understand and approve running the selected local AI tool with dangerous auto-approval settings."
        );
        JPanel warningPanel = new JPanel(new BorderLayout(0, 6));
        warningPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Local agent permissions"),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)
        ));
        warningPanel.add(new JLabel(
                "<html><b>Warning:</b> AI Auto Scripting (Beta) starts a local coding agent that can edit the open plan "
                        + "and run tools with broad permissions. A backup is created first, but you are approving "
                        + "dangerous automation for this run.</html>"
        ), BorderLayout.CENTER);
        warningPanel.add(dangerApproved, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.setPreferredSize(new Dimension(780, 740));
        panel.add(startDialogHeader(gui, testPlanFile), BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        panel.add(warningPanel, BorderLayout.SOUTH);

        JButton startButton = new JButton("Start");
        JButton cancelButton = new JButton("Cancel");
        startButton.setEnabled(dangerApproved.isSelected());
        dangerApproved.addActionListener(event -> startButton.setEnabled(dangerApproved.isSelected()));

        JOptionPane optionPane = new JOptionPane(
                panel,
                JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION,
                null,
                new Object[] { startButton, cancelButton },
                startButton
        );
        JDialog dialog = optionPane.createDialog(
                gui == null ? null : gui.getMainFrame(),
                "Start AI Auto Scripting (Beta)"
        );
        startButton.addActionListener(event -> {
            try {
                parseIntegerField(maxRuntimeSeconds, "Maximum runtime", 60, 14400);
                parseIntegerField(maxSimilarRetries, "Similar retry limit", 0, 50);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(
                        dialog,
                        ex.getMessage(),
                        "Start AI Auto Scripting (Beta)",
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }
            optionPane.setValue(startButton);
            dialog.dispose();
        });
        cancelButton.addActionListener(event -> {
            optionPane.setValue(cancelButton);
            dialog.dispose();
        });
        dialog.setVisible(true);
        if (optionPane.getValue() != startButton) {
            return null;
        }
        AiTool selectedTool = (AiTool) aiTool.getSelectedItem();
        ThreadGroupChoice selectedThreadGroup = (ThreadGroupChoice) threadGroup.getSelectedItem();
        AiRunMode mode = specificRequest.isSelected() ? AiRunMode.SPECIFIC_REQUEST : AiRunMode.FULL_SCRIPT_REPAIR;
        AiEditSurface editSurface = liveGui.isSelected() ? AiEditSurface.LIVE_GUI : AiEditSurface.NON_GUI;
        String instructionText = instructions.getText().trim();
        if (mode == AiRunMode.SPECIFIC_REQUEST && instructionText.isBlank()) {
            JOptionPane.showMessageDialog(
                    gui == null ? null : gui.getMainFrame(),
                    "Add instructions for a specific request.",
                    "Start AI Auto Scripting (Beta)",
                    JOptionPane.WARNING_MESSAGE
            );
            return null;
        }
        return new AiRunRequest(
                selectedTool,
                selectedThreadGroup,
                mode,
                editSurface,
                mode == AiRunMode.FULL_SCRIPT_REPAIR && addAssertions.isSelected(),
                parseIntegerField(maxRuntimeSeconds, "Maximum runtime", 60, 14400),
                parseIntegerField(maxSimilarRetries, "Similar retry limit", 0, 50),
                instructionText
        );
    }

    private static JPanel startDialogHeader(GuiPackage gui, String testPlanFile) {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.add(new JLabel("Plan: " + (testPlanFile == null ? "(unsaved)" : testPlanFile)), BorderLayout.CENTER);
        JButton help = new JButton("?");
        help.setMargin(new Insets(1, 7, 1, 7));
        help.setToolTipText("AI Auto Scripting setup help");
        help.addActionListener(event -> showSetupHelpDialog(gui == null ? null : gui.getMainFrame()));
        header.add(help, BorderLayout.EAST);
        return header;
    }

    private static void showSetupHelpDialog(Component parent) {
        JTextArea helpText = new JTextArea(aiSetupHelpText(), 28, 84);
        helpText.setEditable(false);
        helpText.setLineWrap(true);
        helpText.setWrapStyleWord(true);
        helpText.setCaretPosition(0);

        JScrollPane scrollPane = new JScrollPane(
                helpText,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        scrollPane.setPreferredSize(new Dimension(760, 520));

        JOptionPane.showMessageDialog(
                parent,
                scrollPane,
                "AI Auto Scripting Setup",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private static String aiSetupHelpText() {
        try (InputStream input = AiAutoScriptingAction.class.getResourceAsStream("ai-auto-scripting-setup-help.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                String text = properties.getProperty("text");
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        } catch (IOException ignored) {
            // Use the fallback text below.
        }
        return """
                AI Auto Scripting (Beta)

                Codex and Claude Code are the preferred harnesses. OpenCode and Copilot CLI are available for experimentation.
                Configure manual Codex MCP with <BREAKTEST_HOME>/bin/breaktest-agent-mcp.
                """;
    }

    private static JTextField integerTextField(String value, int columns) {
        JTextField field = new JTextField(value, columns);
        field.setHorizontalAlignment(JTextField.RIGHT);
        field.setMaximumSize(field.getPreferredSize());
        return field;
    }

    private static JPanel compactIntegerInputRow(String label, JTextField field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.add(new JLabel(label));
        row.add(field);
        return row;
    }

    private static int parseIntegerField(JTextField field, String label, int min, int max) {
        String value = field.getText().trim();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a whole number.", ex);
        }
    }

    private static <T> JPanel compactComboPanel(String label, JComboBox<T> comboBox) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        JPanel comboWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        comboWrapper.add(comboBox);
        panel.add(comboWrapper, BorderLayout.CENTER);
        return panel;
    }

    private static AiTool defaultAiTool() {
        String configured = JMeterUtils.getPropDefault("breaktest.ai.tool", "codex");
        for (AiTool tool : AiTool.values()) {
            if (tool.id().equalsIgnoreCase(configured) || tool.displayName().equalsIgnoreCase(configured)) {
                return tool;
            }
        }
        return AiTool.CODEX;
    }

    private static AiEditSurface defaultEditSurface() {
        String configured = JMeterUtils.getPropDefault("breaktest.ai.edit_surface", "gui");
        String lower = configured.toLowerCase(Locale.ROOT);
        if (lower.contains("non") || lower.contains("clone") || lower.contains("file")) {
            return AiEditSurface.NON_GUI;
        }
        if (lower.contains("gui") || lower.contains("live")) {
            return AiEditSurface.LIVE_GUI;
        }
        for (AiEditSurface surface : AiEditSurface.values()) {
            if (surface.id().equalsIgnoreCase(configured) || surface.displayName().equalsIgnoreCase(configured)) {
                return surface;
            }
        }
        return AiEditSurface.LIVE_GUI;
    }

    private static List<ThreadGroupChoice> enabledThreadGroups(GuiPackage gui) {
        if (gui == null || gui.getTreeModel() == null) {
            return new ArrayList<>();
        }
        List<ThreadGroupChoice> choices = new ArrayList<>();
        List<JMeterTreeNode> nodes = gui.getTreeModel().getNodesOfType(AbstractThreadGroup.class);
        for (JMeterTreeNode node : nodes) {
            if (node.isEnabled()) {
                choices.add(new ThreadGroupChoice(node));
            }
        }
        return choices;
    }

    private static JMeterTreeNode currentThreadGroupNode(GuiPackage gui) {
        if (gui == null || gui.getTreeListener() == null) {
            return null;
        }
        JMeterTreeNode current = gui.getTreeListener().getCurrentNode();
        while (current != null) {
            if (current.getTestElement() instanceof AbstractThreadGroup && current.isEnabled()) {
                return current;
            }
            Object parent = current.getParent();
            current = parent instanceof JMeterTreeNode parentNode ? parentNode : null;
        }
        return null;
    }

    private static ThreadGroupChoice defaultThreadGroup(List<ThreadGroupChoice> choices, JMeterTreeNode activeThreadGroup) {
        if (activeThreadGroup != null) {
            for (ThreadGroupChoice choice : choices) {
                if (choice.node() == activeThreadGroup) {
                    return choice;
                }
            }
        }
        return choices.get(0);
    }

    private static void selectThreadGroup(GuiPackage gui, JMeterTreeNode threadGroupNode) {
        if (gui == null || threadGroupNode == null || gui.getMainFrame() == null) {
            return;
        }
        TreePath path = new TreePath(threadGroupNode.getPath());
        gui.getMainFrame().getTree().setSelectionPath(path);
        gui.getMainFrame().getTree().scrollPathToVisible(path);
    }

    private static String indent(String text) {
        return "  " + text.replace("\r", "").replace("\n", "\n  ");
    }

    private static AiRunOutput streamOutput(InputStream inputStream, AiTool tool) throws IOException {
        AiOutputFilter filter = new AiOutputFilter(tool);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String display = filter.displayLine(line);
                if (display != null) {
                    postActivity(tool.displayName() + ": " + display);
                }
            }
        }
        return filter.output();
    }

    private static void postActivity(String message) {
        AiAutoScriptingLogWindow.append(message);
    }

    private static void postCompletionSummary(AiRunRequest request, int exitCode, Duration elapsed, AiRunOutput output) {
        List<String> followUps = new ArrayList<>(output.followUpLines());
        postActivity("AI Auto Scripting summary:");
        postActivity("Status: " + completionStatus(exitCode, output));
        postActivity(AiEngineDescription.describe(request.tool().id(), request.tool().displayName()));
        postActivity("Total time: " + formatDuration(elapsed));
        postActivity("Token usage: input=" + output.inputTokensText()
                + ", output=" + output.outputTokensText()
                + ", total=" + output.totalTokensText());
        int changeCount = AiAutoScriptingLogWindow.changes().size();
        if (changeCount > 0) {
            postActivity("Recorded changes: " + changeCount + " (see the changes table)");
        }
        List<String> summaryLines = output.summaryLines();
        if (!summaryLines.isEmpty()) {
            postActivity("Summary:");
            for (String line : summaryLines) {
                postActivity("  - " + line);
            }
        }
        if (request.editSurface() == AiEditSurface.LIVE_GUI
                && request.mode() == AiRunMode.FULL_SCRIPT_REPAIR
                && !knowledgeUpdateObserved()) {
            followUps.add("BreakTest AI Knowledge was not updated during this full repair run.");
        }
        postActivity("Follow-up:");
        if (followUps.isEmpty()) {
            postActivity("  - none");
        } else {
            for (String line : followUps) {
                postActivity("  - " + line);
            }
        }
    }

    private static String completionStatus(int exitCode, AiRunOutput output) {
        if (exitCode != 0) {
            return "exit code " + exitCode;
        }
        return output.hasRepairBlocker() ? "completed with blockers" : "completed";
    }

    private static boolean knowledgeUpdateObserved() {
        for (Map<String, String> change : AiAutoScriptingLogWindow.changes()) {
            String type = change.getOrDefault("type", "");
            String summary = change.getOrDefault("summary", "");
            if ("Updated knowledge".equals(type) || summary.contains("AI scripting knowledge")) {
                return true;
            }
        }
        return false;
    }

    private static void importRepairSummary(AiRunRequest request) {
        if (!request.editSurface().fileBacked() || request.repairTargetPath().isBlank()) {
            return;
        }
        File summaryFile = new File(repairSummaryPath(request));
        if (!summaryFile.isFile()) {
            postActivity("Repair summary sidecar was not produced: " + summaryFile.getPath());
            return;
        }
        try {
            JsonNode root = JSON.readTree(summaryFile);
            JsonNode changes = root.path("changes");
            int importedChanges = 0;
            if (changes.isArray()) {
                for (JsonNode change : changes) {
                    String type = textOrDefault(change, "type", "Updated clone");
                    String node = firstText(change, "node", "nodeName", "nodePath");
                    if (node.isBlank()) {
                        node = "Repair target";
                    }
                    String summary = firstText(change, "summary", "change", "title");
                    if (summary.isBlank()) {
                        summary = type;
                    }
                    String details = firstText(change, "details", "evidence", "result");
                    AiAutoScriptingLogWindow.recordChange(type, node, summary, details, null);
                    importedChanges++;
                }
            }
            postActivity("Imported repair summary: " + importedChanges + " change(s) from "
                    + summaryFile.getPath());
        } catch (Exception ex) {
            log.warn("Could not import AI repair summary {}", summaryFile, ex);
            postActivity("Could not import repair summary: " + ex.getMessage());
        }
    }

    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText("");
        return text.isBlank() ? defaultValue : text;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static void offerToLoadRepairClone(AiRunRequest request, int exitCode) {
        if (request.editSurface() != AiEditSurface.NON_GUI || request.repairTargetPath().isBlank()) {
            return;
        }
        File repairClone = new File(request.repairTargetPath());
        if (!repairClone.isFile()) {
            postActivity("Repair clone was not found after the run: " + repairClone.getPath());
            return;
        }
        if (exitCode != 0) {
            postActivity("AI run exited with code " + exitCode
                    + "; repaired clone left on disk (not merged): " + repairClone.getPath());
            return;
        }
        // Merge automatically: the repaired Thread Group is added as *_AI_Generated
        // next to the original, so the user reviews the result in the tree instead
        // of answering a modal dialog after every non-GUI run.
        SwingUtilities.invokeLater(() -> {
            try {
                postActivity("Merging repaired Thread Group into open plan: " + repairClone.getPath());
                Map<String, Object> result = BreakTestAgentGuiService.mergeRepairCloneIntoOpenPlan(
                        repairClone.getPath(),
                        request.threadGroupPath()
                );
                postActivity("Merged repaired Thread Group: " + result.getOrDefault("threadGroupNodePath", "")
                        + " (original Thread Group unchanged; clone remains at " + repairClone.getPath() + ")");
            } catch (Exception ex) {
                log.warn("Could not merge AI repaired clone {}", repairClone, ex);
                postActivity("Could not merge repaired Thread Group: " + ex.getMessage()
                        + ". Clone left on disk: " + repairClone.getPath());
            }
        });
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private enum AiRunMode {
        FULL_SCRIPT_REPAIR("Full script repair"),
        SPECIFIC_REQUEST("Specific request");

        private final String displayName;

        AiRunMode(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }
    }

    private enum AiEditSurface {
        LIVE_GUI("gui", "GUI mode"),
        NON_GUI("non-gui", "Non-GUI mode");

        private final String id;
        private final String displayName;

        AiEditSurface(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        private String id() {
            return id;
        }

        private String displayName() {
            return displayName;
        }

        private boolean fileBacked() {
            return this == NON_GUI;
        }
    }

    private enum AiTool {
        CODEX("codex", "Codex", "breaktest.codex.cwd"),
        CLAUDE("claude", "Claude Code", "breaktest.claude.cwd"),
        OPENCODE("opencode", "opencode", "breaktest.opencode.cwd"),
        COPILOT("copilot", "Copilot CLI", "breaktest.copilot.cwd");

        private final String id;
        private final String displayName;
        private final String cwdProperty;

        AiTool(String id, String displayName, String cwdProperty) {
            this.id = id;
            this.displayName = displayName;
            this.cwdProperty = cwdProperty;
        }

        private String id() {
            return id;
        }

        private String displayName() {
            return displayName;
        }

        private String cwdProperty() {
            return cwdProperty;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private static AiTool[] aiToolChoices() {
        return new AiTool[] { AiTool.CODEX, AiTool.CLAUDE, AiTool.OPENCODE, AiTool.COPILOT };
    }

    private static final class AiRunRequest {
        private final AiTool tool;
        private final ThreadGroupChoice threadGroup;
        private final AiRunMode mode;
        private final AiEditSurface editSurface;
        private final boolean addAssertions;
        private final int maxRuntimeSeconds;
        private final int maxSimilarRetries;
        private final String instructions;
        private final String backupPath;
        private final String repairTargetPath;

        private AiRunRequest(
                AiTool tool,
                ThreadGroupChoice threadGroup,
                AiRunMode mode,
                AiEditSurface editSurface,
                boolean addAssertions,
                int maxRuntimeSeconds,
                int maxSimilarRetries,
                String instructions
        ) {
            this(tool, threadGroup, mode, editSurface, addAssertions, maxRuntimeSeconds, maxSimilarRetries, instructions,
                    "", "");
        }

        private AiRunRequest(
                AiTool tool,
                ThreadGroupChoice threadGroup,
                AiRunMode mode,
                AiEditSurface editSurface,
                boolean addAssertions,
                int maxRuntimeSeconds,
                int maxSimilarRetries,
                String instructions,
                String backupPath,
                String repairTargetPath
        ) {
            this.tool = tool == null ? AiTool.CODEX : tool;
            this.threadGroup = threadGroup;
            this.mode = mode == null ? AiRunMode.FULL_SCRIPT_REPAIR : mode;
            this.editSurface = editSurface == null ? AiEditSurface.LIVE_GUI : editSurface;
            this.addAssertions = addAssertions;
            this.maxRuntimeSeconds = Math.max(60, maxRuntimeSeconds);
            this.maxSimilarRetries = Math.max(0, maxSimilarRetries);
            this.instructions = instructions == null ? "" : instructions;
            this.backupPath = backupPath == null ? "" : backupPath;
            this.repairTargetPath = repairTargetPath == null ? "" : repairTargetPath;
        }

        private AiRunRequest withPaths(String backupPath, String repairTargetPath) {
            return new AiRunRequest(tool, threadGroup, mode, editSurface, addAssertions, maxRuntimeSeconds,
                    maxSimilarRetries, instructions, backupPath,
                    repairTargetPath);
        }

        private AiTool tool() {
            return tool;
        }

        private JMeterTreeNode threadGroupNode() {
            return threadGroup == null ? null : threadGroup.node();
        }

        private String threadGroupName() {
            return threadGroup == null ? "" : threadGroup.name();
        }

        private String threadGroupPath() {
            return threadGroup == null ? "" : threadGroup.path();
        }

        private AiRunMode mode() {
            return mode;
        }

        private AiEditSurface editSurface() {
            return editSurface;
        }

        private boolean addAssertions() {
            return addAssertions;
        }

        private int maxRuntimeSeconds() {
            return maxRuntimeSeconds;
        }

        private int maxSimilarRetries() {
            return maxSimilarRetries;
        }

        private String instructions() {
            return instructions;
        }

        private String backupPath() {
            return backupPath;
        }

        private String repairTargetPath() {
            return repairTargetPath;
        }

        private boolean hasUserInput() {
            return !instructions.isBlank();
        }
    }

    private static final class ThreadGroupChoice {
        private final JMeterTreeNode node;
        private final String name;
        private final String path;

        private ThreadGroupChoice(JMeterTreeNode node) {
            this.node = node;
            this.name = node.getName();
            this.path = treePath(node);
        }

        private JMeterTreeNode node() {
            return node;
        }

        private String name() {
            return name;
        }

        private String path() {
            return path;
        }

        @Override
        public String toString() {
            return name;
        }

        private static String treePath(JMeterTreeNode node) {
            Object[] nodes = node.getPath();
            List<String> names = new ArrayList<>();
            for (Object pathNode : nodes) {
                if (pathNode instanceof JMeterTreeNode treeNode) {
                    names.add(treeNode.getName());
                } else {
                    names.add(String.valueOf(pathNode));
                }
            }
            return String.join(" / ", names);
        }
    }

    private static class AiOutputFilter {
        private final AiTool tool;
        private boolean finalResponseStarted;
        private boolean skipNextTokenCount;
        private boolean suppressToolOutput;
        private boolean suppressDiffOutput;
        private final Set<String> displayedFinalLines = new HashSet<>();
        private final AiRunOutput output = new AiRunOutput();

        AiOutputFilter(AiTool tool) {
            this.tool = tool;
            // Codex marks its final response with a "codex" sentinel line; every
            // other CLI streams plain agent text from the first line onwards.
            this.finalResponseStarted = tool != AiTool.CODEX;
        }

        String displayLine(String rawLine) {
            String line = stripAnsi(rawLine);
            String display = null;
            String trimmed = line.trim();
            if (!line.isBlank()) {
                output.captureTokenLine(trimmed);
                if (SHOW_RAW_OUTPUT) {
                    display = line;
                } else if (shouldStartDiffSuppression(trimmed)) {
                    suppressDiffOutput = true;
                } else if (suppressDiffOutput && !shouldEndDiffSuppression(trimmed)) {
                    return null;
                } else if (suppressDiffOutput) {
                    suppressDiffOutput = false;
                    display = tool == AiTool.CODEX ? displayFilteredLine(trimmed) : displayPlainAgentLine(trimmed);
                } else if (isToolOutputBoundary(trimmed)) {
                    finalResponseStarted = false;
                    suppressToolOutput = true;
                } else if (trimmed.equals("codex")) {
                    finalResponseStarted = true;
                    suppressToolOutput = false;
                    output.startFinalResponseBlock();
                } else if (trimmed.equals("tokens used")) {
                    skipNextTokenCount = true;
                } else if (skipNextTokenCount) {
                    skipNextTokenCount = false;
                } else if (!suppressToolOutput) {
                    display = tool == AiTool.CODEX ? displayFilteredLine(trimmed) : displayPlainAgentLine(trimmed);
                }
            }
            // CLI agents such as opencode echo every shell command and its JSON
            // result into stdout. Those lines are neither useful in the activity
            // log (the GUI bridge already logs each tool call) nor valid "final
            // response" content for summary/follow-up extraction.
            if (display != null && looksLikeToolEcho(display)) {
                return null;
            }
            if (display != null && finalResponseStarted && isDuplicateFinalLine(display)) {
                return null;
            }
            if (display != null && finalResponseStarted) {
                display = normalizeFinalDisplayLine(display);
                if (display == null) {
                    return null;
                }
                output.captureFinalResponse(display);
            }
            return display;
        }

        private static String stripAnsi(String line) {
            return line.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").replace("\u001B", "");
        }

        private static boolean looksLikeToolEcho(String line) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            char first = trimmed.charAt(0);
            if (first == '{' || first == '}' || first == '[' || first == ']' || first == '$') {
                return true;
            }
            if (first == '"' && (trimmed.contains("\" : ") || trimmed.contains("\": ") || trimmed.endsWith(","))) {
                return true;
            }
            return trimmed.contains("breaktest-agent-tool ")
                    || trimmed.startsWith("\"required\"")
                    || trimmed.startsWith("},");
        }

        AiRunOutput output() {
            return output;
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

        private static String displayPlainAgentLine(String trimmed) {
            if (trimmed.startsWith("ERROR") || trimmed.startsWith("Error") || trimmed.contains("error:")) {
                return trimmed;
            }
            if (trimmed.startsWith("WARN") || trimmed.contains(" WARN ")) {
                return null;
            }
            return trimmed;
        }

        private boolean isDuplicateFinalLine(String display) {
            if (display.length() <= 16) {
                return false;
            }
            return !displayedFinalLines.add(display);
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

        private static boolean shouldStartDiffSuppression(String line) {
            return line.startsWith("diff --git ")
                    || line.startsWith("diff -- ")
                    || line.startsWith("--- a/")
                    || line.startsWith("+++ b/")
                    || line.startsWith("patch: completed");
        }

        private static boolean shouldEndDiffSuppression(String line) {
            return line.startsWith("Status:")
                    || line.startsWith("Change list:")
                    || line.startsWith("Audit:")
                    || line.startsWith("Summary:")
                    || line.startsWith("Repair complete")
                    || line.startsWith("Non-GUI repair")
                    || line.startsWith("Final bounded validation")
                    || line.startsWith("The summary JSON")
                    || line.startsWith("The JMX")
                    || line.startsWith("Saved repaired JMX")
                    || line.startsWith("Summary JSON written");
        }

        private static String normalizeFinalDisplayLine(String line) {
            String trimmed = line.trim();
            if (trimmed.matches("\\|?\\s*[-:| ]{3,}\\s*\\|?")) {
                return null;
            }
            if (trimmed.startsWith("|")) {
                List<String> cells = markdownCells(trimmed);
                if (cells.isEmpty() || cells.get(0).equalsIgnoreCase("transaction")) {
                    return null;
                }
                if (cells.size() >= 5) {
                    return cells.get(0)
                            + ": assertion=" + cells.get(1)
                            + "; reviewed=" + cells.get(2)
                            + "; fixes=" + cells.get(3)
                            + "; blockers=" + cells.get(4);
                }
                return String.join(" | ", cells);
            }
            String plain = trimmed.replace("`", "").replace("**", "");
            while (plain.startsWith("- ") || plain.startsWith("* ")) {
                plain = plain.substring(2).trim();
            }
            return plain.isBlank() ? null : plain;
        }

        private static List<String> markdownCells(String line) {
            String[] rawCells = line.split("\\|", -1);
            List<String> cells = new ArrayList<>();
            for (String rawCell : rawCells) {
                String cell = rawCell.trim().replace("`", "").replace("**", "");
                if (!cell.isBlank()) {
                    cells.add(cell);
                }
            }
            return cells;
        }
    }

    private static final class AiRunOutput {
        private static final int MAX_FOLLOW_UP_LINES = 4;
        private static final int MAX_SUMMARY_LINES = 5;
        private Long inputTokens;
        private Long outputTokens;
        private Long totalTokens;
        private boolean nextLineIsTotalTokens;
        private final List<String> finalResponseLines = new ArrayList<>();

        private void captureTokenLine(String line) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.equals("tokens used")) {
                nextLineIsTotalTokens = true;
                return;
            }
            if (nextLineIsTotalTokens) {
                parseTokenNumber(line).ifPresent(value -> totalTokens = value);
                nextLineIsTotalTokens = false;
                return;
            }
            if (lower.contains("input") && lower.contains("token")) {
                parseTokenNumber(line).ifPresent(value -> inputTokens = value);
            } else if ((lower.contains("output") || lower.contains("completion")) && lower.contains("token")) {
                parseTokenNumber(line).ifPresent(value -> outputTokens = value);
            } else if (lower.contains("total") && lower.contains("token")) {
                parseTokenNumber(line).ifPresent(value -> totalTokens = value);
            }
        }

        private static java.util.Optional<Long> parseTokenNumber(String line) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9][0-9.,]*)").matcher(line);
            Long found = null;
            while (matcher.find()) {
                String normalized = matcher.group(1).replace(".", "").replace(",", "");
                try {
                    found = Long.parseLong(normalized);
                } catch (NumberFormatException ignored) {
                    // Keep looking for another numeric token.
                }
            }
            return java.util.Optional.ofNullable(found);
        }

        private void startFinalResponseBlock() {
            finalResponseLines.clear();
        }

        private void captureFinalResponse(String line) {
            finalResponseLines.add(line);
        }

        private String inputTokensText() {
            return inputTokens == null ? "not reported" : String.valueOf(inputTokens);
        }

        private String outputTokensText() {
            return outputTokens == null ? "not reported" : String.valueOf(outputTokens);
        }

        private String totalTokensText() {
            return totalTokens == null ? "not reported" : String.valueOf(totalTokens);
        }

        private List<String> summaryLines() {
            List<String> summary = new ArrayList<>();
            for (String line : finalResponseLines) {
                if (summary.size() >= MAX_SUMMARY_LINES) {
                    break;
                }
                if (isMarkdownTableLine(line) || isMarkdownHeading(line)) {
                    continue;
                }
                String plain = plainText(line);
                String lower = plain.toLowerCase(Locale.ROOT);
                if (plain.isBlank()) {
                    continue;
                }
                if (lower.startsWith("repaired ")
                        || lower.startsWith("final validation")
                        || lower.contains("validates green")
                        || lower.contains("validation is green")
                        || lower.contains("green across")
                        || lower.contains("ai knowledge update succeeded")
                        || lower.contains("updated breaktest ai knowledge")
                        || lower.contains("updated ai scripting knowledge")) {
                    addDistinct(summary, plain);
                }
            }
            return summary;
        }

        private List<String> followUpLines() {
            List<String> followUpLines = new ArrayList<>();
            for (String line : finalResponseLines) {
                if (followUpLines.size() >= MAX_FOLLOW_UP_LINES) {
                    break;
                }
                String tableIssue = remainingBlockerFromTable(line, true);
                if (tableIssue != null) {
                    addDistinct(followUpLines, tableIssue);
                    continue;
                }
                if (isMarkdownTableLine(line)) {
                    continue;
                }
                String plain = plainText(line);
                String lower = plain.toLowerCase(Locale.ROOT);
                if (plain.isBlank() || reportsNoFollowUp(lower) || reportsSuccess(lower)) {
                    continue;
                }
                if (reportsRepairBlocker(lower)
                        || lower.contains("manual")
                        || lower.contains("could not")
                        || lower.contains("unresolved")) {
                    addDistinct(followUpLines, plain);
                }
            }
            return followUpLines;
        }

        private boolean hasRepairBlocker() {
            for (String line : finalResponseLines) {
                if (remainingBlockerFromTable(line, false) != null) {
                    return true;
                }
                if (isMarkdownTableLine(line)) {
                    continue;
                }
                String lower = plainText(line).toLowerCase(Locale.ROOT);
                if (reportsNoFollowUp(lower) || reportsSuccess(lower)) {
                    continue;
                }
                if (reportsRepairBlocker(lower)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean reportsRepairBlocker(String lower) {
            return lower.startsWith("status: blocked")
                    || lower.startsWith("status: failed")
                    || lower.contains("not fully green")
                    || lower.contains("validation is not fully green")
                    || lower.contains("validation remains blocked")
                    || lower.contains("validation remains")
                    || lower.contains("not validated past")
                    || lower.contains("not reached due")
                    || lower.contains("remaining blocker")
                    || lower.contains("unresolved blocker")
                    || lower.contains("gui bridge failure")
                    || lower.contains("could not be restored")
                    || lower.contains("could not be validated")
                    || lower.contains("could not restore")
                    || lower.contains("could not validate");
        }

        private static void addDistinct(List<String> lines, String line) {
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }

        private static boolean isMarkdownTableLine(String line) {
            String trimmed = line.trim();
            return trimmed.startsWith("|") || trimmed.matches("\\|?\\s*[-:| ]{3,}\\s*\\|?");
        }

        private static boolean isMarkdownHeading(String line) {
            return line.trim().matches("#{1,6}\\s+.*");
        }

        private static String plainText(String line) {
            String plain = line.trim()
                    .replace("`", "")
                    .replace("**", "");
            while (plain.startsWith("- ") || plain.startsWith("* ")) {
                plain = plain.substring(2).trim();
            }
            return plain;
        }

        private static boolean reportsNoFollowUp(String lower) {
            return lower.contains("none reported")
                    || lower.contains("no remaining blocker")
                    || lower.contains("remaining blockers: none")
                    || lower.contains("remaining blocker: none")
                    || lower.equals("none");
        }

        private static boolean reportsSuccess(String lower) {
            return lower.contains("final validation is green")
                    || lower.contains("validates green")
                    || lower.contains("green across")
                    || lower.contains("with no ignored static failures")
                    || lower.contains("remaining blockers |");
        }

        private static String remainingBlockerFromTable(String line, boolean includeResidualNotes) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                return null;
            }
            String[] rawCells = trimmed.split("\\|", -1);
            List<String> cells = new ArrayList<>();
            for (String rawCell : rawCells) {
                String cell = plainText(rawCell);
                if (!cell.isBlank()) {
                    cells.add(cell);
                }
            }
            if (cells.size() < 5) {
                return null;
            }
            String transaction = cells.get(0);
            String blocker = cells.get(cells.size() - 1);
            String lower = blocker.toLowerCase(Locale.ROOT);
            if (transaction.equalsIgnoreCase("transaction")
                    || lower.equals("remaining blockers")
                    || blocker.matches("[-: ]+")) {
                return null;
            }
            if (lower.equals("none")) {
                return null;
            }
            if (lower.startsWith("none;")) {
                String residual = blocker.substring(blocker.indexOf(';') + 1).trim();
                return includeResidualNotes && !residual.isBlank()
                        ? transaction + ": " + residual
                        : null;
            }
            if (isNonBlockingResidual(lower)) {
                return includeResidualNotes ? transaction + ": " + blocker : null;
            }
            return transaction + ": " + blocker;
        }

        private static boolean isNonBlockingResidual(String lower) {
            return lower.contains("low-confidence")
                    || lower.contains("noise")
                    || lower.contains("left unchanged");
        }
    }
}
