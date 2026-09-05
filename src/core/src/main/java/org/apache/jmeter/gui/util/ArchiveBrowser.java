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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.gui.GuiPackage;
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
        HashTree tree = new HashTree();
        GuiPackage.getInstance().getTreeModel().getNodesOfType(TestElement.class)
                .forEach(node -> tree.add(node.getTestElement()));
        return new TreeMap<>(SaveService.collectArchiveReferences(tree));
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
            entries().forEach((entry, checksum) -> model.addRow(new Object[] {entry,
                    JmxArchiveEntryStore.find(entry, checksum).map(bytes -> Integer.toString(bytes.length)).orElse("?")}));
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
            for (java.io.File file : chooser.getSelectedFiles()) {
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
                byte[] bytes = JmxArchiveEntryStore.find(entry, entries().get(entry))
                        .orElseThrow(() -> new IOException("Archive file content is unavailable: " + entry));
                Files.write(destination, bytes);
            } catch (IOException | RuntimeException ex) {
                showError(dialog, ex);
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
        JButton close = new JButton(JMeterUtils.getResString("close"));
        close.addActionListener(event -> dialog.dispose());
        buttons.add(add);
        buttons.add(export);
        buttons.add(reference);
        buttons.add(close);
        dialog.add(new JLabel(JMeterUtils.getResString("archive_help")), BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(800, 460);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static void showError(Component parent, Exception error) {
        JOptionPane.showMessageDialog(parent, error.getMessage(), JMeterUtils.getResString("archive_browser"),
                JOptionPane.ERROR_MESSAGE);
    }
}
