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

package org.apache.jmeter.gui.util;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.save.ArchiveCleanup;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;

/** Browses attachments in the current test plan, including unsaved imports. */
public final class ArchiveBrowser {
    private ArchiveBrowser() {
    }

    private static Map<String, String> entries() {
        HashTree tree = testElementTree(GuiPackage.getInstance().getTreeModel().getTestPlan());
        return new TreeMap<>(SaveService.collectArchiveReferences(tree));
    }

    private static Map<String, byte[]> archiveContents() {
        Map<String, byte[]> contents = new TreeMap<>();
        entries().forEach((entry, checksum) -> JmxArchiveEntryStore.findBundle(entry, checksum)
                .ifPresent(contents::putAll));
        return contents;
    }

    public static String chooseFile(Component parent) {
        Object[] files = entries().keySet().stream().filter(name -> name.startsWith("files/")).toArray();
        if (files.length == 0) {
            JOptionPane.showMessageDialog(parent, JMeterUtils.getResString("archive_no_files"));
            return null;
        }
        return (String) JOptionPane.showInputDialog(parent, JMeterUtils.getResString("archive_choose_file"),
                JMeterUtils.getResString("archive_browser"), JOptionPane.PLAIN_MESSAGE, null, files, files[0]);
    }

    public static void show(Component parent) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                JMeterUtils.getResString("archive_browser"), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        DefaultTableModel model = new DefaultTableModel(
                new String[] {JMeterUtils.getResString("archive_path"), JMeterUtils.getResString("archive_size")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        Runnable refresh = () -> {
            model.setRowCount(0);
            Map<String, byte[]> contents = archiveContents();
            contents.forEach((entry, bytes) -> model.addRow(new Object[] {entry, bytes.length}));
            entries().keySet().stream().filter(entry -> !contents.containsKey(entry)).forEach(entry ->
                    model.addRow(new Object[] {entry, JMeterUtils.getResString("archive_file_unavailable")}));
        };
        refresh.run();
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton add = new JButton(JMeterUtils.getResString("archive_add"));
        add.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            for (File file : chooser.getSelectedFiles()) {
                try {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String entry = ArchiveFiles.entryName(file.getName());
                    String existing = entries().get(entry);
                    if (existing != null && !existing.equals(ArchiveFiles.checksum(bytes))) {
                        throw new IOException("A different file already uses " + entry + ". Use a unique filename.");
                    }
                    ArchiveFiles.put(ArchiveFiles.currentPlan(), entry, bytes, false);
                    GuiPackage.getInstance().setDirty(true);
                } catch (IOException | RuntimeException ex) {
                    showError(dialog, ex);
                }
            }
            refresh.run();
        });
        JButton export = new JButton(JMeterUtils.getResString("archive_export"));
        export.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            String entry = model.getValueAt(row, 0).toString();
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(Path.of(entry).getFileName().toFile());
            if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path destination = chooser.getSelectedFile().toPath();
            if (Files.exists(destination) && JOptionPane.showConfirmDialog(dialog,
                    JMeterUtils.getResString("archive_overwrite"), JMeterUtils.getResString("archive_export"),
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                byte[] bytes = archiveContents().get(entry);
                if (bytes == null) {
                    throw new IOException("Archive file content is unavailable: " + entry);
                }
                Files.write(destination, bytes);
            } catch (IOException | RuntimeException ex) {
                showError(dialog, ex);
            }
        });
        JButton delete = new JButton(JMeterUtils.getResString("delete"));
        delete.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            String entry = model.getValueAt(row, 0).toString();
            if (!entry.startsWith("files/")) {
                JOptionPane.showMessageDialog(dialog, JMeterUtils.getResString("archive_delete_recording"));
                return;
            }
            if (JOptionPane.showConfirmDialog(dialog,
                    MessageFormat.format(JMeterUtils.getResString("archive_delete_confirm"), entry),
                    JMeterUtils.getResString("delete"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                ArchiveFiles.remove(ArchiveFiles.currentPlan(), entry);
                GuiPackage.getInstance().setDirty(true);
                refresh.run();
            }
        });
        JButton reference = new JButton(JMeterUtils.getResString("archive_copy_reference"));
        reference.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                String entry = model.getValueAt(row, 0).toString();
                if (entry.startsWith("files/")) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                            new StringSelection("${__archiveFile(" + entry.substring("files/".length()) + ")}"), null);
                }
            }
        });
        JButton cleanup = new JButton(JMeterUtils.getResString("archive_cleanup"));
        cleanup.addActionListener(event -> cleanRecordings(dialog, refresh));
        JButton close = new JButton(JMeterUtils.getResString("close"));
        close.addActionListener(event -> dialog.dispose());
        buttons.add(add);
        buttons.add(export);
        buttons.add(delete);
        buttons.add(reference);
        buttons.add(cleanup);
        buttons.add(close);
        dialog.add(new JLabel(JMeterUtils.getResString("archive_help")), BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(800, 460);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static HashTree testElementTree(HashTree source) {
        HashTree tree = new HashTree();
        for (Object node : source.list()) {
            tree.add(((JMeterTreeNode) node).getTestElement(), testElementTree(source.getTree(node)));
        }
        return tree;
    }

    private static void cleanRecordings(JDialog parent, Runnable refresh) {
        String title = JMeterUtils.getResString("archive_cleanup");
        JComboBox<String> mode = new JComboBox<>(new String[] {
                JMeterUtils.getResString("archive_keep_recordings"),
                JMeterUtils.getResString("archive_omit_static_bodies"),
                JMeterUtils.getResString("archive_omit_statics"),
                JMeterUtils.getResString("archive_omit_recordings")});
        RecordingStorageMode[] modes = {RecordingStorageMode.ALL, RecordingStorageMode.OMIT_STATIC_BODIES,
                RecordingStorageMode.OMIT_STATICS, RecordingStorageMode.NONE};
        JCheckBox orphans = new JCheckBox(JMeterUtils.getResString("archive_remove_orphans"));
        JPanel options = new JPanel(new BorderLayout(5, 5));
        options.add(mode, BorderLayout.NORTH);
        options.add(orphans, BorderLayout.CENTER);
        options.add(new JLabel(JMeterUtils.getResString("archive_cleanup_help")), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(parent, options, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        RecordingStorageMode selected = modes[mode.getSelectedIndex()];
        boolean removeOrphans = orphans.isSelected();
        if (selected == RecordingStorageMode.ALL && !removeOrphans) {
            return;
        }
        GuiPackage gui = GuiPackage.getInstance();
        gui.updateCurrentNode();
        HashTree tree = testElementTree(gui.getTreeModel().getTestPlan());
        JDialog progress = new JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progress.add(new JLabel(JMeterUtils.getResString("archive_cleanup_calculating")));
        progress.setSize(400, 100);
        progress.setLocationRelativeTo(parent);
        new SwingWorker<ArchiveCleanup.Prepared, Void>() {
            @Override
            protected ArchiveCleanup.Prepared doInBackground() throws IOException {
                return ArchiveCleanup.prepare(tree, selected, removeOrphans);
            }

            @Override
            protected void done() {
                progress.dispose();
                try {
                    ArchiveCleanup.Prepared prepared = get();
                    String summary = MessageFormat.format(JMeterUtils.getResString("archive_cleanup_summary"),
                            prepared.removedEntries(), prepared.bytesRemoved(), prepared.retainedExchanges());
                    if (prepared.unlinkedRecordings() > 0) {
                        summary += "\n" + MessageFormat.format(JMeterUtils.getResString("archive_cleanup_unlinked"),
                                prepared.unlinkedRecordings());
                    }
                    if (JOptionPane.showConfirmDialog(parent, summary, title, JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                        prepared.apply();
                        gui.setDirty(true);
                        gui.refreshCurrentGui();
                        refresh.run();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError(parent, ex);
                } catch (ExecutionException | RuntimeException ex) {
                    showError(parent, ex);
                }
            }
        }.execute();
        progress.setVisible(true);
    }

    private static void showError(Component parent, Exception error) {
        JOptionPane.showMessageDialog(parent, error.getMessage(), JMeterUtils.getResString("archive_browser"),
                JOptionPane.ERROR_MESSAGE);
    }
}
