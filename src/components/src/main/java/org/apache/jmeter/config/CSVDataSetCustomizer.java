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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Dialog;
import java.awt.GridBagLayout;
import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

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
            } catch (RuntimeException | java.io.IOException ex) {
                previewText.setText(ex.getMessage());
                previewText.setCaretPosition(0);
            }
        });

        JButton editButton = new JButton(bundle.getString("editCsv.displayName"));
        editButton.addActionListener(event -> {
            saveGuiFields();
            try {
                CsvFileEditor file = CsvFileEditor.open(getString("filename"), getString("fileEncoding"));
                showEditor(file, bundle, previewButton);
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), bundle.getString("editCsv.error"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttonPanel.add(previewButton);
        buttonPanel.add(editButton);

        JPanel previewPanel = new JPanel(new BorderLayout(0, 5));
        previewPanel.add(buttonPanel, BorderLayout.NORTH);
        previewPanel.add(previewScroll, BorderLayout.CENTER);

        return previewPanel;
    }

    private void showEditor(CsvFileEditor file, ResourceBundle bundle, JButton previewButton) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                bundle.getString("editCsv.displayName"), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JTextArea editor = new JTextArea(file.getContent(), 24, 100);
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
                file.save(editor.getText());
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
