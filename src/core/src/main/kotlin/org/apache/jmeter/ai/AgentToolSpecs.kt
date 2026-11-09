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

package org.apache.jmeter.ai

/**
 * Declares the general plan tools once. The MCP server advertises and forwards them and the
 * GUI service executes them, so adding a tool here needs no second or third registration.
 */
public object AgentToolSpecs {
    /** [properties] maps an argument name to `string`, `number`, `boolean`, `object` or `array[type]`. */
    public data class Spec(
        val name: String,
        val description: String,
        val properties: Map<String, String> = emptyMap(),
        val required: List<String> = emptyList(),
    )

    private val target = mapOf("targetNodeId" to "string", "targetNodePath" to "string")

    /** Tools executed by the running GUI. */
    public val guiTools: List<Spec> = listOf(
        Spec(
            "get_plan_outline",
            "Read the open plan as a compact indented outline, one node per line: nodeId, type, name, " +
                "and for HTTP requests the method and path. Start here instead of inspect_open_plan. " +
                "Static assets are folded into a count unless includeStaticAssets=true. Scope with " +
                "underNodeId/underNodePath and limit with maxDepth.",
            mapOf(
                "underNodeId" to "string", "underNodePath" to "string", "maxDepth" to "number",
                "maxNodes" to "number", "includeStaticAssets" to "boolean",
            ),
        ),
        Spec(
            "get_element_open_plan",
            "Read one node's current settings: non-empty properties, table rows (HTTP arguments, headers, " +
                "variables) and direct children. raw=true adds every stored property key and value, which " +
                "are the keys accepted by rawProperties when configuring.",
            target + mapOf("raw" to "boolean"),
        ),
        Spec(
            "set_enabled_open_plan",
            "Enable or disable a node, for example a Thread Group that must not run. Undoable.",
            target + mapOf("enabled" to "boolean"),
            listOf("enabled"),
        ),
        Spec(
            "set_scenario_workload",
            "Add or change the load a scenario gives one Thread Group: threads (virtual users), " +
                "rampUpSeconds, durationSeconds (empty text removes the time limit) and loops. A real " +
                "run only includes Thread Groups that have a workload in the enabled scenario. " +
                "model is Closed or Open (openSchedule, openMaxThreads); pacingMillis sets fixed pacing, " +
                "pacingMinMillis with pacingMaxMillis random pacing, pacingMillis=0 none. " +
                "remove=true takes the Thread Group out. Without scenarioNodeId the single enabled " +
                "scenario is used. get_element_open_plan on a scenario lists its workloads.",
            mapOf(
                "scenarioNodeId" to "string", "scenarioNodePath" to "string", "threadGroupName" to "string",
                "threads" to "string", "rampUpSeconds" to "string", "durationSeconds" to "string",
                "loops" to "string", "enabled" to "boolean", "remove" to "boolean",
                "model" to "string", "delaySeconds" to "string", "closedSchedule" to "string",
                "openSchedule" to "string", "openMaxThreads" to "string", "pacingMillis" to "string",
                "pacingMinMillis" to "string", "pacingMaxMillis" to "string", "profile" to "string",
            ),
            listOf("threadGroupName"),
        ),
        Spec(
            "batch_open_plan",
            "Run several plan tools in one call, in order. steps is an array of {tool, arguments, as}. " +
                "A later step can use an earlier result: the whole string \"@name\" becomes that step's " +
                "node id and \"@name.field\" a field of its result. Stops at the first failing step " +
                "unless stopOnError=false; steps already applied stay applied and remain undoable. " +
                "Edit steps return only their node id and path (detail=full returns everything); read " +
                "steps return their full result. Use this for every multi-step edit instead of one call per node.",
            mapOf("steps" to "array[object]", "stopOnError" to "boolean", "detail" to "string"),
            listOf("steps"),
        ),
        Spec(
            "save_open_plan",
            "Save the open plan. A plan that was never saved needs path (a .jmx file); an existing other " +
                "file is only replaced with overwrite=true.",
            mapOf("path" to "string", "overwrite" to "boolean"),
        ),
        Spec(
            "start_test",
            "Start the open plan as a real test run with its configured load. The user is asked to " +
                "confirm in the GUI unless confirmation is switched off there. Use validate_open_plan " +
                "to check a script; use this only when asked to run the test. scenarioNodeId runs that " +
                "scenario instead of the enabled one. Follow the run with test_results.",
            mapOf("scenarioNodeId" to "string", "scenarioNodePath" to "string"),
        ),
        Spec(
            "stop_test",
            "Stop the running test. mode=shutdown lets virtual users finish their current sample; " +
                "mode=stop (default) interrupts them.",
            mapOf("mode" to "string"),
        ),
        Spec(
            "test_status",
            "Report whether a test is running, for how long, and the active, started and finished " +
                "virtual user counts.",
        ),
    )

