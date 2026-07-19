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
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.gui.util.EscapeDialog;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings dialog listing every JMeter property known to the settings
 * catalog. Groups are shown on the left; the settings of the selected group
 * are edited on the right. Changes are written to {@code user.properties}
 * (or {@code system.properties} for JVM system properties) so that
 * {@code jmeter.properties} stays untouched.
 */
public class SettingsDialog extends EscapeDialog {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(SettingsDialog.class);

    private final SettingsModel model;
    private final List<SettingsGroup> groups;
    /** editors created so far, keyed by property key; keeps pending edits across group switches */
    private final Map<String, SettingEditor> editors = new HashMap<>();

    private final JTextField searchField = new JTextField();
    private final JList<SettingsGroup> groupList;
    private final JPanel settingsPanel = new ViewportWidthPanel();
    private final JScrollPane settingsScroll;
    private final JTabbedPane tabs = new JTabbedPane();
    private final DefaultTableModel overridesModel = new DefaultTableModel(
            new String[] {
                    JMeterUtils.getResString("settings_column_setting"),
                    JMeterUtils.getResString("settings_column_value"),
                    JMeterUtils.getResString("settings_column_default"),
                    JMeterUtils.getResString("settings_column_source")
            }, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JLabel modifiedLabel = new JLabel(" ");
    private final JButton saveButton = new JButton(JMeterUtils.getResString("settings_save"));

    public SettingsDialog(JFrame parent, SettingsModel model) {
        super(parent, JMeterUtils.getResString("settings_title"), true);
        this.model = model;
        this.groups = new ArrayList<>(model.getGroupsWithOther(
                JMeterUtils.getResString("settings_other_group"),
                JMeterUtils.getResString("settings_other_group_description")));

        groupList = new JList<>(groups.toArray(new SettingsGroup[0]));
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        groupList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && searchField.getText().trim().isEmpty()) {
                showSelectedGroup();
            }
        });

        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));
        // ViewportWidthPanel tracks the viewport width (for description wrapping) but not
        // its height, so rows keep their natural height instead of stretching; it must be
        // the scroll pane's direct view for its Scrollable contract to apply
        settingsScroll = new JScrollPane(settingsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildTopPanel(), BorderLayout.NORTH);
        getContentPane().add(buildCenterPanel(), BorderLayout.CENTER);
        getContentPane().add(buildBottomPanel(), BorderLayout.SOUTH);

        setSize(new Dimension(1050, 700));
        setLocationRelativeTo(parent);
        if (!groups.isEmpty()) {
            groupList.setSelectedIndex(0);
        }
    }

    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JLabel searchLabel = new JLabel(JMeterUtils.getResString("settings_search"));
        top.add(searchLabel, BorderLayout.WEST);
        top.add(searchField, BorderLayout.CENTER);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshView();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshView();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshView();
            }
        });
        return top;
    }

    private JTabbedPane buildCenterPanel() {
        JScrollPane groupScroll = new JScrollPane(groupList);
        groupScroll.setMinimumSize(new Dimension(220, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, groupScroll, settingsScroll);
        split.setDividerLocation(260);
        split.setContinuousLayout(true);

        JTable overridesTable = new JTable(overridesModel);
        overridesTable.setAutoCreateRowSorter(true);
        overridesTable.setFillsViewportHeight(true);
        overridesTable.getColumnModel().getColumn(0).setPreferredWidth(360);
        overridesTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        overridesTable.getColumnModel().getColumn(2).setPreferredWidth(180);
        overridesTable.getColumnModel().getColumn(3).setPreferredWidth(130);

        tabs.addTab(JMeterUtils.getResString("settings_all_tab"), split);
        tabs.addTab(JMeterUtils.getResString("settings_overrides_tab"), new JScrollPane(overridesTable));
        tabs.addChangeListener(e -> refreshView());
        return tabs;
    }

    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));

        JLabel note = new JLabel(MessageFormat.format(
                JMeterUtils.getResString("settings_note"),
                model.getUserFile().getName(), model.getSystemFile().getName()));
        note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1f));
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        note.setToolTipText(model.getUserFile().getAbsolutePath());
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(note);
        bottom.add(Box.createVerticalStrut(6));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        modifiedLabel.setFont(modifiedLabel.getFont().deriveFont(Font.ITALIC));
        buttons.add(modifiedLabel);
        buttons.add(Box.createHorizontalGlue());
        JButton cancelButton = new JButton(JMeterUtils.getResString("settings_cancel"));
        cancelButton.addActionListener(e -> setVisible(false));
        buttons.add(cancelButton);
        buttons.add(Box.createHorizontalStrut(8));
        saveButton.setEnabled(false);
        saveButton.addActionListener(e -> save());
        buttons.add(saveButton);
        bottom.add(buttons);
        return bottom;
    }

    private SettingEditor editorFor(SettingsGroup group, SettingDefinition setting) {
        return editors.computeIfAbsent(setting.getKey(),
                key -> new SettingEditor(group, setting, model, this::updateModifiedState));
    }

    private void refreshView() {
        String query = searchField.getText().trim();
        if (tabs.getSelectedIndex() == 1) {
            showOverrides(query);
            return;
        }
        if (query.isEmpty()) {
            showSelectedGroup();
        } else {
            showSearchResults(query);
        }
    }

    private void showOverrides(String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        overridesModel.setRowCount(0);
        for (SettingsGroup group : groups) {
            for (SettingDefinition setting : group.getSettings()) {
                if (model.getSource(group, setting) != SettingsModel.ValueSource.OVERRIDE) {
                    continue;
                }
                String value = model.getValue(group, setting);
                String inherited = model.getInheritedValue(group, setting);
                if (value.equals(inherited)) {
                    continue;
                }
                String source = group.getTarget() == SettingsGroup.Target.SYSTEM
                        ? model.getSystemFile().getName() : model.getUserFile().getName();
                if (!normalizedQuery.isEmpty()
                        && !setting.getKey().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !group.getTitle().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !value.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    continue;
                }
                overridesModel.addRow(new Object[] {setting.getKey(), value, inherited, source});
            }
        }
    }

    private void showSelectedGroup() {
        SettingsGroup group = groupList.getSelectedValue();
        settingsPanel.removeAll();
        if (group != null) {
            settingsPanel.add(buildGroupHeader(group));
            for (SettingDefinition setting : group.getSettings()) {
                settingsPanel.add(editorFor(group, setting).getComponent());
                settingsPanel.add(new JSeparator());
            }
        }
        settingsPanel.revalidate();
        settingsPanel.repaint();
        SwingUtilities.invokeLater(() -> settingsScroll.getVerticalScrollBar().setValue(0));
    }

    private void showSearchResults(String query) {
        settingsPanel.removeAll();
        int matches = 0;
        for (SettingsGroup group : groups) {
            boolean headerAdded = false;
            for (SettingDefinition setting : group.getSettings()) {
                SettingEditor editor = editorFor(group, setting);
                if (!editor.matches(query)) {
                    continue;
                }
                if (!headerAdded) {
                    settingsPanel.add(buildGroupHeader(group));
                    headerAdded = true;
                }
                settingsPanel.add(editor.getComponent());
                settingsPanel.add(new JSeparator());
                matches++;
            }
        }
        if (matches == 0) {
            JLabel none = new JLabel(JMeterUtils.getResString("settings_no_matches"));
            none.setBorder(BorderFactory.createEmptyBorder(20, 10, 20, 10));
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            settingsPanel.add(none);
        }
        settingsPanel.revalidate();
        settingsPanel.repaint();
        SwingUtilities.invokeLater(() -> settingsScroll.getVerticalScrollBar().setValue(0));
    }

    private static JPanel buildGroupHeader(SettingsGroup group) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel(group.getTitle());
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        if (!group.getDescription().isEmpty()) {
            JLabel description = new JLabel("<html>" + group.getDescription() + "</html>");
            description.setForeground(UIManager.getColor("Label.disabledForeground"));
            description.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(description);
        }
        return header;
    }

    private void updateModifiedState() {
        long modified = editors.values().stream().filter(SettingEditor::isModified).count();
        saveButton.setEnabled(modified > 0);
        modifiedLabel.setText(modified == 0 ? " "
                : MessageFormat.format(JMeterUtils.getResString("settings_modified_count"), modified));
    }

    private void save() {
        Map<SettingsGroup.Target, Map<String, String>> overridesByTarget = new LinkedHashMap<>();
        Map<SettingsGroup.Target, Set<String>> removalsByTarget = new LinkedHashMap<>();
        for (SettingEditor editor : editors.values()) {
            SettingsGroup.Target target = editor.getGroup().getTarget();
            if (editor.isSetRequested()) {
                overridesByTarget.computeIfAbsent(target, t -> new LinkedHashMap<>())
                        .put(editor.getSetting().getKey(), editor.getControlValue());
            } else if (editor.isRemoveRequested()) {
                removalsByTarget.computeIfAbsent(target, t -> new TreeSet<>())
                        .add(editor.getSetting().getKey());
            }
        }
        try {
            for (SettingsGroup.Target target : SettingsGroup.Target.values()) {
                model.apply(target,
                        overridesByTarget.getOrDefault(target, Map.of()),
                        removalsByTarget.getOrDefault(target, Set.of()));
            }
        } catch (IOException e) {
            log.error("Failed to save settings", e);
            JOptionPane.showMessageDialog(this,
                    MessageFormat.format(JMeterUtils.getResString("settings_save_error"), e.getMessage()),
                    JMeterUtils.getResString("settings_title"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        for (SettingEditor editor : editors.values()) {
            editor.rebase();
        }
        updateModifiedState();
        refreshView();
        JOptionPane.showMessageDialog(this,
                JMeterUtils.getResString("settings_saved_message"),
                JMeterUtils.getResString("settings_title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Keeps setting rows constrained to the visible viewport width. */
    private static final class ViewportWidthPanel extends JPanel implements Scrollable {

        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
