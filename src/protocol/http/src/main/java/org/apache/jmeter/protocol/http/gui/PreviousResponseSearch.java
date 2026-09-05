/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jmeter.protocol.http.gui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

/** Literal parameter-value lookup in earlier recorded HTTP responses. */
final class PreviousResponseSearch {
    private PreviousResponseSearch() {
    }

    record Candidate(JMeterTreeNode target, JMeterTreeNode snapshot, String path) { }

    record Hit(Candidate candidate, int offset, String context) { }

    // Take detached copies on the EDT so recording I/O can run without reading a changing GUI tree.
    static List<Candidate> previousSamplers(JMeterTreeNode current) {
        List<Candidate> candidates = new ArrayList<>();
        var nodes = ((JMeterTreeNode) current.getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) nodes.nextElement();
            if (node == current) {
                break;
            }
            if (!(node.getTestElement() instanceof HTTPSamplerBase)) {
                continue;
            }
            JMeterTreeNode snapshot = null;
            List<String> names = new ArrayList<>();
            for (var ancestor : node.getPath()) {
                var original = (JMeterTreeNode) ancestor;
                var copy = new JMeterTreeNode((TestElement) original.getTestElement().clone(), null);
                if (snapshot != null) {
                    snapshot.add(copy);
                }
                snapshot = copy;
                names.add(original.getName());
            }
            candidates.add(new Candidate(node, snapshot, String.join(" / ", names)));
        }
        return candidates;
    }

    static List<Hit> findHits(Candidate candidate, String response, String value) {
        List<Hit> hits = new ArrayList<>();
        if (value.isEmpty()) {
            return hits;
        }
        for (int offset = response.indexOf(value); offset >= 0; offset = response.indexOf(value, offset + 1)) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            String context = response.substring(Math.max(0, offset - 40),
                    Math.min(response.length(), offset + Math.min(value.length(), 100) + 40))
                    .replace('\r', ' ').replace('\n', ' ');
            hits.add(new Hit(candidate, offset, context));
        }
        return hits;
    }

    static void search(Component owner, String value) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || value == null || value.isEmpty()
                || !(gui.getCurrentNode().getTestElement() instanceof HTTPSamplerBase)) {
            return;
        }
        gui.updateCurrentNode();
        List<Candidate> candidates = previousSamplers(gui.getCurrentNode());
        Path plan = gui.getTestPlanFile() == null || gui.getTestPlanFile().isEmpty()
                ? null : Path.of(gui.getTestPlanFile());
        String title = JMeterUtils.getResString("http_parameter_search_responses");
        JOptionPane progress = new JOptionPane(JMeterUtils.getResString("http_parameter_search_running"),
                JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                new Object[] {JMeterUtils.getResString("cancel")});
        JDialog dialog = progress.createDialog(owner, title);
        SwingWorker<List<Hit>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<Hit> doInBackground() {
                List<Hit> hits = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    if (isCancelled()) {
                        break;
                    }
                    var resolution = RecordedHarExchangeResolver.resolveFor(candidate.snapshot(), plan);
                    if (resolution.status() == RecordedHarExchangeResolver.Status.FOUND) {
                        hits.addAll(findHits(candidate, resolution.responseText(), value));
                    }
                }
                return hits;
            }

            @Override
            protected void done() {
                dialog.dispose();
                if (isCancelled()) {
                    return;
                }
                try {
                    showHits(owner, gui, get(), value, title);
                } catch (CancellationException ignored) {
                    // The user closed the progress dialog.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    JMeterUtils.reportErrorToUser(JMeterUtils.getResString("http_parameter_search_failed"));
                }
            }
        };
        worker.execute();
        dialog.setVisible(true);
        if (!worker.isDone()) {
            worker.cancel(true);
        }
        dialog.dispose();
    }

    private static void showHits(Component owner, GuiPackage gui, List<Hit> hits, String value, String title) {
        if (hits.isEmpty()) {
            JOptionPane.showMessageDialog(owner, JMeterUtils.getResString("http_parameter_search_no_hits"),
                    title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Hit selected = hits.get(0);
        if (hits.size() > 1) {
            DefaultTableModel model = new DefaultTableModel(new Object[] {
                    JMeterUtils.getResString("http_parameter_search_path"),
                    JMeterUtils.getResString("http_parameter_search_offset"),
                    JMeterUtils.getResString("http_parameter_search_context")}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            for (Hit hit : hits) {
                model.addRow(new Object[] {hit.candidate().path(), hit.offset() + 1, hit.context()});
            }
            JTable table = new JTable(model);
            DefaultTableCellRenderer textRenderer = new DefaultTableCellRenderer();
            textRenderer.putClientProperty("html.disable", true);
            table.setDefaultRenderer(Object.class, textRenderer);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setRowSelectionInterval(0, 0);
            table.getColumnModel().getColumn(0).setPreferredWidth(350);
            table.getColumnModel().getColumn(1).setMaxWidth(90);
            table.getColumnModel().getColumn(2).setPreferredWidth(450);
            table.setPreferredScrollableViewportSize(new Dimension(900, 320));
            if (JOptionPane.showConfirmDialog(owner, new JScrollPane(table), title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION
                    || table.getSelectedRow() < 0) {
                return;
            }
            selected = hits.get(table.convertRowIndexToModel(table.getSelectedRow()));
        }
        Hit hit = selected;
        TreePath path = new TreePath(hit.candidate().target().getPath());
        gui.getTreeListener().setSelectionPathWithoutEdit(path);
        gui.getTreeListener().getJTree().scrollPathToVisible(path);
        ActionRouter.getInstance().doActionNow(new ActionEvent(owner, ActionEvent.ACTION_PERFORMED, ActionNames.EDIT));
        if (gui.getCurrentGui() instanceof HttpTestSampleGui samplerGui) {
            SwingUtilities.invokeLater(() -> samplerGui.selectRecordedResponseText(hit.offset(), value));
        }
    }
}