    private val moreGuiTools: List<Spec> = listOf(
        Spec(
            "test_results",
            "Results of the current or last run: totals, one row per request and transaction (count, " +
                "errors, average, p90, p95, max, rate), the most frequent error kinds and the first " +
                "failing samples with response head. Percentiles are approximate.",
            mapOf("maxLabels" to "number", "errorSamples" to "number"),
        ),
        Spec(
            "get_log",
            "Tail of the BreakTest log. level=warn or error keeps only those lines; contains filters by text.",
            mapOf("lines" to "number", "level" to "string", "contains" to "string"),
        ),
        Spec(
            "add_http_request",
            "Add an HTTP request from a URL in one step: protocol, host, port, path and query parameters " +
                "are split out. A URL starting with / or useDefaults=true leaves host and protocol to HTTP " +
                "Request Defaults. body sets a raw request body; headers sets the request's own headers. " +
                "targetNodeId/targetNodePath is the parent.",
            target + mapOf(
                "url" to "string", "method" to "string", "name" to "string", "body" to "string",
                "headers" to "object", "useDefaults" to "boolean",
            ),
            listOf("url"),
        ),
        Spec(
            "export_subtree",
            "Return a node and everything under it as JMX XML text, for edits that are easier as text " +
                "than as many tool calls. Change the text and pass it to import_subtree.",
            target + mapOf("maxChars" to "number"),
        ),
        Spec(
            "import_subtree",
            "Insert JMX XML text (from export_subtree, edited or written by hand) into the open plan. " +
                "replaceNodeId swaps an existing node for it in place; otherwise it is added under " +
                "targetNodeId/targetNodePath at position first_child or last_child. Undoable.",
            target + mapOf("xml" to "string", "replaceNodeId" to "string", "position" to "string"),
            listOf("xml"),
        ),
        Spec("undo", "Undo the last change to the open plan. Returns whether more can be undone."),
        Spec("redo", "Redo the last undone change."),
        Spec(
            "select_node",
            "Select a node in the BreakTest window and scroll to it, to show the user where something is.",
            target,
        ),
        Spec(
            "open_plan",
            "Open a .jmx plan in the BreakTest window, replacing the open one. Unsaved changes block " +
                "this unless discardChanges=true.",
            mapOf("path" to "string", "discardChanges" to "boolean"),
            listOf("path"),
        ),
        Spec(
            "new_plan",
            "Start an empty plan. Unsaved changes block this unless discardChanges=true.",
            mapOf("discardChanges" to "boolean"),
        ),
        Spec(
            "add_scenario",
            "Add a scenario. enable=true makes it the enabled scenario and disables the others. Give it " +
                "load with set_scenario_workload.",
            mapOf("name" to "string", "enable" to "boolean"),
            listOf("name"),
        ),
        Spec(
            "add_profile",
            "Add a profile (a named set of configuration that scenarios and workloads can select). " +
                "makeDefault=true makes it the default profile.",
            mapOf("name" to "string", "makeDefault" to "boolean"),
            listOf("name"),
        ),
        Spec(
            "list_functions",
            "List the built-in functions usable in any field as \${__name(args)}, with their arguments. " +
                "query filters by name.",
            mapOf("query" to "string"),
        ),
        Spec(
            "list_variables",
            "List the variables the open plan defines (user defined variables, extractors, CSV data, " +
                "counters) with the node that defines each.",
        ),
    )

