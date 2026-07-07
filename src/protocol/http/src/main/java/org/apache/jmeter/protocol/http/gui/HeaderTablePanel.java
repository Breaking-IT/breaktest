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

package org.apache.jmeter.protocol.http.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.TableModelListener;
import javax.swing.table.AbstractTableModel;

import org.apache.jmeter.gui.util.FileDialoger;
import org.apache.jmeter.gui.util.HeaderAsPropertyRenderer;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.GuiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable name/value header table with Add / Add-from-Clipboard / Delete buttons
 * (plus optional Load / Save from file). Backs both the standalone HTTP Header Manager
 * editor ({@link HeaderPanel}) and the Headers tab of the HTTP Request sampler.
 */
public class HeaderTablePanel extends JPanel implements ActionListener {

    private static final Logger log = LoggerFactory.getLogger(HeaderTablePanel.class);

    private static final long serialVersionUID = 241L;

    private static final String CLIPBOARD_LINE_DELIMITER = "\n";
    private static final String CLIPBOARD_COLON_DELIMITER = ":";
    private static final String CLIPBOARD_TAB_DELIMITER = "\t";
    private static final String ADD_COMMAND = "Add"; // $NON-NLS-1$
    private static final String DELETE_COMMAND = "Delete"; // $NON-NLS-1$
    private static final String LOAD_COMMAND = "Load"; // $NON-NLS-1$
    private static final String SAVE_COMMAND = "Save"; // $NON-NLS-1$
    private static final String ADD_FROM_CLIPBOARD = "addFromClipboard"; // $NON-NLS-1$

    private final HeaderManager headerManager;
    private final InnerTableModel tableModel;
    private final boolean showLoadSave;
    private JTable headerTable;
    private JButton deleteButton;
    private JButton saveButton;

    public HeaderTablePanel(boolean showLoadSave) {
        this(new HeaderManager(), showLoadSave);
    }

    public HeaderTablePanel(HeaderManager headerManager, boolean showLoadSave) {
        this.headerManager = headerManager;
        this.showLoadSave = showLoadSave;
        this.tableModel = new InnerTableModel(headerManager);
        init();
    }

    /**
     * @return the {@link HeaderManager} backing the table
     */
    public HeaderManager getModel() {
        return headerManager;
    }

    /**
     * Commit any in-progress cell edit into the model.
     */
    public void stopEditing() {
        GuiUtils.stopTableEditing(headerTable);
    }

    /**
     * Re-read the model and repaint (call after mutating the backing manager externally).
     */
    public void refresh() {
        tableModel.fireTableDataChanged();
        checkButtonsStatus();
    }

    /**
     * Remove all headers from the table.
     */
    public void clear() {
        GuiUtils.stopTableEditing(headerTable);
        tableModel.clearData();
        checkButtonsStatus();
    }

    /**
     * Replace the table content with the given headers.
     * Headers are cloned so later edits don't mutate the caller's instances.
     *
     * @param headers headers to show
     */
    public void setHeaders(List<Header> headers) {
        headerManager.clear();
        for (Header header : headers) {
            headerManager.add((Header) header.clone());
        }
        refresh();
    }

    /**
     * @return a snapshot of the table content as cloned {@link Header} instances,
     *         skipping rows with an empty name
     */
    public List<Header> getHeaders() {
        stopEditing();
        List<Header> result = new ArrayList<>(headerManager.size());
        for (int i = 0; i < headerManager.size(); i++) {
            Header header = headerManager.get(i);
            if (!header.getName().isEmpty()) {
                result.add((Header) header.clone());
            }
        }
        return result;
    }

