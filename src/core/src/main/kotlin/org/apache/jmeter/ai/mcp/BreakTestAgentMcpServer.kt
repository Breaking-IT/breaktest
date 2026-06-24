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
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
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

    private val mapper = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
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
        set<ObjectNode>("capabilities", mapper.createObjectNode().apply {
            set<ObjectNode>("tools", mapper.createObjectNode())
        })
        set<ObjectNode>("serverInfo", mapper.createObjectNode().apply {
            put("name", "breaktest-agent")
            put("version", "0.1.0")
        })
    }

    private fun emptyListResult(name: String): ObjectNode = mapper.createObjectNode().apply {
        putArray(name)
    }

    private fun toolsListResult(): ObjectNode = mapper.createObjectNode().apply {
        putArray("tools").apply {
            add(tool(
                "gui_status",
                "Return status for the running BreakTest GUI agent service and its open plan.",
                emptyMap(),
                emptyList(),
            ))
            add(tool(
                "inspect_open_plan",
                "Inspect the test plan currently open in the running BreakTest GUI.",
                mapOf(
                    "includeDsl" to "boolean",
                    "dslCharacterLimit" to "number",
                ),
                emptyList(),
            ))
            add(tool(
                "validate_open_plan",
                "Run a bounded validation pass for the test plan currently open in the running BreakTest GUI.",
                mapOf(
                    "timeoutSeconds" to "number",
                    "responseBodyLimit" to "number",
                    "requestBodyLimit" to "number",
                    "maxSamples" to "number",
                    "stopOnFirstFailure" to "boolean",
                    "includeDsl" to "boolean",
                    "dslCharacterLimit" to "number",
                ),
                emptyList(),
            ))
            add(tool(
                "backup_open_plan",
                "Save a timestamped backup copy of the currently open BreakTest GUI plan before live edits.",
                emptyMap(),
                emptyList(),
            ))
            add(tool(
                "get_ai_knowledge_open_plan",
                "Read or create the BreakTest AI Knowledge element in the currently open GUI plan. Returns the selected node path, whether the selected knowledge is default/empty, and all available knowledge nodes so agents can confirm they loaded the intended project learnings.",
                emptyMap(),
                emptyList(),
            ))
            add(tool(
                "update_ai_knowledge_open_plan",
                "Replace the BreakTest AI Knowledge JSON in the currently open GUI plan. Full AI repair runs must call this before finishing. Preserve existing arrays and append reusable run learnings to projectHints, correlationPatterns, assertionPatterns, variableMappings, knownDynamicFields, timestampRules, transactionDependencies, and learnedFromThreadGroups. Include selected Thread Group and transaction/request evidence. Default/empty knowledge is rejected unless allowDefault=true.",
                mapOf(
                    "knowledgeJson" to "string",
                    "knowledge" to "object",
                    "summary" to "string",
                    "allowDefault" to "boolean",
                ),
                emptyList(),
            ))
            add(tool(
                "list_agent_changes_open_plan",
                "Return the current AI Auto Scripting change table from the running BreakTest GUI.",
                emptyMap(),
                emptyList(),
            ))
            add(tool(
                "agent_activity",
                "Post a live Codex/agent progress update into the running BreakTest AI Auto Scripting window.",
                mapOf(
                    "message" to "string",
                    "level" to "string",
                    "source" to "string",
                    "details" to "string",
                ),
                listOf("message"),
            ))
            add(tool(
                "apply_boundary_correlation_open_plan",
                "Add a Boundary Extractor to the running BreakTest GUI plan and replace a literal in the open plan. Provide evidenceSource and evidence details proving the extractor boundaries came from a validated response, recorded response, or AI Knowledge. Static inference requires allowStaticInference=true and is logged as unvalidated.",
                mapOf(
                    "sourceSamplerIndex" to "number",
                    "sourceSamplerLabel" to "string",
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
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
            ))
            add(tool(
                "apply_regex_correlation_open_plan",
                "Add a Regex Extractor to the running BreakTest GUI plan and replace a literal. If no target sampler is specified, the literal is replaced across request/config data in the open plan while element names remain unchanged. useField accepts body, headers, request_headers, unescaped, as_document, url, code, or message. Provide evidenceSource and evidence details proving the regex came from a validated response, recorded response, or AI Knowledge. Static inference requires allowStaticInference=true and is logged as unvalidated.",
                mapOf(
                    "sourceSamplerIndex" to "number",
                    "sourceSamplerLabel" to "string",
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "variableName" to "string",
                    "regex" to "string",
                    "template" to "string",
                    "matchNumber" to "string",
                    "defaultValue" to "string",
                    "useField" to "string",
                    "literal" to "string",
                    "failOnNoMatch" to "boolean",
                    "evidenceSource" to "string",
                    "evidence" to "string",
                    "allowStaticInference" to "boolean",
                ),
                listOf("variableName", "regex", "literal", "evidenceSource", "evidence"),
            ))
            add(tool(
                "update_regex_extractor_open_plan",
                "Update an existing Regex Extractor under a source sampler by variableName. Use this instead of replace_literal_open_plan for changing regex, useField, template, matchNumber, defaultValue, or failOnNoMatch.",
                mapOf(
                    "sourceSamplerIndex" to "number",
                    "sourceSamplerLabel" to "string",
                    "variableName" to "string",
                    "regex" to "string",
                    "template" to "string",
                    "matchNumber" to "string",
                    "defaultValue" to "string",
                    "useField" to "string",
                    "failOnNoMatch" to "boolean",
                ),
                listOf("variableName"),
            ))
            add(tool(
                "replace_literal_open_plan",
                "Replace a literal in one target sampler subtree, or in the whole running BreakTest GUI plan when no target sampler is specified. This reaches nested HTTP arguments, POST bodies, paths, headers, and child element properties. Element names are ignored by default; set includeNames=true only for an intentional rename. For credential replacement, set excludeUserDefinedVariables=true so the Test Plan User Defined Variables table keeps the original secret value. Replacements using invalid ${'$'}{__UUID(...)} syntax are rejected; use ${'$'}{__UUID} or a JSR223/setup variable for reusable UUIDs.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "literal" to "string",
                    "replacement" to "string",
                    "includeNames" to "boolean",
                    "excludeUserDefinedVariables" to "boolean",
                ),
                listOf("literal", "replacement"),
            ))
            add(tool(
                "replace_literal_in_names_open_plan",
                "Replace a literal only in element names in the running BreakTest GUI plan, or under one target sampler when specified. Use this after parameterizing request data to turn stale UUIDs/IDs in sampler labels into static display placeholders such as {basket_page_id}. This rejects ${'$'}{variable} replacements because element names must stay static.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "literal" to "string",
                    "replacement" to "string",
                ),
                listOf("literal", "replacement"),
            ))
            add(tool(
                "set_user_defined_variable_open_plan",
                "Create or update a top-level Test Plan User Defined Variable in the running BreakTest GUI plan. For credentials, pass the preserved original literal value, not the ${'$'}{variable} reference; self-referential values are rejected.",
                mapOf(
                    "name" to "string",
                    "value" to "string",
                ),
                listOf("name", "value"),
            ))
            add(tool(
                "list_http_arguments_open_plan",
                "List HTTP arguments exposed by a target sampler, including actual argument names, values, and alwaysEncode flags. Use this before credential or POST form edits when names are uncertain.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                ),
                emptyList(),
            ))
            add(tool(
                "set_http_argument_encode_open_plan",
                "Set the alwaysEncode flag on matching HTTP arguments in a sampler. Use this before replacing encoded form values with \${variable} references when the variable syntax would otherwise be percent-encoded.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "argumentName" to "string",
                    "argumentValue" to "string",
                    "alwaysEncode" to "boolean",
                ),
                listOf("alwaysEncode"),
            ))
            add(tool(
                "set_http_argument_value_open_plan",
                "Set the value of a matching HTTP argument in a target sampler, optionally setting alwaysEncode in the same edit. Use this for POST form arguments such as username/password when literal replacement or encoding edits are ambiguous.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "argumentName" to "string",
                    "argumentValue" to "string",
                    "newValue" to "string",
                    "alwaysEncode" to "boolean",
                ),
                listOf("newValue"),
            ))
            add(tool(
                "search_open_plan_values",
                "Search string-valued open-plan properties for a literal or regex. By default element names are ignored, so use this to verify old UUIDs, credentials, tokens, and stale IDs are gone from request data after edits. For credential verification, set excludeUserDefinedVariables=true so the original secret stored in the Test Plan variables table is not counted as a stale request value.",
                mapOf(
                    "literal" to "string",
                    "regex" to "string",
                    "includeNames" to "boolean",
                    "excludeUserDefinedVariables" to "boolean",
                    "maxMatches" to "number",
                ),
                emptyList(),
            ))
            add(tool(
                "audit_dynamic_request_values_open_plan",
                "Audit the open GUI plan for hard-coded request-looking dynamic values in paths, query strings, bodies, cookies, and headers, including UUIDs, bearer/JWT tokens, csrf/request-verification tokens, credentials, long opaque IDs, numeric IDs, drawId-style fields, formatted date-times, and epoch-millisecond timestamps. Static browser assets such as CSS, JavaScript, images, fonts, and source maps are ignored by default; set includeStaticAssets=true only when those asset responses are relevant to a failure. Use this after inspection and again before finishing; a green validation run does not clear unresolved high-confidence candidates.",
                mapOf(
                    "maxCandidates" to "number",
                    "includeStaticAssets" to "boolean",
                    "threadGroupName" to "string",
                ),
                emptyList(),
            ))
            add(tool(
                "add_jsr223_open_plan",
                "Add a GUI-backed JSR223 Groovy element to the test plan, an enabled Thread Group, or a target sampler. Supports elementType sampler, preprocessor, postprocessor, assertion, or timer. Preprocessors, postprocessors, and assertions must target a specific sampler; use elementType=sampler for setup code under a Thread Group.",
                mapOf(
                    "parentType" to "string",
                    "threadGroupName" to "string",
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "elementType" to "string",
                    "name" to "string",
                    "language" to "string",
                    "parameters" to "string",
                    "cacheKey" to "string",
                    "script" to "string",
                ),
                listOf("script"),
            ))
            add(tool(
                "add_response_assertion_open_plan",
                "Add a Response Assertion to a sampler in the running BreakTest GUI plan. Assertion patterns must be meaningful response markers; weak single-word/generic patterns such as tickets, succes, Mijn, or juli are rejected unless allowWeakPattern=true. Provide evidenceSource and evidence details proving the marker came from a validated response, recorded response, or AI Knowledge. Static inference requires allowStaticInference=true and is logged as unvalidated.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "assertionName" to "string",
                    "pattern" to "string",
                    "field" to "string",
                    "matchType" to "string",
                    "allowWeakPattern" to "boolean",
                    "evidenceSource" to "string",
                    "evidence" to "string",
                    "allowStaticInference" to "boolean",
                ),
                listOf("pattern", "evidenceSource", "evidence"),
            ))
            add(tool(
                "update_response_assertion_open_plan",
                "Update one existing Response Assertion under a target sampler. Use this to fix assertion pattern, field, or match type without touching request bodies or other sampler data. Provide assertionName and/or currentPattern to identify the assertion. Weak single-word/generic patterns are rejected unless allowWeakPattern=true.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "assertionName" to "string",
                    "currentPattern" to "string",
                    "pattern" to "string",
                    "field" to "string",
                    "matchType" to "string",
                    "allowWeakPattern" to "boolean",
                ),
                listOf("pattern"),
            ))
            add(tool(
                "set_redirect_mode_open_plan",
                "Set followRedirects and/or autoRedirects on an HTTP sampler in the running BreakTest GUI plan. Use only as a diagnostic edit after inspection proves an intermediate response/header is hidden by redirect handling; post the reason and revalidate immediately because redirect changes can alter auth/payment behavior.",
                mapOf(
                    "targetSamplerIndex" to "number",
                    "targetSamplerLabel" to "string",
                    "followRedirects" to "boolean",
                    "autoRedirects" to "boolean",
                ),
                emptyList(),
            ))
            add(tool(
                "move_think_times_to_transactions_open_plan",
                "Move standalone ThinkTime TestAction sampler nodes with timer children into the next Transaction Controller's transaction-level delay fields, then remove the standalone ThinkTime nodes by default. Use this for requests like moving separate think time timers to transaction level instead of probing or editing TransactionController.delayMode/delayMin/delayMax with literal replacement.",
                mapOf(
                    "threadGroupName" to "string",
                    "removeOriginal" to "boolean",
                ),
                emptyList(),
            ))
            add(tool(
                "inspect_jmx",
                "Load a BreakTest/JMeter .jmx file and return a compact plan summary/DSL.",
                mapOf(
                    "path" to "string",
                    "includeDsl" to "boolean",
                    "dslCharacterLimit" to "number",
                ),
                listOf("path"),
            ))
            add(tool(
                "validate_jmx",
                "Run a bounded BreakTest/JMeter validation pass and return sampler evidence plus failure analysis.",
                mapOf(
                    "path" to "string",
                    "timeoutSeconds" to "number",
                    "responseBodyLimit" to "number",
                    "requestBodyLimit" to "number",
                    "maxSamples" to "number",
                    "stopOnFirstFailure" to "boolean",
                    "includeDsl" to "boolean",
                    "dslCharacterLimit" to "number",
                ),
                listOf("path"),
            ))
            add(tool(
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
            ))
            add(tool(
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
            ))
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
            set<ObjectNode>("inputSchema", mapper.createObjectNode().apply {
                put("type", "object")
                set<ObjectNode>("properties", mapper.createObjectNode().apply {
                    for ((property, type) in properties) {
                        set<ObjectNode>(property, mapper.createObjectNode().apply {
                            put("type", type)
                        })
                    }
                })
                putArray("required").apply {
                    for (property in required) {
                        add(property)
                    }
                }
            })
        }

    private fun callTool(params: JsonNode): ObjectNode {
        val name = params.path("name").asText()
        val arguments = params.path("arguments")
        val payload = executeTool(name, arguments)
        return mapper.createObjectNode().apply {
            putArray("content").add(
                mapper.createObjectNode().apply {
                    put("type", "text")
                    put("text", mapper.writeValueAsString(payload))
                }
            )
        }
    }

    private fun executeTool(name: String, arguments: JsonNode): Any {
        val startedAt = System.nanoTime()
        if (name.shouldMirrorActivity()) {
            postGuiActivityIfAvailable("started", "MCP tool `$name` started")
        }
        return try {
            when (name) {
                "gui_status" -> callGuiTool("status", arguments)
                "inspect_open_plan" -> callGuiTool("inspect_open_plan", arguments)
                "validate_open_plan" -> callGuiTool("validate_open_plan", arguments)
                "backup_open_plan" -> callGuiTool("backup_open_plan", arguments)
                "get_ai_knowledge_open_plan" -> callGuiTool("get_ai_knowledge_open_plan", arguments)
                "update_ai_knowledge_open_plan" -> callGuiTool("update_ai_knowledge_open_plan", arguments)
                "list_agent_changes_open_plan" -> callGuiTool("list_agent_changes_open_plan", arguments)
                "agent_activity" -> callGuiTool("agent_activity", arguments)
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
                "move_think_times_to_transactions_open_plan" -> callGuiTool("move_think_times_to_transactions_open_plan", arguments)
                "inspect_jmx" -> inspectJmx(arguments)
                "validate_jmx" -> validateJmx(arguments)
                "apply_boundary_correlation" -> applyBoundaryCorrelation(arguments)
                "repair_boundary_correlation_once" -> repairBoundaryCorrelationOnce(arguments)
                else -> throw IllegalArgumentException("Unknown tool: $name")
            }.also {
                if (name.shouldMirrorActivity()) {
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
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
        return BreakTestAgent().inspect(tree, dslCharacterLimit(arguments))
    }

    private fun validateJmx(arguments: JsonNode): Any {
        val tree = SaveService.loadTree(File(arguments.requiredText("path")))
        return BreakTestAgent().inspectAndValidate(
            tree,
            optionsFrom(arguments),
            dslCharacterLimit(arguments),
        )
    }

    private fun String.shouldMirrorActivity(): Boolean =
        this !in setOf(
            "gui_status",
            "inspect_open_plan",
            "validate_open_plan",
            "backup_open_plan",
            "get_ai_knowledge_open_plan",
            "update_ai_knowledge_open_plan",
            "list_agent_changes_open_plan",
            "agent_activity",
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
        )

    internal fun dslCharacterLimit(arguments: JsonNode): Int? {
        if (arguments.path("includeDsl").takeIfPresent()?.asBoolean() != true) {
            return 0
        }
        return arguments.path("dslCharacterLimit").takeIfPresent()?.asInt() ?: DEFAULT_DSL_CHARACTER_LIMIT
    }

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
        set<ObjectNode>("error", mapper.createObjectNode().apply {
            put("code", code)
            put("message", message)
        })
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
