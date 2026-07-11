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
import org.apache.jmeter.ai.AgentRegexSupport
import org.apache.jmeter.ai.AgentReportCompactor
import org.apache.jmeter.ai.AgentRunOptions
import org.apache.jmeter.ai.AgentSampleSummary
import org.apache.jmeter.ai.BreakTestAgent
import org.apache.jmeter.ai.edit.BoundaryCorrelationRequest
import org.apache.jmeter.ai.edit.LiteralReplacementRequest
import org.apache.jmeter.ai.edit.RedirectModeRequest
import org.apache.jmeter.ai.edit.RegexCorrelationRequest
import org.apache.jmeter.ai.edit.RegexExtractorUpdateRequest
import org.apache.jmeter.ai.edit.ResponseAssertionRequest
import org.apache.jmeter.ai.edit.TestPlanEditor
import org.apache.jmeter.ai.knowledge.BreakTestAiKnowledge
import org.apache.jmeter.ai.knowledge.gui.BreakTestAiKnowledgeGui
import org.apache.jmeter.control.Controller
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.action.ActionNames
import org.apache.jmeter.gui.action.Copy
import org.apache.jmeter.gui.action.Load
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver
import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.testbeans.gui.TestBeanGUI
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.BooleanProperty
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.MultiProperty
import org.apache.jmeter.testelement.property.ObjectProperty
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.util.JMeterUtils
import org.apache.jmeter.util.JSR223TestElement
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import org.slf4j.LoggerFactory
import java.awt.event.ActionEvent
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath
import kotlin.concurrent.thread

public object BreakTestAgentGuiService {
    private const val DEFAULT_DSL_CHARACTER_LIMIT = 80_000
    private const val MAX_REPAIR_ACTION_SNAPSHOTS = 8
    private const val MAX_ACTIVE_FILE_REFRESHES_PER_RUN = 1
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