    /**
     * @return the number of headers with a non-empty name
     */
    public int getHeaderCount() {
        stopEditing();
        int count = 0;
        for (int i = 0; i < headerManager.size(); i++) {
            if (!headerManager.get(i).getName().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Add a listener for table row/cell changes.
     *
     * @param listener listener to notify
     */
    public void addTableModelListener(TableModelListener listener) {
        tableModel.addTableModelListener(listener);
    }

    private void init() { // called from ctor, so must not be overridable
        setLayout(new BorderLayout(0, 5));
        headerTable = new JTable(tableModel);
        JMeterUtils.applyHiDPI(headerTable);
        headerTable.getTableHeader().setDefaultRenderer(new HeaderAsPropertyRenderer());
        headerTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        headerTable.setPreferredScrollableViewportSize(new Dimension(100, 70));

        add(GuiUtils.emptyBorder(new JScrollPane(headerTable)), BorderLayout.CENTER);
        add(createButtonPanel(), BorderLayout.SOUTH);
    }

    private void checkButtonsStatus() {
        boolean hasRows = tableModel.getRowCount() != 0;
        deleteButton.setEnabled(hasRows);
        if (saveButton != null) {
            saveButton.setEnabled(hasRows);
        }
    }

    /**
     * Remove the currently selected rows from the table.
     */
    protected void deleteRows() {
        // If a table cell is being edited, we must cancel the editing
        // before deleting the row.
        GuiUtils.cancelEditing(headerTable);

        int[] rowsSelected = headerTable.getSelectedRows();
        int anchorSelection = headerTable.getSelectionModel().getAnchorSelectionIndex();
        headerTable.clearSelection();
        if (rowsSelected.length > 0) {
            for (int i = rowsSelected.length - 1; i >= 0; i--) {
                tableModel.removeRow(rowsSelected[i]);
            }
            tableModel.fireTableDataChanged();

            // Table still contains one or more rows, so highlight (select)
            // the appropriate one.
            if (tableModel.getRowCount() > 0) {
                if (anchorSelection >= tableModel.getRowCount()) {
                    anchorSelection = tableModel.getRowCount() - 1;
                }
                headerTable.setRowSelectionInterval(anchorSelection, anchorSelection);
            }

            checkButtonsStatus();
        } else {
            if (tableModel.getRowCount() > 0) {
                tableModel.removeRow(0);
                tableModel.fireTableDataChanged();
                headerTable.setRowSelectionInterval(0, 0);
            }
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String action = e.getActionCommand();

        if (action.equals(DELETE_COMMAND)) {
            deleteRows();
        } else if (action.equals(ADD_COMMAND)) {
            // If a table cell is being edited, we should accept the current
            // value and stop the editing before adding a new row.
            GuiUtils.stopTableEditing(headerTable);

            tableModel.addNewRow();
            tableModel.fireTableDataChanged();

            checkButtonsStatus();

            // Highlight (select) the appropriate row.
            int rowToSelect = tableModel.getRowCount() - 1;
            headerTable.setRowSelectionInterval(rowToSelect, rowToSelect);
        } else if (action.equals(LOAD_COMMAND)) {
            try {
                final JFileChooser chooser = FileDialoger.promptToOpenFile();
                if (chooser != null) {
                    headerManager.addFile(chooser.getSelectedFile().getAbsolutePath());
                    tableModel.fireTableDataChanged();

                    checkButtonsStatus();
                }
            } catch (IOException ex) {
                log.error("Could not load headers", ex);
            }
        } else if (action.equals(SAVE_COMMAND)) {
            try {
                final JFileChooser chooser = FileDialoger.promptToSaveFile(null);
                if (chooser != null) {
                    headerManager.save(chooser.getSelectedFile().getAbsolutePath());
                }
            } catch (IOException ex) {
                JMeterUtils.reportErrorToUser(ex.getMessage(), "Error saving headers");
            }
        } else if (action.equals(ADD_FROM_CLIPBOARD)) {
            addFromClipboard();
        }
    }

    /**
     * Add values from the clipboard.
     * The clipboard is first split into lines, and the lines are then split on ':' or '\t'
     * to produce the header name and value.
     * Lines without a ':' are tested with '\t' and ignored if not found.
     */
    protected void addFromClipboard() {
        GuiUtils.stopTableEditing(headerTable);
        int rowCount = headerTable.getRowCount();
        try {
            String clipboardContent = GuiUtils.getPastedText();
            if (clipboardContent == null) {
                return;
            }
            String[] clipboardLines = clipboardContent.split(CLIPBOARD_LINE_DELIMITER); // $NON-NLS-1$
            for (String clipboardLine : clipboardLines) {
                int index = clipboardLine.indexOf(CLIPBOARD_COLON_DELIMITER); // $NON-NLS-1$
                if (index < 0) {
                    // when pasting from another header panel the values are separated with '\t'
                    index = clipboardLine.indexOf(CLIPBOARD_TAB_DELIMITER);
                }
                if (index > 0) {
                    Header header = new Header(clipboardLine.substring(0, index).trim(), clipboardLine.substring(index + 1).trim());
                    headerManager.add(header);
                }
            }
            tableModel.fireTableDataChanged();
            if (headerTable.getRowCount() > rowCount) {
                // Highlight (select) the appropriate rows.
                int rowToSelect = tableModel.getRowCount() - 1;
                headerTable.setRowSelectionInterval(rowCount, rowToSelect);
            }

            checkButtonsStatus();
        } catch (IOException ioe) {
            JOptionPane.showMessageDialog(this,
                    "Could not add read headers from clipboard:\n" + ioe.getLocalizedMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        } catch (UnsupportedFlavorException ufe) {
            JOptionPane.showMessageDialog(this,
                    "Could not add retrieved " + DataFlavor.stringFlavor.getHumanPresentableName()
                            + " from clipboard" + ufe.getLocalizedMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JButton createButton(String resName, char mnemonic, String command, boolean enabled) {
        JButton button = new JButton(JMeterUtils.getResString(resName));
        button.setMnemonic(mnemonic);
        button.setActionCommand(command);
        button.setEnabled(enabled);
        button.addActionListener(this);
        return button;
    }

    private JPanel createButtonPanel() {
        boolean tableEmpty = tableModel.getRowCount() == 0;

        JButton addButton = createButton("add", 'A', ADD_COMMAND, true); // $NON-NLS-1$
        JButton addFromClipboard = createButton("add_from_clipboard", 'C', ADD_FROM_CLIPBOARD, true); // $NON-NLS-1$
        deleteButton = createButton("delete", 'D', DELETE_COMMAND, !tableEmpty); // $NON-NLS-1$

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(addButton);
        buttonPanel.add(addFromClipboard);
        buttonPanel.add(deleteButton);
        if (showLoadSave) {
            JButton loadButton = createButton("load", 'L', LOAD_COMMAND, true); // $NON-NLS-1$
            saveButton = createButton("save", 'S', SAVE_COMMAND, !tableEmpty); // $NON-NLS-1$
            buttonPanel.add(loadButton);
            buttonPanel.add(saveButton);
        }
        return buttonPanel;
    }

    private static class InnerTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 240L;

        private final HeaderManager manager;

        private InnerTableModel(HeaderManager man) {
            manager = man;
        }

        private void clearData() {
            manager.clear();
            fireTableDataChanged();
        }

        private void removeRow(int row) {
            manager.remove(row);
        }

        private void addNewRow() {
            manager.add();
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            // all table cells are editable
            return true;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return getValueAt(0, column).getClass();
        }

        @Override
        public int getRowCount() {
            return manager.getHeaders().size();
        }

        @Override
        public int getColumnCount() {
            return manager.getColumnCount();
        }

        @Override
        public String getColumnName(int column) {
            return manager.getColumnName(column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            Header head = manager.getHeader(row);
            if (column == 0) {
                return head.getName();
            } else if (column == 1) {
                return head.getValue();
            }
            return null;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            Header header = manager.getHeader(row);

            if (column == 0) {
                header.setName((String) value);
            } else if (column == 1) {
                header.setValue((String) value);
            }
            fireTableCellUpdated(row, column);
        }
    }
}
