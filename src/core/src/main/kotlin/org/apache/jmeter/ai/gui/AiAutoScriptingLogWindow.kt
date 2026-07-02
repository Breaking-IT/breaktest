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

import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.gui.util.EscapeDialog
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Insets
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import javax.swing.tree.TreePath

public object AiAutoScriptingLogWindow {
    private const val MAX_LINE_LENGTH = 2_000
    private const val MAX_DOCUMENT_LENGTH = 120_000
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var dialog: EscapeDialog? = null
    private var contentPanel: JPanel? = null
    private var textArea: JTextArea? = null
    private var statusLabel: JLabel? = null
    private var progressBar: JProgressBar? = null
    private var changeTable: JTable? = null
    private var stopButton: JButton? = null
    private var placementButton: JButton? = null
    private var separateWindowMode = false
    private var startedAt: Instant? = null
    private var stopHandler: Runnable? = null
    private val timer = Timer(1_000) { updateStatus() }
    private val changeModel = ChangeTableModel()

    @JvmStatic
    public fun showWindow() {
        onEdt {
            separateWindowMode = true
            ensureDialog().isVisible = true
            placeBesideMainFrame()
            updatePlacementButton()
        }
    }

    @JvmStatic
    public fun showLog() {
        if (separateWindowMode) {
            showWindow()
        } else {
            showDocked()
        }
    }

    @JvmStatic
    public fun showDocked() {
        onEdt {
            separateWindowMode = false
            dialog?.isVisible = false
            GuiPackage.getInstance()?.mainFrame?.showAiLogPanel()
            updatePlacementButton()
        }
    }

    @JvmStatic
    public fun toggleVisibility() {
        onEdt {
            if (separateWindowMode) {
                val currentDialog = ensureDialog()
                currentDialog.isVisible = !currentDialog.isVisible
                if (currentDialog.isVisible) {
                    placeBesideMainFrame()
                }
            } else {
                val mainFrame = GuiPackage.getInstance()?.mainFrame ?: return@onEdt
                if (mainFrame.isAiLogPanelVisible) {
                    mainFrame.hideAiLogPanel()
                } else {
                    showDocked()
                }
            }
            updatePlacementButton()
        }
    }

    @JvmStatic
    public fun dockedComponent(): JPanel {
        ensureContentPanel()
        return contentPanel ?: error("AI log panel was not created")
    }

    @JvmStatic
    public fun useSeparateWindow() {
        showWindow()
    }

    @JvmStatic
    public fun useDockedPanel() {
        showDocked()
    }

    @JvmStatic
    public fun isSeparateWindowMode(): Boolean = separateWindowMode

    @JvmStatic
    public fun isDockedVisible(): Boolean =
        GuiPackage.getInstance()?.mainFrame?.isAiLogPanelVisible ?: false

    @JvmStatic
    public fun isSeparateWindowVisible(): Boolean =
        dialog?.isVisible == true

    @JvmStatic
    public fun hideDocked() {
        onEdt {
            GuiPackage.getInstance()?.mainFrame?.hideAiLogPanel()
            updatePlacementButton()
        }
    }

    @JvmStatic
    public fun append(message: String) {
        val line = "${LocalTime.now(ZoneId.systemDefault()).format(timeFormat)} ${message.compactForDisplay()}\n"
        onEdt {
            val area = ensureTextArea()
            area.append(line)
            trimDocument(area)
            area.caretPosition = area.document.length
        }
    }

    @JvmStatic
    public fun setStopHandler(handler: Runnable?) {
        onEdt {
            stopHandler = handler
            updateStopButton()
        }
    }

    @JvmStatic
    public fun startRun() {
        onEdt {
            startedAt = Instant.now()
            changeModel.clear()
            showLog()
            ensureProgressBar().isIndeterminate = true
            ensureStatusLabel().text = "Running..."
            updateStopButton()
            if (!timer.isRunning) {
                timer.start()
            }
        }
    }

    @JvmStatic
    public fun finishRun(status: String) {
        onEdt {
            timer.stop()
            startedAt = null
            ensureProgressBar().isIndeterminate = false
            ensureStatusLabel().text = status
            updateStopButton()
        }
    }

