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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.util.JSyntaxSearchToolBar;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.RequestPanel;
import org.junit.jupiter.api.Test;

class RequestViewHTTPDiffTest {
    @Test
    void alignsDuplicateFieldsAndDistinguishesEmptyFromAbsent() {
        var before = List.of(field("a", "1"), field("a", "2"), field("removed", ""));
        var after = List.of(field("a", "1"), field("a", "3"), field("added", ""));
        var rows = RequestViewHTTPDiff.compare(before, after, false, false);
        assertEquals(List.of("same", "changed", "removed", "added"),
                rows.stream().map(RequestViewHTTPDiff.DiffRow::status).toList());
        assertEquals("2", rows.get(1).recorded());
        assertEquals("3", rows.get(1).current());
        assertEquals("", rows.get(2).recorded());
        assertEquals(null, rows.get(2).current());
    }

    @Test
    void matchesHeadersWithoutCaseAndDoesNotCompareMissingMetadata() {
        assertEquals("same", RequestViewHTTPDiff.compare(
                List.of(field("Accept", "text/plain")), List.of(field("accept", "text/plain")),
                true, false).get(0).status());
        assertEquals("not_recorded", RequestViewHTTPDiff.compare(
                List.of(), List.of(field("Local IP/port", "127.0.0.1:1234")),
                false, true).get(0).status());
        assertEquals("removed", RequestViewHTTPDiff.compare(
                List.of(field("Name", "one")), List.of(field("name", "one")),
                false, false).get(0).status());
    }

    @Test
    void parsesRecordedRequestWithoutChangingBodyLineEndings() throws Exception {
        for (String newline : List.of("\n", "\r\n")) {
            String body = "line1\r\nline2\n\nline3";
            HTTPSampleResult sample = RequestViewHTTPDiff.recordedSample(
                    "POST https://example.invalid/path?a=1 HTTP/2" + newline
                    + "Content-Type: text/plain" + newline + newline + body);
            assertEquals("POST", sample.getHTTPMethod());
            assertEquals("a=1", sample.getURL().getQuery());
            assertEquals("HTTP/2", sample.getProtocolVersion());
            assertEquals(body, sample.getQueryString());
        }
        assertEquals("body", RequestViewHTTPDiff.recordedSample(
                "POST https://example.invalid/\n\nbody").getQueryString());
        assertEquals("", RequestViewHTTPDiff.recordedSample(
                "GET https://example.invalid/ HTTP/1.1\nAccept: */*\n").getQueryString());
    }

    @Test
    void topTabsCompareQueryHeadersAndFormAndClearMissingRecording() throws Exception {
        HTTPSampleResult current = new HTTPSampleResult();
        current.setHTTPMethod("POST");
        current.setURL(URI.create("https://example.invalid/path?q=new").toURL());
        current.setRequestHeaders("content-type: application/x-www-form-urlencoded\n");
        current.setQueryString("a=new&extra=");
        AtomicReference<JSyntaxSearchToolBar.DiffContent> recording = new AtomicReference<>(
                new JSyntaxSearchToolBar.DiffContent(
                        "POST https://example.invalid/path?q=old HTTP/1.1\n"
                        + "Content-Type: application/x-www-form-urlencoded\n\na=old", ""));
        SwingUtilities.invokeAndWait(() -> {
            RequestPanel panel = new RequestPanel(recording::get);
            panel.setSamplerResult(current);
            JTabbedPane tabs = descendants(panel.getPanel(), JTabbedPane.class).get(0);
            assertEquals(JTabbedPane.TOP, tabs.getTabPlacement());
            int diffIndex = tabs.indexOfTab(JMeterUtils.getResString("view_results_parsed_diff_title"));
            assertTrue(diffIndex > tabs.indexOfTab(JMeterUtils.getResString("view_results_table_result_tab_parsed")));
            List<JTable> tables = descendants(tabs.getComponentAt(diffIndex), JTable.class);
            assertEquals("old", tables.get(1).getValueAt(0, 1));
            assertEquals("new", tables.get(1).getValueAt(0, 2));
            assertEquals(JMeterUtils.getResString("view_results_parsed_diff_changed"), tables.get(1).getValueAt(0, 3));
            assertEquals(JMeterUtils.getResString("view_results_parsed_diff_same"), tables.get(2).getValueAt(0, 3));
            assertEquals("old", tables.get(3).getValueAt(0, 1));
            assertEquals("new", tables.get(3).getValueAt(0, 2));
            recording.set(null);
            panel.setSamplerResult(current);
            assertTrue(descendants(tabs.getComponentAt(diffIndex), JTable.class).isEmpty());
        });
    }

    @Test
    void showsRawBodiesSideBySideAndHandlesInvalidRecording() throws Exception {
        HTTPSampleResult current = new HTTPSampleResult();
        current.setHTTPMethod("POST");
        current.setURL(URI.create("https://example.invalid/").toURL());
        current.setRequestHeaders("Content-Type: application/json\n");
        current.setQueryString("{\"value\":2}");
        AtomicReference<JSyntaxSearchToolBar.DiffContent> recording = new AtomicReference<>(
                new JSyntaxSearchToolBar.DiffContent(
                        "POST https://example.invalid/\nContent-Type: application/json\n\n{\"value\":1}", ""));
        SwingUtilities.invokeAndWait(() -> {
            RequestViewHTTPDiff view = new RequestViewHTTPDiff();
            view.setDiffContentSupplier(recording::get);
            view.init();
            view.setSamplerResult(current);
            var text = descendants(view.getPanel(), JSyntaxTextArea.class);
            assertEquals(2, text.size());
            assertEquals("{\"value\":1}", text.get(0).getText());
            assertEquals("{\"value\":2}", text.get(1).getText());
            recording.set(new JSyntaxSearchToolBar.DiffContent("invalid", ""));
            view.setSamplerResult(current);
            assertTrue(descendants(view.getPanel(), JSyntaxTextArea.class).isEmpty());
        });
    }

    private static RequestViewHTTP.Field field(String name, String value) {
        return new RequestViewHTTP.Field(name, value);
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