    /** Tools that the MCP server answers itself, without a running GUI. */
    public val serverTools: List<Spec> = listOf(
        Spec(
            "list_instances",
            "List the running BreakTest windows with their process id, open plan and unsaved state.",
        ),
        Spec(
            "select_instance",
            "Send all following calls to the BreakTest window with this process id (from list_instances).",
            mapOf("pid" to "number"),
            listOf("pid"),
        ),
        Spec(
            "open_plan_headless",
            "Open a .jmx plan inside this server, with no BreakTest window, so the plan tools work on a " +
                "file (for CI or a machine without a display). Following calls act on that plan; " +
                "save_open_plan writes it back. Real test runs and window actions are not available.",
            mapOf("path" to "string"),
            listOf("path"),
        ),
    )

    /** Every tool the running GUI executes. */
    public val allGuiTools: List<Spec> = guiTools + moreGuiTools

    public const val TIER_READ: Int = 0
    public const val TIER_EDIT: Int = 1
    public const val TIER_RUN: Int = 2
    private val readTools = setOf(
        "status", "gui_status", "test_status", "test_results", "agent_activity", "select_node",
        "export_subtree", "batch_open_plan", "describe_tools", "describe_element", "select_instance",
    )
    private val readPrefixes = listOf("get_", "list_", "find_", "search_", "inspect_", "audit_")
    private val runTools = setOf("start_test", "stop_test")

    /** The permission a tool needs: reading, editing (including validation runs) or running real load. */
    public fun tier(tool: String): Int = when {
        tool in runTools -> TIER_RUN
        tool in readTools || readPrefixes.any(tool::startsWith) -> TIER_READ
        else -> TIER_EDIT
    }

    public fun tierName(tier: Int): String = when (tier) {
        TIER_READ -> "read"
        TIER_EDIT -> "edit"
        else -> "run"
    }

    /** Names of [allGuiTools]. */
    public val guiToolNames: Set<String> = allGuiTools.mapTo(LinkedHashSet()) { it.name }

    /**
     * The general-purpose set advertised when the MCP server runs with the `core` toolset. Every
     * other tool stays callable and discoverable through `describe_tools`.
     */
    public val coreToolNames: Set<String> = guiTools.mapTo(LinkedHashSet()) { it.name } + setOf(
        "test_results",
        "add_http_request",
        "export_subtree",
        "import_subtree",
        "undo",
        "open_plan",
        "gui_status",
        "describe_tools",
        "find_open_plan_nodes",
        "search_open_plan_values",
        "list_available_elements",
        "describe_element",
        "add_element_open_plan",
        "configure_element_open_plan",
        "clone_node_open_plan",
        "move_node_open_plan",
        "delete_node_open_plan",
        "replace_literal_open_plan",
        "apply_regex_correlation_open_plan",
        "add_jsr223_open_plan",
        "add_response_assertion_open_plan",
        "validate_open_plan",
        "search_validated_response_open_plan",
    )

    /** Sent to MCP clients on initialize so every harness starts with the same workflow. */
    public const val INSTRUCTIONS: String =
        "BreakTest tools act on the plan open in the running BreakTest GUI; edits appear there live " +
            "and are undoable. Workflow: get_plan_outline to see the plan and node ids; " +
            "get_element_open_plan to read a node; describe_element before setting properties on a " +
            "new element type; batch_open_plan to apply all edits of a task in one call; " +
            "validate_open_plan (scoped with threadGroupName) to prove the script works; " +
            "save_open_plan when asked. Address nodes by nodeId. A result with " +
            "planChangedSinceLastCall=true means the user edited the plan: re-read the outline. " +
            "start_test sends real load: only on explicit request, then test_results. More " +
            "specialised tools are listed by describe_tools."
}
