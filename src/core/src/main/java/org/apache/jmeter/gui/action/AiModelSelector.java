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
import java.awt.Dimension;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.apache.jmeter.util.JMeterUtils;

/** Per-dialog, asynchronous model picker. All UI access stays on the EDT. */
final class AiModelSelector extends JPanel {
    private static final String DEFAULT = JMeterUtils.getResString("ai_model_default");
    private final JComboBox<String> model = new JComboBox<>(new String[] { DEFAULT });
    private final JLabel status = new JLabel(" ");
    private final Map<String, AiModelCatalog.Result> cache = new HashMap<>();
    private final Map<String, String> selections = new HashMap<>();
    private SwingWorker<AiModelCatalog.Result, Void> worker;
    private String tool;
    private File directory;
    private int generation;
    private final BiFunction<String, File, AiModelCatalog.Result> loader;

    AiModelSelector() {
        this(AiModelCatalog::load);
    }

    AiModelSelector(BiFunction<String, File, AiModelCatalog.Result> loader) {
        super(new BorderLayout(0, 4));
        this.loader = loader;
        model.setEditable(true);
        model.setMaximumRowCount(14);
        // Long provider/model IDs must not force the dialog wider.
        model.setPrototypeDisplayValue("openrouter/provider/model-name");
        model.setMinimumSize(new Dimension(160, model.getPreferredSize().height));
        model.setToolTipText(JMeterUtils.getResString("ai_model_tooltip"));
        JButton refresh = new JButton(JMeterUtils.getResString("ai_model_refresh"));
        refresh.addActionListener(event -> load(true));
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.add(model, BorderLayout.CENTER);
        controls.add(refresh, BorderLayout.EAST);
        JLabel label = new JLabel(JMeterUtils.getResString("ai_model_label"));
        label.setLabelFor(model);
        status.setFont(status.getFont().deriveFont(Math.max(10f, status.getFont().getSize2D() - 1)));
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.add(label, BorderLayout.WEST);
        heading.add(status, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);
        add(controls, BorderLayout.CENTER);
    }

    String selectedModel() {
        Object value = model.getEditor().getItem();
        String text = value == null ? "" : value.toString().trim();
        return DEFAULT.equals(text) ? "" : text;
    }

    void setSelectedModel(String selected) {
        model.setSelectedItem(selected == null || selected.isBlank() ? DEFAULT : selected);
    }

    void selectTool(String selectedTool, File workingDirectory) {
        if (tool != null) {
            selections.put(tool, selectedModel());
        }
        tool = selectedTool;
        directory = workingDirectory;
        model.setModel(new DefaultComboBoxModel<>(new String[] { DEFAULT }));
        model.setSelectedItem(selections.getOrDefault(tool, "").isBlank() ? DEFAULT : selections.get(tool));
        load(false);
    }

    private void load(boolean refresh) {
        cancelLookup();
        if (tool == null) {
            return;
        }
        if (!refresh && cache.containsKey(tool)) {
            display(cache.get(tool));
            return;
        }
        setStatus(JMeterUtils.getResString("ai_model_loading"), JMeterUtils.getResString("ai_model_loading_help"));
        int requestGeneration = generation;
        String requestedTool = tool;
        File requestedDirectory = directory;
        worker = new SwingWorker<>() {
            @Override
            protected AiModelCatalog.Result doInBackground() {
                return loader.apply(requestedTool, requestedDirectory);
            }

            @Override
            protected void done() {
                if (isCancelled() || requestGeneration != generation) {
                    return;
                }
                try {
                    AiModelCatalog.Result result = get();
                    cache.put(requestedTool, result);
                    display(result);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | CancellationException ex) {
                    setStatus(JMeterUtils.getResString("ai_model_unavailable"), JMeterUtils.getResString("ai_model_unavailable_help"));
                }
            }
        };
        worker.execute();
    }

    private void display(AiModelCatalog.Result result) {
        String selected = selectedModel();
        DefaultComboBoxModel<String> choices = new DefaultComboBoxModel<>();
        choices.addElement(DEFAULT);
        result.models().forEach(choices::addElement);
        model.setModel(choices);
        model.setSelectedItem(selected.isBlank() ? DEFAULT : selected);
        String summary;
        if (result.status().unavailable()) {
            summary = JMeterUtils.getResString("ai_model_unavailable");
        } else if (result.models().isEmpty()) {
            summary = JMeterUtils.getResString("ai_model_empty");
        } else {
            summary = java.text.MessageFormat.format(JMeterUtils.getResString("ai_model_count"), result.models().size());
        }
        setStatus(summary, result.status().description());
    }

    private void setStatus(String summary, String detail) {
        status.setText(summary);
        status.setToolTipText(detail);
    }

    void cancelLookup() {
        generation++;
        if (worker != null) {
            worker.cancel(true);
        }
    }
}
