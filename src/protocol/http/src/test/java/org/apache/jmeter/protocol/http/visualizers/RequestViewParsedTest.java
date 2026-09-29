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

package org.apache.jmeter.protocol.http.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.RequestPanel;
import org.junit.jupiter.api.Test;

class RequestViewParsedTest {
    @Test
    void separatesQueryAndFormWithDuplicateAndEncodedValues() throws Exception {
        HTTPSampleResult result = sample("application/x-www-form-urlencoded; charset=ISO-8859-1",
                "q=caf%E9&token=a=b==&empty=&bad=%ZZ");
        result.setTlsVersion("TLSv1.3");
        result.setLocalEndpoint("127.0.0.1:1234");
        result.setDestinationEndpoint("[::1]:443");
        SwingUtilities.invokeAndWait(() -> {
            RequestViewHTTP view = view(result);
            List<JTable> tables = descendants(view.getPanel(), JTable.class);
            JTable query = tables.get(1);
            assertEquals(3, query.getRowCount());
            assertEquals("é", query.getValueAt(0, 1));
            assertEquals("two words", query.getValueAt(1, 1));
            assertEquals("flag", query.getValueAt(2, 0));
            JTable body = tables.get(3);
            assertEquals(4, body.getRowCount());
            assertEquals("café", body.getValueAt(0, 1));
            assertEquals("a=b==", body.getValueAt(1, 1));
            assertEquals("", body.getValueAt(2, 1));
            assertEquals("%ZZ", body.getValueAt(3, 1));
            JTable details = tables.get(0);
            assertTrue(values(details).containsAll(List.of("TLSv1.3", "127.0.0.1:1234", "[::1]:443", "443")));
            view.setSamplerResult(new SampleResult());
            assertEquals(0, query.getRowCount());
            assertEquals(0, body.getRowCount());
            assertEquals(1, details.getRowCount());
        });
    }

    @Test
    void keepsJsonRawAndPrettyPrintingDoesNotChangeSample() throws Exception {
        String json = "{\"name\":\"value\",\"count\":2}";
        HTTPSampleResult result = sample("application/json", json);
        SwingUtilities.invokeAndWait(() -> {
            RequestViewHTTP view = view(result);
            JSyntaxTextArea text = descendants(view.getPanel(), JSyntaxTextArea.class).get(0);
            assertEquals(json, text.getText());
            assertEquals(0, descendants(view.getPanel(), JTable.class).get(3).getRowCount());
            descendants(view.getPanel(), JButton.class).stream()
                    .filter(button -> JMeterUtils.getResString("view_results_pretty_print").equals(button.getText())).findFirst().orElseThrow().doClick();
            assertTrue(text.getText().contains("\n"));
            assertEquals(json, result.getQueryString());
            view.clearData();
            assertEquals("", text.getText());
        });
    }

