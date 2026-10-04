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

package org.apache.jmeter.gui.util;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.TransferHandler;

import org.apache.jmeter.util.JMeterUtils;

/** Displays response bytes without decoding or treating them as an image. */
public class BinaryDataPanel extends JPanel {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final JTextArea offsets = column(8);
    private final JTextArea hexadecimal = column(47);
    private final JTextArea ascii = column(16);
    private final JLabel status = new JLabel();

    private static JTextArea column(int columns) {
        JTextArea area = new JTextArea(0, columns);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setMargin(new Insets(0, 0, 0, 0));
        // Explicit 16-byte rows keep all columns aligned; copying removes these visual row breaks.
        area.setLineWrap(false);
        return area;
    }

    public BinaryDataPanel() {
        super(new BorderLayout());
        hexadecimal.setTransferHandler(new ColumnTransferHandler(" "));
        ascii.setTransferHandler(new ColumnTransferHandler(""));
        JPanel columns = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(4, 8, 4, 8);
        JTextArea[] areas = {offsets, hexadecimal, ascii};
        String[] titles = {"view_results_binary_offset", "view_results_binary_hex", "view_results_binary_ascii"};
        for (int index = 0; index < areas.length; index++) {
            constraints.gridx = index;
            constraints.gridy = 0;
            constraints.weightx = index == areas.length - 1 ? 1 : 0;
            constraints.weighty = 0;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            JLabel label = new JLabel(JMeterUtils.getResString(titles[index]));
            label.setLabelFor(areas[index]);
            columns.add(label, constraints);
            constraints.gridy = 1;
            constraints.weighty = 1;
            constraints.fill = GridBagConstraints.BOTH;
            columns.add(areas[index], constraints);
        }
        add(new JScrollPane(columns), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(status, BorderLayout.SOUTH);
    }

    private static final class ColumnTransferHandler extends TransferHandler {
        private final String rowSeparator;

        private ColumnTransferHandler(String rowSeparator) {
            this.rowSeparator = rowSeparator;
        }

        @Override
        public int getSourceActions(JComponent component) {
            return COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent component) {
            String selection = ((JTextArea) component).getSelectedText();
            return selection == null ? null : new StringSelection(selection.replace("\n", rowSeparator));
        }
    }

    public void clearData() {
        offsets.setText("");
        hexadecimal.setText("");
        ascii.setText("");
        status.setText("");
    }

    public void setData(byte[] bytes) {
        int limit = JMeterUtils.getPropDefault("view.results.tree.binary.max_bytes", 65536);
        int generalLimit = JMeterUtils.getPropDefault("view.results.tree.max_size", 10485760);
        if (generalLimit > 0) {
            limit = limit > 0 ? Math.min(limit, generalLimit) : generalLimit;
        }
        String[] data = formatColumns(bytes, limit);
        JTextArea[] areas = {offsets, hexadecimal, ascii};
        for (int index = 0; index < areas.length; index++) {
            areas[index].setText(data[index]);
            areas[index].setCaretPosition(0);
        }
        int length = limit > 0 ? Math.min(bytes.length, limit) : bytes.length;
        status.setText(length < bytes.length
                ? JMeterUtils.getResString("view_results_binary_truncated")
                        + " " + length + " / " + bytes.length + " bytes"
                : "");
    }

    public static String[] formatColumns(byte[] bytes, int limit) {
        int length = limit > 0 ? Math.min(bytes.length, limit) : bytes.length;
        StringBuilder offsets = new StringBuilder();
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int offset = 0; offset < length; offset += 16) {
            if (offset > 0) {
                offsets.append('\n');
                hex.append('\n');
                ascii.append('\n');
            }
            for (int shift = 28; shift >= 0; shift -= 4) {
                offsets.append(HEX[(offset >>> shift) & 15]);
            }
            int end = Math.min(offset + 16, length);
            for (int index = offset; index < end; index++) {
                int value = bytes[index] & 255;
                if (index > offset) {
                    hex.append(' ');
                }
                hex.append(HEX[value >>> 4]).append(HEX[value & 15]);
                ascii.append(value >= 32 && value <= 126 ? (char) value : '.');
            }
        }
        return new String[] {offsets.toString(), hex.toString(), ascii.toString()};
    }

    public String getDisplayText() {
        return offsets.getText() + "\n" + hexadecimal.getText() + "\n" + ascii.getText() + status.getText();
    }

}
