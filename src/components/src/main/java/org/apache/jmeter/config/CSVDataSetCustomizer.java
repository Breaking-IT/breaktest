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

package org.apache.jmeter.config;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.util.ArchiveBrowser;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.testbeans.gui.FileEditor;
import org.apache.jmeter.testbeans.gui.GenericTestBeanCustomizer;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Adds CSV preview and editing actions while keeping the standard TestBean property editor.
 */
public class CSVDataSetCustomizer extends GenericTestBeanCustomizer {

    private static final long serialVersionUID = 1L;

    private transient Map<String, Object> propertyMap;

    public CSVDataSetCustomizer() {
        super(beanInfo());
        JPanel propertyPanel = moveGeneratedPropertyPanel();
        setLayout(new BorderLayout(0, 5));
        add(propertyPanel, BorderLayout.NORTH);
        add(createPreviewPanel(), BorderLayout.CENTER);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setObject(Object map) {
        super.setObject(map);
        propertyMap = (Map<String, Object>) map;
    }

    private JPanel moveGeneratedPropertyPanel() {
        Component[] propertyComponents = getComponents();
        GridBagLayout propertyLayout = (GridBagLayout) getLayout();
        JPanel propertyPanel = new JPanel(new GridBagLayout());
        for (Component propertyComponent : propertyComponents) {
            propertyPanel.add(propertyComponent, propertyLayout.getConstraints(propertyComponent));
        }
        removeAll();
        return propertyPanel;
    }

    private JPanel createPreviewPanel() {
        ResourceBundle bundle = ResourceBundle.getBundle(
                CSVDataSet.class.getName() + "Resources",
                JMeterUtils.getLocale());
        JButton previewButton = new JButton(bundle.getString("readFirstSample.displayName"));
        JTextArea previewText = new JTextArea(16, 120);
        previewText.setEditable(false);
        JScrollPane previewScroll = new JScrollPane(previewText);
        previewScroll.setMinimumSize(new Dimension(300, 220));
        previewScroll.setPreferredSize(new Dimension(900, 320));

        previewButton.addActionListener(event -> {
            saveGuiFields();
            CSVDataSet csvDataSet = createCsvDataSet();
            try {
                List<String> lines = csvDataSet.readFirstSample(10);
                previewText.setText(String.join(System.lineSeparator(), lines));
                previewText.setCaretPosition(0);
            } catch (RuntimeException | IOException ex) {
                previewText.setText(ex.getMessage());
                previewText.setCaretPosition(0);
            }
        });

        JButton editButton = new JButton(bundle.getString("editCsv.displayName"));
        editButton.addActionListener(event -> {
            saveGuiFields();
            try {
                CSVDataSet csv = createCsvDataSet();
                CsvFileEditor file = csv.isUseCsvFromArchive()
                        ? CsvFileEditor.fromBytes(Path.of(csv.getCsvArchiveEntry()), csv.getFileEncoding(),
                                csv.readCsvContent())
                        : CsvFileEditor.open(csv.getFilename(), csv.getFileEncoding());
                showEditor(file, csv, bundle, previewButton);
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("editCsv.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton copyButton = new JButton(bundle.getString("copyCsvToArchive.displayName"));
        copyButton.addActionListener(event -> {
            saveGuiFields();
            try {
                CSVDataSet csv = createCsvDataSet();
                // Always import the configured external file, even when an archived copy is active.
                csv.setUseCsvFromArchive(false);
                storeArchivedCsv(csv, csv.readCsvContent());
                updateArchiveProperties(csv);
                previewButton.doClick();
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("csvArchive.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        JButton exportButton = new JButton(bundle.getString("exportCsv.displayName"));
        exportButton.addActionListener(event -> {
            saveGuiFields();
            try {
                CSVDataSet csv = createCsvDataSet();
                byte[] content = csv.readCsvContent();
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(bundle.getString("exportCsv.displayName"));
                String source = csv.isUseCsvFromArchive() ? csv.getCsvArchiveEntry() : csv.getFilename();
                chooser.setSelectedFile(Path.of(source).getFileName().toFile());
                if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    Path destination = chooser.getSelectedFile().toPath();
                    if (Files.exists(destination) && JOptionPane.showConfirmDialog(this,
                            bundle.getString("exportCsv.overwrite"), bundle.getString("exportCsv.displayName"),
                            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                        return;
                    }
                    Files.write(destination, content);
                }
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("csvArchive.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttonPanel.add(previewButton);
        buttonPanel.add(editButton);
        buttonPanel.add(copyButton);
        buttonPanel.add(exportButton);

        JPanel previewPanel = new JPanel(new BorderLayout(0, 5));
        previewPanel.add(buttonPanel, BorderLayout.NORTH);
        previewPanel.add(previewScroll, BorderLayout.CENTER);

        return previewPanel;
    }

    boolean browseArchiveFile() {
        saveGuiFields();
        if (!getBoolean("useCsvFromArchive")) {
            return false;
        }
        String entry = chooseArchiveFile();
        if (entry != null) {
            CSVDataSet csv = createCsvDataSet();
            propertyMap.put("filename", entry.substring("files/".length()));
            csv.setCsvArchiveEntry(entry);
            csv.setCsvArchiveChecksum(ArchiveFiles.references(ArchiveFiles.currentPlan()).get(entry));
            updateArchiveProperties(csv);
        }
        return true;
    }

    String chooseArchiveFile() {
        return ArchiveBrowser.chooseFile(this);
    }

    /** Routes the standard filename Browse button through the selected CSV source. */
    public static class CsvFilenameEditor extends FileEditor {
        public CsvFilenameEditor() throws IntrospectionException {
            super(new PropertyDescriptor("filename", CSVDataSet.class));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            CSVDataSetCustomizer customizer = (CSVDataSetCustomizer) SwingUtilities.getAncestorOfClass(
                    CSVDataSetCustomizer.class, getCustomEditor());
            if (customizer == null || !customizer.browseArchiveFile()) {
                super.actionPerformed(event);
            }
        }
    }

    private static void storeArchivedCsv(CSVDataSet csv, byte[] content) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            String entry = csv.isUseCsvFromArchive() && !csv.getCsvArchiveEntry().isEmpty()
                    ? csv.getCsvArchiveEntry() : CsvArchiveSupport.entryName(csv.getFilename());
            // An archived file is shared by name; editing updates that shared file.
            // Importing a different external file still rejects a name collision.
            ArchiveFiles.put(ArchiveFiles.currentPlan(), entry, content, csv.isUseCsvFromArchive());
            csv.setCsvArchiveEntry(entry);
            csv.setCsvArchiveChecksum(ArchiveFiles.checksum(content));
            csv.setUseCsvFromArchive(true);
        } else {
            csv.storeArchivedCsv(content);
        }
    }

    private void updateArchiveProperties(CSVDataSet csv) {
        propertyMap.put("useCsvFromArchive", csv.isUseCsvFromArchive());
        propertyMap.put("csvArchiveEntry", csv.getCsvArchiveEntry());
        propertyMap.put("csvArchiveChecksum", csv.getCsvArchiveChecksum());
        setObject(propertyMap);
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            gui.setDirty(true);
        }
    }

    private void showEditor(CsvFileEditor file, CSVDataSet csv, ResourceBundle bundle, JButton previewButton) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                bundle.getString("editCsv.displayName"), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JTextArea editor = new JTextArea(file.getEditorText(), 24, 100);
        editor.setCaretPosition(0);
        editor.getAccessibleContext().setAccessibleName(bundle.getString("editCsv.displayName"));
        JPanel content = new JPanel(new BorderLayout(5, 5));
        content.add(new JLabel(file.getPath().toString()), BorderLayout.NORTH);
        content.add(new JScrollPane(editor), BorderLayout.CENTER);
        JButton cancel = new JButton(JMeterUtils.getResString("cancel"));
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = new JButton(JMeterUtils.getResString("save"));
        save.addActionListener(event -> {
            try {
                String updatedContent = file.toFileText(editor.getText());
                if (csv.isUseCsvFromArchive()) {
                    storeArchivedCsv(csv, file.encode(updatedContent));
                    updateArchiveProperties(csv);
                } else {
                    file.save(updatedContent);
                }
                dialog.dispose();
                previewButton.doClick();
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), bundle.getString("editCsv.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(save);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private CSVDataSet createCsvDataSet() {
        CSVDataSet csvDataSet = new CSVDataSet();
        csvDataSet.setFilename(getString("filename"));
        csvDataSet.setUseCsvFromArchive(getBoolean("useCsvFromArchive"));
        csvDataSet.setCsvArchiveEntry(getString("csvArchiveEntry"));
        csvDataSet.setCsvArchiveChecksum(getString("csvArchiveChecksum"));
        csvDataSet.setFileEncoding(getString("fileEncoding"));
        csvDataSet.setVariableNames(getString("variableNames"));
        csvDataSet.setDelimiter(getString("delimiter"));
        csvDataSet.setIgnoreFirstLine(getBoolean("ignoreFirstLine"));
        csvDataSet.setQuotedData(getBoolean("quotedData"));
        csvDataSet.setRandomOrder(getBoolean("randomOrder"));
        return csvDataSet;
    }

    private String getString(String name) {
        Object value = propertyMap.get(name);
        return value == null ? "" : value.toString();
    }

    private boolean getBoolean(String name) {
        return Boolean.TRUE.equals(propertyMap.get(name));
    }

    private static BeanInfo beanInfo() {
        try {
            return Introspector.getBeanInfo(CSVDataSet.class);
        } catch (IntrospectionException e) {
            throw new Error("Can't get BeanInfo for " + CSVDataSet.class.getName(), e);
        }
    }
}
