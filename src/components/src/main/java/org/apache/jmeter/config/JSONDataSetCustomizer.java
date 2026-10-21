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
 * Adds JSON preview and editing actions while keeping the standard TestBean property editor.
 */
public class JSONDataSetCustomizer extends GenericTestBeanCustomizer {

    private static final long serialVersionUID = 1L;

    private transient Map<String, Object> propertyMap;

    public JSONDataSetCustomizer() {
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
                JSONDataSet.class.getName() + "Resources",
                JMeterUtils.getLocale());
        JButton previewButton = new JButton(bundle.getString("readFirstSample.displayName"));
        JTextArea previewText = new JTextArea(16, 120);
        previewText.setEditable(false);
        JScrollPane previewScroll = new JScrollPane(previewText);
        previewScroll.setMinimumSize(new Dimension(300, 220));
        previewScroll.setPreferredSize(new Dimension(900, 320));

        previewButton.addActionListener(event -> {
            saveGuiFields();
            JSONDataSet jsonDataSet = createJsonDataSet();
            try {
                Callable<InputStream> source = jsonInput(jsonDataSet);
                List<String> lines = loadInBackground(previewButton.getText(), () -> {
                    try (InputStream input = source.call()) {
                        return jsonDataSet.readFirstSample(10, input.readAllBytes());
                    }
                });
                previewText.setText(String.join(System.lineSeparator(), lines));
                previewText.setCaretPosition(0);
            } catch (RuntimeException | IOException ex) {
                previewText.setText(ex.getMessage());
                previewText.setCaretPosition(0);
            }
        });

