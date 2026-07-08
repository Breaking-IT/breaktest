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

package org.apache.jmeter.ai.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.jmeter.ai.AgentReportCompactor
import org.apache.jmeter.ai.AgentRunOptions
import org.apache.jmeter.ai.BreakTestAgent
import org.apache.jmeter.ai.edit.BoundaryCorrelationRequest
import org.apache.jmeter.ai.edit.TestPlanEditor
import org.apache.jmeter.ai.gui.BreakTestAgentGuiService
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.util.JMeterUtils
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.time.Duration

/**
 * Minimal stdio MCP server for Codex-to-BreakTest automation.
 */
public object BreakTestAgentMcpServer {
    private const val DEFAULT_DSL_CHARACTER_LIMIT = 80_000

    // Compact JSON everywhere: tool results are consumed by a model (and echoed
    // verbatim into transcripts by CLI agents such as opencode), so pretty-printing
    // only inflates tokens and log noise.
    private val mapper = ObjectMapper()
    private val wireMapper = ObjectMapper()

    @JvmStatic
    public fun main(args: Array<String>) {
        McpTrace.log("main args=${args.joinToString(",")}")
        initializeBreakTestHome(args.firstOrNull())
        val server = StdioJsonRpcServer(::handleRequest)
        server.run()
    }

    @JvmStatic
    public fun initializeForCli(jmeterHomeArgument: String?) {
        initializeBreakTestHome(jmeterHomeArgument)
    }

    @JvmStatic
    public fun callToolForCli(name: String, arguments: JsonNode): String =
        mapper.writeValueAsString(executeTool(name, arguments))

    @JvmStatic
    public fun toolsListForCli(): String =
        mapper.writeValueAsString(toolsListResult())

    private fun initializeBreakTestHome(jmeterHomeArgument: String?) {
        val home = jmeterHomeArgument
            ?: System.getProperty("jmeter.home")
            ?: File(".").canonicalFile.parentFile?.path
            ?: File(".").canonicalPath
        JMeterUtils.setJMeterHome(home)
        JMeterUtils.loadJMeterProperties(File(home, "bin/jmeter.properties").path)
        JMeterUtils.initLocale()
    }

    private fun handleRequest(request: ObjectNode): ObjectNode? {
        val method = request.path("method").asText()
        McpTrace.log("request method=$method id=${request.get("id")}")
        val id = request.get("id")
        if (id == null || id.isNull) {
            return null
        }
        return try {
            when (method) {
                "initialize" -> response(id, initializeResult(request.path("params")))
                "ping" -> response(id, mapper.createObjectNode())
                "tools/list" -> response(id, toolsListResult())
                "resources/list" -> response(id, emptyListResult("resources"))
                "prompts/list" -> response(id, emptyListResult("prompts"))
                "tools/call" -> response(id, callTool(request.path("params")))
                else -> error(id, -32601, "Unknown method: $method")
            }
        } catch (e: Exception) {
            error(id, -32000, e.message ?: e.toString())
        }
    }

    private fun initializeResult(params: JsonNode): ObjectNode = mapper.createObjectNode().apply {
        put("protocolVersion", params.path("protocolVersion").asText("2024-11-05"))
        set<ObjectNode>(
            "capabilities",
            mapper.createObjectNode().apply {
                set<ObjectNode>("tools", mapper.createObjectNode())
            }
        )
        set<ObjectNode>(
            "serverInfo",
            mapper.createObjectNode().apply {
                put("name", "breaktest-agent")
                put("version", "0.1.0")
            }
        )
    }

    private fun emptyListResult(name: String): ObjectNode = mapper.createObjectNode().apply {
        putArray(name)
    }

