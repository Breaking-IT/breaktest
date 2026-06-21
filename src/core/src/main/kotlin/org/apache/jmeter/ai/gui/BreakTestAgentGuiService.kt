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
import org.apache.jmeter.ai.AgentRunOptions
import org.apache.jmeter.ai.BreakTestAgent
import org.apache.jmeter.ai.edit.BoundaryCorrelationRequest
import org.apache.jmeter.ai.edit.LiteralReplacementRequest
import org.apache.jmeter.ai.edit.LiteralReplacementResult
import org.apache.jmeter.ai.edit.RedirectModeRequest
import org.apache.jmeter.ai.edit.RegexCorrelationRequest
import org.apache.jmeter.ai.edit.ResponseAssertionRequest
import org.apache.jmeter.ai.edit.TestPlanEditResult
import org.apache.jmeter.ai.edit.TestPlanEditor
import org.apache.jmeter.ai.knowledge.BreakTestAiKnowledge
import org.apache.jmeter.ai.knowledge.gui.BreakTestAiKnowledgeGui
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.property.BooleanProperty
import org.apache.jmeter.util.JMeterUtils
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath
import kotlin.concurrent.thread

public object BreakTestAgentGuiService {
    private const val DEFAULT_DSL_CHARACTER_LIMIT = 80_000
    private val backupTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val log = LoggerFactory.getLogger(BreakTestAgentGuiService::class.java)
    private val mapper = ObjectMapper()
    private var serverSocket: ServerSocket? = null
    private var unixServerSocket: ServerSocketChannel? = null
    private var token: String? = null
    private val backedUpPlanKeys = mutableSetOf<String>()

    @JvmStatic
    public fun startIfEnabled() {
        if (!java.lang.Boolean.getBoolean("breaktest.agent.enabled")) {
            return
        }
        start()
    }

    @JvmStatic
    @Synchronized
    public fun start() {
        if (serverSocket != null) {
            return
        }
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val unixServer = openUnixServer()
        val serviceToken = UUID.randomUUID().toString()
        serverSocket = server
        unixServerSocket = unixServer
        token = serviceToken
        writeDescriptor(server.localPort, unixSocketFile().takeIf { unixServer != null }, serviceToken)
        if (unixServer != null) {
            thread(name = "BreakTest Agent GUI Unix Service", isDaemon = true) {
                log.info("BreakTest Agent GUI service listening on {}", unixSocketFile().path)
                while (unixServer.isOpen) {
                    try {
                        unixServer.accept().use(::handleUnixConnection)
                    } catch (e: Exception) {
                        if (unixServer.isOpen) {
                            log.warn("BreakTest Agent GUI Unix service request failed", e)
                        }
                    }
                }
            }
        }
        thread(name = "BreakTest Agent GUI Service", isDaemon = true) {
            log.info("BreakTest Agent GUI service listening on 127.0.0.1:{}", server.localPort)
            while (!server.isClosed) {
                try {
                    server.accept().use(::handleConnection)
                } catch (e: Exception) {
                    if (!server.isClosed) {
                        log.warn("BreakTest Agent GUI service request failed", e)
                    }
                }
            }
        }
    }

