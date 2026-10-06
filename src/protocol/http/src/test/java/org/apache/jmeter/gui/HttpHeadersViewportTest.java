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

package org.apache.jmeter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.gui.HeaderTablePanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.sse.SseSampler;
import org.apache.jmeter.protocol.sse.SseSamplerGui;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpHeadersViewportTest extends JMeterTestCase {
    @ParameterizedTest
    @CsvSource({
        "false, 500, 320, 0", "false, 700, 320, 100",
        "false, 500, 450, 100", "false, 700, 450, 0",
        "true, 500, 320, 0", "true, 700, 320, 100",
        "true, 500, 450, 100", "true, 700, 450, 0"
    })
    void headerActionsStayVisibleWhileOnlyTheTableScrolls(
            boolean sse, int width, int height, int headerCount) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HttpTestSampleGui editor = sse ? new SseSamplerGui() : new HttpTestSampleGui();
            HTTPSamplerProxy sampler = sse ? new SseSampler() : new HTTPSamplerProxy();
            List<Header> headers = new ArrayList<>();
            for (int i = 0; i < headerCount; i++) {
                headers.add(new Header("X-Header-" + i, "value"));
            }
            sampler.setNativeHeaders(headers);
            editor.configure(sampler);
            HeaderTablePanel headerPanel = descendants(editor, HeaderTablePanel.class).get(0);
            JTabbedPane tabs = (JTabbedPane) headerPanel.getParent();
            tabs.setSelectedComponent(headerPanel);
            MainFrame.ScrollableMainPanel main = new MainFrame.ScrollableMainPanel();
            main.setMainPanel(editor);
            JScrollPane outer = new JScrollPane(main);
            outer.setSize(new Dimension(1000, 800));
            layoutTree(outer);
            outer.setSize(new Dimension(width, height));
            layoutTree(outer);
            layoutTree(outer);

            assertEquals(outer.getViewport().getHeight(), main.getHeight());
            assertFalse(outer.getVerticalScrollBar().isVisible());
            Rectangle visible = new Rectangle(outer.getViewport().getExtentSize());
            List<JButton> actions = new ArrayList<>();
            for (JButton button : descendants(headerPanel, JButton.class)) {
                // Scrollbar arrow buttons are not part of the header action bar.
                if (button.getActionCommand() != null && !button.getActionCommand().isEmpty()) {
                    actions.add(button);
                    Rectangle bounds = SwingUtilities.convertRectangle(
                            button.getParent(), button.getBounds(), main);
                    assertTrue(visible.contains(bounds), button.getText() + " must remain visible: " + bounds);
                }
            }
            assertEquals(List.of("Add", "addFromClipboard", "Delete"),
                    actions.stream().map(JButton::getActionCommand).toList());
            JTable table = descendants(headerPanel, JTable.class).get(0);
            JScrollPane tableScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
            assertTrue(tableScroll.getViewport().getHeight() > 0);
            assertEquals(headerCount > 0, tableScroll.getVerticalScrollBar().isVisible());
            if (headerCount > 0) {
                tableScroll.getVerticalScrollBar().setValue(200);
                assertTrue(tableScroll.getViewport().getViewPosition().y > 0);
            }
            assertEquals(0, outer.getViewport().getViewPosition().y);

            tabs.setSelectedIndex(0);
            assertFalse(editor.isViewportHeightConstrained());
            layoutTree(outer);
            tabs.setSelectedComponent(headerPanel);
            layoutTree(outer);
            layoutTree(outer);
            assertFalse(outer.getVerticalScrollBar().isVisible());

            actions.get(0).doClick();
            assertEquals(headerCount + 1, table.getRowCount());
            table.setValueAt("X-New", headerCount, 0);
            table.setValueAt("new value", headerCount, 1);
            editor.modifyTestElement(sampler);
            assertEquals(sse, sampler.isSseEnabled());
            assertEquals(headerCount + 1, sampler.getNativeHeaderList().size());
            assertEquals("X-New", sampler.getNativeHeaderList().get(headerCount).getName());
            assertEquals("new value", sampler.getNativeHeaderList().get(headerCount).getValue());
            table.setRowSelectionInterval(headerCount, headerCount);
            actions.get(2).doClick();
            editor.modifyTestElement(sampler);
            assertEquals(headerCount, sampler.getNativeHeaderList().size());
        });
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static <T> List<T> descendants(Container container, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                result.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                result.addAll(descendants(nested, type));
            }
        }
        return result;
    }
}
