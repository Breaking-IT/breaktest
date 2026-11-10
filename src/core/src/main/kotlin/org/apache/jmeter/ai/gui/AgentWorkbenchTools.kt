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
import org.apache.jmeter.ai.AgentRunStats
import org.apache.jmeter.ai.AgentSampleSummary
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.engine.util.CompoundVariable
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.action.ActionNames
import org.apache.jmeter.gui.action.ActionRouter
import org.apache.jmeter.gui.action.Load
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.scenario.Profile
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.gui.ScenarioGui
import org.apache.jmeter.services.FileServer
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.util.JMeterUtils
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import java.awt.event.ActionEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.swing.tree.TreePath

/**
 * Tools around the plan rather than inside one element: run results, the log, plan files, undo,
 * text round-trips, scenarios and reference lists. Declared in [org.apache.jmeter.ai.AgentToolSpecs].
 */
internal object AgentWorkbenchTools {
    private const val HTTP_SAMPLER_GUI = "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"
    private const val DEFAULT_EXPORT_CHARS = 60_000
    private const val LOG_TAIL_BYTES = 512 * 1024L
    private const val DEFAULT_BODY_WINDOW = 20_000
    private const val MAX_BODY_WINDOW = 400_000
    private const val FIND_LEAD_CHARS = 200
    private val SAMPLE_PARTS = setOf("requestHeaders", "requestBody", "responseHeaders", "responseBody")
    private val SAMPLE_PART_ALIASES = SAMPLE_PARTS.associateBy { it.lowercase() } + mapOf(
        "request" to "requestBody", "requestline" to "requestBody", "requestdata" to "requestBody",
        "response" to "responseBody", "body" to "responseBody", "headers" to "responseHeaders",
    )
    private const val LOAD_WAIT_MILLIS = 30_000L
    private val URL_PARTS = Regex("""^(?:(https?)://([^/:?#]+)(?::(\d+))?)?([^?#]*)(?:\?([^#]*))?(?:#.*)?$""", RegexOption.IGNORE_CASE)
    private val VARIABLE_GETTERS = listOf("getRefName", "getRefNames", "getVariableNames", "getVarName")

    private val mapper = ObjectMapper()
    private val service = BreakTestAgentGuiService

    /** Returns null for a tool this object does not implement. */
    fun handle(tool: String, arguments: JsonNode): Any? = when (tool) {
        "test_results" -> testResults(arguments)
        "get_log" -> log(arguments)
        "list_samples" -> listSamples(arguments)
        "get_sample" -> getSample(arguments)
        "add_http_request" -> addHttpRequest(arguments)
        "export_subtree" -> exportSubtree(arguments)
        "import_subtree" -> importSubtree(arguments)
        "undo" -> undoRedo(undo = true)
        "redo" -> undoRedo(undo = false)
        "select_node" -> selectNode(arguments)
        "open_plan" -> openPlan(arguments)
        "new_plan" -> newPlan(arguments)
        "add_scenario" -> addScenario(arguments)
        "add_profile" -> addProfile(arguments)
        "list_functions" -> listFunctions(arguments)
        "list_variables" -> listVariables()
        else -> null
    }

    private fun gui(): GuiPackage = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")

    private fun JsonNode.present(): JsonNode? = takeIf { !isMissingNode && !isNull }

    private fun JsonNode.text(): String? = present()?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun event(command: String) = ActionEvent(this, ActionEvent.ACTION_PERFORMED, command)

    private fun planNode(gui: GuiPackage): JMeterTreeNode =
        (gui.treeModel.root as JMeterTreeNode).let { root -> (0 until root.childCount).firstNotNullOfOrNull { root.getChildAt(it) as? JMeterTreeNode } }
            ?: error("The open plan is empty")

    private fun testResults(arguments: JsonNode): Map<String, Any?> =
        mapOf("running" to JMeterUtils.isTestRunning()) + AgentRunStats.snapshot(
            arguments.path("maxLabels").present()?.asInt()?.coerceIn(1, 500) ?: 60,
            arguments.path("errorSamples").present()?.asInt()?.coerceIn(0, 20) ?: 3,
        )