    @JvmStatic
    public fun recordChange(type: String, nodeName: String, summary: String, details: String?, path: TreePath?) {
        onEdt {
            changeModel.add(
                ChangeEntry(
                    time = LocalTime.now(ZoneId.systemDefault()).format(timeFormat),
                    type = type,
                    nodeName = nodeName,
                    summary = summary,
                    details = details.orEmpty(),
                    path = path,
                )
            )
        }
    }

    @JvmStatic
    public fun changes(): List<Map<String, String>> =
        changeModel.snapshot().map {
            mapOf(
                "time" to it.time,
                "type" to it.type,
                "nodeName" to it.nodeName,
                "summary" to it.summary,
                "details" to it.details,
            )
        }

    @JvmStatic
    public fun clear() {
        onEdt {
            ensureTextArea().text = ""
            changeModel.clear()
        }
    }

    private fun ensureDialog(): EscapeDialog {
        dialog?.let { return it }
        val mainFrame = GuiPackage.getInstance()?.mainFrame
        val newDialog = EscapeDialog(mainFrame, "AI Auto Scripting (Beta)", false).apply {
            contentPane.layout = BorderLayout()
            contentPane.add(ensureContentPanel(), BorderLayout.CENTER)
            minimumSize = Dimension(460, 280)
            size = Dimension(620, 520)
        }
        dialog = newDialog
        return newDialog
    }

