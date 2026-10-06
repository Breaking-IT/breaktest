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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.gui.HeaderTablePanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.sse.SseSampler;
import org.apache.jmeter.protocol.sse.SseSamplerGui;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpHeadersViewportTest extends JMeterTestCase implements JMeterSerialTest {
    private boolean longButtonLabels;
    private int extraTitleHeight = 32;
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
            expandTitlePreferredHeight(editor);
            HTTPSamplerProxy sampler = sse ? new SseSampler() : new HTTPSamplerProxy();
            List<Header> headers = new ArrayList<>();
            for (int i = 0; i < headerCount; i++) {
                headers.add(new Header("X-Header-" + i, "value"));
            }
            sampler.setNativeHeaders(headers);
            editor.configure(sampler);
            HeaderTablePanel headerPanel = descendants(editor, HeaderTablePanel.class).get(0);
            applyLongButtonLabels(headerPanel);
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

            boolean fits = main.getMinimumSize().height <= outer.getViewport().getHeight();
            assertEquals(!fits, outer.getVerticalScrollBar().isVisible());
            if (fits) {
                assertEquals(outer.getViewport().getHeight(), main.getHeight());
            }
            Rectangle visible = new Rectangle(outer.getViewport().getExtentSize());
            List<JButton> actions = new ArrayList<>();
            for (JButton button : descendants(headerPanel, JButton.class)) {
                // Scrollbar arrow buttons are not part of the header action bar.
                if (button.getActionCommand() != null && !button.getActionCommand().isEmpty()) {
                    actions.add(button);
                    Rectangle bounds = SwingUtilities.convertRectangle(
                            button.getParent(), button.getBounds(), main);
                    if (!fits) {
                        main.scrollRectToVisible(bounds);
                        visible = outer.getViewport().getViewRect();
                    }
                    assertTrue(visible.contains(bounds), button.getText() + " must remain reachable: " + bounds);
                }
            }
            assertEquals(List.of("Add", "addFromClipboard", "Delete"),
                    actions.stream().map(JButton::getActionCommand).toList());
            JTable table = descendants(headerPanel, JTable.class).get(0);
            JScrollPane tableScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
            assertTrue(tableScroll.getViewport().getHeight() >= table.getRowHeight(),
                    "at least one header row must fit: viewport=" + tableScroll.getViewport().getHeight()
                    + ", row=" + table.getRowHeight() + ", minimum=" + main.getMinimumSize().height
                    + ", available=" + outer.getViewport().getHeight());
            assertEquals(headerCount > 0, tableScroll.getVerticalScrollBar().isVisible());
            if (headerCount > 0) {
                tableScroll.getVerticalScrollBar().setValue(200);
                assertTrue(tableScroll.getViewport().getViewPosition().y > 0);
            }
            if (fits) {
                assertEquals(0, outer.getViewport().getViewPosition().y);
            }

            tabs.setSelectedIndex(0);
            assertFalse(editor.isViewportHeightConstrained());
            layoutTree(outer);
            tabs.setSelectedComponent(headerPanel);
            layoutTree(outer);
            layoutTree(outer);
            assertEquals(!fits, outer.getVerticalScrollBar().isVisible());

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

    @ParameterizedTest
    @CsvSource({"false, 500", "false, 700", "true, 500", "true, 700"})
    void veryShortEditorsCanScrollToHeaderActionsAndRecoverWhenEnlarged(boolean sse, int width) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HttpTestSampleGui editor = sse ? new SseSamplerGui() : new HttpTestSampleGui();
            expandTitlePreferredHeight(editor);
            HeaderTablePanel headers = descendants(editor, HeaderTablePanel.class).get(0);
            applyLongButtonLabels(headers);
            ((JTabbedPane) headers.getParent()).setSelectedComponent(headers);
            MainFrame.ScrollableMainPanel main = new MainFrame.ScrollableMainPanel();
            main.setMainPanel(editor);
            JScrollPane outer = new JScrollPane(main);
            outer.setSize(width, 450);
            layoutTree(outer);
            layoutTree(outer);
            assertFalse(outer.getVerticalScrollBar().isVisible());

            outer.setSize(width, 120);
            layoutTree(outer);
            layoutTree(outer);
            assertTrue(outer.getVerticalScrollBar().isVisible(), "short editors must remain scrollable");
            JButton clipboard = descendants(headers, JButton.class).stream()
                    .filter(button -> "addFromClipboard".equals(button.getActionCommand())).findFirst().orElseThrow();
            Rectangle bounds = SwingUtilities.convertRectangle(clipboard.getParent(), clipboard.getBounds(), main);
            main.scrollRectToVisible(bounds);
            assertTrue(outer.getViewport().getViewRect().contains(bounds), "clipboard action must be reachable");

            int minimumHeight = main.getMinimumSize().height + outer.getInsets().top + outer.getInsets().bottom;
            outer.setSize(width, minimumHeight - 1);
            layoutTree(outer);
            layoutTree(outer);
            assertTrue(outer.getVerticalScrollBar().isVisible(), "one pixel below the minimum must scroll");
            outer.setSize(width, minimumHeight);
            layoutTree(outer);
            layoutTree(outer);
            assertFalse(outer.getVerticalScrollBar().isVisible(), "the minimum itself must fit");
            JTable table = descendants(headers, JTable.class).get(0);
            JScrollPane tableScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
            assertTrue(tableScroll.getViewport().getHeight() >= table.getRowHeight(),
                    "minimum height must leave room for a header row: viewport=" + tableScroll.getViewport().getHeight()
                    + ", row=" + table.getRowHeight());

            outer.setSize(width, 450);
            layoutTree(outer);
            layoutTree(outer);
            assertFalse(outer.getVerticalScrollBar().isVisible());
            assertEquals(0, outer.getViewport().getViewPosition().y);
            bounds = SwingUtilities.convertRectangle(clipboard.getParent(), clipboard.getBounds(), main);
            assertTrue(outer.getViewport().getViewRect().contains(bounds));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"javax.swing.plaf.metal.MetalLookAndFeel", "javax.swing.plaf.nimbus.NimbusLookAndFeel"})
    void minimumHeightWorksWithDifferentTabAndTableMetrics(String lookAndFeel) throws Exception {
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            longButtonLabels = true;
            SwingUtilities.invokeAndWait(() -> setLookAndFeel(lookAndFeel));
            for (boolean sse : new boolean[]{false, true}) {
                headerActionsStayVisibleWhileOnlyTheTableScrolls(sse, 500, 320, 0);
                headerActionsStayVisibleWhileOnlyTheTableScrolls(sse, 500, 320, 100);
                veryShortEditorsCanScrollToHeaderActionsAndRecoverWhenEnlarged(sse, 500);
                extraTitleHeight = 180;
                headerActionsStayVisibleWhileOnlyTheTableScrolls(sse, 500, 320, 100);
                extraTitleHeight = 32;
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(previous);
                    longButtonLabels = false;
                } catch (javax.swing.UnsupportedLookAndFeelException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private void expandTitlePreferredHeight(HttpTestSampleGui editor) {
        if (longButtonLabels) {
            // BorderLayout allocates preferred height to the title, even when its minimum is smaller.
            Container wrapper = (Container) editor.getComponent(0);
            Component title = ((BorderLayout) wrapper.getLayout()).getLayoutComponent(BorderLayout.NORTH);
            Dimension preferred = title.getPreferredSize();
            title.setPreferredSize(new Dimension(preferred.width, preferred.height + extraTitleHeight));
        }
    }

    private void applyLongButtonLabels(HeaderTablePanel panel) {
        if (longButtonLabels) {
            // Exercise extra viewport padding as used by platform look-and-feels.
            JTable table = descendants(panel, JTable.class).get(0);
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
            scroll.setViewportBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
            // Match the wider fallback labels seen in CI without depending on global resource state.
            for (JButton button : descendants(panel, JButton.class)) {
                String key = switch (button.getActionCommand()) {
                case "Add" -> "add";
                case "addFromClipboard" -> "add_from_clipboard";
                case "Delete" -> "delete";
                default -> null;
                };
                if (key != null) {
                    button.setText("[res_key=" + key + "]");
                }
            }
        }
    }

    private static void setLookAndFeel(String className) {
        try {
            UIManager.setLookAndFeel(className);
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void layoutTree(Container container) {
        List<Rectangle> previous = List.of();
        for (int pass = 0; pass < 10; pass++) {
            layoutOnce(container);
            List<Rectangle> bounds = descendants(container, Component.class).stream().map(Component::getBounds).toList();
            if (bounds.equals(previous)) {
                return;
            }
            previous = bounds;
        }
        throw new AssertionError("Editor layout did not converge after 10 passes");
    }

    private static void layoutOnce(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutOnce(nested);
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
