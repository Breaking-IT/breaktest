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

package org.apache.jmeter.protocol.websocket.sampler;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.List;
import java.util.ResourceBundle;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import org.apache.jmeter.gui.util.BinaryDataPanel;
import org.apache.jmeter.recording.RecordedWebSocketMessage;

/** Read-only recording browser; changing the display never changes the stored bytes. */
class WebSocketRecordedMessagesPanel extends JPanel {
    private final MessageModel model;
    private final JTable table;
    private final JTextArea payload = new JTextArea();
    private final BinaryDataPanel binary = new BinaryDataPanel();
    private final CardLayout payloadLayout = new CardLayout();
    private final JPanel payloadViews = new JPanel(payloadLayout);
    private final JLabel status = new JLabel();
    private final JComboBox<String> display;
    private List<RecordedWebSocketMessage> messages = List.of();

    WebSocketRecordedMessagesPanel(ResourceBundle resources) {
        super(new BorderLayout());
        model = new MessageModel(resources);
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        DefaultTableCellRenderer timeRenderer = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                super.setValue(value instanceof BigDecimal time
                        ? time.setScale(3, RoundingMode.HALF_UP).toPlainString() : value);
            }
        };
        timeRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.setDefaultRenderer(BigDecimal.class, timeRenderer);
        display = new JComboBox<>(new String[] {resources.getString("recorded.auto"),
                resources.getString("recorded.text"), resources.getString("recorded.hex")});
        JPanel details = new JPanel(new BorderLayout());
        details.add(display, BorderLayout.NORTH);
        payload.setEditable(false);
        payload.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        payloadViews.add(new JScrollPane(payload), "text");
        payloadViews.add(binary, "binary");
        details.add(payloadViews, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), details);
        split.setResizeWeight(0.5);
        add(status, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        table.getSelectionModel().addListSelectionListener(event -> showPayload());
        display.addActionListener(event -> showPayload());
    }

    void setMessages(List<RecordedWebSocketMessage> values, String diagnostic) {
        table.clearSelection();
        messages = List.copyOf(values);
        payload.setText("");
        model.fireTableDataChanged();
        status.setText(diagnostic);
        if (!messages.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void showPayload() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            payload.setText("");
            binary.clearData();
            return;
        }
        RecordedWebSocketMessage message = messages.get(table.convertRowIndexToModel(selected));
        boolean hex = display.getSelectedIndex() == 2 || display.getSelectedIndex() == 0 && message.opcode() != 1;
        if (hex) {
            payload.setText("");
            binary.setData(Base64.getDecoder().decode(message.data()));
        } else {
            binary.clearData();
            payload.setText(message.text());
            payload.setCaretPosition(0);
        }
        payloadLayout.show(payloadViews, hex ? "binary" : "text");
    }

    private final class MessageModel extends AbstractTableModel {
        private final ResourceBundle resources;
        private final String[] columns;

        private MessageModel(ResourceBundle resources) {
            this.resources = resources;
            columns = new String[] {resources.getString("recorded.time"), resources.getString("recorded.direction"),
                    resources.getString("recorded.type"), resources.getString("recorded.message")};
        }

        @Override
        public int getRowCount() {
            return messages.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? BigDecimal.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            RecordedWebSocketMessage message = messages.get(row);
            return switch (column) {
                case 0 -> message.relativeTimeMs();
                case 1 -> resources.getString("send".equals(message.direction()) ? "recorded.sent" : "recorded.received");
                case 2 -> switch (message.opcode()) {
                    case 0 -> resources.getString("recorded.continuation");
                    case 1 -> resources.getString("recorded.type.text");
                    case 2 -> resources.getString("recorded.type.binary");
                    case 8 -> resources.getString("recorded.close");
                    case 9 -> resources.getString("recorded.ping");
                    case 10 -> resources.getString("recorded.pong");
                    default -> resources.getString("recorded.unknown") + " (" + message.opcode() + ")";
                };
                default -> message.preview();
            };
        }
    }
}