    @Test
    void parsesQuotedMultipartBoundaryAndFileName() throws Exception {
        HTTPSampleResult result = sample("multipart/form-data; boundary=\"example\"",
                "--example\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n"
                + "--example\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"test.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nfile content\r\n--example--\r\n");
        SwingUtilities.invokeAndWait(() -> {
            JTable body = descendants(view(result).getPanel(), JTable.class).get(3);
            assertEquals(2, body.getRowCount());
            assertEquals("value", body.getValueAt(0, 1));
            assertEquals("test.txt", body.getValueAt(1, 1));
        });
    }

    @Test
    void rawFallbackForUnknownContentTypeAndMissingBoundary() throws Exception {
        for (String type : List.of("text/plain", "multipart/form-data", "application/x-www-form-urlencoded; charset=invalid")) {
            HTTPSampleResult result = sample(type, "a=b&c=d");
            SwingUtilities.invokeAndWait(() -> {
                RequestViewHTTP view = view(result);
                assertEquals(0, descendants(view.getPanel(), JTable.class).get(3).getRowCount());
                assertEquals("a=b&c=d", descendants(view.getPanel(), JSyntaxTextArea.class).get(0).getText());
            });
        }
    }

    @Test
    void loadsParsedTabAlongsideRaw() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RequestPanel panel = new RequestPanel();
            List<javax.swing.JTabbedPane> tabs = descendants(panel.getPanel(), javax.swing.JTabbedPane.class);
            assertEquals(JMeterUtils.getResString("view_results_table_request_tab_raw"), tabs.get(0).getTitleAt(0));
            assertTrue(java.util.stream.IntStream.range(0, tabs.get(0).getTabCount())
                    .anyMatch(index -> JMeterUtils.getResString("view_results_table_result_tab_parsed").equals(tabs.get(0).getTitleAt(index))));
        });
    }

    @Test
    void prettyPrintingXmlDoesNotAddHtmlDocument() {
        String pretty = RequestViewHTTP.prettyPrintBody("<root><value>hello</value></root>", "application/xml");
        assertTrue(pretty.contains("<root>"));
        assertFalse(pretty.contains("<html>"));
        assertEquals("plain text", RequestViewHTTP.prettyPrintBody("plain text", "text/plain"));
    }

    @Test
    void laysOutQueriesAndBodiesAfterAnInitiallyEmptyView() throws Exception {
        HTTPSampleResult json = sample("application/json", "{\"hello\":\"world\"}");
        HTTPSampleResult form = sample("application/x-www-form-urlencoded", "a=1&b=2");
        SwingUtilities.invokeAndWait(() -> {
            RequestViewHTTP view = new RequestViewHTTP();
            view.init();
            JPanel panel = view.getPanel();
            panel.setSize(1000, 800);
            layout(panel);
            for (HTTPSampleResult result : List.of(json, form, json)) {
                view.clearData();
                layout(panel);
                view.setSamplerResult(result);
                layout(panel);
                List<JTable> tables = descendants(panel, JTable.class);
                JTable details = tables.get(0);
                JTable query = tables.get(1);
                JTable headers = tables.get(2);
                assertEquals(details.getRowCount() * details.getRowHeight(), details.getHeight());
                assertEquals(query.getRowCount() * query.getRowHeight(), query.getHeight());
                assertEquals(headers.getRowCount() * headers.getRowHeight(), headers.getHeight());
                assertTrue(query.getVisibleRect().height >= query.getRowHeight());
                Component body = result == json
                        ? descendants(panel, JSyntaxTextArea.class).get(0) : tables.get(3);
                assertTrue(body.isVisible());
                Rectangle bodyBounds = SwingUtilities.convertRectangle(body.getParent(), body.getBounds(), panel);
                assertTrue(bodyBounds.height >= 40, bodyBounds.toString());
                assertTrue(bodyBounds.y < 600, bodyBounds.toString());
                assertTrue(bodyBounds.y > SwingUtilities.convertRectangle(
                        headers.getParent(), headers.getBounds(), panel).y);
            }
            // Small panes scroll the full layout instead of collapsing sections.
            panel.setSize(500, 250);
            layout(panel);
            JScrollPane scroll = (JScrollPane) panel.getComponent(0);
            assertTrue(scroll.getViewport().getView().getHeight() > scroll.getViewport().getHeight());
            assertTrue(scroll.getVerticalScrollBar().isVisible());
        });
    }

    private static void layout(Container container) {
        container.invalidate();
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layout(child);
            }
        }
    }

    private static HTTPSampleResult sample(String contentType, String body) throws Exception {
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("https://example.invalid/path?q=%C3%A9&q=two+words&flag").toURL());
        result.setHTTPMethod("POST");
        result.setRequestHeaders("content-type: " + contentType + "\n");
        result.setQueryString(body);
        return result;
    }

    private static RequestViewHTTP view(HTTPSampleResult result) {
        RequestViewHTTP view = new RequestViewHTTP();
        view.init();
        view.setSamplerResult(result);
        return view;
    }

    private static List<Object> values(JTable table) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < table.getRowCount(); i++) {
            values.add(table.getValueAt(i, 1));
        }
        return values;
    }

    private static <T> List<T> descendants(Component component, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(component)) {
            result.add(type.cast(component));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                result.addAll(descendants(child, type));
            }
        }
        return result;
    }
}
