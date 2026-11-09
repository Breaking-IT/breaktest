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
                "remove=true takes the Thread Group out. Without scenarioNodeId the single enabled " +
                "scenario is used. get_element_open_plan on a scenario lists its workloads.",
            mapOf(
                "scenarioNodeId" to "string", "scenarioNodePath" to "string", "threadGroupName" to "string",
                "threads" to "string", "rampUpSeconds" to "string", "durationSeconds" to "string",
                "loops" to "string", "enabled" to "boolean", "remove" to "boolean",
            ),
            listOf("threadGroupName"),
        ),
        Spec(
            "batch_open_plan",
            "Run several plan tools in one call, in order. steps is an array of {tool, arguments, as}. " +
                "A later step can use an earlier result: the whole string \"@name\" becomes that step's " +
                "node id and \"@name.field\" a field of its result. Stops at the first failing step " +
                "unless stopOnError=false; steps already applied stay applied and remain undoable. " +
                "Use this for every multi-step edit instead of one call per node.",
            mapOf("steps" to "array[object]", "stopOnError" to "boolean"),
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
                "to check a script; use this only when asked to run the test.",
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

    /** Names of [guiTools]. */
    public val guiToolNames: Set<String> = guiTools.mapTo(LinkedHashSet()) { it.name }

    /**
     * The general-purpose set advertised when the MCP server runs with the `core` toolset. Every
     * other tool stays callable and discoverable through `describe_tools`.
     */
    public val coreToolNames: Set<String> = guiToolNames + setOf(
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
            "save_open_plan when asked. Address nodes by nodeId. start_test sends real load: only " +
            "on explicit request. More specialised tools are listed by describe_tools."
}