    private fun openUnixServer(): ServerSocketChannel? =
        runCatching {
            val socketFile = unixSocketFile()
            socketFile.delete()
            socketFile.parentFile.mkdirs()
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                bind(UnixDomainSocketAddress.of(socketFile.toPath()))
            }
        }.onFailure {
            log.info("BreakTest Agent GUI Unix socket is unavailable; falling back to TCP loopback", it)
        }.getOrNull()

    private fun handleConnection(socket: Socket) {
        val request = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
            ?: return
        val response = handleRequest(request)
        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use {
            it.write(response)
            it.write("\n")
        }
    }

    private fun handleUnixConnection(socket: SocketChannel) {
        val request = BufferedReader(Channels.newReader(socket, Charsets.UTF_8.newDecoder(), -1)).readLine()
            ?: return
        val response = handleRequest(request)
        Channels.newWriter(socket, Charsets.UTF_8.newEncoder(), -1).use {
            it.write(response)
            it.write("\n")
        }
    }

    private fun handleRequest(request: String): String {
        var tool = "unknown"
        val response = try {
            val node = mapper.readTree(request)
            require(node.path("token").asText() == token) {
                "Invalid BreakTest agent token"
            }
            tool = node.path("tool").asText()
            val startedAt = System.nanoTime()
            if (tool != "agent_activity") {
                postActivity("started", "MCP tool `$tool` started")
            }
            val result = handleTool(tool, node.path("arguments"))
            if (tool != "agent_activity") {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                postActivity("completed", "MCP tool `$tool` completed in ${elapsedMs}ms")
            }
            mapOf("ok" to true, "result" to result)
        } catch (e: Exception) {
            if (tool != "unknown" && tool != "agent_activity") {
                postActivity("failed", "MCP tool `$tool` failed: ${e.message ?: e}")
            }
            mapOf("ok" to false, "error" to (e.message ?: e.toString()))
        }
        return mapper.writeValueAsString(response)
    }

    private fun handleTool(tool: String, arguments: JsonNode): Any =
        when (tool) {
            "status" -> guiCall {
                val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
                mapOf(
                    "testPlanFile" to gui.testPlanFile,
                    "dirty" to gui.isDirty,
                    "selectedNode" to gui.treeListener.currentNode?.name,
                )
            }
            "inspect_open_plan" -> BreakTestAgent().inspect(currentPlanTree(), dslCharacterLimit(arguments))
            "validate_open_plan" -> BreakTestAgent().inspectAndValidate(
                currentPlanTree(),
                optionsFrom(arguments),
                dslCharacterLimit(arguments),
            )
            "backup_open_plan" -> mapOf("backupPath" to createBackupForOpenPlan())
            "get_ai_knowledge_open_plan" -> getAiKnowledgeOpenPlan()
            "update_ai_knowledge_open_plan" -> updateAiKnowledgeOpenPlan(arguments)
            "agent_activity" -> handleAgentActivity(arguments)
            "apply_boundary_correlation_open_plan" -> applyBoundaryCorrelationOpenPlan(arguments)
            "apply_regex_correlation_open_plan" -> applyRegexCorrelationOpenPlan(arguments)
            "replace_literal_open_plan" -> replaceLiteralOpenPlan(arguments)
            "add_response_assertion_open_plan" -> addResponseAssertionOpenPlan(arguments)
            "set_redirect_mode_open_plan" -> setRedirectModeOpenPlan(arguments)
            else -> throw IllegalArgumentException("Unknown GUI agent tool: $tool")
        }

    @JvmStatic
    public fun createBackupForOpenPlan(): String? =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            val backupFile = backupFileFor(gui.testPlanFile)
            backupFile.parentFile?.mkdirs()
            backupFile.outputStream().use { SaveService.saveTree(convertTree(gui.treeModel.testPlan), it) }
            backedUpPlanKeys += backupKey(gui.testPlanFile)
            postActivity(
                "info",
                "Created AI backup before editing",
                details = backupFile.path,
            )
            backupFile.path
        }

    private fun getAiKnowledgeOpenPlan(): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val node = ensureKnowledgeNode(gui)
            val element = node.testElement as BreakTestAiKnowledge
            val json = element.knowledgeJson
            mapOf(
                "nodeName" to element.name,
                "knowledgeJson" to json,
                "knowledge" to mapper.readTree(json),
            )
        }

    private fun updateAiKnowledgeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val knowledgeJson = knowledgeJsonFrom(arguments)
            mapper.readTree(knowledgeJson)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            val node = ensureKnowledgeNode(gui)
            val element = node.testElement as BreakTestAiKnowledge
            element.knowledgeJson = knowledgeJson
            gui.setDirty(true)
            gui.treeModel.nodeChanged(node)
            gui.mainFrame.tree.selectionPath = TreePath(node.path)
            gui.mainFrame.repaint()
            postActivity(
                "info",
                "Updated AI scripting knowledge",
                details = arguments.path("summary").takeIfPresent()?.asText(),
            )
            mapOf(
                "updated" to true,
                "nodeName" to element.name,
                "knowledgeJson" to element.knowledgeJson,
            )
        }

    private fun applyBoundaryCorrelationOpenPlan(arguments: JsonNode): TestPlanEditResult =
        guiCall {
            val request = boundaryCorrelationRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val samplerNodes = samplerNodes(gui.treeModel.testPlan)
                val source = selectSampler(samplerNodes, request.sourceSamplerIndex, request.sourceSamplerLabel, "source")
                val target = selectSampler(samplerNodes, request.targetSamplerIndex, request.targetSamplerLabel, "target")
                val editor = TestPlanEditor()
                val extractor = editor.createBoundaryExtractor(request)
                val extractorNode = JMeterTreeNode(extractor, gui.treeModel)
                extractorNode.isEnabled = extractor.isEnabled
                gui.treeModel.insertNodeInto(extractorNode, source, source.childCount)
                val variableReference = "\${${request.variableName}}"
                val targetElement = target.testElement
                val targetSubTree = currentPlanTree().findSubTree(targetElement)
                    ?: throw IllegalStateException("Could not locate target sampler subtree '${targetElement.name}'")
                val replacements = editor.replaceLiteral(targetElement, targetSubTree, request.literal, variableReference)
                require(replacements > 0) {
                    "Literal '${request.literal}' was not found under target sampler '${targetElement.name}'"
                }
                gui.setDirty(true)
                gui.treeModel.nodeStructureChanged(source)
                gui.mainFrame.tree.expandPath(TreePath(source.path))
                gui.mainFrame.tree.selectionPath = TreePath(extractorNode.path)
                gui.mainFrame.repaint()
                postActivity(
                    "info",
                    "Applied live boundary correlation",
                    details = "${request.variableName}: ${source.testElement.name} -> ${targetElement.name}",
                )
                TestPlanEditResult(
                    sourceSamplerLabel = source.testElement.name.orEmpty(),
                    targetSamplerLabel = targetElement.name.orEmpty(),
                    variableReference = variableReference,
                    replacements = replacements,
                    extractorClass = extractor::class.java.name,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun applyRegexCorrelationOpenPlan(arguments: JsonNode): TestPlanEditResult =
        guiCall {
            val request = regexCorrelationRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val samplerNodes = samplerNodes(gui.treeModel.testPlan)
                val source = selectSampler(samplerNodes, request.sourceSamplerIndex, request.sourceSamplerLabel, "source")
                val target = selectSampler(samplerNodes, request.targetSamplerIndex, request.targetSamplerLabel, "target")
                val editor = TestPlanEditor()
                val extractor = editor.createRegexExtractor(request)
                val extractorNode = JMeterTreeNode(extractor, gui.treeModel)
                extractorNode.isEnabled = extractor.isEnabled
                gui.treeModel.insertNodeInto(extractorNode, source, source.childCount)
                val variableReference = "\${${request.variableName}}"
                val targetElement = target.testElement
                val targetSubTree = currentPlanTree().findSubTree(targetElement)
                    ?: throw IllegalStateException("Could not locate target sampler subtree '${targetElement.name}'")
                val replacements = editor.replaceLiteral(targetElement, targetSubTree, request.literal, variableReference)
                require(replacements > 0) {
                    "Literal '${request.literal}' was not found under target sampler '${targetElement.name}'"
                }
                markEdited(gui, source, extractorNode)
                postActivity(
                    "info",
                    "Applied live regex correlation",
                    details = "${request.variableName}: ${source.testElement.name} -> ${targetElement.name}",
                )
                TestPlanEditResult(
                    sourceSamplerLabel = source.testElement.name.orEmpty(),
                    targetSamplerLabel = targetElement.name.orEmpty(),
                    variableReference = variableReference,
                    replacements = replacements,
                    extractorClass = extractor::class.java.name,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun replaceLiteralOpenPlan(arguments: JsonNode): LiteralReplacementResult =
        guiCall {
            val request = literalReplacementRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectSampler(
                    samplerNodes(gui.treeModel.testPlan),
                    request.targetSamplerIndex,
                    request.targetSamplerLabel,
                    "target",
                )
                val targetSubTree = currentPlanTree().findSubTree(target.testElement)
                    ?: throw IllegalStateException("Could not locate target sampler subtree '${target.testElement.name}'")
                val replacements = TestPlanEditor().replaceLiteral(
                    target.testElement,
                    targetSubTree,
                    request.literal,
                    request.replacement,
                )
                require(replacements > 0) {
                    "Literal '${request.literal}' was not found under target sampler '${target.testElement.name}'"
                }
                markEdited(gui, target)
                postActivity(
                    "info",
                    "Applied live literal replacement",
                    details = "${target.testElement.name}: ${request.literal} -> ${request.replacement}",
                )
                LiteralReplacementResult(target.testElement.name.orEmpty(), replacements)
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun addResponseAssertionOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val request = responseAssertionRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectSampler(
                    samplerNodes(gui.treeModel.testPlan),
                    request.targetSamplerIndex,
                    request.targetSamplerLabel,
                    "target",
                )
                val assertion = TestPlanEditor().createResponseAssertion(request)
                val assertionNode = JMeterTreeNode(assertion, gui.treeModel)
                assertionNode.isEnabled = assertion.isEnabled
                gui.treeModel.insertNodeInto(assertionNode, target, target.childCount)
                markEdited(gui, target, assertionNode)
                postActivity(
                    "info",
                    "Added live response assertion",
                    details = "${target.testElement.name}: ${request.pattern}",
                )
                mapOf(
                    "targetSamplerLabel" to target.testElement.name,
                    "assertionName" to assertion.name,
                    "pattern" to request.pattern,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun setRedirectModeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val request = redirectModeRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectSampler(
                    samplerNodes(gui.treeModel.testPlan),
                    request.targetSamplerIndex,
                    request.targetSamplerLabel,
                    "target",
                )
                val element = target.testElement
                request.followRedirects?.let { setRedirectProperty(element, "setFollowRedirects", "HTTPSampler.follow_redirects", it) }
                request.autoRedirects?.let { setRedirectProperty(element, "setAutoRedirects", "HTTPSampler.auto_redirects", it) }
                markEdited(gui, target)
                postActivity(
                    "info",
                    "Updated redirect mode",
                    details = "${element.name}: follow=${request.followRedirects}, auto=${request.autoRedirects}",
                )
                mapOf(
                    "targetSamplerLabel" to element.name,
                    "followRedirects" to request.followRedirects,
                    "autoRedirects" to request.autoRedirects,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun setRedirectProperty(element: TestElement, setter: String, fallbackProperty: String, value: Boolean) {
        runCatching {
            element::class.java.getMethod(setter, Boolean::class.javaPrimitiveType).invoke(element, value)
        }.getOrElse {
            element.setProperty(BooleanProperty(fallbackProperty, value))
        }
    }

    private fun markEdited(gui: GuiPackage, changedNode: JMeterTreeNode, selectNode: JMeterTreeNode = changedNode) {
        gui.setDirty(true)
        gui.treeModel.nodeStructureChanged(changedNode)
        gui.mainFrame.tree.expandPath(TreePath(changedNode.path))
        gui.mainFrame.tree.selectionPath = TreePath(selectNode.path)
        gui.mainFrame.repaint()
    }

    private fun ensureBackupForOpenPlan(gui: GuiPackage) {
        val key = backupKey(gui.testPlanFile)
        if (key in backedUpPlanKeys) {
            return
        }
        createBackupForOpenPlan()
    }

    private fun backupFileFor(testPlanFile: String?): File {
        val source = testPlanFile?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
        val directory = source?.parentFile?.takeIf { it.isDirectory }
            ?: File(JMeterUtils.getJMeterHome()).takeIf { it.isDirectory }
            ?: File(System.getProperty("java.io.tmpdir"))
        val baseName = source?.nameWithoutExtension?.takeIf { it.isNotBlank() } ?: "untitled"
        val timestamp = LocalDateTime.now().format(backupTimeFormat)
        return File(directory, "$baseName.ai-backup-$timestamp.jmx")
    }

    private fun backupKey(testPlanFile: String?): String =
        testPlanFile?.takeIf { it.isNotBlank() }?.let { File(it).absolutePath } ?: "(untitled)"

    private fun ensureKnowledgeNode(gui: GuiPackage): JMeterTreeNode {
        findKnowledgeNode(gui.treeModel.testPlan)?.let { return it }
        val testPlanNode = testPlanNode(gui)
        val element = BreakTestAiKnowledge().apply {
            setProperty(TestElement.GUI_CLASS, BreakTestAiKnowledgeGui::class.java.name)
            setProperty(TestElement.TEST_CLASS, BreakTestAiKnowledge::class.java.name)
        }
        val node = JMeterTreeNode(element, gui.treeModel)
        node.isEnabled = element.isEnabled
        gui.treeModel.insertNodeInto(node, testPlanNode, testPlanNode.childCount)
        gui.setDirty(true)
        gui.treeModel.nodeStructureChanged(testPlanNode)
        gui.mainFrame.tree.expandPath(TreePath(testPlanNode.path))
        gui.mainFrame.tree.selectionPath = TreePath(node.path)
        postActivity("info", "Created BreakTest AI Knowledge node")
        return node
    }

    private fun findKnowledgeNode(tree: HashTree): JMeterTreeNode? {
        for (node in tree.list()) {
            if (node is JMeterTreeNode && node.testElement is BreakTestAiKnowledge) {
                return node
            }
            val nested = findKnowledgeNode(tree.getTree(node))
            if (nested != null) {
                return nested
            }
        }
        return null
    }

    private fun testPlanNode(gui: GuiPackage): JMeterTreeNode =
        gui.treeModel.testPlan.list().filterIsInstance<JMeterTreeNode>().firstOrNull()
            ?: throw IllegalStateException("Could not locate the open Test Plan node")

    private fun knowledgeJsonFrom(arguments: JsonNode): String {
        val node = arguments.path("knowledgeJson").takeIfPresent()
            ?: arguments.path("knowledge").takeIfPresent()
            ?: throw IllegalArgumentException("Missing required argument 'knowledgeJson' or 'knowledge'")
        return if (node.isTextual) {
            node.asText()
        } else {
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
        }
    }

    private fun boundaryCorrelationRequest(arguments: JsonNode): BoundaryCorrelationRequest =
        BoundaryCorrelationRequest(
            sourceSamplerIndex = arguments.path("sourceSamplerIndex").takeIfPresent()?.asInt(),
            sourceSamplerLabel = arguments.path("sourceSamplerLabel").takeIfPresent()?.asText(),
            targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
            targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
            variableName = arguments.requiredText("variableName"),
            leftBoundary = arguments.requiredText("leftBoundary"),
            rightBoundary = arguments.requiredText("rightBoundary"),
            literal = arguments.requiredText("literal"),
        )

    private fun regexCorrelationRequest(arguments: JsonNode): RegexCorrelationRequest =
        RegexCorrelationRequest(
            sourceSamplerIndex = arguments.path("sourceSamplerIndex").takeIfPresent()?.asInt(),
            sourceSamplerLabel = arguments.path("sourceSamplerLabel").takeIfPresent()?.asText(),
            targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
            targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
            variableName = arguments.requiredText("variableName"),
            regex = arguments.requiredText("regex"),
            template = arguments.path("template").takeIfPresent()?.asText() ?: "$1$",
            matchNumber = arguments.path("matchNumber").takeIfPresent()?.asText() ?: "1",
            defaultValue = arguments.path("defaultValue").takeIfPresent()?.asText() ?: "NOT_FOUND",
            useField = arguments.path("useField").takeIfPresent()?.asText() ?: "body",
            literal = arguments.requiredText("literal"),
        )

    private fun literalReplacementRequest(arguments: JsonNode): LiteralReplacementRequest =
        LiteralReplacementRequest(
            targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
            targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
            literal = arguments.requiredText("literal"),
            replacement = arguments.requiredText("replacement"),
        )

    private fun responseAssertionRequest(arguments: JsonNode): ResponseAssertionRequest =
        ResponseAssertionRequest(
            targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
            targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
            assertionName = arguments.path("assertionName").takeIfPresent()?.asText() ?: "AI Response Assertion",
            pattern = arguments.requiredText("pattern"),
            field = arguments.path("field").takeIfPresent()?.asText() ?: "body",
            matchType = arguments.path("matchType").takeIfPresent()?.asText() ?: "substring",
        )

    private fun redirectModeRequest(arguments: JsonNode): RedirectModeRequest {
        val followRedirects = arguments.path("followRedirects").takeIfPresent()?.asBoolean()
        val autoRedirects = arguments.path("autoRedirects").takeIfPresent()?.asBoolean()
        require(followRedirects != null || autoRedirects != null) {
            "Specify followRedirects or autoRedirects"
        }
        return RedirectModeRequest(
            targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
            targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
            followRedirects = followRedirects,
            autoRedirects = autoRedirects,
        )
    }

    private fun samplerNodes(tree: HashTree): List<JMeterTreeNode> {
        val result = mutableListOf<JMeterTreeNode>()
        fun visit(currentTree: HashTree) {
            for (node in currentTree.list()) {
                if (node is JMeterTreeNode && node.testElement is Sampler) {
                    result += node
                }
                visit(currentTree.getTree(node))
            }
        }
        visit(tree)
        return result
    }

    private fun selectSampler(
        samplers: List<JMeterTreeNode>,
        index: Int?,
        label: String?,
        role: String,
    ): JMeterTreeNode {
        if (index != null) {
            return samplers.getOrNull(index)
                ?: throw IllegalArgumentException("No $role sampler at index $index")
        }
        if (!label.isNullOrBlank()) {
            return samplers.singleOrNull { it.testElement.name == label }
                ?: throw IllegalArgumentException("Expected exactly one $role sampler named '$label'")
        }
        throw IllegalArgumentException("Specify ${role}SamplerIndex or ${role}SamplerLabel")
    }

    private fun handleAgentActivity(arguments: JsonNode): Map<String, Any> {
        val message = arguments.path("message").asText().ifBlank { "Agent activity" }
        val level = arguments.path("level").asText("info")
        val source = arguments.path("source").asText("Codex").ifBlank { "Codex" }
        val details = arguments.path("details").takeIfPresent()?.asText()?.takeIf { it.isNotBlank() }
        postActivity(level, message, source, details)
        return mapOf(
            "recorded" to true,
            "level" to level,
            "message" to message,
        )
    }

    private fun postActivity(
        level: String,
        message: String,
        source: String = "BreakTest Agent",
        details: String? = null,
    ) {
        val line = buildString {
            append("[")
            append(source)
            append("] ")
            append(level.uppercase())
            append(" - ")
            append(message)
            if (!details.isNullOrBlank()) {
                append(" - ")
                append(details)
            }
        }
        AiAutoScriptingLogWindow.append(line)
    }

    private fun currentPlanTree(): HashTree =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            convertTree(gui.treeModel.testPlan)
        }

    private fun convertTree(tree: HashTree): HashTree {
        val converted = ListedHashTree()
        for (node in tree.list()) {
            val key = if (node is JMeterTreeNode) node.testElement else node
            converted.add(key, convertTree(tree.getTree(node)))
        }
        return converted
    }

    private fun HashTree.findSubTree(element: TestElement): HashTree? {
        for (node in list()) {
            val subTree = getTree(node)
            if (node === element) {
                return subTree
            }
            val nested = subTree.findSubTree(element)
            if (nested != null) {
                return nested
            }
        }
        return null
    }

    private fun <T> guiCall(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return action()
        }
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait {
            result = runCatching(action)
        }
        return result!!.getOrThrow()
    }

    private fun optionsFrom(arguments: JsonNode): AgentRunOptions =
        AgentRunOptions(
            timeout = Duration.ofSeconds(arguments.path("timeoutSeconds").asLong(30)),
            responseBodyLimit = arguments.path("responseBodyLimit").asInt(32 * 1024),
            requestBodyLimit = arguments.path("requestBodyLimit").asInt(16 * 1024),
            maxSamples = arguments.path("maxSamples").takeIfPresent()?.asInt(),
            stopOnFirstFailure = arguments.path("stopOnFirstFailure").asBoolean(false),
        )

    private fun dslCharacterLimit(arguments: JsonNode): Int? {
        if (arguments.path("includeDsl").takeIfPresent()?.asBoolean() == false) {
            return 0
        }
        return arguments.path("dslCharacterLimit").takeIfPresent()?.asInt() ?: DEFAULT_DSL_CHARACTER_LIMIT
    }

    private fun writeDescriptor(port: Int, socketFile: File?, serviceToken: String) {
        val descriptor = descriptorFile()
        descriptor.parentFile.mkdirs()
        descriptor.writeText(
            mapper.writeValueAsString(
                mapOf(
                    "host" to "127.0.0.1",
                    "port" to port,
                    "socketPath" to socketFile?.path,
                    "token" to serviceToken,
                )
            ),
            Charsets.UTF_8,
        )
    }

    public fun descriptorFile(): File =
        File(System.getProperty("breaktest.agent.descriptor", defaultDescriptorPath()))

    private fun defaultDescriptorPath(): String =
        File(System.getProperty("java.io.tmpdir"), "breaktest-agent-${System.getProperty("user.name")}.json").path

    private fun unixSocketFile(): File =
        File(System.getProperty("breaktest.agent.socket", defaultSocketPath()))

    private fun defaultSocketPath(): String =
        File(System.getProperty("java.io.tmpdir"), "breaktest-agent-${System.getProperty("user.name")}.sock").path

    private fun JsonNode.takeIfPresent(): JsonNode? =
        takeIf { !isMissingNode && !isNull }

    private fun JsonNode.requiredText(name: String): String {
        val value = path(name)
        require(!value.isMissingNode && !value.isNull && value.asText().isNotBlank()) {
            "Missing required argument '$name'"
        }
        return value.asText()
    }
}
