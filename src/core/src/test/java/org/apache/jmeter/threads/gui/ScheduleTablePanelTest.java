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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.openmodel.ThreadScheduleUtils;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

class ScheduleTablePanelTest {
    @Test
    void closedPhasesCanBeEditedReorderedAndRemoved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScheduleTablePanel panel = new ScheduleTablePanel(false);
            panel.setText("threadsPhase(10, 20) threadsPhase(30, 40)");
            JTable table = find(panel, JTable.class);
            assertEquals(2, table.getRowCount());
            table.setValueAt("50", 0, 1);
            table.setRowSelectionInterval(0, 0);
            click(panel, "down");
            assertEquals("threadsPhase(30, 40)\nthreadsPhase(10, 50)", panel.getText());
            click(panel, "delete");
            assertEquals("threadsPhase(30, 40)", panel.getText());
            click(panel, "add");
            assertEquals("threadsPhase(30, 40)\nthreadsPhase(10, 10)", panel.getText());
        });
    }

    @Test
    void openScheduleUsesFromToDurationAndRandomColumns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScheduleTablePanel panel = new ScheduleTablePanel(true);
            panel.setText("rate(2/sec) random_arrivals(1 min 10 sec) rate(3/sec) pause(1 min)");
            JTable table = find(panel, JTable.class);
            assertEquals(2, table.getRowCount());
            assertEquals(4, table.getColumnCount());
            assertEquals(Boolean.class, table.getColumnClass(3));
            assertEquals(true, table.getValueAt(0, 3));
            assertEquals(false, table.getValueAt(1, 3));
            assertEquals("0", table.getValueAt(1, 0));
            table.setValueAt(false, 0, 3);
            table.setValueAt("25", 0, 0);
            assertTrue(panel.getText().contains("rampThreadsPerMinDuring(25, 180, 70, false)"));
            assertTrue(table.editCellAt(0, 0));
            assertTrue(table.getEditorComponent() instanceof JTextField);
            table.getCellEditor().cancelCellEditing();
            table.setValueAt("", 0, 1);
            assertTrue(panel.getText().contains("rampThreadsPerMinDuring(25, 25, 70, false)"));
            table.setValueAt(true, 0, 3);
            table.setValueAt("50", 0, 1);
            assertTrue(panel.getText().contains("rampThreadsPerMinDuring(25, 50, 70, true)"));
            try {
                ThreadScheduleUtils.ThreadSchedule(panel.getText());
            } catch (Exception exception) {
                throw new AssertionError("Table must produce a valid schedule", exception);
            }
        });
    }

    @Test
    void constantSchedulesAndBlankTargetsSaveAsRamps() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScheduleTablePanel panel = new ScheduleTablePanel(true);
            panel.setText("constantThreadsPerMinDuring(120, 30, true)");
            JTable table = find(panel, JTable.class);
            assertEquals("", table.getValueAt(0, 1));
            assertEquals("rampThreadsPerMinDuring(120, 120, 30, true)", panel.getText());
            table.setValueAt("60", 0, 0);
            assertEquals("rampThreadsPerMinDuring(60, 60, 30, true)", panel.getText());
            table.setValueAt("   ", 0, 1);
            assertEquals("rampThreadsPerMinDuring(60, 60, 30, true)", panel.getText());
            table.setValueAt("60", 0, 1);
            assertEquals("rampThreadsPerMinDuring(60, 60, 30, true)", panel.getText());
            String saved = panel.getText();
            panel.setText(saved);
            assertEquals(saved, panel.getText());
            assertEquals("60", table.getValueAt(0, 1));
            JCheckBox mode = find(panel, JCheckBox.class);
            mode.doClick();
            assertEquals(saved, find(panel, JTextArea.class).getText());
            mode.doClick();
            assertEquals(saved, panel.getText());
            panel.setText("");
            click(panel, "add");
            assertEquals("", table.getValueAt(0, 1));
            assertEquals("rampThreadsPerMinDuring(20, 20, 15, false)", panel.getText());
        });
    }

    @Test
    void commentsRemainInTextViewWithoutLosingContent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScheduleTablePanel panel = new ScheduleTablePanel(true);
            for (String schedule : new String[] {
                "/* warmup */ constantThreadsPerMinDuring(120, 10, true)",
                "constantThreadsPerMinDuring(/* warmup */ 120, 10, true)"
            }) {
                panel.setText(schedule);
                JCheckBox mode = find(panel, JCheckBox.class);
                assertTrue(mode.isSelected());
                assertEquals(schedule, find(panel, JTextArea.class).getText());
                mode.doClick();
                assertTrue(mode.isSelected());
                assertEquals(schedule, panel.getText());
            }
        });
    }

    @Test
    void advancedExpressionsRemainUnchangedAndCanReturnToTable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScheduleTablePanel panel = new ScheduleTablePanel(true);
            String expression = "${__groovy(props.get('schedule'))}";
            panel.setText(expression);
            JCheckBox mode = find(panel, JCheckBox.class);
            assertTrue(mode.isSelected());
            assertEquals(expression, panel.getText());
            mode.doClick();
            assertTrue(mode.isSelected());
            assertEquals(expression, panel.getText());
            find(panel, JTextArea.class).setText("rate(2/sec) pause(10 sec)");
            mode.doClick();
            assertFalse(mode.isSelected());
            assertEquals(1, find(panel, JTable.class).getRowCount());
            assertEquals("rampThreadsPerMinDuring(0, 0, 10, false)", panel.getText());
            panel.setText("");
            assertEquals(0, find(panel, JTable.class).getRowCount());
            assertEquals("", panel.getText());
        });
    }

    @Test
    void savingThreadGroupCommitsActiveCellAndConfigureDiscardsOldEdit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThreadGroupGui gui = new ThreadGroupGui();
            ThreadGroup group = (ThreadGroup) gui.createTestElement();
            group.setClosedModelSchedule("threadsPhase(10, 20)");
            gui.configure(group);
            JTable table = find(gui, JTable.class);
            assertTrue(table.editCellAt(0, 0));
            ((JTextField) table.getEditorComponent()).setText("99");
            gui.modifyTestElement(group);
            assertEquals("threadsPhase(99, 20)", group.getClosedModelSchedule());
            assertTrue(table.editCellAt(0, 0));
            ((JTextField) table.getEditorComponent()).setText("123");
            gui.configure(group);
            assertEquals("99", table.getValueAt(0, 0));
        });
    }

    private static void click(Container root, String key) {
        String label = JMeterUtils.getResString(key);
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) {
                button.doClick();
                return;
            }
            if (child instanceof Container container) {
                try {
                    click(container, key);
                    return;
                } catch (IllegalArgumentException ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new IllegalArgumentException(label);
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T result = find(container, type);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}
