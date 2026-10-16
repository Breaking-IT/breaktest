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
import java.awt.CardLayout;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.util.ArchiveBrowser;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.testbeans.gui.FileEditor;
import org.apache.jmeter.testbeans.gui.GenericTestBeanCustomizer;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Adds XML preview and editing actions while keeping the standard TestBean property editor.
 */
public class XMLDataSetCustomizer extends GenericTestBeanCustomizer {

    private static final long serialVersionUID = 1L;

    private transient Map<String, Object> propertyMap;

    public XMLDataSetCustomizer() {
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
                XMLDataSet.class.getName() + "Resources",
                JMeterUtils.getLocale());
        JButton previewButton = new JButton(bundle.getString("readFirstSample.displayName"));
        JTextArea previewText = new JTextArea(16, 120);
        previewText.setEditable(false);
        JScrollPane previewScroll = new JScrollPane(previewText);
        previewScroll.setMinimumSize(new Dimension(300, 220));
        previewScroll.setPreferredSize(new Dimension(900, 320));

        previewButton.addActionListener(event -> {
            saveGuiFields();
            XMLDataSet xmlDataSet = createXmlDataSet();
            try {
                Callable<InputStream> source = xmlInput(xmlDataSet);
                List<String> lines = loadInBackground(previewButton.getText(), () -> {
                    try (InputStream input = source.call()) {
                        return xmlDataSet.readFirstSample(10, input.readAllBytes());
                    }
                });
                previewText.setText(String.join(System.lineSeparator(), lines));
                previewText.setCaretPosition(0);
            } catch (RuntimeException | IOException ex) {
                previewText.setText(ex.getMessage());
                previewText.setCaretPosition(0);
            }
        });

