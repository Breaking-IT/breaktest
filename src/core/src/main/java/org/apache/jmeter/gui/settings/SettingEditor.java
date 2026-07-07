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

package org.apache.jmeter.gui.settings;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Locale;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.jmeter.util.JMeterUtils;

/**
 * Editor row for one setting in the Settings dialog: property key, source
 * badge, reset button, description and a control matching the setting type.
 */
class SettingEditor {

    private final SettingsGroup group;
    private final SettingDefinition setting;
    private final SettingsModel model;
    private final Runnable changeCallback;

    private final JPanel panel;
    private final JLabel badge = new JLabel();
    private final JButton resetButton = new JButton(JMeterUtils.getResString("settings_reset"));

    private JCheckBox checkBox;
    private JComboBox<String> comboBox;
    private JTextField textField;

    /** value currently shown when the dialog was opened (effective value) */
    private String baseline;
    /** value the setting falls back to when its override is removed */
    private String inherited;
    /** whether the target file currently contains an override for the key */
    private boolean overridden;
    /** true when the user asked to remove the override */
    private boolean removeRequested;
    /** guards listeners while the control is updated programmatically */
    private boolean updating;

    SettingEditor(SettingsGroup group, SettingDefinition setting, SettingsModel model, Runnable changeCallback) {
        this.group = group;
        this.setting = setting;
        this.model = model;
        this.changeCallback = changeCallback;
        this.baseline = model.getValue(group, setting);
        this.inherited = model.getInheritedValue(group, setting);
        this.overridden = model.getSource(group, setting) == SettingsModel.ValueSource.OVERRIDE;
        this.panel = buildPanel();
        updateBadge();
    }

    SettingDefinition getSetting() {
        return setting;
    }

    SettingsGroup getGroup() {
        return group;
    }

    JComponent getComponent() {
        return panel;
    }

    private JPanel buildPanel() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel keyLabel = new JLabel(setting.getKey());
        keyLabel.setFont(keyLabel.getFont().deriveFont(Font.BOLD));
        header.add(keyLabel);
        header.add(Box.createHorizontalStrut(8));
        badge.setFont(badge.getFont().deriveFont(badge.getFont().getSize2D() - 1f));
        badge.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.add(badge);
        header.add(Box.createHorizontalGlue());
        resetButton.setFont(resetButton.getFont().deriveFont(resetButton.getFont().getSize2D() - 1f));
        resetButton.setToolTipText(JMeterUtils.getResString("settings_reset_tooltip"));
        resetButton.addActionListener(e -> reset());
        header.add(resetButton);
        row.add(header);

