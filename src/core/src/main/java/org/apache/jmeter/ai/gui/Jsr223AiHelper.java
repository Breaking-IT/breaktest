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

package org.apache.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds a small Codex-assisted rewrite helper to JSR223 script editors.
 */
public final class Jsr223AiHelper {
    private static final Logger log = LoggerFactory.getLogger(Jsr223AiHelper.class);
    private static final String MENU_ITEM_MARKER = "breaktest.jsr223.aiHelperInstalled"; // $NON-NLS-1$
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private Jsr223AiHelper() {
    }

    public static void install(
            JSyntaxTextArea textArea,
            String elementType,
            Supplier<String> languageSupplier,
            Runnable changedCallback) {
        if (Boolean.TRUE.equals(textArea.getClientProperty(MENU_ITEM_MARKER))) {
            return;
        }
        JPopupMenu popupMenu = textArea.getPopupMenu();
        if (popupMenu == null) {
            popupMenu = new JPopupMenu();
            textArea.setPopupMenu(popupMenu);
        }
        popupMenu.addSeparator();
        JMenuItem aiHelper = new JMenuItem("AI Helper");
        aiHelper.addActionListener(event -> openDialog(textArea, elementType, languageSupplier, changedCallback));
        popupMenu.add(aiHelper);
        textArea.putClientProperty(MENU_ITEM_MARKER, true);
    }

    private static void openDialog(
            JSyntaxTextArea textArea,
            String elementType,
            Supplier<String> languageSupplier,
            Runnable changedCallback) {
        if (RUNNING.get()) {
            AiAutoScriptingLogWindow.append("JSR223 AI Helper is already running.");
            AiAutoScriptingLogWindow.showWindow();
            return;
        }
        JTextArea request = new JTextArea(7, 56);
        request.setLineWrap(true);
        request.setWrapStyleWord(true);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.setPreferredSize(new Dimension(620, 240));
        panel.add(new JLabel("What should AI change in this JSR223 script?"), BorderLayout.NORTH);
        panel.add(
                new JScrollPane(
                        request,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                ),
                BorderLayout.CENTER
        );

        int choice = JOptionPane.showOptionDialog(
                GuiPackage.getInstance() == null ? null : GuiPackage.getInstance().getMainFrame(),
                panel,
                "JSR223 AI Helper",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                new Object[] { "Update Script", "Cancel" },
                "Update Script"
        );
        if (choice != JOptionPane.OK_OPTION || request.getText().isBlank()) {
            return;
        }
        ScriptContext context = captureContext(textArea, elementType, languageSupplier.get(), request.getText().trim());
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        AiAutoScriptingLogWindow.showWindow();
        AiAutoScriptingLogWindow.startRun();
        AiAutoScriptingLogWindow.append("JSR223 AI Helper: generating script update.");
        Thread worker = new Thread(
                () -> runCodex(textArea, context, changedCallback),
                "BreakTest JSR223 AI Helper"
        );
        worker.setDaemon(true);
        worker.start();
    }

    private static ScriptContext captureContext(
            JSyntaxTextArea textArea,
            String elementType,
            String language,
            String request) {
        int selectionStart = textArea.getSelectionStart();
        int selectionEnd = textArea.getSelectionEnd();
        String selectedText = textArea.getSelectedText();
        return new ScriptContext(
                textArea.getText(),
                selectionStart,
                selectionEnd,
                selectedText == null ? "" : selectedText,
                elementType == null ? "JSR223 element" : elementType,
                language == null || language.isBlank() ? "groovy" : language,
                request
        );
    }