        JButton editButton = new JButton(bundle.getString("editXml.displayName"));
        editButton.addActionListener(event -> {
            saveGuiFields();
            try {
                XMLDataSet xml = createXmlDataSet();
                LoadedEditor loaded = loadEditor(xml, bundle);
                if (loaded == null) {
                    return;
                }
                showEditor(loaded.file(), loaded.text(), xml, bundle, previewButton);
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("editXml.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton copyButton = new JButton(bundle.getString("copyXmlToArchive.displayName"));
        copyButton.addActionListener(event -> {
            saveGuiFields();
            try {
                XMLDataSet xml = createXmlDataSet();
                // Always import the configured external file, even when an archived copy is active.
                xml.setUseXmlFromArchive(false);
                storeArchivedXml(xml, xml.readXmlContent());
                updateArchiveProperties(xml);
                previewButton.doClick();
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("xmlArchive.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        JButton exportButton = new JButton(bundle.getString("exportXml.displayName"));
        exportButton.addActionListener(event -> {
            saveGuiFields();
            try {
                XMLDataSet xml = createXmlDataSet();
                byte[] content = xml.readXmlContent();
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(bundle.getString("exportXml.displayName"));
                String source = xml.isUseXmlFromArchive() ? xml.archiveEntry() : xml.getFilename();
                chooser.setSelectedFile(Path.of(source).getFileName().toFile());
                if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    Path destination = chooser.getSelectedFile().toPath();
                    if (Files.exists(destination) && JOptionPane.showConfirmDialog(this,
                            bundle.getString("exportXml.overwrite"), bundle.getString("exportXml.displayName"),
                            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                        return;
                    }
                    Files.write(destination, content);
                }
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("xmlArchive.error"),
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

    // Capture the source on the EDT so workers never consult the mutable GUI tree.
    private static Callable<InputStream> xmlInput(XMLDataSet xml) throws IOException {
        if (xml.isUseXmlFromArchive()) {
            byte[] content = xml.readXmlContent();
            return () -> new ByteArrayInputStream(content);
        }
        Path path = xml.resolveXmlFile();
        return () -> Files.newInputStream(path);
    }

    LoadedEditor loadEditor(XMLDataSet xml, ResourceBundle bundle) throws IOException {
        if (xml.getFilename().isBlank()) {
            throw new IllegalArgumentException("Filename must not be empty");
        }
        String archiveEntry = xml.isUseXmlFromArchive() ? xml.archiveEntry() : "";
        Path path = xml.isUseXmlFromArchive()
                ? Path.of(ArchiveFiles.entryName(archiveEntry))
                : xml.resolveXmlFile();
        Callable<InputStream> source;
        if (xml.isUseXmlFromArchive()) {
            // Offer creation only for an entry the archive does not hold. One that exists but
            // cannot be read must surface its own error instead, so accepting the prompt can
            // never replace real content with an empty file.
            if (ArchiveFiles.currentPlan() == null) {
                throw new IOException("No test plan is available for the XML archive");
            }
            if (!ArchiveFiles.references(ArchiveFiles.currentPlan()).containsKey(archiveEntry)) {
                return createEditor(path, bundle, missingMessage(bundle, path));
            }
            byte[] content = xml.readXmlContent();
            CsvFileEditor.checkEditableSize(content.length);
            source = () -> new ByteArrayInputStream(content);
        } else {
            if (Files.notExists(path)) {
                return createEditor(path, bundle, missingMessage(bundle, path));
            }
            source = () -> Files.newInputStream(path);
        }
        return loadInBackground(bundle.getString("editXml.displayName"), () -> {
            if (!xml.isUseXmlFromArchive()) {
                CsvFileEditor.checkEditableSize(Files.size(path));
            }
            try (InputStream input = source.call()) {
                byte[] content = CsvFileEditor.readEditableContent(input);
                CsvFileEditor file = CsvFileEditor.fromBytes(path, XMLDataSet.fileEncoding(content), content);
                return new LoadedEditor(file, file.getEditorText());
            }
        });
    }

    private static String missingMessage(ResourceBundle bundle, Path path) {
        return MessageFormat.format(bundle.getString("editXml.missing"), path);
    }

    private LoadedEditor createEditor(Path path, ResourceBundle bundle, String message)
            throws IOException {
        if (!confirmCreateXml(message, bundle)) {
            return null;
        }
        CsvFileEditor file = CsvFileEditor.create(path, "UTF-8");
        return new LoadedEditor(file, file.getEditorText());
    }

    boolean confirmCreateXml(String message, ResourceBundle bundle) {
        return JOptionPane.showConfirmDialog(this, message + "\n\n" + bundle.getString("editXml.create"),
                bundle.getString("editXml.displayName"), JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    record LoadedEditor(CsvFileEditor file, String text) {
    }

    <T> T loadInBackground(String title, Callable<T> operation) throws IOException {
        JDialog progress = new JDialog(SwingUtilities.getWindowAncestor(this), title,
                Dialog.ModalityType.APPLICATION_MODAL);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        progress.add(bar);
        progress.pack();
        progress.setLocationRelativeTo(this);
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override
            protected T doInBackground() throws Exception {
                return operation.call();
            }

            @Override
            protected void done() {
                progress.dispose();
            }
        };
        worker.execute();
        progress.setVisible(true);
        try {
            return worker.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        } finally {
            progress.dispose();
        }
    }

    boolean browseArchiveFile() {
        saveGuiFields();
        if (!getBoolean("useXmlFromArchive")) {
            return false;
        }
        String currentEntry = ArchiveFiles.entryName(getString("filename"));
        String entry = chooseArchiveFile(currentEntry);
        if (entry != null) {
            propertyMap.put("filename", entry.substring("files/".length()));
            setObject(propertyMap);
            markDirty();
        }
        return true;
    }

    String chooseArchiveFile(String currentEntry) {
        return ArchiveBrowser.chooseFile(this, currentEntry);
    }

    /** Routes the standard filename Browse button through the selected XML source. */
    public static class XmlFilenameEditor extends FileEditor {
        public XmlFilenameEditor() throws IntrospectionException {
            super(new PropertyDescriptor("filename", XMLDataSet.class));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            XMLDataSetCustomizer customizer = (XMLDataSetCustomizer) SwingUtilities.getAncestorOfClass(
                    XMLDataSetCustomizer.class, getCustomEditor());
            if (customizer == null || !customizer.browseArchiveFile()) {
                super.actionPerformed(event);
            }
        }
    }

    private static void storeArchivedXml(XMLDataSet xml, byte[] content) throws IOException {
        xml.storeArchivedXml(content);
    }

    private void updateArchiveProperties(XMLDataSet xml) {
        propertyMap.put("filename", xml.getFilename());
        propertyMap.put("useXmlFromArchive", xml.isUseXmlFromArchive());
        setObject(propertyMap);
        markDirty();
    }

    private static void markDirty() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            gui.setDirty(true);
        }
    }

    private void showEditor(CsvFileEditor file, String text, XMLDataSet xml, ResourceBundle bundle, JButton previewButton) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                bundle.getString("editXml.displayName"), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JTextArea editor = new JTextArea(24, 100);
        editor.setCaretPosition(0);
        editor.getAccessibleContext().setAccessibleName(bundle.getString("editXml.displayName"));
        JPanel content = new JPanel(new BorderLayout(5, 5));
        content.add(new JLabel(file.getPath().toString()), BorderLayout.NORTH);
        CardLayout cards = new CardLayout();
        JPanel editorPanel = new JPanel(cards);
        JPanel loading = new JPanel(new GridBagLayout());
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setStringPainted(true);
        progress.setString(bundle.getString("editXml.loading"));
        loading.add(progress);
        editorPanel.add(loading, "loading");
        editorPanel.add(new JScrollPane(editor), "editor");
        content.add(editorPanel, BorderLayout.CENTER);
        JButton cancel = new JButton(JMeterUtils.getResString("cancel"));
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = new JButton(JMeterUtils.getResString("save"));
        save.setEnabled(false);
        save.addActionListener(event -> {
            try {
                saveEditor(file, editor.getText(), xml);
                dialog.dispose();
                previewButton.doClick();
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), bundle.getString("editXml.error"),
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
        // Populate the Swing document in bounded EDT turns. Keeping the editor hidden
        // avoids showing readable text while its document and views are still being built.
        Timer populate = CSVDataSetCustomizer.populateEditor(editor, text, () -> {
            editor.setCaretPosition(0);
            cards.show(editorPanel, "editor");
            save.setEnabled(true);
            editor.requestFocusInWindow();
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                populate.stop();
            }
        });
        dialog.setVisible(true);
    }

    void saveEditor(CsvFileEditor file, String text, XMLDataSet xml) throws IOException {
        String updatedContent = file.toFileText(text);
        CsvFileEditor target = file;
        byte[] bytes = target.encode(updatedContent);
        String declared = XMLDataSet.declaredEncoding(bytes);
        if (declared != null && !Charset.forName(declared).equals(target.getCharset())) {
            // The declaration was edited: save with the encoding the file now declares.
            target = target.withCharset(Charset.forName(declared));
            bytes = target.encode(updatedContent);
        }
        xml.parseRecords(bytes);
        if (xml.isUseXmlFromArchive()) {
            storeArchivedXml(xml, bytes);
            updateArchiveProperties(xml);
        } else {
            target.save(updatedContent);
        }
    }

    private XMLDataSet createXmlDataSet() {
        XMLDataSet xmlDataSet = new XMLDataSet();
        xmlDataSet.setFilename(getString("filename"));
        xmlDataSet.setUseXmlFromArchive(getBoolean("useXmlFromArchive"));
        xmlDataSet.setVariablePrefix(getString("variablePrefix"));
        xmlDataSet.setRecordXPath(getString("recordXPath"));
        return xmlDataSet;
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
            return Introspector.getBeanInfo(XMLDataSet.class);
        } catch (IntrospectionException e) {
            throw new Error("Can't get BeanInfo for " + XMLDataSet.class.getName(), e);
        }
    }
}