    private fun ensureContentPanel(): JPanel {
        contentPanel?.let { return it }
        val panel = JPanel(BorderLayout()).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(
                JSplitPane(
                    JSplitPane.VERTICAL_SPLIT,
                    JScrollPane(
                        ensureTextArea(),
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                    ),
                    JScrollPane(
                        ensureChangeTable(),
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                    ),
                ).apply {
                    resizeWeight = 0.68
                    dividerLocation = 250
                },
                BorderLayout.CENTER,
            )
        }
        contentPanel = panel
        return panel
    }

    private fun toolbar(): JPanel =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            add(JPanel(BorderLayout(8, 0)).apply {
                add(ensureProgressBar(), BorderLayout.WEST)
                add(ensureStatusLabel(), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(6, 0)).apply {
                add(JButton("Clear").apply {
                    addActionListener { clear() }
                }, BorderLayout.WEST)
                add(JPanel(BorderLayout(6, 0)).apply {
                    add(ensureStopButton(), BorderLayout.WEST)
                    add(ensurePlacementButton(), BorderLayout.EAST)
                }, BorderLayout.EAST)
            }, BorderLayout.EAST)
        }

    private fun ensureStopButton(): JButton {
        stopButton?.let { return it }
        val button = JButton("Stop").apply {
            isEnabled = false
            toolTipText = "Stop the running AI Auto Scripting (Beta) process"
            addActionListener {
                isEnabled = false
                stopHandler?.run()
            }
        }
        stopButton = button
        updateStopButton()
        return button
    }

    private fun updateStopButton() {
        stopButton?.isEnabled = stopHandler != null && startedAt != null
    }

    private fun ensurePlacementButton(): JButton {
        placementButton?.let { return it }
        val button = JButton().apply {
            addActionListener {
                if (separateWindowMode) {
                    showDocked()
                } else {
                    showWindow()
                }
            }
        }
        placementButton = button
        updatePlacementButton()
        return button
    }

    private fun updatePlacementButton() {
        placementButton?.let {
            it.text = if (separateWindowMode) "Dock" else "Open Window"
            it.toolTipText = if (separateWindowMode) {
                "Show AI log at the bottom of the main BreakTest window"
            } else {
                "Show AI log in a separate window"
            }
        }
    }

    private fun ensureProgressBar(): JProgressBar {
        progressBar?.let { return it }
        val bar = JProgressBar().apply {
            isIndeterminate = false
            preferredSize = Dimension(90, 16)
            minimumSize = Dimension(90, 16)
        }
        progressBar = bar
        return bar
    }

    private fun ensureStatusLabel(): JLabel {
        statusLabel?.let { return it }
        val label = JLabel("Idle")
        statusLabel = label
        return label
    }

    private fun ensureTextArea(): JTextArea {
        textArea?.let { return it }
        val area = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            margin = Insets(6, 8, 6, 8)
        }
        textArea = area
        return area
    }

    private fun ensureChangeTable(): JTable {
        changeTable?.let { return it }
        val table = JTable(changeModel).apply {
            fillsViewportHeight = true
            autoCreateRowSorter = true
            rowHeight = 22
            toolTipText = "Double-click a change to jump to the matching test plan node"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(event: java.awt.event.MouseEvent) {
                    if (event.clickCount < 2) {
                        return
                    }
                    val viewRow = selectedRow.takeIf { it >= 0 } ?: return
                    val modelRow = convertRowIndexToModel(viewRow)
                    val entry = changeModel.entryAt(modelRow) ?: return
                    val path = entry.path ?: resolveChangePath(entry)
                    if (path != null) {
                        selectNode(path)
                    } else {
                        append("Could not find changed node in the current test plan: ${entry.nodeName}")
                    }
                }
            })
        }
        table.columnModel.getColumn(0).preferredWidth = 72
        table.columnModel.getColumn(1).preferredWidth = 130
        table.columnModel.getColumn(2).preferredWidth = 180
        table.columnModel.getColumn(3).preferredWidth = 260
        changeTable = table
        return table
    }

    private fun selectNode(path: TreePath) {
        val tree = GuiPackage.getInstance()?.mainFrame?.tree ?: return
        path.parentPath?.let { tree.expandPath(it) }
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        tree.requestFocusInWindow()
    }

    private fun resolveChangePath(entry: ChangeEntry): TreePath? {
        val gui = GuiPackage.getInstance() ?: return null
        val root = gui.treeModel.root as? JMeterTreeNode ?: return null
        val nodes = flattenNodes(root)
        if (nodes.isEmpty()) {
            return null
        }
        val references = nodeReferences(entry).filter { it.length >= 2 }
        for (reference in references) {
            val normalizedReference = normalizeNodeReference(reference)
            val exactPath = nodes.firstOrNull {
                normalizeNodeReference(treePathText(it)) == normalizedReference
            }
            if (exactPath != null) {
                return TreePath(exactPath.path)
            }
            val suffixPath = nodes.firstOrNull {
                normalizeNodeReference(treePathText(it)).endsWith(" / $normalizedReference")
            }
            if (suffixPath != null) {
                return TreePath(suffixPath.path)
            }
        }
        val best = nodes
            .mapNotNull { node ->
                val score = references.maxOfOrNull { scoreNodeMatch(node, it) } ?: 0
                if (score > 0) node to score else null
            }
            .maxWithOrNull(compareBy<Pair<JMeterTreeNode, Int>> { it.second }.thenByDescending { treePathText(it.first).length })
            ?.takeIf { it.second >= 45 }
            ?.first
        return best?.let { TreePath(it.path) }
    }

    private fun flattenNodes(root: JMeterTreeNode): List<JMeterTreeNode> {
        val nodes = mutableListOf<JMeterTreeNode>()
        fun visit(node: JMeterTreeNode) {
            nodes += node
            for (index in 0 until node.childCount) {
                val child = node.getChildAt(index)
                if (child is JMeterTreeNode) {
                    visit(child)
                }
            }
        }
        visit(root)
        return nodes
    }

    private fun nodeReferences(entry: ChangeEntry): List<String> {
        val raw = linkedSetOf<String>()
        listOf(entry.nodeName, entry.summary, entry.details).forEach { value ->
            extractNodeReferences(value).forEach(raw::add)
        }
        return raw.toList()
    }

    private fun extractNodeReferences(value: String): List<String> {
        val cleaned = value
            .replace('`', ' ')
            .replace('"', ' ')
            .replace('\'', ' ')
            .replace("TestPlan.user_defined_variables", "Test Plan")
            .trim()
        if (cleaned.isBlank()) {
            return emptyList()
        }
        val references = mutableListOf(cleaned)
        val slashReferences = Regex("""(?:^|[\s,(])(/[^\s,;)]{2,})""")
            .findAll(cleaned)
            .map { it.groupValues[1].trimEnd('.', ',', ';', ')') }
            .toList()
        references += slashReferences
        if (!cleaned.contains(" / ")) {
            cleaned.split(Regex("""\s+(?:and|or)\s+|,\s*|;\s*"""))
                .map { it.trim() }
                .filter { it.length >= 3 }
                .forEach(references::add)
        }
        return references.distinct()
    }

    private fun scoreNodeMatch(node: JMeterTreeNode, reference: String): Int {
        val normalizedReference = normalizeNodeReference(reference)
        if (normalizedReference.length < 2) {
            return 0
        }
        val name = normalizeNodeReference(node.testElement.name.orEmpty())
        val path = normalizeNodeReference(treePathText(node))
        return when {
            path == normalizedReference -> 100
            path.endsWith(" / $normalizedReference") -> 95
            name == normalizedReference -> 90
            normalizedReference.startsWith("/") && name == normalizedReference.substringAfterLast(" / ") -> 88
            normalizedReference.startsWith("/") && name.contains(normalizedReference) -> 82
            normalizedReference.length >= 4 && path.contains(normalizedReference) -> 70
            normalizedReference.length >= 6 && normalizedReference.contains(name) -> 55
            normalizedReference.length >= 6 && name.contains(normalizedReference) -> 50
            else -> 0
        }
    }

    private fun treePathText(node: JMeterTreeNode): String =
        node.path
            .filterIsInstance<JMeterTreeNode>()
            .joinToString(" / ") { it.testElement.name.orEmpty() }

    private fun normalizeNodeReference(value: String): String =
        value
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.', ',', ';')
            .lowercase()

    private fun updateStatus() {
        val start = startedAt ?: return
        val elapsed = Duration.between(start, Instant.now()).seconds
        ensureStatusLabel().text = "Running... ${elapsed}s elapsed"
    }

    private fun placeBesideMainFrame() {
        val dialog = dialog ?: return
        val mainFrame = GuiPackage.getInstance()?.mainFrame ?: return
        if (!mainFrame.isShowing) {
            return
        }
        val mainLocation = mainFrame.locationOnScreen
        val x = mainLocation.x + mainFrame.width - minOf(dialog.width, mainFrame.width / 2) - 24
        val y = mainLocation.y + 90
        dialog.setLocation(maxOf(mainLocation.x + 24, x), y)
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private fun String.compactForDisplay(): String =
        replace('\r', ' ')
            .split('\n')
            .joinToString("\n") { line ->
                if (line.length <= MAX_LINE_LENGTH) {
                    line
                } else {
                    line.take(MAX_LINE_LENGTH) + " ... [truncated ${line.length - MAX_LINE_LENGTH} chars]"
                }
            }

    private fun trimDocument(area: JTextArea) {
        val document = area.document
        val excess = document.length - MAX_DOCUMENT_LENGTH
        if (excess <= 0) {
            return
        }
        val text = document.getText(0, minOf(document.length, excess + 2_000))
        val removeUntil = text.indexOf('\n', excess).takeIf { it >= 0 }?.plus(1) ?: excess
        document.remove(0, removeUntil)
    }

    private data class ChangeEntry(
        val time: String,
        val type: String,
        val nodeName: String,
        val summary: String,
        val details: String,
        val path: TreePath?,
    )

    private class ChangeTableModel : AbstractTableModel() {
        private val columns = arrayOf("Time", "Change", "Node", "Summary", "Details")
        private val rows = mutableListOf<ChangeEntry>()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            when (columnIndex) {
                0 -> rows[rowIndex].time
                1 -> rows[rowIndex].type
                2 -> rows[rowIndex].nodeName
                3 -> rows[rowIndex].summary
                else -> rows[rowIndex].details
            }

        fun add(entry: ChangeEntry) {
            rows += entry
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        fun clear() {
            val last = rows.lastIndex
            rows.clear()
            if (last >= 0) {
                fireTableRowsDeleted(0, last)
            }
        }

        fun entryAt(row: Int): ChangeEntry? = rows.getOrNull(row)

        fun snapshot(): List<ChangeEntry> = rows.toList()
    }
}