        if (!setting.getDescription().isEmpty()) {
            JTextArea description = new JTextArea(setting.getDescription());
            description.setEditable(false);
            description.setFocusable(false);
            description.setLineWrap(true);
            description.setWrapStyleWord(true);
            description.setOpaque(false);
            description.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 0, 4, 0));
            description.setFont(UIManager.getFont("Label.font")
                    .deriveFont(UIManager.getFont("Label.font").getSize2D() - 1f));
            description.setForeground(UIManager.getColor("Label.disabledForeground"));
            description.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(description);
        }

        JComponent control = buildControl();
        JPanel controlWrapper = new JPanel(new BorderLayout());
        controlWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlWrapper.add(control, BorderLayout.CENTER);
        if (setting.getType() == SettingType.FILE || setting.getType() == SettingType.DIRECTORY) {
            JButton browse = new JButton("…");
            browse.addActionListener(e -> browse());
            controlWrapper.add(browse, BorderLayout.EAST);
        }
        controlWrapper.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                control.getPreferredSize().height + 4));
        row.add(controlWrapper);
        return row;
    }

    private JComponent buildControl() {
        switch (setting.getType()) {
            case BOOLEAN -> {
                checkBox = new JCheckBox(JMeterUtils.getResString("settings_enabled"));
                checkBox.setSelected(Boolean.parseBoolean(baseline.trim().toLowerCase(Locale.ROOT)));
                checkBox.addItemListener(e -> onControlChange());
                return checkBox;
            }
            case ENUM -> {
                comboBox = new JComboBox<>();
                for (String option : setting.getOptions()) {
                    comboBox.addItem(option);
                }
                if (!baseline.isEmpty() && ((javax.swing.DefaultComboBoxModel<String>) comboBox.getModel())
                        .getIndexOf(baseline) < 0) {
                    comboBox.addItem(baseline);
                }
                comboBox.setSelectedItem(baseline);
                comboBox.addActionListener(e -> onControlChange());
                return comboBox;
            }
            default -> {
                textField = new JTextField(baseline);
                if (setting.getDefaultValue() != null) {
                    textField.setToolTipText(JMeterUtils.getResString("settings_default_prefix")
                            + " " + setting.getDefaultValue());
                }
                textField.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        onControlChange();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        onControlChange();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        onControlChange();
                    }
                });
                return textField;
            }
        }
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(setting.getType() == SettingType.DIRECTORY
                ? JFileChooser.DIRECTORIES_ONLY
                : JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            textField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void onControlChange() {
        if (updating) {
            return;
        }
        removeRequested = false;
        updateBadge();
        changeCallback.run();
    }

    /** Sets the control back to the inherited value and requests removal of the override. */
    private void reset() {
        updating = true;
        try {
            setControlValue(inherited);
        } finally {
            updating = false;
        }
        removeRequested = overridden;
        updateBadge();
        changeCallback.run();
    }

    private void setControlValue(String value) {
        if (checkBox != null) {
            checkBox.setSelected(Boolean.parseBoolean(value.trim().toLowerCase(Locale.ROOT)));
        } else if (comboBox != null) {
            if (!value.isEmpty() && ((javax.swing.DefaultComboBoxModel<String>) comboBox.getModel())
                    .getIndexOf(value) < 0) {
                comboBox.addItem(value);
            }
            comboBox.setSelectedItem(value);
        } else {
            textField.setText(value);
        }
    }

    String getControlValue() {
        if (checkBox != null) {
            return Boolean.toString(checkBox.isSelected());
        }
        if (comboBox != null) {
            Object selected = comboBox.getSelectedItem();
            return selected == null ? "" : selected.toString();
        }
        return textField.getText();
    }

    /** @return true when the user asked to remove the existing override */
    boolean isRemoveRequested() {
        return removeRequested && overridden;
    }

    /** @return true when saving should write a new override value */
    boolean isSetRequested() {
        if (removeRequested) {
            return false;
        }
        return !getControlValue().equals(baseline);
    }

    boolean isModified() {
        return isRemoveRequested() || isSetRequested();
    }

    /** Re-reads the state from the model after a successful save. */
    void rebase() {
        baseline = model.getValue(group, setting);
        inherited = model.getInheritedValue(group, setting);
        overridden = model.getSource(group, setting) == SettingsModel.ValueSource.OVERRIDE;
        removeRequested = false;
        updating = true;
        try {
            setControlValue(baseline);
        } finally {
            updating = false;
        }
        updateBadge();
    }

    private void updateBadge() {
        String text;
        if (isModified()) {
            text = JMeterUtils.getResString("settings_badge_modified");
        } else if (overridden) {
            text = group.getTarget() == SettingsGroup.Target.SYSTEM
                    ? model.getSystemFile().getName()
                    : model.getUserFile().getName();
        } else if (model.getSource(group, setting) == SettingsModel.ValueSource.JMETER_PROPERTIES) {
            text = "jmeter.properties";
        } else {
            text = JMeterUtils.getResString("settings_badge_default");
        }
        badge.setText(text);
        resetButton.setVisible(overridden || isModified());
    }

    boolean matches(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return setting.getKey().toLowerCase(Locale.ROOT).contains(q)
                || setting.getDescription().toLowerCase(Locale.ROOT).contains(q);
    }
}