    private fun samples(arguments: JsonNode): List<AgentSampleSummary> =
        when (val source = arguments.path("source").text()?.lowercase() ?: "validation") {
            "validation" -> service.lastValidationSamples()
                ?: error("No validation run yet; call validate_open_plan first, or pass source=run for a real run")
            "run" -> AgentRunStats.capturedSamples()
                .ifEmpty { error("No samples kept from a real run yet; start one with start_test") }
            else -> throw IllegalArgumentException("source must be validation or run, not '$source'")
        }

    private fun listSamples(arguments: JsonNode): Map<String, Any?> {
        val onlyFailed = arguments.path("onlyFailed").asBoolean(false)
        val labelContains = arguments.path("labelContains").text()
        val maxRows = arguments.path("maxRows").present()?.asInt()?.coerceIn(1, 1000) ?: 200
        val all = samples(arguments)
        val matching = all.filter { sample ->
            (!onlyFailed || !sample.success) && (labelContains == null || sample.label.contains(labelContains, ignoreCase = true))
        }
        return mapOf(
            "columns" to "index | label | status | responseCode | ms | responseBytes | subSamples",
            "rows" to matching.take(maxRows).joinToString("\n") { sample ->
                listOf(
                    sample.index, sample.label, if (sample.success) "ok" else "FAILED", sample.responseCode,
                    sample.elapsedTimeMillis, sample.responseByteLength, sample.subResults.size,
                ).joinToString(" | ")
            },
            "total" to all.size,
            "rowsOmitted" to (matching.size - maxRows).takeIf { it > 0 },
        ).filterValues { it != null }
    }

    private fun getSample(arguments: JsonNode): Map<String, Any?> {
        val all = samples(arguments)
        val index = arguments.path("index").present()?.asInt()
        val label = arguments.path("label").text()
        var sample = when {
            index != null -> all.firstOrNull { it.index == index } ?: all.getOrNull(index)
                ?: throw IllegalArgumentException("No sample with index $index; see list_samples")
            label != null -> {
                val matches = all.filter { it.label == label }.ifEmpty { all.filter { it.label.contains(label, ignoreCase = true) } }
                matches.getOrNull(arguments.path("occurrence").asInt(0))
                    ?: throw IllegalArgumentException("No sample labelled '$label' at that occurrence (${matches.size} found)")
            }
            else -> throw IllegalArgumentException("Pass index or label; list_samples shows what is available")
        }
        arguments.path("subIndex").present()?.asInt()?.let { sub ->
            sample = sample.subResults.getOrNull(sub)
                ?: throw IllegalArgumentException("Sample '${sample.label}' has ${sample.subResults.size} sub-samples")
        }
        // Accept the names an agent reaches for: the result calls the request line and body
        // "request", and a single part is often passed as plain text instead of a list.
        val requested = arguments.path("parts").let { node ->
            when {
                node.isArray && node.size() > 0 -> node.map { it.asText() }
                node.isTextual && node.asText().isNotBlank() -> node.asText().split(',').map(String::trim)
                else -> null
            }
        }
        val parts = requested?.map { name ->
            SAMPLE_PART_ALIASES[name.lowercase()]
                ?: throw IllegalArgumentException("Unknown part '$name'; parts can be any of ${SAMPLE_PARTS.joinToString()}")
        }?.toSet() ?: SAMPLE_PARTS
        val result = linkedMapOf<String, Any?>(
            "index" to sample.index,
            "label" to sample.label,
            "success" to sample.success,
            "responseCode" to sample.responseCode,
            "responseMessage" to sample.responseMessage.takeIf { it.isNotBlank() },
            "elapsedMillis" to sample.elapsedTimeMillis,
            "failedAssertions" to sample.assertions.filter { it.failure || it.error }.map { "${it.name}: ${it.message}" }.takeIf { it.isNotEmpty() },
            "subSamples" to sample.subResults.mapIndexed { sub, it -> "$sub ${it.label} ${it.responseCode}" }.takeIf { it.isNotEmpty() },
        )
        if ("requestBody" in parts) result["request"] = sample.requestBody
        if ("requestHeaders" in parts) result["requestHeaders"] = sample.requestHeaders
        if ("responseHeaders" in parts) result["responseHeaders"] = sample.responseHeaders
        if ("responseBody" in parts) {
            val body = sample.responseBody
            val limit = arguments.path("limit").present()?.asInt()?.coerceIn(100, MAX_BODY_WINDOW) ?: DEFAULT_BODY_WINDOW
            val find = arguments.path("find").present()?.asText()?.takeIf { it.isNotEmpty() }
            val found = find?.let { body.indexOf(it, ignoreCase = true) }
            require(found == null || found >= 0) { "'$find' does not occur in the kept response body of '${sample.label}'" }
            val offset = (found?.let { it - FIND_LEAD_CHARS } ?: arguments.path("offset").asInt(0)).coerceIn(0, body.length)
            val end = minOf(body.length, offset + limit)
            result["responseBody"] = body.substring(offset, end)
            result["responseBodyEncoding"] = sample.responseBodyEncoding.takeIf { it != "text" }
            result["responseBodyChars"] = body.length
            result["responseBytes"] = sample.responseByteLength
            if (offset > 0 || end < body.length) {
                result["window"] = mapOf("offset" to offset, "end" to end, "nextOffset" to end.takeIf { it < body.length })
            }
            if (sample.responseBodyTruncated) {
                result["bodyCutWhenKept"] = "Only the first ${body.length} characters were kept; " +
                    "run validate_open_plan with a larger responseBodyLimit (-1 for no limit) to keep all of it"
            }
        }
        return result.filterValues { it != null }
    }

