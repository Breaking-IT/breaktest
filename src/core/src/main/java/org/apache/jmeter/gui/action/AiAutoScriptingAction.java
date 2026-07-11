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
        return """
                Use $breaktest-jmeter-repair.

                Repair and harden the currently open BreakTest/JMeter GUI plan through the BreakTest MCP server.

                Setup:
                - This is a single non-interactive run: it ends permanently the first time you reply with plain text instead of a tool/command call. Never pause to think aloud or summarize between steps — post progress via agent_activity and keep calling tools until the work is complete. Only the final report may be a plain reply.
                - First call `{{BRIDGE}} agent_activity '{"level":"info","message":"Starting AI Auto Scripting"}'` so progress is visible in the BreakTest AI Auto Scripting window, and keep posting concise progress (validations, edits, blockers, audits). Do not echo prompts, code, full XML, or long tool payloads into progress or final text.
                - A GUI backup was created before this run; use it only as a rollback safety net. Keep the open plan as the live edit surface so changes stay visible in the GUI. Do not start a separate MCP server; when native MCP tools are unavailable use the supported bridge command: `{{BRIDGE}} <tool-name> '<json-arguments>'`. Call `{{BRIDGE}} tools` once before the first edit and read only the schemas you need; do not probe tools with dummy or missing-argument calls, and do not search the filesystem for a different bridge unless this exact command fails.
                - Scope every step to exactly one Thread Group path: `%s`. Pass threadGroupName or scopeNodePath on validation, search, replace, correlation, and audit calls so identically named transactions/samplers in other Thread Groups are never touched. Do not repair, validate, or edit other Thread Groups unless the selected one depends on a shared test-plan-level config element.
                - If a live edit tool fails, read its rollback fields before continuing. Failed planner actions are rolled back automatically. If an integrity failure says the pre-run backup was restored, stop using stale node IDs, re-inspect once, and continue only when the selected Thread Group is present. Use restore_open_plan_from_backup only when the bridge reports a live-tree integrity failure that was not already restored automatically; never use it as an ordinary retry mechanism.

                Targeting (prevents wrong-node edits):
                - Prefer stable node IDs. find_open_plan_nodes and every edit result return nodeId values that keep pointing at the same GUI node even after add/move/delete edits; pass sourceNodeId/targetNodeId to edit tools whenever available. Node paths are the fallback; flat sampler indexes drift after structural edits, so never reuse an index captured before an add/move/delete.
                - Edit results are self-verifying: they report where the node actually landed (extractorNodePath/extractorNodeId, assertionNodePath, targetAssertions, sourceExtractors, replacements, scopeNodePath). Read the result instead of calling find_open_plan_nodes to confirm each edit; re-locate nodes only when a result looks wrong.
                - Use find_open_plan_nodes (not broad inspect_open_plan) to locate nodes, duplicates, extractor/assertion metadata, and sampler/controller positions. If an edit produced a duplicate or misplaced node, delete only that node with delete_node_open_plan (deleteAllMatches=true only for intentional duplicate cleanup); never compensate with JSR223 scripts or broad literal replacement.

                Knowledge first:
                - Peek BreakTest AI Knowledge with get_ai_knowledge_open_plan createIfMissing=false. If non-default knowledge exists, post a concise agent_activity with nodePath, knowledgeNodeCount, and isDefaultKnowledge, then treat it as mandatory context: reuse confirmed correlations, variables, timestamps, and dependencies, and never repeat an approach it records as a confirmed limitation. If knowledge is missing/default, continue from runtime evidence and create it only at the end.

                Evidence and planning:
                - If linked HAR evidence is available, aim to one-shot the repair before the first validation: call plan_repair_actions_open_plan with threadGroupName="%s", includeStaticAssets=false, maxActions around 40, maxUnresolved around 25, contextChars around 80, includeApplyArguments=false. The planner already resolves each value's source (response body vs headers), request order, encoding variants, and conflicting literals; it emits only native Regex Extractor correlations. Apply the selected high-confidence actions in one apply_repair_actions_open_plan(snapshotId, actionIds) call, inspect any skipped_conflict/rolledBack results, then run a single bounded validation to confirm. Fall back to get_repair_actions_open_plan plus manual edits only when an action needs changes before applying. If no HAR is linked, run one bounded compact validation first and work from its analysis instead.
                - Validate with validate_open_plan using scopeNodePath set to the selected Thread Group path, compact=true, stopOnFirstFailure, maxSamples, includeDsl=false, compactSampleLimit around 20, compactBodyLimit around 1500. Compact results carry full request/response evidence only for the first failure and the samples just before it; samples whose outcome is unchanged from the previous run collapse to one-line unchanged entries, and green runs return light responseBodyPreview entries. For any snippet you need afterwards (assertion markers, correlation sources), use search_validated_response_open_plan against the cached last run instead of re-validating; do not rerun validation just to reshape evidence, and do not call inspect_open_plan repeatedly for the same failure. Use search_recorded_har_open_plan / get_recorded_har_exchange_open_plan / audit_recorded_har_correlations_open_plan only for targeted follow-up evidence.
                - Encoding: validation request evidence shows URL/HTML-encoded data (@ appears as %%40) while the plan stores raw field values. Replace and search tools automatically try encoded/decoded variants and report matchedLiteral/matchedEncodingVariant, so pass the literal you saw and read the result. For credentials and User Defined Variables, take the raw value from list_http_arguments_open_plan (or the plan property), not from encoded request data, and store the raw form in the variable; set alwaysEncode on the argument when the extracted raw value must be re-encoded at runtime.
                - If linked HAR response bodies are blank or omitted, do not pretend the HAR proved a source: use bounded validation evidence, keep the value as a User Defined Variable, or report it unresolved. Never add speculative extractors that overwrite scenario variables with NOT_FOUND.

                Repair loop (repeat until the selected flow is green or a real external blocker remains):
                - Work top to bottom. A failing login/callback/basket/payment/order request is often a symptom of earlier static data: before declaring it blocked, audit every reached non-static request up to and including the failure for dynamic path/query/body/cookie/header values, credentials, OAuth/OpenID state/code/nonce, csrf/request-verification and anti-forgery tokens, hidden form fields, redirect Location values, and missing extractor reuse. Treat custom headers (Authorization, X-CSRF-Token, x-* IDs/tokens/timestamps) as request data. Do not treat an upstream access_denied/401/403/500 as unfixable until that pre-failure audit is done.
                - Correlate from response evidence only. apply_regex_correlation_open_plan requires evidenceSource and evidence: validated_response when validate_open_plan returned the marker, recorded_response when a HAR tool returned it, ai_knowledge for a confirmed project pattern. Choose useField at creation time — headers immediately when the marker is in response headers, Location, or Set-Cookie. The tool updates an existing same-variable extractor under the source instead of duplicating (allowDuplicateExtractor=true only deliberately).
                - Prefer native extractors: Regex by default; JSONPath for JSON, CSS for HTML, XPath for XML/HTML when clearly more robust; never Boundary Extractors. JSR223 only when no native extractor, JMeter function, variable, or simple replacement can express the value — never for a single field capture, and never attached to the Test Plan, Thread Group, or a controller: Pre/PostProcessors belong on their exact sampler, sequenced setup logic in a JSR223 Sampler. To change an existing extractor use update_regex_extractor_open_plan (extractorMatchIndex/updateAllMatches for duplicates); to change an existing assertion use update_response_assertion_open_plan; never edit element internals via literal replacement, and never rebuild a native extractor as JSR223 just because it was misplaced — move or update it.
                - Set failOnNoMatch=true on extractors whose variable later requests need, so missing correlations fail loudly; leave it false only for optional extractors with a proven fallback. Use one capture group; avoid templates that can render `null`.
                - JMeter's Regex Extractor uses the ORO/Perl5 engine, not java.util.regex: \\Q...\\E quoting and lookbehind never match. Escape literal metacharacters with single backslashes. Build regexes against the exact evidence snippet you pass to the tool, including whether JSON uses quoted strings, numbers, arrays, or escaped/encoded text. Example for a quoted JSON field: `"pageId"\\s*:\\s*"([^"]+)"` only works when the evidence snippet literally contains `"pageId":"..."`; if the value is numeric, encoded, absent, or in headers, change the regex/evidence/useField instead. The apply/update tools validate every regex against that engine and require it to match the provided evidence snippet; when a call is rejected for this, fix the regex or fetch the correct evidence — do not retry with allowUnmatchedEvidence unless the evidence is intentionally partial.
                - After adding a correlation, replace every dependent occurrence of the stale literal (path, query, POST/form/raw body, headers, cookies, referers, callback args) with replace_literal_open_plan scopeNodePath="%s"; use a target sampler only when the literal is local to it. Element names are ignored by default (includeNames=true only if the user asked for renaming); clean stale IDs in names separately with replace_literal_in_names_open_plan scopeNodePath="%s" and a static `{variable}` placeholder. Never put `${...}` in element names.
                - Credentials (username/password/email in preFailureRequestCandidates): parameterize immediately — replace the request literal with `${variable}` using excludeUserDefinedVariables=true, set the top-level User Defined Variable to the original literal, verify with search_open_plan_values excludeUserDefinedVariables=true. For POST form credentials use list_http_arguments_open_plan first, then set_http_argument_value_open_plan with argumentIndex (alwaysEncode=false where needed).
                - UUIDs, IDs, tokens, drawId/expiry-style fields, hex hashes, opaque URL path segments, and epoch-millisecond timestamps default to correlation from earlier response evidence, not runtime generation — even when validation is green. A random-looking segment inside a request path is server-issued until proven otherwise: search validated and recorded responses for it before anything else. Generate at runtime only after response evidence is exhausted and client-side generation is proven (validated JS, AI Knowledge, or a reached clearly client-owned field). If a generated value causes 4xx, stop generating it and seed a UDV with the recorded value or mark it unresolved.
                - For runtime generation, prefer native JMeter functions over scripting: inline ${__UUID} for a UUID used in one field (the only valid form — never ${__UUID()} ), ${__time(,)} for epoch milliseconds, ${__Random(,,)} / ${__RandomString(,,)} for random data. Use a JSR223 setup sampler only when one generated value must be identical across several fields or requests (compile caching is enabled automatically); never use JSR223 for something a function or extractor can do.
                - Execution order: if a dependent request sends an unresolved variable while its extractor is correct, move the source sampler into deterministic order with move_node_open_plan (to leave a Parallel Controller, position before/after the controller node). Dependent API/XHR calls run sequentially; only independent static assets stay parallel.
                - Redirect modes can alter auth/payment behavior: use set_redirect_mode_open_plan only as a diagnostic after inspection proves a source value is hidden by redirect handling, post the reason, revalidate immediately, and revert before finishing if it produced no usable evidence.
                - Keep static browser assets enabled but out of analysis (includeStaticAssets=false everywhere unless an asset response is directly relevant). If the next blocker is a 404 on a css/js/mjs/map/image/font asset that nothing references, rerun with ignoreStaticAssetFailures=true, report ignoredStaticFailureCount, and never declare success only because static failures were ignored.
                - Do not stop at the first unresolvable failure: continue applying safe evidence-backed correlations, credential variables, and replacements elsewhere, and report unresolved candidates in the audit. Do not disable samplers to make the test green. Randomize scenario data from prior responses where practical.

                After the first green run:
                - Run audit_dynamic_request_values_open_plan with threadGroupName="%s". A green run only proves the current data happened to work: correlate, parameterize, generate, or explicitly document every high-confidence leftover (UUIDs, opaque IDs, tokens, hashes, nonces, timestamps). Verify replaced literals are gone with search_open_plan_values scopeNodePath="%s" (allowWholePlan=true only for an explicit global audit).
                %s
                - When adding assertions: pick each transaction's marker from its own sampler's validated response via search_validated_response_open_plan(samplerLabel=...), then add all assertions in a single add_response_assertions_open_plan call with targetNodeId/targetNodePath per item, then run one validation to verify. The add/update tools verify validated_response patterns against the target sampler's cached latest response and reject markers that only occur on a different sampler (the error names where it was found) — fix the target or the marker, never bypass with allowUnverifiedPattern. Pass evidenceSource/evidence per assertion; static inference needs allowStaticInference=true. Prefer significant phrases, HTML/JSON fragments, or post-login/confirmation text over weak single words; if a pattern is rejected as weak, find a stronger marker instead of using allowWeakPattern. Do not assert on samplers that validation never reached unless a recorded response or AI Knowledge proves the marker — document the gap instead. If one assertion fails, fix or remove only that one.

                Finish (mandatory even if validation never became fully green):
                - The first final-report line must be exactly `Status: completed` when the selected flow is fully validated, or `Status: blocked` when any validation, GUI, recovery, credential, permission, or unresolved-correlation blocker remains.
                - Update AI Knowledge with update_ai_knowledge_open_plan appendLearnings: pass only the new entries per array field (projectHints, correlationPatterns, variableMappings, knownDynamicFields, timestampRules, transactionDependencies, learnedFromThreadGroups) — confirmed fixes, confirmed limitations, and unresolved high-confidence patterns with the Thread Group and transaction/request where each was observed. The GUI merges them server-side, so do not fetch and resend the whole document. If there were no reusable learnings, still append a learnedFromThreadGroups entry with "noReusableLearnings": true.
                - Call list_agent_changes_open_plan, then report in plain text only (the BreakTest AI log is a plain Swing text area, no markdown tables): a per-transaction audit list (assertion status, dynamic values reviewed, correlations added, remaining blockers), a concise change list matching the BreakTest table, and the AI Knowledge update summary.

                Context:
                - Open plan file: %s
                - Backup before live edits: %s
                - Selected Thread Group: %s
                - Selected Thread Group path: %s
                %s
                """.replace("{{BRIDGE}}", bridgeCommand()).formatted(
                request.threadGroupPath(),
                request.threadGroupName(),
                request.threadGroupPath(),
                request.threadGroupPath(),
                request.threadGroupName(),
                request.threadGroupPath(),
                runOptionsInstruction(request),
                testPlanFile == null ? "(unknown)" : testPlanFile,
                request.backupPath().isBlank() ? "(unknown)" : request.backupPath(),
                request.threadGroupName(),
                request.threadGroupPath(),
                userInstructionBlock(request)
        );
    }

    private static String specificRequestPrompt(AiRunRequest request, String testPlanFile) {
        return """
                Use $breaktest-jmeter-repair.

                Complete a specific targeted task against the currently open BreakTest/JMeter GUI plan.

                Requirements:
                - This is a single non-interactive run: it ends permanently the first time you reply with plain text instead of a tool/command call. Keep calling tools until the task is complete; only the final report may be a plain reply.
                - First call agent_activity so progress is visible in the BreakTest AI Auto Scripting window.
                - A GUI backup is created before this run starts. Use it only as a rollback safety net.
                - Use the supported BreakTest agent bridge command when native MCP tools are unavailable: `{{BRIDGE}} <tool-name> '<json-arguments>'`.
                - Before the first edit, call `{{BRIDGE}} tools` to inspect exact bridge tool schemas. Read only the schemas needed for the next action, and do not echo the full tool output back into progress or final text. Do not search the filesystem for a different bridge unless this exact command fails.
                - Scope the work to exactly one Thread Group path: `%s`.
                - Do only the user-requested task, with the fewest tool calls that complete it correctly. Do not start a full script repair, full correlation pass, assertion pass, dynamic audit, or validation loop unless the request explicitly asks for it. Do not call plan_repair_actions_open_plan, audit_dynamic_request_values_open_plan, or broad inspect_open_plan for a targeted task: locate nodes with find_open_plan_nodes and evidence with search_open_plan_values / search_validated_response_open_plan / search_recorded_har_open_plan.
                - Keep the open BreakTest test plan as the live edit surface so changes are visible in the GUI.
                - Use GUI-backed edit tools where available, including replace_literal_open_plan, replace_literal_in_names_open_plan, search_open_plan_values, list_http_arguments_open_plan, set_http_argument_value_open_plan, add_response_assertion_open_plan, update_response_assertion_open_plan, add_jsr223_open_plan, set_redirect_mode_open_plan, move_node_open_plan, delete_node_open_plan, and move_think_times_to_transactions_open_plan.
                - When the specific request is to move separate ThinkTime timers to transaction level, call move_think_times_to_transactions_open_plan with the selected threadGroupName. Do not inspect code internals or try broad literal replacement for TransactionController delay fields.
                - For request data, variables use `${variable}`. For sampler/controller/transaction names, never use `${...}`; use a display-safe `{variable}` placeholder or another static name.
                - The only valid inline UUID function form in this BreakTest/JMeter build is `${__UUID}`. Never use `${__UUID()}` or `${__UUID(name)}`. For a one-off client-generated UUID in a request field, prefer inline `${__UUID}`; use JSR223/setup only when the same generated UUID must be reused across multiple request fields or transformed before use.
                - When adding response assertions or correlation extractors, provide evidenceSource and evidence. Do not add assertions/extractors for responses that were not reached unless a recorded response or confirmed AI Knowledge entry proves the marker.
                - Prefer native extractors for simple response/header/body captures: Regex by default, JSONPath for robust JSON field/array extraction, CSS selectors for HTML when CSS selection is more robust, and XPath for XML/HTML when path selection is more robust. Do not use Boundary Extractors. Do not add a JSR223 PostProcessor that reads prev.getResponseDataAsString() or prev.getResponseHeaders() when a native Regex/JSONPath/CSS/XPath extractor can capture the value. JSR223 PreProcessors, PostProcessors, and Assertions must be attached to a specific sampler, never directly under a Test Plan, Thread Group, or transaction/controller. Use a JSR223 Sampler for setup logic that should run once in sequence.
                - JMeter Regex Extractors use the ORO/Perl5 engine, not java.util.regex. Do not use \\Q...\\E, lookbehind, named groups, or Java-only constructs. Escape literal metacharacters with single backslashes and make the regex match the exact evidence snippet you provide; for JSON, first verify whether the field is a quoted string, number, array, escaped value, or absent. If the bridge rejects the regex/evidence pair, fix the regex or fetch the correct body/header evidence instead of bypassing with allowUnmatchedEvidence.
                - For credential parameterization, preserve the original username/password literal, replace request data with `${variable}` first, then set the User Defined Variable to the original literal. When using broad replace/search for credentials, pass excludeUserDefinedVariables=true so the Test Plan variables table is not self-replaced.
                - When renaming stale UUIDs or IDs in element names after request data has been parameterized, use replace_literal_in_names_open_plan so request data is not changed to `{variable}` by mistake.
                - When using replace_literal_open_plan, replace_literal_in_names_open_plan, apply_regex_correlation_open_plan without a target sampler, or search_open_plan_values, pass threadGroupName or scopeNodePath for the selected Thread Group. Use allowWholePlan=true only when the user explicitly requested a global edit.
                - Do NOT run validate_open_plan or validate_jmx unless the user's instructions explicitly ask to validate, test, or run the script. Edit tool results are self-verifying (they report the resolved nodes, replacements, and extractor placement), and regex/assertion edits are checked against evidence at apply time — for a typical targeted change, confirm via the edit results plus one search_open_plan_values check and finish. If validation is explicitly requested, run one bounded compact validate_open_plan with scopeNodePath set to the selected Thread Group path, stopOnFirstFailure, and includeDsl=false.
                - Update BreakTest AI Knowledge only when the task produced a clearly reusable project pattern, using one small appendLearnings call; skip it otherwise.
                - Keep progress concise. Do not echo prompts, code, full XML, or long tool payloads.
                - Before finishing, call list_agent_changes_open_plan and summarize only the changes made for this specific request.
                - Use plain text only in the final response. Do not use markdown tables because the BreakTest AI log is a plain Swing text area.

                Context:
                - Open plan file: %s
                - Backup before live edits: %s
                - Selected Thread Group: %s
                - Selected Thread Group path: %s
                %s
                """.replace("{{BRIDGE}}", bridgeCommand()).formatted(
                request.threadGroupPath(),
                testPlanFile == null ? "(unknown)" : testPlanFile,
                request.backupPath().isBlank() ? "(unknown)" : request.backupPath(),
                request.threadGroupName(),
                request.threadGroupPath(),
                userInstructionBlock(request)
        );
    }

    private static String fileBackedRepairPrompt(AiRunRequest request, String testPlanFile) {
        String taskScope = request.mode() == AiRunMode.SPECIFIC_REQUEST
                ? "Complete only the specific user-requested task against the repair target file."
                : "Repair and harden the selected Thread Group in the repair target file.";
        String workflowDescription = """
                This is the native BreakTest non-GUI repair workflow. The open GUI plan is first saved as a \
                separate repair JMX that includes the current in-memory GUI state, including unsaved edits. \
                The GUI remains unchanged while the AI edits the repair copy.""";
        String targetDescription = "Edit only this repair copy: `%s`. Do not edit or reload the currently open GUI plan.";
        String finishInstruction = "- Provide the repaired JMX copy path. The BreakTest launcher will ask the user whether to load it after the run.\n";
        return """
                Use $breaktest-jmeter-repair.

                %s

                Requirements:
                - First call agent_activity so progress is visible in the BreakTest AI Auto Scripting window.
                - %s
                - %s
                - Keep the backup file unchanged. The original open GUI plan remains unchanged during non-GUI repair.
                - Do not use sibling backup/repaired/exported JMX files as repair evidence or as a source to transplant nodes unless the user explicitly asks to compare or reuse another file. Treat only the selected repair target, linked HAR evidence, validation evidence, and AI Knowledge as authoritative.
                - Use direct JMX/XML or structured file editing on the repair target when needed. Prefer robust XML/property-aware edits over brittle text-only changes.
                - Use the supported BreakTest agent bridge command for compact evidence and validation: `{{BRIDGE}} <tool-name> '<json-arguments>'`.
                - Before the first edit, call `{{BRIDGE}} tools` and read only the schemas needed for file inspection, linked HAR evidence, validation, and progress. Do not echo the full tools output.
                - You may use inspect_jmx and validate_jmx against the repair target. Use includeDsl=false and includeStaticAssets=false by default; request raw DSL or static assets only for a specific local edit/correlation question and keep limits small.
                - You may use read-only open-plan context tools against the original GUI plan, especially get_ai_knowledge_open_plan with createIfMissing=false, plan_repair_actions_open_plan, get_repair_actions_open_plan, get_repair_action_open_plan, list_recorded_har_exchanges_open_plan, get_recorded_har_exchange_open_plan, search_recorded_har_open_plan, audit_recorded_har_correlations_open_plan, and agent_activity.
                - Do not use open-plan edit tools such as replace_literal_open_plan, apply_regex_correlation_open_plan, add_response_assertion_open_plan, set_user_defined_variable_open_plan, move_node_open_plan, or delete_node_open_plan because those would edit the live GUI plan outside the file-backed repair flow. Translate any useful open-plan action evidence into structured edits on the repair target file.
                - Broad search/replace must be scoped to the selected Thread Group subtree. The only allowed edits outside that subtree are top-level User Defined Variables, shared config elements that the selected Thread Group directly uses, BreakTest AI Knowledge, and the optional summary sidecar. Do not perform whole-JMX replacement of credentials, UUIDs, IDs, tokens, paths, headers, or names.
                - Scope the repair to exactly one Thread Group path from the original GUI selection: `%s`.
                - Validate only the selected Thread Group scope. If validate_jmx supports a threadGroupName or threadGroupPath argument, pass it. If it does not, create a temporary validation-only copy of the target with other enabled Thread Groups disabled, validate that copy, and keep all real edits in the repair target. Do not chase failures from backup, duplicate, or out-of-scope Thread Groups.
                - If the target file contains BreakTest AI Knowledge, read and update it in the target file with reusable learnings from this run.
                - For full script repair, work top-to-bottom through the selected Thread Group. Audit paths, query strings, POST/form/raw bodies, cookies, and custom headers for UUIDs, IDs, random strings, timestamps, credentials, CSRF/request-verification tokens, bearer/access tokens, nonce/state, draw IDs, date/time fields, and long opaque values.
                - If linked HAR evidence is available, use it before the first validation run. Call plan_repair_actions_open_plan for the selected Thread Group once with includeStaticAssets=false, maxActions around 40, maxUnresolved around 25, contextChars around 80, and includeApplyArguments=false. Fetch details only for high-confidence actions you intend to apply; use get_repair_actions_open_plan for multiple selected action IDs instead of many one-action calls. Do not rerun the planner unless the first result is stale or incomplete. Batch proven credential variables, token/state/code/nonce/csrf/request-verification correlations, path/body/header replacements, and static name cleanup into the target file before the first validate_jmx.
                - If linked HAR response bodies are omitted or blank, do not treat matching recorded request values as extractor evidence. Keep scenario values as named User Defined Variables, wait for validation response evidence, or record the candidate as unresolved. Do not add speculative extractors that overwrite variables with NOT_FOUND.
                - If no useful linked HAR evidence is available, run exactly one bounded compact validate_jmx before repair. Then analyze request/response evidence up to the first failure, ignore static assets, identify high-correlation-potential path/query/body/header/cookie values, search earlier validation responses by field name and literal, and batch all evidence-backed correlations/parameterizations before rerunning.
                - The repair loop is: inspect JMX plus HAR/AI Knowledge evidence; batch high-confidence static/HAR edits; validate the target once; analyze the compact first-failure evidence; batch the next set of evidence-backed edits; rerun; repeat until the selected Thread Group validates through the flow or a real external blocker remains. Do not rerun validation merely to reshape, filter, or summarize the same evidence.
                - For a specific request, do only that request and avoid a broad repair pass unless the user explicitly asked for it.
                - Prefer native extractors for correlations. Use Regex Extractors by default; use JSONPath when the source is JSON and JSON-path extraction is more robust than regex; use CSS selectors for HTML when CSS selection is more robust; use XPath for XML/HTML when path selection is more robust. Do not use Boundary Extractors. Do not use a JSR223 PostProcessor that reads `prev.getResponseDataAsString()` or `prev.getResponseHeaders()` for a value that a native Regex/JSONPath/CSS/XPath extractor can capture.
                - Only add extractors from evidence: validated response, recorded response, or existing AI Knowledge. If a response was not reached and no recorded response/knowledge proves the marker, document the gap instead of inventing a node.
                - Choose the extractor field at creation time from the evidence location. If the marker is in response headers, Location, Set-Cookie, or another response header, create/update the extractor with useField=headers immediately instead of first trying body.
                - Before adding a Regex Extractor, inspect the intended source sampler for an existing extractor with the same variableName. If it exists, update it instead of adding a duplicate unless the duplicate is deliberate and explained in the change summary.
                - The only valid inline UUID function form in this BreakTest/JMeter build is `${__UUID}`. Never use `${__UUID()}` or `${__UUID(name)}`. UUIDs should be correlated first. Generate a UUID only after earlier response/header evidence is exhausted and validated HTML/JavaScript, AI Knowledge, or reached request semantics prove it is client-generated. For a one-off client-generated UUID in a request field, prefer inline `${__UUID}`; use JSR223/setup only when the same generated UUID must be reused across multiple request fields or transformed before use. Do not generate UUIDs for unreached later requests just because they look client-like.
                - JavaScript evidence such as `crypto.randomUUID()` proves the browser can generate UUIDs, not that a server endpoint accepts any random UUID. If a generated UUID causes a 404/400, stop generating it; keep the value as a named User Defined Variable seeded with the recorded literal or mark it unresolved until a source response is proven.
                - Never put `${...}` variable references in sampler/controller/transaction/assertion/extractor names. Use static display placeholders such as `{ticket_id}` in names.
                - Preserve original credential literal values in User Defined Variables and replace request data with variable references. Do not self-replace the variable values.
                - When a regex extractor supports multiple response shapes, design it with one capture group or a template that cannot emit `null`. Avoid `$1$$2$` style templates unless this BreakTest/JMeter runtime is known not to render unmatched groups as `null`. Do not replace a simple native Regex/JSONPath extractor with JSR223 just because the first attempt had the wrong field, sampler parent, regex, or JSON path; correct, move, or re-add the native extractor by stable sourceNodePath first.
                - JMeter Regex Extractors use the ORO/Perl5 engine, not java.util.regex. Do not use \\Q...\\E, lookbehind, named groups, or Java-only constructs. Escape literal metacharacters with single backslashes and make the regex match the exact validated/recorded response snippet you used as evidence. For JSON fields, verify whether the field is a quoted string, number, array, escaped value, encoded value, or absent before choosing a regex; use JSONPath instead when that is more robust. If a bridge tool rejects a regex/evidence pair, fix the regex or fetch the correct body/header evidence instead of bypassing with allowUnmatchedEvidence.
                - Do not add new response assertions during the repair/correlation phase while validation is still blocked. If the assertion phase is enabled, immediately after the first green flow, do one compact dynamic request audit. If that audit finds evidence-backed leftovers, apply those hardening edits and add one meaningful assertion per transaction in the same edit batch, then run one final validate_jmx. Do not run a separate validation between post-green hardening and assertion insertion unless the hardening changes credentials/auth, redirect behavior, sampler execution order, or the source evidence is uncertain. Use assertion snippets from the latest green validation response evidence whenever possible; do not rerun validation only to collect snippets unless that evidence lacks safe markers. Assertions must be meaningful and unique to the expected response; avoid weak single words and generic strings that could match an error page.
                - If a response assertion fails, update or remove only that failing assertion. Do not remove all AI-created assertions just because one marker was too strict. If a required extractor has failOnNoMatch=true, do not add another assertion merely to prove the extractor matched.
                - Keep static browser assets enabled and runnable, but keep them out of model-facing analysis by default. Use includeStaticAssets=true only when a stylesheet, JavaScript, image, font, favicon, or source-map response is directly relevant to a failure or suspected correlation source.
                - After replacing hard-coded values with extracted variables, re-check Parallel Controllers. Token/API/XHR samplers that depend on extracted data should run sequentially before their dependents; keep only independent static browser assets in parallel.
                - Validate in bounded runs against the repair target with validate_jmx using path exactly `%s`, compact=true, maxSamples, stopOnFirstFailure, includeDsl=false, compactSampleLimit around 20, compactBodyLimit around 1500, and tight responseBodyLimit/requestBodyLimit values. Never call validate_jmx without the path argument. Increase scope only after blockers are understood. Do not rerun validation merely to reshape, filter, or summarize the same evidence; save the validation JSON locally or use local jq/scripts on the previous output instead.
                - If the next blocker is a static browser asset such as css/js/mjs/map/image/font returning 404 and no current HTML/response evidence references that exact asset URL, keep the sampler enabled but rerun functional validation with ignoreStaticAssetFailures=true so repair can continue to the next non-static request. Report ignoredStaticFailureCount and do not declare the script repaired only because static failures were ignored.
                - Before declaring a login/callback/payment/basket/order failure blocked, use validate_jmx `preFailureDynamicCandidates`, `preFailureRequestCandidates`, `validation.evidenceSamples`, and the successful samples before `analysis.firstFailure` to audit every enabled non-static request up to and including the failing sampler. Check path, query, POST/form/raw body, headers, cookies, redirect `Location`, hidden fields, `state`, `code`, nonce/correlation cookies, csrf/request-verification values, credentials, and missing extractor reuse.
                - Do not disable samplers merely to make validation green.
                - Keep progress concise. Do not echo prompts, code, full XML, patch diffs, JSON sidecar diffs, raw summary JSON, or long tool payloads.
                %s

                Before finishing:
                - Save all edits to the repair target path.
                - Write a machine-readable repair summary JSON to `%s`. Use this shape: {"status":"green|blocked","validation":{"green":true|false,"transactions":["..."],"blockers":["..."]},"changes":[{"type":"Added extractor","node":"sampler or node path","summary":"short change","details":"short evidence/result"}],"audit":[{"transaction":"...","assertionStatus":"...","dynamicValuesReviewed":"...","correlationsAdded":"...","remainingBlockers":"..."}],"followUps":["..."]}. Keep values short; do not include secrets beyond variable names or long raw response bodies.
                %s
                - Provide a plain-text audit list with transaction name, assertion status, dynamic values reviewed, correlations added, and remaining blockers.
                - Provide a concise change list of added/updated variables, extractors, assertions, samplers, headers, bodies, paths, and names.
                - State whether bounded validation of the repair target is green or still blocked.
                - Use plain text only in the final response. Do not use markdown tables because the BreakTest AI log is a plain Swing text area.

                Context:
                - Open plan file: %s
                - Backup before run: %s
                - Repair target to edit: %s
                - Selected Thread Group: %s
                - Selected Thread Group path: %s
                %s
                """.replace("{{BRIDGE}}", bridgeCommand()).formatted(
                taskScope,
                workflowDescription,
                targetDescription.formatted(
                        request.repairTargetPath().isBlank() ? "(missing repair target path)" : request.repairTargetPath()),
                request.threadGroupPath(),
                repairSummaryPath(request).isBlank() ? "(missing repair summary path)" : repairSummaryPath(request),
                request.repairTargetPath().isBlank() ? "(missing repair target path)" : request.repairTargetPath(),
                finishInstruction,
                runOptionsInstruction(request),
                testPlanFile == null ? "(unknown)" : testPlanFile,
                request.backupPath().isBlank() ? "(unknown)" : request.backupPath(),
                request.repairTargetPath().isBlank() ? "(missing repair target path)" : request.repairTargetPath(),
                request.threadGroupName(),
                request.threadGroupPath(),
                userInstructionBlock(request)
        );
    }

    private static String userInstructionBlock(AiRunRequest request) {
        return """

                User-provided start dialog instructions:
                - AI Tool: %s
                - Mode: %s
                - Edit surface: %s
                - Add assertions: %s
                - Maximum runtime seconds: %d
                - Similar retry limit: %d
                - Thread Group: %s
                - Extra instructions:
                %s
                """.formatted(
                request.tool().displayName(),
                request.mode().displayName(),
                request.editSurface().displayName(),
                request.addAssertions() ? "yes" : "no",
                request.maxRuntimeSeconds(),
                request.maxSimilarRetries(),
                request.threadGroupName().isBlank() ? "(none selected)" : request.threadGroupName(),
                request.instructions().isBlank() ? "(none provided)" : indent(request.instructions())
        );
    }

    private static String runOptionsInstruction(AiRunRequest request) {
        String assertionInstruction = request.addAssertions()
                ? """
                        - Assertion phase is enabled. Add meaningful response assertions only after the selected flow is green \
                        and high-confidence dynamic leftovers are resolved or documented."""
                : """
                        - Assertion phase is disabled by the start dialog. Skip adding or updating Response Assertions unless \
                        the user explicitly asks for assertions in the extra instructions. Keep failOnNoMatch=true for required \
                        extractors because that is correlation failure detection, not a transaction assertion.""";
        return """
                - Start-dialog run budget: finish or report blockers before %d seconds. Do not repeat a similar failed validation/edit loop more than %d times; if the same task fails again, summarize the blocker and move to other safe candidates or stop.
                %s
                - When a required extractor has failOnNoMatch=true, do not add a separate Response Assertion solely to prove that extractor matched.
                """.formatted(
                request.maxRuntimeSeconds(),
                request.maxSimilarRetries(),
                assertionInstruction
        );
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

                Codex and Claude Code are the preferred harnesses. OpenCode is available for experimentation.
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
        OPENCODE("opencode", "opencode", "breaktest.opencode.cwd");

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
        return new AiTool[] { AiTool.CODEX, AiTool.CLAUDE, AiTool.OPENCODE };
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
            this.finalResponseStarted = tool == AiTool.OPENCODE || tool == AiTool.CLAUDE;
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
