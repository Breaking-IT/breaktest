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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;

class HarCorrelationMatchesPanelTest extends JMeterTestCase {
    @Test
    void selectedRequestsControlConversionAndKeepOriginalScanIntact() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HarEntry source = entry(0, "/source");
            source.setResponseContentText("value=token-value;");
            HarEntry first = entry(1, "/a/token-value");
            first.getRequestHeaders().add(new HarEntry.NameValue("X-Token", "token-value"));
            HarEntry second = entry(2, "/b/token-value");
            List<HarEntry> entries = List.of(source, first, second);
            Rule rule = new Rule("token", "Custom", "Token", "token", ExtractorType.REGEX,
                    ResponseField.BODY, "value=([^;]+);", "$1$", "", false, false, true);
            List<HarPredefinedCorrelation> found = HarPredefinedCorrelation.find(entries, List.of(rule));
            HarCorrelationMatchesPanel panel = new HarCorrelationMatchesPanel();
            panel.setCorrelations(found);
            List<JCheckBox> boxes = components(panel, JCheckBox.class);
            assertEquals(3, boxes.size(), "One extraction and two requests, even with multiple locations");
            assertTrue(components(panel, JTextField.class).stream().anyMatch(text -> "token-value → ${token}".equals(text.getText())));
            boxes.get(1).doClick();
            List<HarPredefinedCorrelation> selected = panel.getSelectedCorrelations();
            assertEquals(1, selected.size());
            assertTrue(selected.get(0).getReplacements().stream().allMatch(r -> r.getTargetEntryIndex() == 2));
            assertEquals(3, found.get(0).getReplacements().size(), "Review must not mutate the scan");
            HarImportOptions options = new HarImportOptions();
            options.setPredefinedCorrelations(selected);
            HashTree tree = new HarConverter(entries, options, "review.har", "test").convert(Set.of("example.test"));
            List<String> paths = new ArrayList<>();
            collectPaths(tree, paths);
            assertTrue(paths.contains("/a/token-value"));
            assertTrue(paths.contains("/b/${token}"));
            boxes.get(2).doClick();
            assertTrue(panel.getSelectedCorrelations().isEmpty(), "No extractor when every request is excluded");
            boxes.get(0).doClick();
            assertEquals(3, panel.getSelectedCorrelations().get(0).getReplacements().size());
            boxes.get(0).doClick();
            assertTrue(panel.getSelectedCorrelations().isEmpty());
            panel.setCorrelations(found);
            assertEquals(3, panel.getSelectedCorrelations().get(0).getReplacements().size());
            panel.setCorrelations(List.of());
            assertTrue(panel.getSelectedCorrelations().isEmpty());
        });
    }

    private static HarEntry entry(int index, String path) {
        HarEntry entry = new HarEntry();
        entry.setOriginalIndex(index);
        entry.setStartMs(index * 100);
        entry.setEndMs(index * 100 + 50);
        entry.setMethod("GET");
        entry.setUrl("https://example.test" + path);
        entry.setResponseStatus(200);
        entry.setServerIpAddress("127.0.0.1");
        entry.setHasPositiveTiming(true);
        return entry;
    }

    private static <T> List<T> components(Container parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                result.add(type.cast(child));
            }
            if (child instanceof Container container) {
                result.addAll(components(container, type));
            }
        }
        return result;
    }

    private static void collectPaths(HashTree tree, List<String> paths) {
        for (Object element : tree.list()) {
            if (element instanceof HTTPSamplerProxy sampler) {
                paths.add(sampler.getPath());
            }
            collectPaths(tree.getTree(element), paths);
        }
    }
}