    private fun log(arguments: JsonNode): Map<String, Any?> {
        val file = File(System.getProperty("jmeter.logfile") ?: "breaktest.log").absoluteFile
        require(file.isFile) { "No log file at ${file.path}" }
        val limit = arguments.path("lines").present()?.asInt()?.coerceIn(1, 500) ?: 40
        val level = arguments.path("level").text()?.lowercase()
        val contains = arguments.path("contains").text()
        val text = RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - LOG_TAIL_BYTES).coerceAtLeast(0)
            input.seek(start)
            ByteArray((input.length() - start).toInt()).also(input::readFully).toString(Charsets.UTF_8)
        }
        val lines = text.lines().filter { line ->
            line.isNotBlank() &&
                when (level) {
                    "error" -> " ERROR " in line
                    "warn", "warning" -> " ERROR " in line || " WARN " in line
                    else -> true
                } && (contains == null || line.contains(contains, ignoreCase = true))
        }
        return mapOf("file" to file.path, "lines" to lines.takeLast(limit).joinToString("\n"))
    }

    private fun addHttpRequest(arguments: JsonNode): Map<String, Any?> {
        val url = arguments.path("url").text() ?: throw IllegalArgumentException("url is required")
        val match = URL_PARTS.matchEntire(url) ?: throw IllegalArgumentException("Cannot read '$url' as a URL")
        val (protocol, host, port, rawPath, query) = match.destructured
        require(host.isNotEmpty() || rawPath.startsWith("/")) { "url must start with http://, https:// or /" }
        val path = rawPath.ifEmpty { "/" }
        val method = arguments.path("method").text()?.uppercase() ?: if (arguments.path("body").present() != null) "POST" else "GET"
        val useDefaults = arguments.path("useDefaults").asBoolean(false) || host.isEmpty()
        val properties = mapper.createObjectNode().put("method", method).put("path", path)
        if (!useDefaults) {
            properties.put("protocol", protocol.lowercase()).put("domain", host)
            if (port.isNotEmpty()) properties.put("port", port.toInt())
        }
        val rows = mapper.createArrayNode()
        val body = arguments.path("body").present()?.asText()
        if (body != null) {
            properties.put("postBodyRaw", true)
            rows.addObject().put("name", "").put("value", body)
        } else {
            for (pair in query.split('&').filter { it.isNotEmpty() }) {
                rows.addObject().put("name", pair.substringBefore('=')).put("value", pair.substringAfter('=', ""))
            }
        }
        val add = mapper.createObjectNode()
        add.put("elementId", HTTP_SAMPLER_GUI).put("name", arguments.path("name").text() ?: path)
        // With a body the query stays in the path, since the single raw row is the body.
        if (body != null && query.isNotEmpty()) properties.put("path", "$path?$query")
        add.set<JsonNode>("properties", properties)
        if (rows.size() > 0) add.putObject("tables").set<JsonNode>("arguments", rows)
        for (key in listOf("targetNodeId", "targetNodePath")) {
            arguments.path(key).text()?.let { add.put(key, it) }
        }
        val headers = arguments.path("headers").present()
        if (headers != null && headers.isObject && headers.size() > 0) {
            val headerRows = mapper.createArrayNode()
            headers.properties().forEach { (name, value) -> headerRows.addObject().put("name", name).put("value", value.asText()) }
            (add.get("tables") as? com.fasterxml.jackson.databind.node.ObjectNode ?: add.putObject("tables")).set<JsonNode>("headers", headerRows)
        }
        val created = mapper.valueToTree<JsonNode>(service.handleTool("add_element_open_plan", add))
        return mapOf(
            "nodeId" to created.path("nodeId").asText(),
            "nodePath" to created.path("nodePath").asText(),
            "method" to method,
            "host" to host.takeIf { !useDefaults },
            "path" to path,
            "parameters" to rows.size().takeIf { body == null && it > 0 },
        ).filterValues { it != null }
    }

    private fun subTree(node: JMeterTreeNode): HashTree = ListedHashTree().also { tree ->
        fun fill(parent: HashTree, current: JMeterTreeNode) {
            val below = parent.add(current.testElement)
            for (index in 0 until current.childCount) {
                (current.getChildAt(index) as? JMeterTreeNode)?.let { fill(below, it) }
            }
        }
        fill(tree, node)
    }

    private fun exportSubtree(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val gui = gui()
        gui.updateCurrentNode()
        val node = service.selectNodeReference(gui, arguments, "target", "target")
        val archive = ByteArrayOutputStream().also { SaveService.saveTree(subTree(node), it) }.toByteArray()
        val xml = ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            zip.nextEntry ?: error("The plan could not be written as text")
            zip.readBytes().toString(Charsets.UTF_8)
        }
        val limit = arguments.path("maxChars").present()?.asInt()?.coerceAtLeast(1000) ?: DEFAULT_EXPORT_CHARS
        require(xml.length <= limit) {
            "This subtree is ${xml.length} characters, over the limit of $limit; export a smaller node or raise maxChars"
        }
        mapOf("nodeId" to service.nodeId(node), "nodePath" to service.nodePath(node), "xml" to xml)
    }

    private fun importSubtree(arguments: JsonNode): Map<String, Any?> {
        val xml = arguments.path("xml").present()?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("xml is required")
        val file = Files.createTempFile("breaktest-agent-import", ".jmx").toFile()
        val tree = try {
            file.writeText(xml, Charsets.UTF_8)
            SaveService.loadTree(file)
        } finally {
            file.delete()
        }
        val roots = tree.list().filterIsInstance<TestElement>()
        require(roots.isNotEmpty()) { "The XML holds no elements" }
        require(roots.none { it is TestPlan }) { "Import nodes below the Test Plan, not a whole plan; use open_plan for a plan file" }
        return service.guiCall {
            val gui = gui()
            gui.updateCurrentNode()
            val replaceId = arguments.path("replaceNodeId").text()
            val replaced = replaceId?.let {
                service.selectNodeReference(gui, mapper.createObjectNode().put("targetNodeId", it), "target", "replaced")
            }
            val parent = replaced?.parent as? JMeterTreeNode
                ?: service.selectNodeReference(gui, arguments, "target", "parent")
            val position = arguments.path("position").text()?.lowercase() ?: "last_child"
            require(position == "last_child" || position == "first_child") { "position must be first_child or last_child" }
            service.ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val before = parent.childCount
                service.insertDetachedSubTree(gui, parent, tree)
                val added = (before until parent.childCount).map { parent.getChildAt(it) as JMeterTreeNode }
                val index = when {
                    replaced != null -> parent.getIndex(replaced)
                    position == "first_child" -> 0
                    else -> -1
                }
                if (index >= 0) {
                    added.forEachIndexed { offset, node ->
                        gui.treeModel.removeNodeFromParent(node)
                        gui.treeModel.insertNodeInto(node, parent, index + offset)
                    }
                }
                if (replaced != null) {
                    gui.treeListener.setSelectionPathWithoutEdit(TreePath(parent.path))
                    service.removeTreeNode(gui, replaced)
                }
                service.markEdited(gui, parent, added.first())
                service.recordChange(
                    if (replaced != null) "Replaced subtree" else "Imported subtree", added.first(), "From edited plan text",
                )
                mapOf(
                    "nodeId" to service.nodeId(added.first()),
                    "nodePath" to service.nodePath(added.first()),
                    "nodeIds" to added.map { service.nodeId(it) },
                    "replaced" to (replaced != null),
                )
            } finally {
                gui.endUndoTransaction()
            }
        }
    }

    private fun undoRedo(undo: Boolean): Map<String, Any?> = service.guiCall {
        val gui = gui()
        gui.updateCurrentNode()
        val possible = if (undo) gui.canUndo() else gui.canRedo()
        if (possible) {
            if (undo) gui.undo() else gui.redo()
        }
        mapOf(
            "done" to possible, "canUndo" to gui.canUndo(), "canRedo" to gui.canRedo(),
            // Undo and redo rebuild the tree, so earlier node ids no longer resolve.
            "nodeIdsReset" to possible.takeIf { it },
        ).filterValues { it != null }
    }

    private fun selectNode(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val gui = gui()
        val node = service.selectNodeReference(gui, arguments, "target", "target")
        val path = TreePath(node.path)
        val tree = gui.mainFrame?.tree
        if (tree != null) {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } else {
            gui.treeListener.setSelectionPathWithoutEdit(path)
        }
        mapOf("nodeId" to service.nodeId(node), "nodePath" to service.nodePath(node), "selected" to true)
    }

    /** What the Close action does once the user agreed to drop the plan; that action itself asks in a dialog. */
    private fun closeWithoutAsking(gui: GuiPackage, discardChanges: Boolean) {
        require(!AgentHeadlessPlan.isOpen()) { "No BreakTest window: use open_plan_headless to switch plans" }
        require(!JMeterUtils.isTestRunning()) { "A test is running; stop it first" }
        ActionRouter.getInstance().doActionNow(event(ActionNames.CHECK_DIRTY))
        require(!gui.isDirty || discardChanges) {
            "The open plan has unsaved changes; save it with save_open_plan or pass discardChanges=true"
        }
        gui.clearTestPlan()
        service.forgetValidationBaseline()
        gui.treeListener.jTree.setSelectionRow(0)
        FileServer.getFileServer().setScriptName(null)
        ActionRouter.getInstance().actionPerformed(event(ActionNames.ADD_ALL))
        gui.setDirty(false)
    }

    private fun openPlan(arguments: JsonNode): Map<String, Any?> {
        val file = File(arguments.path("path").text() ?: throw IllegalArgumentException("path is required")).absoluteFile
        require(file.isFile) { "No such file: ${file.path}" }
        service.guiCall {
            val gui = gui()
            closeWithoutAsking(gui, arguments.path("discardChanges").asBoolean(false))
            Load.loadProjectFile(event(ActionNames.OPEN), file, false)
        }
        // The window loads a plan in the background.
        val deadline = System.currentTimeMillis() + LOAD_WAIT_MILLIS
        var opened = false
        while (!opened && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            opened = service.guiCall { gui().testPlanFile?.let { File(it).absoluteFile } == file }
        }
        return service.guiCall {
            mapOf("opened" to opened, "path" to file.path, "nodeCount" to service.allTreeNodes(gui().treeModel.testPlan).size)
        }
    }

    private fun newPlan(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val gui = gui()
        closeWithoutAsking(gui, arguments.path("discardChanges").asBoolean(false))
        mapOf("created" to true, "nodeCount" to service.allTreeNodes(gui.treeModel.testPlan).size)
    }

    private fun addSectionElement(element: TestElement, guiClass: String, enabled: Boolean, change: String): JMeterTreeNode {
        val gui = gui()
        gui.updateCurrentNode()
        element.setProperty(TestElement.GUI_CLASS, guiClass)
        element.setProperty(TestElement.TEST_CLASS, element.javaClass.name)
        element.isEnabled = enabled
        val target = gui.treeModel.addTargetFor(planNode(gui), element)
        val node = JMeterTreeNode(element, gui.treeModel)
        node.isEnabled = enabled
        service.ensureBackupForOpenPlan(gui)
        gui.beginUndoTransaction()
        try {
            gui.treeModel.insertNodeInto(node, target, target.childCount)
            service.markEdited(gui, target, node)
            service.recordChange(change, node, element.javaClass.simpleName)
        } finally {
            gui.endUndoTransaction()
        }
        return node
    }

    private fun addScenario(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val name = arguments.path("name").text() ?: throw IllegalArgumentException("name is required")
        val enable = arguments.path("enable").asBoolean(false)
        val node = addSectionElement(Scenario(name), "org.apache.jmeter.scenario.gui.ScenarioGui", enable, "Added scenario")
        if (enable) {
            ScenarioGui.disableOtherScenarios(node)
        }
        mapOf("nodeId" to service.nodeId(node), "nodePath" to service.nodePath(node), "enabled" to enable)
    }

    private fun addProfile(arguments: JsonNode): Map<String, Any?> = service.guiCall {
        val name = arguments.path("name").text() ?: throw IllegalArgumentException("name is required")
        val makeDefault = arguments.path("makeDefault").asBoolean(false)
        if (makeDefault) {
            service.allTreeNodes(gui().treeModel.testPlan).mapNotNull { it.testElement as? Profile }.forEach { it.isDefault = false }
        }
        val profile = Profile(name).apply { isDefault = makeDefault }
        val node = addSectionElement(profile, "org.apache.jmeter.scenario.gui.ProfileGui", true, "Added profile")
        mapOf("nodeId" to service.nodeId(node), "nodePath" to service.nodePath(node), "default" to makeDefault)
    }

    private fun listFunctions(arguments: JsonNode): Map<String, Any?> {
        val query = arguments.path("query").text()
        val lines = CompoundVariable.getFunctionNames().sorted()
            .filter { query == null || it.contains(query, ignoreCase = true) }
            .map { name ->
                val described = runCatching {
                    CompoundVariable.getFunctionClass(name).getDeclaredConstructor().newInstance().argumentDesc
                }.getOrNull().orEmpty()
                "$name(${described.joinToString("; ")})"
            }
        return mapOf("count" to lines.size, "usage" to "\${name(arg1,arg2)}", "functions" to lines.joinToString("\n"))
    }

    private fun listVariables(): Map<String, Any?> = service.guiCall {
        val gui = gui()
        gui.updateCurrentNode()
        val lines = mutableListOf<String>()
        for (node in service.allTreeNodes(gui.treeModel.testPlan)) {
            val element = node.testElement
            val names = buildList {
                if (element is TestPlan) addAll(element.userDefinedVariables.keys)
                if (element is Arguments) addAll(element.argumentsAsMap.keys)
                for (getter in VARIABLE_GETTERS) {
                    val value = runCatching { element.javaClass.getMethod(getter).invoke(element) as? String }.getOrNull()
                    value?.split(',', ';')?.map(String::trim)?.filter(String::isNotEmpty)?.let(::addAll)
                }
            }.distinct()
            if (names.isNotEmpty()) {
                lines += "${names.joinToString(", ")} <- ${service.nodeId(node)} ${element.javaClass.simpleName} ${element.name.orEmpty()}"
            }
        }
        mapOf("format" to "variables <- nodeId Type name of the defining node", "variables" to lines.joinToString("\n"))
    }
}
