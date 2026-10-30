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

import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.TransferHandler;
import javax.swing.event.TableModelListener;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.GuiUtils;
import org.apache.jorphan.gui.ObjectTableModel;
import org.apache.jorphan.reflect.Functor;
import org.apache.jorphan.util.BooleanUtils;
import org.apache.jorphan.util.StringUtilities;

/**
 * A GUI panel allowing the user to enter HTTP Parameters.
 * These have names and values, as well as check-boxes to determine whether or not to
 * include the "=" sign in the output and whether or not to encode the output and
 * whether or not to enable them.
 */
public class HTTPArgumentsPanel extends ArgumentsPanel {

    private static final long serialVersionUID = 240L;

    private static final String ENABLE = "enable"; //$NON-NLS-1$

    private static final String ENCODE_OR_NOT = "encode?"; //$NON-NLS-1$

    private static final String INCLUDE_EQUALS = "include_equals"; //$NON-NLS-1$

    private static final String CONTENT_TYPE = "content_type";

    /** When pasting from the clipboard, split lines on linebreak or '&' */
    static final String CLIPBOARD_LINE_DELIMITERS = "\r?\n|&"; //$NON-NLS-1$

    /** When pasting from the clipboard, split parameters on tab or '=' */
    static final String CLIPBOARD_ARG_DELIMITERS = "\t|="; //$NON-NLS-1$

    @Override
    protected void initializeTableModel() {
        tableModel = new ObjectTableModel(new String[] {
                ENABLE, ArgumentsPanel.COLUMN_RESOURCE_NAMES_0, ArgumentsPanel.COLUMN_RESOURCE_NAMES_1, ENCODE_OR_NOT, CONTENT_TYPE, INCLUDE_EQUALS },
                HTTPArgument.class,
                new Functor[] {
                new Functor("isEnabled"), //$NON-NLS-1$
                new Functor("getName"), //$NON-NLS-1$
                new Functor("getValue"), //$NON-NLS-1$
                new Functor("isAlwaysEncoded"), //$NON-NLS-1$
                new Functor("getContentType"), //$NON-NLS-1$
                new Functor("isUseEquals") }, //$NON-NLS-1$
                new Functor[] {
                new Functor("setEnabled"), //$NON-NLS-1$
                new Functor("setName"), //$NON-NLS-1$
                new Functor("setValue"), //$NON-NLS-1$
                new Functor("setAlwaysEncoded"), //$NON-NLS-1$
                new Functor("setContentType"),
                new Functor("setUseEquals")}, //$NON-NLS-1$
                new Class[] {Boolean.class, String.class, String.class, Boolean.class, String.class, Boolean.class });
    }

    public static boolean testFunctors(){
        HTTPArgumentsPanel instance = new HTTPArgumentsPanel();
        instance.initializeTableModel();
        return instance.tableModel.checkFunctors(null,instance.getClass());
    }

    @Override
    protected void sizeColumns(JTable table) {
        GuiUtils.fixSize(table.getColumn(ENABLE), table);
        GuiUtils.fixSize(table.getColumn(INCLUDE_EQUALS), table);
        GuiUtils.fixSize(table.getColumn(ENCODE_OR_NOT), table);
    }

    @Override
    protected HTTPArgument makeNewArgument() {
        HTTPArgument arg = new HTTPArgument("", "");
        arg.setAlwaysEncoded(false);
        arg.setUseEquals(true);
        return arg;
    }

    public HTTPArgumentsPanel() {
        super(JMeterUtils.getResString("paramtable")); //$NON-NLS-1$
        init();
        clearBorderForMainPanel();
    }

    @Override
    public Arguments createTestElement() {
        Arguments args = getUnclonedParameters();
        assignDefaultValues(args);
        return (Arguments) args.clone();
    }

    /**
     * Convert the argument panel contents to an {@link Arguments} collection.
     *
     * @return a collection of {@link HTTPArgument} entries
     */
    public Arguments getParameters() {
        Arguments args = getUnclonedParameters();
        return (Arguments) args.clone();
    }

