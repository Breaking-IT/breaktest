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
import org.apache.jmeter.ai.AgentDynamicValueAnalyzer
import org.apache.jmeter.ai.AgentRunOptions
import org.apache.jmeter.ai.BreakTestAgent
import org.apache.jmeter.ai.edit.BoundaryCorrelationRequest
import org.apache.jmeter.ai.edit.LiteralReplacementRequest
import org.apache.jmeter.ai.edit.LiteralReplacementResult
import org.apache.jmeter.ai.edit.RedirectModeRequest
import org.apache.jmeter.ai.edit.RegexExtractorUpdateRequest
import org.apache.jmeter.ai.edit.RegexCorrelationRequest
import org.apache.jmeter.ai.edit.ResponseAssertionRequest
import org.apache.jmeter.ai.edit.TestPlanEditResult
import org.apache.jmeter.ai.edit.TestPlanEditor
import org.apache.jmeter.ai.knowledge.BreakTestAiKnowledge
import org.apache.jmeter.ai.knowledge.gui.BreakTestAiKnowledgeGui
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.property.BooleanProperty
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.MultiProperty
import org.apache.jmeter.testelement.property.ObjectProperty
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.util.JSR223TestElement
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
    private const val TEST_PLAN_USER_DEFINED_VARIABLES = "TestPlan.user_defined_variables"
    private val backupTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val log = LoggerFactory.getLogger(BreakTestAgentGuiService::class.java)
    private val mapper = ObjectMapper()
    private val defaultKnowledgeJson = mapper.readTree(BreakTestAiKnowledge.DEFAULT_JSON)
    private val WEAK_ASSERTION_TOKENS = setOf(
        "ok",
        "true",
        "false",
        "success",
        "succes",
        "ticket",
        "tickets",
        "mijn",
        "juli",
        "html",
        "body",
    )
    private val INVALID_UUID_FUNCTION_REGEX = Regex("""\$\{__UUID\s*\(""")
    private var serverSocket: ServerSocket? = null
    private var unixServerSocket: ServerSocketChannel? = null
    private var token: String? = null
    private val backedUpPlanKeys = mutableSetOf<String>()

    @JvmStatic
    public fun startIfEnabled() {
        val enabled = System.getProperty("breaktest.agent.enabled")?.toBooleanStrictOrNull() ?: true
        if (!enabled) {
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
            "list_agent_changes_open_plan" -> AiAutoScriptingLogWindow.changes()
            "agent_activity" -> handleAgentActivity(arguments)
            "apply_boundary_correlation_open_plan" -> applyBoundaryCorrelationOpenPlan(arguments)
            "apply_regex_correlation_open_plan" -> applyRegexCorrelationOpenPlan(arguments)
            "update_regex_extractor_open_plan" -> updateRegexExtractorOpenPlan(arguments)
            "replace_literal_open_plan" -> replaceLiteralOpenPlan(arguments)
            "replace_literal_in_names_open_plan" -> replaceLiteralInNamesOpenPlan(arguments)
            "set_user_defined_variable_open_plan" -> setUserDefinedVariableOpenPlan(arguments)
            "list_http_arguments_open_plan" -> listHttpArgumentsOpenPlan(arguments)
            "set_http_argument_encode_open_plan" -> setHttpArgumentEncodeOpenPlan(arguments)
            "set_http_argument_value_open_plan" -> setHttpArgumentValueOpenPlan(arguments)
            "search_open_plan_values" -> searchOpenPlanValues(arguments)
            "audit_dynamic_request_values_open_plan" -> auditDynamicRequestValuesOpenPlan(arguments)
            "add_jsr223_open_plan" -> addJsr223OpenPlan(arguments)
            "add_response_assertion_open_plan" -> addResponseAssertionOpenPlan(arguments)
            "update_response_assertion_open_plan" -> updateResponseAssertionOpenPlan(arguments)
            "set_redirect_mode_open_plan" -> setRedirectModeOpenPlan(arguments)
            "move_think_times_to_transactions_open_plan" -> moveThinkTimesToTransactionsOpenPlan(arguments)
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

    @JvmStatic
    public fun createRepairCloneForOpenPlan(): String? =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            val cloneFile = repairCloneFileFor(gui.testPlanFile)
            cloneFile.parentFile?.mkdirs()
            cloneFile.outputStream().use { SaveService.saveTree(convertTree(gui.treeModel.testPlan), it) }
            postActivity(
                "info",
                "Created AI repair clone",
                details = cloneFile.path,
            )
            cloneFile.path
        }

    private fun getAiKnowledgeOpenPlan(): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            val selection = ensureKnowledgeNode(gui)
            val node = selection.selected.node
            val element = node.testElement as BreakTestAiKnowledge
            val json = element.knowledgeJson
            mapOf(
                "nodeName" to element.name,
                "nodePath" to selection.selected.path,
                "knowledgeNodeCount" to selection.candidates.size,
                "isDefaultKnowledge" to selection.selected.defaultKnowledge,
                "selectedDirectTestPlanChild" to selection.selected.directTestPlanChild,
                "availableKnowledgeNodes" to selection.candidates.map { candidate ->
                    mapOf(
                        "nodeName" to candidate.node.name,
                        "nodePath" to candidate.path,
                        "directTestPlanChild" to candidate.directTestPlanChild,
                        "isDefaultKnowledge" to candidate.defaultKnowledge,
                    )
                },
                "knowledgeJson" to json,
                "knowledge" to mapper.readTree(json),
            )
        }

    private fun updateAiKnowledgeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val knowledgeJson = knowledgeJsonFrom(arguments)
            mapper.readTree(knowledgeJson)
            val allowDefault = arguments.path("allowDefault").asBoolean(false)
            require(allowDefault || !isDefaultKnowledge(knowledgeJson)) {
                "Refusing to write default/empty AI Knowledge. Append reusable run learnings or a learnedFromThreadGroups noReusableLearnings entry before finishing."
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            val node = ensureKnowledgeNode(gui).selected.node
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
            recordChange(
                "Updated knowledge",
                node,
                "Updated reusable AI scripting knowledge",
                arguments.path("summary").takeIfPresent()?.asText(),
            )
            mapOf(
                "updated" to true,
                "nodeName" to element.name,
                "knowledgeJson" to element.knowledgeJson,
            )
        }

    private fun applyBoundaryCorrelationOpenPlan(arguments: JsonNode): TestPlanEditResult =
        guiCall {
            val evidence = requireResponseEvidence(arguments, "add boundary correlation")
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
                val extractorNode = addConfiguredComponent(
                    gui,
                    source,
                    "org.apache.jmeter.extractor.gui.BoundaryExtractorGui",
                ) { editor.configureBoundaryExtractor(it, request) }
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
                recordChange(
                    "Added extractor",
                    extractorNode,
                    "Boundary extractor `${request.variableName}`",
                    "${source.testElement.name} -> ${targetElement.name}; failOnNoMatch=${request.failOnNoMatch}; " +
                        "evidence=${evidence.summary}",
                )
                recordChange(
                    "Updated sampler",
                    target,
                    "Replaced literal with $variableReference",
                    "Replacements: $replacements",
                )
                TestPlanEditResult(
                    sourceSamplerLabel = source.testElement.name.orEmpty(),
                    targetSamplerLabel = targetElement.name.orEmpty(),
                    variableReference = variableReference,
                    replacements = replacements,
                    extractorClass = extractorNode.testElement::class.java.name,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun applyRegexCorrelationOpenPlan(arguments: JsonNode): TestPlanEditResult =
        guiCall {
            val evidence = requireResponseEvidence(arguments, "add regex correlation")
            val request = regexCorrelationRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val samplerNodes = samplerNodes(gui.treeModel.testPlan)
                val source = selectSampler(samplerNodes, request.sourceSamplerIndex, request.sourceSamplerLabel, "source")
                val target = selectOptionalSampler(samplerNodes, request.targetSamplerIndex, request.targetSamplerLabel, "target")
                val editor = TestPlanEditor()
                val extractorNode = addConfiguredComponent(
                    gui,
                    source,
                    "org.apache.jmeter.extractor.gui.RegexExtractorGui",
                ) { editor.configureRegexExtractor(it, request) }
                val variableReference = "\${${request.variableName}}"
                val replacements = if (target == null) {
                    editor.replaceLiteralInTree(currentPlanTree(), request.literal, variableReference)
                } else {
                    val targetElement = target.testElement
                    val targetSubTree = currentPlanTree().findSubTree(targetElement)
                        ?: throw IllegalStateException("Could not locate target sampler subtree '${targetElement.name}'")
                    editor.replaceLiteral(targetElement, targetSubTree, request.literal, variableReference)
                }
                require(replacements > 0) {
                    target?.let {
                        "Literal '${request.literal}' was not found under target sampler '${it.testElement.name}'"
                    } ?: "Literal '${request.literal}' was not found in the open plan"
                }
                markEdited(gui, if (target == null) testPlanNode(gui) else target, extractorNode)
                postActivity(
                    "info",
                    "Applied live regex correlation",
                    details = "${request.variableName}: ${source.testElement.name} -> ${target?.testElement?.name ?: "open plan"}",
                )
                recordChange(
                    "Added extractor",
                    extractorNode,
                    "Regex extractor `${request.variableName}`",
                    "${source.testElement.name} -> ${target?.testElement?.name ?: "open plan"}; " +
                        "failOnNoMatch=${request.failOnNoMatch}; evidence=${evidence.summary}",
                )
                recordChange(
                    if (target == null) "Updated plan" else "Updated sampler",
                    target ?: testPlanNode(gui),
                    "Replaced literal with $variableReference",
                    "Replacements: $replacements",
                )
                TestPlanEditResult(
                    sourceSamplerLabel = source.testElement.name.orEmpty(),
                    targetSamplerLabel = target?.testElement?.name ?: "open plan",
                    variableReference = variableReference,
                    replacements = replacements,
                    extractorClass = extractorNode.testElement::class.java.name,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun updateRegexExtractorOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val request = regexExtractorUpdateRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val source = selectSampler(
                    samplerNodes(gui.treeModel.testPlan),
                    request.sourceSamplerIndex,
                    request.sourceSamplerLabel,
                    "source",
                )
                val extractorNode = regexExtractorNode(source, request.variableName)
                TestPlanEditor().updateRegexExtractor(extractorNode.testElement, request)
                markEdited(gui, source, extractorNode)
                postActivity(
                    "info",
                    "Updated live regex extractor",
                    details = "${request.variableName}: ${source.testElement.name}",
                )
                recordChange(
                    "Updated extractor",
                    extractorNode,
                    "Regex extractor `${request.variableName}`",
                    listOfNotNull(
                        request.regex?.let { "regex=$it" },
                        request.useField?.let { "useField=$it" },
                        request.failOnNoMatch?.let { "failOnNoMatch=$it" },
                    ).joinToString("; "),
                )
                mapOf(
                    "sourceSamplerLabel" to source.testElement.name,
                    "extractorName" to extractorNode.testElement.name,
                    "variableName" to request.variableName,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun replaceLiteralOpenPlan(arguments: JsonNode): LiteralReplacementResult =
        guiCall {
            val request = literalReplacementRequest(arguments)
            validateJMeterFunctionSyntax(request.replacement)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectedReplacementTarget(gui, request)
                val editor = TestPlanEditor()
                val replacements = if (target == null) {
                    editor.replaceLiteralInTree(
                        currentPlanTree(),
                        request.literal,
                        request.replacement,
                        request.includeNames,
                        request.excludeUserDefinedVariables,
                    )
                } else {
                    val targetSubTree = currentPlanTree().findSubTree(target.testElement)
                        ?: throw IllegalStateException("Could not locate target sampler subtree '${target.testElement.name}'")
                    editor.replaceLiteral(
                        target.testElement,
                        targetSubTree,
                        request.literal,
                        request.replacement,
                        request.includeNames,
                        request.excludeUserDefinedVariables,
                    )
                }
                require(replacements > 0) {
                    target?.let {
                        "Literal '${request.literal}' was not found under target sampler '${it.testElement.name}'"
                    } ?: "Literal '${request.literal}' was not found in the open plan"
                }
                val changedNode = target ?: testPlanNode(gui)
                markEdited(gui, changedNode)
                postActivity(
                    "info",
                    "Applied live literal replacement",
                    details = "${target?.testElement?.name ?: "open plan"}: ${request.literal} -> ${request.replacement}",
                )
                recordChange(
                    if (target == null) "Updated plan" else "Updated sampler",
                    changedNode,
                    "Replaced literal with ${request.replacement}",
                    "Replacements: $replacements",
                )
                LiteralReplacementResult(target?.testElement?.name ?: "open plan", replacements)
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun replaceLiteralInNamesOpenPlan(arguments: JsonNode): LiteralReplacementResult =
        guiCall {
            val request = literalReplacementRequest(arguments)
            require(!request.replacement.contains("\${")) {
                "Element names must stay static; use a display-safe placeholder like {variable}, not \${variable}"
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectedReplacementTarget(gui, request)
                val planTree = currentPlanTree()
                val editTree = if (target == null) {
                    planTree
                } else {
                    val targetSubTree = planTree.findSubTree(target.testElement)
                        ?: throw IllegalStateException("Could not locate target sampler subtree '${target.testElement.name}'")
                    ListedHashTree().apply {
                        add(target.testElement, targetSubTree)
                    }
                }
                val replacements = TestPlanEditor().replaceLiteralInNamesInTree(
                    editTree,
                    request.literal,
                    request.replacement,
                )
                require(replacements > 0) {
                    target?.let {
                        "Literal '${request.literal}' was not found in names under target sampler '${it.testElement.name}'"
                    } ?: "Literal '${request.literal}' was not found in open-plan names"
                }
                val changedNode = target ?: testPlanNode(gui)
                markEdited(gui, changedNode)
                postActivity(
                    "info",
                    "Applied live name replacement",
                    details = "${target?.testElement?.name ?: "open plan"}: ${request.literal} -> ${request.replacement}",
                )
                recordChange(
                    if (target == null) "Renamed elements" else "Renamed sampler elements",
                    changedNode,
                    "Replaced name literal with ${request.replacement}",
                    "Name replacements: $replacements",
                )
                LiteralReplacementResult(target?.testElement?.name ?: "open plan", replacements)
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun selectedReplacementTarget(gui: GuiPackage, request: LiteralReplacementRequest): JMeterTreeNode? {
        if (request.targetSamplerIndex == null && request.targetSamplerLabel.isNullOrBlank()) {
            return null
        }
        return selectSampler(
            samplerNodes(gui.treeModel.testPlan),
            request.targetSamplerIndex,
            request.targetSamplerLabel,
            "target",
        )
    }

    private fun setUserDefinedVariableOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val name = arguments.requiredText("name")
            val value = arguments.requiredText("value")
            require(value != "\${$name}") {
                "Refusing to set User Defined Variable '$name' to self-reference '$value'; use the original literal value"
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val node = testPlanNode(gui)
                val testPlan = node.testElement as? TestPlan
                    ?: throw IllegalStateException("Open root node is not a Test Plan")
                testPlan.arguments.removeArgument(name)
                testPlan.addParameter(name, value)
                markEdited(gui, node)
                postActivity(
                    "info",
                    "Set User Defined Variable",
                    details = "$name=${displayVariableValue(name, value)}",
                )
                recordChange(
                    "Updated variables",
                    node,
                    "Set User Defined Variable `$name`",
                    displayVariableValue(name, value),
                )
                mapOf(
                    "name" to name,
                    "value" to value,
                    "nodeName" to node.testElement.name,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun listHttpArgumentsOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt()
            val targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText()
            val target = selectSampler(
                samplerNodes(GuiPackage.getInstance()?.treeModel?.testPlan ?: error("BreakTest GUI is not ready")),
                targetSamplerIndex,
                targetSamplerLabel,
                "target",
            )
            val argumentRows = httpArguments(target.testElement).mapIndexed { index, argument ->
                mapOf(
                    "index" to index,
                    "name" to argument.name,
                    "value" to argument.value,
                    "alwaysEncode" to argument.alwaysEncode,
                    "className" to argument.element::class.java.name,
                )
            }
            mapOf(
                "targetSamplerLabel" to target.testElement.name,
                "argumentCount" to argumentRows.size,
                "arguments" to argumentRows,
            )
        }

    private fun setHttpArgumentEncodeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt()
            val targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText()
            val argumentName = arguments.path("argumentName").takeIfPresent()?.asText()
            val argumentValue = arguments.path("argumentValue").takeIfPresent()?.asText()
            val alwaysEncode = arguments.path("alwaysEncode").takeIfPresent()?.asBoolean()
                ?: throw IllegalArgumentException("Missing required argument 'alwaysEncode'")
            if (argumentName.isNullOrBlank() && argumentValue.isNullOrBlank()) {
                throw IllegalArgumentException("Specify argumentName or argumentValue")
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectSampler(
                    samplerNodes(gui.treeModel.testPlan),
                    targetSamplerIndex,
                    targetSamplerLabel,
                    "target",
                )
                val changed = setHttpArgumentEncode(target.testElement, argumentName, argumentValue, alwaysEncode)
                require(changed > 0) {
                    "No HTTP argument matched name='$argumentName' value='$argumentValue' under '${target.testElement.name}'"
                }
                markEdited(gui, target)
                postActivity(
                    "info",
                    "Updated HTTP argument encoding",
                    details = "${target.testElement.name}: name=$argumentName value=$argumentValue alwaysEncode=$alwaysEncode",
                )
                recordChange(
                    "Updated sampler",
                    target,
                    "Set HTTP argument alwaysEncode=$alwaysEncode",
                    "name=$argumentName value=$argumentValue changed=$changed",
                )
                mapOf(
                    "targetSamplerLabel" to target.testElement.name,
                    "changedArguments" to changed,
                    "alwaysEncode" to alwaysEncode,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun setHttpArgumentValueOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt()
            val targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText()
            val argumentName = arguments.path("argumentName").takeIfPresent()?.asText()
            val argumentValue = arguments.path("argumentValue").takeIfPresent()?.asText()
            val newValue = arguments.requiredText("newValue")
            validateJMeterFunctionSyntax(newValue)
            val alwaysEncode = arguments.path("alwaysEncode").takeIfPresent()?.asBoolean()
            if (argumentName.isNullOrBlank() && argumentValue.isNullOrBlank()) {
                throw IllegalArgumentException("Specify argumentName or argumentValue")
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectSampler(
                    samplerNodes(gui.treeModel.testPlan),
                    targetSamplerIndex,
                    targetSamplerLabel,
                    "target",
                )
                val changed = setHttpArgumentValue(target.testElement, argumentName, argumentValue, newValue, alwaysEncode)
                require(changed > 0) {
                    "No HTTP argument matched name='$argumentName' value='$argumentValue' under '${target.testElement.name}'"
                }
                markEdited(gui, target)
                postActivity(
                    "info",
                    "Updated HTTP argument value",
                    details = "${target.testElement.name}: name=$argumentName value=$argumentValue -> $newValue",
                )
                recordChange(
                    "Updated sampler",
                    target,
                    "Set HTTP argument value",
                    "name=$argumentName value=$argumentValue changed=$changed",
                )
                mapOf(
                    "targetSamplerLabel" to target.testElement.name,
                    "changedArguments" to changed,
                    "newValue" to newValue,
                    "alwaysEncode" to alwaysEncode,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun searchOpenPlanValues(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val literal = arguments.path("literal").takeIfPresent()?.asText()
            val regex = arguments.path("regex").takeIfPresent()?.asText()
            val includeNames = arguments.path("includeNames").asBoolean(false)
            val excludeUserDefinedVariables = arguments.path("excludeUserDefinedVariables").asBoolean(false)
            val maxMatches = arguments.path("maxMatches").takeIfPresent()?.asInt()?.coerceAtLeast(1) ?: 100
            require(!literal.isNullOrBlank() || !regex.isNullOrBlank()) {
                "Specify literal or regex"
            }
            val pattern = regex?.takeIf { it.isNotBlank() }?.toRegex()
            val matches = mutableListOf<Map<String, Any?>>()
            val visitedElements = java.util.IdentityHashMap<TestElement, Boolean>()
            val visitedProperties = java.util.IdentityHashMap<JMeterProperty, Boolean>()
            fun visitNode(tree: HashTree, path: List<String>) {
                if (matches.size >= maxMatches) {
                    return
                }
                for (node in tree.list()) {
                    val nodeName = (node as? JMeterTreeNode)?.testElement?.name ?: node.toString()
                    val element = (node as? JMeterTreeNode)?.testElement ?: node as? TestElement
                    if (element != null) {
                        searchElementValues(
                            element,
                            path + nodeName,
                            literal,
                            pattern,
                            includeNames,
                            excludeUserDefinedVariables,
                            maxMatches,
                            visitedElements,
                            visitedProperties,
                            matches,
                        )
                    }
                    visitNode(tree.getTree(node), path + nodeName)
                }
            }
            visitNode(GuiPackage.getInstance()?.treeModel?.testPlan ?: error("BreakTest GUI is not ready"), emptyList())
            mapOf(
                "matchCount" to matches.size,
                "truncated" to (matches.size >= maxMatches),
                "matches" to matches,
            )
        }

    private fun auditDynamicRequestValuesOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val maxCandidates = arguments.path("maxCandidates").takeIfPresent()?.asInt()?.coerceAtLeast(1) ?: 300
            val includeStaticAssets = arguments.path("includeStaticAssets").asBoolean(false)
            val threadGroupName = arguments.path("threadGroupName").takeIfPresent()?.asText()
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            val planTree = convertTree(gui.treeModel.testPlan)
            val auditTree = if (threadGroupName.isNullOrBlank()) {
                planTree
            } else {
                val threadGroup = selectThreadGroup(gui, threadGroupName)
                val threadGroupSubTree = planTree.findSubTree(threadGroup.testElement)
                    ?: throw IllegalStateException("Could not locate Thread Group '$threadGroupName' in the open plan")
                ListedHashTree().apply {
                    add(threadGroup.testElement, threadGroupSubTree)
                }
            }
            val candidates = AgentDynamicValueAnalyzer(includeStaticAssetRequests = includeStaticAssets)
                .analyze(auditTree, maxCandidates)
            val highConfidenceKinds = setOf(
                "bearer-token",
                "csrf-or-verification-token",
                "credential",
                "uuid",
                "epoch-ms",
                "date-time",
                "draw-id",
            )
            val highConfidence = candidates.filter { it.kind in highConfidenceKinds }
            mapOf(
                "candidateCount" to candidates.size,
                "highConfidenceCount" to highConfidence.size,
                "truncated" to (candidates.size >= maxCandidates),
                "includeStaticAssets" to includeStaticAssets,
                "threadGroupName" to threadGroupName,
                "highConfidenceKinds" to highConfidenceKinds.sorted(),
                "guidance" to "A successful validation run is not enough when high-confidence hard-coded request values remain. Review path, query, body, cookies, and custom headers. Correlate, parameterize, generate at runtime, randomize from prior responses, or document why each candidate is safe/static. Date/time values and drawId-style fields are high-confidence until proven safe. Static browser asset requests are ignored by default; set includeStaticAssets=true only when asset responses are relevant to a failure.",
                "candidates" to candidates,
            )
        }

    private fun addJsr223OpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val parentType = arguments.path("parentType").takeIfPresent()?.asText()?.lowercase() ?: "sampler"
            val targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt()
            val targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText()
            val threadGroupName = arguments.path("threadGroupName").takeIfPresent()?.asText()
            val elementType = arguments.path("elementType").takeIfPresent()?.asText()?.lowercase() ?: "preprocessor"
            val name = arguments.path("name").takeIfPresent()?.asText()?.takeIf { it.isNotBlank() }
                ?: "AI JSR223 ${elementType.replaceFirstChar { it.uppercase() }}"
            val script = arguments.requiredText("script")
            val language = arguments.path("language").takeIfPresent()?.asText()?.takeIf { it.isNotBlank() } ?: "groovy"
            val parameters = arguments.path("parameters").takeIfPresent()?.asText() ?: ""
            val cacheKey = arguments.path("cacheKey").takeIfPresent()?.asText()
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val requiresSamplerParent = elementType in setOf(
                    "preprocessor",
                    "pre_processor",
                    "pre",
                    "postprocessor",
                    "post_processor",
                    "post",
                    "assertion",
                )
                require(!requiresSamplerParent || parentType !in setOf("testplan", "test_plan", "plan", "threadgroup", "thread_group")) {
                    "JSR223 $elementType elements must be attached to a specific sampler. Use parentType='sampler' with targetSamplerIndex/targetSamplerLabel, or use elementType='sampler' for thread-group setup code."
                }
                val parent = when (parentType) {
                    "testplan", "test_plan", "plan" -> testPlanNode(gui)
                    "threadgroup", "thread_group" -> selectThreadGroup(gui, threadGroupName)
                    else -> selectSampler(
                        samplerNodes(gui.treeModel.testPlan),
                        targetSamplerIndex,
                        targetSamplerLabel,
                        "target",
                    )
                }
                val className = when (elementType) {
                    "sampler" -> "org.apache.jmeter.protocol.java.sampler.JSR223Sampler"
                    "postprocessor", "post_processor", "post" -> "org.apache.jmeter.extractor.JSR223PostProcessor"
                    "preprocessor", "pre_processor", "pre" -> "org.apache.jmeter.modifiers.JSR223PreProcessor"
                    "assertion" -> "org.apache.jmeter.assertions.JSR223Assertion"
                    "timer" -> "org.apache.jmeter.timers.JSR223Timer"
                    else -> throw IllegalArgumentException("Unsupported JSR223 elementType '$elementType'")
                }
                val node = addConfiguredComponent(gui, parent, className) { element ->
                    element.name = name
                    val jsr223 = element as? JSR223TestElement
                        ?: throw IllegalStateException("$className is not a JSR223 element")
                    jsr223.scriptLanguage = language
                    jsr223.script = script
                    jsr223.parameters = parameters
                    jsr223.filename = ""
                    jsr223.setProperty(StringProperty("scriptLanguage", language))
                    jsr223.setProperty(StringProperty("script", script))
                    jsr223.setProperty(StringProperty("parameters", parameters))
                    jsr223.setProperty(StringProperty("filename", ""))
                    cacheKey?.let { setOptionalMethod(jsr223, "setCacheKey", it) }
                    cacheKey?.let { jsr223.setProperty(StringProperty("cacheKey", it)) }
                    require(callString(jsr223, "getScript") == script || jsr223.getPropertyAsString("script") == script) {
                        "JSR223 script was not applied to the new element"
                    }
                }
                markEdited(gui, parent, node)
                postActivity(
                    "info",
                    "Added JSR223 $elementType",
                    details = "${parent.testElement.name}: $name",
                )
                recordChange(
                    "Added JSR223",
                    node,
                    "Added JSR223 $elementType `$name`",
                    "language=$language parent=${parent.testElement.name}",
                )
                mapOf(
                    "parentName" to parent.testElement.name,
                    "nodeName" to node.testElement.name,
                    "elementType" to elementType,
                    "language" to language,
                    "scriptLength" to script.length,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun addResponseAssertionOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val evidence = requireResponseEvidence(arguments, "add response assertion")
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
                val assertionNode = addConfiguredComponent(
                    gui,
                    target,
                    "org.apache.jmeter.assertions.gui.AssertionGui",
                ) { TestPlanEditor().configureResponseAssertion(it, request) }
                markEdited(gui, target, assertionNode)
                postActivity(
                    "info",
                    "Added live response assertion",
                    details = "${target.testElement.name}: ${request.pattern}",
                )
                recordChange(
                    "Added assertion",
                    assertionNode,
                    "Response assertion",
                    "${request.pattern}; evidence=${evidence.summary}",
                )
                mapOf(
                    "targetSamplerLabel" to target.testElement.name,
                    "assertionName" to assertionNode.testElement.name,
                    "pattern" to request.pattern,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun updateResponseAssertionOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val assertionName = arguments.path("assertionName").takeIfPresent()?.asText().orEmpty()
            val currentPattern = arguments.path("currentPattern").takeIfPresent()?.asText()
            val pattern = arguments.requiredText("pattern")
            validateAssertionPattern(pattern, arguments.path("allowWeakPattern").asBoolean(false))
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt()
                val targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText()
                val target = selectSampler(
                    samplerNodes(gui.treeModel.testPlan),
                    targetSamplerIndex,
                    targetSamplerLabel,
                    "target",
                )
                val assertionNode = responseAssertionNode(target, assertionName, currentPattern)
                val request = ResponseAssertionRequest(
                    targetSamplerIndex = targetSamplerIndex,
                    targetSamplerLabel = targetSamplerLabel,
                    assertionName = assertionName.ifBlank { assertionNode.testElement.name.orEmpty() },
                    pattern = pattern,
                    field = arguments.path("field").takeIfPresent()?.asText() ?: "body",
                    matchType = arguments.path("matchType").takeIfPresent()?.asText() ?: "substring",
                )
                TestPlanEditor().configureResponseAssertion(assertionNode.testElement, request, clearExisting = true)
                markEdited(gui, target, assertionNode)
                postActivity(
                    "info",
                    "Updated live response assertion",
                    details = "${target.testElement.name}: ${request.pattern}",
                )
                recordChange(
                    "Updated assertion",
                    assertionNode,
                    "Response assertion `${assertionNode.testElement.name}`",
                    request.pattern,
                )
                mapOf(
                    "targetSamplerLabel" to target.testElement.name,
                    "assertionName" to assertionNode.testElement.name,
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
                recordChange(
                    "Updated sampler",
                    target,
                    "Updated redirect mode",
                    "follow=${request.followRedirects}, auto=${request.autoRedirects}",
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

    private fun moveThinkTimesToTransactionsOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val threadGroupName = arguments.path("threadGroupName").takeIfPresent()?.asText()
            val removeOriginal = arguments.path("removeOriginal").asBoolean(true)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val threadGroup = selectThreadGroup(gui, threadGroupName)
                val moves = mutableListOf<Map<String, Any?>>()
                val thinkTimeNodes = standaloneThinkTimeNodes(threadGroup)
                for (thinkTimeNode in thinkTimeNodes) {
                    val target = nextTransactionSibling(thinkTimeNode) ?: continue
                    val timer = firstTimerChild(thinkTimeNode) ?: continue
                    val delay = transactionDelayFromTimer(timer)
                    applyTransactionDelay(target.testElement as TransactionController, delay)
                    if (removeOriginal) {
                        removeTreeNode(gui, thinkTimeNode)
                    }
                    markEdited(gui, target)
                    val detail = "${thinkTimeNode.testElement.name} -> ${target.testElement.name}: ${delay.summary}"
                    recordChange(
                        "Updated transaction",
                        target,
                        "Moved think time to transaction delay",
                        detail,
                    )
                    moves += mapOf(
                        "thinkTimeName" to thinkTimeNode.testElement.name,
                        "transactionName" to target.testElement.name,
                        "delayMode" to delay.mode,
                        "delayMin" to delay.min,
                        "delayMax" to delay.max,
                        "fixedDelay" to delay.fixed,
                        "removedOriginal" to removeOriginal,
                    )
                }
                require(moves.isNotEmpty()) {
                    "No standalone ThinkTime/TestAction nodes with timer children before Transaction Controllers were found"
                }
                gui.setDirty(true)
                gui.treeModel.nodeStructureChanged(threadGroup)
                gui.mainFrame.tree.expandPath(TreePath(threadGroup.path))
                gui.mainFrame.tree.selectionPath = TreePath(threadGroup.path)
                gui.mainFrame.repaint()
                postActivity(
                    "info",
                    "Moved think times to transaction level",
                    details = "${moves.size} transaction(s) updated in ${threadGroup.testElement.name}",
                )
                mapOf(
                    "threadGroupName" to threadGroup.testElement.name,
                    "updatedTransactions" to moves.size,
                    "removedStandaloneThinkTimes" to if (removeOriginal) moves.size else 0,
                    "moves" to moves,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun addConfiguredComponent(
        gui: GuiPackage,
        parent: JMeterTreeNode,
        guiClassName: String,
        configure: (TestElement) -> Unit,
    ): JMeterTreeNode {
        val element = gui.createTestElement(guiClassName)
            ?: throw IllegalStateException("Could not create test element from $guiClassName")
        configure(element)
        return gui.treeModel.addComponent(element, parent)
    }

    private fun setRedirectProperty(element: TestElement, setter: String, fallbackProperty: String, value: Boolean) {
        runCatching {
            element::class.java.getMethod(setter, Boolean::class.javaPrimitiveType).invoke(element, value)
        }.getOrElse {
            element.setProperty(BooleanProperty(fallbackProperty, value))
        }
    }

    private fun standaloneThinkTimeNodes(parent: JMeterTreeNode): List<JMeterTreeNode> {
        val matches = mutableListOf<JMeterTreeNode>()
        fun visit(container: JMeterTreeNode) {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index) as? JMeterTreeNode ?: continue
                if (isStandaloneThinkTime(child)) {
                    matches += child
                } else {
                    visit(child)
                }
            }
        }
        visit(parent)
        return matches
    }

    private fun isStandaloneThinkTime(node: JMeterTreeNode): Boolean {
        val action = node.testElement
        return action::class.java.name == "org.apache.jmeter.sampler.TestAction" &&
            action.getPropertyAsInt("ActionProcessor.action") == 1 &&
            action.getPropertyAsString("ActionProcessor.duration").trim().ifBlank { "0" } == "0" &&
            firstTimerChild(node) != null &&
            node.testElement.name.orEmpty().contains("think", ignoreCase = true)
    }

    private fun nextTransactionSibling(node: JMeterTreeNode): JMeterTreeNode? {
        val parent = node.parent as? JMeterTreeNode ?: return null
        val start = parent.getIndex(node) + 1
        for (index in start until parent.childCount) {
            val sibling = parent.getChildAt(index) as? JMeterTreeNode ?: continue
            if (!sibling.isEnabled) {
                continue
            }
            if (sibling.testElement is TransactionController) {
                return sibling
            }
            if (!isStandaloneThinkTime(sibling)) {
                return null
            }
        }
        return null
    }

    private fun firstTimerChild(node: JMeterTreeNode): TestElement? {
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? JMeterTreeNode ?: continue
            if (isTimerElement(child.testElement)) {
                return child.testElement
            }
        }
        return null
    }

    private fun isTimerElement(element: TestElement): Boolean =
        element::class.java.name.startsWith("org.apache.jmeter.timers.") &&
            !element.getPropertyAsString("ConstantTimer.delay").isNullOrBlank()

    private fun transactionDelayFromTimer(timer: TestElement): TransactionDelay {
        if (timer.getPropertyAsString("RandomTimer.range").isNotBlank()) {
            val min = parseDelay(timer.delayString(), timer.name.orEmpty(), "delay")
            val range = parseDelay(timer.rangeString(), timer.name.orEmpty(), "range")
            val mode = if (timer::class.java.name.contains("Gaussian")) {
                TransactionController.DELAY_GAUSSIAN_RANDOM
            } else {
                TransactionController.DELAY_RANDOM
            }
            return TransactionDelay(
                mode = mode,
                min = min.toString(),
                max = (min + range).toString(),
                fixed = "0",
            )
        }
        return TransactionDelay(
            mode = TransactionController.DELAY_FIXED,
            min = "0",
            max = "0",
            fixed = parseDelay(timer.delayString(), timer.name.orEmpty(), "delay").toString(),
        )
    }

    private fun applyTransactionDelay(transaction: TransactionController, delay: TransactionDelay) {
        transaction.setDelayMode(delay.mode)
        transaction.setDelayMin(delay.min)
        transaction.setDelayMax(delay.max)
        transaction.setFixedDelay(delay.fixed)
    }

    private fun removeTreeNode(gui: GuiPackage, node: JMeterTreeNode) {
        val elements = mutableListOf<TestElement>()
        fun collect(current: JMeterTreeNode) {
            elements += current.testElement
            for (index in 0 until current.childCount) {
                collect(current.getChildAt(index) as JMeterTreeNode)
            }
        }
        collect(node)
        gui.treeModel.removeNodeFromParent(node)
        elements.forEach { gui.removeNode(it) }
    }

    private fun TestElement.delayString(): String =
        getPropertyAsString("ConstantTimer.delay").ifBlank { "0" }

    private fun TestElement.rangeString(): String =
        getPropertyAsString("RandomTimer.range").ifBlank { "0" }

    private fun parseDelay(raw: String, timerName: String, field: String): Long =
        raw.trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Timer '$timerName' has non-numeric $field value '$raw'")

    private data class TransactionDelay(
        val mode: String,
        val min: String,
        val max: String,
        val fixed: String,
    ) {
        val summary: String
            get() = when (mode) {
                TransactionController.DELAY_FIXED -> "fixed=$fixed ms"
                else -> "mode=$mode min=$min ms max=$max ms"
            }
    }

    private fun regexExtractorNode(source: JMeterTreeNode, variableName: String): JMeterTreeNode {
        val sourceSubTree = GuiPackage.getInstance().treeModel.testPlan.findSubTree(source)
            ?: throw IllegalStateException("Could not locate source sampler subtree '${source.testElement.name}'")
        val matches = mutableListOf<JMeterTreeNode>()
        fun visit(tree: HashTree) {
            for (node in tree.list()) {
                if (node is JMeterTreeNode) {
                    if (node.testElement::class.java.name == "org.apache.jmeter.extractor.RegexExtractor" &&
                        callString(node.testElement, "getRefName") == variableName
                    ) {
                        matches += node
                    }
                    visit(tree.getTree(node))
                }
            }
        }
        visit(sourceSubTree)
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException(
                "No regex extractor named '$variableName' under '${source.testElement.name}'",
            )
            else -> throw IllegalArgumentException(
                "Expected one regex extractor named '$variableName' under '${source.testElement.name}', found ${matches.size}",
            )
        }
    }

    private fun responseAssertionNode(
        source: JMeterTreeNode,
        assertionName: String,
        currentPattern: String?,
    ): JMeterTreeNode {
        val sourceSubTree = GuiPackage.getInstance().treeModel.testPlan.findSubTree(source)
            ?: throw IllegalStateException("Could not locate source sampler subtree '${source.testElement.name}'")
        val matches = mutableListOf<JMeterTreeNode>()
        fun visit(tree: HashTree) {
            for (node in tree.list()) {
                if (node is JMeterTreeNode) {
                    if (node.testElement::class.java.name == "org.apache.jmeter.assertions.ResponseAssertion" &&
                        (assertionName.isBlank() || node.testElement.name == assertionName) &&
                        (currentPattern.isNullOrBlank() || responseAssertionPatterns(node.testElement).contains(currentPattern))
                    ) {
                        matches += node
                    }
                    visit(tree.getTree(node))
                }
            }
        }
        visit(sourceSubTree)
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException(
                "No response assertion matched name='$assertionName' pattern='$currentPattern' under '${source.testElement.name}'",
            )
            else -> throw IllegalArgumentException(
                "Expected one response assertion matched name='$assertionName' pattern='$currentPattern' " +
                    "under '${source.testElement.name}', found ${matches.size}",
            )
        }
    }

    private fun responseAssertionPatterns(assertion: TestElement): List<String> {
        val property = assertion.getProperty("Asserion.test_strings") as? MultiProperty ?: return emptyList()
        return property.mapNotNull { child ->
            when (child) {
                is StringProperty -> child.stringValue
                is ObjectProperty -> child.objectValue as? String
                else -> null
            }
        }
    }

    private fun setHttpArgumentEncode(
        sampler: TestElement,
        argumentName: String?,
        argumentValue: String?,
        alwaysEncode: Boolean,
    ): Int {
        var changed = 0
        for (argument in httpArguments(sampler)) {
            if (argument.matches(argumentName, argumentValue)) {
                setAlwaysEncoded(argument.element, alwaysEncode)
                changed++
            }
        }
        return changed
    }

    private fun setHttpArgumentValue(
        sampler: TestElement,
        argumentName: String?,
        argumentValue: String?,
        newValue: String,
        alwaysEncode: Boolean?,
    ): Int {
        var changed = 0
        for (argument in httpArguments(sampler)) {
            if (argument.matches(argumentName, argumentValue)) {
                val setter = argument.element::class.java.methods.firstOrNull {
                    it.name == "setValue" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
                } ?: throw IllegalArgumentException("Argument '${argument.name}' does not support setValue")
                setter.invoke(argument.element, newValue)
                alwaysEncode?.let { setAlwaysEncoded(argument.element, it) }
                changed++
            }
        }
        return changed
    }

    private data class HttpArgument(
        val element: Any,
        val name: String?,
        val value: String?,
        val alwaysEncode: Boolean?,
    ) {
        fun matches(argumentName: String?, argumentValue: String?): Boolean =
            (argumentName.isNullOrBlank() || name == argumentName) &&
                (argumentValue.isNullOrBlank() || value == argumentValue)
    }

    private fun httpArguments(sampler: TestElement): List<HttpArgument> {
        val containers = samplerArguments(sampler)
        if (containers.isEmpty()) {
            throw IllegalArgumentException("Sampler '${sampler.name}' does not expose HTTP arguments")
        }
        val arguments = mutableListOf<HttpArgument>()
        for (container in containers) {
            val iterator = container.propertyIterator()
            while (iterator.hasNext()) {
                val argument = iterator.next().objectValue ?: continue
                val name = callString(argument, "getName")
                val value = callString(argument, "getValue")
                if (name != null || value != null) {
                    arguments += HttpArgument(argument, name, value, callBoolean(argument, "isAlwaysEncoded"))
                }
            }
        }
        return arguments
    }

    private fun samplerArguments(sampler: TestElement): List<TestElement> {
        val results = mutableListOf<TestElement>()
        val visited = java.util.IdentityHashMap<Any, Boolean>()
        runCatching { sampler::class.java.getMethod("getArguments").invoke(sampler) as? TestElement }
            .getOrNull()
            ?.let { results += it }

        val visitor = object {
            fun visitElement(element: TestElement) {
                if (visited.put(element, true) != null) {
                    return
                }
                if (element::class.java.name == "org.apache.jmeter.config.Arguments") {
                    results += element
                }
                val iterator = element.propertyIterator()
                while (iterator.hasNext()) {
                    visitProperty(iterator.next())
                }
            }

            fun visitProperty(property: JMeterProperty) {
                if (visited.put(property, true) != null) {
                    return
                }
                if (property is TestElementProperty && property.objectValue is TestElement) {
                    visitElement(property.objectValue as TestElement)
                } else if (property is MultiProperty) {
                    for (child in property) {
                        visitProperty(child)
                    }
                } else if (property is ObjectProperty) {
                    when (val value = property.objectValue) {
                        is TestElement -> visitElement(value)
                        is JMeterProperty -> visitProperty(value)
                    }
                }
            }
        }

        visitor.visitElement(sampler)
        return results.distinctBy { System.identityHashCode(it) }
    }

    private fun callString(target: Any, methodName: String): String? =
        runCatching { target::class.java.getMethod(methodName).invoke(target) as? String }
            .getOrNull()

    private fun callBoolean(target: Any, methodName: String): Boolean? =
        runCatching { target::class.java.getMethod(methodName).invoke(target) as? Boolean }
            .getOrNull()

    private fun setAlwaysEncoded(argument: Any, alwaysEncode: Boolean) {
        val setter = argument::class.java.methods.firstOrNull {
            it.name == "setAlwaysEncoded" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: throw IllegalArgumentException("Argument '${callString(argument, "getName")}' does not support setAlwaysEncoded")
        setter.invoke(argument, alwaysEncode)
    }

    private fun setOptionalMethod(target: Any, methodName: String, value: String) {
        target::class.java.methods.firstOrNull {
            it.name == methodName &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }?.invoke(target, value)
    }

    private fun searchElementValues(
        element: TestElement,
        path: List<String>,
        literal: String?,
        pattern: Regex?,
        includeNames: Boolean,
        excludeUserDefinedVariables: Boolean,
        maxMatches: Int,
        visitedElements: java.util.IdentityHashMap<TestElement, Boolean>,
        visitedProperties: java.util.IdentityHashMap<JMeterProperty, Boolean>,
        matches: MutableList<Map<String, Any?>>,
    ) {
        if (matches.size >= maxMatches || visitedElements.put(element, true) != null) {
            return
        }
        val iterator = element.propertyIterator()
        while (iterator.hasNext() && matches.size < maxMatches) {
            searchPropertyValues(
                iterator.next(),
                path,
                literal,
                pattern,
                includeNames,
                excludeUserDefinedVariables,
                maxMatches,
                visitedElements,
                visitedProperties,
                matches,
            )
        }
    }

    private fun searchPropertyValues(
        property: JMeterProperty,
        path: List<String>,
        literal: String?,
        pattern: Regex?,
        includeNames: Boolean,
        excludeUserDefinedVariables: Boolean,
        maxMatches: Int,
        visitedElements: java.util.IdentityHashMap<TestElement, Boolean>,
        visitedProperties: java.util.IdentityHashMap<JMeterProperty, Boolean>,
        matches: MutableList<Map<String, Any?>>,
    ) {
        if (matches.size >= maxMatches || visitedProperties.put(property, true) != null) {
            return
        }
        if (excludeUserDefinedVariables && property.name == TEST_PLAN_USER_DEFINED_VARIABLES) {
            return
        }
        val value = when (property) {
            is StringProperty -> property.stringValue
            is ObjectProperty -> property.objectValue as? String
            else -> null
        }
        if (value != null && (includeNames || property.name != TestElement.NAME)) {
            val matched = literal?.let { value.contains(it) } ?: (pattern?.containsMatchIn(value) == true)
            if (matched) {
                matches += mapOf(
                    "nodePath" to path.joinToString(" / "),
                    "property" to property.name,
                    "excerpt" to value.take(500),
                )
            }
        }
        if (property is TestElementProperty && property.objectValue is TestElement) {
            searchElementValues(
                property.objectValue as TestElement,
                path,
                literal,
                pattern,
                includeNames,
                excludeUserDefinedVariables,
                maxMatches,
                visitedElements,
                visitedProperties,
                matches,
            )
        } else if (property is MultiProperty) {
            for (child in property) {
                searchPropertyValues(
                    child,
                    path,
                    literal,
                    pattern,
                    includeNames,
                    excludeUserDefinedVariables,
                    maxMatches,
                    visitedElements,
                    visitedProperties,
                    matches,
                )
            }
        }
    }

    private fun markEdited(gui: GuiPackage, changedNode: JMeterTreeNode, selectNode: JMeterTreeNode = changedNode) {
        gui.setDirty(true)
        gui.treeModel.nodeStructureChanged(changedNode)
        gui.mainFrame.tree.expandPath(TreePath(changedNode.path))
        gui.mainFrame.tree.selectionPath = TreePath(selectNode.path)
        gui.mainFrame.repaint()
    }

    private fun recordChange(type: String, node: JMeterTreeNode, summary: String, details: String? = null) {
        AiAutoScriptingLogWindow.recordChange(
            type,
            node.testElement.name.orEmpty(),
            summary,
            details,
            TreePath(node.path),
        )
    }

    private fun displayVariableValue(name: String, value: String): String =
        if (name.contains(Regex("(?i)(password|passwd|pwd|secret|token|apikey|api_key|client_secret)"))) {
            "(hidden)"
        } else {
            value
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

    private fun repairCloneFileFor(testPlanFile: String?): File {
        val source = testPlanFile?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
        val directory = source?.parentFile?.takeIf { it.isDirectory }
            ?: File(JMeterUtils.getJMeterHome()).takeIf { it.isDirectory }
            ?: File(System.getProperty("java.io.tmpdir"))
        val baseName = source?.nameWithoutExtension?.takeIf { it.isNotBlank() } ?: "untitled"
        val timestamp = LocalDateTime.now().format(backupTimeFormat)
        return File(directory, "$baseName.ai-repaired-$timestamp.jmx")
    }

    private fun backupKey(testPlanFile: String?): String =
        testPlanFile?.takeIf { it.isNotBlank() }?.let { File(it).absolutePath } ?: "(untitled)"

    private data class KnowledgeNodeCandidate(
        val node: JMeterTreeNode,
        val parent: JMeterTreeNode?,
        val path: String,
        val directTestPlanChild: Boolean,
        val defaultKnowledge: Boolean,
    )

    private data class KnowledgeNodeSelection(
        val selected: KnowledgeNodeCandidate,
        val candidates: List<KnowledgeNodeCandidate>,
    )

    private fun ensureKnowledgeNode(gui: GuiPackage): KnowledgeNodeSelection {
        val testPlanNode = testPlanNode(gui)
        val candidates = findKnowledgeNodes(gui.treeModel.testPlan, testPlanNode)
        selectKnowledgeNode(candidates)?.let { selected ->
            return KnowledgeNodeSelection(selected, candidates)
        }

        val node = addConfiguredComponent(gui, testPlanNode, BreakTestAiKnowledgeGui::class.java.name) { element ->
            element.name = BreakTestAiKnowledge.DEFAULT_NAME
        }
        val candidate = knowledgeNodeCandidate(node, testPlanNode, testPlanNode)
        gui.setDirty(true)
        gui.treeModel.nodeStructureChanged(testPlanNode)
        gui.mainFrame.tree.expandPath(TreePath(testPlanNode.path))
        gui.mainFrame.tree.selectionPath = TreePath(node.path)
        postActivity("info", "Created BreakTest AI Knowledge node")
        return KnowledgeNodeSelection(candidate, listOf(candidate))
    }

    private fun selectKnowledgeNode(candidates: List<KnowledgeNodeCandidate>): KnowledgeNodeCandidate? =
        candidates.maxWithOrNull(
            compareBy<KnowledgeNodeCandidate> { !it.defaultKnowledge }
                .thenBy { it.directTestPlanChild },
        )

    private fun findKnowledgeNodes(tree: HashTree, testPlanNode: JMeterTreeNode): List<KnowledgeNodeCandidate> {
        val candidates = mutableListOf<KnowledgeNodeCandidate>()
        fun walk(currentTree: HashTree, parent: JMeterTreeNode?) {
            for (node in currentTree.list()) {
                if (node is JMeterTreeNode) {
                    if (node.testElement is BreakTestAiKnowledge) {
                        candidates += knowledgeNodeCandidate(node, parent, testPlanNode)
                    }
                    walk(currentTree.getTree(node), node)
                } else {
                    walk(currentTree.getTree(node), parent)
                }
            }
        }
        walk(tree, null)
        return candidates
    }

    private fun knowledgeNodeCandidate(
        node: JMeterTreeNode,
        parent: JMeterTreeNode?,
        testPlanNode: JMeterTreeNode,
    ): KnowledgeNodeCandidate {
        val element = node.testElement as BreakTestAiKnowledge
        return KnowledgeNodeCandidate(
            node = node,
            parent = parent,
            path = node.path.joinToString(" / ") { pathNode ->
                (pathNode as? JMeterTreeNode)?.name ?: pathNode.toString()
            },
            directTestPlanChild = parent === testPlanNode,
            defaultKnowledge = isDefaultKnowledge(element.knowledgeJson),
        )
    }

    private fun isDefaultKnowledge(knowledgeJson: String): Boolean =
        runCatching { mapper.readTree(knowledgeJson) == defaultKnowledgeJson }
            .getOrDefault(knowledgeJson.trim() == BreakTestAiKnowledge.DEFAULT_JSON.trim())

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
            failOnNoMatch = arguments.path("failOnNoMatch").takeIfPresent()?.asBoolean() ?: true,
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
            failOnNoMatch = arguments.path("failOnNoMatch").takeIfPresent()?.asBoolean() ?: true,
        )

    private fun regexExtractorUpdateRequest(arguments: JsonNode): RegexExtractorUpdateRequest =
        RegexExtractorUpdateRequest(
            sourceSamplerIndex = arguments.path("sourceSamplerIndex").takeIfPresent()?.asInt(),
            sourceSamplerLabel = arguments.path("sourceSamplerLabel").takeIfPresent()?.asText(),
            variableName = arguments.requiredText("variableName"),
            regex = arguments.path("regex").takeIfPresent()?.asText(),
            template = arguments.path("template").takeIfPresent()?.asText(),
            matchNumber = arguments.path("matchNumber").takeIfPresent()?.asText(),
            defaultValue = arguments.path("defaultValue").takeIfPresent()?.asText(),
            useField = arguments.path("useField").takeIfPresent()?.asText(),
            failOnNoMatch = arguments.path("failOnNoMatch").takeIfPresent()?.asBoolean(),
        )

    private fun literalReplacementRequest(arguments: JsonNode): LiteralReplacementRequest =
        LiteralReplacementRequest(
            targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
            targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
            literal = arguments.requiredText("literal"),
            replacement = arguments.requiredText("replacement"),
            includeNames = arguments.path("includeNames").asBoolean(false),
            excludeUserDefinedVariables = arguments.path("excludeUserDefinedVariables").asBoolean(false),
        )

    private fun responseAssertionRequest(arguments: JsonNode): ResponseAssertionRequest =
        arguments.requiredText("pattern").let { pattern ->
            validateAssertionPattern(pattern, arguments.path("allowWeakPattern").asBoolean(false))
            ResponseAssertionRequest(
                targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
                targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
                assertionName = arguments.path("assertionName").takeIfPresent()?.asText() ?: "AI Response Assertion",
                pattern = pattern,
                field = arguments.path("field").takeIfPresent()?.asText() ?: "body",
                matchType = arguments.path("matchType").takeIfPresent()?.asText() ?: "substring",
            )
        }

    private data class EditEvidence(
        val source: String,
        val details: String,
    ) {
        val summary: String
            get() = if (details.isBlank()) source else "$source: $details"
    }

    private fun requireResponseEvidence(arguments: JsonNode, action: String): EditEvidence {
        val source = arguments.path("evidenceSource").takeIfPresent()?.asText()?.trim().orEmpty()
        val details = arguments.path("evidence").takeIfPresent()?.asText()?.trim()
            ?: arguments.path("sourceEvidence").takeIfPresent()?.asText()?.trim()
            ?: arguments.path("evidenceDetails").takeIfPresent()?.asText()?.trim()
            ?: ""
        val allowStaticInference = arguments.path("allowStaticInference").asBoolean(false)
        require(source.isNotBlank()) {
            "Cannot $action without evidenceSource. Use validated_response, recorded_response, or ai_knowledge; " +
                "for weaker static inference, set evidenceSource=static_plan_inference and allowStaticInference=true."
        }
        require(details.isNotBlank()) {
            "Cannot $action without evidence details. Include the exact validated/recorded marker, response field, " +
                "or AI Knowledge entry that supports this edit."
        }
        val normalized = source.lowercase().replace('-', '_')
        val allowed = setOf("validated_response", "recorded_response", "ai_knowledge")
        if (normalized !in allowed) {
            require(normalized == "static_plan_inference" && allowStaticInference) {
                "Cannot $action from '$source' evidence. Use validated_response, recorded_response, or ai_knowledge. " +
                    "Static inference is only allowed with allowStaticInference=true and must be documented as unvalidated."
            }
        }
        return EditEvidence(normalized, details)
    }

    private fun validateJMeterFunctionSyntax(value: String) {
        require(!INVALID_UUID_FUNCTION_REGEX.containsMatchIn(value)) {
            "Invalid JMeter function syntax '$value': __UUID takes no parameters in this BreakTest/JMeter build. " +
                "Use \${__UUID} for one-off UUIDs, or create a JSR223/setup variable when the same UUID must be reused."
        }
    }

    private fun validateAssertionPattern(pattern: String, allowWeakPattern: Boolean) {
        if (allowWeakPattern) {
            return
        }
        val normalized = pattern.trim()
        require(normalized.length >= 8) {
            "Weak response assertion '$pattern': use a significant sentence, HTML/JSON/XML fragment, or unique response marker"
        }
        val singleToken = normalized.none { it.isWhitespace() } &&
            !normalized.any { it in "<>{}[]:=/\"'" }
        val genericToken = WEAK_ASSERTION_TOKENS.contains(normalized.lowercase())
        require(!singleToken || !genericToken) {
            "Weak response assertion '$pattern': generic single-word assertions are not allowed"
        }
    }

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

    private fun selectOptionalSampler(
        samplers: List<JMeterTreeNode>,
        index: Int?,
        label: String?,
        role: String,
    ): JMeterTreeNode? {
        if (index == null && label.isNullOrBlank()) {
            return null
        }
        return selectSampler(samplers, index, label, role)
    }

    private fun selectThreadGroup(gui: GuiPackage, threadGroupName: String?): JMeterTreeNode {
        val matches = mutableListOf<JMeterTreeNode>()
        fun visit(tree: HashTree) {
            for (node in tree.list()) {
                if (node is JMeterTreeNode && node.testElement is AbstractThreadGroup && node.isEnabled) {
                    if (threadGroupName.isNullOrBlank() || node.testElement.name == threadGroupName) {
                        matches += node
                    }
                }
                visit(tree.getTree(node))
            }
        }
        visit(gui.treeModel.testPlan)
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException("No enabled Thread Group matched '$threadGroupName'")
            else -> throw IllegalArgumentException("Expected one enabled Thread Group named '$threadGroupName', found ${matches.size}")
        }
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

    private fun HashTree.findSubTree(treeNode: JMeterTreeNode): HashTree? {
        for (node in list()) {
            val subTree = getTree(node)
            if (node === treeNode) {
                return subTree
            }
            val nested = subTree.findSubTree(treeNode)
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
        if (arguments.path("includeDsl").takeIfPresent()?.asBoolean() != true) {
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
