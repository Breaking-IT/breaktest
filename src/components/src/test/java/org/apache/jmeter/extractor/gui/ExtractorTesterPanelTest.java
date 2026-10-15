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

package org.apache.jmeter.extractor.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.extractor.json.jmespath.gui.JMESPathExtractorGui;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui;
import org.apache.jmeter.processor.gui.AbstractPostProcessorGui;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JLabeledTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExtractorTesterPanelTest {
    @Test
    void showsVariablesTimingAndErrorsThenClears() throws Exception {
        JSONPostProcessor extractor = new JSONPostProcessor();
        extractor.setRefNames("id");
        extractor.setJsonPathExpressions("$.id");
        extractor.setMatchNumbers("1");
        extractor.setDefaultValues("");
        SampleResult sample = new SampleResult();
        sample.setResponseData("{\"id\":42}", "UTF-8");
        // Warm the parser before testing the bounded asynchronous preview.
        extractor.extractForTesting(sample);
        AtomicReference<ExtractorTesterPanel<JSONPostProcessor>> panelRef = new AtomicReference<>();
        CountDownLatch success = new CountDownLatch(1);
        CountDownLatch failure = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            ExtractorTesterPanel<JSONPostProcessor> panel = new ExtractorTesterPanel<>("jsonpath_tester_title", () -> extractor,
                    e -> !e.getJsonPathExpressions().isBlank(), JSONPostProcessor::extractForTesting);
            panelRef.set(panel);
            JLabel status = field(panel, "testResultStatus", JLabel.class);
            status.addPropertyChangeListener("text", event -> {
                String text = (String) event.getNewValue();
                if (text.startsWith(JMeterUtils.getResString("regex_test_match_count"))) {
                    success.countDown();
                }
                if (text.startsWith(JMeterUtils.getResString("extractor_test_error"))) {
                    failure.countDown();
                }
            });
            setField(panel, "recordedSampleResult", sample);
            panel.updateTestResult();
        });
        assertTrue(success.await(5, TimeUnit.SECONDS), "Preview must finish");
        SwingUtilities.invokeAndWait(() -> {
            ExtractorTesterPanel<JSONPostProcessor> panel = panelRef.get();
            JTable table = field(panel, "testResultTable", JTable.class);
            assertEquals("id", table.getValueAt(0, 0));
            assertEquals("42", table.getValueAt(0, 1));
            assertTrue(field(panel, "testParseTime", ExtractorTestTiming.class).isVisible());
            extractor.setJsonPathExpressions("$[");
            panel.updateTestResult();
            assertEquals(0, table.getRowCount());
        });
        assertTrue(failure.await(5, TimeUnit.SECONDS), "Invalid expression must show an error");
        SwingUtilities.invokeAndWait(() -> {
            ExtractorTesterPanel<JSONPostProcessor> panel = panelRef.get();
            assertTrue(field(panel, "testParseTime", ExtractorTestTiming.class).isVisible());
            panel.clear();
            assertEquals("", field(panel, "testResultStatus", JLabel.class).getText());
            assertEquals(0, field(panel, "testResultTable", JTable.class).getRowCount());
        });
    }

    @Test
    void guiRoundTripsAndReportsUnavailableSources() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSONPostProcessor extractor = new JSONPostProcessor();
            extractor.setRefNames("id");
            extractor.setJsonPathExpressions("$.id");
            extractor.setMatchNumbers("-1");
            extractor.setDefaultValues("missing");
            extractor.setComputeConcatenation(true);
            JSONPostProcessorGui gui = new JSONPostProcessorGui();
            gui.configure(extractor);
            JSONPostProcessor saved = (JSONPostProcessor) gui.createTestElement();
            assertEquals("$.id", saved.getJsonPathExpressions());
            assertEquals("-1", saved.getMatchNumbers());
            assertTrue(saved.getComputeConcatenation());
            ExtractorTesterPanel<?> panel = field(gui, "tester", ExtractorTesterPanel.class);
            JLabel status = field(panel, "testResultStatus", JLabel.class);
            assertEquals(JMeterUtils.getResString("regex_test_no_recorded_response"), status.getText());
            extractor.setScopeVariable("body");
            gui.configure(extractor);
            assertEquals(JMeterUtils.getResString("extractor_test_variable_scope"), status.getText());
            gui.clearGui();
            assertEquals("", status.getText());
            assertEquals("", ((JSONPostProcessor) gui.createTestElement()).getJsonPathExpressions());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"boundary", "css", "xpath", "xpath2", "jmespath"})
    void allExtractorEditorsRefreshAndClearTheirPreview(String type) throws Exception {
        AtomicReference<AbstractPostProcessorGui> guiRef = new AtomicReference<>();
        CountDownLatch success = new CountDownLatch(1);
        CountDownLatch noMatches = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            AbstractPostProcessorGui gui;
            String queryMethod;
            switch (type) {
            case "boundary":
                gui = new BoundaryExtractorGui();
                queryMethod = "setLeftBoundary";
                break;
            case "css":
                gui = new HtmlExtractorGui();
                queryMethod = "setExpression";
                break;
            case "xpath":
                gui = new XPathExtractorGui();
                queryMethod = "setXPathQuery";
                break;
            case "xpath2":
                gui = new XPath2ExtractorGui();
                queryMethod = "setXPathQuery";
                break;
            default:
                gui = new JMESPathExtractorGui();
                queryMethod = "setJmesPathExpression";
            }
            guiRef.set(gui);
            TestElement extractor = gui.createTestElement();
            try {
                extractor.getClass().getMethod("setRefName", String.class).invoke(extractor, "value");
                extractor.getClass().getMethod("setMatchNumber", String.class).invoke(extractor, "-1");
                String expression = switch (type) {
                case "boundary" -> "id='";
                case "css" -> "item";
                case "jmespath" -> "items[*].id";
                default -> "/root/item/@id";
                };
                extractor.getClass().getMethod(queryMethod, String.class).invoke(extractor, expression);
                if ("boundary".equals(type)) {
                    extractor.getClass().getMethod("setRightBoundary", String.class).invoke(extractor, "'");
                }
                if ("css".equals(type)) {
                    extractor.getClass().getMethod("setAttribute", String.class).invoke(extractor, "id");
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            gui.configure(extractor);
            ExtractorTesterPanel<?> panel = field(gui, "tester", ExtractorTesterPanel.class);
            JLabel status = field(panel, "testResultStatus", JLabel.class);
            assertEquals(JMeterUtils.getResString("regex_test_no_recorded_response"), status.getText());
            status.addPropertyChangeListener("text", event -> {
                String text = (String) event.getNewValue();
                if (text.startsWith(JMeterUtils.getResString("regex_test_match_count") + " 2")) {
                    success.countDown();
                }
                if (text.startsWith(JMeterUtils.getResString("regex_test_match_count") + " 0")) {
                    noMatches.countDown();
                }
            });
            SampleResult sample = new SampleResult();
            sample.setResponseData("jmespath".equals(type) ? "{\"items\":[{\"id\":1},{\"id\":2}]}"
                    : "<root><item id='1'/><item id='2'/></root>", "UTF-8");
            setField(panel, "recordedSampleResult", sample);
            panel.updateTestResult();
        });
        assertTrue(success.await(5, TimeUnit.SECONDS), type + " preview must finish");
        SwingUtilities.invokeAndWait(() -> {
            AbstractPostProcessorGui gui = guiRef.get();
            String queryField = switch (type) {
            case "boundary" -> "leftBoundaryField";
            case "css" -> "expressionField";
            case "jmespath" -> "jmesPathExpressionField";
            default -> "xpathQueryField";
            };
            Object field = field(gui, queryField, Object.class);
            if (field instanceof JTextField text) {
                text.setText("absent");
            } else {
                ((JLabeledTextField) field).setText("absent");
            }
        });
        assertTrue(noMatches.await(5, TimeUnit.SECONDS), type + " must refresh when the expression changes");
        SwingUtilities.invokeAndWait(() -> {
            AbstractPostProcessorGui gui = guiRef.get();
            gui.clearGui();
            ExtractorTesterPanel<?> panel = field(gui, "tester", ExtractorTesterPanel.class);
            assertEquals("", field(panel, "testResultStatus", JLabel.class).getText());
            assertEquals(0, field(panel, "testResultTable", JTable.class).getRowCount());
        });
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
