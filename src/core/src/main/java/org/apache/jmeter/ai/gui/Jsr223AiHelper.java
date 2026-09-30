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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultEditorKit;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AiEngineChooser;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.JSR223TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds an AI-assisted rewrite helper to JSR223 script editors, using the AI tool, thinking level
 * and model chosen in its dialog.
 */
public final class Jsr223AiHelper {
    private static final Logger log = LoggerFactory.getLogger(Jsr223AiHelper.class);
    private static final String MENU_ITEM_MARKER = "breaktest.jsr223.aiHelperInstalled"; // $NON-NLS-1$
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final String ASK_AI = "Ask AI"; // $NON-NLS-1$
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private Jsr223AiHelper() {
    }

    public static void install(
            JSyntaxTextArea textArea,
            String elementType,
            Supplier<String> languageSupplier) {
        if (Boolean.TRUE.equals(textArea.getClientProperty(MENU_ITEM_MARKER))) {
            return;
        }
        JPopupMenu popupMenu = textArea.getPopupMenu();
        if (popupMenu == null) {
            popupMenu = new JPopupMenu();
            textArea.setPopupMenu(popupMenu);
        }
        popupMenu.addSeparator();
        JMenuItem aiHelper = new JMenuItem(ASK_AI);
        aiHelper.addActionListener(event -> openDialog(textArea, elementType, languageSupplier));
        popupMenu.add(aiHelper);
        textArea.putClientProperty(MENU_ITEM_MARKER, true);
    }

    /** Creates a button that opens the same AI Helper dialog as the editor's context menu. */
    public static JButton createAskAiButton(
            JSyntaxTextArea textArea,
            String elementType,
            Supplier<String> languageSupplier) {
        JButton askAi = new JButton(ASK_AI);
        askAi.setToolTipText("Ask AI to change this JSR223 script"); // $NON-NLS-1$
        askAi.addActionListener(event -> openDialog(textArea, elementType, languageSupplier));
        return askAi;
    }