    private static void runCodex(JSyntaxTextArea textArea, ScriptContext context, Runnable changedCallback) {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("breaktest-jsr223-ai-", ".txt");
            List<String> command = codexCommand(context, outputFile);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(codexWorkingDirectory());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            process.getOutputStream().close();
            streamShortOutput(process);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                AiAutoScriptingLogWindow.append("JSR223 AI Helper exited with code " + exitCode + ".");
                AiAutoScriptingLogWindow.finishRun("JSR223 helper failed");
                return;
            }
            String updatedScript = stripCodeFence(Files.readString(outputFile, StandardCharsets.UTF_8));
            if (updatedScript.isBlank()) {
                AiAutoScriptingLogWindow.append("JSR223 AI Helper returned an empty script; no changes applied.");
                AiAutoScriptingLogWindow.finishRun("No script returned");
                return;
            }
            applyScript(textArea, updatedScript, changedCallback);
            AiAutoScriptingLogWindow.append("JSR223 AI Helper applied the script update.");
            AiAutoScriptingLogWindow.finishRun("JSR223 helper finished");
        } catch (Exception ex) {
            log.warn("JSR223 AI Helper failed", ex);
            AiAutoScriptingLogWindow.append("JSR223 AI Helper failed: " + ex.getMessage());
            AiAutoScriptingLogWindow.finishRun("JSR223 helper failed");
        } finally {
            RUNNING.set(false);
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException ex) {
                    log.debug("Could not delete temporary Codex output file {}", outputFile, ex);
                }
            }
        }
    }

    private static List<String> codexCommand(ScriptContext context, Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add(JMeterUtils.getPropDefault("breaktest.codex.command", "codex"));
        command.add("--ask-for-approval");
        command.add(JMeterUtils.getPropDefault("breaktest.codex.approval", "never"));
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--sandbox");
        command.add(JMeterUtils.getPropDefault("breaktest.codex.sandbox", "danger-full-access"));
        command.add("--cd");
        command.add(codexWorkingDirectory().getPath());
        command.add("-c");
        command.add("mcp_servers.breaktest.enabled=false");
        command.add("--output-last-message");
        command.add(outputFile.toString());
        String model = JMeterUtils.getProperty("breaktest.codex.model");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        command.add(prompt(context));
        return command;
    }

    private static File codexWorkingDirectory() {
        String configured = JMeterUtils.getProperty("breaktest.codex.cwd");
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        File jmeterHome = new File(JMeterUtils.getJMeterHome());
        if (jmeterHome.isDirectory()) {
            return jmeterHome;
        }
        return new File(".").getAbsoluteFile();
    }

    private static String prompt(ScriptContext context) {
        return """
                You are editing a BreakTest/JMeter JSR223 script.

                Return only the complete updated script. Do not include Markdown fences, explanations, comments about what changed, or surrounding text.

                Rules:
                - Preserve existing behavior unless the user request requires a change.
                - Prefer Groovy-compatible code when the language is groovy.
                - JMeter variables are available as vars; use vars.get("name") and vars.put("name", value).
                - JMeter runtime objects such as ctx, log, sampler, prev, props, and Parameters may be available.
                - If the user selected text, treat it as the main edit target, but still return the full script.
                - Avoid hard-coded dynamic values when a runtime expression is appropriate.

                Element type: %s
                Language: %s
                User request:
                %s

                Selection:
                - start: %d
                - end: %d
                - selected text:
                %s

                Current full script:
                %s
                """.formatted(
                context.elementType(),
                context.language().toLowerCase(Locale.ROOT),
                context.request(),
                context.selectionStart(),
                context.selectionEnd(),
                context.selectedText().isBlank() ? "(no selected text)" : context.selectedText(),
                context.script()
        );
    }

    private static void streamShortOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isBlank()
                        || trimmed.equals("codex")
                        || trimmed.equals("tokens used")
                        || trimmed.matches("[0-9]+(\\.[0-9]+)?")
                        || trimmed.startsWith("exec")
                        || trimmed.startsWith("succeeded in ")) {
                    continue;
                }
                if (trimmed.startsWith("ERROR") || trimmed.startsWith("Error") || trimmed.contains("error:")) {
                    AiAutoScriptingLogWindow.append("JSR223 AI Helper: " + trimmed);
                }
            }
        }
    }

    private static String stripCodeFence(String text) {
        String normalized = Objects.toString(text, "").replace("\r\n", "\n");
        String trimmed = normalized.strip();
        if (!trimmed.startsWith("```")) {
            return normalized;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).stripTrailing();
        }
        return normalized;
    }

    private static void applyScript(JSyntaxTextArea textArea, String updatedScript, Runnable changedCallback) {
        SwingUtilities.invokeLater(() -> {
            int caret = Math.min(textArea.getCaretPosition(), updatedScript.length());
            textArea.setText(updatedScript);
            textArea.setCaretPosition(caret);
            textArea.discardAllEdits();
            changedCallback.run();
            GuiPackage gui = GuiPackage.getInstance();
            if (gui != null) {
                try {
                    gui.updateCurrentNode();
                    gui.setDirty(true);
                } catch (RuntimeException ex) {
                    log.debug("Could not mark GUI dirty after JSR223 AI Helper update", ex);
                }
            }
        });
    }

    private record ScriptContext(
            String script,
            int selectionStart,
            int selectionEnd,
            String selectedText,
            String elementType,
            String language,
            String request) {
    }
}
