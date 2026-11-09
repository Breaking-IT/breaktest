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

package org.apache.jmeter.ai.gui

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.jmeter.JMeter
import org.apache.jmeter.ai.AgentElementCatalog
import org.apache.jmeter.ai.AgentElementTables
import org.apache.jmeter.ai.AgentStaticAssetClassifier
import org.apache.jmeter.ai.AgentToolSpecs
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.action.ActionNames
import org.apache.jmeter.gui.action.ActionRouter
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.ScenarioException
import org.apache.jmeter.scenario.ScenarioResolver
import org.apache.jmeter.scenario.ScenarioWorkload
import org.apache.jmeter.scenario.gui.ScenarioGui
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.JMeterContextService
import org.apache.jmeter.util.JMeterUtils
import java.awt.event.ActionEvent
import java.io.File
import java.time.Instant
import javax.swing.JOptionPane

/**
 * General plan tools declared in [org.apache.jmeter.ai.AgentToolSpecs]: compact reads, batched
 * edits, save and run control. They exist so a coding agent needs few, small round trips.
 */
internal object AgentPlanTools {
    /** Set to false to let an agent start a test without the confirmation dialog. */
    const val CONFIRM_RUN_PROPERTY = "breaktest.agent.confirm_run"

    private const val DEFAULT_OUTLINE_NODES = 400
    private const val MAX_BATCH_STEPS = 100
    private const val MAX_PROPERTY_CHARS = 4000
    private const val MAX_RAW_PROPERTY_CHARS = 2000
    private const val MAX_RAW_DEPTH = 4
    private const val SAVE_WAIT_MILLIS = 15_000L
    private const val START_WAIT_MILLIS = 3_000L
    private val REFERENCE = Regex("""^@([A-Za-z_][A-Za-z0-9_]*)((?:\.[A-Za-z0-9_]+)*)$""")
    private val NODE_ID_FIELDS = listOf("nodeId", "clonedNodeId", "extractorNodeId")
    private val BATCH_ALIASES = mapOf("gui_status" to "status")

    private val mapper = ObjectMapper()
    private val service = BreakTestAgentGuiService

    /** Returns null for a tool this object does not implement. */
    fun handle(tool: String, arguments: JsonNode): Any? = when (tool) {
        "get_plan_outline" -> outline(arguments)
        "get_element_open_plan" -> element(arguments)
        "set_enabled_open_plan" -> setEnabled(arguments)
        "set_scenario_workload" -> setScenarioWorkload(arguments)
        "batch_open_plan" -> batch(arguments)
        "save_open_plan" -> save(arguments)
        "start_test" -> startTest(arguments)
        "stop_test" -> stopTest(arguments)
        "test_status" -> testStatus()
        else -> AgentWorkbenchTools.handle(tool, arguments)
    }

    private fun gui(): GuiPackage = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")

    private fun JsonNode.present(): JsonNode? = takeIf { !isMissingNode && !isNull }

