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

package org.apache.jmeter.threads.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.threads.openmodel.OpenModelScheduleMigration;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JFactory;

import net.miginfocom.swing.MigLayout;

/** Edits schedule steps without changing the persisted schedule language. */
class ScheduleTablePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String CONSTANT = "constantThreadsPerMinDuring";
    private static final String RAMP = "rampThreadsPerMinDuring";
    private static final Pattern STEP = Pattern.compile(
            "\\s*(?:([a-zA-Z_]+)\\s*\\(([^()]*)\\)|/\\*(.*?)\\*/)", Pattern.DOTALL);

    private final boolean openModel;
    private final DefaultTableModel model;
    private final JTable table;
    private final JTextArea source = JFactory.tabMovesFocus(new JTextArea(5, 42));
    private final JCheckBox sourceMode = new JCheckBox(text("thread_group_schedule_edit_text"));
    private final JPanel cards = new JPanel(new CardLayout());
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean updating;
    private String originalText = "";
    private JLabel scheduleLabel;

    ScheduleTablePanel(boolean openModel) {
        super(new BorderLayout(0, 6));
        this.openModel = openModel;
        String[] columns = openModel
                ? new String[] {text("thread_group_schedule_start_rate"),
                    text("thread_group_schedule_end_rate"), text("thread_group_schedule_duration_seconds"),
                    text("thread_group_schedule_random_arrivals")}
                : new String[] {text("thread_group_schedule_threads"), text("thread_group_schedule_duration_seconds")};
        model = new DefaultTableModel(columns, 0) {
            @Override
            public void setValueAt(Object value, int row, int column) {
                if (!Objects.equals(value, getValueAt(row, column))) {
                    super.setValueAt(value, row, column);
                }
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return openModel && column == 3 ? Boolean.class : String.class;
            }

        };
        table = new JTable(model);
        table.setName(openModel ? "openModelScheduleTable" : "closedModelScheduleTable");
        table.setDefaultEditor(String.class, new NumericCellEditor());
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
                    boolean focused, int row, int column) {
                Component component = super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
                setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                setToolTipText(value == null ? null : value.toString());
                return component;
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(28, table.getRowHeight() + 8));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.setPreferredScrollableViewportSize(new Dimension(720, table.getRowHeight() * 5));
        JPanel tablePanel = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1", "[fill, 0:720:720]push", "[grow,fill][]"));
        tablePanel.add(new JScrollPane(table), "growy");
        JPanel actions = new JPanel(new MigLayout("insets 0", "[][][][]push"));
        JButton add = button("add", () -> {
            if (!stopEditing()) {
                return;
            }
            model.addRow(openModel ? new Object[] {"20", "", "15", false} : new Object[] {"10", "10"});
            select(model.getRowCount() - 1);
        });
        JButton remove = button("delete", () -> {
            if (!stopEditing()) {
                return;
            }
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeRow(row);
                select(Math.min(row, model.getRowCount() - 1));
            }
        });
        JButton up = button("up", () -> move(-1));
        JButton down = button("down", () -> move(1));
        actions.add(add);
        actions.add(remove);
        actions.add(up);
        actions.add(down);
        Runnable updateActions = () -> {
            int row = table.getSelectedRow();
            remove.setEnabled(row >= 0);
            up.setEnabled(row > 0);
            down.setEnabled(row >= 0 && row < model.getRowCount() - 1);
        };
        table.getSelectionModel().addListSelectionListener(event -> updateActions.run());
        updateActions.run();
        tablePanel.add(actions);
        cards.add(tablePanel, "table");
        cards.add(new JScrollPane(source), "source");
        add(cards, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.add(sourceMode, BorderLayout.NORTH);
        if (openModel) {
            JLabel hint = new JLabel(text("thread_group_schedule_table_hint"));
            hint.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
            footer.add(hint, BorderLayout.SOUTH);
        }
        add(footer, BorderLayout.SOUTH);
        sourceMode.addActionListener(event -> {
            if (!stopEditing()) {
                sourceMode.setSelected(false);
                return;
            }
            if (sourceMode.isSelected()) {
                updating = true;
                try {
                    source.setText(originalText);
                } finally {
                    updating = false;
                }
            } else {
                String migrated = openModel
                        ? OpenModelScheduleMigration.migrateOpenModelSchedule(source.getText()) : source.getText();
                if (!loadRows(migrated)) {
                    sourceMode.setSelected(true);
                }
            }
            showCard();
        });
        model.addTableModelListener(event -> {
            if (!updating) {
                originalText = serialize();
                changed();
            }
        });
        source.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                sourceChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                sourceChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                sourceChanged();
            }
        });
    }

    private void sourceChanged() {
        if (!updating && sourceMode.isSelected()) {
            originalText = source.getText();
            changed();
        }
    }

    private static String text(String key) {
        return JMeterUtils.getResString(key);
    }

    private static JButton button(String key, Runnable action) {
        JButton button = new JButton(text(key));
        button.addActionListener(event -> action.run());
        return button;
    }

    private void select(int row) {
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
    }

    private void move(int direction) {
        if (!stopEditing()) {
            return;
        }
        int row = table.getSelectedRow();
        int target = row + direction;
        if (row >= 0 && target >= 0 && target < model.getRowCount()) {
            model.moveRow(row, row, target);
            select(target);
        }
    }

    boolean stopEditing() {
        return !table.isEditing() || table.getCellEditor().stopCellEditing();
    }

    String getText() {
        return originalText;
    }

    void setText(String value) {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        updating = true;
        try {
            originalText = value;
            source.setText(value);
            String display = openModel ? OpenModelScheduleMigration.migrateOpenModelSchedule(value) : value;
            sourceMode.setSelected(!loadRows(display));
            showCard();
        } finally {
            updating = false;
        }
        changed();
    }

    void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }

    JLabel createLabel(String key) {
        String name = text(key);
        scheduleLabel = new JLabel(name);
        table.getAccessibleContext().setAccessibleName(name);
        source.getAccessibleContext().setAccessibleName(name);
        showCard();
        return scheduleLabel;
    }

    private void showCard() {
        if (scheduleLabel != null) {
            scheduleLabel.setLabelFor(sourceMode.isSelected() ? source : table);
        }
        ((CardLayout) cards.getLayout()).show(cards, sourceMode.isSelected() ? "source" : "table");
        sourceMode.setToolTipText(text("thread_group_schedule_text_hint"));
    }

    private boolean loadRows(String value) {
        List<Object[]> rows = new ArrayList<>();
        Matcher matcher = STEP.matcher(value);
        int position = 0;
        while (position < value.length() && !value.substring(position).isBlank()) {
            matcher.region(position, value.length());
            if (!matcher.lookingAt()) {
                return false;
            }
            Object[] row = parseRow(matcher.group(1), matcher.group(2), matcher.group(3));
            if (row == null) {
                return false;
            }
            for (int column = 0; column < (openModel ? 3 : 2); column++) {
                if (!validNumber(row[column].toString(), column)) {
                    return false;
                }
            }
            rows.add(row);
            position = matcher.end();
        }
        boolean wasUpdating = updating;
        updating = true;
        try {
            model.setRowCount(0);
            rows.forEach(model::addRow);
        } finally {
            updating = wasUpdating;
        }
        return true;
    }

    private Object[] parseRow(String function, String arguments, String comment) {
        if (comment != null || arguments.contains("/*")) {
            return null;
        }
        String[] args = arguments.split(",", -1);
        for (int i = 0; i < args.length; i++) {
            args[i] = args[i].trim();
        }
        if (!openModel) {
            return "threadsPhase".equalsIgnoreCase(function) && args.length == 2 ? args : null;
        }
        boolean constant = CONSTANT.equalsIgnoreCase(function);
        boolean ramp = RAMP.equalsIgnoreCase(function);
        if (!constant && !ramp) {
            return null;
        }
        int flagIndex = constant ? 2 : 3;
        if (args.length != flagIndex && args.length != flagIndex + 1) {
            return null;
        }
        boolean random = false;
        if (args.length > flagIndex) {
            if (!"true".equalsIgnoreCase(args[flagIndex]) && !"false".equalsIgnoreCase(args[flagIndex])) {
                return null;
            }
            random = Boolean.parseBoolean(args[flagIndex]);
        }
        return new Object[] {args[0], constant ? "" : args[1],
            args[flagIndex - 1], random};
    }

    private String cell(int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString();
    }

    private static boolean sameRate(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        try {
            return new BigDecimal(from).compareTo(new BigDecimal(to)) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean validNumber(String value, int column) {
        String number = value.trim();
        if (openModel && column == 1 && number.isEmpty()) {
            return true;
        }
        if (openModel && number.matches("\\$\\{[^{}]+}")) {
            return true;
        }
        try {
            if (openModel) {
                return number.matches("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")
                        && Double.isFinite(Double.parseDouble(number));
            }
            return number.matches("[0-9]+") && Long.parseLong(number) >= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private class NumericCellEditor extends DefaultCellEditor {
        private int column;

        NumericCellEditor() {
            super(new JTextField());
        }

        @Override
        public Component getTableCellEditorComponent(JTable owner, Object value, boolean selected, int row, int col) {
            column = col;
            JTextField field = (JTextField) super.getTableCellEditorComponent(owner, value, selected, row, col);
            field.setBorder(UIManager.getBorder("TextField.border"));
            field.setToolTipText(null);
            field.getAccessibleContext().setAccessibleDescription(null);
            return field;
        }

        @Override
        public boolean stopCellEditing() {
            JTextField field = (JTextField) getComponent();
            if (!validNumber(field.getText(), column)) {
                Color error = UIManager.getColor("Component.error.borderColor");
                field.setBorder(BorderFactory.createLineBorder(error == null ? Color.RED : error, 2));
                String message = text(openModel ? "thread_group_schedule_invalid_number"
                        : "thread_group_schedule_invalid_integer");
                field.setToolTipText(message);
                field.getAccessibleContext().setAccessibleDescription(message);
                return false;
            }
            return super.stopCellEditing();
        }
    }

    private String serialize() {
        List<String> steps = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            if (!openModel) {
                steps.add("threadsPhase(" + cell(row, 0) + ", " + cell(row, 1) + ")");
                continue;
            }
            String from = cell(row, 0);
            String to = cell(row, 1);
            if (to.isBlank()) {
                to = from;
            }
            String random = Boolean.TRUE.equals(model.getValueAt(row, 3)) ? ", true" : "";
            if (sameRate(from, to)) {
                steps.add(CONSTANT + "(" + from + ", " + cell(row, 2) + random + ")");
            } else {
                steps.add(RAMP + "(" + from + ", " + to + ", " + cell(row, 2) + random + ")");
            }
        }
        return String.join("\n", steps);
    }
}