    /**
     * @return the number of non-empty parameters currently shown in the table
     */
    public int getParameterCount() {
        stopTableEditing();
        int count = 0;
        @SuppressWarnings("unchecked") // only contains HTTPArgument
        Iterator<HTTPArgument> modelData = (Iterator<HTTPArgument>) tableModel.iterator();
        while (modelData.hasNext()) {
            HTTPArgument arg = modelData.next();
            if (StringUtilities.isNotEmpty(arg.getName()) || StringUtilities.isNotEmpty(arg.getValue())) {
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

    private Arguments getUnclonedParameters() {
        stopTableEditing();
        @SuppressWarnings("unchecked") // only contains Argument (or HTTPArgument)
        Iterator<HTTPArgument> modelData = (Iterator<HTTPArgument>) tableModel.iterator();
        Arguments args = new Arguments();
        while (modelData.hasNext()) {
            HTTPArgument arg = modelData.next();
            args.addArgument(arg);
        }
        return args;
    }

    @Override
    public void configure(TestElement el) {
        super.configure(el);
        if (el instanceof Arguments arguments) {
            tableModel.clearData();
            HTTPArgument.convertArgumentsToHTTP(arguments);
            for (JMeterProperty jMeterProperty : arguments.getArguments()) {
                HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
                tableModel.addRow(arg);
            }
        }
        checkButtonsStatus();
    }

    protected boolean isMetaDataNormal(HTTPArgument arg) {
        return arg.getMetaData() == null || arg.getMetaData().equals("=")
                || StringUtilities.isNotEmpty(arg.getValue());
    }

    @Override
    protected void addFromClipboard() {
        addFromClipboard(CLIPBOARD_LINE_DELIMITERS, CLIPBOARD_ARG_DELIMITERS);
    }

    @Override
    protected List<Argument> createArgumentsFromClipboard(String clipboardContent,
            String lineDelimiter, String argDelimiter) {
        // Lines containing a tab are table rows: '&' and '=' are literal cell contents and
        // quoted cells may span lines. Other lines use the plain-text delimiters.
        List<Argument> arguments = new ArrayList<>();
        int pos = 0;
        while (pos < clipboardContent.length()) {
            int lineEnd = clipboardContent.indexOf('\n', pos);
            if (lineEnd < 0) {
                lineEnd = clipboardContent.length();
            }
            String line = clipboardContent.substring(pos, lineEnd);
            if (line.indexOf('\t') >= 0) {
                List<String> cells = new ArrayList<>();
                pos = readTableRow(clipboardContent, pos, cells);
                arguments.add(createArgumentFromClipboard(cells.toArray(new String[0])));
            } else {
                arguments.addAll(super.createArgumentsFromClipboard(
                        stripCarriageReturn(line), lineDelimiter, argDelimiter));
                pos = lineEnd + 1;
            }
        }
        return arguments;
    }

    /**
     * Read one tab-separated row starting at {@code pos}. A cell with a valid closing quote is
     * quoted: it may contain tabs and line breaks, and {@code ""} stands for one quote.
     *
     * @return position after the row's line break
     */
    private static int readTableRow(String text, int pos, List<String> cells) {
        StringBuilder cell = new StringBuilder();
        boolean quoted = hasQuotedCell(text, pos);
        int i = quoted ? pos + 1 : pos;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '\t') {
                cells.add(cell.toString());
                cell.setLength(0);
                quoted = hasQuotedCell(text, i + 1);
                if (quoted) {
                    i++;
                }
            } else if (c == '\n') {
                break;
            } else {
                cell.append(c);
            }
            i++;
        }
        cells.add(stripCarriageReturn(cell.toString()));
        return i + 1;
    }

    private static boolean hasQuotedCell(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '"') {
            return false;
        }
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) != '"') {
                i++;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                i += 2;
                continue;
            }
            // Only consume quotes when the closing quote ends the cell. Otherwise the
            // leading quote is literal legacy clipboard data, as is an unmatched quote.
            int next = i + 1;
            return next == text.length() || text.charAt(next) == '\t' || text.charAt(next) == '\n'
                    || (text.charAt(next) == '\r' && next + 1 < text.length() && text.charAt(next + 1) == '\n');
        }
        return false;
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static boolean isBoolean(String value) {
        String trimmed = value.trim();
        return "true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed);
    }

    @Override
    protected Argument createArgumentFromClipboard(String[] clipboardCols) {
        HTTPArgument argument = makeNewArgument();
        if (clipboardCols.length == 6 && isBoolean(clipboardCols[0])
                && isBoolean(clipboardCols[3]) && isBoolean(clipboardCols[5])) {
            argument.setEnabled(Boolean.parseBoolean(clipboardCols[0].trim()));
            argument.setName(clipboardCols[1]);
            argument.setValue(clipboardCols[2]);
            argument.setAlwaysEncoded(Boolean.parseBoolean(clipboardCols[3].trim()));
            argument.setContentType(clipboardCols[4]);
            argument.setUseEquals(Boolean.parseBoolean(clipboardCols[5].trim()));
            return argument;
        }
        argument.setName(clipboardCols[0]);
        if (clipboardCols.length > 1) {
            argument.setValue(clipboardCols[1]);

            if (clipboardCols.length > 2) {

                // default to false if the string is not a boolean
                argument.setAlwaysEncoded(Boolean.parseBoolean(clipboardCols[2].trim()));

                if (clipboardCols.length > 3) {
                    Boolean useEqual = BooleanUtils.toBooleanObject(clipboardCols[3].trim());
                    // default to true if the string is not a boolean
                    argument.setUseEquals(useEqual!=null? useEqual :true);
                }
            }
        }

        return argument;
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)

        JTable table = getTable();
        table.setTransferHandler(new ParameterRowTransferHandler());

        // register the right click menu
        final JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem searchItem = new JMenuItem(JMeterUtils.getResString("http_parameter_search_responses"));
        searchItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                PreviousResponseSearch.search(this,
                        (String) tableModel.getValueAt(table.convertRowIndexToModel(row), 2));
            }
        });
        popupMenu.add(searchItem);
        table.addMouseListener(new MouseAdapter() {
            private void showPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row < 0 || column < 0 || table.convertColumnIndexToModel(column) != 2) {
                    return;
                }
                if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
                    return;
                }
                table.setRowSelectionInterval(row, row);
                String value = (String) tableModel.getValueAt(table.convertRowIndexToModel(row), 2);
                searchItem.setEnabled(value != null && !value.isEmpty());
                popupMenu.show(table, event.getX(), event.getY());
            }

            @Override
            public void mousePressed(MouseEvent event) {
                showPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showPopup(event);
            }
        });
    }

    /**
     * Copies selected rows with every column in model order, so pasting does not depend on how
     * the columns are arranged on screen. Cells with tabs, line breaks or a leading quote are quoted.
     */
    private class ParameterRowTransferHandler extends TransferHandler {
        private static final long serialVersionUID = 1L;

        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            JTable table = getTable();
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (int row : rows) {
                int modelRow = table.convertRowIndexToModel(row);
                for (int col = 0; col < tableModel.getColumnCount(); col++) {
                    if (col > 0) {
                        text.append('\t');
                    }
                    Object value = tableModel.getValueAt(modelRow, col);
                    text.append(quoteCell(value == null ? "" : value.toString()));
                }
                text.append('\n');
            }
            return new StringSelection(text.toString());
        }

        private static String quoteCell(String cell) {
            if (cell.startsWith("\"") || cell.indexOf('\t') >= 0
                    || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0) {
                return '"' + cell.replace("\"", "\"\"") + '"';
            }
            return cell;
        }
    }
}
