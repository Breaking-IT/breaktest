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

package org.apache.jmeter.gui.action;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.apache.jmeter.ai.gui.AiCliProcess;
import org.apache.jmeter.gui.action.AiAutoScriptingAction.AiThinkingLevel;
import org.apache.jmeter.gui.action.AiAutoScriptingAction.AiTool;

/**
 * The AI tool, thinking level and model pickers of the AI Auto Scripting dialog, for other AI
 * entry points such as the JSR223 Ask AI dialog. The last choice is kept for the session.
 */
public final class AiEngineChooser {
    private static AiTool lastTool;
    private static AiThinkingLevel lastThinkingLevel = AiThinkingLevel.DEFAULT;
    private static String lastModel = "";

    private final JComboBox<AiTool> aiTool = new JComboBox<>(AiAutoScriptingAction.aiToolChoices());
    private final JComboBox<AiThinkingLevel> thinkingLevel = new JComboBox<>();
    private final AiModelSelector modelSelector = new AiModelSelector();
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));

    public AiEngineChooser() {
        aiTool.setSelectedItem(lastTool == null ? AiAutoScriptingAction.defaultAiTool() : lastTool);
        AiAutoScriptingAction.bindEngineFields(aiTool, thinkingLevel, modelSelector);
        thinkingLevel.setSelectedItem(lastThinkingLevel);
        modelSelector.setSelectedModel(lastModel);
        JPanel fields = new JPanel(new GridLayout(1, 2, 12, 0));
        fields.add(AiAutoScriptingAction.compactComboPanel("AI tool", aiTool));
        fields.add(AiAutoScriptingAction.compactComboPanel("Thinking level", thinkingLevel));
        panel.add(fields, BorderLayout.NORTH);
        panel.add(modelSelector, BorderLayout.CENTER);
    }

    public JComponent component() {
        return panel;
    }

    /** Stops the model lookup, remembers the choice and returns it for a run off the EDT. */
    public Engine confirm() {
        modelSelector.cancelLookup();
        lastTool = (AiTool) aiTool.getSelectedItem();
        lastThinkingLevel = (AiThinkingLevel) thinkingLevel.getSelectedItem();
        lastModel = modelSelector.selectedModel();
        return new Engine(lastTool, lastThinkingLevel, lastModel);
    }

    public void cancel() {
        modelSelector.cancelLookup();
    }

    /** A chosen AI tool, thinking level and model that can run a one-off prompt. */
    public static final class Engine {
        private final AiTool tool;
        private final AiThinkingLevel thinkingLevel;
        private final String model;

        Engine(AiTool tool, AiThinkingLevel thinkingLevel, String model) {
            this.tool = tool;
            this.thinkingLevel = thinkingLevel;
            this.model = model;
        }

        public String displayName() {
            return tool.displayName();
        }

        public String description() {
            return AiEngineDescription.describe(tool.id(), tool.displayName(), thinkingLevel.value, model);
        }

        /**
         * Runs the prompt non-interactively in {@code workingDirectory}, streaming the agent's
         * output to the AI activity log.
         *
         * @return the process exit code
         */
        public int run(String prompt, File workingDirectory) throws IOException, InterruptedException {
            List<String> command = AiAutoScriptingAction.oneShotCommand(
                    tool, thinkingLevel, model, prompt, workingDirectory);
            AiCliProcess processCommand = AiCliProcess.prepare(command, AiAutoScriptingAction.promptStyle(tool));
            Process process;
            try {
                process = processCommand.start(workingDirectory);
            } catch (IOException ex) {
                throw new IOException(AiAutoScriptingAction.launchFailureMessage(tool, command, ex), ex);
            }
            processCommand.writePrompt(process);
            AiAutoScriptingAction.streamOutput(process.getInputStream(), tool);
            return process.waitFor();
        }
    }
}
