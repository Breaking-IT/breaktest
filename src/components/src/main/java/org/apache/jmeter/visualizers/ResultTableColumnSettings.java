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

package org.apache.jmeter.visualizers;

import java.awt.Component;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

/** Column visibility stored by stable column names in each listener's test element. */
final class ResultTableColumnSettings {
    private final JTable resultTable;
    private final TableColumn[] resultTableColumns;
    private final boolean[] selectedResultTableColumns = ResultTableModel.defaultVisibleColumns();

    ResultTableColumnSettings(JTable table) {
        resultTable = table;
        resultTableColumns = captureTableColumns(table);
        applySelectedResultTableColumns();
    }

    void configure(TestElement element) {
        boolean[] defaults = ResultTableModel.defaultVisibleColumns();
        for (int i = 0; i < defaults.length; i++) {
            selectedResultTableColumns[i] = element.getPropertyAsBoolean(propertyName(i), defaults[i]);
        }
        applySelectedResultTableColumns();
    }

    void save(TestElement element) {
        boolean[] defaults = ResultTableModel.defaultVisibleColumns();
        for (int i = 0; i < selectedResultTableColumns.length; i++) {
            element.setProperty(propertyName(i), selectedResultTableColumns[i], defaults[i]);
        }
    }

    private static String propertyName(int column) {
        return "ViewResultsFullVisualizer.column." + (column == ResultTableModel.STATUS
                ? "status" : ResultTableModel.COLUMNS[column]);
    }

    void showMenu(Component invoker) {
        JPopupMenu popup = new JPopupMenu();
        for (int modelColumn = 0; modelColumn < resultTableColumns.length; modelColumn++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(
                    resultTableColumnConfigurationLabel(modelColumn), selectedResultTableColumns[modelColumn]);
            final int column = modelColumn;
            item.addActionListener(event -> {
                selectedResultTableColumns[column] = item.isSelected();
                applySelectedResultTableColumns();
            });
            popup.add(item);
        }
        popup.show(invoker, 0, invoker.getHeight());
    }

    private String resultTableColumnConfigurationLabel(int modelColumn) {
        return modelColumn == ResultTableModel.STATUS
                ? JMeterUtils.getResString("table_visualizer_status") // $NON-NLS-1$
                : resultTable.getModel().getColumnName(modelColumn);
    }

    private void applySelectedResultTableColumns() {
        TableColumnModel columnModel = resultTable.getColumnModel();
        for (int modelColumn = 0; modelColumn < resultTableColumns.length; modelColumn++) {
            boolean shouldShow = selectedResultTableColumns[modelColumn];
            boolean isVisible = isColumnVisible(columnModel, resultTableColumns[modelColumn]);
            if (shouldShow && !isVisible) {
                columnModel.addColumn(resultTableColumns[modelColumn]);
                columnModel.moveColumn(columnModel.getColumnCount() - 1, countVisibleResultTableColumnsBefore(modelColumn));
            } else if (!shouldShow && isVisible) {
                columnModel.removeColumn(resultTableColumns[modelColumn]);
            }
        }
    }

    private static TableColumn[] captureTableColumns(JTable table) {
        TableColumnModel columnModel = table.getColumnModel();
        TableColumn[] columns = new TableColumn[columnModel.getColumnCount()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = columnModel.getColumn(i);
        }
        return columns;
    }

    private static boolean isColumnVisible(TableColumnModel columnModel, TableColumn column) {
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            if (columnModel.getColumn(i) == column) {
                return true;
            }
        }
        return false;
    }

    private int countVisibleResultTableColumnsBefore(int modelColumn) {
        int count = 0;
        for (int i = 0; i < modelColumn; i++) {
            if (selectedResultTableColumns[i]) {
                count++;
            }
        }
        return count;
    }

}