    private fun JsonNode.text(): String? = present()?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun callString(target: Any, method: String): String? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? String }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun children(node: JMeterTreeNode): List<JMeterTreeNode> =
        (0 until node.childCount).mapNotNull { node.getChildAt(it) as? JMeterTreeNode }

    private fun typeName(element: TestElement): String = when (val simple = element.javaClass.simpleName) {
        "HTTPSamplerProxy" -> "HTTP"
        else -> simple
    }

    private fun summaryLine(node: JMeterTreeNode): String {
        val element = node.testElement
        val name = element.name.orEmpty()
        val details = buildList {
            val path = callString(element, "getPath")
            callString(element, "getMethod")?.takeIf { path != null }?.let { add(if (path == name) it else "$it $path") }
            (callString(element, "getRefName") ?: callString(element, "getVariableName"))?.let { add("var=$it") }
            if (!node.isEnabled) add("disabled")
        }
        return "${service.nodeId(node)} ${typeName(element)} $name" +
            if (details.isEmpty()) "" else " [${details.joinToString(", ")}]"
    }

    private fun outline(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val gui = gui()
        gui.updateCurrentNode()
        val scoped = arguments.path("underNodeId").text() != null || arguments.path("underNodePath").text() != null
        val root = if (scoped) {
            service.selectNodeReference(gui, arguments, "under", "outline root")
        } else {
            children(gui.treeModel.root as JMeterTreeNode).firstOrNull() ?: error("The open plan is empty")
        }
        val maxDepth = arguments.path("maxDepth").present()?.asInt()?.coerceAtLeast(0) ?: Int.MAX_VALUE
        val maxNodes = arguments.path("maxNodes").present()?.asInt()?.coerceAtLeast(1) ?: DEFAULT_OUTLINE_NODES
        val includeStatic = arguments.path("includeStaticAssets").asBoolean(false)
        val lines = mutableListOf<String>()
        var nodeCount = 0
        var truncated = false

        fun visit(node: JMeterTreeNode, depth: Int) {
            if (nodeCount >= maxNodes) {
                truncated = true
                return
            }
            nodeCount++
            val kids = children(node)
            val indent = "  ".repeat(depth)
            if (depth >= maxDepth && kids.isNotEmpty()) {
                lines += "$indent${summaryLine(node)} (+${kids.size} children)"
                return
            }
            lines += indent + summaryLine(node)
            var foldedStatic = 0
            for (kid in kids) {
                if (!includeStatic && kid.testElement is Sampler && AgentStaticAssetClassifier.isStaticAsset(kid.testElement)) {
                    foldedStatic++
                } else {
                    visit(kid, depth + 1)
                }
            }
            if (foldedStatic > 0) {
                lines += "$indent  (+$foldedStatic static asset requests)"
            }
        }
        visit(root, 0)
        mapOf(
            "format" to "nodeId Type name [details]; indentation is nesting",
            "nodeCount" to nodeCount,
            "truncated" to truncated,
            "outline" to lines.joinToString("\n"),
        )
    }

    private fun element(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val gui = gui()
        gui.updateCurrentNode()
        val node = service.selectNodeReference(gui, arguments, "target", "target")
        val element = node.testElement
        // Getters report built-in defaults (protocol "http", port 80) as if they were set, so only
        // values that differ from a fresh element of the same class are shown.
        val defaults = runCatching { describedValues(element.javaClass.getDeclaredConstructor().newInstance()) }.getOrDefault(emptyMap())
        val properties = describedValues(element).mapNotNull { (name, value) ->
            val shown = when {
                value == null || (name != "name" && value == defaults[name]) -> null
                value is String && value.length > MAX_PROPERTY_CHARS ->
                    value.take(MAX_PROPERTY_CHARS) + "... [${value.length - MAX_PROPERTY_CHARS} more chars]"
                else -> value
            }
            shown?.let { name to it }
        }.toMap()
        val result = linkedMapOf<String, Any?>(
            "nodeId" to service.nodeId(node),
            "nodePath" to service.nodePath(node),
            "className" to element.javaClass.name,
            "enabled" to node.isEnabled,
            "properties" to properties,
            "omitted" to "properties at their default value",
        )
        AgentElementTables.read(element).filterValues { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { result["tables"] = it }
        (element as? Scenario)?.let { result["workloads"] = workloadRows(gui, it) }
        children(node).takeIf { it.isNotEmpty() }?.let { kids -> result["children"] = kids.map(::summaryLine) }
        if (arguments.path("raw").asBoolean(false)) {
            result["rawProperties"] = rawProperties(element, 0)
        }
        result
    }

    /** Stored properties as they are saved; nested elements and lists keep their shape so paths can address them. */
    private fun rawProperties(element: TestElement, depth: Int): Map<String, Any?> =
        element.propertyIterator().asSequence().associate { it.name to rawValue(it, depth) }

    private fun rawValue(property: JMeterProperty, depth: Int): Any? = when {
        depth >= MAX_RAW_DEPTH -> "..."
        property is TestElementProperty && property.objectValue is TestElement ->
            rawProperties(property.objectValue as TestElement, depth + 1)
        property is CollectionProperty -> property.iterator().asSequence().map { row ->
            (row.objectValue as? TestElement)?.let { rawProperties(it, depth + 1) } ?: rawValue(row, depth + 1)
        }.toList()
        else -> property.stringValue.let { if (it.length > MAX_RAW_PROPERTY_CHARS) it.take(MAX_RAW_PROPERTY_CHARS) + "..." else it }
    }

    private fun describedValues(element: TestElement): Map<String, Any?> =
        (runCatching { AgentElementCatalog.describe(element)["properties"] as? List<*> }.getOrNull().orEmpty())
            .filterIsInstance<Map<*, *>>().associate { it["name"].toString() to it["value"] }

    private fun setEnabled(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val gui = gui()
        gui.updateCurrentNode()
        val flag = arguments.path("enabled")
        require(flag.isBoolean) { "enabled must be true or false" }
        val enabled = flag.asBoolean()
        val node = service.selectNodeReference(gui, arguments, "target", "target")
        val changed = node.isEnabled != enabled
        if (changed) {
            service.ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                node.isEnabled = enabled
                if (enabled && node.testElement is Scenario) {
                    ScenarioGui.disableOtherScenarios(node)
                }
                service.markEdited(gui, node)
                service.recordChange(if (enabled) "Enabled element" else "Disabled element", node, typeName(node.testElement))
            } finally {
                gui.endUndoTransaction()
            }
        }
        mapOf("nodeId" to service.nodeId(node), "nodePath" to service.nodePath(node), "enabled" to node.isEnabled, "changed" to changed)
    }

    private fun threadGroupNodes(gui: GuiPackage): List<JMeterTreeNode> =
        service.allTreeNodes(gui.treeModel.testPlan).filter { it.testElement is AbstractThreadGroup }

    private fun workloadRows(gui: GuiPackage, scenario: Scenario): List<Map<String, Any?>> {
        val names = threadGroupNodes(gui).associate { (it.testElement as AbstractThreadGroup).threadGroupId to it.testElement.name }
        return scenario.workloads.map { workload ->
            mapOf(
                "threadGroup" to (names[workload.threadGroupId] ?: "(missing: ${workload.threadGroupId})"),
                "enabled" to workload.isEnabled.takeIf { !it },
                "model" to workload.model,
                "threads" to workload.threads,
                "rampUpSeconds" to workload.rampUp,
                "durationSeconds" to workload.duration,
                "loops" to workload.loops,
                "delaySeconds" to workload.delay.takeIf { workload.isDelayedStart },
                "schedule" to (if (workload.model == "Open") workload.openSchedule else workload.closedSchedule),
                "pacing" to when (workload.pacingMode) {
                    AbstractThreadGroup.PACING_FIXED -> "${workload.fixedPacing} ms"
                    AbstractThreadGroup.PACING_RANDOM, AbstractThreadGroup.PACING_GAUSSIAN_RANDOM ->
                        "${workload.pacingMin}-${workload.pacingMax} ms"
                    else -> null
                },
                "profile" to workload.profile,
            ).filterValues { it != null && it != "" }
        }
    }

    private fun setScenarioWorkload(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val gui = gui()
        gui.updateCurrentNode()
        val scenarioNode = if (arguments.path("scenarioNodeId").text() != null || arguments.path("scenarioNodePath").text() != null) {
            service.selectNodeReference(gui, arguments, "scenario", "scenario")
        } else {
            service.allTreeNodes(gui.treeModel.testPlan).filter { it.testElement is Scenario && it.isEnabled }
                .singleOrNull() ?: error("Pass scenarioNodeId: the plan does not have exactly one enabled scenario")
        }
        val scenario = scenarioNode.testElement as? Scenario ?: error("'${scenarioNode.testElement.name}' is not a scenario")
        val groupName = arguments.path("threadGroupName").text() ?: throw IllegalArgumentException("threadGroupName is required")
        val group = threadGroupNodes(gui).filter { it.testElement.name == groupName }.let { matches ->
            require(matches.size == 1) { "Expected one thread group named '$groupName', found ${matches.size}" }
            matches.single().testElement as AbstractThreadGroup
        }
        val id = group.orCreateThreadGroupId
        val workloads = scenario.workloads.toMutableList()
        val existing = workloads.firstOrNull { it.threadGroupId == id }
        service.ensureBackupForOpenPlan(gui)
        gui.beginUndoTransaction()
        try {
            if (arguments.path("remove").asBoolean(false)) {
                workloads.removeAll { it.threadGroupId == id }
            } else {
                val workload = existing ?: ScenarioWorkload().also {
                    it.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.scenario.gui.ScenarioWorkloadGui")
                    it.setProperty(TestElement.TEST_CLASS, ScenarioWorkload::class.java.name)
                    it.name = group.name
                    it.threadGroupId = id
                    it.copyWorkloadFrom(group)
                    workloads += it
                }
                arguments.path("threads").text()?.let(workload::setThreads)
                arguments.path("rampUpSeconds").text()?.let(workload::setRampUp)
                arguments.path("durationSeconds").present()?.let { workload.setDuration(it.asText()) }
                arguments.path("loops").text()?.let(workload::setLoops)
                arguments.path("enabled").present()?.let { workload.isEnabled = it.asBoolean() }
                arguments.path("model").text()?.let { workload.model = if (it.equals("open", ignoreCase = true)) "Open" else "Closed" }
                arguments.path("delaySeconds").present()?.let {
                    workload.delay = it.asText()
                    workload.isDelayedStart = it.asText().isNotBlank() && it.asText() != "0"
                }
                arguments.path("closedSchedule").present()?.let { workload.closedSchedule = it.asText() }
                arguments.path("openSchedule").present()?.let { workload.openSchedule = it.asText() }
                arguments.path("openMaxThreads").text()?.let(workload::setOpenMaxThreads)
                arguments.path("profile").present()?.let { workload.profile = it.asText() }
                val pacingMin = arguments.path("pacingMinMillis").text()
                val pacingMax = arguments.path("pacingMaxMillis").text()
                val pacing = arguments.path("pacingMillis").text()
                when {
                    pacingMin != null && pacingMax != null -> workload.setRandomPacing(pacingMin, pacingMax, false)
                    pacing == "0" -> workload.disablePacing()
                    pacing != null -> workload.setFixedPacing(pacing)
                }
            }
            scenario.setWorkloads(workloads)
            service.markEdited(gui, scenarioNode)
            service.recordChange("Configured scenario", scenarioNode, "Workload for $groupName")
        } finally {
            gui.endUndoTransaction()
        }
        mapOf(
            "nodeId" to service.nodeId(scenarioNode),
            "scenario" to scenario.name,
            "workloads" to workloadRows(gui, scenario),
        )
    }

    private fun batch(arguments: JsonNode): Map<String, Any?> {
        val steps = arguments.path("steps")
        require(steps.isArray && steps.size() > 0) { "steps must be a non-empty array of {tool, arguments, as}" }
        require(steps.size() <= MAX_BATCH_STEPS) { "A batch takes at most $MAX_BATCH_STEPS steps" }
        val stopOnError = arguments.path("stopOnError").asBoolean(true)
        val brief = !arguments.path("detail").asText("brief").equals("full", ignoreCase = true)
        val references = HashMap<String, JsonNode>()
        val results = mutableListOf<Map<String, Any?>>()
        var failed = 0
        for ((index, step) in steps.withIndex()) {
            val requested = step.path("tool").asText("")
            val entry = linkedMapOf<String, Any?>("step" to index, "tool" to requested)
            try {
                require(requested.isNotBlank()) { "Step $index has no tool" }
                require(requested != "batch_open_plan") { "A batch cannot contain another batch" }
                val stepArguments = resolve(step.path("arguments").present() ?: mapper.createObjectNode(), references)
                val tool = BATCH_ALIASES[requested] ?: requested
                val result = mapper.valueToTree<JsonNode>(service.handleToolInProcess(tool, stepArguments))
                step.path("as").text()?.let { name ->
                    references[name] = result
                    entry["as"] = name
                }
                entry["result"] = if (brief && AgentToolSpecs.tier(tool) != AgentToolSpecs.TIER_READ) briefResult(result) else result
            } catch (e: Exception) {
                failed++
                entry["error"] = e.message ?: e.toString()
            }
            results += entry
            if ("error" in entry && stopOnError) {
                break
            }
        }
        return mapOf(
            "stepCount" to steps.size(),
            "succeeded" to results.size - failed,
            "failed" to failed,
            "stoppedEarly" to (results.size < steps.size()),
            "results" to results,
        )
    }

    /** An edit's confirmation reduced to where it landed; the full result stays available to later steps. */
    private fun briefResult(result: JsonNode): JsonNode {
        val id = NODE_ID_FIELDS.firstNotNullOfOrNull { result.path(it).present() } ?: return result
        val path = listOf("nodePath", "clonedPath", "extractorNodePath").firstNotNullOfOrNull { result.path(it).present() }
        return mapper.createObjectNode().also { brief ->
            brief.set<JsonNode>("nodeId", id)
            path?.let { brief.set<JsonNode>("nodePath", it) }
        }
    }

    /** Replaces whole-string `@name` / `@name.field` values that name an earlier step's result. */
    private fun resolve(node: JsonNode, references: Map<String, JsonNode>): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { copy: ObjectNode ->
            node.properties().forEach { (key, value) -> copy.set<JsonNode>(key, resolve(value, references)) }
        }
        node.isArray -> mapper.createArrayNode().also { copy: ArrayNode -> node.forEach { copy.add(resolve(it, references)) } }
        node.isTextual -> reference(node.asText(), references) ?: node
        else -> node
    }

    private fun reference(text: String, references: Map<String, JsonNode>): JsonNode? {
        val match = REFERENCE.matchEntire(text) ?: return null
        val source = references[match.groupValues[1]] ?: return null
        val fields = match.groupValues[2].split('.').filter { it.isNotEmpty() }
        if (fields.isEmpty()) {
            return NODE_ID_FIELDS.firstNotNullOfOrNull { source.path(it).present() }
                ?: throw IllegalArgumentException("Result '${match.groupValues[1]}' has no node id; name a field, e.g. $text.nodePath")
        }
        return fields.fold(source) { current, field ->
            (field.toIntOrNull()?.let { current.path(it) } ?: current.path(field)).present()
                ?: throw IllegalArgumentException("Reference $text: no field '$field' in that result")
        }
    }

    private fun save(arguments: JsonNode): Map<String, Any?> {
        val requested = arguments.path("path").text()
        val overwrite = arguments.path("overwrite").asBoolean(false)
        val target = service.guiCall {
            val gui = gui()
            gui.updateCurrentNode()
            val current = gui.testPlanFile?.let { File(it).absoluteFile }
            val file = when {
                requested != null -> File(requested).absoluteFile
                current != null -> current
                else -> throw IllegalArgumentException("This plan has never been saved; pass path, ending in .jmx")
            }
            if (file != current) {
                require(file.name.endsWith(".jmx", ignoreCase = true)) { "path must end in .jmx" }
                require(file.parentFile?.isDirectory == true) { "Folder does not exist: ${file.parent}" }
                require(overwrite || !file.exists()) { "${file.path} already exists; pass overwrite=true to replace it" }
                gui.setTestPlanFile(file.path)
            }
            if (AgentHeadlessPlan.isOpen()) {
                // No window, so no Save action: write the tree the way a backup is written.
                file.outputStream().use { SaveService.saveTree(service.convertTree(gui.treeModel.testPlan), it) }
                gui.setDirty(false)
            } else {
                ActionRouter.getInstance().doActionNow(ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.SAVE))
            }
            file
        }
        // A save from the GUI thread finishes in the background.
        val deadline = System.currentTimeMillis() + SAVE_WAIT_MILLIS
        var dirty = service.guiCall { gui().isDirty }
        while ((dirty || !target.exists()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            dirty = service.guiCall { gui().isDirty }
        }
        return mapOf("saved" to (target.exists() && !dirty), "path" to target.path, "dirty" to dirty)
    }

    private fun startTest(arguments: JsonNode): Map<String, Any?> {
        service.guiCall {
            val gui = gui()
            val scenarioNode = if (arguments.path("scenarioNodeId").text() != null || arguments.path("scenarioNodePath").text() != null) {
                service.selectNodeReference(gui, arguments, "scenario", "scenario")
                    .also { require(it.testElement is Scenario) { "'${it.testElement.name}' is not a scenario" } }
            } else {
                null
            }
            require(!JMeterUtils.isTestRunning()) { "A test is already running; see test_status" }
            // BreakTest reports these two in dialogs, which would leave this call waiting on the user.
            require(gui.testPlanFile != null) { "Save the plan first with save_open_plan; a run needs a saved plan" }
            gui.updateCurrentNode()
            try {
                val plan = JMeter.convertSubTree(gui.treeModel.testPlan, false)
                if (scenarioNode != null) ScenarioResolver.resolve(plan, scenarioNode.testElement as Scenario) else ScenarioResolver.resolve(plan)
            } catch (e: ScenarioException) {
                throw IllegalStateException("The test cannot start: ${e.message}", e)
            }
            if (JMeterUtils.getPropDefault(CONFIRM_RUN_PROPERTY, true)) {
                val frame = gui.mainFrame
                    ?: error("Starting a test needs confirmation in the BreakTest window, which is not available")
                val answer = JOptionPane.showConfirmDialog(
                    frame,
                    "An AI agent connected to BreakTest asks to start this test with its configured load.\nStart the test?",
                    "Start test",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                )
                require(answer == JOptionPane.YES_OPTION) { "The user declined to start the test" }
            }
            if (scenarioNode != null) {
                // "Run scenario" acts on the selected node.
                gui.treeListener.setSelectionPathWithoutEdit(javax.swing.tree.TreePath(scenarioNode.path))
            }
            val command = if (scenarioNode != null) ActionNames.RUN_SCENARIO else ActionNames.ACTION_START
            ActionRouter.getInstance().doActionNow(ActionEvent(this, ActionEvent.ACTION_PERFORMED, command))
        }
        val deadline = System.currentTimeMillis() + START_WAIT_MILLIS
        while (!JMeterUtils.isTestRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return mapOf("startRequested" to true) + testStatus()
    }

    private fun stopTest(arguments: JsonNode): Map<String, Any?> {
        val mode = arguments.path("mode").text()?.lowercase() ?: "stop"
        require(mode == "stop" || mode == "shutdown") { "mode must be stop or shutdown" }
        val wasRunning = service.guiCall {
            val running = JMeterUtils.isTestRunning()
            if (running) {
                val command = if (mode == "shutdown") ActionNames.ACTION_SHUTDOWN else ActionNames.ACTION_STOP
                ActionRouter.getInstance().doActionNow(ActionEvent(this, ActionEvent.ACTION_PERFORMED, command))
            }
            running
        }
        return mapOf("wasRunning" to wasRunning, "mode" to mode) + testStatus()
    }

    private fun testStatus(): Map<String, Any?> {
        val running = JMeterUtils.isTestRunning()
        val startedAt = JMeterContextService.getTestStartTime()
        val threads = JMeterContextService.getThreadCounts()
        return linkedMapOf<String, Any?>(
            "running" to running,
            "startedAt" to startedAt.takeIf { running && it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
            "elapsedSeconds" to startedAt.takeIf { running && it > 0 }?.let { (System.currentTimeMillis() - it) / 1000 },
            "activeThreads" to threads.activeThreads,
            "startedThreads" to threads.startedThreads,
            "finishedThreads" to threads.finishedThreads,
        ).filterValues { it != null }
    }
}
