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

package org.apache.jmeter.reporters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.samplers.TransactionRef;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResultCollectorTransactionTest extends JMeterTestCase {
    private final TransactionRef outer = TransactionRef.start("outer", null);
    private final TransactionRef inner = TransactionRef.start("inner", outer);

    private List<SampleResult> samples() {
        SampleResult child = sample("child");
        child.setParentTransaction(inner);
        SampleResult nested = sample("inner");
        nested.setTransaction(inner);
        SampleResult transaction = sample("outer");
        transaction.setTransaction(outer);
        return List.of(child, nested, transaction, sample("outside"));
    }

    private static SampleResult sample(String label) {
        SampleResult result = new SampleResult(1000, 10);
        result.setSampleLabel(label);
        result.setSuccessful(true);
        return result;
    }

    @Test
    void liveResultsHideChildrenByDefaultAndRespectOutcomeFilters() {
        ResultCollector collector = new ResultCollector();
        RecordingVisualizer visualizer = new RecordingVisualizer();
        collector.setListener(visualizer);
        List<SampleResult> samples = samples();
        samples.forEach(s -> collector.sampleOccurred(new SampleEvent(s, "tg")));
        assertEquals(samples.subList(2, 4), visualizer.results);

        collector.setShowTransactionChildren(true);
        visualizer.results.clear();
        samples.forEach(s -> collector.sampleOccurred(new SampleEvent(s, "tg")));
        assertEquals(samples, visualizer.results);

        samples.get(0).setSuccessful(false);
        collector.setErrorLogging(true);
        visualizer.results.clear();
        samples.forEach(s -> collector.sampleOccurred(new SampleEvent(s, "tg")));
        assertEquals(List.of(samples.get(0)), visualizer.results);

        collector.setShowTransactionChildren(false);
        visualizer.results.clear();
        samples.forEach(s -> collector.sampleOccurred(new SampleEvent(s, "tg")));
        assertTrue(visualizer.results.isEmpty());
    }

    @Test
    void hierarchicalVisualizerKeepsChildrenAndStartEvents() {
        ResultCollector collector = new ResultCollector();
        RecordingVisualizer visualizer = new RecordingVisualizer() {
            @Override
            public boolean displaysTransactionHierarchy() {
                return true;
            }
        };
        collector.setListener(visualizer);
        List<SampleResult> samples = samples();
        samples.forEach(s -> {
            SampleEvent event = new SampleEvent(s, "tg");
            collector.sampleStarted(event);
            collector.transactionStarted(event);
            collector.sampleStopped(event);
            collector.sampleOccurred(event);
        });
        assertEquals(samples, visualizer.results);
        assertEquals(12, visualizer.startEvents);
    }

    @Test
    void flatVisualizerFiltersStartAndStopEventsConsistently() {
        ResultCollector collector = new ResultCollector();
        RecordingVisualizer visualizer = new RecordingVisualizer();
        collector.setListener(visualizer);
        samples().forEach(s -> {
            SampleEvent event = new SampleEvent(s, "tg");
            collector.sampleStarted(event);
            collector.transactionStarted(event);
            collector.sampleStopped(event);
        });
        assertEquals(6, visualizer.startEvents);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savedFilesRetainChildrenAndLoadingUsesDisplayFilter(boolean xml, @TempDir Path directory) {
        ResultCollector collector = new ResultCollector();
        RecordingVisualizer visualizer = new RecordingVisualizer();
        collector.setListener(visualizer);
        collector.setFilename(directory.resolve(xml ? "results.xml" : "results.csv").toString());
        SampleSaveConfiguration config = new SampleSaveConfiguration();
        config.setAsXml(xml);
        config.setTransactionIds(true);
        collector.setSaveConfig(config);
        collector.testStarted();
        try {
            samples().forEach(s -> collector.sampleOccurred(new SampleEvent(s, "tg")));
        } finally {
            collector.testEnded();
        }
        assertEquals(List.of("outer", "outside"), visualizer.labels());
        visualizer.results.clear();
        collector.loadExistingFile();
        assertEquals(List.of("outer", "outside"), visualizer.labels());
        visualizer.results.clear();
        collector.setShowTransactionChildren(true);
        collector.loadExistingFile();
        assertEquals(List.of("child", "inner", "outer", "outside"), visualizer.labels());
    }

    @Test
    void resultFiltersPersistAndReset() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gui = new AbstractVisualizer() {
                public void selectOutcome(int index) {
                    getResultFilter().setSelectedIndex(index);
                }

                public int selectedOutcome() {
                    return getResultFilter().getSelectedIndex();
                }

                @Override
                public String getLabelResource() {
                    return "view_results_in_table";
                }

                @Override
                public void add(SampleResult sample) {
                }

                @Override
                public void clearData() {
                }
            };
            ResultCollector collector = new ResultCollector();
            gui.configure(collector);
            assertFalse(((ResultCollector) gui.createTestElement()).isShowTransactionChildren());
            collector.setShowTransactionChildren(true);
            gui.configure(collector);
            ResultCollector saved = (ResultCollector) gui.createTestElement();
            assertTrue(saved.isShowTransactionChildren());
            assertTrue(((ResultCollector) saved.clone()).isShowTransactionChildren());
            for (int index = 0; index < 3; index++) {
                gui.selectOutcome(index);
                ResultCollector filtered = (ResultCollector) gui.createTestElement();
                assertEquals(index == 1, filtered.isErrorLogging());
                assertEquals(index == 2, filtered.isSuccessOnlyLogging());
                gui.clearGui();
                gui.configure(filtered);
                assertEquals(index, gui.selectedOutcome());
                assertTrue(((ResultCollector) gui.createTestElement()).isShowTransactionChildren());
            }
            // Older plans can contain both flags; together they accept all outcomes.
            collector.setErrorLogging(true);
            collector.setSuccessOnlyLogging(true);
            gui.configure(collector);
            assertEquals(0, gui.selectedOutcome());
            gui.selectOutcome(1);
            gui.clearGui();
            ResultCollector cleared = (ResultCollector) gui.createTestElement();
            assertFalse(cleared.isShowTransactionChildren());
            assertFalse(cleared.isErrorLogging());
            assertFalse(cleared.isSuccessOnlyLogging());
        });
    }

    private static class RecordingVisualizer implements Visualizer {
        private final List<SampleResult> results = new ArrayList<>();
        private int startEvents;

        List<String> labels() {
            return results.stream().map(SampleResult::getSampleLabel).toList();
        }

        @Override
        public void add(SampleResult sample) {
            results.add(sample);
        }

        @Override
        public boolean isStats() {
            return false;
        }

        @Override
        public boolean needsStartedResults() {
            return true;
        }

        @Override
        public void addStartedSample(SampleEvent event) {
            startEvents++;
        }

        @Override
        public void addStartedTransaction(SampleEvent event) {
            startEvents++;
        }

        @Override
        public void removeStartedSample(SampleEvent event) {
            startEvents++;
        }
    }
}
