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
import org.apache.jmeter.gui.util.EscapeDialog
import java.awt.BorderLayout
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
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

public object AiAutoScriptingLogWindow {
    private const val MAX_LINE_LENGTH = 2_000
    private const val MAX_DOCUMENT_LENGTH = 120_000
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var dialog: EscapeDialog? = null
    private var textArea: JTextArea? = null
    private var statusLabel: JLabel? = null
    private var progressBar: JProgressBar? = null
    private var startedAt: Instant? = null
    private val timer = Timer(1_000) { updateStatus() }

    @JvmStatic
    public fun showWindow() {
        onEdt {
            ensureDialog().isVisible = true
            placeBesideMainFrame()
        }
    }

    @JvmStatic
    public fun append(message: String) {
        val line = "${LocalTime.now(ZoneId.systemDefault()).format(timeFormat)} ${message.compactForDisplay()}\n"
        onEdt {
            val area = ensureTextArea()
            ensureDialog().isVisible = true
            area.append(line)
            trimDocument(area)
            area.caretPosition = area.document.length
        }
    }

    @JvmStatic
    public fun startRun() {
        onEdt {
            startedAt = Instant.now()
            ensureDialog().isVisible = true
            ensureProgressBar().isIndeterminate = true
            ensureStatusLabel().text = "Running..."
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
        }
    }

    @JvmStatic
    public fun clear() {
        onEdt {
            ensureTextArea().text = ""
        }
    }

    private fun ensureDialog(): EscapeDialog {
        dialog?.let { return it }
        val mainFrame = GuiPackage.getInstance()?.mainFrame
        val newDialog = EscapeDialog(mainFrame, "AI Auto Scripting", false).apply {
            contentPane.layout = BorderLayout()
            contentPane.add(toolbar(), BorderLayout.NORTH)
            contentPane.add(
                JScrollPane(
                    ensureTextArea(),
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                ),
                BorderLayout.CENTER,
            )
            minimumSize = Dimension(460, 280)
            size = Dimension(620, 520)
        }
        dialog = newDialog
        return newDialog
    }

    private fun toolbar(): JPanel =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            add(JPanel(BorderLayout(8, 0)).apply {
                add(ensureProgressBar(), BorderLayout.WEST)
                add(ensureStatusLabel(), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JButton("Clear").apply {
                addActionListener { clear() }
            }, BorderLayout.EAST)
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
}
