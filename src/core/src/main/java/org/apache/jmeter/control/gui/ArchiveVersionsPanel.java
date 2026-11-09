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

package org.apache.jmeter.control.gui;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Load;
import org.apache.jmeter.save.ArchiveVersions;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;

/** Browses saved archive history without changing the latest plan until Save. */
final class ArchiveVersionsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Saved", "Size (bytes)"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 1 ? Long.class : String.class;
        }
    };
    private final JTable table = new JTable(model);
    private final JButton open = new JButton("Open version");
    private final JButton delete = new JButton("Delete selected");
    private final JLabel status = new JLabel();
    private ArchiveVersions archive;
    private List<ArchiveVersions.Version> versions = List.of();
    private boolean busy;

    ArchiveVersionsPanel() {
        super(new BorderLayout(0, 8));
        add(new JLabel("Opening a version keeps the saved latest plan. Save to make the opened version latest."),
                BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setToolTipText("Version size excludes shared attachment content.");
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.add(open);
        actions.add(delete);
        actions.add(status);
        add(actions, BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(event -> updateButtons());
        open.addActionListener(event -> openVersion());
        delete.addActionListener(event -> deleteVersions());
        updateButtons();
    }

    void configure(TestPlan plan) {
        archive = plan == null ? null : plan.getArchiveVersions();
        refresh();
    }

    void refresh() {
        if (busy) {
            return;
        }
        model.setRowCount(0);
        try {
            versions = archive == null ? List.of() : archive.list();
            DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            for (ArchiveVersions.Version version : versions) {
                model.addRow(new Object[]{date.format(version.date()), version.size()});
            }
            status.setText(versions.isEmpty() ? "Previous saves will appear here." : versions.size() + " versions");
        } catch (Exception ex) {
            versions = List.of();
            status.setText("Cannot read versions: " + ex.getMessage());
        }
        updateButtons();
    }

    private void updateButtons() {
        open.setEnabled(!busy && table.getSelectedRowCount() == 1);
        delete.setEnabled(!busy && table.getSelectedRowCount() > 0);
    }

    private void openVersion() {
        if (JMeterUtils.isTestRunning()) {
            JMeterUtils.reportErrorToUser("Stop the running test before opening a version.");
            return;
        }
        GuiPackage gui = GuiPackage.getInstance();
        gui.updateCurrentNode();
        String name = versions.get(table.convertRowIndexToModel(table.getSelectedRow())).entry();
        ArchiveVersions selectedArchive = archive;
        ActionRouter.getInstance().doActionNow(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.CHECK_DIRTY));
        boolean saveEdits = gui.isDirty();
        String message = saveEdits
                ? "Save your current edits, then open this version? The saved plan will be kept when you save again."
                : "Open this version? The saved latest plan remains in the archive until you save.";
        if (JOptionPane.showConfirmDialog(this, message, "Open version", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) {
            return;
        }
        if (saveEdits) {
            ActionRouter.getInstance().doActionNow(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.SAVE));
            if (gui.isDirty()) {
                return;
            }
        }
        runTask(new SwingWorker<HashTree, Void>() {
            @Override
            protected HashTree doInBackground() throws Exception {
                return selectedArchive.open(name);
            }

            @Override
            protected void done() {
                try {
                    HashTree tree = get();
                    Load.insertLoadedTree(ActionEvent.ACTION_PERFORMED, tree, false);
                    gui.setConvertedPlanUnsaved(true);
                    gui.setDirty(true);
                    gui.updateCurrentGui();
                } catch (Exception ex) {
                    JMeterUtils.reportErrorToUser("Cannot open version: " + ex.getMessage());
                } finally {
                    finishTask();
                }
            }
        });
    }

    private void deleteVersions() {
        Set<String> names = new HashSet<>();
        for (int row : table.getSelectedRows()) {
            names.add(versions.get(table.convertRowIndexToModel(row)).entry());
        }
        if (JOptionPane.showConfirmDialog(this,
                "Permanently delete " + names.size() + " selected versions from the archive?",
                "Delete versions", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        ArchiveVersions selectedArchive = archive;
        runTask(new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                selectedArchive.delete(names);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    JMeterUtils.reportErrorToUser("Cannot delete versions: " + ex.getMessage());
                } finally {
                    finishTask();
                }
            }
        });
    }

    private void runTask(SwingWorker<?, ?> task) {
        busy = true;
        updateButtons();
        GuiPackage.getInstance().getMainFrame().showLoadingOverlay("Updating versions...");
        task.execute();
    }

    private void finishTask() {
        busy = false;
        GuiPackage.getInstance().getMainFrame().hideLoadingOverlay();
        refresh();
    }
}