    private fun toolsListResult(): ObjectNode = mapper.createObjectNode().apply {
        putArray("tools").apply {
            add(
                tool(
                    "gui_status",
                    "Return status for the running BreakTest GUI agent service and its open plan.",
                    emptyMap(),
                    emptyList(),
                )
            )
            add(
                tool(
                    "refresh_open_plan_from_file",
                    "Reload the currently open BreakTest GUI plan from its saved JMX file. Use this only after a file-backed active-JMX repair batch has saved valid XML and inspect_jmx confirms the same path parses cleanly. The GUI bridge allows one normal refresh per AI run to avoid very slow repeated reloads of large JMX files; pass force=true only when the user explicitly requests another live reload.",
                    mapOf(
                        "path" to "string",
                        "force" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "inspect_open_plan",
                    "Inspect the test plan currently open in the running BreakTest GUI. Static browser asset samplers are omitted by default to keep AI analysis focused on functional requests; pass includeStaticAssets=true only when CSS/JS/font/image responses are directly relevant to correlation or a failure.",
                    mapOf(
                        "includeDsl" to "boolean",
                        "dslCharacterLimit" to "number",
                        "includeStaticAssets" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "validate_open_plan",
                    "Run a bounded validation pass for the test plan currently open in the running BreakTest GUI. Pass threadGroupName or scopeNodePath to validate only the selected Thread Group when backup/duplicate Thread Groups are enabled. Use compact=true so one validation returns first-failure evidence, reached non-static samples, preFailureDynamicCandidates, and preFailureRequestCandidates without static asset noise. Compact results carry full request/response evidence only for the first failure and the samples just before it; earlier passing samples and fully green runs return light response-body previews (evidenceLevel=light), which are still enough to pick assertion markers.",
                    mapOf(
                        "threadGroupName" to "string",
                        "scopeNodePath" to "string",
                        "timeoutSeconds" to "number",
                        "responseBodyLimit" to "number",
                        "requestBodyLimit" to "number",
                        "maxSamples" to "number",
                        "stopOnFirstFailure" to "boolean",
                        "ignoreStaticAssetFailures" to "boolean",
                        "includeDsl" to "boolean",
                        "dslCharacterLimit" to "number",
                        "includeStaticAssets" to "boolean",
                        "compact" to "boolean",
                        "compactSampleLimit" to "number",
                        "compactBodyLimit" to "number",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "search_validated_response_open_plan",
                    "Search the cached responses of the most recent validate_open_plan run for a literal (encoded/decoded variants are tried automatically) or regex, optionally limited to one samplerLabel. Returns small snippets with sampler label, code, and surface. Use this to pick assertion markers and confirm correlation sources instead of re-running validation; with samplerLabel only, it returns the head of that sampler's validated response.",
                    mapOf(
                        "query" to "string",
                        "regex" to "string",
                        "samplerLabel" to "string",
                        "includeRequests" to "boolean",
                        "contextChars" to "number",
                        "maxMatches" to "number",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "backup_open_plan",
                    "Save a timestamped backup copy of the currently open BreakTest GUI plan before live edits.",
                    emptyMap(),
                    emptyList(),
                )
            )
            add(
                tool(
                    "get_ai_knowledge_open_plan",
                    "Read the BreakTest AI Knowledge element in the currently open GUI plan. By default this does not create a missing knowledge node; pass createIfMissing=true only at the final update stage or when the user explicitly asks to create it. Returns whether knowledge is missing/default and all available knowledge nodes.",
                    mapOf(
                        "createIfMissing" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "update_ai_knowledge_open_plan",
                    "Update the BreakTest AI Knowledge in the currently open GUI plan. Full AI repair runs must call this before finishing. Prefer appendLearnings: pass only the new entries per array field (projectHints, correlationPatterns, variableMappings, knownDynamicFields, timestampRules, transactionDependencies, learnedFromThreadGroups) and the GUI merges them into the existing knowledge server-side, skipping exact duplicates — no need to fetch and resend the whole document. knowledgeJson/knowledge replaces the entire document instead. Include selected Thread Group and transaction/request evidence. Default/empty knowledge is rejected unless allowDefault=true.",
                    mapOf(
                        "appendLearnings" to "object",
                        "knowledgeJson" to "string",
                        "knowledge" to "object",
                        "summary" to "string",
                        "allowDefault" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "list_agent_changes_open_plan",
                    "Return the current AI Auto Scripting change table from the running BreakTest GUI.",
                    emptyMap(),
                    emptyList(),
                )
            )
            add(
                tool(
                    "find_open_plan_nodes",
                    "Find compact node metadata in the running BreakTest GUI plan without returning a full inspect_open_plan payload. Every match includes a stable nodeId that keeps pointing at the same node even after structural edits move or reindex it; prefer passing that nodeId (sourceNodeId/targetNodeId) to later edit tools instead of paths or sampler indexes. Use this to locate duplicate extractors/processors/assertions/samplers by name, class, type, variableName, or parent path before delete/update/move operations.",
                    mapOf(
                        "name" to "string",
                        "nameContains" to "string",
                        "className" to "string",
                        "classNameContains" to "string",
                        "type" to "string",
                        "variableName" to "string",
                        "underNodePath" to "string",
                        "maxMatches" to "number",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "agent_activity",
                    "Post a live Codex/agent progress update into the running BreakTest AI Auto Scripting window.",
                    mapOf(
                        "message" to "string",
                        "level" to "string",
                        "source" to "string",
                        "details" to "string",
                    ),
                    listOf("message"),
                )
            )
            add(
                tool(
                    "list_recorded_har_exchanges_open_plan",
                    "List compact recorded HAR request/response evidence linked to samplers in the open BreakTest GUI plan. Use this before the first validation run when a linked HAR is available, so correlations can be inferred from recorded responses without reading the full HAR. Static browser assets are skipped by default.",
                    mapOf(
                        "threadGroupName" to "string",
                        "maxEntries" to "number",
                        "bodyLimit" to "number",
                        "includeStaticAssets" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "get_recorded_har_exchange_open_plan",
                    "Fetch the recorded HAR request/response text linked to one sampler in the open BreakTest GUI plan. Use this as recorded_response evidence before adding extractors or assertions.",
                    mapOf(
                        "threadGroupName" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "bodyLimit" to "number",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "search_recorded_har_open_plan",
                    "Search linked recorded HAR requests and responses for a literal or regex. Use this to find where recorded UUIDs, state/code/nonce, CSRF tokens, draw IDs, basket/order IDs, timestamps, or credentials were issued before running validation.",
                    mapOf(
                        "threadGroupName" to "string",
                        "query" to "string",
                        "regex" to "string",
                        "maxMatches" to "number",
                        "contextChars" to "number",
                        "includeStaticAssets" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "audit_recorded_har_correlations_open_plan",
                    "Scan linked HAR recorded requests for high-correlation-potential values, then search earlier recorded HAR responses for matching source evidence. Use this before the first validation run to get ranked source/target extractor candidates for IDs, UUIDs, client_id, nonce, state/code, CSRF/request-verification tokens, bearer/access tokens, draw/product/basket/ticket/order IDs, timestamps, and long opaque values without spending tokens on many one-off searches.",
                    mapOf(
                        "threadGroupName" to "string",
                        "maxCandidates" to "number",
                        "contextChars" to "number",
                        "includeStaticAssets" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "plan_repair_actions_open_plan",
                    "Create a compact local repair action plan for the selected open GUI Thread Group. This combines linked HAR source-response matching and request-value audits into ranked action IDs so agents can review/apply likely correlations and credential parameterization without reading large HAR or validation payloads. By default apply arguments are stored behind a snapshotId; fetch selected action details with get_repair_actions_open_plan, or one action with get_repair_action_open_plan.",
                    mapOf(
                        "threadGroupName" to "string",
                        "maxActions" to "number",
                        "maxUnresolved" to "number",
                        "contextChars" to "number",
                        "includeStaticAssets" to "boolean",
                        "includeApplyArguments" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "get_repair_action_open_plan",
                    "Fetch the full apply arguments and verification steps for one action returned by plan_repair_actions_open_plan. Use this instead of asking for large HAR snippets or broad dynamic audits when a compact action is enough.",
                    mapOf(
                        "snapshotId" to "string",
                        "actionId" to "string",
                    ),
                    listOf("snapshotId", "actionId"),
                )
            )
            add(
                tool(
                    "get_repair_actions_open_plan",
                    "Fetch the full apply arguments and verification steps for several selected actions returned by plan_repair_actions_open_plan in one response. Prefer this over many get_repair_action_open_plan calls when you already know multiple high-confidence action IDs to apply. If some IDs are stale or absent, the response includes missingActionIds and still returns the valid actions.",
                    mapOf(
                        "snapshotId" to "string",
                        "actionIds" to "array[string]",
                        "maxActions" to "number",
                    ),
                    listOf("snapshotId", "actionIds"),
                )
            )
            add(
                tool(
                    "apply_repair_actions_open_plan",
                    "Apply several planner actions from plan_repair_actions_open_plan in one call. Pass the snapshotId and the selected high-confidence actionIds; the GUI applies each action's stored arguments server-side and returns per-action results (applied/failed/missing) without needing get_repair_actions_open_plan first. Failed actions do not stop the batch unless stopOnFirstError=true. Prefer this over fetching apply arguments and replaying them one call at a time.",
                    mapOf(
                        "snapshotId" to "string",
                        "actionIds" to "array[string]",
                        "stopOnFirstError" to "boolean",
                    ),
                    listOf("snapshotId", "actionIds"),
                )
            )
            add(
                tool(
                    "add_response_assertions_open_plan",
                    "Add several Response Assertions in one call. Pass assertions as an array of add_response_assertion_open_plan argument objects; each item takes exactly one target and one 'pattern' string (never a 'patterns' array): {targetNodeId or targetNodePath, assertionName, pattern, field, matchType, evidenceSource, evidence}. Each item is applied and verified independently against the target's cached validated response; failures are reported per item and do not stop the batch. Prefer this over one add_response_assertion_open_plan call per transaction.",
                    mapOf(
                        "assertions" to "array[object]",
                    ),
                    listOf("assertions"),
                )
            )
            add(
                tool(
                    "apply_boundary_correlation_open_plan",
                    "Add a Boundary Extractor to the running BreakTest GUI plan and replace a literal in the target sampler. Prefer sourceNodePath/targetNodePath and occurrence indexes when labels repeat. Provide evidenceSource and evidence details proving the extractor boundaries came from a validated response, recorded response, or AI Knowledge. Set failOnNoMatch=true to enable the GUI option \"Assertion error when not matched\" when the extracted value is required by later requests. Static inference requires allowStaticInference=true and is logged as unvalidated.",
                    mapOf(
                        "sourceNodeId" to "string",
                        "sourceSamplerIndex" to "number",
                        "sourceSamplerLabel" to "string",
                        "sourceNodePath" to "string",
                        "sourceOccurrenceIndex" to "number",
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "targetOccurrenceIndex" to "number",
                        "threadGroupName" to "string",
                        "scopeNodePath" to "string",
                        "variableName" to "string",
                        "leftBoundary" to "string",
                        "rightBoundary" to "string",
                        "literal" to "string",
                        "failOnNoMatch" to "boolean",
                        "evidenceSource" to "string",
                        "evidence" to "string",
                        "allowStaticInference" to "boolean",
                    ),
                    listOf("variableName", "leftBoundary", "rightBoundary", "literal", "evidenceSource", "evidence"),
                )
            )
            add(
                tool(
                    "apply_regex_correlation_open_plan",
                    "Add or update a Regex Extractor in the running BreakTest GUI plan and optionally replace a literal. The regex runs in JMeter's ORO/Perl5 engine: \\Q...\\E quoting, lookbehind, named groups, and Java-only constructs are NOT supported; escape literal metacharacters with single backslashes. The regex is validated against that engine AND must match the exact provided evidence snippet, otherwise the call is rejected (allowUnmatchedEvidence=true skips only the evidence-match check). For JSON evidence, first verify whether the field is a quoted string, number, array, escaped/encoded value, or absent; e.g. \"pageId\"\\s*:\\s*\"([^\"]+)\" only matches a quoted string value in the snippet. If a Regex Extractor with the same variableName already exists under the source sampler, it is updated instead of duplicated unless allowDuplicateExtractor=true. If literal is omitted, this adds/updates the extractor only. If no target sampler is specified and literal is present, the literal is replaced under threadGroupName/scopeNodePath; whole-plan replacement is refused when multiple enabled Thread Groups exist unless allowWholePlan=true. Prefer sourceNodeId/targetNodeId when available. useField accepts body, headers, request_headers, unescaped, as_document, url, code, or message. Use useField=headers immediately when evidence is in response headers, Location, or Set-Cookie. Provide evidenceSource and evidence details proving the regex came from a validated response, recorded response, or AI Knowledge. Set failOnNoMatch=true to enable the GUI option \"Assertion error when not matched\" when the extracted value is required by later requests. Static inference requires allowStaticInference=true and is logged as unvalidated.",
                    mapOf(
                        "sourceNodeId" to "string",
                        "sourceSamplerIndex" to "number",
                        "sourceSamplerLabel" to "string",
                        "sourceNodePath" to "string",
                        "sourceOccurrenceIndex" to "number",
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "targetOccurrenceIndex" to "number",
                        "threadGroupName" to "string",
                        "scopeNodePath" to "string",
                        "allowWholePlan" to "boolean",
                        "variableName" to "string",
                        "regex" to "string",
                        "template" to "string",
                        "matchNumber" to "string",
                        "defaultValue" to "string",
                        "useField" to "string",
                        "literal" to "string",
                        "failOnNoMatch" to "boolean",
                        "allowDuplicateExtractor" to "boolean",
                        "allowUnmatchedEvidence" to "boolean",
                        "evidenceSource" to "string",
                        "evidence" to "string",
                        "allowStaticInference" to "boolean",
                    ),
                    listOf("variableName", "regex", "evidenceSource", "evidence"),
                )
            )
            add(
                tool(
                    "update_regex_extractor_open_plan",
                    "Update existing Regex Extractor node(s). Identify the extractor either by its own extractorNodeId (from find_open_plan_nodes or edit results) or by source sampler plus variableName. Use this instead of replace_literal_open_plan for changing regex, useField, template, matchNumber, defaultValue, or failOnNoMatch. The regex runs in JMeter's ORO/Perl5 engine: \\Q...\\E quoting, lookbehind, named groups, and Java-only constructs are NOT supported; the regex is validated against that engine, and when an evidence snippet is passed it must match the exact snippet. For JSON evidence, verify quoted string vs numeric/array/escaped/encoded forms before updating. Set failOnNoMatch=true to enable the GUI option \"Assertion error when not matched\" when the extracted value is required by later requests. If duplicates exist, pass extractorMatchIndex for one node or updateAllMatches=true for intentional duplicate cleanup.",
                    mapOf(
                        "extractorNodeId" to "string",
                        "sourceNodeId" to "string",
                        "sourceSamplerIndex" to "number",
                        "sourceSamplerLabel" to "string",
                        "sourceNodePath" to "string",
                        "variableName" to "string",
                        "extractorMatchIndex" to "number",
                        "updateAllMatches" to "boolean",
                        "regex" to "string",
                        "template" to "string",
                        "matchNumber" to "string",
                        "defaultValue" to "string",
                        "useField" to "string",
                        "failOnNoMatch" to "boolean",
                        "evidence" to "string",
                        "allowUnmatchedEvidence" to "boolean",
                    ),
                    listOf("variableName"),
                )
            )
            add(
                tool(
                    "replace_literal_open_plan",
                    "Replace a literal in one target sampler subtree, under threadGroupName/scopeNodePath, or in the whole running BreakTest GUI plan only when allowWholePlan=true or there is a single enabled Thread Group. This reaches nested HTTP arguments, POST bodies, paths, headers, and child element properties. Element names are ignored by default; set includeNames=true only for an intentional rename. Prefer targetNodePath/targetOccurrenceIndex when labels repeat. For credential replacement, set excludeUserDefinedVariables=true so the Test Plan User Defined Variables table keeps the original secret value. Replacements using invalid ${'$'}{__UUID(...)} syntax are rejected; use ${'$'}{__UUID} or a JSR223/setup variable for reusable UUIDs.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "targetOccurrenceIndex" to "number",
                        "threadGroupName" to "string",
                        "scopeNodePath" to "string",
                        "allowWholePlan" to "boolean",
                        "literal" to "string",
                        "replacement" to "string",
                        "includeNames" to "boolean",
                        "excludeUserDefinedVariables" to "boolean",
                    ),
                    listOf("literal", "replacement"),
                )
            )
            add(
                tool(
                    "replace_literal_in_names_open_plan",
                    "Replace a literal only in element names in the running BreakTest GUI plan, under threadGroupName/scopeNodePath, or under one target sampler when specified. Whole-plan name replacement is refused when multiple enabled Thread Groups exist unless allowWholePlan=true. Use this after parameterizing request data to turn stale UUIDs/IDs in sampler labels into static display placeholders such as {basket_page_id}. This rejects ${'$'}{variable} replacements because element names must stay static.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "targetOccurrenceIndex" to "number",
                        "threadGroupName" to "string",
                        "scopeNodePath" to "string",
                        "allowWholePlan" to "boolean",
                        "literal" to "string",
                        "replacement" to "string",
                    ),
                    listOf("literal", "replacement"),
                )
            )
            add(
                tool(
                    "set_user_defined_variable_open_plan",
                    "Create or update a top-level Test Plan User Defined Variable in the running BreakTest GUI plan. For credentials, pass the preserved original literal value, not the ${'$'}{variable} reference; self-referential values are rejected.",
                    mapOf(
                        "name" to "string",
                        "value" to "string",
                    ),
                    listOf("name", "value"),
                )
            )
            add(
                tool(
                    "list_http_arguments_open_plan",
                    "List HTTP arguments exposed by a target sampler, including actual argument names, values, and alwaysEncode flags. Prefer targetNodePath after structural edits because sampler indexes can drift. Use this before credential or POST form edits when names are uncertain.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "set_http_argument_encode_open_plan",
                    "Set the alwaysEncode flag on matching HTTP arguments in a sampler. Use this before replacing encoded form values with \${variable} references when the variable syntax would otherwise be percent-encoded.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "argumentIndex" to "number",
                        "argumentName" to "string",
                        "argumentValue" to "string",
                        "alwaysEncode" to "boolean",
                    ),
                    listOf("alwaysEncode"),
                )
            )
            add(
                tool(
                    "set_http_argument_value_open_plan",
                    "Set the value of a matching HTTP argument in a target sampler, optionally setting alwaysEncode in the same edit. Use this for POST form arguments such as username/password when literal replacement or encoding edits are ambiguous.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "argumentIndex" to "number",
                        "argumentName" to "string",
                        "argumentValue" to "string",
                        "newValue" to "string",
                        "alwaysEncode" to "boolean",
                    ),
                    listOf("newValue"),
                )
            )
            add(
                tool(
                    "search_open_plan_values",
                    "Search string-valued open-plan properties for a literal or regex. Pass threadGroupName or scopeNodePath when multiple Thread Groups have similar transaction names. By default element names are ignored, so use this to verify old UUIDs, credentials, tokens, and stale IDs are gone from request data after edits. For credential verification, set excludeUserDefinedVariables=true so the original secret stored in the Test Plan variables table is not counted as a stale request value.",
                    mapOf(
                        "literal" to "string",
                        "query" to "string",
                        "regex" to "string",
                        "threadGroupName" to "string",
                        "scopeNodePath" to "string",
                        "includeNames" to "boolean",
                        "excludeUserDefinedVariables" to "boolean",
                        "maxMatches" to "number",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "audit_dynamic_request_values_open_plan",
                    "Audit the open GUI plan for hard-coded request-looking dynamic values in paths, query strings, bodies, cookies, and headers, including UUIDs, bearer/JWT tokens, csrf/request-verification tokens, credentials, long opaque IDs, numeric IDs, drawId-style fields, formatted date-times, and epoch-millisecond timestamps. Static browser assets such as CSS, JavaScript, images, fonts, and source maps are ignored by default; set includeStaticAssets=true only when those asset responses are relevant to a failure. Use this after inspection and again before finishing; a green validation run does not clear unresolved high-confidence candidates.",
                    mapOf(
                        "maxCandidates" to "number",
                        "includeStaticAssets" to "boolean",
                        "threadGroupName" to "string",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "add_jsr223_open_plan",
                    "Add a GUI-backed JSR223 Groovy element to the test plan, an enabled Thread Group, or a target sampler. Supports elementType sampler, preprocessor, postprocessor, assertion, or timer. Preprocessors, postprocessors, and assertions must target a specific sampler; use elementType=sampler for setup code under a Thread Group. Compile caching is enabled by default (pass cacheKey=false only with a specific reason) because uncached scripts recompile per execution under load. Do not use JSR223 for values a native JMeter function covers: a single-field UUID is inline ${'$'}{__UUID}, epoch millis is ${'$'}{__time(,)}; JSR223 setup is only for one generated value reused identically across several fields/requests.",
                    mapOf(
                        "parentType" to "string",
                        "threadGroupName" to "string",
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "elementType" to "string",
                        "name" to "string",
                        "language" to "string",
                        "parameters" to "string",
                        "cacheKey" to "string",
                        "script" to "string",
                    ),
                    listOf("script"),
                )
            )
            add(
                tool(
                    "add_response_assertion_open_plan",
                    "Add a Response Assertion to a sampler in the running BreakTest GUI plan. Assertion patterns must be meaningful response markers; weak single-word/generic patterns such as tickets, succes, Mijn, or juli are rejected, and so are volatile markers that embed run-specific data (JSON number values like \"totalDraws\":35, UUIDs, dates, epoch values) — assert the stable field name or phrase without the changing value. Provide evidenceSource and evidence details proving the marker came from a validated response, recorded response, or AI Knowledge. validated_response patterns are verified against the target sampler's cached latest validation response and rejected if the marker only occurs on a different sampler (the error names it); pass allowUnverifiedPattern=true only for deliberate unvalidated assertions. Static inference requires allowStaticInference=true and is logged as unvalidated.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "assertionName" to "string",
                        "pattern" to "string",
                        "field" to "string",
                        "matchType" to "string",
                        "allowWeakPattern" to "boolean",
                        "allowUnverifiedPattern" to "boolean",
                        "evidenceSource" to "string",
                        "evidence" to "string",
                        "allowStaticInference" to "boolean",
                    ),
                    listOf("pattern", "evidenceSource", "evidence"),
                )
            )
            add(
                tool(
                    "update_response_assertion_open_plan",
                    "Update one existing Response Assertion under a target sampler. Use this to fix assertion pattern, field, or match type without touching request bodies or other sampler data. Provide assertionName and/or currentPattern to identify the assertion. Weak single-word/generic patterns are rejected unless allowWeakPattern=true. The new pattern is verified against the target sampler's cached latest validation response and rejected if it only occurs elsewhere; pass allowUnverifiedPattern=true to skip that check.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "assertionName" to "string",
                        "currentPattern" to "string",
                        "pattern" to "string",
                        "field" to "string",
                        "matchType" to "string",
                        "allowWeakPattern" to "boolean",
                        "allowUnverifiedPattern" to "boolean",
                    ),
                    listOf("pattern"),
                )
            )
            add(
                tool(
                    "set_redirect_mode_open_plan",
                    "Set followRedirects and/or autoRedirects on an HTTP sampler in the running BreakTest GUI plan. Use only as a diagnostic edit after inspection proves an intermediate response/header is hidden by redirect handling; post the reason and revalidate immediately because redirect changes can alter auth/payment behavior.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "followRedirects" to "boolean",
                        "autoRedirects" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "clone_node_open_plan",
                    "Clone a node and its full child subtree in the running BreakTest GUI plan, preserving controller/sampler/processor properties such as Transaction Controller pacing settings. Use this when copying an existing controller structure is safer than reconstructing it from partial fields. Prefer sourceNodeId/targetNodeId from find_open_plan_nodes; node paths are the fallback. position is before, after, first_child, or last_child.",
                    mapOf(
                        "sourceNodeId" to "string",
                        "sourceSamplerIndex" to "number",
                        "sourceSamplerLabel" to "string",
                        "sourceNodePath" to "string",
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "position" to "string",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "move_node_open_plan",
                    "Move or reorder a node in the running BreakTest GUI plan while preserving its children. Use this when a sampler/config/postprocessor/extractor must execute before or after another node, for example moving an /api/token sampler before the Parallel Controller containing API requests that need its extracted token. Prefer sourceNodeId/targetNodeId from find_open_plan_nodes or earlier edit results; node paths are the fallback and sampler indexes drift after structural edits. position is before, after, first_child, or last_child.",
                    mapOf(
                        "sourceNodeId" to "string",
                        "sourceSamplerIndex" to "number",
                        "sourceSamplerLabel" to "string",
                        "sourceNodePath" to "string",
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "position" to "string",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "delete_node_open_plan",
                    "Delete node(s) and their children from the running BreakTest GUI plan. Use this to remove misplaced duplicate processors, extractors, assertions, samplers, controllers, or config elements after a GUI edit targets the wrong node. Prefer targetNodeId from find_open_plan_nodes or earlier edit results; node paths are the fallback and sampler indexes drift after structural edits. If duplicate identical node paths exist, pass targetOccurrenceIndex for one or deleteAllMatches=true for intentional duplicate cleanup.",
                    mapOf(
                        "targetNodeId" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "targetNodePath" to "string",
                        "targetOccurrenceIndex" to "number",
                        "deleteAllMatches" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "move_think_times_to_transactions_open_plan",
                    "Move standalone ThinkTime TestAction sampler nodes with timer children into the next Transaction Controller's transaction-level delay fields, then remove the standalone ThinkTime nodes by default. Use this for requests like moving separate think time timers to transaction level instead of probing or editing TransactionController.delayMode/delayMin/delayMax with literal replacement.",
                    mapOf(
                        "threadGroupName" to "string",
                        "removeOriginal" to "boolean",
                    ),
                    emptyList(),
                )
            )
            add(
                tool(
                    "inspect_jmx",
                    "Load a BreakTest/JMeter .jmx file and return a compact plan summary/DSL. Static browser asset samplers are omitted by default to keep AI analysis focused on functional requests; pass includeStaticAssets=true only when CSS/JS/font/image responses are directly relevant to correlation or a failure.",
                    mapOf(
                        "path" to "string",
                        "includeDsl" to "boolean",
                        "dslCharacterLimit" to "number",
                        "includeStaticAssets" to "boolean",
                    ),
                    listOf("path"),
                )
            )
            add(
                tool(
                    "validate_jmx",
                    "Run a bounded BreakTest/JMeter validation pass and return sampler evidence plus failure analysis. Use compact=true for the first repair run so one validation returns first-failure evidence, reached non-static samples, preFailureDynamicCandidates, and preFailureRequestCandidates without static asset noise.",
                    mapOf(
                        "path" to "string",
                        "timeoutSeconds" to "number",
                        "responseBodyLimit" to "number",
                        "requestBodyLimit" to "number",
                        "maxSamples" to "number",
                        "stopOnFirstFailure" to "boolean",
                        "ignoreStaticAssetFailures" to "boolean",
                        "includeDsl" to "boolean",
                        "dslCharacterLimit" to "number",
                        "includeStaticAssets" to "boolean",
                        "compact" to "boolean",
                        "compactSampleLimit" to "number",
                        "compactBodyLimit" to "number",
                    ),
                    listOf("path"),
                )
            )
            add(
                tool(
                    "apply_boundary_correlation",
                    "Add a Boundary Extractor to a source sampler and replace a literal in a target sampler subtree.",
                    mapOf(
                        "path" to "string",
                        "outputPath" to "string",
                        "sourceSamplerIndex" to "number",
                        "sourceSamplerLabel" to "string",
                        "targetSamplerIndex" to "number",
                        "targetSamplerLabel" to "string",
                        "variableName" to "string",
                        "leftBoundary" to "string",
                        "rightBoundary" to "string",
                        "literal" to "string",
                    ),
                    listOf("path", "variableName", "leftBoundary", "rightBoundary", "literal"),
                )
            )
            add(
                tool(
                    "repair_boundary_correlation_once",
                    "Run validation, apply the first inferred boundary-correlation edit, and save the updated .jmx.",
                    mapOf(
                        "path" to "string",
                        "outputPath" to "string",
                        "timeoutSeconds" to "number",
                        "responseBodyLimit" to "number",
                        "requestBodyLimit" to "number",
                        "maxSamples" to "number",
                        "stopOnFirstFailure" to "boolean",
                    ),
                    listOf("path"),
                )
            )
        }
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, String>,
        required: List<String>,
    ): ObjectNode =
        mapper.createObjectNode().apply {
            put("name", name)
            put("description", description)
            set<ObjectNode>(
                "inputSchema",
                mapper.createObjectNode().apply {
                    put("type", "object")
                    set<ObjectNode>(
                        "properties",
                        mapper.createObjectNode().apply {
                            for ((property, type) in properties) {
                                set<ObjectNode>(
                                    property,
                                    mapper.createObjectNode().apply {
                                        put("type", type)
                                    }
                                )
                            }
                        }
                    )
                    putArray("required").apply {
                        for (property in required) {
                            add(property)
                        }
                    }
                }
            )
        }

    private fun callTool(params: JsonNode): ObjectNode {
        val name = params.path("name").asText()
        val arguments = params.path("arguments")
        val payload = executeTool(name, arguments)
        val payloadText = mapper.writeValueAsString(payload)
        McpTrace.log(
            "tool_content name=$name responseBytes=${payloadText.toByteArray(Charsets.UTF_8).size}",
        )
        return mapper.createObjectNode().apply {
            putArray("content").add(
                mapper.createObjectNode().apply {
                    put("type", "text")
                    put("text", payloadText)
                }
            )
        }
    }

    private fun executeTool(name: String, arguments: JsonNode): Any {
        val startedAt = System.nanoTime()
        val argumentBytes = jsonByteSize(arguments)
        if (name.shouldMirrorActivity()) {
            postGuiActivityIfAvailable("started", "MCP tool `$name` started")
        }
        return try {
            when (name) {
                "gui_status" -> callGuiTool("status", arguments)
                "refresh_open_plan_from_file" -> callGuiTool("refresh_open_plan_from_file", arguments)
                "inspect_open_plan" -> callGuiTool("inspect_open_plan", arguments)
                "validate_open_plan" -> callGuiTool("validate_open_plan", arguments)
                "search_validated_response_open_plan" -> callGuiTool("search_validated_response_open_plan", arguments)
                "backup_open_plan" -> callGuiTool("backup_open_plan", arguments)
                "get_ai_knowledge_open_plan" -> callGuiTool("get_ai_knowledge_open_plan", arguments)
                "update_ai_knowledge_open_plan" -> callGuiTool("update_ai_knowledge_open_plan", arguments)
                "list_agent_changes_open_plan" -> callGuiTool("list_agent_changes_open_plan", arguments)
                "find_open_plan_nodes" -> callGuiTool("find_open_plan_nodes", arguments)
                "agent_activity" -> callGuiTool("agent_activity", arguments)
                "list_recorded_har_exchanges_open_plan" -> callGuiTool("list_recorded_har_exchanges_open_plan", arguments)
                "get_recorded_har_exchange_open_plan" -> callGuiTool("get_recorded_har_exchange_open_plan", arguments)
                "search_recorded_har_open_plan" -> callGuiTool("search_recorded_har_open_plan", arguments)
                "audit_recorded_har_correlations_open_plan" -> callGuiTool("audit_recorded_har_correlations_open_plan", arguments)
                "plan_repair_actions_open_plan" -> callGuiTool("plan_repair_actions_open_plan", arguments)
                "get_repair_action_open_plan" -> callGuiTool("get_repair_action_open_plan", arguments)
                "get_repair_actions_open_plan" -> callGuiTool("get_repair_actions_open_plan", arguments)
                "apply_repair_actions_open_plan" -> callGuiTool("apply_repair_actions_open_plan", arguments)
                "add_response_assertions_open_plan" -> callGuiTool("add_response_assertions_open_plan", arguments)
                "apply_boundary_correlation_open_plan" -> callGuiTool("apply_boundary_correlation_open_plan", arguments)
                "apply_regex_correlation_open_plan" -> callGuiTool("apply_regex_correlation_open_plan", arguments)
                "update_regex_extractor_open_plan" -> callGuiTool("update_regex_extractor_open_plan", arguments)
                "replace_literal_open_plan" -> callGuiTool("replace_literal_open_plan", arguments)
                "replace_literal_in_names_open_plan" -> callGuiTool("replace_literal_in_names_open_plan", arguments)
                "set_user_defined_variable_open_plan" -> callGuiTool("set_user_defined_variable_open_plan", arguments)
                "list_http_arguments_open_plan" -> callGuiTool("list_http_arguments_open_plan", arguments)
                "set_http_argument_encode_open_plan" -> callGuiTool("set_http_argument_encode_open_plan", arguments)
                "set_http_argument_value_open_plan" -> callGuiTool("set_http_argument_value_open_plan", arguments)
                "search_open_plan_values" -> callGuiTool("search_open_plan_values", arguments)
                "audit_dynamic_request_values_open_plan" -> callGuiTool("audit_dynamic_request_values_open_plan", arguments)
                "add_jsr223_open_plan" -> callGuiTool("add_jsr223_open_plan", arguments)
                "add_response_assertion_open_plan" -> callGuiTool("add_response_assertion_open_plan", arguments)
                "update_response_assertion_open_plan" -> callGuiTool("update_response_assertion_open_plan", arguments)
                "set_redirect_mode_open_plan" -> callGuiTool("set_redirect_mode_open_plan", arguments)
                "clone_node_open_plan" -> callGuiTool("clone_node_open_plan", arguments)
                "move_node_open_plan" -> callGuiTool("move_node_open_plan", arguments)
                "delete_node_open_plan" -> callGuiTool("delete_node_open_plan", arguments)
                "move_think_times_to_transactions_open_plan" -> callGuiTool("move_think_times_to_transactions_open_plan", arguments)
                "inspect_jmx" -> inspectJmx(arguments)
                "validate_jmx" -> validateJmx(arguments)
                "apply_boundary_correlation" -> applyBoundaryCorrelation(arguments)
                "repair_boundary_correlation_once" -> repairBoundaryCorrelationOnce(arguments)
                else -> throw IllegalArgumentException("Unknown tool: $name")
            }.also {
                val resultBytes = jsonByteSize(it)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                McpTrace.log(
                    "tool_telemetry name=$name elapsedMs=$elapsedMs argsBytes=$argumentBytes resultBytes=$resultBytes",
                )
                if (name.shouldMirrorActivity()) {
                    postGuiActivityIfAvailable("completed", "MCP tool `$name` completed", "${elapsedMs}ms")
                }
            }
        } catch (e: Exception) {
            if (name.shouldMirrorActivity()) {
                postGuiActivityIfAvailable("failed", "MCP tool `$name` failed", e.message ?: e.toString())
            }
            throw e
        }
    }

    private fun inspectJmx(arguments: JsonNode): Any {
        val tree = SaveService.loadTree(File(arguments.requiredText("path")))
        return BreakTestAgent().inspect(tree, dslCharacterLimit(arguments), includeStaticAssets(arguments))
    }

    private fun validateJmx(arguments: JsonNode): Any {
        val tree = SaveService.loadTree(File(arguments.requiredText("path")))
        val report = BreakTestAgent().inspectAndValidate(
            tree,
            optionsFrom(arguments),
            dslCharacterLimit(arguments),
            includeStaticAssets(arguments),
        )
        return if (arguments.path("compact").asBoolean(false)) {
            AgentReportCompactor.compactForRepair(
                report,
                sampleLimit = arguments.path("compactSampleLimit").asInt(20),
                bodyLimit = arguments.path("compactBodyLimit").asInt(1_500),
            )
        } else {
            report
        }
    }

    private fun String.shouldMirrorActivity(): Boolean =
        this !in setOf(
            "gui_status",
            "refresh_open_plan_from_file",
            "inspect_open_plan",
            "validate_open_plan",
            "search_validated_response_open_plan",
            "backup_open_plan",
            "get_ai_knowledge_open_plan",
            "update_ai_knowledge_open_plan",
            "list_agent_changes_open_plan",
            "agent_activity",
            "list_recorded_har_exchanges_open_plan",
            "get_recorded_har_exchange_open_plan",
            "search_recorded_har_open_plan",
            "audit_recorded_har_correlations_open_plan",
            "plan_repair_actions_open_plan",
            "get_repair_action_open_plan",
            "get_repair_actions_open_plan",
            "apply_repair_actions_open_plan",
            "add_response_assertions_open_plan",
            "apply_boundary_correlation_open_plan",
            "apply_regex_correlation_open_plan",
            "update_regex_extractor_open_plan",
            "replace_literal_open_plan",
            "replace_literal_in_names_open_plan",
            "set_user_defined_variable_open_plan",
            "list_http_arguments_open_plan",
            "set_http_argument_encode_open_plan",
            "set_http_argument_value_open_plan",
            "search_open_plan_values",
            "audit_dynamic_request_values_open_plan",
            "add_jsr223_open_plan",
            "add_response_assertion_open_plan",
            "update_response_assertion_open_plan",
            "set_redirect_mode_open_plan",
            "clone_node_open_plan",
            "move_node_open_plan",
            "delete_node_open_plan",
            "move_think_times_to_transactions_open_plan",
        )

    private fun postGuiActivityIfAvailable(level: String, message: String, details: String? = null) {
        runCatching {
            callGuiTool(
                "agent_activity",
                mapper.createObjectNode().apply {
                    put("level", level)
                    put("message", message)
                    put("source", "Codex MCP")
                    if (!details.isNullOrBlank()) {
                        put("details", details)
                    }
                },
            )
        }
    }

    private fun applyBoundaryCorrelation(arguments: JsonNode): Any {
        val path = File(arguments.requiredText("path"))
        val outputPath = File(arguments.path("outputPath").asText(path.path))
        val tree = SaveService.loadTree(path)
        val result = TestPlanEditor().applyBoundaryCorrelation(
            tree,
            BoundaryCorrelationRequest(
                sourceSamplerIndex = arguments.path("sourceSamplerIndex").takeIfPresent()?.asInt(),
                sourceSamplerLabel = arguments.path("sourceSamplerLabel").takeIfPresent()?.asText(),
                targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
                targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
                variableName = arguments.requiredText("variableName"),
                leftBoundary = arguments.requiredText("leftBoundary"),
                rightBoundary = arguments.requiredText("rightBoundary"),
                literal = arguments.requiredText("literal"),
            ),
        )
        outputPath.outputStream().use { SaveService.saveTree(tree, it) }
        return mapOf(
            "outputPath" to outputPath.path,
            "edit" to result,
        )
    }

    private fun repairBoundaryCorrelationOnce(arguments: JsonNode): Any {
        val path = File(arguments.requiredText("path"))
        val outputPath = File(arguments.path("outputPath").asText(path.path))
        val tree = SaveService.loadTree(path)
        val report = BreakTestAgent().inspectAndValidate(tree, optionsFrom(arguments))
        val candidate = report.analysis.correlationCandidates.firstOrNull()
            ?: return mapOf(
                "changed" to false,
                "reason" to "No correlation candidate was found in the validation evidence.",
                "report" to report,
            )
        val source = report.validation.samples.getOrNull(candidate.sourceSampleIndex)
            ?: throw IllegalStateException("Source sample ${candidate.sourceSampleIndex} was not captured")
        val boundaries = inferBoundaries(source.responseBody, candidate.literal)
        val edit = TestPlanEditor().applyBoundaryCorrelation(
            tree,
            BoundaryCorrelationRequest(
                sourceSamplerIndex = candidate.sourceSampleIndex,
                targetSamplerIndex = candidate.targetSampleIndex,
                variableName = candidate.variableName,
                leftBoundary = boundaries.first,
                rightBoundary = boundaries.second,
                literal = candidate.literal,
            ),
        )
        outputPath.outputStream().use { SaveService.saveTree(tree, it) }
        return mapOf(
            "changed" to true,
            "outputPath" to outputPath.path,
            "edit" to edit,
            "candidate" to candidate,
            "leftBoundary" to boundaries.first,
            "rightBoundary" to boundaries.second,
            "report" to report,
        )
    }

    private fun optionsFrom(arguments: JsonNode): AgentRunOptions =
        AgentRunOptions(
            timeout = Duration.ofSeconds(arguments.path("timeoutSeconds").asLong(30)),
            responseBodyLimit = arguments.path("responseBodyLimit").asInt(32 * 1024),
            requestBodyLimit = arguments.path("requestBodyLimit").asInt(16 * 1024),
            maxSamples = arguments.path("maxSamples").takeIfPresent()?.asInt(),
            stopOnFirstFailure = arguments.path("stopOnFirstFailure").asBoolean(false),
            ignoreStaticAssetFailures = arguments.path("ignoreStaticAssetFailures").asBoolean(false),
        )

    private fun jsonByteSize(value: Any?): Int =
        runCatching { mapper.writeValueAsBytes(value).size }
            .getOrDefault(0)

    internal fun dslCharacterLimit(arguments: JsonNode): Int? {
        if (arguments.path("includeDsl").takeIfPresent()?.asBoolean() != true) {
            return 0
        }
        return arguments.path("dslCharacterLimit").takeIfPresent()?.asInt() ?: DEFAULT_DSL_CHARACTER_LIMIT
    }

    private fun includeStaticAssets(arguments: JsonNode): Boolean =
        arguments.path("includeStaticAssets").asBoolean(false)

    private fun inferBoundaries(responseBody: String, literal: String): Pair<String, String> {
        val index = responseBody.indexOf(literal)
        require(index >= 0) {
            "Literal '$literal' was not found in source response body"
        }
        val left = responseBody.substring(maxOf(0, index - 80), index)
        val right = responseBody.substring(
            index + literal.length,
            minOf(responseBody.length, index + literal.length + 80),
        )
        require(left.isNotEmpty() && right.isNotEmpty()) {
            "Could not infer non-empty boundaries around literal '$literal'"
        }
        return left to right
    }

    private fun callGuiTool(name: String, arguments: JsonNode): Any {
        val descriptor = BreakTestAgentGuiService.descriptorFile()
        require(descriptor.isFile) {
            "BreakTest GUI agent is not running. Start BreakTest GUI from this build first, or check that breaktest.agent.enabled was not set to false."
        }
        val details = mapper.readTree(descriptor)
        val host = details.path("host").asText("127.0.0.1")
        val port = details.path("port").asInt()
        val socketPath = details.path("socketPath").asText("")
        val token = details.path("token").asText()
        require(port > 0 && token.isNotBlank()) {
            "BreakTest GUI agent descriptor is invalid: ${descriptor.path}"
        }
        val request = wireMapper.writeValueAsString(
            mapOf(
                "token" to token,
                "tool" to name,
                "arguments" to arguments,
            )
        )
        val response = callGuiAgent(descriptor, socketPath, host, port, request)
        val node = mapper.readTree(response)
        if (!node.path("ok").asBoolean(false)) {
            throw IllegalStateException(node.path("error").asText("BreakTest GUI agent request failed"))
        }
        return node.path("result")
    }

    private fun callGuiAgent(
        descriptor: File,
        socketPath: String,
        host: String,
        port: Int,
        request: String,
    ): String {
        val failures = mutableListOf<String>()
        if (socketPath.isNotBlank()) {
            runCatching {
                return callGuiUnixSocket(socketPath, request)
            }.onFailure {
                failures += "unix socket $socketPath: ${it.shortMessage()}"
            }
        }
        runCatching {
            return callGuiTcpSocket(host, port, request)
        }.onFailure {
            failures += "tcp $host:$port: ${it.shortMessage()}"
        }
        throw ConnectException(
            "Could not connect to the BreakTest GUI agent. The descriptor may be stale: ${descriptor.path}. " +
                "Click AI Auto Scripting in the currently running BreakTest GUI, or restart BreakTest from this build. " +
                "Connection attempts: ${failures.joinToString("; ")}"
        )
    }

    private fun callGuiTcpSocket(host: String, port: Int, request: String): String =
        Socket(host, port).use { socket ->
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write(request)
            writer.write("\n")
            writer.flush()
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                ?: throw IllegalStateException("BreakTest GUI agent closed the connection without a response")
        }

    private fun callGuiUnixSocket(socketPath: String, request: String): String =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.connect(UnixDomainSocketAddress.of(File(socketPath).toPath()))
            val writer = Channels.newWriter(socket, Charsets.UTF_8.newEncoder(), -1)
            writer.write(request)
            writer.write("\n")
            writer.flush()
            BufferedReader(Channels.newReader(socket, Charsets.UTF_8.newDecoder(), -1)).readLine()
                ?: throw IllegalStateException("BreakTest GUI agent closed the connection without a response")
        }

    private fun response(id: JsonNode, result: JsonNode): ObjectNode = mapper.createObjectNode().apply {
        put("jsonrpc", "2.0")
        set<JsonNode>("id", id)
        set<JsonNode>("result", result)
    }

    private fun response(id: JsonNode, result: Any): ObjectNode = response(id, mapper.valueToTree(result))

    private fun error(id: JsonNode, code: Int, message: String): ObjectNode = mapper.createObjectNode().apply {
        put("jsonrpc", "2.0")
        set<JsonNode>("id", id)
        set<ObjectNode>(
            "error",
            mapper.createObjectNode().apply {
                put("code", code)
                put("message", message)
            }
        )
    }

    private fun JsonNode.requiredText(name: String): String {
        val value = path(name)
        require(!value.isMissingNode && !value.isNull && value.asText().isNotBlank()) {
            "Missing required argument '$name'"
        }
        return value.asText()
    }

    private fun JsonNode.takeIfPresent(): JsonNode? =
        takeIf { !isMissingNode && !isNull }

    private fun Throwable.shortMessage(): String =
        message ?: javaClass.simpleName
}

private class StdioJsonRpcServer(
    private val handler: (ObjectNode) -> ObjectNode?,
) {
    private val mapper = ObjectMapper()
    private val input = BufferedInputStream(System.`in`)

    fun run() {
        McpTrace.log("stdio run")
        while (true) {
            val message = readMessage() ?: return
            McpTrace.log("stdio read ${message.size} bytes")
            val request = mapper.readTree(message) as? ObjectNode ?: continue
            val response = handler(request) ?: continue
            val responseBytes = mapper.writeValueAsBytes(response)
            McpTrace.log("stdio write ${responseBytes.size} bytes")
            writeMessage(responseBytes)
        }
    }

    private fun readMessage(): ByteArray? {
        var contentLength: Int? = null
        while (true) {
            val line = readAsciiLine() ?: run {
                McpTrace.log("stdio eof before header")
                return null
            }
            if (line.isEmpty()) {
                break
            }
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(separator + 1).trim().toInt()
            }
        }
        val length = contentLength ?: run {
            McpTrace.log("stdio missing content-length")
            return null
        }
        McpTrace.log("stdio content-length=$length")
        return input.readNBytes(length).takeIf { it.size == length }
    }

    private fun readAsciiLine(): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            if (next == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.lastIndex)
                }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes += next.toByte()
        }
    }

    private fun writeMessage(bytes: ByteArray) {
        val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        System.out.write(header)
        System.out.write(bytes)
        System.out.flush()
    }
}

private object McpTrace {
    private val traceFile: File? =
        System.getProperty("breaktest.mcp.trace")?.takeIf { it.isNotBlank() }?.let(::File)

    @Synchronized
    fun log(message: String) {
        val file = traceFile ?: return
        file.parentFile?.mkdirs()
        file.appendText("${java.time.Instant.now()} $message\n")
    }
}