    private static void openDialog(
            JSyntaxTextArea textArea,
            String elementType,
            Supplier<String> languageSupplier) {
        if (RUNNING.get()) {
            AiAutoScriptingLogWindow.append("JSR223 AI Helper is already running.");
            AiAutoScriptingLogWindow.showLog();
            return;
        }
        JMeterTreeNode target = captureTarget();
        if (target == null) {
            return;
        }
        JTextArea request = new JTextArea(7, 56);
        request.setLineWrap(true);
        request.setWrapStyleWord(true);
        installShiftEnterNewLine(request);

        AiEngineChooser engineChooser = new AiEngineChooser();
        JPanel requestPanel = new JPanel(new BorderLayout(0, 8));
        requestPanel.add(new JLabel("What should AI change in this JSR223 script?"), BorderLayout.NORTH);
        requestPanel.add(
                new JScrollPane(
                        request,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                ),
                BorderLayout.CENTER
        );
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.setPreferredSize(new Dimension(620, 340));
        panel.add(engineChooser.component(), BorderLayout.NORTH);
        panel.add(requestPanel, BorderLayout.CENTER);

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
            engineChooser.cancel();
            return;
        }
        AiEngineChooser.Engine engine = engineChooser.confirm();
        ScriptContext context = captureContext(textArea, elementType, languageSupplier.get(), request.getText().trim());
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        AiAutoScriptingLogWindow.showLog();
        AiAutoScriptingLogWindow.startRun();
        AiAutoScriptingLogWindow.append("JSR223 AI Helper: generating script update.");
        AiAutoScriptingLogWindow.append(engine.description());
        Thread worker = new Thread(
                () -> runAi(engine, target, context),
                "BreakTest JSR223 AI Helper"
        );
        worker.setDaemon(true);
        worker.start();
    }

    private static void installShiftEnterNewLine(JTextArea textArea) {
        InputMap inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED);
        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                DefaultEditorKit.insertBreakAction
        );
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

    private static void runAi(AiEngineChooser.Engine engine, JMeterTreeNode target, ScriptContext context) {
        Path workingDirectory = null;
        try {
            // A private folder holding only the script works the same for every AI CLI: the agent
            // edits a real file instead of returning text. This is not a sandbox; the CLIs run with
            // their usual permissions, so the prompt limits the agent to this one file.
            workingDirectory = Files.createTempDirectory("breaktest-jsr223-ai-");
            Path scriptFile = workingDirectory.resolve(scriptFileName(context.language()));
            Files.writeString(scriptFile, context.script(), StandardCharsets.UTF_8);
            int exitCode = engine.run(prompt(context, scriptFile.getFileName().toString()),
                    workingDirectory.toFile(), Duration.ofSeconds(JMeterUtils.getPropDefault(
                            "breaktest.jsr223.ai.timeout_seconds", DEFAULT_TIMEOUT_SECONDS))); // $NON-NLS-1$
            if (exitCode != 0) {
                AiAutoScriptingLogWindow.append(engine.displayName() + " exited with code " + exitCode + ".");
                AiAutoScriptingLogWindow.finishRun("JSR223 helper failed");
                return;
            }
            String updatedScript = stripCodeFence(Files.readString(scriptFile, StandardCharsets.UTF_8));
            if (updatedScript.isBlank()) {
                AiAutoScriptingLogWindow.append("JSR223 AI Helper returned an empty script; no changes applied.");
                AiAutoScriptingLogWindow.finishRun("No script returned");
                return;
            }
            if (updatedScript.equals(context.script())) {
                AiAutoScriptingLogWindow.append("JSR223 AI Helper did not change the script.");
                AiAutoScriptingLogWindow.finishRun("No changes");
                return;
            }
            SwingUtilities.invokeAndWait(() -> {
                if (applyScript(target, updatedScript)) {
                    AiAutoScriptingLogWindow.append("JSR223 AI Helper applied the script update to " + target.getName() + ".");
                    AiAutoScriptingLogWindow.finishRun("JSR223 helper finished");
                } else {
                    AiAutoScriptingLogWindow.append("JSR223 AI Helper's original element was removed; no changes applied.");
                    AiAutoScriptingLogWindow.finishRun("Original element removed");
                }
            });
        } catch (CancellationException ex) {
            AiAutoScriptingLogWindow.append("JSR223 AI Helper was stopped; no changes applied.");
            AiAutoScriptingLogWindow.finishRun("Stopped");
        } catch (Exception ex) {
            log.warn("JSR223 AI Helper failed", ex);
            AiAutoScriptingLogWindow.append("JSR223 AI Helper failed: " + ex.getMessage());
            AiAutoScriptingLogWindow.finishRun("JSR223 helper failed");
        } finally {
            RUNNING.set(false);
            deleteRecursively(workingDirectory);
        }
    }

    static String scriptFileName(String language) {
        String lower = language.toLowerCase(Locale.ROOT);
        if (lower.contains("groovy")) {
            return "script.groovy"; // $NON-NLS-1$
        }
        if (lower.contains("javascript") || lower.equals("js") || lower.contains("nashorn") || lower.contains("graal")) {
            return "script.js"; // $NON-NLS-1$
        }
        if (lower.contains("java") || lower.contains("beanshell") || lower.contains("bsh")) {
            return "script.java"; // $NON-NLS-1$
        }
        return "script.txt"; // $NON-NLS-1$
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.debug("Could not delete temporary JSR223 AI file {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.debug("Could not clean up temporary JSR223 AI folder {}", directory, ex);
        }
    }

    static String prompt(ScriptContext context, String scriptFileName) {
        return """
                You are editing a BreakTest/JMeter JSR223 script.

                The current script is in the file %s in your working directory. Edit that file in place so it \
                contains the complete updated script. Do not create, rename, or modify any other file, and do not \
                add Markdown fences to the file. Do not use MCP servers, BreakTest tools, or other tools to change \
                the BreakTest test plan; only edit this script file.

                Rules:
                - Preserve existing behavior, if any, unless the user request requires a change.
                - An empty script is a valid starting point. Create the requested script without asking \
                for existing behavior or treating the empty file as a blocker.
                - For unspecified implementation details such as an output variable name, choose a sensible \
                default (for example, vars.put("iban", generatedIban) for an IBAN) and briefly state it \
                in your final response. Ask a question only if the request cannot be implemented safely \
                without the answer.
                - Prefer Groovy-compatible code when the language is groovy.
                - JMeter variables are available as vars; use vars.get("name") and vars.put("name", value).
                - JMeter runtime objects such as ctx, log, sampler, prev, props, and Parameters may be available.
                - If the user selected text, treat it as the main edit target, but keep the rest of the script.
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
                """.formatted(
                scriptFileName,
                context.elementType(),
                context.language().toLowerCase(Locale.ROOT),
                context.request(),
                context.selectionStart(),
                context.selectionEnd(),
                context.selectedText().isBlank() ? "(no selected text)" : context.selectedText()
        );
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

    static JMeterTreeNode captureTarget() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || !(gui.getCurrentElement() instanceof JSR223TestElement)) {
            return null;
        }
        return gui.getCurrentNode();
    }

    /** Applies the result on the EDT to the initiating node, never to a reused editor. */
    static boolean applyScript(JMeterTreeNode target, String updatedScript) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || target.getRoot() != gui.getTreeModel().getRoot()
                || !(target.getTestElement() instanceof JSR223TestElement element)) {
            return false;
        }
        // Flush any visible edits before changing the model so they cannot overwrite the result.
        gui.updateCurrentNode();
        element.setProperty("script", updatedScript); // $NON-NLS-1$
        element.setScript(updatedScript);
        if (gui.getCurrentNode() == target) {
            gui.refreshCurrentGui();
        }
        target.nameChanged();
        gui.setDirty(true);
        return true;
    }

    record ScriptContext(
            String script,
            int selectionStart,
            int selectionEnd,
            String selectedText,
            String elementType,
            String language,
            String request) {
    }
}
