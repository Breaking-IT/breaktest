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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.stream.Stream;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.apache.jmeter.recording.RecordedWebSocketMessage;
import org.junit.jupiter.api.Test;

class WebSocketRecordedMessagesPanelTest {
    @Test
    void displaysTextAndHexAndResetsSelectionWhenSwitchingRecordings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new WebSocketRecordedMessagesPanel(ResourceBundle.getBundle(
                    WebSocketConnectSampler.class.getName() + "Resources", Locale.ENGLISH));
            var text = new RecordedWebSocketMessage(new BigDecimal("1000"), "send", 1, "aGk=");
            var binary = new RecordedWebSocketMessage(new BigDecimal("20"), "receive", 2, "AP8=");
            panel.setMessages(List.of(text, binary), "");
            JTable table = find(panel, JTable.class);
            JTextArea payload = find(panel, JTextArea.class);
            var binaryView = find(panel, org.apache.jmeter.gui.util.BinaryDataPanel.class);
            JComboBox<?> display = find(panel, JComboBox.class);
            assertEquals("hi", payload.getText());
            assertFalse(payload.isEditable());
            assertFalse(table.isCellEditable(0, 0));
            assertEquals("Sent", table.getValueAt(0, 1));
            assertEquals("Type", table.getColumnName(2));
            assertEquals("Text", table.getValueAt(0, 2));
            assertEquals("Binary", table.getValueAt(1, 2));
            assertEquals("1000.000", ((JLabel) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0)).getText());
            table.setRowSelectionInterval(1, 1);
            assertTrue(binaryView.isVisible());
            assertEquals("00000000\n00 FF\n..", binaryView.getDisplayText());
            display.setSelectedIndex(2);
            table.setRowSelectionInterval(0, 0);
            assertEquals("00000000\n68 69\nhi", binaryView.getDisplayText());
            display.setSelectedIndex(1);
            assertFalse(binaryView.isVisible());
            assertEquals("hi", payload.getText());
            assertTrue(binaryView.getDisplayText().isBlank());
            display.setSelectedIndex(0);
            table.getRowSorter().toggleSortOrder(0);
            assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) table.getValueAt(0, 0)));
            table.setRowSelectionInterval(0, 0);
            assertTrue(binaryView.isVisible());
            assertEquals("00000000\n00 FF\n..", binaryView.getDisplayText());
            panel.setMessages(List.of(), "No recording");
            assertEquals(0, table.getRowCount());
            assertTrue(binaryView.getDisplayText().isBlank());
            assertEquals("", payload.getText());
            panel.setMessages(List.of(text), "");
            assertEquals("hi", payload.getText());
            panel.setMessages(List.of(
                    new RecordedWebSocketMessage(new BigDecimal("199.24"), "send", 9, ""),
                    new RecordedWebSocketMessage(new BigDecimal("948.004"), "receive", 10, "")), "");
            assertEquals("199.240", ((JLabel) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0)).getText());
            assertEquals("948.004", ((JLabel) table.prepareRenderer(table.getCellRenderer(1, 0), 1, 0)).getText());
            assertEquals("Ping", table.getValueAt(0, 2));
            assertEquals("Pong", table.getValueAt(1, 2));
        });
    }

    private static <T> T find(Component root, Class<T> type) {
        return descendants(root).filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(WebSocketRecordedMessagesPanelTest::descendants)
                : Stream.empty());
    }
}
