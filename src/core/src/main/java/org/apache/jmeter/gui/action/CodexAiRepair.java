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

import com.google.auto.service.AutoService;

/**
 * Starts a local Codex repair session for the currently open BreakTest plan.
 */
@AutoService(Command.class)
public class CodexAiRepair extends AbstractAction {
    private static final Logger log = LoggerFactory.getLogger(CodexAiRepair.class);
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
            String repairClonePath = request.editSurface() == AiEditSurface.JMX_CLONE
                    ? BreakTestAgentGuiService.createRepairCloneForOpenPlan()
                    : "";
            AiRunRequest runRequest = request.withPaths(backupPath, repairClonePath);

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
        try {
            File workingDirectory = aiWorkingDirectory(request.tool());
            List<String> command = aiCommand(request, workingDirectory);
            postActivity("Starting AI Auto Scripting.");
            postActivity("AI tool: " + request.tool().displayName()
                    + " (dangerous local-agent settings approved in the start dialog).");
            postActivity("Edit surface: " + request.editSurface().displayName());
            postActivity("Command: " + commandSummary(command));
            if (request.hasUserInput()) {
                postActivity("Included extra user instructions from the start dialog.");
            }
            if (!request.repairClonePath().isBlank()) {
                postActivity("Repair clone: " + request.repairClonePath());
            }
            postActivity("Working directory: " + workingDirectory.getPath());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            CURRENT_PROCESS.set(process);
            if (STOP_REQUESTED.get()) {
                process.destroy();
            }
            process.getOutputStream().close();
            output = streamOutput(process.getInputStream(), request.tool());
            int exitCode = process.waitFor();
            boolean stopped = STOP_REQUESTED.get();
            if (stopped) {
                postActivity("AI Auto Scripting stopped by user.");
                AiAutoScriptingLogWindow.finishRun("Stopped");
            } else if (exitCode == 0) {
                postActivity("AI Auto Scripting finished successfully.");
                AiAutoScriptingLogWindow.finishRun("Finished successfully");
            } else {
                postActivity("AI Auto Scripting exited with code " + exitCode + ".");
                AiAutoScriptingLogWindow.finishRun("Exited with code " + exitCode);
            }
            postCompletionSummary(request, exitCode, Duration.between(started, Instant.now()), output);
            if (!stopped) {
                offerToLoadRepairClone(request, exitCode);
            }
        } catch (Exception ex) {
            if (STOP_REQUESTED.get()) {
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
        if (request.editSurface() == AiEditSurface.JMX_CLONE) {
            return nonGuiClonePrompt(request, testPlanFile);
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
                - Prefer GUI-backed MCP tools: inspect_open_plan, validate_open_plan, agent_activity, get_ai_knowledge_open_plan, update_ai_knowledge_open_plan, list_agent_changes_open_plan, and open-plan edit tools.
                - Use live open-plan edit tools for supported changes: apply_boundary_correlation_open_plan, apply_regex_correlation_open_plan, update_regex_extractor_open_plan, replace_literal_open_plan, replace_literal_in_names_open_plan, set_user_defined_variable_open_plan, list_http_arguments_open_plan, set_http_argument_encode_open_plan, set_http_argument_value_open_plan, search_open_plan_values, audit_dynamic_request_values_open_plan, add_jsr223_open_plan, add_response_assertion_open_plan, update_response_assertion_open_plan, set_redirect_mode_open_plan, and move_think_times_to_transactions_open_plan.
                - The GUI edit tools create elements through BreakTest/JMeter GUI components. If a live edit tool fails, report it with agent_activity and continue with other safe GUI-backed edits.
                - First call `{{BRIDGE}} agent_activity '{"level":"info","message":"Starting AI Auto Scripting"}'` so progress is visible in the BreakTest AI Auto Scripting window.
                - Keep the open BreakTest test plan as the live edit surface so changes are visible in the GUI.
                - Do not start a separate MCP server by hand. Use the supported BreakTest agent bridge command for validation, inspection, and edits.
                - Read BreakTest AI Knowledge before repairing. Immediately post a concise agent_activity message with the loaded knowledge nodePath, knowledgeNodeCount, and whether isDefaultKnowledge is true. If it is default/empty while the user expected project learnings, report that blocker before doing broad edits.
                - Treat non-default BreakTest AI Knowledge as mandatory first context. Reuse applicable confirmed project patterns for correlations, assertions, variables, timestamps, dependencies, and randomization before inventing a fresh approach.
                - If AI Knowledge records a confirmed limitation from an earlier run, such as an encoded HTTP argument issue or a failed inline Groovy expression, do not repeat the same failed approach. Use the dedicated bridge tool for that limitation or choose the previously validated fallback.
                - Before finishing every full repair run, update BreakTest AI Knowledge with update_ai_knowledge_open_plan. This is mandatory even if validation did not become fully green.
                - Preserve existing BreakTest AI Knowledge arrays and append only reusable run learnings. Record confirmed fixes, confirmed limitations, and unresolved high-confidence patterns that future runs should know about, including the selected Thread Group and transaction/request where each pattern was observed.
                - If there were no reusable learnings, still append a learnedFromThreadGroups entry for this run with the selected Thread Group, reviewed scope, and "noReusableLearnings": true. Never finish a full repair with BreakTest AI Knowledge unchanged from the start.
                - Store recurring dynamic field names such as drawId, expiredDateTime, ticket path UUIDs, transactionId, basketItemId, csrf/request-verification tokens, and credential fields under knownDynamicFields/timestampRules/correlationPatterns as appropriate, with status such as confirmed, generated, correlated, or unresolved.
                - Scope the repair to exactly one Thread Group path: `%s`.
                - Do not repair, validate for broad fixes, add assertions, or change samplers in other Thread Groups unless the selected Thread Group directly depends on a shared test-plan-level config element.
                - Work from top to bottom through the selected Thread Group. For each transaction, review the previous transaction's extracted values before blaming the current failing request.
                - A failing callback, payment, basket, or order request can be a symptom of earlier static data. Check upstream requests first for stale login state, redirect/session/correlation cookies, request verification tokens, anti-forgery values, product/ticket/order IDs, and timestamps.
                - Keep inspection and validation compact for local agents. Call inspect_open_plan and validate_open_plan with includeDsl=false unless you need raw DSL for a specific edit; if DSL is needed, request the smallest dslCharacterLimit that can answer the question.
                - Validate with `validate_open_plan` in bounded runs with maxSamples, stopOnFirstFailure, includeDsl=false, and tight responseBodyLimit/requestBodyLimit values.
                - A first validation failure is a starting point, not the full scope. If validation blocks early, continue by inspecting the whole open plan and auditing later transactions statically.
                - Use `plan.samplers[*].transactionName` and `plan.dynamicValueCandidates[*].transactionName` from inspect/validate results to group work by transaction and sampler order.
                - Use `plan.dynamicValueCandidates` and `audit_dynamic_request_values_open_plan` as structured checklists for hard-coded UUIDs, IDs, tokens, hashes, nonces, and epoch timestamps in requests that were not reached by validation.
                - Build a request inventory for the selected Thread Group before broad edits. For every enabled non-static sampler, review method, path, query string, POST/form/multipart/raw body, cookies, and request headers.
                - For each request surface, check for high-correlation-potential values: UUID/GUIDs, numeric IDs, selected scenario data, random/fuzzy strings, long opaque values, nonces, state values, hashes, current/future/past dates, formatted date-times, epoch seconds/milliseconds, usernames, passwords, emails, bearer/access/refresh tokens, CSRF/request-verification tokens, and custom token/security headers.
                - Treat custom headers as request data, not metadata. Audit headers such as Authorization, X-CSRF-Token, X-Request-Verification-Token, X-Requested-With, X-Api-Key, nonce/state/correlation/request IDs, and any application-specific `x-*` header carrying an ID, token, timestamp, or random-looking value.
                - Classify each suspicious value as server-issued correlation, client-generated runtime data, user/config variable, randomized scenario data, or safe static constant. Do not ignore a value merely because the current validation run is green.
                - For each dynamic value candidate, decide whether it has enough evidence for a live correlation edit, should become a runtime-generated value, or must be reported as a candidate needing source-response evidence.
                - When a recorded literal can occur in multiple places, such as path, query string, POST form argument, raw body, header, referer, cookie, callback URL, or nested child element, use replace_literal_open_plan without targetSamplerIndex/targetSamplerLabel to run a whole-plan recursive search/replace. Element names are ignored by default; do not set includeNames=true unless the user explicitly asks for renaming. Use a target sampler only when the literal is known to be local to that sampler.
                - When request data replacement leaves an old dynamic literal in sampler/controller names, use replace_literal_in_names_open_plan with a display-safe replacement like `{basket_page_id}`. Do not put `${...}` in element names.
                - After adding a correlation extractor, replace every dependent occurrence of the old literal, not only the first query/path occurrence. Re-inspect the plan and verify no stale copies remain in POST data, headers, or callback args.
                - Do not stop solely because the first failing request lacks a confirmed source response. Continue reviewing other requests and transactions, apply safe GUI-backed correlations/assertions/replacements where the source/target/literal/pattern is known, and include unresolved candidates in the audit.
                - Prefer regular expression extractors for dynamic values when a regex can robustly capture the value. Use boundary extractors when boundaries are clearer.
                - Only add extractors from response evidence. For apply_regex_correlation_open_plan/apply_boundary_correlation_open_plan, pass evidenceSource and evidence. Use evidenceSource=validated_response only when validate_open_plan returned the source sampler response containing the marker; use recorded_response only when a recorded response body/header is actually present; use ai_knowledge only for a previously confirmed project pattern. Do not invent extractors from request literals alone.
                - If a later sampler was not reached in validation and no recorded response or AI Knowledge proves the response marker, do not add a Response Assertion to it. Document the assertion gap in the final audit instead.
                - When adding assertions, pass evidenceSource and evidence. Prefer evidenceSource=validated_response with the exact response snippet from validate_open_plan. Static request/plan inference is not a valid success assertion unless the user explicitly requested a static edit and you mark it with allowStaticInference=true.
                - For extractors whose variable is required by later requests, set failOnNoMatch=true so missing correlations fail clearly.
                - Add meaningful response assertions only as transactions become understandable from reached validation samples, recorded responses, or confirmed AI Knowledge. Replace literals with `${variable}` references, User Defined Variables, or runtime functions where appropriate.
                - Automatic redirects can hide the intermediate response that issues dynamic values, but redirect mode changes can also alter auth/payment behavior. Do not change redirects as generic hardening. Only use set_redirect_mode_open_plan after inspection proves a needed source value is hidden by redirect handling, post the reason with agent_activity, and revalidate immediately so the change can be treated as a possible regression.
                - Keep progress updates concise: report validations, edits, blockers, and audit results. Do not echo prompts, code, full XML, or long tool payloads.
                - Review all transactions, POST bodies, URL/query/path values, cookies, and headers for dynamic UUIDs, IDs, random strings, timestamps, csrf/requestverification tokens, and bearer/access tokens.
                - Call audit_dynamic_request_values_open_plan with threadGroupName="%s" before finishing, even after a green validation run. A successful run only proves the current data happened to work; it does not prove recorded UUIDs, long opaque IDs, tokens, or timestamps are safe to replay. Correlate, parameterize, generate, or explicitly report every high-confidence leftover candidate.
                - Keep static browser assets enabled and runnable, but ignore stylesheet, JavaScript, image, font, favicon, and source-map requests during normal AI repair/audit. Do not set includeStaticAssets=true on audit_dynamic_request_values_open_plan unless an asset response is directly relevant to a failure or dependency.
                - UUIDs in request fields such as transactionId, basketItemId, cartItemId, clientId, correlationId, or requestId are often generated by browser JavaScript. If no earlier response issues the value, generate it at runtime with JSR223/Groovy or `${__UUID}` and replace the recorded literal. The only valid inline UUID function form in this BreakTest/JMeter build is `${__UUID}`; never use `${__UUID()}` or `${__UUID(name)}`. If the same UUID is used in more than one dependent request, generate it once with a JSR223/setup sampler before the first dependent request and reuse that variable.
                - Detect millisecond epoch timestamps, typically 13-digit current-era values. Check whether they came from an earlier response and should be correlated, or whether they should be generated at runtime as the current epoch timestamp in milliseconds.
                - Detect formatted date/time fields and drawId-style fields such as drawId, draw_id, expiry/expire/expiredDateTime, validUntil, startDate, endDate, and timestamp. Correlate them from earlier product/draw/session responses or generate/derive them at runtime; do not leave recorded values merely because validation is green.
                - Prefer regex extractors for correlations. Use JSON extractors only when JSON-path extraction is more robust.
                - For identified username/password values, preserve the original literal value, replace the old literal in request data first, then immediately create/update the matching top-level User Defined Variable with the original literal value. For POST form credentials, first call list_http_arguments_open_plan on the exact sampler to discover actual argument names/values, then use set_http_argument_value_open_plan with alwaysEncode=false where needed. If an old credential literal might occur outside arguments, use replace_literal_open_plan with excludeUserDefinedVariables=true before or after setting the variable so the User Defined Variables table is not self-replaced. Verify with search_open_plan_values using excludeUserDefinedVariables=true.
                - After credential, UUID, token, state, timestamp, or ID replacement, call search_open_plan_values for the old literal or an appropriate regex to verify stale request values are gone. Do not rely only on a green validation run.
                - Use add_jsr223_open_plan when runtime data generation or transformation is clearer in Groovy than with inline JMeter functions, for example grouped setup variables, current/future timestamps, randomized values, derived IDs, or complex response post-processing.
                - Never add JSR223 PreProcessors, PostProcessors, or Assertions directly under the Test Plan, Thread Group, or transaction/controller. Pre/PostProcessors must be attached to the exact sampler they prepare or process. For setup logic that should run once in sequence, add a JSR223 Sampler at the correct location in the selected Thread Group.
                - When an existing Regex Extractor needs a changed regex, useField, template, match number, default value, or failOnNoMatch, use update_regex_extractor_open_plan. Do not use broad literal replacement of values like `false`, `true`, or `headers` to edit extractor internals.
                - When an existing Response Assertion needs a changed pattern, field, or match type, use update_response_assertion_open_plan. Do not use broad literal replacement to edit assertion internals because the same literal can occur in request JSON.
                - Ensure every transaction has at least one meaningful assertion on text or response data that identifies the expected page, transaction, or API response.
                - Do not use weak single-word assertions unless the word is genuinely unique and scenario-significant. Prefer a significant sentence, phrase, HTML fragment, JSON field/value, or XML fragment. For example, after login assert on text only visible after successful login; after purchase assert on confirmation text such as "Thank you for your purchase".
                - Avoid generic assertion strings that could also match an error page, fallback response, generic layout, or unrelated transaction. The assertion tools reject weak/generic patterns by default; if rejected, inspect validation samples for a stronger marker instead of bypassing with allowWeakPattern.
                - Never put `${...}` variable references in sampler, controller, transaction, assertion, extractor, or other element names. Names must remain static. If a dynamic value must be hinted in a name, use `{name}` without `$` so repeated runs do not create unique names.
                - When a dynamic literal is replaced in request data and that literal also appears in a sampler/controller/transaction name, immediately use replace_literal_in_names_open_plan with a static `{variable}` placeholder. Never finish with raw UUIDs or hard-coded IDs in HTTP sampler names when the path/body/header has been parameterized.
                - Keep static browser assets parallel only when independent; keep dependent API/REST/XHR calls sequential.
                - When asked to move separate ThinkTime timers to transaction level, use move_think_times_to_transactions_open_plan for the selected Thread Group. This converts standalone ThinkTime TestAction sampler nodes with timer children into delay fields on the next Transaction Controller and removes the standalone ThinkTime nodes by default. Do not edit TransactionController.delayMode/delayMin/delayMax through broad literal replacement.
                - Randomize scenario data where practical by selecting from prior responses instead of replaying fixed IDs.
                - Do not disable samplers merely to make the test green.

                Before finishing, call list_agent_changes_open_plan and provide:
                - an audit table with transaction name, assertion status, dynamic values reviewed, correlations added, and remaining blockers.
                - a concise change list matching the BreakTest AI Auto Scripting table.
                - the BreakTest AI Knowledge update summary, including whether update_ai_knowledge_open_plan succeeded and what reusable learnings were appended.

                Context:
                - Open plan file: %s
                - Backup before live edits: %s
                - Selected Thread Group: %s
                - Selected Thread Group path: %s
                %s
                """.replace("{{BRIDGE}}", bridgeCommand()).formatted(
                request.threadGroupPath(),
                request.threadGroupName(),
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
                - Use GUI-backed edit tools where available, including replace_literal_open_plan, replace_literal_in_names_open_plan, search_open_plan_values, list_http_arguments_open_plan, set_http_argument_value_open_plan, add_response_assertion_open_plan, update_response_assertion_open_plan, add_jsr223_open_plan, set_redirect_mode_open_plan, and move_think_times_to_transactions_open_plan.
                - When the specific request is to move separate ThinkTime timers to transaction level, call move_think_times_to_transactions_open_plan with the selected threadGroupName. Do not inspect code internals or try broad literal replacement for TransactionController delay fields.
                - For request data, variables use `${variable}`. For sampler/controller/transaction names, never use `${...}`; use a display-safe `{variable}` placeholder or another static name.
                - The only valid inline UUID function form in this BreakTest/JMeter build is `${__UUID}`. Never use `${__UUID()}` or `${__UUID(name)}`; for a reusable UUID, create it in JSR223/setup code and reference the variable.
                - When adding response assertions or correlation extractors, provide evidenceSource and evidence. Do not add assertions/extractors for responses that were not reached unless a recorded response or confirmed AI Knowledge entry proves the marker.
                - JSR223 PreProcessors, PostProcessors, and Assertions must be attached to a specific sampler, never directly under a Test Plan, Thread Group, or transaction/controller. Use a JSR223 Sampler for setup logic that should run once in sequence.
                - For credential parameterization, preserve the original username/password literal, replace request data with `${variable}` first, then set the User Defined Variable to the original literal. When using broad replace/search for credentials, pass excludeUserDefinedVariables=true so the Test Plan variables table is not self-replaced.
                - When renaming stale UUIDs or IDs in element names after request data has been parameterized, use replace_literal_in_names_open_plan so request data is not changed to `{variable}` by mistake.
                - Keep inspection and validation compact for local agents. Call inspect_open_plan and validate_open_plan with includeDsl=false unless raw DSL is truly required.
                - Validate only when it is useful for the specific request. Prefer a bounded validate_open_plan run with maxSamples, stopOnFirstFailure, and includeDsl=false when validation is needed.
                - Keep progress concise. Do not echo prompts, code, full XML, or long tool payloads.
                - Before finishing, call list_agent_changes_open_plan and summarize only the changes made for this specific request.

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

    private static String nonGuiClonePrompt(AiRunRequest request, String testPlanFile) {
        String taskScope = request.mode() == AiRunMode.SPECIFIC_REQUEST
                ? "Complete only the specific user-requested task against the repair clone."
                : "Repair and harden the selected Thread Group in the repair clone.";
        return """
                Use $breaktest-jmeter-repair.

                %s

                Requirements:
                - First call agent_activity so progress is visible in the BreakTest AI Auto Scripting window.
                - This is non-GUI clone mode. Do not live-edit the open GUI plan.
                - The open GUI plan was copied to a repair clone before this run. Edit only this clone file: `%s`.
                - Keep the original open plan and backup unchanged. The user will be offered the option to load the repaired clone after you finish.
                - Use direct JMX/XML or structured file editing on the repair clone when needed. Prefer robust XML/property-aware edits over brittle text-only changes.
                - Use the supported BreakTest agent bridge command when useful for file-backed tools: `{{BRIDGE}} <tool-name> '<json-arguments>'`.
                - You may use inspect_jmx and validate_jmx against the repair clone. Use includeDsl=false by default; request raw DSL only for a specific local edit and keep dslCharacterLimit small. Do not use open-plan edit tools such as replace_literal_open_plan, apply_regex_correlation_open_plan, add_response_assertion_open_plan, or set_user_defined_variable_open_plan because those would edit the live GUI plan.
                - Scope the repair to exactly one Thread Group path from the original GUI selection: `%s`.
                - If the clone contains BreakTest AI Knowledge, read and update it in the clone with reusable learnings from this run.
                - For full script repair, work top-to-bottom through the selected Thread Group. Audit paths, query strings, POST/form/raw bodies, cookies, and custom headers for UUIDs, IDs, random strings, timestamps, credentials, CSRF/request-verification tokens, bearer/access tokens, nonce/state, draw IDs, date/time fields, and long opaque values.
                - For a specific request, do only that request and avoid a broad repair pass unless the user explicitly asked for it.
                - Prefer regex extractors for correlations. Use JSON extractors only when JSON-path extraction is more robust.
                - Only add extractors/assertions from evidence: validated response, recorded response, or existing AI Knowledge. If a response was not reached and no recorded response/knowledge proves the marker, document the gap instead of inventing a node.
                - The only valid inline UUID function form in this BreakTest/JMeter build is `${__UUID}`. Never use `${__UUID()}` or `${__UUID(name)}`. If the same UUID must be reused, create it once with JSR223/setup logic and reference the variable.
                - Never put `${...}` variable references in sampler/controller/transaction/assertion/extractor names. Use static display placeholders such as `{ticket_id}` in names.
                - Preserve original credential literal values in User Defined Variables and replace request data with variable references. Do not self-replace the variable values.
                - Ensure assertions are meaningful and unique to the expected response; avoid weak single words and generic strings that could match an error page.
                - Keep static browser assets enabled and runnable, but ignore stylesheet, JavaScript, image, font, favicon, and source-map requests during normal repair unless directly relevant.
                - Validate in bounded runs against the repair clone with validate_jmx using maxSamples, stopOnFirstFailure, includeDsl=false, and tight responseBodyLimit/requestBodyLimit values. Increase scope only after blockers are understood.
                - Do not disable samplers merely to make validation green.
                - Keep progress concise. Do not echo prompts, code, full XML, or long tool payloads.

                Before finishing:
                - Save all edits to the repair clone path.
                - Provide the repaired clone path.
                - Provide an audit table with transaction name, assertion status, dynamic values reviewed, correlations added, and remaining blockers.
                - Provide a concise change list of added/updated variables, extractors, assertions, samplers, headers, bodies, paths, and names.
                - State whether bounded validation of the clone is green or still blocked.

                Context:
                - Open plan file: %s
                - Backup before run: %s
                - Repair clone to edit: %s
                - Selected Thread Group: %s
                - Selected Thread Group path: %s
                %s
                """.replace("{{BRIDGE}}", bridgeCommand()).formatted(
                taskScope,
                request.repairClonePath().isBlank() ? "(missing repair clone path)" : request.repairClonePath(),
                request.threadGroupPath(),
                testPlanFile == null ? "(unknown)" : testPlanFile,
                request.backupPath().isBlank() ? "(unknown)" : request.backupPath(),
                request.repairClonePath().isBlank() ? "(missing repair clone path)" : request.repairClonePath(),
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
                - Thread Group: %s
                - Extra instructions:
                %s
                """.formatted(
                request.tool().displayName(),
                request.mode().displayName(),
                request.editSurface().displayName(),
                request.threadGroupName().isBlank() ? "(none selected)" : request.threadGroupName(),
                request.instructions().isBlank() ? "(none provided)" : indent(request.instructions())
        );
    }

    private static AiRunRequest showStartDialog(GuiPackage gui) {
        List<ThreadGroupChoice> threadGroups = enabledThreadGroups(gui);
        if (threadGroups.isEmpty()) {
            JOptionPane.showMessageDialog(
                    gui == null ? null : gui.getMainFrame(),
                    "No enabled Thread Groups are available for AI Auto Scripting.",
                    "Start AI Auto Scripting",
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

        JRadioButton liveGui = new JRadioButton("Live GUI plan", defaultEditSurface() == AiEditSurface.LIVE_GUI);
        JRadioButton jmxClone = new JRadioButton("JMX clone (non-GUI)", defaultEditSurface() == AiEditSurface.JMX_CLONE);
        ButtonGroup surfaceGroup = new ButtonGroup();
        surfaceGroup.add(liveGui);
        surfaceGroup.add(jmxClone);
        JPanel surfacePanel = new JPanel(new BorderLayout(0, 4));
        surfacePanel.add(new JLabel("Edit surface"), BorderLayout.NORTH);
        JPanel surfaceChoices = new JPanel(new BorderLayout(0, 2));
        surfaceChoices.add(liveGui, BorderLayout.NORTH);
        surfaceChoices.add(jmxClone, BorderLayout.CENTER);
        surfacePanel.add(surfaceChoices, BorderLayout.CENTER);

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
                "<html><b>Warning:</b> AI Auto Scripting starts a local coding agent that can edit the open plan "
                        + "and run tools with broad permissions. A backup is created first, but you are approving "
                        + "dangerous automation for this run.</html>"
        ), BorderLayout.CENTER);
        warningPanel.add(dangerApproved, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.setPreferredSize(new Dimension(760, 680));
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
                "Start AI Auto Scripting"
        );
        startButton.addActionListener(event -> {
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
        AiEditSurface editSurface = jmxClone.isSelected() ? AiEditSurface.JMX_CLONE : AiEditSurface.LIVE_GUI;
        String instructionText = instructions.getText().trim();
        if (mode == AiRunMode.SPECIFIC_REQUEST && instructionText.isBlank()) {
            JOptionPane.showMessageDialog(
                    gui == null ? null : gui.getMainFrame(),
                    "Add instructions for a specific request.",
                    "Start AI Auto Scripting",
                    JOptionPane.WARNING_MESSAGE
            );
            return null;
        }
        return new AiRunRequest(selectedTool, selectedThreadGroup, mode, editSurface, instructionText);
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
        postActivity("AI Auto Scripting summary:");
        postActivity("Status: " + completionStatus(exitCode, output));
        postActivity("Total time: " + formatDuration(elapsed));
        postActivity("Token usage: input=" + output.inputTokensText()
                + ", output=" + output.outputTokensText()
                + ", total=" + output.totalTokensText());
        if (request.editSurface() == AiEditSurface.LIVE_GUI
                && request.mode() == AiRunMode.FULL_SCRIPT_REPAIR
                && !knowledgeUpdateObserved()) {
            postActivity("Potential follow-up: BreakTest AI Knowledge was not updated during this full repair run.");
        }
        postActivity("Potential follow-up: " + output.followUpText());
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

    private static void offerToLoadRepairClone(AiRunRequest request, int exitCode) {
        if (request.editSurface() != AiEditSurface.JMX_CLONE || request.repairClonePath().isBlank()) {
            return;
        }
        File repairClone = new File(request.repairClonePath());
        if (!repairClone.isFile()) {
            postActivity("Repair clone was not found after the run: " + repairClone.getPath());
            return;
        }
        SwingUtilities.invokeLater(() -> {
            GuiPackage gui = GuiPackage.getInstance();
            String status = exitCode == 0 ? "The AI run finished." : "The AI run exited with code " + exitCode + ".";
            int choice = JOptionPane.showConfirmDialog(
                    gui == null ? null : gui.getMainFrame(),
                    "<html>" + status + "<br><br>"
                            + "Load the repaired JMX clone into BreakTest now?<br>"
                            + "<code>" + repairClone.getPath() + "</code><br><br>"
                            + "This replaces the currently open plan in the GUI.</html>",
                    "Load AI Repaired Clone",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (choice == JOptionPane.YES_OPTION) {
                postActivity("Loading repaired clone: " + repairClone.getPath());
                Load.loadProjectFile(
                        new ActionEvent(CodexAiRepair.class, ActionEvent.ACTION_PERFORMED, ActionNames.OPEN),
                        repairClone,
                        false
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
        LIVE_GUI("gui", "Live GUI plan"),
        JMX_CLONE("clone", "JMX clone (non-GUI)");

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
        private final String instructions;
        private final String backupPath;
        private final String repairClonePath;

        private AiRunRequest(
                AiTool tool,
                ThreadGroupChoice threadGroup,
                AiRunMode mode,
                AiEditSurface editSurface,
                String instructions
        ) {
            this(tool, threadGroup, mode, editSurface, instructions, "", "");
        }

        private AiRunRequest(
                AiTool tool,
                ThreadGroupChoice threadGroup,
                AiRunMode mode,
                AiEditSurface editSurface,
                String instructions,
                String backupPath,
                String repairClonePath
        ) {
            this.tool = tool == null ? AiTool.CODEX : tool;
            this.threadGroup = threadGroup;
            this.mode = mode == null ? AiRunMode.FULL_SCRIPT_REPAIR : mode;
            this.editSurface = editSurface == null ? AiEditSurface.LIVE_GUI : editSurface;
            this.instructions = instructions == null ? "" : instructions;
            this.backupPath = backupPath == null ? "" : backupPath;
            this.repairClonePath = repairClonePath == null ? "" : repairClonePath;
        }

        private AiRunRequest withPaths(String backupPath, String repairClonePath) {
            return new AiRunRequest(tool, threadGroup, mode, editSurface, instructions, backupPath,
                    repairClonePath);
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

        private String instructions() {
            return instructions;
        }

        private String backupPath() {
            return backupPath;
        }

        private String repairClonePath() {
            return repairClonePath;
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
    }

    private static final class CodexRunOutput {
        private static final int MAX_FOLLOW_UP_LINES = 4;
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

        private String followUpText() {
            List<String> followUpLines = new ArrayList<>();
            for (String line : finalResponseLines) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (followUpLines.size() < MAX_FOLLOW_UP_LINES
                        && (lower.contains("remaining")
                        || lower.contains("blocker")
                        || lower.contains("follow")
                        || lower.contains("manual")
                        || lower.contains("could not")
                        || lower.contains("unresolved"))) {
                    followUpLines.add(line);
                }
            }
            if (followUpLines.isEmpty()) {
                return "none reported by Codex";
            }
            return String.join(" | ", followUpLines);
        }

        private boolean hasRepairBlocker() {
            for (String line : finalResponseLines) {
                String lower = line.toLowerCase(Locale.ROOT);
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
    }
}
