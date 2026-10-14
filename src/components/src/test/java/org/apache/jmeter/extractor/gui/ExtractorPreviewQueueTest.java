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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.apache.jmeter.extractor.ExtractorTestResult;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class ExtractorPreviewQueueTest implements JMeterSerialTest {
    @Test
    void queuedPreviewReportsBusyAndRetriesAutomaticallyAfterUninterruptibleEvaluationStops() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch timeout = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch success = new CountDownLatch(1);
        AtomicInteger evaluations = new AtomicInteger();
        AtomicReference<ExtractorTesterPanel<JSONPostProcessor>> first = new AtomicReference<>();
        AtomicReference<ExtractorTesterPanel<JSONPostProcessor>> second = new AtomicReference<>();
        ExtractorTestResult result = new ExtractorTestResult(Map.of("value", "ok"), Set.of(), 1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                first.set(panel((extractor, sample) -> {
                    // Model parsers/regex engines that do not honor thread interruption.
                    boolean released = false;
                    while (!released) {
                        try {
                            release.await();
                            released = true;
                        } catch (InterruptedException ignored) {
                            // Deliberately remain on the worker after its execution timeout.
                        }
                    }
                    return result;
                }));
                onStatus(first.get(), "extractor_test_timeout", timeout);
                first.get().updateTestResult();
            });
            assertTrue(timeout.await(10, TimeUnit.SECONDS), "Running evaluation should time out");
            SwingUtilities.invokeAndWait(() -> {
                first.get().clear();
                second.set(panel((extractor, sample) -> {
                    evaluations.incrementAndGet();
                    return result;
                }));
                onStatus(second.get(), "extractor_test_busy", busy);
                onStatus(second.get(), "regex_test_match_count", success);
                second.get().updateTestResult();
            });
            assertTrue(busy.await(10, TimeUnit.SECONDS), "Queued evaluation must report busy within a bounded wait");
            assertEquals(0, evaluations.get(), "Expired queued work must never evaluate");
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0, field(second.get(), "testResultTable", JTable.class).getRowCount());
                assertFalse(field(second.get(), "testParseTime", ExtractorTestTiming.class).isVisible());
            });
            release.countDown();
            assertTrue(success.await(10, TimeUnit.SECONDS),
                    "The busy preview should retry by itself once the worker is available");
            assertEquals(1, evaluations.get());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("ok", field(second.get(), "testResultTable", JTable.class).getValueAt(0, 1));
                assertEquals("", field(first.get(), "testResultStatus", JLabel.class).getText());
            });
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                if (first.get() != null) {
                    first.get().clear();
                }
                if (second.get() != null) {
                    second.get().clear();
                }
            });
        }
    }

    @Test
    void newPreviewCancelsRunStillOccupyingWorkerForAnotherTester() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstInterrupted = new CountDownLatch(1);
        CountDownLatch success = new CountDownLatch(1);
        AtomicReference<ExtractorTesterPanel<JSONPostProcessor>> first = new AtomicReference<>();
        AtomicReference<ExtractorTesterPanel<JSONPostProcessor>> second = new AtomicReference<>();
        ExtractorTestResult result = new ExtractorTestResult(Map.of("value", "ok"), Set.of(), 1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                // A tester that is no longer shown is never cleared by GuiPackage.
                first.set(panel((extractor, sample) -> {
                    firstStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        firstInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return result;
                }));
                first.get().updateTestResult();
            });
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                second.set(panel((extractor, sample) -> result));
                onStatus(second.get(), "regex_test_match_count", success);
                second.get().updateTestResult();
            });
            assertTrue(firstInterrupted.await(1, TimeUnit.SECONDS),
                    "Another tester's run must be cancelled instead of holding the worker until its timeout");
            assertTrue(success.await(1, TimeUnit.SECONDS),
                    "The new preview must run without waiting for the previous tester's timeout");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (first.get() != null) {
                    first.get().clear();
                }
                if (second.get() != null) {
                    second.get().clear();
                }
            });
        }
    }

    private static ExtractorTesterPanel<JSONPostProcessor> panel(
            ExtractorTesterPanel.Evaluation<JSONPostProcessor> evaluation) {
        var panel = new ExtractorTesterPanel<>("jsonpath_tester_title", JSONPostProcessor::new,
                extractor -> true, evaluation);
        try {
            var field = ExtractorTesterPanel.class.getDeclaredField("recordedSampleResult");
            field.setAccessible(true);
            field.set(panel, new SampleResult());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return panel;
    }

    private static void onStatus(ExtractorTesterPanel<?> panel, String key, CountDownLatch latch) {
        field(panel, "testResultStatus", JLabel.class).addPropertyChangeListener("text", event -> {
            if (((String) event.getNewValue()).startsWith(JMeterUtils.getResString(key))) {
                latch.countDown();
            }
        });
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
