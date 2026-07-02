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
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
 * Starts a local Codex repair session for the currently open BreakTest plan.
 */
@AutoService(Command.class)
public class CodexAiRepair extends AbstractAction {
    private static final Logger log = LoggerFactory.getLogger(CodexAiRepair.class);
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
            AiAutoScriptingLogWindow.setStopHandler(CodexAiRepair::stopCurrentRun);
            AiAutoScriptingLogWindow.startRun();
            BreakTestAgentGuiService.start();
            String backupPath = BreakTestAgentGuiService.createBackupForOpenPlan();
            String repairTargetPath = switch (request.editSurface()) {
                case NON_GUI -> BreakTestAgentGuiService.createRepairCloneForOpenPlan();
                case LIVE_GUI -> "";
            };
            AiRunRequest runRequest = request.withPaths(backupPath, repairTargetPath);

            Thread worker = new Thread(() -> runCodex(runRequest), "BreakTest AI Auto Scripting");
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

    private static void runCodex(AiRunRequest request) {
        Instant started = Instant.now();
        CodexRunOutput output = new CodexRunOutput();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            File workingDirectory = aiWorkingDirectory(request.tool());
            List<String> command = aiCommand(request, workingDirectory);
            postActivity("Starting AI Auto Scripting.");
            postActivity("AI tool: " + request.tool().displayName()
                    + " (dangerous local-agent settings approved in the start dialog).");
            postActivity("Edit surface: " + request.editSurface().displayName());
            postActivity("Run limits: max runtime " + request.maxRuntimeSeconds()
                    + "s, similar retry limit " + request.maxSimilarRetries()
                    + ", add assertions " + (request.addAssertions() ? "yes" : "no") + ".");
            postActivity("AI telemetry: initial prompt " + payloadSize(prompt(request)));
            postActivity("Command: " + commandSummary(command));
            if (request.hasUserInput()) {
                postActivity("Included extra user instructions from the start dialog.");
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
                if (output.hasRepairBlocker()) {
                    postActivity("AI Auto Scripting finished with blockers.");
                    AiAutoScriptingLogWindow.finishRun("Finished with blockers");
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

                Requirements:
                - First call agent_activity so progress is visible in the BreakTest AI Auto Scripting window.
                - A GUI backup is created before this run starts. Use it only as a rollback safety net.
                - Use the supported BreakTest agent bridge command when native MCP tools are unavailable: `{{BRIDGE}} <tool-name> '<json-arguments>'`.
                - Before the first edit, call `{{BRIDGE}} tools` to inspect exact bridge tool schemas. Read only the schemas needed for the next action, and do not echo the full tool output back into progress or final text. Do not make intentional missing-argument or dummy-target calls to discover argument names. Do not search the filesystem for a different bridge unless this exact command fails.
                - Prefer GUI-backed MCP tools: inspect_open_plan, validate_open_plan, agent_activity, get_ai_knowledge_open_plan, update_ai_knowledge_open_plan, list_agent_changes_open_plan, find_open_plan_nodes, plan_repair_actions_open_plan, get_repair_actions_open_plan, get_repair_action_open_plan, audit_recorded_har_correlations_open_plan, list_recorded_har_exchanges_open_plan, get_recorded_har_exchange_open_plan, search_recorded_har_open_plan, and open-plan edit tools.
                - Use live open-plan edit tools for supported changes: apply_regex_correlation_open_plan, update_regex_extractor_open_plan, replace_literal_open_plan, replace_literal_in_names_open_plan, set_user_defined_variable_open_plan, list_http_arguments_open_plan, set_http_argument_encode_open_plan, set_http_argument_value_open_plan, search_open_plan_values, audit_dynamic_request_values_open_plan, add_jsr223_open_plan, add_response_assertion_open_plan, update_response_assertion_open_plan, set_redirect_mode_open_plan, move_node_open_plan, delete_node_open_plan, and move_think_times_to_transactions_open_plan.
                - The GUI edit tools create elements through BreakTest/JMeter GUI components. If a live edit tool fails, report it with agent_activity and continue with other safe GUI-backed edits.
                - First call `{{BRIDGE}} agent_activity '{"level":"info","message":"Starting AI Auto Scripting"}'` so progress is visible in the BreakTest AI Auto Scripting window.
                - Keep the open BreakTest test plan as the live edit surface so changes are visible in the GUI.
                - Do not start a separate MCP server by hand. Use the supported BreakTest agent bridge command for validation, inspection, and edits.
                - Peek existing BreakTest AI Knowledge with get_ai_knowledge_open_plan createIfMissing=false. Do not create a missing/default AI Knowledge node at the start. If non-default knowledge exists, post a concise agent_activity message with nodePath, knowledgeNodeCount, and isDefaultKnowledge, then reuse confirmed project patterns. If knowledge is missing/default, continue with runtime evidence and create/update knowledge only before finishing.
                - Treat non-default BreakTest AI Knowledge as mandatory first context. Reuse applicable confirmed project patterns for correlations, assertions, variables, timestamps, dependencies, and randomization before inventing a fresh approach.
                - If AI Knowledge records a confirmed limitation from an earlier run, such as an encoded HTTP argument issue or a failed inline Groovy expression, do not repeat the same failed approach. Use the dedicated bridge tool for that limitation or choose the previously validated fallback.
                - Before finishing every full repair run, update BreakTest AI Knowledge with update_ai_knowledge_open_plan. This is mandatory even if validation did not become fully green.
                - Preserve existing BreakTest AI Knowledge arrays and append only reusable run learnings. Record confirmed fixes, confirmed limitations, and unresolved high-confidence patterns that future runs should know about, including the selected Thread Group and transaction/request where each pattern was observed.
                - If there were no reusable learnings, still append a learnedFromThreadGroups entry for this run with the selected Thread Group, reviewed scope, and "noReusableLearnings": true. Never finish a full repair with BreakTest AI Knowledge unchanged from the start.
                - Store recurring dynamic field names such as drawId, expiredDateTime, ticket path UUIDs, transactionId, basketItemId, csrf/request-verification tokens, and credential fields under knownDynamicFields/timestampRules/correlationPatterns as appropriate, with status such as confirmed, generated, correlated, or unresolved.
                - If linked HAR evidence is known to be available, call plan_repair_actions_open_plan with threadGroupName="%s", includeStaticAssets=false, maxActions around 40, maxUnresolved around 25, contextChars around 80, and includeApplyArguments=false before the first validation. Review the compact ranked actions first. Fetch full details for multiple selected high-confidence actions with get_repair_actions_open_plan; use get_repair_action_open_plan only for a single follow-up action.
                - If linked HAR response bodies are omitted or blank, do not pretend the HAR proved an extractor source. Use the next bounded validation response for runtime evidence, keep the value as a User Defined Variable, or report it as unresolved. Do not add speculative extractors that overwrite scenario variables with NOT_FOUND.
                - If no HAR is linked or known, do not call plan_repair_actions_open_plan before the first validation. Run one bounded compact validation first, then use analysis.validationRepairActions, preFailureRequestCandidates, preFailureDynamicCandidates, and evidenceSamples to correlate as many high-confidence reached values as possible before rerunning.
                - Use audit_recorded_har_correlations_open_plan, search_recorded_har_open_plan, get_recorded_har_exchange_open_plan, and broad inspect_open_plan only for targeted follow-up evidence when the compact validation/action planner is insufficient. Do not call inspect_open_plan repeatedly just to understand the same failure.
                - Use find_open_plan_nodes instead of broad inspect_open_plan when you only need node paths, duplicate counts, extractor metadata, assertion nodes, JSR223 processors, or sampler/controller locations. This is the preferred way to locate misplaced or duplicate nodes after an edit.
                - Follow this repair loop in order when no HAR is available: initial bounded validation; ignore static resources for analysis; inspect reached non-static requests up to the first failure; identify high-correlation-potential values in paths, query strings, POST/form/raw bodies, cookies, and headers; search earlier validation responses by field name and literal; add extractors/replacements for all values with evidence; rerun; repeat until the selected Thread Group validates through the flow or a real external blocker remains.
                - After the first green flow, run audit_dynamic_request_values_open_plan and harden high-confidence leftovers before adding assertions. If the assertion phase is enabled, add response assertions only after the flow is green and high-confidence dynamic leftovers have been resolved or explicitly documented.
                - Scope the repair to exactly one Thread Group path: `%s`.
                - Do not repair, validate for broad fixes, add assertions, or change samplers in other Thread Groups unless the selected Thread Group directly depends on a shared test-plan-level config element.
                - Work from top to bottom through the selected Thread Group. For each transaction, review the previous transaction's extracted values before blaming the current failing request.
                - A failing callback, payment, basket, or order request can be a symptom of earlier static data. Check upstream requests first for stale login state, redirect/session/correlation cookies, request verification tokens, anti-forgery values, product/ticket/order IDs, and timestamps.
                - Keep inspection and validation compact for local agents. Call inspect_open_plan and validate_open_plan with includeDsl=false and includeStaticAssets=false unless you need raw DSL or static assets for a specific edit/correlation question; if DSL is needed, request the smallest dslCharacterLimit that can answer the question.
                - Validate first with `validate_open_plan` using scopeNodePath set to the selected Thread Group path, compact=true, maxSamples, stopOnFirstFailure, includeDsl=false, compactSampleLimit around 20, compactBodyLimit around 1500, and tight responseBodyLimit/requestBodyLimit values. Do not validate enabled backup/duplicate Thread Groups during selected Thread Group repair. Do not rerun validation merely to reshape or summarize the same evidence; use the compact result's reachedSampleSummary, evidenceSamples, firstFailure, validationRepairActions, preFailureDynamicCandidates, and preFailureRequestCandidates for the first repair analysis.
                - If the next blocker is a static browser asset such as css/js/mjs/map/image/font returning 404 and no current HTML/response evidence references that exact asset URL, keep the sampler enabled but rerun functional validation with ignoreStaticAssetFailures=true so repair can continue to the next non-static request. Report ignoredStaticFailureCount and do not declare the script repaired only because static failures were ignored.
                - A first validation failure is a starting point, not the full scope. If validation blocks early, continue by inspecting the whole open plan and auditing later transactions statically.
                - Before declaring a login/callback/payment/basket/order failure blocked, perform and report a pre-failure dependency audit using `preFailureDynamicCandidates`, `preFailureRequestCandidates`, `validation.evidenceSamples`, and the successful samples before `analysis.firstFailure` from validate_open_plan. Review every enabled non-static sampler up to and including the failing sampler for dynamic path/query/body/header/cookie values, credentials, OAuth/OpenID state/code/nonce, and missing extractor reuse.
                - For callback/auth failures, inspect the successful authorize/login/resume samples before the callback. Check whether `state`, `code`, nonce, correlation cookies, csrf/request-verification values, redirect `Location`, or hidden form fields were issued in an earlier response or header. If redirects hide those values, consider a diagnostic `set_redirect_mode_open_plan` only with a posted reason and immediate revalidation.
                - Do not treat an upstream `access_denied`, 401, 403, or 500 as proof that no correlation is possible until the pre-failure dependency audit has checked all previous successful response bodies and headers for the failed request's tokens and suspicious request literals.
                - If `preFailureRequestCandidates` reports username, password, login, or email values, immediately parameterize them: preserve the original literal, replace request data with `${variable}`, set the top-level User Defined Variable to the original literal, and verify stale request literals are gone with excludeUserDefinedVariables=true before continuing auth/callback repair.
                - Use `plan.samplers[*].transactionName` and `plan.dynamicValueCandidates[*].transactionName` from inspect/validate results to group work by transaction and sampler order.
                - Use `plan.dynamicValueCandidates` and `audit_dynamic_request_values_open_plan` as structured checklists for hard-coded UUIDs, IDs, tokens, hashes, nonces, and epoch timestamps in requests that were not reached by validation.
                - Build a request inventory for the selected Thread Group before broad edits. For every enabled non-static sampler, review method, path, query string, POST/form/multipart/raw body, cookies, and request headers.
                - For each request surface, check for high-correlation-potential values: UUID/GUIDs, numeric IDs, selected scenario data, random/fuzzy strings, long opaque values, nonces, state values, hashes, current/future/past dates, formatted date-times, epoch seconds/milliseconds, usernames, passwords, emails, bearer/access/refresh tokens, CSRF/request-verification tokens, and custom token/security headers.
                - Treat custom headers as request data, not metadata. Audit headers such as Authorization, X-CSRF-Token, X-Request-Verification-Token, X-Requested-With, X-Api-Key, nonce/state/correlation/request IDs, and any application-specific `x-*` header carrying an ID, token, timestamp, or random-looking value.
                - Classify each suspicious value as server-issued correlation, client-generated runtime data, user/config variable, randomized scenario data, or safe static constant. Do not ignore a value merely because the current validation run is green.
                - For each dynamic value candidate, decide whether it has enough evidence for a live correlation edit, should become a runtime-generated value, or must be reported as a candidate needing source-response evidence. The default for UUIDs, IDs, timestamps, draw IDs, and tokens is correlation from earlier response evidence, not runtime generation.
                - When a recorded literal can occur in multiple places, such as path, query string, POST form argument, raw body, header, referer, cookie, callback URL, or nested child element, use replace_literal_open_plan with scopeNodePath="%s" for the selected Thread Group. Do not run whole-plan replacement when backup/duplicate Thread Groups exist unless the user explicitly asks. Element names are ignored by default; do not set includeNames=true unless the user explicitly asks for renaming. Use a target sampler only when the literal is known to be local to that sampler.
                - When request data replacement leaves an old dynamic literal in sampler/controller names, use replace_literal_in_names_open_plan with scopeNodePath="%s" for the selected Thread Group and a display-safe replacement like `{basket_page_id}`. Do not put `${...}` in element names.
                - After adding a correlation extractor, replace every dependent occurrence of the old literal, not only the first query/path occurrence. Re-inspect the plan and verify no stale copies remain in POST data, headers, or callback args.
                - Do not stop solely because the first failing request lacks a confirmed source response. Continue reviewing other requests and transactions, apply safe GUI-backed correlations, credential variables, and replacements where source/target/literal/pattern is known, and include unresolved candidates in the audit.
                - Prefer native extractors for dynamic values: Regex Extractors by default, JSONPath extractors for JSON fields/arrays when JSON-path extraction is more robust, CSS selectors for HTML when CSS selection is more robust, and XPath for XML/HTML when path selection is more robust. Do not use Boundary Extractors. Do not replace a simple native extractor with JSR223 merely because the first extractor was attached to the wrong sampler or used the wrong field; instead update, move, delete/re-add, or recreate the native extractor with stable sourceNodePath/targetNodePath from find_open_plan_nodes.
                - Only add extractors from response evidence. For apply_regex_correlation_open_plan, pass evidenceSource and evidence. Use evidenceSource=validated_response only when validate_open_plan returned the source sampler response containing the marker; use recorded_response only when get_recorded_har_exchange_open_plan or search_recorded_har_open_plan returned the recorded response body/header containing the marker; use ai_knowledge only for a previously confirmed project pattern. Do not invent extractors from request literals alone.
                - Choose the extractor field at creation time from the evidence location. If the marker is in response headers, Location, Set-Cookie, or another response header, create/update the extractor with useField=headers immediately instead of first trying body.
                - Before adding a Regex Extractor, use find_open_plan_nodes for the intended source sampler and variableName. If the variable already exists under that source, update it instead of adding a duplicate unless you have a deliberate reason and pass allowDuplicateExtractor=true.
                - If a later sampler was not reached in validation and no recorded response or AI Knowledge proves the response marker, do not add a Response Assertion to it. Document the assertion gap in the final audit instead.
                - When adding assertions, pass evidenceSource and evidence. Prefer evidenceSource=validated_response with the exact response snippet from validate_open_plan. Static request/plan inference is not a valid success assertion unless the user explicitly requested a static edit and you mark it with allowStaticInference=true.
                - For extractors whose variable is required by later requests, enable "Assertion error when not matched" by setting failOnNoMatch=true so missing correlations fail the run clearly. Leave it false only for truly optional extractors with a proven safe fallback.
                - Do not add new response assertions during the repair/correlation phase while validation is still blocked. If the assertion phase is enabled, after the selected Thread Group validates successfully through the intended flow and the dynamic audit is clean or documented, add one meaningful assertion per transaction using the latest green validation response evidence, then rerun validation to verify the assertions. Do not rerun validation only to collect snippets unless the latest compact evidence lacks safe markers.
                - Automatic redirects can hide the intermediate response that issues dynamic values, but redirect mode changes can also alter auth/payment behavior. Do not change redirects as generic hardening. Only use set_redirect_mode_open_plan after inspection proves a needed source value is hidden by redirect handling, post the reason with agent_activity, and revalidate immediately so the change can be treated as a possible regression. If the diagnostic redirect change does not produce usable source evidence, revert it before finishing unless the user explicitly asked to keep redirects disabled.
                - Keep progress updates concise: report validations, edits, blockers, and audit results. Do not echo prompts, code, full XML, or long tool payloads.
                - Review all transactions, POST bodies, URL/query/path values, cookies, and headers for dynamic UUIDs, IDs, random strings, timestamps, csrf/requestverification tokens, and bearer/access tokens.
                - Call audit_dynamic_request_values_open_plan with threadGroupName="%s" before finishing, even after a green validation run. A successful run only proves the current data happened to work; it does not prove recorded UUIDs, long opaque IDs, tokens, or timestamps are safe to replay. Correlate, parameterize, generate, or explicitly report every high-confidence leftover candidate.
                - Keep static browser assets enabled and runnable, but keep them out of model-facing analysis by default. Use includeStaticAssets=true only when a stylesheet, JavaScript, image, font, favicon, or source-map response is directly relevant to a failure or suspected correlation source.
                - UUIDs in request fields such as transactionId, basketItemId, cartItemId, clientId, correlationId, or requestId might be server-issued, selected from prior data, or browser-generated. First search earlier reached responses and headers for the literal or surrounding field. Only generate a UUID at runtime after response evidence is exhausted and either validated HTML/JavaScript shows client-side generation, AI Knowledge confirms this project pattern, or the request field is reached and clearly client-owned. Do not generate UUIDs for unreached later requests merely because they look client-like; report them as unresolved until source evidence exists.
                - JavaScript evidence such as `crypto.randomUUID()` proves the browser can generate UUIDs, not that every UUID path/header/body value accepts any random UUID. If a generated UUID causes a 404/400 on a server endpoint, stop generating it; keep it as a named User Defined Variable seeded with the recorded value or mark it unresolved until a server/source response is proven.
                - Detect millisecond epoch timestamps, typically 13-digit current-era values. Check whether they came from an earlier response and should be correlated, or whether they should be generated at runtime as the current epoch timestamp in milliseconds.
                - Detect formatted date/time fields and drawId-style fields such as drawId, draw_id, expiry/expire/expiredDateTime, validUntil, startDate, endDate, and timestamp. Correlate them from earlier product/draw/session responses or generate/derive them at runtime; do not leave recorded values merely because validation is green.
                - Prefer native extractors for correlations. Use Regex Extractors by default; use JSONPath when the source is JSON and JSON-path extraction is more robust than regex; use CSS selectors for HTML when CSS selection is more robust; use XPath for XML/HTML when path selection is more robust. Do not use Boundary Extractors. Do not use a JSR223 PostProcessor that reads `prev.getResponseDataAsString()` or `prev.getResponseHeaders()` for a value that a native Regex/JSONPath/CSS/XPath extractor can capture.
                - For extractors whose variable is required by later requests, enable "Assertion error when not matched" by setting failOnNoMatch=true so missing correlations fail the run clearly. Leave it false only for truly optional extractors with a proven safe fallback.
                - After structural edits such as adding, deleting, or moving nodes, sampler indexes can drift. Prefer targetNodePath/sourceNodePath from find_open_plan_nodes, search, validation, or HAR results for list_http_arguments_open_plan, set_http_argument_value_open_plan, add_jsr223_open_plan, assertion edits, redirect edits, move_node_open_plan, and delete_node_open_plan. If only an index is available after a structural edit, use find_open_plan_nodes for the narrow node type/name/path before broad inspect_open_plan.
                - For identified username/password values, preserve the original literal value, replace the old literal in request data first, then immediately create/update the matching top-level User Defined Variable with the original literal value. For POST form credentials, first call list_http_arguments_open_plan on the exact sampler, preferably by targetNodePath, to discover actual argument names/values/indexes, then use set_http_argument_value_open_plan with argumentIndex and alwaysEncode=false where needed. If an old credential literal might occur outside arguments, use replace_literal_open_plan with excludeUserDefinedVariables=true before or after setting the variable so the User Defined Variables table is not self-replaced. Verify with search_open_plan_values using excludeUserDefinedVariables=true.
                - After credential, UUID, token, state, timestamp, or ID replacement, call search_open_plan_values with scopeNodePath="%s" for the old literal or an appropriate regex to verify stale request values are gone in the selected Thread Group. Use allowWholePlan=true only for an explicit out-of-scope/global audit. Do not rely only on a green validation run.
                - Use add_jsr223_open_plan only when runtime data generation or transformation cannot be expressed cleanly with native extractors, JMeter functions, variables, or simple replacements. JSR223 is appropriate for complex JSON selection/transformation that JSONPath cannot express cleanly, derived values, or grouped setup variables; it is not appropriate for a single field capture that a JSONPath or Regex Extractor can do. For a one-off client-generated UUID in a request field, prefer inline `${__UUID}`. Use JSR223/setup only when the same generated UUID must be reused across multiple request fields or transformed before use.
                - When a regex extractor supports multiple response shapes, design it with one capture group or a template that cannot emit `null`. Avoid `$1$$2$` style templates unless this BreakTest/JMeter runtime is known not to render unmatched groups as `null`.
                - Never add JSR223 PreProcessors, PostProcessors, or Assertions directly under the Test Plan, Thread Group, or transaction/controller. Pre/PostProcessors must be attached to the exact sampler they prepare or process. For setup logic that should run once in sequence, add a JSR223 Sampler at the correct location in the selected Thread Group.
                - When an existing Regex Extractor needs a changed regex, useField, template, match number, default value, or failOnNoMatch, use update_regex_extractor_open_plan. If duplicate extractors exist, use find_open_plan_nodes with type=regex_extractor and variableName first, then pass extractorMatchIndex for one extractor or updateAllMatches=true for intentional duplicate cleanup. Do not use broad literal replacement of values like `false`, `true`, or `headers` to edit extractor internals. Do not delete native Regex/JSONPath/CSS/XPath extractors and rebuild them as JSR223 unless no native extractor can represent the proven source/value relationship.
                - When an existing Response Assertion needs a changed pattern, field, or match type, use update_response_assertion_open_plan. Do not use broad literal replacement to edit assertion internals because the same literal can occur in request JSON.
                - If the assertion phase is enabled, after the repair loop validates successfully through the selected Thread Group and high-confidence dynamic leftovers are resolved or documented, ensure every transaction has at least one meaningful assertion on text or response data that identifies the expected page, transaction, or API response.
                - Do not use weak single-word assertions unless the word is genuinely unique and scenario-significant. Prefer a significant sentence, phrase, HTML fragment, JSON field/value, or XML fragment. For example, after login assert on text only visible after successful login; after purchase assert on confirmation text such as "Thank you for your purchase".
                - Avoid generic assertion strings that could also match an error page, fallback response, generic layout, or unrelated transaction. The assertion tools reject weak/generic patterns by default; if rejected, inspect validation samples for a stronger marker instead of bypassing with allowWeakPattern.
                - If a response assertion fails, update or remove only that failing assertion. Do not remove all AI-created assertions just because one marker was too strict. If a required extractor has failOnNoMatch=true, do not add another assertion merely to prove the extractor matched.
                - Never put `${...}` variable references in sampler, controller, transaction, assertion, extractor, or other element names. Names must remain static. If a dynamic value must be hinted in a name, use `{name}` without `$` so repeated runs do not create unique names.
                - When a dynamic literal is replaced in request data and that literal also appears in a sampler/controller/transaction name, immediately use replace_literal_in_names_open_plan with a static `{variable}` placeholder. Never finish with raw UUIDs or hard-coded IDs in HTTP sampler names when the path/body/header has been parameterized.
                - Keep static browser assets parallel only when independent; keep dependent API/REST/XHR calls sequential.
                - If a correlation extractor is correct but a dependent request still sends an unresolved variable, inspect execution order. When the source sampler is after, inside a parallel branch with, or otherwise not guaranteed to run before the dependent sampler, use move_node_open_plan with sourceNodePath/targetNodePath to move the source sampler or dependent chain into deterministic order before declaring a GUI-edit blocker. To move a sampler out of a Parallel Controller, place it before/after the Parallel Controller node rather than under a sampler.
                - After replacing hard-coded values with extracted variables, re-check Parallel Controllers. Token/API/XHR samplers that depend on extracted data should run sequentially before their dependents; keep only independent static browser assets in parallel.
                - If an edit creates a duplicate or misplaced processor, extractor, assertion, sampler, controller, or config node, use find_open_plan_nodes to locate it, then use delete_node_open_plan with targetNodePath. If multiple identical duplicate nodes are intentional cleanup targets, use deleteAllMatches=true instead of adding compensating JSR223 scripts or neutralizing bad extractors with broad literal replacement.
                - When asked to move separate ThinkTime timers to transaction level, use move_think_times_to_transactions_open_plan for the selected Thread Group. This converts standalone ThinkTime TestAction sampler nodes with timer children into delay fields on the next Transaction Controller and removes the standalone ThinkTime nodes by default. Do not edit TransactionController.delayMode/delayMin/delayMax through broad literal replacement.
                - Randomize scenario data where practical by selecting from prior responses instead of replaying fixed IDs.
                - Do not disable samplers merely to make the test green.
                %s

                Before finishing, call list_agent_changes_open_plan and provide:
                - a plain-text audit list with transaction name, assertion status, dynamic values reviewed, correlations added, and remaining blockers.
                - a concise change list matching the BreakTest AI Auto Scripting table.
                - the BreakTest AI Knowledge update summary, including whether update_ai_knowledge_open_plan succeeded and what reusable learnings were appended.
                - Use plain text only in the final response. Do not use markdown tables because the BreakTest AI log is a plain Swing text area.

                Context:
                - Open plan file: %s
                - Backup before live edits: %s
                - Selected Thread Group: %s
                - Selected Thread Group path: %s
                %s
                """.replace("{{BRIDGE}}", bridgeCommand()).formatted(
                request.threadGroupName(),
                request.threadGroupPath(),
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
                - First call agent_activity so progress is visible in the BreakTest AI Auto Scripting window.
                - A GUI backup is created before this run starts. Use it only as a rollback safety net.
                - Use the supported BreakTest agent bridge command when native MCP tools are unavailable: `{{BRIDGE}} <tool-name> '<json-arguments>'`.
                - Before the first edit, call `{{BRIDGE}} tools` to inspect exact bridge tool schemas. Read only the schemas needed for the next action, and do not echo the full tool output back into progress or final text. Do not search the filesystem for a different bridge unless this exact command fails.
                - Scope the work to exactly one Thread Group path: `%s`.
                - Do only the user-requested task. Do not start a full script repair, full correlation pass, assertion pass, or validation loop unless the request explicitly asks for it.
                - Keep the open BreakTest test plan as the live edit surface so changes are visible in the GUI.
                - Use GUI-backed edit tools where available, including replace_literal_open_plan, replace_literal_in_names_open_plan, search_open_plan_values, list_http_arguments_open_plan, set_http_argument_value_open_plan, add_response_assertion_open_plan, update_response_assertion_open_plan, add_jsr223_open_plan, set_redirect_mode_open_plan, move_node_open_plan, delete_node_open_plan, and move_think_times_to_transactions_open_plan.
                - When the specific request is to move separate ThinkTime timers to transaction level, call move_think_times_to_transactions_open_plan with the selected threadGroupName. Do not inspect code internals or try broad literal replacement for TransactionController delay fields.
                - For request data, variables use `${variable}`. For sampler/controller/transaction names, never use `${...}`; use a display-safe `{variable}` placeholder or another static name.
                - The only valid inline UUID function form in this BreakTest/JMeter build is `${__UUID}`. Never use `${__UUID()}` or `${__UUID(name)}`. For a one-off client-generated UUID in a request field, prefer inline `${__UUID}`; use JSR223/setup only when the same generated UUID must be reused across multiple request fields or transformed before use.
                - When adding response assertions or correlation extractors, provide evidenceSource and evidence. Do not add assertions/extractors for responses that were not reached unless a recorded response or confirmed AI Knowledge entry proves the marker.
                - Prefer native extractors for simple response/header/body captures: Regex by default, JSONPath for robust JSON field/array extraction, CSS selectors for HTML when CSS selection is more robust, and XPath for XML/HTML when path selection is more robust. Do not use Boundary Extractors. Do not add a JSR223 PostProcessor that reads prev.getResponseDataAsString() or prev.getResponseHeaders() when a native Regex/JSONPath/CSS/XPath extractor can capture the value. JSR223 PreProcessors, PostProcessors, and Assertions must be attached to a specific sampler, never directly under a Test Plan, Thread Group, or transaction/controller. Use a JSR223 Sampler for setup logic that should run once in sequence.
                - For credential parameterization, preserve the original username/password literal, replace request data with `${variable}` first, then set the User Defined Variable to the original literal. When using broad replace/search for credentials, pass excludeUserDefinedVariables=true so the Test Plan variables table is not self-replaced.
                - When renaming stale UUIDs or IDs in element names after request data has been parameterized, use replace_literal_in_names_open_plan so request data is not changed to `{variable}` by mistake.
                - When using replace_literal_open_plan, replace_literal_in_names_open_plan, apply_regex_correlation_open_plan without a target sampler, or search_open_plan_values, pass threadGroupName or scopeNodePath for the selected Thread Group. Use allowWholePlan=true only when the user explicitly requested a global edit.
                - Keep inspection and validation compact for local agents. Call inspect_open_plan and validate_open_plan with includeDsl=false and includeStaticAssets=false unless raw DSL or static asset evidence is truly required.
                - Validate only when it is useful for the specific request. Prefer a bounded validate_open_plan run with scopeNodePath set to the selected Thread Group path, maxSamples, stopOnFirstFailure, and includeDsl=false when validation is needed.
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
        String workflowDescription = "This is the native BreakTest non-GUI repair workflow. The open GUI plan is first saved as a separate repair JMX that includes the current in-memory GUI state, including unsaved edits. The GUI remains unchanged while the AI edits the repair copy.";
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
                ? "- Assertion phase is enabled. Add meaningful response assertions only after the selected flow is green and high-confidence dynamic leftovers are resolved or documented."
                : "- Assertion phase is disabled by the start dialog. Skip adding or updating Response Assertions unless the user explicitly asks for assertions in the extra instructions. Keep failOnNoMatch=true for required extractors because that is correlation failure detection, not a transaction assertion.";
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
        panel.add(new JLabel("Plan: " + (testPlanFile == null ? "(unsaved)" : testPlanFile)), BorderLayout.NORTH);
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

    private static CodexRunOutput streamOutput(InputStream inputStream, AiTool tool) throws IOException {
        CodexOutputFilter filter = new CodexOutputFilter(tool);
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

    private static void postCompletionSummary(AiRunRequest request, int exitCode, Duration elapsed, CodexRunOutput output) {
        List<String> followUps = new ArrayList<>(output.followUpLines());
        postActivity("AI Auto Scripting summary:");
        postActivity("Status: " + completionStatus(exitCode, output));
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

    private static String completionStatus(int exitCode, CodexRunOutput output) {
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
        SwingUtilities.invokeLater(() -> {
            GuiPackage gui = GuiPackage.getInstance();
            String status = exitCode == 0 ? "The AI run finished." : "The AI run exited with code " + exitCode + ".";
            Object[] options = {
                    "Merge _AI_Generated",
                    "Load Clone",
                    "Leave On Disk"
            };
            int choice = JOptionPane.showOptionDialog(
                    gui == null ? null : gui.getMainFrame(),
                    "<html>" + status + "<br><br>"
                            + "What do you want to do with the repaired JMX clone?<br>"
                            + "<code>" + repairClone.getPath() + "</code><br><br>"
                            + "Merge adds the repaired Thread Group as <code>_AI_Generated</code> "
                            + "and merges top-level User Defined Variables without replacing the open plan.</html>",
                    "AI Repaired Clone",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]
            );
            if (choice == 0) {
                try {
                    postActivity("Merging repaired Thread Group into open plan: " + repairClone.getPath());
                    Map<String, Object> result = BreakTestAgentGuiService.mergeRepairCloneIntoOpenPlan(
                            repairClone.getPath(),
                            request.threadGroupPath()
                    );
                    postActivity("Merged repaired Thread Group: " + result.getOrDefault("threadGroupNodePath", ""));
                } catch (Exception ex) {
                    log.warn("Could not merge AI repaired clone {}", repairClone, ex);
                    postActivity("Could not merge repaired Thread Group: " + ex.getMessage());
                }
            } else if (choice == 1) {
                postActivity("Loading repaired clone: " + repairClone.getPath());
                LoadDraggedFile.loadProject(
                        new ActionEvent(CodexAiRepair.class, ActionEvent.ACTION_PERFORMED, ActionNames.OPEN),
                        repairClone
                );
            } else {
                postActivity("Repaired clone left on disk: " + repairClone.getPath());
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

    private static class CodexOutputFilter {
        private final AiTool tool;
        private boolean finalResponseStarted;
        private boolean skipNextTokenCount;
        private boolean suppressToolOutput;
        private boolean suppressDiffOutput;
        private final Set<String> displayedFinalLines = new HashSet<>();
        private final CodexRunOutput output = new CodexRunOutput();

        CodexOutputFilter(AiTool tool) {
            this.tool = tool;
            this.finalResponseStarted = tool == AiTool.OPENCODE || tool == AiTool.CLAUDE;
        }

        String displayLine(String line) {
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

        CodexRunOutput output() {
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

    private static final class CodexRunOutput {
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
                if (lower.contains("not fully green")
                        || lower.contains("validation is not fully green")
                        || lower.contains("validation remains")
                        || lower.contains("not validated past")
                        || lower.contains("not reached due")
                        || lower.contains("remaining blocker")
                        || lower.contains("unresolved blocker")
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
                if (lower.contains("not fully green")
                        || lower.contains("validation is not fully green")
                        || lower.contains("validation remains blocked")
                        || lower.contains("validation remains")
                        || lower.contains("not validated past")
                        || lower.contains("not reached due")
                        || lower.contains("remaining blocker")
                        || lower.contains("unresolved blocker")) {
                    return true;
                }
            }
            return false;
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