        JButton editButton = new JButton(bundle.getString("editJson.displayName"));
        editButton.addActionListener(event -> {
            saveGuiFields();
            try {
                JSONDataSet json = createJsonDataSet();
                LoadedEditor loaded = loadEditor(json, bundle);
                if (loaded == null) {
                    return;
                }
                showEditor(loaded.file(), loaded.text(), json, bundle, previewButton);
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("editJson.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton copyButton = new JButton(bundle.getString("copyJsonToArchive.displayName"));
        copyButton.addActionListener(event -> {
            saveGuiFields();
            try {
                JSONDataSet json = createJsonDataSet();
                // Always import the configured external file, even when an archived copy is active.
                json.setUseJsonFromArchive(false);
                storeArchivedJson(json, json.readJsonContent());
                updateArchiveProperties(json);
                previewButton.doClick();
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("jsonArchive.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        JButton exportButton = new JButton(bundle.getString("exportJson.displayName"));
        exportButton.addActionListener(event -> {
            saveGuiFields();
            try {
                JSONDataSet json = createJsonDataSet();
                byte[] content = json.readJsonContent();
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(bundle.getString("exportJson.displayName"));
                String source = json.isUseJsonFromArchive() ? json.archiveEntry() : json.getFilename();
                chooser.setSelectedFile(Path.of(source).getFileName().toFile());
                if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    Path destination = chooser.getSelectedFile().toPath();
                    if (Files.exists(destination) && JOptionPane.showConfirmDialog(this,
                            bundle.getString("exportJson.overwrite"), bundle.getString("exportJson.displayName"),
                            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                        return;
                    }
                    Files.write(destination, content);
                }
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("jsonArchive.error"),
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
    private static Callable<InputStream> jsonInput(JSONDataSet json) throws IOException {
        if (json.isUseJsonFromArchive()) {
            byte[] content = json.readJsonContent();
            return () -> new ByteArrayInputStream(content);
        }
        Path path = json.resolveJsonFile();
        return () -> Files.newInputStream(path);
    }

    LoadedEditor loadEditor(JSONDataSet json, ResourceBundle bundle) throws IOException {
        if (json.getFilename().isBlank()) {
            throw new IllegalArgumentException("Filename must not be empty");
        }
        String archiveEntry = json.isUseJsonFromArchive() ? json.archiveEntry() : "";
        Path path = json.isUseJsonFromArchive()
                ? Path.of(ArchiveFiles.entryName(archiveEntry))
                : json.resolveJsonFile();
        Callable<InputStream> source;
        if (json.isUseJsonFromArchive()) {
            // Offer creation only for an entry the archive does not hold. One that exists but
            // cannot be read must surface its own error instead, so accepting the prompt can
            // never replace real content with an empty file.
            if (ArchiveFiles.currentPlan() == null) {
                throw new IOException("No test plan is available for the JSON archive");
            }
            if (!ArchiveFiles.references(ArchiveFiles.currentPlan()).containsKey(archiveEntry)) {
                return createEditor(path, bundle, missingMessage(bundle, path));
            }
            byte[] content = json.readJsonContent();
            CsvFileEditor.checkEditableSize(content.length);
            source = () -> new ByteArrayInputStream(content);
        } else {
            if (Files.notExists(path)) {
                return createEditor(path, bundle, missingMessage(bundle, path));
            }
            source = () -> Files.newInputStream(path);
        }
        return loadInBackground(bundle.getString("editJson.displayName"), () -> {
            if (!json.isUseJsonFromArchive()) {
                CsvFileEditor.checkEditableSize(Files.size(path));
            }
            try (InputStream input = source.call()) {
                CsvFileEditor file = CsvFileEditor.fromBytes(path, "UTF-8",
                        CsvFileEditor.readEditableContent(input));
                return new LoadedEditor(file, file.getEditorText());
            }
        });
    }

    private static String missingMessage(ResourceBundle bundle, Path path) {
        return MessageFormat.format(bundle.getString("editJson.missing"), path);
    }

    private LoadedEditor createEditor(Path path, ResourceBundle bundle, String message)
            throws IOException {
        if (!confirmCreateJson(message, bundle)) {
            return null;
        }
        CsvFileEditor file = CsvFileEditor.create(path, "UTF-8");
        return new LoadedEditor(file, file.getEditorText());
    }

    boolean confirmCreateJson(String message, ResourceBundle bundle) {
        return JOptionPane.showConfirmDialog(this, message + "\n\n" + bundle.getString("editJson.create"),
                bundle.getString("editJson.displayName"), JOptionPane.YES_NO_OPTION,
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
        if (!getBoolean("useJsonFromArchive")) {
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

    /** Routes the standard filename Browse button through the selected JSON source. */
    public static class JsonFilenameEditor extends FileEditor {
        public JsonFilenameEditor() throws IntrospectionException {
            super(new PropertyDescriptor("filename", JSONDataSet.class));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            JSONDataSetCustomizer customizer = (JSONDataSetCustomizer) SwingUtilities.getAncestorOfClass(
                    JSONDataSetCustomizer.class, getCustomEditor());
            if (customizer == null || !customizer.browseArchiveFile()) {
                super.actionPerformed(event);
            }
        }
    }

    private static void storeArchivedJson(JSONDataSet json, byte[] content) throws IOException {
        json.storeArchivedJson(content);
    }

    private void updateArchiveProperties(JSONDataSet json) {
        propertyMap.put("filename", json.getFilename());
        propertyMap.put("useJsonFromArchive", json.isUseJsonFromArchive());
        setObject(propertyMap);
        markDirty();
    }

    private static void markDirty() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            gui.setDirty(true);
        }
    }

    private void showEditor(CsvFileEditor file, String text, JSONDataSet json, ResourceBundle bundle, JButton previewButton) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                bundle.getString("editJson.displayName"), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JTextArea editor = new JTextArea(24, 100);
        editor.setCaretPosition(0);
        editor.getAccessibleContext().setAccessibleName(bundle.getString("editJson.displayName"));
        JPanel content = new JPanel(new BorderLayout(5, 5));
        content.add(new JLabel(file.getPath().toString()), BorderLayout.NORTH);
        CardLayout cards = new CardLayout();
        JPanel editorPanel = new JPanel(cards);
        JPanel loading = new JPanel(new GridBagLayout());
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setStringPainted(true);
        progress.setString(bundle.getString("editJson.loading"));
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
                saveEditor(file, editor.getText(), json);
                dialog.dispose();
                previewButton.doClick();
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), bundle.getString("editJson.error"),
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

    void saveEditor(CsvFileEditor file, String text, JSONDataSet json) throws IOException {
        String updatedContent = file.toFileText(text);
        byte[] bytes = file.encode(updatedContent);
        JSONDataSet.parseRecords(bytes);
        if (json.isUseJsonFromArchive()) {
            storeArchivedJson(json, bytes);
            updateArchiveProperties(json);
        } else {
            file.save(updatedContent);
        }
    }

    private JSONDataSet createJsonDataSet() {
        JSONDataSet jsonDataSet = new JSONDataSet();
        jsonDataSet.setFilename(getString("filename"));
        jsonDataSet.setUseJsonFromArchive(getBoolean("useJsonFromArchive"));
        jsonDataSet.setVariablePrefix(getString("variablePrefix"));
        return jsonDataSet;
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
            return Introspector.getBeanInfo(JSONDataSet.class);
        } catch (IntrospectionException e) {
            throw new Error("Can't get BeanInfo for " + JSONDataSet.class.getName(), e);
        }
    }
}