    // Assertion markers that carry run-specific values: JSON number values
    // ("total":35), UUIDs, ISO-like dates, or epoch-millisecond numbers.
    private val VOLATILE_ASSERTION_FRAGMENT_REGEX = Regex(
        """(?x)
        "[^"]+"\s*:\s*-?\d+(?:\.\d+)?(?![\w.]) |
        [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12} |
        \b20\d{2}-\d{2}-\d{2} |
        (?<!\d)1[6-9]\d{11}(?!\d)
        """,
    )
    private val STATIC_HAR_REQUEST_REGEX =
        Regex("""(?:\.|/)(?:css|js|mjs|map|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|otf)(?:[?/"'\s]|$)""")
    private val HAR_FIELD_VALUE_REGEX = Regex(
        """(?i)([A-Za-z0-9_.:-]*(?:id|uuid|token|nonce|state|code|csrf|verification|challenge|draw|date|time|timestamp|session|basket|ticket|order|product|client)[A-Za-z0-9_.:-]*)["']?\s*(?:=|:)\s*["']?([^"',&\s{}<>\[\]]{4,})""",
    )
    private val BEARER_TOKEN_REGEX = Regex("""(?i)Authorization:\s*Bearer\s+([A-Za-z0-9._~+/\-=]{12,})""")
    private val UUID_REGEX = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val EPOCH_MS_REGEX = Regex("""\b1[6-9]\d{11}\b""")
    private val LONG_OPAQUE_REGEX = Regex("""\b[A-Za-z0-9._~+/\-=]{32,}\b""")
    private val LOW_VALUE_HAR_LITERALS = setOf(
        "keep-alive",
        "gzip",
        "br",
        "deflate",
        "cors",
        "same-origin",
        "no-cache",
        "no-store",
        "anonymous",
        "undefined",
    )
    private var serverSocket: ServerSocket? = null
    private var unixServerSocket: ServerSocketChannel? = null
    private var token: String? = null
    private val backedUpPlanKeys = mutableSetOf<String>()
    private var latestOpenPlanBackup: OpenPlanBackup? = null
    private var activeFileRefreshesThisRun = 0
    private val repairActionSnapshots = object : LinkedHashMap<String, Map<String, Map<String, Any?>>>() {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Map<String, Map<String, Any?>>>?,
        ): Boolean = size > MAX_REPAIR_ACTION_SNAPSHOTS
    }

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
            val arguments = node.path("arguments")
            val argumentBytes = jsonByteSize(arguments)
            val result = handleTool(tool, arguments)
            val resultBytes = jsonByteSize(result)
            if (tool != "agent_activity") {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                postActivity(
                    "completed",
                    "MCP tool `$tool` completed in ${elapsedMs}ms",
                    details = "payload: args=${argumentBytes.toHumanBytes()}, result=${resultBytes.toHumanBytes()}",
                )
            }
            mapOf("ok" to true, "result" to result)
        } catch (e: Exception) {
            log.warn("BreakTest Agent GUI tool '{}' failed", tool, e)
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
            "refresh_open_plan_from_file" -> refreshOpenPlanFromFile(arguments)
            "restore_open_plan_from_backup" -> restoreOpenPlanFromBackup(arguments)
            "inspect_open_plan" -> BreakTestAgent().inspect(
                currentPlanTree(),
                dslCharacterLimit(arguments),
                includeStaticAssets(arguments),
            )
            "validate_open_plan" -> validateOpenPlan(arguments)
            "search_validated_response_open_plan" -> searchValidatedResponseOpenPlan(arguments)
            "backup_open_plan" -> mapOf("backupPath" to createBackupForOpenPlan())
            "get_ai_knowledge_open_plan" -> getAiKnowledgeOpenPlan(arguments)
            "update_ai_knowledge_open_plan" -> updateAiKnowledgeOpenPlan(arguments)
            "list_agent_changes_open_plan" -> AiAutoScriptingLogWindow.changes()
            "find_open_plan_nodes" -> findOpenPlanNodes(arguments)
            "agent_activity" -> handleAgentActivity(arguments)
            "list_recorded_har_exchanges_open_plan" -> listRecordedHarExchangesOpenPlan(arguments)
            "get_recorded_har_exchange_open_plan" -> getRecordedHarExchangeOpenPlan(arguments)
            "search_recorded_har_open_plan" -> searchRecordedHarOpenPlan(arguments)
            "audit_recorded_har_correlations_open_plan" -> auditRecordedHarCorrelationsOpenPlan(arguments)
            "plan_repair_actions_open_plan" -> planRepairActionsOpenPlan(arguments)
            "get_repair_action_open_plan" -> getRepairActionOpenPlan(arguments)
            "get_repair_actions_open_plan" -> getRepairActionsOpenPlan(arguments)
            "apply_repair_actions_open_plan" -> applyRepairActionsOpenPlan(arguments)
            "add_response_assertions_open_plan" -> addResponseAssertionsOpenPlan(arguments)
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
            "clone_node_open_plan" -> cloneNodeOpenPlan(arguments)
            "move_node_open_plan" -> moveNodeOpenPlan(arguments)
            "delete_node_open_plan" -> deleteNodeOpenPlan(arguments)
            "move_think_times_to_transactions_open_plan" -> moveThinkTimesToTransactionsOpenPlan(arguments)
            else -> throw IllegalArgumentException("Unknown GUI agent tool: $tool")
        }

    @JvmStatic
    public fun createBackupForOpenPlan(): String? =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            activeFileRefreshesThisRun = 0
            val backupFile = backupFileFor(gui.testPlanFile)
            backupFile.parentFile?.mkdirs()
            backupFile.outputStream().use { SaveService.saveTree(convertTree(gui.treeModel.testPlan), it) }
            backedUpPlanKeys += backupKey(gui.testPlanFile)
            latestOpenPlanBackup = OpenPlanBackup(
                file = backupFile.canonicalFile,
                originalPlanPath = gui.testPlanFile?.takeIf { it.isNotBlank() },
                wasDirty = gui.isDirty,
            )
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
                "Created AI repair clone from current open GUI state",
                details = "${cloneFile.path} (includes current in-memory edits)",
            )
            cloneFile.path
        }

    @JvmStatic
    public fun mergeRepairCloneIntoOpenPlan(repairTargetPath: String, threadGroupPath: String): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val repairFile = File(repairTargetPath)
            require(repairFile.isFile) {
                "Repair target does not exist: ${repairFile.path}"
            }
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)

            val repairedTree = SaveService.loadTree(repairFile)
            val repairedThreadGroup = findThreadGroupInTree(repairedTree, threadGroupPath)
            val repairedSubTree = repairedTree.findSubTree(repairedThreadGroup)
                ?: throw IllegalStateException("Could not locate repaired Thread Group subtree for '$threadGroupPath'")
            val activeThreadGroup = selectThreadGroup(gui, threadGroupPath)
            val parent = activeThreadGroup.parent as? JMeterTreeNode
                ?: throw IllegalStateException("Selected Thread Group has no parent")

            gui.beginUndoTransaction()
            try {
                val generatedName = uniqueGeneratedThreadGroupName(gui, repairedThreadGroup.name.orEmpty())
                repairedThreadGroup.name = generatedName
                mergeUserDefinedVariables(gui, repairedTree)
                // Insert the subtree without JMeterTreeModel.addSubTree: that path runs
                // every element through the shared per-class GUI panels and flushes them
                // into the currently selected node, which renamed the user's original
                // (still selected) Thread Group to the *_AI_Generated name.
                val generatedNode = insertDetachedNode(gui, parent, repairedThreadGroup)
                insertDetachedSubTree(gui, generatedNode, repairedSubTree)
                gui.treeModel.nodeStructureChanged(parent)
                markEdited(gui, parent, generatedNode)
                postActivity(
                    "info",
                    "Merged AI generated Thread Group",
                    details = "${repairFile.path} -> ${nodePath(generatedNode)}",
                )
                recordChange(
                    "Merged repair",
                    generatedNode,
                    "Added AI generated Thread Group",
                    "Source: ${repairFile.path}",
                )
                mapOf(
                    "merged" to true,
                    "sourcePath" to repairFile.path,
                    "threadGroupName" to generatedName,
                    "threadGroupNodePath" to nodePath(generatedNode),
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun refreshOpenPlanFromFile(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val current = gui.testPlanFile?.takeIf { it.isNotBlank() }
                ?: error("The open plan must be saved before it can be refreshed")
            val requested = arguments.path("path").optionalText()?.takeIf { it.isNotBlank() } ?: current
            val currentFile = File(current).canonicalFile
            val requestedFile = File(requested).canonicalFile
            require(currentFile == requestedFile) {
                "Refusing to refresh a different JMX. Current open plan is ${currentFile.path}, requested ${requestedFile.path}"
            }
            require(requestedFile.isFile) {
                "Cannot refresh open plan because file does not exist: ${requestedFile.path}"
            }
            val force = arguments.path("force").asBoolean(false)
            if (!force && activeFileRefreshesThisRun >= MAX_ACTIVE_FILE_REFRESHES_PER_RUN) {
                postActivity(
                    "warn",
                    "Skipped active JMX refresh because this AI run already refreshed the GUI once",
                    details = "Continuing against the saved active JMX file without another expensive GUI reload: ${requestedFile.path}.",
                )
                return@guiCall mapOf(
                    "refreshed" to false,
                    "skipped" to true,
                    "reason" to "refresh_budget_exhausted",
                    "path" to requestedFile.path,
                    "refreshesThisRun" to activeFileRefreshesThisRun,
                )
            }
            gui.updateCurrentNode()
            if (gui.isDirty) {
                postActivity(
                    "warn",
                    "Skipped active JMX refresh because the GUI has unsaved changes",
                    details = "Save or discard the current GUI edits before refreshing from ${requestedFile.path}.",
                )
                return@guiCall mapOf(
                    "refreshed" to false,
                    "skipped" to true,
                    "reason" to "gui_dirty",
                    "path" to requestedFile.path,
                )
            }
            Load.loadProjectFile(
                ActionEvent(BreakTestAgentGuiService::class.java, ActionEvent.ACTION_PERFORMED, ActionNames.OPEN),
                requestedFile,
                false,
            )
            activeFileRefreshesThisRun++
            gui.setDirty(false)
            postActivity(
                "info",
                "Refreshed open plan from active JMX file",
                details = "${requestedFile.path} (refresh $activeFileRefreshesThisRun/$MAX_ACTIVE_FILE_REFRESHES_PER_RUN)",
            )
            mapOf(
                "refreshed" to true,
                "path" to requestedFile.path,
                "refreshesThisRun" to activeFileRefreshesThisRun,
            )
        }

    private fun restoreOpenPlanFromBackup(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val requestedPath = arguments.path("path").optionalText()
            restoreLatestOpenPlanBackup(requestedPath, "explicit agent recovery")
        }

    private fun restoreLatestOpenPlanBackup(requestedPath: String?, reason: String): Map<String, Any?> {
        val backup = latestOpenPlanBackup
            ?: throw IllegalStateException("No AI pre-run backup is available for the current BreakTest session")
        val requestedFile = requestedPath?.let { File(it).canonicalFile }
        require(requestedFile == null || requestedFile == backup.file) {
            "Refusing to restore an unrecognized backup. Expected ${backup.file.path}, requested ${requestedFile?.path}"
        }
        require(backup.file.isFile) {
            "AI pre-run backup does not exist: ${backup.file.path}"
        }
        // Parse before touching the live GUI so a corrupt or incomplete backup
        // can never replace the current in-memory plan.
        SaveService.loadTree(backup.file)
        Load.loadProjectFile(
            ActionEvent(BreakTestAgentGuiService::class.java, ActionEvent.ACTION_PERFORMED, ActionNames.OPEN),
            backup.file,
            false,
        )
        val restoredGui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready after backup restore")
        restoredGui.setTestPlanFile(backup.originalPlanPath)
        restoredGui.setDirty(backup.wasDirty)
        NodeIds.clear()
        activeFileRefreshesThisRun = 0
        postActivity(
            "warn",
            "Restored open plan from AI pre-run backup",
            details = "${backup.file.path}; reason=$reason",
        )
        return mapOf(
            "restored" to true,
            "backupPath" to backup.file.path,
            "originalPlanPath" to backup.originalPlanPath,
            "dirty" to backup.wasDirty,
            "reason" to reason,
        )
    }

    private fun listRecordedHarExchangesOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val testPlanFile = gui.testPlanFile?.takeIf { it.isNotBlank() }
                ?: return@guiCall mapOf(
                    "linkedHarAvailable" to false,
                    "status" to RecordedHarExchangeResolver.Status.TEST_PLAN_FILE_UNKNOWN.name,
                    "diagnostic" to "The open plan must be saved before linked HAR paths can be resolved.",
                    "exchanges" to emptyList<Map<String, Any?>>(),
                )
            val bodyLimit = arguments.path("bodyLimit").asInt(600).coerceAtLeast(0)
            val maxEntries = arguments.path("maxEntries").asInt(80).coerceAtLeast(1)
            val includeStaticAssets = arguments.path("includeStaticAssets").asBoolean(false)
            val samplers = scopedSamplerNodes(gui, arguments.path("threadGroupName").optionalText())
            val exchanges = samplers
                .mapIndexedNotNull { index, sampler ->
                    val resolution = RecordedHarExchangeResolver.resolveFor(sampler, Path.of(testPlanFile))
                    if (!includeStaticAssets && isStaticHarRequest(resolution.requestText())) {
                        return@mapIndexedNotNull null
                    }
                    mapOf(
                        "samplerIndex" to index,
                        "samplerLabel" to sampler.testElement.name,
                        "nodePath" to nodePath(sampler),
                        "status" to resolution.status().name,
                        "hasExchange" to resolution.exchange().isPresent,
                        "requestLine" to firstNonBlankLine(resolution.requestText()),
                        "responseLine" to firstNonBlankLine(resolution.responseText()),
                        "requestSnippet" to resolution.requestText().truncate(bodyLimit),
                        "responseSnippet" to resolution.responseText().truncate(bodyLimit),
                    )
                }
                .take(maxEntries)
            mapOf(
                "linkedHarAvailable" to exchanges.any { it["hasExchange"] == true },
                "testPlanFile" to testPlanFile,
                "threadGroupName" to arguments.path("threadGroupName").optionalText(),
                "exchangeCount" to exchanges.size,
                "truncated" to (samplers.size > maxEntries),
                "exchanges" to exchanges,
            )
        }

    private fun getRecordedHarExchangeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val testPlanFile = gui.testPlanFile?.takeIf { it.isNotBlank() }
                ?: error("The open plan must be saved before linked HAR paths can be resolved")
            val bodyLimit = arguments.path("bodyLimit").asInt(12_000).coerceAtLeast(0)
            val sampler = selectSampler(
                scopedSamplerNodes(gui, arguments.path("threadGroupName").optionalText()),
                arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
                arguments.path("targetSamplerLabel").optionalText(),
                "target",
            )
            val resolution = RecordedHarExchangeResolver.resolveFor(sampler, Path.of(testPlanFile))
            mapOf(
                "samplerLabel" to sampler.testElement.name,
                "nodePath" to nodePath(sampler),
                "status" to resolution.status().name,
                "hasExchange" to resolution.exchange().isPresent,
                "requestLine" to firstNonBlankLine(resolution.requestText()),
                "responseLine" to firstNonBlankLine(resolution.responseText()),
                "request" to resolution.requestText().truncate(bodyLimit),
                "response" to resolution.responseText().truncate(bodyLimit),
            )
        }

    private fun searchRecordedHarOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val testPlanFile = gui.testPlanFile?.takeIf { it.isNotBlank() }
                ?: error("The open plan must be saved before linked HAR paths can be resolved")
            val query = arguments.path("query").optionalText()
            val regexText = arguments.path("regex").optionalText()
            require(query != null || regexText != null) {
                "Specify query or regex"
            }
            val regex = regexText?.let { Regex(it, RegexOption.IGNORE_CASE) }
            val contextChars = arguments.path("contextChars").asInt(180).coerceAtLeast(0)
            val maxMatches = arguments.path("maxMatches").asInt(50).coerceAtLeast(1)
            val includeStaticAssets = arguments.path("includeStaticAssets").asBoolean(false)
            val matches = mutableListOf<Map<String, Any?>>()
            val samplers = scopedSamplerNodes(gui, arguments.path("threadGroupName").optionalText())
            for ((index, sampler) in samplers.withIndex()) {
                val resolution = RecordedHarExchangeResolver.resolveFor(sampler, Path.of(testPlanFile))
                if (!resolution.exchange().isPresent) {
                    continue
                }
                if (!includeStaticAssets && isStaticHarRequest(resolution.requestText())) {
                    continue
                }
                fun search(surface: String, text: String) {
                    if (matches.size >= maxMatches) {
                        return
                    }
                    val range = if (regex != null) {
                        regex.find(text)?.range
                    } else {
                        val start = text.indexOf(query!!, ignoreCase = true)
                        if (start >= 0) start until (start + query.length) else null
                    } ?: return
                    matches += mapOf(
                        "samplerIndex" to index,
                        "samplerLabel" to sampler.testElement.name,
                        "nodePath" to nodePath(sampler),
                        "surface" to surface,
                        "requestLine" to firstNonBlankLine(resolution.requestText()),
                        "responseLine" to firstNonBlankLine(resolution.responseText()),
                        "context" to text.contextAround(range.first, range.last + 1, contextChars),
                    )
                }
                search("recorded_request", resolution.requestText())
                search("recorded_response", resolution.responseText())
                if (matches.size >= maxMatches) {
                    break
                }
            }
            mapOf(
                "query" to query,
                "regex" to regexText,
                "matchCount" to matches.size,
                "truncated" to (matches.size >= maxMatches),
                "matches" to matches,
            )
        }

    private fun auditRecordedHarCorrelationsOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val testPlanFile = gui.testPlanFile?.takeIf { it.isNotBlank() }
                ?: error("The open plan must be saved before linked HAR paths can be resolved")
            val includeStaticAssets = arguments.path("includeStaticAssets").asBoolean(false)
            val maxCandidates = arguments.path("maxCandidates").asInt(120).coerceAtLeast(1)
            val contextChars = arguments.path("contextChars").asInt(180).coerceAtLeast(0)
            val exchanges = linkedHarExchanges(
                gui,
                testPlanFile,
                arguments.path("threadGroupName").optionalText(),
                includeStaticAssets,
            )
            val candidates = mutableListOf<Map<String, Any?>>()
            val unresolved = mutableListOf<Map<String, Any?>>()
            val seen = mutableSetOf<String>()
            for (target in exchanges) {
                val requestCandidates = harRequestCandidates(target.request)
                for (requestCandidate in requestCandidates) {
                    val key = "${target.index}:${requestCandidate.literal}"
                    if (!seen.add(key)) {
                        continue
                    }
                    val source = exchanges
                        .asSequence()
                        .filter { it.index < target.index }
                        .mapNotNull { source ->
                            val index = source.response.indexOf(requestCandidate.literal, ignoreCase = false)
                            if (index >= 0) {
                                source to index
                            } else {
                                null
                            }
                        }
                        .lastOrNull()
                    if (source != null) {
                        val (sourceExchange, literalIndex) = source
                        candidates += mapOf(
                            "priority" to requestCandidate.priority,
                            "kind" to requestCandidate.kind,
                            "fieldName" to requestCandidate.fieldName,
                            "literal" to requestCandidate.literal,
                            "tokenPreview" to requestCandidate.literal.previewToken(),
                            "variableName" to variableNameFor(requestCandidate),
                            "targetSamplerIndex" to target.globalIndex,
                            "targetScopedIndex" to target.index,
                            "targetSamplerLabel" to target.sampler.testElement.name,
                            "targetNodePath" to nodePath(target.sampler),
                            "targetRequestLine" to firstNonBlankLine(target.request),
                            "targetSurface" to requestCandidate.surface,
                            "sourceSamplerIndex" to sourceExchange.globalIndex,
                            "sourceScopedIndex" to sourceExchange.index,
                            "sourceSamplerLabel" to sourceExchange.sampler.testElement.name,
                            "sourceNodePath" to nodePath(sourceExchange.sampler),
                            "sourceRequestLine" to firstNonBlankLine(sourceExchange.request),
                            "sourceResponseLine" to firstNonBlankLine(sourceExchange.response),
                            "evidenceSource" to "recorded_response",
                            "evidence" to sourceExchange.response.contextAround(
                                literalIndex,
                                literalIndex + requestCandidate.literal.length,
                                contextChars,
                            ),
                            "reason" to "Suspicious request value appears in an earlier linked HAR recorded response.",
                        )
                    } else if (requestCandidate.priority >= 80 && unresolved.size < maxCandidates) {
                        unresolved += mapOf(
                            "priority" to requestCandidate.priority,
                            "kind" to requestCandidate.kind,
                            "fieldName" to requestCandidate.fieldName,
                            "literal" to requestCandidate.literal,
                            "tokenPreview" to requestCandidate.literal.previewToken(),
                            "targetSamplerIndex" to target.globalIndex,
                            "targetScopedIndex" to target.index,
                            "targetSamplerLabel" to target.sampler.testElement.name,
                            "targetNodePath" to nodePath(target.sampler),
                            "targetRequestLine" to firstNonBlankLine(target.request),
                            "targetSurface" to requestCandidate.surface,
                            "reason" to "Suspicious request value was not found in earlier linked HAR recorded responses.",
                        )
                    }
                    if (candidates.size >= maxCandidates) {
                        break
                    }
                }
                if (candidates.size >= maxCandidates) {
                    break
                }
            }
            val sortedCandidates = candidates
                .sortedWith(
                    compareByDescending<Map<String, Any?>> { it["priority"] as Int }
                        .thenBy { it["targetSamplerIndex"] as Int },
                )
                .take(maxCandidates)
            mapOf(
                "linkedHarAvailable" to exchanges.isNotEmpty(),
                "threadGroupName" to arguments.path("threadGroupName").optionalText(),
                "exchangeCount" to exchanges.size,
                "candidateCount" to sortedCandidates.size,
                "truncated" to (candidates.size >= maxCandidates),
                "candidates" to sortedCandidates,
                "unresolvedHighConfidenceCount" to unresolved.size,
                "unresolvedHighConfidence" to unresolved.take(maxCandidates),
            )
        }

    private fun planRepairActionsOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val threadGroupName = arguments.path("threadGroupName").optionalText()
            val includeStaticAssets = arguments.path("includeStaticAssets").asBoolean(false)
            val maxActions = arguments.path("maxActions").asInt(40).coerceAtLeast(1)
            val maxUnresolved = arguments.path("maxUnresolved").asInt(30).coerceAtLeast(0)
            val includeApplyArguments = arguments.path("includeApplyArguments").asBoolean(false)
            val contextChars = arguments.path("contextChars").asInt(80).coerceAtLeast(0)
            val testPlanFile = gui.testPlanFile?.takeIf { it.isNotBlank() }
            val actions = mutableListOf<Map<String, Any?>>()
            val unresolved = mutableListOf<Map<String, Any?>>()
            val usedVariableNames = mutableSetOf<String>()

            if (testPlanFile != null) {
                val harActions = planHarCorrelationActions(
                    gui = gui,
                    testPlanFile = testPlanFile,
                    threadGroupName = threadGroupName,
                    includeStaticAssets = includeStaticAssets,
                    contextChars = contextChars,
                    usedVariableNames = usedVariableNames,
                    unresolved = unresolved,
                    maxUnresolved = maxUnresolved,
                )
                actions += harActions
            }

            actions += planCredentialActions(
                gui = gui,
                threadGroupName = threadGroupName,
                includeStaticAssets = includeStaticAssets,
                usedVariableNames = usedVariableNames,
            )

            val sortedActions = actions
                .sortedWith(
                    compareByDescending<Map<String, Any?>> { it["confidence"] as Int }
                        .thenByDescending { it["priority"] as Int }
                        .thenBy { it["targetSamplerIndex"] as? Int ?: Int.MAX_VALUE },
                )
                .take(maxActions)
            val snapshotId = "repair-actions-${UUID.randomUUID()}"
            repairActionSnapshots[snapshotId] = sortedActions.associateBy { it["id"].toString() }
            mapOf(
                "snapshotId" to snapshotId,
                "threadGroupName" to threadGroupName,
                "actionCount" to sortedActions.size,
                "unresolvedCount" to unresolved.size,
                "includeApplyArguments" to includeApplyArguments,
                "guidance" to "Apply selected actionIds with apply_repair_actions_open_plan.",
                "unresolvedReason" to "No safe evidence-backed native correlation action could be planned."
                    .takeIf { unresolved.isNotEmpty() },
                "actions" to sortedActions.map { compactRepairAction(it, includeApplyArguments) },
                "unresolved" to unresolved.take(maxUnresolved),
            ).filterValues { it != null }
        }

    private fun getRepairActionOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val snapshotId = arguments.requiredText("snapshotId")
            val actionId = arguments.requiredText("actionId")
            val snapshot = repairActionSnapshots[snapshotId]
                ?: throw IllegalArgumentException("Unknown or expired repair action snapshot '$snapshotId'")
            snapshot[actionId]
                ?: throw IllegalArgumentException("Unknown repair action '$actionId' in snapshot '$snapshotId'")
        }

    private fun getRepairActionsOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val snapshotId = arguments.requiredText("snapshotId")
            val snapshot = repairActionSnapshots[snapshotId]
                ?: throw IllegalArgumentException("Unknown or expired repair action snapshot '$snapshotId'")
            val actionIds = arguments.path("actionIds")
                .takeIf { it.isArray }
                ?.mapNotNull { it.asText(null)?.takeIf(String::isNotBlank) }
                ?: throw IllegalArgumentException("actionIds must be a non-empty array")
            require(actionIds.isNotEmpty()) {
                "actionIds must be a non-empty array"
            }
            val maxActions = arguments.path("maxActions").asInt(30).coerceAtLeast(1)
            val selectedIds = actionIds.take(maxActions)
            val missing = selectedIds.filterNot(snapshot::containsKey)
            val foundIds = selectedIds.filter(snapshot::containsKey)
            mapOf(
                "snapshotId" to snapshotId,
                "requestedCount" to actionIds.size,
                "returnedCount" to foundIds.size,
                "truncated" to (actionIds.size > selectedIds.size),
                "missingActionIds" to missing,
                "actions" to foundIds.map { actionId -> snapshot.getValue(actionId) },
            )
        }

    private fun applyRepairActionsOpenPlan(arguments: JsonNode): Map<String, Any?> {
        val snapshotId = arguments.requiredText("snapshotId")
        val snapshot = repairActionSnapshots[snapshotId]
            ?: throw IllegalArgumentException("Unknown or expired repair action snapshot '$snapshotId'")
        val actionIds = arguments.path("actionIds")
            .takeIf { it.isArray }
            ?.mapNotNull { it.asText(null)?.takeIf(String::isNotBlank) }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("actionIds must be a non-empty array")
        val stopOnFirstError = arguments.path("stopOnFirstError").asBoolean(false)
        val results = mutableListOf<Map<String, Any?>>()
        val selectedConflictKeys = mutableMapOf<String, String>()
        var applied = 0
        var failed = 0
        var skipped = 0
        var rolledBack = 0
        var restoredFromBackup = false
        for (actionId in actionIds) {
            val action = snapshot[actionId]
            if (action == null) {
                failed++
                results += mapOf("actionId" to actionId, "status" to "missing")
                continue
            }
            val conflictKey = repairActionConflictKey(action)
            val conflictingActionId = conflictKey?.let(selectedConflictKeys::get)
            if (conflictingActionId != null) {
                skipped++
                results += mapOf(
                    "actionId" to actionId,
                    "status" to "skipped_conflict",
                    "conflictsWithActionId" to conflictingActionId,
                )
                continue
            }
            if (conflictKey != null) {
                selectedConflictKeys[conflictKey] = actionId
            }
            val before = captureRepairActionState()
            val beforeChangeCount = AiAutoScriptingLogWindow.changes().size
            val outcome = runCatching {
                val result = applySingleRepairAction(action)
                val after = captureRepairActionState()
                require(after.elementCount >= before.elementCount && after.threadGroupCount >= before.threadGroupCount) {
                    "Repair action '$actionId' damaged the open plan " +
                        "(elements ${before.elementCount} -> ${after.elementCount}, " +
                        "Thread Groups ${before.threadGroupCount} -> ${after.threadGroupCount})"
                }
                result
            }
            outcome.fold(
                onSuccess = { result ->
                    applied++
                    results += mapOf("actionId" to actionId, "status" to "applied", "result" to result)
                },
                onFailure = { error ->
                    failed++
                    val rollback = rollbackFailedRepairAction(before, beforeChangeCount)
                    if (rollback.rolledBack) {
                        rolledBack++
                    }
                    if (rollback.method == "pre-run-backup") {
                        restoredFromBackup = true
                    }
                    results += mapOf(
                        "actionId" to actionId,
                        "status" to "failed",
                        "error" to (error.message ?: error.toString()),
                        "rolledBack" to rollback.rolledBack,
                        "rollbackMethod" to rollback.method,
                    )
                },
            )
            if (stopOnFirstError && outcome.isFailure) {
                break
            }
            if (restoredFromBackup) {
                break
            }
        }
        if (restoredFromBackup) {
            results.indices.forEach { index ->
                if (results[index]["status"] == "applied") {
                    results[index] = results[index] + ("durable" to false)
                }
            }
        }
        postActivity(
            "info",
            "Applied repair action batch",
            details = "applied=$applied failed=$failed skipped=$skipped rolledBack=$rolledBack of ${actionIds.size} requested",
        )
        return mapOf(
            "snapshotId" to snapshotId,
            "requestedCount" to actionIds.size,
            "appliedCount" to applied,
            "failedCount" to failed,
            "skippedCount" to skipped,
            "rolledBackCount" to rolledBack,
            "restoredFromBackup" to restoredFromBackup,
            "durableAppliedCount" to if (restoredFromBackup) 0 else applied,
            "aborted" to restoredFromBackup,
            "results" to results,
        )
    }

    private fun repairActionConflictKey(action: Map<String, Any?>): String? {
        fun keyFrom(arguments: Any?): String? {
            val node = mapper.valueToTree<JsonNode>(arguments ?: return null)
            val literal = node.path("literal").optionalText() ?: return null
            val scope = node.path("scopeNodePath").optionalText()
                ?: node.path("threadGroupName").optionalText()
                ?: node.path("targetNodePath").optionalText()
                ?: "open-plan"
            return "$scope\u0000${canonicalRepairLiteral(literal)}"
        }
        keyFrom(action["applyArguments"])?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val steps = action["steps"] as? List<Map<String, Any?>> ?: return null
        return steps.firstNotNullOfOrNull { step -> keyFrom(step["arguments"]) }
    }

    private fun captureRepairActionState(): RepairActionState =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            RepairActionState(
                signature = openPlanSignature(gui),
                elementCount = countTreeNodes(gui.treeModel.testPlan),
                threadGroupCount = gui.treeModel.getNodesOfType(AbstractThreadGroup::class.java).size,
            )
        }

    private fun rollbackFailedRepairAction(before: RepairActionState, beforeChangeCount: Int): RepairRollback {
        val current = runCatching { captureRepairActionState() }.getOrElse { captureError ->
            val restore = runCatching {
                guiCall { restoreLatestOpenPlanBackup(null, "unreadable plan after failed repair action") }
            }
            if (restore.isSuccess) {
                AiAutoScriptingLogWindow.truncateChanges(0)
                return RepairRollback(true, "pre-run-backup")
            }
            throw IllegalStateException(
                "The failed repair action left the open plan unreadable and backup restore failed. " +
                    "Plan read: ${captureError.message}. Backup restore: ${restore.exceptionOrNull()?.message}",
                captureError,
            )
        }
        if (current.signature == before.signature) {
            AiAutoScriptingLogWindow.truncateChanges(beforeChangeCount)
            return RepairRollback(false, null)
        }
        val undoResult = runCatching {
            guiCall {
                val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
                require(gui.canUndo()) { "The failed repair action changed the plan but no undo entry is available" }
                gui.undo()
                NodeIds.clear()
            }
            val restored = captureRepairActionState()
            require(restored.signature == before.signature) {
                "Undo did not restore the exact pre-action plan state"
            }
        }
        if (undoResult.isSuccess) {
            AiAutoScriptingLogWindow.truncateChanges(beforeChangeCount)
            return RepairRollback(true, "undo")
        }
        val restoreResult = runCatching {
            guiCall { restoreLatestOpenPlanBackup(null, "failed repair action rollback") }
        }
        if (restoreResult.isSuccess) {
            AiAutoScriptingLogWindow.truncateChanges(0)
            return RepairRollback(true, "pre-run-backup")
        }
        val undoError = undoResult.exceptionOrNull()?.message ?: "unknown undo failure"
        val restoreError = restoreResult.exceptionOrNull()?.message ?: "unknown backup restore failure"
        throw IllegalStateException(
            "Failed repair action changed the plan and could not be rolled back. " +
                "Undo: $undoError. Backup restore: $restoreError",
        )
    }

    private fun openPlanSignature(gui: GuiPackage): String {
        val bytes = ByteArrayOutputStream().use { output ->
            SaveService.saveTree(convertTree(gui.treeModel.testPlan), output)
            output.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun countTreeNodes(tree: HashTree): Int =
        tree.list().sumOf { node -> 1 + countTreeNodes(tree.getTree(node)) }

    private fun canonicalRepairLiteral(literal: String): String =
        (if ('%' in literal) runCatching { URLDecoder.decode(literal, Charsets.UTF_8) }.getOrDefault(literal) else literal)
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")

    private fun applySingleRepairAction(action: Map<String, Any?>): Any {
        val applyTool = action["applyTool"] as? String
        val applyArguments = action["applyArguments"]
        if (applyTool != null && applyArguments != null) {
            return handleTool(applyTool.removePrefix("breaktest."), mapper.valueToTree(applyArguments))
        }
        @Suppress("UNCHECKED_CAST")
        val steps = action["steps"] as? List<Map<String, Any?>>
            ?: throw IllegalArgumentException("Repair action '${action["id"]}' has no applyArguments or steps")
        val stepResults = mutableListOf<Any?>()
        for (step in steps) {
            val tool = step["tool"] as? String
                ?: throw IllegalArgumentException("Repair action step is missing 'tool'")
            stepResults += handleTool(tool, mapper.valueToTree(step["arguments"]))
        }
        return mapOf("steps" to stepResults)
    }

    private fun addResponseAssertionsOpenPlan(arguments: JsonNode): Map<String, Any?> {
        val assertionItems = arguments.path("assertions")
            .takeIf { it.isArray && it.size() > 0 }
            ?: throw IllegalArgumentException("assertions must be a non-empty array of add_response_assertion_open_plan argument objects")
        val results = mutableListOf<Map<String, Any?>>()
        var added = 0
        var failed = 0
        for (item in assertionItems) {
            runCatching { addResponseAssertionOpenPlan(item) }.fold(
                onSuccess = { result ->
                    added++
                    results += mapOf("status" to "added", "result" to result)
                },
                onFailure = { error ->
                    failed++
                    results += mapOf(
                        "status" to "failed",
                        "targetNodePath" to item.path("targetNodePath").optionalText(),
                        "targetNodeId" to item.path("targetNodeId").optionalText(),
                        "pattern" to item.path("pattern").optionalText(),
                        "error" to (error.message ?: error.toString()),
                    )
                },
            )
        }
        return mapOf(
            "requestedCount" to assertionItems.size(),
            "addedCount" to added,
            "failedCount" to failed,
            "results" to results,
        )
    }

    private fun getAiKnowledgeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            val createIfMissing = arguments.path("createIfMissing").asBoolean(false)
            val selection = if (createIfMissing) {
                ensureKnowledgeNode(gui)
            } else {
                findKnowledgeSelection(gui)
            }
            if (selection == null) {
                return@guiCall mapOf(
                    "knowledgeNodeCount" to 0,
                    "isDefaultKnowledge" to true,
                    "created" to false,
                    "knowledgeMissing" to true,
                    "knowledge" to defaultKnowledgeJson,
                )
            }
            val node = selection.selected.node
            val element = node.testElement as BreakTestAiKnowledge
            val json = element.knowledgeJson
            mapOf(
                "nodeName" to element.name,
                "nodePath" to selection.selected.path,
                "knowledgeNodeCount" to selection.candidates.size,
                "isDefaultKnowledge" to selection.selected.defaultKnowledge,
                "created" to false,
                "knowledgeMissing" to false,
                "selectedDirectTestPlanChild" to selection.selected.directTestPlanChild,
                "availableKnowledgeNodes" to selection.candidates.map { candidate ->
                    mapOf(
                        "nodeName" to candidate.node.name,
                        "nodePath" to candidate.path,
                        "directTestPlanChild" to candidate.directTestPlanChild,
                        "isDefaultKnowledge" to candidate.defaultKnowledge,
                    )
                },
                "knowledge" to mapper.readTree(json),
            )
        }

    private fun updateAiKnowledgeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            val node = ensureKnowledgeNode(gui).selected.node
            val element = node.testElement as BreakTestAiKnowledge
            val appendLearnings = arguments.path("appendLearnings").takeIfPresent()?.takeIf { it.isObject }
            val appendedCounts = linkedMapOf<String, Int>()
            val knowledgeJson = if (appendLearnings != null) {
                mergeKnowledgeAppend(element.knowledgeJson, appendLearnings, appendedCounts)
            } else {
                knowledgeJsonFrom(arguments)
            }
            mapper.readTree(knowledgeJson)
            val allowDefault = arguments.path("allowDefault").asBoolean(false)
            require(allowDefault || !isDefaultKnowledge(knowledgeJson)) {
                "Refusing to write default/empty AI Knowledge. Append reusable run learnings or a learnedFromThreadGroups noReusableLearnings entry before finishing."
            }
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
                "mode" to if (appendLearnings != null) "append" else "replace",
                "appendedCounts" to appendedCounts.ifEmpty { null },
                "knowledgeBytes" to knowledgeJson.toByteArray(Charsets.UTF_8).size,
            )
        }

    /**
     * Merges appended learnings into the existing knowledge JSON server-side so agents
     * don't have to fetch, rewrite, and resend the whole knowledge document just to
     * add run learnings. Array fields append (skipping exact duplicates); scalar and
     * object fields overwrite.
     */
    private fun mergeKnowledgeAppend(
        existingJson: String,
        appendLearnings: JsonNode,
        appendedCounts: MutableMap<String, Int>,
    ): String {
        val root = runCatching { mapper.readTree(existingJson) }
            .getOrElse { mapper.readTree(BreakTestAiKnowledge.DEFAULT_JSON) }
        require(root.isObject) { "Existing AI Knowledge is not a JSON object" }
        val target = root as com.fasterxml.jackson.databind.node.ObjectNode
        appendLearnings.fields().forEach { (fieldName, appendValue) ->
            if (appendValue.isArray) {
                val existingArray = target.path(fieldName).takeIf { it.isArray }
                    ?.let { it as com.fasterxml.jackson.databind.node.ArrayNode }
                    ?: target.putArray(fieldName)
                var appended = 0
                appendValue.forEach { item ->
                    val duplicate = existingArray.any { it == item }
                    if (!duplicate) {
                        existingArray.add(item)
                        appended++
                    }
                }
                appendedCounts[fieldName] = appended
            } else {
                target.set<JsonNode>(fieldName, appendValue)
                appendedCounts[fieldName] = 1
            }
        }
        return mapper.writeValueAsString(target)
    }

    private fun applyBoundaryCorrelationOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val evidence = requireResponseEvidence(arguments, "add boundary correlation")
            val request = boundaryCorrelationRequest(arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val source = selectSamplerReference(gui, arguments, "source", "source")
                val target = selectSamplerReference(gui, arguments, "target", "target")
                val editor = TestPlanEditor()
                val extractor = createDetachedTestElement("org.apache.jmeter.extractor.gui.BoundaryExtractorGui")
                    .also { editor.configureBoundaryExtractor(it, request) }
                val variableReference = "\${${request.variableName}}"
                val targetElement = target.testElement
                val targetSubTree = currentPlanTree().findSubTree(targetElement)
                    ?: throw IllegalStateException("Could not locate target sampler subtree '${targetElement.name}'")
                val (matchedLiteral, replacements) = replaceWithVariants(request.literal) { variant ->
                    editor.replaceLiteral(targetElement, targetSubTree, variant, variableReference)
                }
                require(replacements > 0) {
                    "Literal '${request.literal}' was not found under target sampler '${targetElement.name}' " +
                        "(also tried encoded/decoded variants)"
                }
                val extractorNode = runCatching { insertDetachedNode(gui, source, extractor) }
                    .getOrElse { error ->
                        editor.replaceLiteral(targetElement, targetSubTree, variableReference, matchedLiteral)
                        throw error
                    }
                markEdited(gui, source, extractorNode)
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
                mapOf(
                    "action" to "created_extractor",
                    "sourceSamplerLabel" to source.testElement.name.orEmpty(),
                    "sourceNodePath" to nodePath(source),
                    "sourceNodeId" to nodeId(source),
                    "extractorNodePath" to nodePath(extractorNode),
                    "extractorNodeId" to nodeId(extractorNode),
                    "targetSamplerLabel" to targetElement.name.orEmpty(),
                    "targetNodePath" to nodePath(target),
                    "variableReference" to variableReference,
                    "replacements" to replacements,
                    "extractorClass" to extractorNode.testElement::class.java.name,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    /**
     * Runs the regex through the same ORO engine the Regex Extractor uses and, when
     * evidence text is supplied, requires it to actually match that evidence. This
     * catches java.util.regex-isms (\\Q...\\E quoting in particular) and wrong
     * patterns at edit time instead of as a mysterious fail-on-missing extractor
     * failure several validation runs later.
     */
    private fun verifyExtractorRegex(regex: String, evidenceText: String?, arguments: JsonNode) {
        AgentRegexSupport.oroProblem(regex)?.let { problem ->
            throw IllegalArgumentException("Regex '$regex' $problem")
        }
        if (arguments.path("allowUnmatchedEvidence").asBoolean(false)) {
            return
        }
        val text = evidenceText?.takeIf { it.isNotBlank() } ?: return
        if (!AgentRegexSupport.oroMatches(regex, text)) {
            throw IllegalArgumentException(
                "Regex '$regex' does not match the provided evidence snippet, so the extractor would fail at runtime. " +
                    "Fix the regex or the evidence (JMeter's ORO/Perl5 engine: no \\Q...\\E, no lookbehind). " +
                    "Pass allowUnmatchedEvidence=true only when the evidence is intentionally partial.",
            )
        }
    }

    private fun applyRegexCorrelationOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val evidence = requireResponseEvidence(arguments, "add regex correlation")
            val request = regexCorrelationRequest(arguments)
            verifyExtractorRegex(request.regex, evidence.details, arguments)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val source = selectSamplerReference(gui, arguments, "source", "source")
                val target = selectOptionalSamplerReference(gui, arguments, "target", "target")
                val editor = TestPlanEditor()
                val allowDuplicateExtractor = arguments.path("allowDuplicateExtractor").asBoolean(false)
                val existingExtractorNodes = regexExtractorNodes(source, request.variableName, requireFound = false)
                val updateExisting = existingExtractorNodes.isNotEmpty() && !allowDuplicateExtractor
                val detachedExtractor = if (updateExisting) {
                    null
                } else {
                    createDetachedTestElement("org.apache.jmeter.extractor.gui.RegexExtractorGui")
                        .also { editor.configureRegexExtractor(it, request) }
                }
                val variableReference = "\${${request.variableName}}"
                val literal = request.literal?.takeIf { it.isNotBlank() }
                val scope = if (literal != null && target == null) selectedBroadEditScope(gui, arguments) else null
                val (matchedLiteral, replacements) = if (literal == null) {
                    null to 0
                } else {
                    replaceWithVariants(literal) { variant ->
                        if (target == null) {
                            editor.replaceLiteralInTree(scopedEditTree(gui, scope), variant, variableReference)
                        } else {
                            val targetElement = target.testElement
                            val targetSubTree = currentPlanTree().findSubTree(targetElement)
                                ?: throw IllegalStateException("Could not locate target sampler subtree '${targetElement.name}'")
                            editor.replaceLiteral(targetElement, targetSubTree, variant, variableReference)
                        }
                    }
                }
                if (literal != null) {
                    require(replacements > 0) {
                        val where = target?.let { "under target sampler '${it.testElement.name}'" }
                            ?: "in ${scope?.let { nodePath(it) } ?: "the open plan"}"
                        "Literal '$literal' was not found $where (also tried encoded/decoded variants)"
                    }
                }
                val extractorNodes = runCatching {
                    if (updateExisting) {
                        existingExtractorNodes.onEach { editor.configureRegexExtractor(it.testElement, request) }
                    } else {
                        listOf(insertDetachedNode(gui, source, requireNotNull(detachedExtractor)))
                    }
                }.getOrElse { error ->
                    if (literal != null && matchedLiteral != null) {
                        if (target == null) {
                            editor.replaceLiteralInTree(scopedEditTree(gui, scope), variableReference, matchedLiteral)
                        } else {
                            val targetElement = target.testElement
                            val targetSubTree = currentPlanTree().findSubTree(targetElement)
                                ?: throw error
                            editor.replaceLiteral(targetElement, targetSubTree, variableReference, matchedLiteral)
                        }
                    }
                    throw error
                }
                val extractorNode = extractorNodes.last()
                val changedNode = target ?: scope ?: testPlanNode(gui)
                markEdited(gui, changedNode, extractorNode)
                postActivity(
                    "info",
                    if (existingExtractorNodes.isNotEmpty() && !allowDuplicateExtractor) {
                        "Updated live regex correlation"
                    } else {
                        "Applied live regex correlation"
                    },
                    details = "${request.variableName}: ${source.testElement.name} -> ${target?.testElement?.name ?: scope?.let { nodePath(it) } ?: "open plan"}",
                )
                extractorNodes.forEach { node ->
                    recordChange(
                        if (existingExtractorNodes.isNotEmpty() && !allowDuplicateExtractor) "Updated extractor" else "Added extractor",
                        node,
                        "Regex extractor `${request.variableName}`",
                        "${source.testElement.name} -> ${target?.testElement?.name ?: scope?.let { nodePath(it) } ?: "open plan"}; " +
                            "useField=${request.useField}; failOnNoMatch=${request.failOnNoMatch}; evidence=${evidence.summary}",
                    )
                }
                if (literal != null) {
                    recordChange(
                        if (target == null) "Updated plan" else "Updated sampler",
                        changedNode,
                        "Replaced literal with $variableReference",
                        "Replacements: $replacements",
                    )
                }
                val siblingExtractors = regexExtractorNodes(source, variableName = null, requireFound = false)
                mapOf(
                    "action" to if (existingExtractorNodes.isNotEmpty() && !allowDuplicateExtractor) {
                        "updated_existing_extractor"
                    } else {
                        "created_extractor"
                    },
                    "sourceSamplerLabel" to source.testElement.name.orEmpty(),
                    "sourceNodePath" to nodePath(source),
                    "sourceNodeId" to nodeId(source),
                    "extractorNodePath" to nodePath(extractorNode),
                    "extractorNodeId" to nodeId(extractorNode),
                    "targetSamplerLabel" to if (literal == null) {
                        "(extractor only)"
                    } else {
                        target?.testElement?.name ?: scope?.let { nodePath(it) } ?: "open plan"
                    },
                    "targetNodePath" to target?.let { nodePath(it) },
                    "scopeNodePath" to scope?.let { nodePath(it) },
                    "variableReference" to variableReference,
                    "replacements" to replacements,
                    "matchedLiteral" to matchedLiteral,
                    "matchedEncodingVariant" to (matchedLiteral != null && matchedLiteral != literal),
                    "extractorClass" to extractorNode.testElement::class.java.name,
                    "sourceExtractors" to siblingExtractors.map { node ->
                        mapOf(
                            "variableName" to extractorVariableName(node.testElement),
                            "nodePath" to nodePath(node),
                            "nodeId" to nodeId(node),
                        )
                    },
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun updateRegexExtractorOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val request = regexExtractorUpdateRequest(arguments)
            request.regex?.let { regex ->
                verifyExtractorRegex(regex, arguments.path("evidence").optionalText(), arguments)
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val updateAllMatches = arguments.path("updateAllMatches").asBoolean(false)
                val extractorMatchIndex = arguments.path("extractorMatchIndex").takeIfPresent()?.asInt()
                // An extractor's own nodeId (from find_open_plan_nodes or edit results)
                // identifies it directly; the source sampler is derived from it.
                val extractorNodeIdArgument = arguments.path("extractorNodeId").optionalText()
                    ?: arguments.path("nodeId").optionalText()
                val extractorByNodeId = extractorNodeIdArgument?.let { id ->
                    val node = selectNodeById(gui, id, "extractor")
                    require(node.testElement::class.java.name == "org.apache.jmeter.extractor.RegexExtractor") {
                        "extractor nodeId '$id' is not a Regex Extractor; it is ${node.testElement::class.java.name}"
                    }
                    node
                }
                val source = extractorByNodeId?.let { it.ancestorSampler() ?: it.parent as? JMeterTreeNode ?: it }
                    ?: selectSamplerReference(gui, arguments, "source", "source")
                val extractorNodes = extractorByNodeId?.let { listOf(it) }
                    ?: regexExtractorNodes(source, request.variableName)
                val selectedExtractors = when {
                    updateAllMatches -> extractorNodes
                    extractorMatchIndex != null -> listOf(
                        extractorNodes.getOrNull(extractorMatchIndex)
                            ?: throw IllegalArgumentException(
                                "No regex extractor match index $extractorMatchIndex for '${request.variableName}' " +
                                    "under '${source.testElement.name}'; found ${extractorNodes.size}",
                            ),
                    )
                    extractorNodes.size == 1 -> extractorNodes
                    else -> throw IllegalArgumentException(
                        "Expected one regex extractor named '${request.variableName}' under '${source.testElement.name}', " +
                            "found ${extractorNodes.size}. Use extractorMatchIndex or updateAllMatches=true.",
                    )
                }
                selectedExtractors.forEach { extractorNode ->
                    TestPlanEditor().updateRegexExtractor(extractorNode.testElement, request)
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
                }
                markEdited(gui, source, selectedExtractors.last())
                postActivity(
                    "info",
                    "Updated live regex extractor",
                    details = "${request.variableName}: ${source.testElement.name}; updated=${selectedExtractors.size}",
                )
                mapOf(
                    "sourceSamplerLabel" to source.testElement.name,
                    "sourceNodePath" to nodePath(source),
                    "sourceNodeId" to nodeId(source),
                    "variableName" to request.variableName,
                    "updatedCount" to selectedExtractors.size,
                    "extractors" to selectedExtractors.mapIndexed { index, extractorNode ->
                        mapOf(
                            "matchIndex" to index,
                            "extractorName" to extractorNode.testElement.name,
                            "extractorNodePath" to nodePath(extractorNode),
                            "extractorNodeId" to nodeId(extractorNode),
                        )
                    },
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun replaceLiteralOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val request = literalReplacementRequest(arguments)
            validateJMeterFunctionSyntax(request.replacement)
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectedReplacementTarget(gui, request)
                val scope = selectedReplacementScope(gui, request, target)
                val editor = TestPlanEditor()
                val (matchedLiteral, replacements) = replaceWithVariants(request.literal) { variant ->
                    if (target == null) {
                        editor.replaceLiteralInTree(
                            scopedEditTree(gui, scope),
                            variant,
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
                            variant,
                            request.replacement,
                            request.includeNames,
                            request.excludeUserDefinedVariables,
                        )
                    }
                }
                require(replacements > 0) {
                    val where = target?.let { "under target sampler '${it.testElement.name}'" }
                        ?: "in ${scope?.let { nodePath(it) } ?: "the open plan"}"
                    "Literal '${request.literal}' was not found $where " +
                        "(also tried encoded/decoded variants: ${literalVariants(request.literal).joinToString(", ") { "'$it'" }})"
                }
                val changedNode = target ?: scope ?: testPlanNode(gui)
                markEdited(gui, changedNode)
                postActivity(
                    "info",
                    "Applied live literal replacement",
                    details = "${target?.testElement?.name ?: scope?.let { nodePath(it) } ?: "open plan"}: $matchedLiteral -> ${request.replacement}",
                )
                recordChange(
                    if (target == null) "Updated plan" else "Updated sampler",
                    changedNode,
                    "Replaced literal with ${request.replacement}",
                    "Replacements: $replacements",
                )
                mapOf(
                    "targetSamplerLabel" to (target?.testElement?.name ?: scope?.let { nodePath(it) } ?: "open plan"),
                    "targetNodePath" to target?.let { nodePath(it) },
                    "targetNodeId" to target?.let { nodeId(it) },
                    "scopeNodePath" to scope?.let { nodePath(it) },
                    "replacements" to replacements,
                    "matchedLiteral" to matchedLiteral,
                    "matchedEncodingVariant" to (matchedLiteral != request.literal),
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun replaceLiteralInNamesOpenPlan(arguments: JsonNode): Map<String, Any?> =
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
                val scope = selectedReplacementScope(gui, request, target)
                val editTree = if (target == null) scopedEditTree(gui, scope) else scopedEditTree(gui, target)
                val (matchedLiteral, replacements) = replaceWithVariants(request.literal) { variant ->
                    TestPlanEditor().replaceLiteralInNamesInTree(
                        editTree,
                        variant,
                        request.replacement,
                    )
                }
                require(replacements > 0) {
                    val where = target?.let { "under target sampler '${it.testElement.name}'" }
                        ?: "under ${scope?.let { nodePath(it) } ?: "the open plan"}"
                    "Literal '${request.literal}' was not found in names $where " +
                        "(also tried encoded/decoded variants)"
                }
                val changedNode = target ?: scope ?: testPlanNode(gui)
                markEdited(gui, changedNode)
                postActivity(
                    "info",
                    "Applied live name replacement",
                    details = "${target?.testElement?.name ?: scope?.let { nodePath(it) } ?: "open plan"}: $matchedLiteral -> ${request.replacement}",
                )
                recordChange(
                    if (target == null) "Renamed elements" else "Renamed sampler elements",
                    changedNode,
                    "Replaced name literal with ${request.replacement}",
                    "Name replacements: $replacements",
                )
                mapOf(
                    "targetSamplerLabel" to (target?.testElement?.name ?: scope?.let { nodePath(it) } ?: "open plan"),
                    "targetNodePath" to target?.let { nodePath(it) },
                    "targetNodeId" to target?.let { nodeId(it) },
                    "scopeNodePath" to scope?.let { nodePath(it) },
                    "replacements" to replacements,
                    "matchedLiteral" to matchedLiteral,
                    "matchedEncodingVariant" to (matchedLiteral != request.literal),
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    /**
     * Request data captured during validation is URL/HTML-encoded (user@x.nl appears
     * as user%40x.nl) while the plan stores raw field values, so a literal taken from
     * validation evidence often fails an exact match. These variants let replace and
     * search operations fall back to the decoded/encoded forms of the same value.
     */
    private fun literalVariants(literal: String): List<String> {
        val variants = LinkedHashSet<String>()
        variants += literal
        if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(literal)) {
            runCatching { URLDecoder.decode(literal, Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { variants += it }
        }
        runCatching { URLEncoder.encode(literal, Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it != literal }
            ?.let { variants += it }
        val htmlDecoded = literal
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
        variants += htmlDecoded
        return variants.toList()
    }

    private fun replaceWithVariants(literal: String, replace: (String) -> Int): Pair<String, Int> {
        for (variant in literalVariants(literal)) {
            val replacements = replace(variant)
            if (replacements > 0) {
                return variant to replacements
            }
        }
        return literal to 0
    }

    private fun selectedReplacementTarget(gui: GuiPackage, request: LiteralReplacementRequest): JMeterTreeNode? {
        if (!request.targetNodeId.isNullOrBlank()) {
            return selectNodeById(gui, request.targetNodeId, "target")
        }
        val scope = when {
            !request.scopeNodePath.isNullOrBlank() ->
                selectNodeByPath(gui.treeModel.testPlan, request.scopeNodePath, "scope")
            !request.threadGroupName.isNullOrBlank() ->
                runCatching { selectThreadGroup(gui, request.threadGroupName) }.getOrNull()
            else -> null
        }
        if (!request.targetNodePath.isNullOrBlank()) {
            val node = selectNodeByPath(
                gui.treeModel.testPlan,
                request.targetNodePath,
                "target",
                request.targetOccurrenceIndex,
                scope = scope,
                nodeFilter = { it.testElement is Sampler },
            )
            require(node.testElement is Sampler) {
                "target node '${request.targetNodePath}' is not a sampler; it is ${node.testElement::class.java.name}"
            }
            return node
        }
        if (request.targetSamplerIndex == null && request.targetSamplerLabel.isNullOrBlank()) {
            return null
        }
        val samplers = samplerNodes(gui.treeModel.testPlan)
        val labelCandidates = if (scope != null && request.targetSamplerIndex == null) {
            samplers.filter { it === scope || it.isDescendantOf(scope) }
        } else {
            samplers
        }
        return selectSampler(
            labelCandidates,
            request.targetSamplerIndex,
            request.targetSamplerLabel,
            "target",
            request.targetOccurrenceIndex,
        )
    }

    private fun selectedReplacementScope(
        gui: GuiPackage,
        request: LiteralReplacementRequest,
        target: JMeterTreeNode?,
    ): JMeterTreeNode? {
        if (target != null) {
            return null
        }
        return selectedBroadEditScope(
            gui,
            request.scopeNodePath,
            request.threadGroupName,
            request.allowWholePlan,
        )
    }

    private fun selectedBroadEditScope(gui: GuiPackage, arguments: JsonNode): JMeterTreeNode? =
        selectedBroadEditScope(
            gui,
            arguments.path("scopeNodePath").optionalText(),
            arguments.path("threadGroupName").optionalText(),
            arguments.path("allowWholePlan").asBoolean(false),
        )

    private fun selectedBroadEditScope(
        gui: GuiPackage,
        scopeNodePath: String?,
        threadGroupName: String?,
        allowWholePlan: Boolean,
    ): JMeterTreeNode? {
        if (!scopeNodePath.isNullOrBlank()) {
            return selectNodeByPath(gui.treeModel.testPlan, scopeNodePath, "scope")
        }
        if (!threadGroupName.isNullOrBlank()) {
            return selectThreadGroup(gui, threadGroupName)
        }
        if (!allowWholePlan && enabledThreadGroupCount(gui) > 1) {
            throw IllegalArgumentException(
                "Refusing whole-plan literal replacement because multiple enabled Thread Groups exist. " +
                    "Pass threadGroupName, scopeNodePath, targetNodePath, target sampler, or allowWholePlan=true.",
            )
        }
        return null
    }

    private fun scopedEditTree(gui: GuiPackage, scope: JMeterTreeNode?): HashTree {
        val planTree = currentPlanTree()
        if (scope == null) {
            return planTree
        }
        val scopeSubTree = planTree.findSubTree(scope.testElement)
            ?: throw IllegalStateException("Could not locate scope subtree '${scope.testElement.name}'")
        return ListedHashTree().apply {
            add(scope.testElement, scopeSubTree)
        }
    }

    private fun selectedSearchScope(gui: GuiPackage, arguments: JsonNode): JMeterTreeNode? {
        val scopeNodePath = arguments.path("scopeNodePath").optionalText()
        if (scopeNodePath != null) {
            return selectNodeByPath(gui.treeModel.testPlan, scopeNodePath, "scope")
        }
        val threadGroupName = arguments.path("threadGroupName").optionalText()
        if (threadGroupName != null) {
            return selectThreadGroup(gui, threadGroupName)
        }
        return null
    }

    private fun searchScopeTree(gui: GuiPackage, scope: JMeterTreeNode?): HashTree {
        if (scope == null) {
            return gui.treeModel.testPlan
        }
        val planTree = currentPlanTree()
        val scopeSubTree = planTree.findSubTree(scope.testElement)
            ?: throw IllegalStateException("Could not locate search scope subtree '${nodePath(scope)}'")
        return ListedHashTree().apply {
            add(scope.testElement, scopeSubTree)
        }
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
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val target = selectSamplerReference(gui, arguments, "target", "target")
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
                "targetNodePath" to nodePath(target),
                "argumentCount" to argumentRows.size,
                "arguments" to argumentRows,
            )
        }

    private fun setHttpArgumentEncodeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val argumentName = arguments.path("argumentName").optionalText()
            val argumentValue = arguments.path("argumentValue").optionalText()
            val argumentIndex = arguments.path("argumentIndex").takeIfPresent()?.asInt()
            val alwaysEncode = arguments.path("alwaysEncode").takeIfPresent()?.asBoolean()
                ?: throw IllegalArgumentException("Missing required argument 'alwaysEncode'")
            if (argumentIndex == null && argumentName.isNullOrBlank() && argumentValue.isNullOrBlank()) {
                throw IllegalArgumentException("Specify argumentIndex, argumentName, or argumentValue")
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectSamplerReference(gui, arguments, "target", "target")
                val changed = setHttpArgumentEncode(target.testElement, argumentIndex, argumentName, argumentValue, alwaysEncode)
                require(changed > 0) {
                    "No HTTP argument matched index=$argumentIndex name='$argumentName' value='$argumentValue' under '${target.testElement.name}'"
                }
                markEdited(gui, target)
                postActivity(
                    "info",
                    "Updated HTTP argument encoding",
                    details = "${target.testElement.name}: index=$argumentIndex name=$argumentName value=$argumentValue alwaysEncode=$alwaysEncode",
                )
                recordChange(
                    "Updated sampler",
                    target,
                    "Set HTTP argument alwaysEncode=$alwaysEncode",
                    "index=$argumentIndex name=$argumentName value=$argumentValue changed=$changed",
                )
                mapOf(
                    "targetSamplerLabel" to target.testElement.name,
                    "targetNodePath" to nodePath(target),
                    "changedArguments" to changed,
                    "alwaysEncode" to alwaysEncode,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun setHttpArgumentValueOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val argumentName = arguments.path("argumentName").optionalText()
            val argumentValue = arguments.path("argumentValue").optionalText()
            val argumentIndex = arguments.path("argumentIndex").takeIfPresent()?.asInt()
            val newValue = arguments.requiredText("newValue")
            validateJMeterFunctionSyntax(newValue)
            val alwaysEncode = arguments.path("alwaysEncode").takeIfPresent()?.asBoolean()
            if (argumentIndex == null && argumentName.isNullOrBlank() && argumentValue.isNullOrBlank()) {
                throw IllegalArgumentException("Specify argumentIndex, argumentName, or argumentValue")
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val target = selectSamplerReference(gui, arguments, "target", "target")
                val changed = setHttpArgumentValue(target.testElement, argumentIndex, argumentName, argumentValue, newValue, alwaysEncode)
                require(changed > 0) {
                    "No HTTP argument matched index=$argumentIndex name='$argumentName' value='$argumentValue' under '${target.testElement.name}'"
                }
                markEdited(gui, target)
                postActivity(
                    "info",
                    "Updated HTTP argument value",
                    details = "${target.testElement.name}: index=$argumentIndex name=$argumentName value=$argumentValue -> $newValue",
                )
                recordChange(
                    "Updated sampler",
                    target,
                    "Set HTTP argument value",
                    "index=$argumentIndex name=$argumentName value=$argumentValue changed=$changed",
                )
                mapOf(
                    "targetSamplerLabel" to target.testElement.name,
                    "targetNodePath" to nodePath(target),
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
                ?: arguments.path("query").takeIfPresent()?.asText()
            val regex = arguments.path("regex").takeIfPresent()?.asText()
            val includeNames = arguments.path("includeNames").asBoolean(false)
            val excludeUserDefinedVariables = arguments.path("excludeUserDefinedVariables").asBoolean(false)
            val maxMatches = arguments.path("maxMatches").takeIfPresent()?.asInt()?.coerceAtLeast(1) ?: 100
            require(!literal.isNullOrBlank() || !regex.isNullOrBlank()) {
                "Specify literal or regex"
            }
            val pattern = regex?.takeIf { it.isNotBlank() }?.toRegex()
            val literals = literal?.takeIf { it.isNotBlank() }?.let(::literalVariants)
            val matches = mutableListOf<Map<String, Any?>>()
            val visitedElements = java.util.IdentityHashMap<TestElement, Boolean>()
            val visitedProperties = java.util.IdentityHashMap<JMeterProperty, Boolean>()
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            val scope = selectedSearchScope(gui, arguments)
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
                            literals,
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
            visitNode(searchScopeTree(gui, scope), emptyList())
            mapOf(
                "matchCount" to matches.size,
                "truncated" to (matches.size >= maxMatches),
                "scopeNodePath" to scope?.let { nodePath(it) },
                "literalVariantsSearched" to literals,
                "matches" to matches,
            )
        }

    /**
     * Serves evidence snippets from the cached most-recent validation run so agents
     * can find assertion markers and correlation sources without re-running
     * validation or receiving another full validation payload.
     */
    private fun searchValidatedResponseOpenPlan(arguments: JsonNode): Map<String, Any?> {
        val last = lastValidation
            ?: throw IllegalStateException("No validation result is cached yet; run validate_open_plan first")
        val query = arguments.path("query").optionalText()
            ?: arguments.path("literal").optionalText()
        val regex = arguments.path("regex").optionalText()?.toRegex()
        val samplerLabel = arguments.path("samplerLabel").optionalText()
        require(query != null || regex != null || samplerLabel != null) {
            "Specify query, regex, and/or samplerLabel"
        }
        val includeRequests = arguments.path("includeRequests").asBoolean(false)
        val contextChars = arguments.path("contextChars").takeIfPresent()?.asInt()?.coerceIn(20, 2_000) ?: 160
        val maxMatches = arguments.path("maxMatches").takeIfPresent()?.asInt()?.coerceAtLeast(1) ?: 20
        val queryVariants = query?.let(::literalVariants)
        val matches = mutableListOf<Map<String, Any?>>()
        outer@ for (sample in last.samples.flatMap { it.flatten() }) {
            if (samplerLabel != null && sample.label != samplerLabel) {
                continue
            }
            val surfaces = buildList {
                add("responseHeaders" to sample.responseHeaders)
                add("responseBody" to sample.responseBody)
                if (includeRequests) {
                    add("requestHeaders" to sample.requestHeaders)
                    add("requestBody" to sample.requestBody)
                }
            }
            for ((surface, text) in surfaces) {
                if (text.isEmpty()) {
                    continue
                }
                val range = when {
                    queryVariants != null -> queryVariants.firstNotNullOfOrNull { variant ->
                        text.indexOf(variant).takeIf { it >= 0 }?.let { it until it + variant.length }
                    }
                    regex != null -> regex.find(text)?.range?.let { it.first until it.last + 1 }
                    // samplerLabel-only lookup: return the head of each surface
                    else -> 0 until minOf(text.length, contextChars * 2)
                } ?: continue
                matches += mapOf(
                    "sampleIndex" to sample.index,
                    "label" to sample.label,
                    "success" to sample.success,
                    "responseCode" to sample.responseCode,
                    "surface" to surface,
                    "snippet" to text.contextAround(range.first, range.last + 1, contextChars),
                )
                if (matches.size >= maxMatches) {
                    break@outer
                }
            }
        }
        return mapOf(
            "validatedAtMillis" to last.atMillis,
            "scopeNodePath" to last.scopeNodePath,
            "matchCount" to matches.size,
            "truncated" to (matches.size >= maxMatches),
            "matches" to matches,
        )
    }

    private fun findOpenPlanNodes(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            val name = arguments.path("name").optionalText()
            val nameContains = arguments.path("nameContains").optionalText()
            val className = arguments.path("className").optionalText()
            val classNameContains = arguments.path("classNameContains").optionalText()
            val type = arguments.path("type").optionalText()?.lowercase()
            val variableName = arguments.path("variableName").optionalText()
            val underNodePath = arguments.path("underNodePath").optionalText()
            val maxMatches = arguments.path("maxMatches").takeIfPresent()?.asInt()?.coerceAtLeast(1) ?: 80
            // An ambiguous under-path is fine for a read-only search: scan under all
            // matching subtrees instead of failing like the edit tools must.
            val underNodes = underNodePath?.let { matchingNodesByPath(gui.treeModel.testPlan, it) }
            require(underNodePath == null || !underNodes.isNullOrEmpty()) {
                "No node matched underNodePath '$underNodePath'"
            }
            val allNodes = allTreeNodes(gui.treeModel.testPlan)
            val matches = allNodes
                .asSequence()
                .filter { node ->
                    underNodes == null || underNodes.any { under -> node === under || node.isDescendantOf(under) }
                }
                .filter { node -> name == null || node.testElement.name == name }
                .filter { node -> nameContains == null || node.testElement.name.orEmpty().contains(nameContains, ignoreCase = true) }
                .filter { node -> className == null || node.testElement::class.java.name == className }
                .filter { node -> classNameContains == null || node.testElement::class.java.name.contains(classNameContains, ignoreCase = true) }
                .filter { node -> type == null || node.matchesNodeType(type) }
                .filter { node -> variableName == null || extractorVariableName(node.testElement) == variableName }
                .toList()
            val limited = matches.take(maxMatches)
            mapOf(
                "matchCount" to matches.size,
                "truncated" to (matches.size > limited.size),
                "matches" to limited.mapIndexed { index, node -> nodeInfo(node, index, allNodes) },
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
                "hex-id",
                "path-opaque-id",
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
                "guidance" to "Correlate, parameterize, generate, or explicitly document every high-confidence candidate; a green run does not clear them. Random-looking path segments (hex-id/path-opaque-id) are server-issued until response evidence says otherwise.",
                "candidates" to candidates,
            )
        }

    private fun addJsr223OpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val parentType = arguments.path("parentType").takeIfPresent()?.asText()?.lowercase() ?: "sampler"
            val threadGroupName = arguments.path("threadGroupName").takeIfPresent()?.asText()
            val elementType = arguments.path("elementType").takeIfPresent()?.asText()?.lowercase() ?: "preprocessor"
            val name = arguments.path("name").takeIfPresent()?.asText()?.takeIf { it.isNotBlank() }
                ?: "AI JSR223 ${elementType.replaceFirstChar { it.uppercase() }}"
            val script = arguments.requiredText("script")
            val language = arguments.path("language").takeIfPresent()?.asText()?.takeIf { it.isNotBlank() } ?: "groovy"
            val parameters = arguments.path("parameters").takeIfPresent()?.asText() ?: ""
            // Compile caching is on unless explicitly disabled: an uncached Groovy
            // script recompiles on every execution, which is disastrous under load.
            val cacheKey = arguments.path("cacheKey").takeIfPresent()?.asText()?.takeIf { it.isNotBlank() } ?: "true"
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
                    else -> selectSamplerReference(gui, arguments, "target", "target")
                }
                val node = addConfiguredComponent(gui, parent, createJsr223Element(elementType)) { element ->
                    element.name = name
                    val jsr223 = element as? JSR223TestElement
                        ?: throw IllegalStateException("${element::class.java.name} is not a JSR223 element")
                    jsr223.scriptLanguage = language
                    jsr223.script = script
                    jsr223.parameters = parameters
                    jsr223.filename = ""
                    jsr223.setProperty(StringProperty("scriptLanguage", language))
                    jsr223.setProperty(StringProperty("script", script))
                    jsr223.setProperty(StringProperty("parameters", parameters))
                    jsr223.setProperty(StringProperty("filename", ""))
                    setOptionalMethod(jsr223, "setCacheKey", cacheKey)
                    jsr223.setProperty(StringProperty("cacheKey", cacheKey))
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
                val uuidOnlyScript = script.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("//") }
                    .all { it.contains("vars.put") && it.contains("randomUUID") }
                mapOf(
                    "parentName" to parent.testElement.name,
                    "parentNodePath" to nodePath(parent),
                    "nodeName" to node.testElement.name,
                    "nodePath" to nodePath(node),
                    "nodeId" to nodeId(node),
                    "elementType" to elementType,
                    "language" to language,
                    "scriptLength" to script.length,
                    "compileCacheEnabled" to (cacheKey == "true"),
                    "hint" to if (uuidOnlyScript) {
                        "This script only generates UUID variables. If each variable is used in exactly one request " +
                            "field, replace that field with inline \${__UUID} and delete this element; keep a JSR223 " +
                            "setup only when one generated value must be identical across several fields or requests."
                    } else {
                        null
                    },
                ).filterValues { it != null }
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun createJsr223Element(elementType: String): JSR223TestElement {
        val className = when (elementType) {
            "sampler" -> "org.apache.jmeter.protocol.java.sampler.JSR223Sampler"
            "postprocessor", "post_processor", "post" -> "org.apache.jmeter.extractor.JSR223PostProcessor"
            "preprocessor", "pre_processor", "pre" -> "org.apache.jmeter.modifiers.JSR223PreProcessor"
            "assertion" -> "org.apache.jmeter.assertions.JSR223Assertion"
            "timer" -> "org.apache.jmeter.timers.JSR223Timer"
            else -> throw IllegalArgumentException("Unsupported JSR223 elementType '$elementType'")
        }
        return try {
            val element = Class.forName(className).getDeclaredConstructor().newInstance()
            val jsr223 = element as? JSR223TestElement
                ?: throw IllegalStateException("$className is not a JSR223 element")
            jsr223.setProperty(TestElement.TEST_CLASS, className)
            jsr223.setProperty(TestElement.GUI_CLASS, TestBeanGUI::class.java.name)
            jsr223
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Could not create JSR223 element $className", e)
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "Could not load JSR223 element $className. The BreakTest runtime classpath looks stale or corrupt; restart BreakTest after rebuilding so jars are not replaced while the GUI is running.",
                e,
            )
        }
    }

    /**
     * Rejects assertion patterns that do not occur in the target sampler's most
     * recently validated response. This catches misplaced assertions at add time
     * (pattern taken from one sampler, assertion attached to another) instead of
     * letting a later validation fail and sending the agent into a repair loop.
     */
    private fun verifyAssertionPatternAgainstLastValidation(
        target: JMeterTreeNode,
        pattern: String,
        field: String,
        matchType: String,
        arguments: JsonNode,
    ) {
        if (arguments.path("allowUnverifiedPattern").asBoolean(false)) {
            return
        }
        if (matchType !in setOf("substring", "contains")) {
            return
        }
        val evidenceSource = arguments.path("evidenceSource").optionalText()
        if (evidenceSource != null && evidenceSource != "validated_response") {
            return
        }
        val last = lastValidation ?: return
        val samples = last.samples.flatMap { it.flatten() }
        val targetLabel = target.testElement.name.orEmpty()
        val targetSamples = samples.filter { it.label == targetLabel }
        if (targetSamples.isEmpty()) {
            return
        }
        fun surfaceText(sample: AgentSampleSummary): String =
            if (field == "headers") sample.responseHeaders else sample.responseBody
        if (targetSamples.any { surfaceText(it).contains(pattern) }) {
            return
        }
        val containing = samples
            .filter { surfaceText(it).contains(pattern) }
            .map { it.label }
            .distinct()
            .take(5)
        val hint = if (containing.isEmpty()) {
            "It was not found in any validated response; pick a marker from search_validated_response_open_plan."
        } else {
            "It occurs in the validated response of: ${containing.joinToString(", ") { "'$it'" }}. " +
                "Either target that sampler or pick a marker from '$targetLabel' via search_validated_response_open_plan."
        }
        throw IllegalArgumentException(
            "Assertion pattern '$pattern' does not occur in the latest validated $field of sampler '$targetLabel'. " +
                hint + " Pass allowUnverifiedPattern=true only for deliberate unvalidated assertions.",
        )
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
                val target = selectSamplerReference(gui, arguments, "target", "target")
                verifyAssertionPatternAgainstLastValidation(target, request.pattern, request.field, request.matchType, arguments)
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
                    "targetNodePath" to nodePath(target),
                    "targetNodeId" to nodeId(target),
                    "assertionName" to assertionNode.testElement.name,
                    "assertionNodePath" to nodePath(assertionNode),
                    "assertionNodeId" to nodeId(assertionNode),
                    "pattern" to request.pattern,
                    "targetAssertions" to assertionPlacementSummary(target),
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
                val target = selectSamplerReference(gui, arguments, "target", "target")
                verifyAssertionPatternAgainstLastValidation(
                    target,
                    pattern,
                    arguments.path("field").takeIfPresent()?.asText() ?: "body",
                    arguments.path("matchType").takeIfPresent()?.asText() ?: "substring",
                    arguments,
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
                    "targetNodePath" to nodePath(target),
                    "targetNodeId" to nodeId(target),
                    "assertionName" to assertionNode.testElement.name,
                    "assertionNodePath" to nodePath(assertionNode),
                    "assertionNodeId" to nodeId(assertionNode),
                    "pattern" to request.pattern,
                    "targetAssertions" to assertionPlacementSummary(target),
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
                val target = selectSamplerReference(gui, arguments, "target", "target")
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
                    "targetNodePath" to nodePath(target),
                    "followRedirects" to request.followRedirects,
                    "autoRedirects" to request.autoRedirects,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun moveNodeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val position = arguments.path("position").asText("before").lowercase()
            require(position in setOf("before", "after", "first_child", "last_child")) {
                "position must be one of before, after, first_child, or last_child"
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val source = selectNodeReference(gui, arguments, "source", "source")
                val target = selectNodeReference(gui, arguments, "target", "target")
                require(source !== target) {
                    "Cannot move a node relative to itself"
                }

                val oldParent = source.parent as? JMeterTreeNode
                    ?: throw IllegalArgumentException("Source node has no movable parent")
                val oldIndex = oldParent.getIndex(source)
                val newParent = when (position) {
                    "before", "after" ->
                        target.parent as? JMeterTreeNode
                            ?: throw IllegalArgumentException("Target node has no parent")
                    "first_child", "last_child" -> target
                    else -> error("Unsupported position $position")
                }
                require(!isAncestorOf(source, newParent)) {
                    "Cannot move `${source.testElement.name}` under one of its descendants"
                }

                gui.treeModel.removeNodeFromParent(source)
                val insertIndex = when (position) {
                    "before" -> newParent.getIndex(target).coerceAtLeast(0)
                    "after" -> (newParent.getIndex(target) + 1).coerceAtMost(newParent.childCount)
                    "first_child" -> 0
                    "last_child" -> newParent.childCount
                    else -> error("Unsupported position $position")
                }
                gui.treeModel.insertNodeInto(source, newParent, insertIndex)
                gui.treeModel.nodeStructureChanged(oldParent)
                gui.treeModel.nodeStructureChanged(newParent)
                markEdited(gui, newParent, source)
                recordChange(
                    "Moved node",
                    source,
                    "Moved `${source.testElement.name}` $position `${target.testElement.name}`",
                    "from ${nodePath(oldParent)}[$oldIndex] to ${nodePath(newParent)}[$insertIndex]",
                )
                postActivity(
                    "info",
                    "Moved node",
                    details = "${source.testElement.name} $position ${target.testElement.name}",
                )
                mapOf(
                    "moved" to true,
                    "sourceName" to source.testElement.name,
                    "sourcePath" to nodePath(source),
                    "sourceNodeId" to nodeId(source),
                    "targetName" to target.testElement.name,
                    "targetPath" to nodePath(target),
                    "position" to position,
                    "oldParentPath" to nodePath(oldParent),
                    "oldIndex" to oldIndex,
                    "newParentPath" to nodePath(newParent),
                    "newIndex" to insertIndex,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun cloneNodeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val position = arguments.path("position").asText("after").lowercase()
            require(position in setOf("before", "after", "first_child", "last_child")) {
                "position must be one of before, after, first_child, or last_child"
            }
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            gui.beginUndoTransaction()
            try {
                val source = selectNodeReference(gui, arguments, "source", "source")
                val target = selectNodeReference(gui, arguments, "target", "target")
                require(source !== testPlanNode(gui)) {
                    "Cannot clone the open Test Plan root"
                }

                val newParent = when (position) {
                    "before", "after" ->
                        target.parent as? JMeterTreeNode
                            ?: throw IllegalArgumentException("Target node has no parent")
                    "first_child", "last_child" -> target
                    else -> error("Unsupported position $position")
                }
                require(!isAncestorOf(source, newParent)) {
                    "Cannot clone `${source.testElement.name}` under one of its descendants"
                }

                val cloned = Copy.cloneTreeNode(source)
                val insertIndex = when (position) {
                    "before" -> newParent.getIndex(target).coerceAtLeast(0)
                    "after" -> (newParent.getIndex(target) + 1).coerceAtMost(newParent.childCount)
                    "first_child" -> 0
                    "last_child" -> newParent.childCount
                    else -> error("Unsupported position $position")
                }
                gui.treeModel.insertNodeInto(cloned, newParent, insertIndex)
                markEdited(gui, newParent, cloned)
                recordChange(
                    "Cloned node",
                    cloned,
                    "Cloned `${source.testElement.name}` $position `${target.testElement.name}`",
                    "from ${nodePath(source)} to ${nodePath(newParent)}[$insertIndex]",
                )
                postActivity(
                    "info",
                    "Cloned node",
                    details = "${source.testElement.name} $position ${target.testElement.name}",
                )
                mapOf(
                    "cloned" to true,
                    "sourceName" to source.testElement.name,
                    "sourcePath" to nodePath(source),
                    "sourceNodeId" to nodeId(source),
                    "clonedName" to cloned.testElement.name,
                    "clonedPath" to nodePath(cloned),
                    "clonedNodeId" to nodeId(cloned),
                    "targetName" to target.testElement.name,
                    "targetPath" to nodePath(target),
                    "targetNodeId" to nodeId(target),
                    "position" to position,
                    "newParentPath" to nodePath(newParent),
                    "newIndex" to insertIndex,
                    "childCount" to cloned.childCount,
                    "clonedClassName" to cloned.testElement::class.java.name,
                )
            } finally {
                gui.endUndoTransaction()
            }
        }

    private fun deleteNodeOpenPlan(arguments: JsonNode): Map<String, Any?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            ensureBackupForOpenPlan(gui)
            val beforeState = RepairActionState(
                signature = openPlanSignature(gui),
                elementCount = countTreeNodes(gui.treeModel.testPlan),
                threadGroupCount = gui.treeModel.getNodesOfType(AbstractThreadGroup::class.java).size,
            )
            val beforeChangeCount = AiAutoScriptingLogWindow.changes().size
            val beforeElementCount = beforeState.elementCount
            var expectedElementCount = beforeElementCount
            try {
                gui.beginUndoTransaction()
                try {
                    val deleteAllMatches = arguments.path("deleteAllMatches").asBoolean(false)
                    val selectedNodes = if (deleteAllMatches) {
                        selectNodeReferences(gui, arguments, "target", "target")
                    } else {
                        listOf(selectNodeReference(gui, arguments, "target", "target"))
                    }.distinct()
                    val nodes = selectedNodes
                        .filterNot { candidate ->
                            selectedNodes.any { ancestor -> ancestor !== candidate && candidate.isDescendantOf(ancestor) }
                        }
                        .sortedByDescending { it.path.size }
                    val allowContainerDelete = arguments.path("allowStructuralContainerDelete").asBoolean(false)
                    if (!allowContainerDelete) {
                        require(nodes.none { it.testElement is TestPlan || it.testElement is AbstractThreadGroup }) {
                            "Refusing to delete a Test Plan or Thread Group through AI repair. " +
                                "Pass allowStructuralContainerDelete=true only for an explicit user-requested structural deletion."
                        }
                    }
                    nodes.forEach { node ->
                        require(isNodeInOpenPlan(gui, node)) {
                            "Target node '${node.testElement.name}' is no longer part of the open plan"
                        }
                        require(node.testElement.canRemove()) {
                            "Target node '${node.testElement.name}' cannot be removed while it is busy"
                        }
                    }
                    val deletedElementCount = nodes.sumOf(::countNodeAndDescendants)
                    expectedElementCount = beforeElementCount - deletedElementCount
                    val deleted = mutableListOf<Map<String, Any?>>()
                    val changedParents = linkedSetOf<JMeterTreeNode>()
                    moveSelectionOutsideDeletedNodes(gui, nodes)
                    for (node in nodes) {
                        val parent = node.parent as? JMeterTreeNode
                            ?: throw IllegalArgumentException("Target node has no removable parent")
                        require(node !== testPlanNode(gui)) {
                            "Cannot delete the open Test Plan root"
                        }
                        val deletedName = node.testElement.name.orEmpty()
                        val deletedPath = nodePath(node)
                        val deletedClassName = node.testElement::class.java.name
                        val childCount = node.childCount
                        removeTreeNode(gui, node)
                        changedParents += parent
                        recordChange(
                            "Deleted node",
                            parent,
                            "Deleted `$deletedName`",
                            deletedPath,
                        )
                        deleted += mapOf(
                            "deletedName" to deletedName,
                            "deletedPath" to deletedPath,
                            "deletedClassName" to deletedClassName,
                            "deletedChildCount" to childCount,
                            "parentPath" to nodePath(parent),
                        )
                    }
                    val primaryParent = changedParents.firstOrNull() ?: testPlanNode(gui)
                    markEdited(gui, primaryParent)
                    changedParents.drop(1).forEach { gui.treeModel.nodeStructureChanged(it) }
                    val actualElementCount = countTreeNodes(gui.treeModel.testPlan)
                    require(actualElementCount == expectedElementCount) {
                        "Live node deletion damaged the open plan: expected $expectedElementCount elements after deletion, found $actualElementCount"
                    }
                    val details = deleted.joinToString("; ") { it["deletedPath"].toString() }
                    postActivity(
                        "info",
                        if (deleted.size == 1) "Deleted node" else "Deleted ${deleted.size} nodes",
                        details = details,
                    )
                    mapOf(
                        "deleted" to true,
                        "deletedCount" to deleted.size,
                        "deletedNodes" to deleted,
                    )
                } finally {
                    gui.endUndoTransaction()
                }
            } catch (error: Exception) {
                val rollback = rollbackFailedRepairAction(beforeState, beforeChangeCount)
                if (rollback.rolledBack) {
                    throw IllegalStateException(
                        "${error.message}. The failed deletion was rolled back via ${rollback.method}.",
                        error,
                    )
                }
                throw error
            }
        }

    private fun moveSelectionOutsideDeletedNodes(gui: GuiPackage, nodes: List<JMeterTreeNode>) {
        val current = gui.treeListener.currentNode ?: return
        if (nodes.none { current.isDescendantOf(it) }) {
            return
        }
        var fallback = current.parent as? JMeterTreeNode
        while (fallback != null && nodes.any { fallback.isDescendantOf(it) }) {
            fallback = fallback.parent as? JMeterTreeNode
        }
        val safeFallback = fallback ?: testPlanNode(gui)
        gui.treeListener.setSelectionPathWithoutEdit(TreePath(safeFallback.path))
    }

    private fun countNodeAndDescendants(node: JMeterTreeNode): Int {
        var count = 1
        for (index in 0 until node.childCount) {
            count += countNodeAndDescendants(node.getChildAt(index) as JMeterTreeNode)
        }
        return count
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
        val element = createDetachedTestElement(guiClassName)
        return addConfiguredComponent(gui, parent, element, configure)
    }

    private fun addConfiguredComponent(
        gui: GuiPackage,
        parent: JMeterTreeNode,
        element: TestElement,
        configure: (TestElement) -> Unit,
    ): JMeterTreeNode {
        configure(element)
        // Deliberately bypasses JMeterTreeModel.addComponent: that path routes the
        // element through the shared per-class GUI panel and then flushes the panel
        // into the *currently selected* node (getCurrentGui -> updateCurrentNode).
        // When the selection is a same-class node - e.g. the assertion or extractor
        // added a moment earlier in a batch - its name and content get silently
        // overwritten with this element's data, which manifested as assertions and
        // extractors "shifting one sampler back" after batch edits.
        val newNode = JMeterTreeNode(element, gui.treeModel)
        runCatching { newNode.isEnabled = element.isEnabled }
        gui.treeModel.insertNodeInto(newNode, parent, parent.childCount)
        return newNode
    }

    private fun insertDetachedNode(gui: GuiPackage, parent: JMeterTreeNode, element: TestElement): JMeterTreeNode {
        val node = JMeterTreeNode(element, gui.treeModel)
        runCatching { node.isEnabled = element.isEnabled }
        gui.treeModel.insertNodeInto(node, parent, parent.childCount)
        return node
    }

    private fun insertDetachedSubTree(gui: GuiPackage, parent: JMeterTreeNode, tree: HashTree) {
        for (element in tree.list()) {
            if (element !is TestElement) {
                continue
            }
            val node = insertDetachedNode(gui, parent, element)
            insertDetachedSubTree(gui, node, tree.getTree(element))
        }
    }

    /**
     * Creates a test element from a fresh, throwaway GUI component instance instead
     * of GuiPackage.createTestElement, which resets the shared cached panel that may
     * currently be displaying (and later be flushed into) the selected node.
     */
    private fun createDetachedTestElement(guiClassName: String): TestElement {
        val guiComponent = Class.forName(guiClassName).getDeclaredConstructor().newInstance()
            as org.apache.jmeter.gui.JMeterGUIComponent
        return guiComponent.createTestElement()
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
        val nodes = mutableListOf<JMeterTreeNode>()
        val elements = mutableListOf<TestElement>()
        fun collect(current: JMeterTreeNode) {
            nodes += current
            elements += current.testElement
            for (index in 0 until current.childCount) {
                collect(current.getChildAt(index) as JMeterTreeNode)
            }
        }
        collect(node)
        gui.treeModel.removeNodeFromParent(node)
        elements.forEach { element ->
            gui.removeNode(element)
            runCatching { element.removed() }
        }
        nodes.forEach(NodeIds::forget)
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

    private fun regexExtractorNodes(
        source: JMeterTreeNode,
        variableName: String?,
        requireFound: Boolean = true,
    ): List<JMeterTreeNode> {
        val sourceSubTree = GuiPackage.getInstance().treeModel.testPlan.findSubTree(source)
            ?: throw IllegalStateException("Could not locate source sampler subtree '${source.testElement.name}'")
        val matches = mutableListOf<JMeterTreeNode>()
        fun visit(tree: HashTree) {
            for (node in tree.list()) {
                if (node is JMeterTreeNode) {
                    if (node.testElement::class.java.name == "org.apache.jmeter.extractor.RegexExtractor" &&
                        (variableName == null || callString(node.testElement, "getRefName") == variableName)
                    ) {
                        matches += node
                    }
                    visit(tree.getTree(node))
                }
            }
        }
        visit(sourceSubTree)
        if (requireFound) {
            require(matches.isNotEmpty()) {
                "No regex extractor named '$variableName' under '${source.testElement.name}'"
            }
        }
        return matches
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

    private fun responseAssertionNodesUnder(target: JMeterTreeNode): List<JMeterTreeNode> {
        val targetSubTree = GuiPackage.getInstance().treeModel.testPlan.findSubTree(target)
            ?: return emptyList()
        val matches = mutableListOf<JMeterTreeNode>()
        fun visit(tree: HashTree) {
            for (node in tree.list()) {
                if (node is JMeterTreeNode) {
                    if (node.testElement::class.java.name == "org.apache.jmeter.assertions.ResponseAssertion") {
                        matches += node
                    }
                    visit(tree.getTree(node))
                }
            }
        }
        visit(targetSubTree)
        return matches
    }

    private fun assertionPlacementSummary(target: JMeterTreeNode): List<Map<String, Any?>> =
        responseAssertionNodesUnder(target).map { node ->
            mapOf(
                "assertionName" to node.testElement.name,
                "nodeId" to nodeId(node),
                "patterns" to responseAssertionPatterns(node.testElement),
            )
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
        argumentIndex: Int?,
        argumentName: String?,
        argumentValue: String?,
        alwaysEncode: Boolean,
    ): Int {
        var changed = 0
        for ((index, argument) in httpArguments(sampler).withIndex()) {
            if (argument.matches(argumentIndex, index, argumentName, argumentValue)) {
                setAlwaysEncoded(argument.element, alwaysEncode)
                changed++
            }
        }
        return changed
    }

    private fun setHttpArgumentValue(
        sampler: TestElement,
        argumentIndex: Int?,
        argumentName: String?,
        argumentValue: String?,
        newValue: String,
        alwaysEncode: Boolean?,
    ): Int {
        var changed = 0
        for ((index, argument) in httpArguments(sampler).withIndex()) {
            if (argument.matches(argumentIndex, index, argumentName, argumentValue)) {
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
        fun matches(argumentIndex: Int?, index: Int, argumentName: String?, argumentValue: String?): Boolean =
            (argumentIndex == null || argumentIndex == index) &&
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
        literals: List<String>?,
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
                literals,
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
        literals: List<String>?,
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
            val matchedLiteral = literals?.firstOrNull { value.contains(it) }
            val matched = if (literals != null) matchedLiteral != null else pattern?.containsMatchIn(value) == true
            if (matched) {
                matches += mapOf(
                    "nodePath" to path.joinToString(" / "),
                    "property" to property.name,
                    "excerpt" to value.take(500),
                    "matchedLiteral" to matchedLiteral,
                )
            }
        }
        if (property is TestElementProperty && property.objectValue is TestElement) {
            searchElementValues(
                property.objectValue as TestElement,
                path,
                literals,
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
                    literals,
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
        gui.treeListener.setSelectionPathWithoutEdit(TreePath(selectNode.path))
        gui.refreshCurrentGui()
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
        findKnowledgeSelection(gui)?.let { return it }
        val testPlanNode = testPlanNode(gui)

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

    private fun findKnowledgeSelection(gui: GuiPackage): KnowledgeNodeSelection? {
        val testPlanNode = testPlanNode(gui)
        val candidates = findKnowledgeNodes(gui.treeModel.testPlan, testPlanNode)
        return selectKnowledgeNode(candidates)?.let { selected ->
            KnowledgeNodeSelection(selected, candidates)
        }
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
        runCatching {
            val root = mapper.readTree(knowledgeJson)
            root == defaultKnowledgeJson || isEmptyKnowledgeShape(root)
        }.getOrDefault(knowledgeJson.trim() == BreakTestAiKnowledge.DEFAULT_JSON.trim())

    private fun isEmptyKnowledgeShape(root: JsonNode): Boolean {
        if (!root.isObject || root.path("schemaVersion").asInt(-1) != 1) {
            return false
        }
        val allowedFields = setOf(
            "schemaVersion",
            "projectHints",
            "correlationPatterns",
            "assertionPatterns",
            "variableMappings",
            "knownDynamicFields",
            "timestampRules",
            "transactionDependencies",
            "learnedFromThreadGroups",
        )
        val fieldNames = root.fieldNames().asSequence().toSet()
        if (!allowedFields.containsAll(fieldNames)) {
            return false
        }
        return fieldNames
            .filterNot { it == "schemaVersion" }
            .all { field -> root.path(field).isArray && root.path(field).isEmpty }
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
            useField = arguments.path("useField").takeIfPresent()?.asText() ?: inferredRegexUseField(arguments),
            literal = arguments.path("literal").takeIfPresent()?.asText(),
            failOnNoMatch = arguments.path("failOnNoMatch").takeIfPresent()?.asBoolean() ?: true,
        )

    private fun inferredRegexUseField(arguments: JsonNode): String {
        val evidence = arguments.path("evidence").asText("").lowercase()
        return if (
            evidence.contains("response header") ||
            evidence.contains("headers") ||
            evidence.contains("location:") ||
            evidence.contains("set-cookie:") ||
            evidence.contains("authorization:") ||
            evidence.contains("www-authenticate:")
        ) {
            "headers"
        } else {
            "body"
        }
    }

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
            targetNodeId = arguments.path("targetNodeId").optionalText(),
            targetSamplerIndex = arguments.path("targetSamplerIndex").takeIfPresent()?.asInt(),
            targetSamplerLabel = arguments.path("targetSamplerLabel").takeIfPresent()?.asText(),
            targetNodePath = arguments.path("targetNodePath").optionalText(),
            targetOccurrenceIndex = arguments.path("targetOccurrenceIndex").takeIfPresent()?.asInt(),
            scopeNodePath = arguments.path("scopeNodePath").optionalText(),
            threadGroupName = arguments.path("threadGroupName").optionalText(),
            allowWholePlan = arguments.path("allowWholePlan").asBoolean(false),
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
        // A marker that embeds run-specific data (JSON numbers, UUIDs, dates, epoch
        // values) matches the cached run it was copied from but fails on the next
        // execution when the value changes. Force the stable structural part.
        val volatileFragment = VOLATILE_ASSERTION_FRAGMENT_REGEX.find(normalized)?.value
        require(volatileFragment == null) {
            "Volatile response assertion '$pattern': the fragment '$volatileFragment' embeds run-specific data " +
                "(number, id, or date) that will differ on the next execution. Assert the stable field name or " +
                "phrase without the changing value, e.g. use '\"totalDraws\"' instead of '\"totalDraws\":35'. " +
                "Pass allowWeakPattern=true only when the value is genuinely constant."
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

    private fun allTreeNodes(tree: HashTree): List<JMeterTreeNode> {
        val result = mutableListOf<JMeterTreeNode>()
        fun visit(currentTree: HashTree) {
            for (node in currentTree.list()) {
                if (node is JMeterTreeNode) {
                    result += node
                }
                visit(currentTree.getTree(node))
            }
        }
        visit(tree)
        return result
    }

    private fun JMeterTreeNode.matchesNodeType(type: String): Boolean {
        val className = testElement::class.java.name
        return when (type) {
            "sampler" -> testElement is Sampler
            "controller" -> testElement is Controller
            "transaction", "transaction_controller" -> testElement is TransactionController
            "thread_group", "threadgroup" -> testElement is AbstractThreadGroup
            "regex_extractor", "regexextractor" -> className == "org.apache.jmeter.extractor.RegexExtractor"
            "boundary_extractor", "boundaryextractor" -> className == "org.apache.jmeter.extractor.BoundaryExtractor"
            "extractor" -> extractorVariableName(testElement) != null
            "response_assertion", "assertion" ->
                className == "org.apache.jmeter.assertions.ResponseAssertion" ||
                    className.contains(".assertions.")
            "jsr223" -> testElement is JSR223TestElement || className.contains("JSR223")
            "preprocessor", "postprocessor", "processor" -> className.contains("PreProcessor") ||
                className.contains("PostProcessor") ||
                className.contains(".extractor.") ||
                className.contains(".modifiers.")
            "config" -> className.contains(".config.")
            else -> className.contains(type, ignoreCase = true)
        }
    }

    private fun nodeInfo(
        node: JMeterTreeNode,
        matchIndex: Int,
        allNodes: List<JMeterTreeNode>,
    ): Map<String, Any?> {
        val samplerIndex = if (node.testElement is Sampler) {
            allNodes.asSequence()
                .filter { it.testElement is Sampler }
                .indexOf(node)
        } else {
            null
        }
        val parentSampler = node.ancestorSampler()
        val parentSamplerIndex = parentSampler?.let { sampler ->
            allNodes.asSequence()
                .filter { it.testElement is Sampler }
                .indexOf(sampler)
        }
        return mapOf(
            "matchIndex" to matchIndex,
            "nodeId" to nodeId(node),
            "name" to node.testElement.name,
            "nodePath" to nodePath(node),
            "className" to node.testElement::class.java.name,
            "enabled" to node.isEnabled,
            "childCount" to node.childCount,
            "samplerIndex" to samplerIndex,
            "parentSamplerIndex" to parentSamplerIndex,
            "parentSamplerName" to parentSampler?.testElement?.name,
            "parentSamplerPath" to parentSampler?.let(::nodePath),
            "variableName" to extractorVariableName(node.testElement),
            "regex" to callString(node.testElement, "getRegex"),
            "useField" to extractorUseField(node.testElement),
            "failOnNoMatch" to callBoolean(node.testElement, "isFailOnNoMatch"),
        ).filterValues { it != null }
    }

    private fun JMeterTreeNode.ancestorSampler(): JMeterTreeNode? {
        var current = parent
        while (current is JMeterTreeNode) {
            if (current.testElement is Sampler) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun extractorVariableName(element: TestElement): String? =
        callString(element, "getRefName")

    private fun extractorUseField(element: TestElement): String? =
        when {
            callBoolean(element, "useHeaders") == true -> "headers"
            callBoolean(element, "useRequestHeaders") == true -> "request_headers"
            callBoolean(element, "useUnescapedBody") == true -> "unescaped"
            callBoolean(element, "useBodyAsDocument") == true -> "as_document"
            callBoolean(element, "useUrl") == true -> "url"
            callBoolean(element, "useCode") == true -> "code"
            callBoolean(element, "useMessage") == true -> "message"
            callBoolean(element, "useBody") == true -> "body"
            else -> null
        }

    private fun Sequence<JMeterTreeNode>.indexOf(target: JMeterTreeNode): Int? {
        forEachIndexed { index, node ->
            if (node === target) {
                return index
            }
        }
        return null
    }

    private fun scopedSamplerNodes(gui: GuiPackage, threadGroupName: String?): List<JMeterTreeNode> {
        val samplers = samplerNodes(gui.treeModel.testPlan)
        if (threadGroupName.isNullOrBlank()) {
            return samplers
        }
        val threadGroup = selectThreadGroup(gui, threadGroupName)
        return samplers.filter { sampler -> sampler.isDescendantOf(threadGroup) }
    }

    private fun linkedHarExchanges(
        gui: GuiPackage,
        testPlanFile: String,
        threadGroupName: String?,
        includeStaticAssets: Boolean,
    ): List<HarExchange> {
        val allSamplers = samplerNodes(gui.treeModel.testPlan)
        val scopedSamplers = if (threadGroupName.isNullOrBlank()) {
            allSamplers
        } else {
            val threadGroup = selectThreadGroup(gui, threadGroupName)
            allSamplers.filter { sampler -> sampler.isDescendantOf(threadGroup) }
        }
        return scopedSamplers.mapIndexedNotNull { index, sampler ->
            val resolution = RecordedHarExchangeResolver.resolveFor(sampler, Path.of(testPlanFile))
            if (!resolution.exchange().isPresent) {
                return@mapIndexedNotNull null
            }
            if (!includeStaticAssets &&
                (isStaticHarRequest(resolution.requestText()) || isStaticHarResponse(resolution.responseText()))
            ) {
                return@mapIndexedNotNull null
            }
            HarExchange(
                index = index,
                globalIndex = allSamplers.indexOf(sampler),
                sampler = sampler,
                request = resolution.requestText(),
                response = resolution.responseText(),
            )
        }
    }

    private fun JMeterTreeNode.isDescendantOf(ancestor: JMeterTreeNode): Boolean {
        var current: javax.swing.tree.TreeNode? = this
        while (current is JMeterTreeNode) {
            if (current === ancestor) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun firstNonBlankLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    // Extension-less asset URLs (e.g. /assets/{uuid} serving image/webp) slip past
    // the URL-based check, flooding HAR listings and planner actions with junk
    // uuid correlations for images. The response content type is authoritative.
    private fun isStaticHarResponse(responseText: String): Boolean {
        val headerEnd = harHeaderBlockEnd(responseText) ?: responseText.length
        val contentType = responseText.substring(0, headerEnd)
            .lineSequence()
            .firstOrNull { it.startsWith("content-type:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.lowercase()
            ?: return false
        return contentType.startsWith("image/") ||
            contentType.startsWith("font/") ||
            contentType.startsWith("audio/") ||
            contentType.startsWith("video/") ||
            contentType.startsWith("text/css") ||
            contentType.contains("javascript") ||
            contentType.contains("font-woff") ||
            contentType.startsWith("application/wasm") ||
            contentType.contains("source-map")
    }

    private fun isStaticHarRequest(requestText: String): Boolean {
        val firstLine = firstNonBlankLine(requestText).lowercase()
        return STATIC_HAR_REQUEST_REGEX.containsMatchIn(firstLine)
    }

    private fun harRequestCandidates(requestText: String): List<HarRequestCandidate> {
        val candidates = mutableListOf<HarRequestCandidate>()
        fun add(surface: String, kind: String, fieldName: String?, literal: String, priority: Int) {
            val cleaned = literal.trim().trim('"', '\'', ',', ';')
            if (cleaned.length < 4 ||
                cleaned.startsWith("\${") ||
                cleaned.equals("null", ignoreCase = true) ||
                cleaned.equals("true", ignoreCase = true) ||
                cleaned.equals("false", ignoreCase = true) ||
                isLowValueHarLiteral(cleaned) ||
                candidates.any { it.literal == cleaned && it.fieldName == fieldName }
            ) {
                return
            }
            candidates += HarRequestCandidate(surface, kind, fieldName, cleaned, priority)
        }

        for (match in HAR_FIELD_VALUE_REGEX.findAll(requestText)) {
            val fieldName = match.groupValues[1]
            val literal = match.groupValues[2]
            val kind = kindForHarField(fieldName, literal)
            add("recorded_request", kind, fieldName, literal, priorityForHarField(fieldName, literal))
        }
        for (match in BEARER_TOKEN_REGEX.findAll(requestText)) {
            add("recorded_request_header", "bearer-token", "Authorization", match.groupValues[1], 98)
        }
        for (match in UUID_REGEX.findAll(requestText)) {
            add("recorded_request", "uuid", null, match.value, 86)
        }
        for (match in EPOCH_MS_REGEX.findAll(requestText)) {
            add("recorded_request", "epoch-ms", null, match.value, 78)
        }
        for (match in LONG_OPAQUE_REGEX.findAll(requestText)) {
            add("recorded_request", "opaque-token", null, match.value, 62)
        }
        return candidates
            .sortedWith(compareByDescending<HarRequestCandidate> { it.priority }.thenBy { it.literal.length })
            .take(200)
    }

    private fun planHarCorrelationActions(
        gui: GuiPackage,
        testPlanFile: String,
        threadGroupName: String?,
        includeStaticAssets: Boolean,
        contextChars: Int,
        usedVariableNames: MutableSet<String>,
        unresolved: MutableList<Map<String, Any?>>,
        maxUnresolved: Int,
    ): List<Map<String, Any?>> {
        val actions = mutableListOf<Map<String, Any?>>()
        val exchanges = linkedHarExchanges(gui, testPlanFile, threadGroupName, includeStaticAssets)
        val seen = mutableSetOf<String>()
        // Correlation actions replace their literal across the selected scope.
        // Planning more than one action for the same literal makes the first
        // action consume every replacement and leaves later actions with an
        // extractor but no literal to replace. One literal therefore has one
        // authoritative source/action per planner snapshot.
        val plannedLiterals = mutableSetOf<String>()
        val scopeNodePath = threadGroupName
            ?.let { runCatching { nodePath(selectThreadGroup(gui, it)) }.getOrNull() }
        for (target in exchanges) {
            for (requestCandidate in harRequestCandidates(target.request)) {
                val seenKey = "${target.globalIndex}:${requestCandidate.literal}"
                if (!seen.add(seenKey)) {
                    continue
                }
                // The recorded request often carries the URL/HTML-encoded form of a
                // value that the issuing response contains raw (or vice versa), so
                // source matching tries the encoding variants of the literal too.
                // Header-block occurrences win over body occurrences: recorded
                // redirect bodies frequently differ from what JMeter replays, while
                // Location/Set-Cookie headers are stable at runtime.
                val source = exchanges
                    .asSequence()
                    .filter { it.index < target.index }
                    .mapNotNull { sourceExchange ->
                        preferredLiteralOccurrence(sourceExchange.response, requestCandidate.literal)
                            ?.let { (index, variant) -> Triple(sourceExchange, index, variant) }
                    }
                    .lastOrNull()
                if (source == null) {
                    if (requestCandidate.priority >= 80 && unresolved.size < maxUnresolved) {
                        unresolved += unresolvedRepairCandidate(target, requestCandidate)
                    }
                    continue
                }

                val (sourceExchange, literalIndex, matchedLiteral) = source
                val regex = regexForHarCandidate(sourceExchange.response, requestCandidate, matchedLiteral, literalIndex)
                    ?.takeIf {
                        AgentRegexSupport.oroProblem(it) == null &&
                            AgentRegexSupport.oroMatches(it, sourceExchange.response)
                    }
                    // Never fall back to a Boundary Extractor action: derive an
                    // ORO-safe regex from the literal's boundaries instead.
                    ?: boundaryDerivedRegex(sourceExchange.response, matchedLiteral, literalIndex)
                if (regex == null) {
                    if (requestCandidate.priority >= 80 && unresolved.size < maxUnresolved) {
                        unresolved += unresolvedRepairCandidate(
                            target,
                            requestCandidate,
                            "The recorded response contains the value, but no safe native Regex Extractor pattern could be derived.",
                        )
                    }
                    continue
                }
                if (!plannedLiterals.add(canonicalRepairLiteral(requestCandidate.literal))) {
                    continue
                }
                val variableName = uniqueVariableName(variableNameFor(requestCandidate), usedVariableNames)
                val evidence = sourceExchange.response.contextAround(
                    literalIndex,
                    literalIndex + matchedLiteral.length,
                    contextChars,
                )
                val applyArguments = mapOf(
                    "sourceNodeId" to nodeId(sourceExchange.sampler),
                    "sourceNodePath" to nodePath(sourceExchange.sampler),
                    "scopeNodePath" to scopeNodePath,
                    "variableName" to variableName,
                    "regex" to regex,
                    "template" to "$1$",
                    "matchNumber" to "1",
                    "defaultValue" to "NOT_FOUND",
                    "useField" to extractorUseFieldFor(sourceExchange.response, literalIndex),
                    "literal" to requestCandidate.literal,
                    "failOnNoMatch" to true,
                    "evidenceSource" to "recorded_response",
                    "evidence" to evidence,
                ).filterValues { it != null }
                val replacementCount = countLiteralMatchesInOpenPlan(gui, requestCandidate.literal, includeNames = false)
                actions += mapOf(
                    "id" to "har-${actions.size + 1}",
                    "type" to "correlate_from_har",
                    "confidence" to confidenceForHarAction(requestCandidate, true),
                    "priority" to requestCandidate.priority,
                    "kind" to requestCandidate.kind,
                    "fieldName" to requestCandidate.fieldName,
                    "variableName" to variableName,
                    "literalPreview" to requestCandidate.literal.previewToken(),
                    "sourceEncodingVariant" to (matchedLiteral != requestCandidate.literal),
                    "matchedSourceLiteralPreview" to matchedLiteral.takeIf { it != requestCandidate.literal }?.previewToken(),
                    "sourceSamplerIndex" to sourceExchange.globalIndex,
                    "sourceSamplerLabel" to sourceExchange.sampler.testElement.name,
                    "sourceNodePath" to nodePath(sourceExchange.sampler),
                    "targetSamplerIndex" to target.globalIndex,
                    "targetSamplerLabel" to target.sampler.testElement.name,
                    "targetNodePath" to nodePath(target.sampler),
                    "targetSurface" to requestCandidate.surface,
                    "replacementCount" to replacementCount,
                    "summary" to "Correlate ${requestCandidate.kind} `${requestCandidate.literal.previewToken()}` from `${sourceExchange.sampler.testElement.name}` and replace dependent request data.",
                    "applyTool" to "apply_regex_correlation_open_plan",
                    "applyArguments" to applyArguments,
                    "verify" to listOf(
                        mapOf(
                            "tool" to "search_open_plan_values",
                            "arguments" to mapOf(
                                "literal" to requestCandidate.literal,
                                "includeNames" to false,
                                "excludeUserDefinedVariables" to true,
                                "maxMatches" to 20,
                            ),
                        )
                    ),
                )
            }
        }
        return actions
    }

    private fun planCredentialActions(
        gui: GuiPackage,
        threadGroupName: String?,
        includeStaticAssets: Boolean,
        usedVariableNames: MutableSet<String>,
    ): List<Map<String, Any?>> {
        val planTree = if (threadGroupName.isNullOrBlank()) {
            currentPlanTree()
        } else {
            val convertedTree = convertTree(gui.treeModel.testPlan)
            val threadGroup = selectThreadGroup(gui, threadGroupName)
            val threadGroupSubTree = convertedTree.findSubTree(threadGroup.testElement)
                ?: return emptyList()
            ListedHashTree().apply {
                add(threadGroup.testElement, threadGroupSubTree)
            }
        }
        val credentials = AgentDynamicValueAnalyzer(includeStaticAssetRequests = includeStaticAssets)
            .analyze(planTree, 80)
            .filter { it.kind == "credential" }
            .distinctBy { it.literal }
            .take(10)
        return credentials.mapIndexed { index, candidate ->
            val variableName = uniqueVariableName(credentialVariableName(candidate.propertyName, candidate.literal), usedVariableNames)
            val replacement = "\${$variableName}"
            mapOf(
                "id" to "credential-${index + 1}",
                "type" to "parameterize_credential",
                "confidence" to 92,
                "priority" to candidate.priority,
                "kind" to candidate.kind,
                "variableName" to variableName,
                "literalPreview" to displayVariableValue(variableName, candidate.literal),
                "targetSamplerIndex" to candidate.samplerIndex,
                "targetSamplerLabel" to candidate.samplerName,
                "replacementCount" to countLiteralMatchesInOpenPlan(gui, candidate.literal, includeNames = false),
                "summary" to "Replace credential-like literal in `${candidate.samplerName}` with `$replacement` and store the original value in User Defined Variables.",
                "steps" to listOf(
                    mapOf(
                        "tool" to "replace_literal_open_plan",
                        "arguments" to mapOf(
                            "literal" to candidate.literal,
                            "replacement" to replacement,
                            "includeNames" to false,
                            "excludeUserDefinedVariables" to true,
                            "threadGroupName" to threadGroupName,
                        ).filterValues { it != null },
                    ),
                    mapOf(
                        "tool" to "set_user_defined_variable_open_plan",
                        "arguments" to mapOf(
                            "name" to variableName,
                            "value" to candidate.literal,
                        ),
                    ),
                    mapOf(
                        "tool" to "search_open_plan_values",
                        "arguments" to mapOf(
                            "literal" to candidate.literal,
                            "includeNames" to false,
                            "excludeUserDefinedVariables" to true,
                            "maxMatches" to 20,
                            "threadGroupName" to threadGroupName,
                        ).filterValues { it != null },
                    ),
                ),
            )
        }
    }

    private fun compactRepairAction(action: Map<String, Any?>, includeApplyArguments: Boolean): Map<String, Any?> {
        val compact = linkedMapOf<String, Any?>(
            "id" to action["id"],
            "type" to action["type"],
            "confidence" to action["confidence"],
            "priority" to action["priority"],
            "summary" to action["summary"],
            "kind" to action["kind"],
            "fieldName" to action["fieldName"],
            "variableName" to action["variableName"],
            "literalPreview" to action["literalPreview"],
            "sourceEncodingVariant" to action["sourceEncodingVariant"],
            "sourceSamplerLabel" to action["sourceSamplerLabel"],
            "targetSamplerLabel" to action["targetSamplerLabel"],
            "replacementCount" to action["replacementCount"],
            "applyTool" to action["applyTool"],
            "hasSteps" to action.containsKey("steps"),
        )
        if (includeApplyArguments) {
            action["applyArguments"]?.let { compact["applyArguments"] = it }
            action["steps"]?.let { compact["steps"] = it }
            action["verify"]?.let { compact["verify"] = it }
        }
        return compact.filterValues { it != null }
    }

    private fun unresolvedRepairCandidate(
        target: HarExchange,
        requestCandidate: HarRequestCandidate,
        reason: String? = null,
    ): Map<String, Any?> =
        // The shared "no source found" reason lives once at the top level of the
        // planner result instead of being repeated per entry: planner output stays
        // in the agent's context for every later turn of the run.
        mapOf(
            "priority" to requestCandidate.priority,
            "kind" to requestCandidate.kind,
            "fieldName" to requestCandidate.fieldName,
            "literalPreview" to requestCandidate.literal.previewToken(),
            "targetNodePath" to nodePath(target.sampler),
            "targetSurface" to requestCandidate.surface,
            "reason" to reason,
        ).filterValues { it != null }

    private fun regexForHarCandidate(
        responseText: String,
        candidate: HarRequestCandidate,
        matchedLiteral: String,
        literalIndex: Int,
    ): String? {
        val fieldName = candidate.fieldName?.takeIf { it.isNotBlank() } ?: return null
        // Kotlin Regex is only used to probe the recorded response; the returned
        // pattern runs in JMeter's ORO engine, so it must use ORO-safe escaping
        // (Regex.escape's \Q...\E form never matches there).
        val escapedField = Regex.escape(fieldName)
        val oroField = AgentRegexSupport.oroEscape(fieldName)
        val quotedJsonRegex = Regex(""""$escapedField"\s*:\s*"${Regex.escape(matchedLiteral)}"""")
        if (quotedJsonRegex.containsMatchIn(responseText)) {
            return """"$oroField"\s*:\s*"([^"]+)""""
        }
        val singleQuotedJsonRegex = Regex("""'$escapedField'\s*:\s*'${Regex.escape(matchedLiteral)}'""")
        if (singleQuotedJsonRegex.containsMatchIn(responseText)) {
            return """'$oroField'\s*:\s*'([^']+)'"""
        }
        val htmlNameValueRegex = Regex(
            """(?is)name\s*=\s*["']$escapedField["'][^>]{0,300}value\s*=\s*["']${Regex.escape(matchedLiteral)}["']""",
        )
        if (htmlNameValueRegex.containsMatchIn(responseText)) {
            return """(?is)name\s*=\s*["']$oroField["'][^>]{0,300}value\s*=\s*["']([^"']+)["']"""
        }
        val assignmentRegex = Regex("""$escapedField\s*=\s*${Regex.escape(matchedLiteral)}""")
        if (assignmentRegex.containsMatchIn(responseText)) {
            return """$oroField\s*=\s*([^&"'\s<>]+)"""
        }
        return if (literalIndex >= 0) null else null
    }

    private fun boundariesAroundLiteral(responseText: String, literal: String, literalIndex: Int): Pair<String, String> {
        val left = responseText.substring((literalIndex - 60).coerceAtLeast(0), literalIndex)
        val rightStart = literalIndex + literal.length
        val right = responseText.substring(rightStart, (rightStart + 60).coerceAtMost(responseText.length))
        return left to right
    }

    private fun harHeaderBlockEnd(responseText: String): Int? =
        responseText.indexOf("\n\n").takeIf { it >= 0 }
            ?: responseText.indexOf("\r\n\r\n").takeIf { it >= 0 }

    private fun isRedirectHarResponse(responseText: String): Boolean =
        Regex("""^HTTP/\S+\s+30\d\b""").containsMatchIn(responseText.lineSequence().firstOrNull().orEmpty())

    /**
     * Finds the occurrence of any encoding variant of the literal, preferring a
     * match inside the response header block over one in the body.
     */
    private fun preferredLiteralOccurrence(responseText: String, literal: String): Pair<Int, String>? {
        val variants = literalVariants(literal)
        val headerEnd = harHeaderBlockEnd(responseText)
        if (headerEnd != null) {
            variants.firstNotNullOfOrNull { variant ->
                responseText.indexOf(variant).takeIf { it in 0 until headerEnd }?.let { it to variant }
            }?.let { return it }
        }
        return variants.firstNotNullOfOrNull { variant ->
            responseText.indexOf(variant).takeIf { it >= 0 }?.let { it to variant }
        }
    }

    /**
     * Builds an ORO-safe extraction regex from the literal's immediate boundaries
     * when no structured field regex applies, so planner actions never need the
     * disallowed Boundary Extractor form.
     */
    private fun boundaryDerivedRegex(responseText: String, literal: String, literalIndex: Int): String? {
        val before = responseText.getOrNull(literalIndex - 1)
        val after = responseText.getOrNull(literalIndex + literal.length)
        if (before != null && before == after && before in setOf('\'', '"')) {
            val quote = AgentRegexSupport.oroEscape(before.toString())
            val regex = if (before == '"') {
                "$quote([^\"]+)$quote"
            } else {
                "$quote([^']+)$quote"
            }
            return regex.takeIf {
                AgentRegexSupport.oroProblem(it) == null && AgentRegexSupport.oroMatches(it, responseText)
            }
        }
        val (left, right) = boundariesAroundLiteral(responseText, literal, literalIndex)
        val leftAnchor = left.substringAfterLast('\n').substringAfterLast('\r').takeLast(24)
        val rightAnchor = right.takeWhile { it != '\n' && it != '\r' }.take(6)
        if (leftAnchor.length < 3 || rightAnchor.isEmpty()) {
            return null
        }
        val regex = AgentRegexSupport.oroEscape(leftAnchor) + "(.+?)" + AgentRegexSupport.oroEscape(rightAnchor)
        return regex.takeIf {
            AgentRegexSupport.oroProblem(it) == null && AgentRegexSupport.oroMatches(it, responseText)
        }
    }

    private fun extractorUseFieldFor(responseText: String, literalIndex: Int): String {
        val headerEnd = harHeaderBlockEnd(responseText)
        return when {
            headerEnd != null && literalIndex < headerEnd -> "headers"
            // Redirect bodies recorded in a HAR ("Found. Redirecting to <a href=...>")
            // are rarely replayed verbatim by JMeter, but the same values are issued
            // in the Location header at runtime, so header extraction is the safer
            // default for 3xx sources.
            isRedirectHarResponse(responseText) -> "headers"
            else -> "body"
        }
    }

    private fun confidenceForHarAction(candidate: HarRequestCandidate, hasStructuredRegex: Boolean): Int {
        val base = when {
            candidate.priority >= 98 -> 96
            candidate.priority >= 90 -> 92
            candidate.priority >= 80 -> 86
            else -> 76
        }
        return (base + if (hasStructuredRegex) 2 else -4).coerceIn(0, 99)
    }

    private fun uniqueVariableName(base: String, used: MutableSet<String>): String {
        val sanitized = base
            .replace(Regex("""[^A-Za-z0-9_]+"""), "_")
            .trim('_')
            .replaceFirstChar { it.lowercase() }
            .ifBlank { "ai_value" }
        var candidate = sanitized
        var index = 2
        while (!used.add(candidate)) {
            candidate = "${sanitized}_$index"
            index++
        }
        return candidate
    }

    private fun credentialVariableName(propertyName: String, literal: String): String {
        val text = "$propertyName $literal"
        return when {
            text.contains("password", ignoreCase = true) ||
                text.contains("passwd", ignoreCase = true) ||
                text.contains("pwd", ignoreCase = true) -> "password"
            text.contains("user", ignoreCase = true) ||
                text.contains("login", ignoreCase = true) ||
                text.contains("email", ignoreCase = true) -> "username"
            else -> "credential"
        }
    }

    private fun countLiteralMatchesInOpenPlan(gui: GuiPackage, literal: String, includeNames: Boolean): Int {
        val matches = mutableListOf<Map<String, Any?>>()
        val visitedElements = java.util.IdentityHashMap<TestElement, Boolean>()
        val visitedProperties = java.util.IdentityHashMap<JMeterProperty, Boolean>()
        fun visitNode(tree: HashTree, path: List<String>) {
            if (matches.size >= 500) {
                return
            }
            for (node in tree.list()) {
                val nodeName = (node as? JMeterTreeNode)?.testElement?.name ?: node.toString()
                val element = (node as? JMeterTreeNode)?.testElement ?: node as? TestElement
                if (element != null) {
                    searchElementValues(
                        element,
                        path + nodeName,
                        literalVariants(literal),
                        null,
                        includeNames,
                        false,
                        500,
                        visitedElements,
                        visitedProperties,
                        matches,
                    )
                }
                visitNode(tree.getTree(node), path + nodeName)
            }
        }
        visitNode(gui.treeModel.testPlan, emptyList())
        return matches.size
    }

    private fun kindForHarField(fieldName: String, literal: String): String {
        val lowerName = fieldName.lowercase()
        return when {
            "csrf" in lowerName || "verification" in lowerName || "requestverification" in lowerName -> "csrf-token"
            "nonce" in lowerName -> "nonce"
            lowerName == "state" || lowerName.endsWith(".state") -> "oauth-state"
            lowerName == "code" || lowerName.endsWith(".code") -> "oauth-code"
            "challenge" in lowerName -> "oauth-code-challenge"
            "client_id" in lowerName || "clientid" in lowerName -> "client-id"
            "draw" in lowerName -> "draw-id"
            "basket" in lowerName -> "basket-id"
            "ticket" in lowerName -> "ticket-id"
            "order" in lowerName -> "order-id"
            "product" in lowerName -> "product-id"
            "token" in lowerName -> "token"
            "date" in lowerName || "time" in lowerName || "timestamp" in lowerName -> "timestamp"
            UUID_REGEX.matches(literal) -> "uuid"
            EPOCH_MS_REGEX.matches(literal) -> "epoch-ms"
            else -> "dynamic-field"
        }
    }

    private fun priorityForHarField(fieldName: String, literal: String): Int {
        val lowerName = fieldName.lowercase()
        return when {
            "csrf" in lowerName || "verification" in lowerName || "nonce" in lowerName -> 100
            lowerName == "state" || lowerName == "code" || "challenge" in lowerName -> 98
            "token" in lowerName || "client_id" in lowerName || "clientid" in lowerName -> 94
            "basket" in lowerName || "ticket" in lowerName || "order" in lowerName || "transaction" in lowerName -> 90
            "product" in lowerName || "draw" in lowerName -> 86
            "date" in lowerName || "time" in lowerName || "timestamp" in lowerName -> 82
            UUID_REGEX.matches(literal) -> 84
            EPOCH_MS_REGEX.matches(literal) -> 78
            else -> 65
        }
    }

    private fun variableNameFor(candidate: HarRequestCandidate): String {
        val base = candidate.fieldName
            ?.replace(Regex("""[^A-Za-z0-9]+"""), "_")
            ?.trim('_')
            ?.takeIf { it.isNotBlank() }
            ?: candidate.kind.replace("-", "_")
        return base.replaceFirstChar { it.lowercase() }
    }

    private fun isLowValueHarLiteral(value: String): Boolean {
        val lower = value.lowercase()
        return lower.length < 4 ||
            lower in LOW_VALUE_HAR_LITERALS ||
            lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("application/") ||
            lower.startsWith("text/") ||
            lower.startsWith("image/") ||
            lower.startsWith("font/") ||
            lower.all { it.isDigit() } && lower.length < 8
    }

    private fun String.previewToken(): String =
        if (length <= 24) this else take(10) + "..." + takeLast(6)

    private data class HarExchange(
        val index: Int,
        val globalIndex: Int,
        val sampler: JMeterTreeNode,
        val request: String,
        val response: String,
    )

    private data class HarRequestCandidate(
        val surface: String,
        val kind: String,
        val fieldName: String?,
        val literal: String,
        val priority: Int,
    )

    private data class OpenPlanBackup(
        val file: File,
        val originalPlanPath: String?,
        val wasDirty: Boolean,
    )

    private data class RepairActionState(
        val signature: String,
        val elementCount: Int,
        val threadGroupCount: Int,
    )

    private data class RepairRollback(
        val rolledBack: Boolean,
        val method: String?,
    )

    private fun String.contextAround(start: Int, endExclusive: Int, contextChars: Int): String {
        val from = (start - contextChars).coerceAtLeast(0)
        val to = (endExclusive + contextChars).coerceAtMost(length)
        val prefix = if (from > 0) "..." else ""
        val suffix = if (to < length) "..." else ""
        return prefix + substring(from, to) + suffix
    }

    private fun String.truncate(maxLength: Int): String =
        if (maxLength >= 0 && length > maxLength) {
            take(maxLength)
        } else {
            this
        }

    private fun jsonByteSize(value: Any?): Int =
        runCatching { mapper.writeValueAsBytes(value).size }
            .getOrDefault(0)

    private fun Int.toHumanBytes(): String =
        when {
            this >= 1024 * 1024 -> String.format("%.1f MB", this / 1024.0 / 1024.0)
            this >= 1024 -> String.format("%.1f KB", this / 1024.0)
            else -> "$this B"
        }

    /**
     * Stable IDs for GUI tree nodes. Node paths and flat sampler indexes both drift
     * after structural edits (move/delete/add); an ID handed out here keeps resolving
     * to the same node for the lifetime of the GUI session regardless of later edits.
     */
    private object NodeIds {
        private val idsByNode = WeakHashMap<JMeterTreeNode, String>()
        private val nodesById = HashMap<String, WeakReference<JMeterTreeNode>>()
        private val counter = AtomicLong()

        @Synchronized
        fun idFor(node: JMeterTreeNode): String =
            idsByNode.getOrPut(node) {
                val id = "n${counter.incrementAndGet()}"
                nodesById[id] = WeakReference(node)
                id
            }

        @Synchronized
        fun nodeFor(id: String): JMeterTreeNode? {
            val node = nodesById[id]?.get()
            if (node == null) {
                nodesById.remove(id)
            }
            return node
        }

        @Synchronized
        fun forget(node: JMeterTreeNode) {
            val id = idsByNode.remove(node) ?: return
            nodesById.remove(id)
        }

        @Synchronized
        fun clear() {
            idsByNode.clear()
            nodesById.clear()
        }
    }

    private fun nodeId(node: JMeterTreeNode): String = NodeIds.idFor(node)

    private fun selectNodeById(gui: GuiPackage, nodeId: String, role: String): JMeterTreeNode {
        val node = NodeIds.nodeFor(nodeId)
            ?: throw IllegalArgumentException(
                "Unknown $role nodeId '$nodeId'. Node IDs come from find_open_plan_nodes or earlier edit results in this GUI session.",
            )
        require(isNodeInOpenPlan(gui, node)) {
            "$role node '$nodeId' (${node.testElement.name}) is no longer part of the open plan"
        }
        return node
    }

    private fun isNodeInOpenPlan(gui: GuiPackage, target: JMeterTreeNode): Boolean =
        allTreeNodes(gui.treeModel.testPlan).any { it === target }

    /**
     * Optional resolution scope shared by the edit tools: when the agent passes
     * threadGroupName/scopeNodePath, label and path lookups are restricted to that
     * subtree so identical transaction/sampler names in other Thread Groups can
     * never be selected by mistake.
     */
    private fun resolutionScope(gui: GuiPackage, arguments: JsonNode): JMeterTreeNode? {
        val scopeNodePath = arguments.path("scopeNodePath").optionalText()
        if (scopeNodePath != null) {
            return selectNodeByPath(gui.treeModel.testPlan, scopeNodePath, "scope")
        }
        val threadGroupName = arguments.path("threadGroupName").optionalText()
        if (threadGroupName != null) {
            return runCatching { selectThreadGroup(gui, threadGroupName) }.getOrNull()
        }
        return null
    }

    private fun selectSampler(
        samplers: List<JMeterTreeNode>,
        index: Int?,
        label: String?,
        role: String,
        occurrenceIndex: Int? = null,
    ): JMeterTreeNode {
        if (index != null) {
            return samplers.getOrNull(index)
                ?: throw IllegalArgumentException("No $role sampler at index $index")
        }
        if (!label.isNullOrBlank()) {
            val matches = samplers.filter { it.testElement.name == label }
            if (occurrenceIndex != null) {
                return matches.getOrNull(occurrenceIndex)
                    ?: throw IllegalArgumentException(
                        "No $role sampler occurrence $occurrenceIndex named '$label'; found ${matches.size}",
                    )
            }
            return when (matches.size) {
                1 -> matches.single()
                0 -> throw IllegalArgumentException("No $role sampler named '$label'")
                else -> throw IllegalArgumentException(
                    "Expected exactly one $role sampler named '$label', found ${matches.size}: " +
                        matches.take(10).joinToString("; ") { "${nodePath(it)} [nodeId=${nodeId(it)}]" } +
                        ". Retry with ${role}NodeId, ${role}OccurrenceIndex, or ${role}NodePath.",
                )
            }
        }
        throw IllegalArgumentException(
            "Specify ${role}NodeId (preferred), ${role}NodePath, ${role}SamplerIndex, or ${role}SamplerLabel",
        )
    }

    private fun selectSamplerReference(
        gui: GuiPackage,
        arguments: JsonNode,
        prefix: String,
        role: String,
    ): JMeterTreeNode {
        val nodeIdArgument = arguments.path("${prefix}NodeId").optionalText()
        val nodePath = arguments.path("${prefix}NodePath").optionalText()
        if (nodeIdArgument != null) {
            val node = runCatching { selectNodeById(gui, nodeIdArgument, role) }.getOrNull()
            if (node != null) {
                require(node.testElement is Sampler) {
                    "$role nodeId '$nodeIdArgument' is not a sampler; it is ${node.testElement::class.java.name}"
                }
                return node
            }
            require(nodePath != null) {
                "Unknown or stale $role nodeId '$nodeIdArgument'; also pass ${prefix}NodePath so the node can be re-resolved after an undo or reload"
            }
        }
        val scope = resolutionScope(gui, arguments)
        val occurrenceIndex = arguments.path("${prefix}OccurrenceIndex").takeIfPresent()?.asInt()
            ?: arguments.path("occurrenceIndex").takeIfPresent()?.asInt()
        if (nodePath != null) {
            val node = selectNodeByPath(
                gui.treeModel.testPlan,
                nodePath,
                role,
                occurrenceIndex,
                scope = scope,
                nodeFilter = { it.testElement is Sampler },
            )
            require(node.testElement is Sampler) {
                "$role node '$nodePath' is not a sampler; it is ${node.testElement::class.java.name}"
            }
            return node
        }
        val samplerIndex = arguments.path("${prefix}SamplerIndex").takeIfPresent()?.asInt()
        val samplerLabel = arguments.path("${prefix}SamplerLabel").optionalText()
        val samplers = samplerNodes(gui.treeModel.testPlan)
        // Indexes are positions in the whole-plan sampler list, so scope must not
        // change the index space; label lookups are scoped to avoid same-name
        // samplers in other Thread Groups.
        val labelCandidates = if (scope != null && samplerIndex == null) {
            samplers.filter { it === scope || it.isDescendantOf(scope) }
        } else {
            samplers
        }
        return selectSampler(labelCandidates, samplerIndex, samplerLabel, role, occurrenceIndex)
    }

    private fun selectOptionalSamplerReference(
        gui: GuiPackage,
        arguments: JsonNode,
        prefix: String,
        role: String,
    ): JMeterTreeNode? {
        val hasNodeId = arguments.path("${prefix}NodeId").optionalText() != null
        val hasNodePath = arguments.path("${prefix}NodePath").optionalText() != null
        val hasSamplerIndex = arguments.path("${prefix}SamplerIndex").takeIfPresent() != null
        val hasSamplerLabel = arguments.path("${prefix}SamplerLabel").optionalText() != null
        if (!hasNodeId && !hasNodePath && !hasSamplerIndex && !hasSamplerLabel) {
            return null
        }
        return selectSamplerReference(gui, arguments, prefix, role)
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

    private fun selectNodeReference(
        gui: GuiPackage,
        arguments: JsonNode,
        prefix: String,
        role: String,
    ): JMeterTreeNode {
        val nodeIdArgument = arguments.path("${prefix}NodeId").optionalText()
        val nodePath = arguments.path("${prefix}NodePath").optionalText()
        if (nodeIdArgument != null) {
            runCatching { selectNodeById(gui, nodeIdArgument, role) }.getOrNull()?.let { return it }
            require(nodePath != null) {
                "Unknown or stale $role nodeId '$nodeIdArgument'; also pass ${prefix}NodePath so the node can be re-resolved after an undo or reload"
            }
        }
        if (nodePath != null) {
            val occurrenceIndex = arguments.path("${prefix}OccurrenceIndex").takeIfPresent()?.asInt()
                ?: arguments.path("occurrenceIndex").takeIfPresent()?.asInt()
            return selectNodeByPath(
                gui.treeModel.testPlan,
                nodePath,
                role,
                occurrenceIndex,
                scope = resolutionScope(gui, arguments),
            )
        }
        val samplerIndex = arguments.path("${prefix}SamplerIndex").takeIfPresent()?.asInt()
        val samplerLabel = arguments.path("${prefix}SamplerLabel").takeIfPresent()
            ?.asText()
            ?.takeIf { it.isNotBlank() }
        if (samplerIndex != null || samplerLabel != null) {
            return selectSampler(samplerNodes(gui.treeModel.testPlan), samplerIndex, samplerLabel, role)
        }
        throw IllegalArgumentException(
            "Specify ${prefix}NodeId, ${prefix}NodePath, ${prefix}SamplerIndex, or ${prefix}SamplerLabel for the $role node",
        )
    }

    private fun selectNodeReferences(
        gui: GuiPackage,
        arguments: JsonNode,
        prefix: String,
        role: String,
    ): List<JMeterTreeNode> {
        val nodeIdArgument = arguments.path("${prefix}NodeId").optionalText()
        val nodePath = arguments.path("${prefix}NodePath").optionalText()
        if (nodeIdArgument != null) {
            runCatching { selectNodeById(gui, nodeIdArgument, role) }.getOrNull()?.let { return listOf(it) }
            require(nodePath != null) {
                "Unknown or stale $role nodeId '$nodeIdArgument'; also pass ${prefix}NodePath so the node can be re-resolved after an undo or reload"
            }
        }
        if (nodePath != null) {
            var matches = matchingNodesByPath(gui.treeModel.testPlan, nodePath)
            val scope = resolutionScope(gui, arguments)
            if (scope != null) {
                matches = matches.filter { it === scope || it.isDescendantOf(scope) }
            }
            require(matches.isNotEmpty()) {
                "No $role node matched '$nodePath'"
            }
            return matches
        }
        return listOf(selectNodeReference(gui, arguments, prefix, role))
    }

    private fun selectNodeByPath(
        root: HashTree,
        requestedPath: String,
        role: String,
        occurrenceIndex: Int? = null,
        scope: JMeterTreeNode? = null,
        nodeFilter: ((JMeterTreeNode) -> Boolean)? = null,
    ): JMeterTreeNode {
        var matches = matchingNodesByPath(root, requestedPath)
        if (scope != null && matches.isNotEmpty()) {
            val scoped = matches.filter { it === scope || it.isDescendantOf(scope) }
            if (scoped.isEmpty()) {
                throw IllegalArgumentException(
                    "No $role node matched '$requestedPath' inside scope '${nodePath(scope)}'; " +
                        "${matches.size} match(es) exist outside the scope: " +
                        matches.take(5).joinToString("; ") { nodePath(it) },
                )
            }
            matches = scoped
        }
        if (nodeFilter != null && matches.size > 1) {
            val filtered = matches.filter(nodeFilter)
            if (filtered.isNotEmpty()) {
                matches = filtered
            }
        }
        if (occurrenceIndex != null) {
            return matches.getOrNull(occurrenceIndex)
                ?: throw IllegalArgumentException(
                    "No $role node occurrence $occurrenceIndex matched '$requestedPath'; found ${matches.size}",
                )
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException("No $role node matched '$requestedPath'")
            else -> throw IllegalArgumentException(
                "Expected exactly one $role node matching '$requestedPath', found ${matches.size}: " +
                    matches.take(10).joinToString("; ") { "${nodePath(it)} [nodeId=${nodeId(it)}]" } +
                    ". Retry with ${role}NodeId, ${role}OccurrenceIndex, or deleteAllMatches=true when appropriate.",
            )
        }
    }

    private fun matchingNodesByPath(root: HashTree, requestedPath: String): List<JMeterTreeNode> {
        val requestedStrict = normalizeNodePath(requestedPath)
        val requestedLegacy = normalizeNodePathLegacy(requestedPath)
        val strictExact = mutableListOf<JMeterTreeNode>()
        val strictSuffix = mutableListOf<JMeterTreeNode>()
        val legacyExact = mutableListOf<JMeterTreeNode>()
        val legacySuffix = mutableListOf<JMeterTreeNode>()
        val nameMatches = mutableListOf<JMeterTreeNode>()
        fun visit(tree: HashTree) {
            for (node in tree.list()) {
                if (node is JMeterTreeNode) {
                    val candidateStrict = node.path
                        .filterIsInstance<JMeterTreeNode>()
                        .map { it.testElement.name.orEmpty().trim() }
                        .filter { it.isNotBlank() }
                    val candidateLegacy = normalizeNodePathLegacy(nodePath(node))
                    when {
                        candidateStrict == requestedStrict -> strictExact += node
                        pathEndsWith(candidateStrict, requestedStrict) -> strictSuffix += node
                        candidateLegacy == requestedLegacy -> legacyExact += node
                        pathEndsWith(candidateLegacy, requestedLegacy) -> legacySuffix += node
                        node.testElement.name == requestedPath.trim() -> nameMatches += node
                    }
                }
                visit(tree.getTree(node))
            }
        }
        visit(root)
        return when {
            strictExact.isNotEmpty() -> strictExact
            strictSuffix.isNotEmpty() -> strictSuffix
            legacyExact.isNotEmpty() -> legacyExact
            legacySuffix.isNotEmpty() -> legacySuffix
            else -> nameMatches
        }.distinct()
    }

    // Splits on the same " / " separator that nodePath() joins with, so element
    // names that are or contain "/" (e.g. an HTTP sampler named "/") survive as
    // their own path segment instead of collapsing into the parent path.
    private fun normalizeNodePath(path: String): List<String> =
        path.split(" / ")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun normalizeNodePathLegacy(path: String): List<String> =
        path.split("/")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun pathEndsWith(candidate: List<String>, requested: List<String>): Boolean =
        requested.isNotEmpty() &&
            candidate.size >= requested.size &&
            candidate.takeLast(requested.size) == requested

    private fun isAncestorOf(ancestor: JMeterTreeNode, node: JMeterTreeNode): Boolean {
        var current = node.parent
        while (current is JMeterTreeNode) {
            if (current === ancestor) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun nodePath(node: JMeterTreeNode): String =
        node.path
            .filterIsInstance<JMeterTreeNode>()
            .joinToString(" / ") { it.testElement.name.orEmpty() }

    private fun selectThreadGroup(gui: GuiPackage, threadGroupName: String?): JMeterTreeNode {
        val matches = mutableListOf<JMeterTreeNode>()
        fun visit(tree: HashTree) {
            for (node in tree.list()) {
                if (node is JMeterTreeNode && node.testElement is AbstractThreadGroup && node.isEnabled) {
                    if (threadGroupName.isNullOrBlank() ||
                        node.testElement.name == threadGroupName ||
                        nodePath(node) == threadGroupName
                    ) {
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

    private fun enabledThreadGroupCount(gui: GuiPackage): Int =
        gui.treeModel.getNodesOfType(AbstractThreadGroup::class.java).count { it.isEnabled }

    @Volatile
    private var activeAgentLabel: String = "Codex"

    @JvmStatic
    public fun setActiveAgentLabel(label: String) {
        activeAgentLabel = label.ifBlank { "Codex" }
    }

    private fun handleAgentActivity(arguments: JsonNode): Map<String, Any> {
        val message = arguments.path("message").asText().ifBlank { "Agent activity" }
        val level = arguments.path("level").asText("info")
        val source = arguments.path("source").asText(activeAgentLabel).ifBlank { activeAgentLabel }
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

    private fun findThreadGroupInTree(tree: HashTree, threadGroupPath: String): AbstractThreadGroup {
        val targetSegments = normalizedPathSegments(threadGroupPath)
        val matches = mutableListOf<AbstractThreadGroup>()
        fun visit(currentTree: HashTree, path: List<String>) {
            for (node in currentTree.list()) {
                val element = node as? TestElement ?: continue
                val currentPath = path + element.name.orEmpty()
                if (element is AbstractThreadGroup && normalizedPathSegments(currentPath) == targetSegments) {
                    matches += element
                }
                visit(currentTree.getTree(node), currentPath)
            }
        }
        visit(tree, emptyList())
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException("No Thread Group in repair target matched '$threadGroupPath'")
            else -> throw IllegalArgumentException("Expected one Thread Group in repair target matching '$threadGroupPath', found ${matches.size}")
        }
    }

    private fun normalizedPathSegments(path: String): List<String> =
        normalizedPathSegments(path.split("/").map { it.trim() }.filter { it.isNotEmpty() })

    private fun normalizedPathSegments(path: List<String>): List<String> =
        if (path.size >= 2 && path[0] == path[1]) {
            path.drop(1)
        } else {
            path
        }

    private fun mergeUserDefinedVariables(gui: GuiPackage, repairedTree: HashTree): Int {
        val repairedTestPlan = firstTestPlan(repairedTree)
            ?: return 0
        val variables = repairedTestPlan.userDefinedVariables
        if (variables.isEmpty()) {
            return 0
        }
        val node = testPlanNode(gui)
        val activeTestPlan = node.testElement as? TestPlan
            ?: throw IllegalStateException("Open root node is not a Test Plan")
        var merged = 0
        for ((name, value) in variables) {
            if (name.isBlank()) {
                continue
            }
            activeTestPlan.arguments.removeArgument(name)
            activeTestPlan.addParameter(name, value)
            merged++
        }
        if (merged > 0) {
            gui.treeModel.nodeStructureChanged(node)
            recordChange(
                "Updated variables",
                node,
                "Merged User Defined Variables from AI repaired clone",
                "Variables merged: $merged",
            )
        }
        return merged
    }

    private fun firstTestPlan(tree: HashTree): TestPlan? {
        for (node in tree.list()) {
            val element = node as? TestElement
            if (element is TestPlan) {
                return element
            }
            val nested = firstTestPlan(tree.getTree(node))
            if (nested != null) {
                return nested
            }
        }
        return null
    }

    private fun uniqueGeneratedThreadGroupName(gui: GuiPackage, originalName: String): String {
        val base = "${originalName.ifBlank { "Thread Group" }}_AI_Generated"
        val existingNames = gui.treeModel.getNodesOfType(AbstractThreadGroup::class.java)
            .map { it.testElement.name.orEmpty() }
            .toSet()
        if (base !in existingNames) {
            return base
        }
        var index = 2
        while ("${base}_$index" in existingNames) {
            index++
        }
        return "${base}_$index"
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
            ignoreStaticAssetFailures = arguments.path("ignoreStaticAssetFailures").asBoolean(false),
        )

    private data class LastValidationSnapshot(
        val scopeNodePath: String?,
        val atMillis: Long,
        val samples: List<AgentSampleSummary>,
    )

    @Volatile
    private var lastValidation: LastValidationSnapshot? = null

    private fun validateOpenPlan(arguments: JsonNode): Any {
        val (tree, scopePath) = validationTreeWithScope(arguments)
        val report = BreakTestAgent().inspectAndValidate(
            tree,
            optionsFrom(arguments),
            dslCharacterLimit(arguments),
            includeStaticAssets(arguments),
        )
        val previous = lastValidation
        lastValidation = LastValidationSnapshot(scopePath, System.currentTimeMillis(), report.validation.samples)
        return if (arguments.path("compact").asBoolean(false)) {
            val previousStatus = previous
                ?.takeIf { it.scopeNodePath == scopePath }
                ?.samples
                ?.associate { it.label to (it.success to it.responseCode) }
            AgentReportCompactor.compactForRepair(
                report,
                sampleLimit = arguments.path("compactSampleLimit").asInt(20),
                bodyLimit = arguments.path("compactBodyLimit").asInt(1_500),
                previousSampleStatus = previousStatus,
            )
        } else {
            report
        }
    }

    private fun validationTreeWithScope(arguments: JsonNode): Pair<HashTree, String?> =
        guiCall {
            val gui = GuiPackage.getInstance() ?: error("BreakTest GUI is not ready")
            gui.updateCurrentNode()
            val planTree = convertTree(gui.treeModel.testPlan)
            val threadGroup = selectedValidationThreadGroup(gui, arguments)
                ?: return@guiCall planTree to null
            scopedThreadGroupValidationTree(planTree, threadGroup) to nodePath(threadGroup)
        }

    private fun selectedValidationThreadGroup(gui: GuiPackage, arguments: JsonNode): JMeterTreeNode? {
        val scopeNodePath = arguments.path("scopeNodePath").optionalText()
        if (scopeNodePath != null) {
            val scopeNode = selectNodeByPath(gui.treeModel.testPlan, scopeNodePath, "validation scope")
            return scopeNode.path
                .filterIsInstance<JMeterTreeNode>()
                .lastOrNull { it.testElement is AbstractThreadGroup }
                ?: throw IllegalArgumentException("Validation scope '$scopeNodePath' is not inside an enabled Thread Group")
        }
        val threadGroupName = arguments.path("threadGroupName").optionalText()
        if (threadGroupName != null) {
            return selectThreadGroup(gui, threadGroupName)
        }
        return null
    }

    private fun scopedThreadGroupValidationTree(planTree: HashTree, threadGroup: JMeterTreeNode): HashTree {
        val selectedThreadGroup = threadGroup.testElement
        val scoped = ListedHashTree()
        for (root in planTree.list()) {
            val rootSubTree = planTree.getTree(root)
            val scopedRootSubTree = ListedHashTree()
            for (child in rootSubTree.list()) {
                val childSubTree = rootSubTree.getTree(child)
                if (child is AbstractThreadGroup) {
                    if (child === selectedThreadGroup) {
                        scopedRootSubTree.add(child, childSubTree)
                    }
                } else {
                    scopedRootSubTree.add(child, childSubTree)
                }
            }
            scoped.add(root, scopedRootSubTree)
        }
        return scoped
    }

    private fun dslCharacterLimit(arguments: JsonNode): Int? {
        if (arguments.path("includeDsl").takeIfPresent()?.asBoolean() != true) {
            return 0
        }
        return arguments.path("dslCharacterLimit").takeIfPresent()?.asInt() ?: DEFAULT_DSL_CHARACTER_LIMIT
    }

    private fun includeStaticAssets(arguments: JsonNode): Boolean =
        arguments.path("includeStaticAssets").asBoolean(false)

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

    private fun JsonNode.optionalText(): String? =
        takeIfPresent()
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JsonNode.requiredText(name: String): String {
        val value = path(name)
        require(!value.isMissingNode && !value.isNull && value.asText().isNotBlank()) {
            "Missing required argument '$name'"
        }
        return value.asText()
    }
}
