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
import javax.swing.table.DefaultTableCellRenderer;
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
        entries().forEach((entry, checksum) -> {
            if (!ArchiveFiles.currentPlan().getUnavailableArchiveFiles().contains(entry)) {
                JmxArchiveEntryStore.findBundle(entry, checksum).ifPresent(contents::putAll);
            }
        });
        return contents;
    }

    /** Immediate children only; directory paths keep their trailing slash. */
    static Map<String, Object> children(Map<String, Object> files, String directory) {
        Map<String, Object> children = new TreeMap<>();
        files.forEach((path, size) -> {
            if (!path.startsWith(directory) || path.equals(directory)) {
                return;
            }
            int separator = path.indexOf('/', directory.length());
            if (separator >= 0) {
                children.put(path.substring(0, separator + 1), "Folder");
            } else {
                children.put(path, size);
            }
        });
        return children;
    }

    private static Map<String, Object> browserEntries() throws IOException {
        Map<String, Object> files = new TreeMap<>();
        String saved = GuiPackage.getInstance().getTestPlanFile();
        if (saved != null && Files.isRegularFile(Path.of(saved))) {
            try (var zip = new java.util.zip.ZipFile(saved)) {
                zip.stream().filter(entry -> entry.getName().startsWith("versions/")
                        || entry.getName().equals(SaveService.TEST_PLAN_ZIP_ENTRY))
                        .forEach(entry -> files.put(entry.getName(), entry.getSize()));
            } catch (java.util.zip.ZipException ex) {
                // A plain XML plan has no internal archive directories yet.
            }
        }
        Map<String, byte[]> contents = archiveContents();
        contents.forEach((entry, bytes) -> files.put(entry, bytes.length));
        entries().keySet().stream().filter(entry -> !contents.containsKey(entry)).forEach(entry ->
                files.put(entry, JMeterUtils.getResString("archive_file_unavailable")));
        return files;
    }

    public static String chooseFile(Component parent) {
        return chooseFile(parent, null);
    }

    public static String chooseFile(Component parent, String currentEntry) {
        Object[] files = entries().keySet().stream()
                .filter(name -> name.startsWith("files/"))
                .map(name -> name.substring("files/".length()))
                .toArray();
        if (files.length == 0) {
            JOptionPane.showMessageDialog(parent, JMeterUtils.getResString("archive_no_files"));
            return null;
        }
        String current = currentEntry != null && currentEntry.startsWith("files/")
                ? currentEntry.substring("files/".length()) : currentEntry;
        Object initial = java.util.Arrays.asList(files).contains(current) ? current : files[0];
        String selected = (String) JOptionPane.showInputDialog(parent, JMeterUtils.getResString("archive_choose_file"),
                JMeterUtils.getResString("archive_browser"), JOptionPane.PLAIN_MESSAGE, null, files, initial);
        return selected == null ? null : "files/" + selected;
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
        var sizeColumn = table.getColumnModel().getColumn(1);
        sizeColumn.setPreferredWidth(120);
        sizeColumn.setMaxWidth(120);
        DefaultTableCellRenderer sizeRenderer = new DefaultTableCellRenderer();
        sizeRenderer.setHorizontalAlignment(JLabel.RIGHT);
        sizeColumn.setCellRenderer(sizeRenderer);
        String[] directory = {""};
        JLabel location = new JLabel("/");
        JButton up = new JButton("Up");
        Runnable refresh = () -> {
            model.setRowCount(0);
            try {
                children(browserEntries(), directory[0]).forEach((entry, size) ->
                        model.addRow(new Object[]{entry.substring(directory[0].length()), size}));
            } catch (IOException ex) {
                showError(dialog, ex);
            }
            location.setText("/" + directory[0]);
            up.setEnabled(!directory[0].isEmpty());
        };
        Runnable openFolder = () -> {
            int row = table.getSelectedRow();
            if (row >= 0 && model.getValueAt(row, 0).toString().endsWith("/")) {
                directory[0] += model.getValueAt(row, 0).toString();
                refresh.run();
            }
        };
        JButton open = new JButton("Open folder");
        open.addActionListener(event -> openFolder.run());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    openFolder.run();
                }
            }
        });
        up.addActionListener(event -> {
            String current = directory[0];
            directory[0] = current.substring(0, current.lastIndexOf('/', current.length() - 2) + 1);
            refresh.run();
        });
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
                    String entry = ArchiveFiles.entryName(directory[0].startsWith("files/")
                            ? directory[0] + file.getName() : file.getName());
                    String existing = entries().get(entry);
                    if (existing != null && !confirmReplacement(dialog, entry)) {
                        continue;
                    }
                    ArchiveFiles.put(ArchiveFiles.currentPlan(), entry, bytes, existing != null);
                    GuiPackage.getInstance().setDirty(true);
                } catch (IOException | RuntimeException ex) {
                    showError(dialog, ex);
                }
            }
            refresh.run();
        });
        JButton replace = new JButton(JMeterUtils.getResString("archive_replace"));
        replace.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            String entry = directory[0] + model.getValueAt(row, 0);
            if (!entry.startsWith("files/") || entry.endsWith("/")) {
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(JMeterUtils.getResString("archive_replace"));
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            try {
                byte[] bytes = Files.readAllBytes(chooser.getSelectedFile().toPath());
                if (confirmReplacement(dialog, entry)) {
                    ArchiveFiles.put(ArchiveFiles.currentPlan(), entry, bytes, true);
                    GuiPackage.getInstance().setDirty(true);
                    refresh.run();
                }
            } catch (IOException | RuntimeException ex) {
                showError(dialog, ex);
            }
        });
        JButton export = new JButton(JMeterUtils.getResString("archive_export"));
        export.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            String entry = directory[0] + model.getValueAt(row, 0).toString();
            if (entry.endsWith("/")) {
                return;
            }
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
                if (bytes == null && (entry.startsWith("versions/") || entry.equals(SaveService.TEST_PLAN_ZIP_ENTRY))) {
                    String saved = GuiPackage.getInstance().getTestPlanFile();
                    if (saved != null) {
                        bytes = SaveService.readArchiveEntry(new File(saved), entry).orElse(null);
                    }
                }
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
            String entry = directory[0] + model.getValueAt(row, 0).toString();
            if (!entry.startsWith("files/") || entry.endsWith("/")) {
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
                String entry = directory[0] + model.getValueAt(row, 0).toString();
                if (entry.startsWith("files/") && !entry.endsWith("/")) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                            new StringSelection("${__archiveFile(" + entry.substring("files/".length()) + ")}"), null);
                }
            }
        });
        JButton cleanup = new JButton(JMeterUtils.getResString("archive_cleanup"));
        cleanup.addActionListener(event -> cleanRecordings(dialog, refresh));
        JButton orphans = new JButton(JMeterUtils.getResString("archive_remove_orphans"));
        orphans.setToolTipText(JMeterUtils.getResString("archive_remove_orphans_help"));
        orphans.addActionListener(event -> cleanRecordings(dialog, refresh, RecordingStorageMode.ALL, true));
        JButton close = new JButton(JMeterUtils.getResString("close"));
        close.addActionListener(event -> dialog.dispose());
        Runnable updateActions = () -> {
            int row = table.getSelectedRow();
            boolean selected = row >= 0;
            String entry = selected ? directory[0] + model.getValueAt(row, 0) : "";
            boolean folder = entry.endsWith("/");
            open.setEnabled(selected && folder);
            export.setEnabled(selected && !folder);
            replace.setEnabled(selected && !folder && entry.startsWith("files/"));
            delete.setEnabled(selected && !folder && entry.startsWith("files/"));
            reference.setEnabled(selected && !folder && entry.startsWith("files/"));
        };
        table.getSelectionModel().addListSelectionListener(event -> updateActions.run());
        updateActions.run();
        buttons.add(add);
        buttons.add(replace);
        buttons.add(export);
        buttons.add(delete);
        buttons.add(reference);
        buttons.add(cleanup);
        buttons.add(orphans);
        buttons.add(close);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT));
        navigation.add(up);
        navigation.add(open);
        navigation.add(location);
        JButton history = new JButton(JMeterUtils.getResString("version_history"));
        history.addActionListener(event -> {
            dialog.dispose();
            org.apache.jmeter.control.gui.TestPlanGui.showVersionHistory();
        });
        navigation.add(history);
        JPanel header = new JPanel(new BorderLayout());
        header.add(navigation, BorderLayout.NORTH);
        header.add(new JLabel(JMeterUtils.getResString("archive_help")), BorderLayout.SOUTH);
        dialog.add(header, BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(1150, 460);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static boolean confirmReplacement(Component parent, String entry) {
        return JOptionPane.showConfirmDialog(parent,
                MessageFormat.format(JMeterUtils.getResString("archive_replace_confirm"), entry),
                JMeterUtils.getResString("archive_replace"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
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
                JMeterUtils.getResString("archive_omit_static_bodies"),
                JMeterUtils.getResString("archive_omit_statics"),
                JMeterUtils.getResString("archive_omit_recordings")});
        RecordingStorageMode[] modes = {RecordingStorageMode.OMIT_STATIC_BODIES,
                RecordingStorageMode.OMIT_STATICS, RecordingStorageMode.NONE};
        JPanel options = new JPanel(new BorderLayout(5, 5));
        options.add(mode, BorderLayout.NORTH);
        options.add(new JLabel(JMeterUtils.getResString("archive_cleanup_help")), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(parent, options, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        cleanRecordings(parent, refresh, modes[mode.getSelectedIndex()], false);
    }

    private static void cleanRecordings(JDialog parent, Runnable refresh,
            RecordingStorageMode selected, boolean removeOrphans) {
        String title = JMeterUtils.getResString(removeOrphans ? "archive_remove_orphans" : "archive_cleanup");
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
