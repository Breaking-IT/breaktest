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

package org.apache.jmeter.protocol.http.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.RecordingHostsPanel;
import org.apache.jmeter.protocol.http.proxy.gui.RecorderWizard;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class RecorderWizardTest extends JMeterTestCase {
    private static RecordedSampler sample(String host, boolean failed) throws Exception {
        var sampler = new HTTPSamplerProxy();
        sampler.setDomain(host);
        sampler.setPath(failed ? "/failed" : "/ok");
        sampler.setComment(failed ? "Connection reset while receiving response" : "");
        var result = new HTTPSampleResult();
        result.setURL(sampler.getUrl());
        result.setResponseCode(failed ? "0" : "200");
        return new RecordedSampler(sampler, new TestElement[0], null, "", 4, result, failed, null);
    }

    private static ProxyControl recorderWith(org.apache.jmeter.config.Arguments arguments) {
        return new ProxyControl() {
            @Override
            public Supplier<List<RecordingVariableReview.Candidate>> prepareRecordingVariableSearch(List<RecordedSampler> samples) {
                return () -> RecordingVariableReview.find(samples, ignored -> List.of(arguments));
            }
        };
    }

    @Test
    void variableMatchingIsOptInAndRequiresAcceptanceWithoutChangingPendingSamples() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var captured = sample("app.test", false);
        captured.sampler.setPath("/10/secret");
        var arguments = new org.apache.jmeter.config.Arguments();
        arguments.addArgument("limit", "10");
        arguments.addArgument("token", "secret");
        var recorder = recorderWith(arguments);
        var wizard = onEdt(() -> new RecorderWizard(null, recorder, List.of(captured)));
        try {
            onEdt(() -> {
                field(wizard, "next", JButton.class).doClick();
                var optIn = field(wizard, "matchVariables", JCheckBox.class);
                assertFalse(optIn.isSelected());
                assertTrue(field(wizard, "finish", JButton.class).isVisible());
                optIn.doClick();
            });
            awaitVariableSearch(wizard);
            onEdt(() -> {
                assertFalse(field(wizard, "finish", JButton.class).isVisible());
                field(wizard, "next", JButton.class).doClick();
                assertTrue(field(wizard, "title", javax.swing.JLabel.class).getText().contains("Review user-defined variable matches"));
                var table = field(wizard, "variables", javax.swing.table.AbstractTableModel.class);
                assertEquals(2, table.getRowCount());
                assertEquals(false, table.getValueAt(0, 0));
                assertEquals(false, table.getValueAt(1, 0));
                table.setValueAt(true, 0, 0);
                field(wizard, "back", JButton.class).doClick();
            });
            awaitVariableSearch(wizard);
            onEdt(() -> {
                field(wizard, "next", JButton.class).doClick();
                var table = field(wizard, "variables", javax.swing.table.AbstractTableModel.class);
                assertEquals(true, table.getValueAt(0, 0));
                field(wizard, "remember", JCheckBox.class).setSelected(false);
                capturePreview(wizard);
                field(wizard, "finish", JButton.class).doClick();
                assertEquals(1, wizard.getResult().variables().size());
                assertEquals("/10/secret", captured.sampler.getPath());
                RecordingVariableReview.apply(wizard.getResult().variables());
                assertEquals("/10/${token}", captured.sampler.getPath());
            });
        } finally {
            onEdt(wizard::dispose);
        }
    }

    @Test
    void optingOutOrPostponingDoesNotApplyAcceptedPairs() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var captured = sample("app.test", false);
        captured.sampler.setPath("/10");
        var arguments = new org.apache.jmeter.config.Arguments();
        arguments.addArgument("limit", "10");
        var recorder = recorderWith(arguments);
        for (boolean postpone : List.of(false, true)) {
            var wizard = onEdt(() -> new RecorderWizard(null, recorder, List.of(captured)));
            try {
                onEdt(() -> {
                    field(wizard, "next", JButton.class).doClick();
                    field(wizard, "matchVariables", JCheckBox.class).doClick();
                });
                awaitVariableSearch(wizard);
                onEdt(() -> {
                    field(wizard, "next", JButton.class).doClick();
                    field(wizard, "variables", javax.swing.table.AbstractTableModel.class).setValueAt(true, 0, 0);
                    if (postpone) {
                        wizard.dispose();
                        assertNull(wizard.getResult());
                    } else {
                        field(wizard, "back", JButton.class).doClick();
                        field(wizard, "matchVariables", JCheckBox.class).doClick();
                        field(wizard, "remember", JCheckBox.class).setSelected(false);
                        field(wizard, "finish", JButton.class).doClick();
                        assertTrue(wizard.getResult().variables().isEmpty());
                    }
                    assertEquals("/10", captured.sampler.getPath());
                });
            } finally {
                onEdt(wizard::dispose);
            }
        }
    }

    @Test
    void skipsVariableReviewWhenNoMatches() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var captured = sample("app.test", false);
        var recorder = new ProxyControl() {
            @Override
            public Supplier<List<RecordingVariableReview.Candidate>> prepareRecordingVariableSearch(List<RecordedSampler> samples) {
                return List::of;
            }
        };
        var wizard = onEdt(() -> new RecorderWizard(null, recorder, List.of(captured)));
        try {
            onEdt(() -> {
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "matchVariables", JCheckBox.class).doClick();
            });
            awaitVariableSearch(wizard);
            onEdt(() -> {
                assertTrue(field(wizard, "finish", JButton.class).isVisible());
                assertFalse(field(wizard, "next", JButton.class).isVisible());
                assertTrue(field(wizard, "variableHint", javax.swing.JLabel.class).getText().startsWith("No matching"));
                field(wizard, "remember", JCheckBox.class).setSelected(false);
                field(wizard, "finish", JButton.class).doClick();
                assertTrue(wizard.getResult().variables().isEmpty());
            });
        } finally {
            onEdt(wizard::dispose);
        }
    }

    @Test
    void variableSearchRunsOffTheEdtWithABusyIndicator() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var captured = sample("app.test", false);
        captured.sampler.setPath("/10");
        var arguments = new org.apache.jmeter.config.Arguments();
        arguments.addArgument("limit", "10");
        var release = new CountDownLatch(1);
        var searchedOnEdt = new AtomicReference<Boolean>();
        var recorder = new ProxyControl() {
            @Override
            public Supplier<List<RecordingVariableReview.Candidate>> prepareRecordingVariableSearch(List<RecordedSampler> samples) {
                assertTrue(SwingUtilities.isEventDispatchThread(), "Variable scopes are resolved on the EDT");
                return () -> {
                    searchedOnEdt.set(SwingUtilities.isEventDispatchThread());
                    awaitUninterruptibly(release);
                    return RecordingVariableReview.find(samples, ignored -> List.of(arguments));
                };
            }
        };
        var wizard = onEdt(() -> new RecorderWizard(null, recorder, List.of(captured)));
        try {
            onEdt(() -> {
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "matchVariables", JCheckBox.class).doClick();
                // The EDT is free while the search is blocked; the wizard shows it is busy instead.
                assertTrue(field(wizard, "variableSearchProgress", JProgressBar.class).isVisible());
                assertTrue(field(wizard, "variableHint", javax.swing.JLabel.class).getText().startsWith("Searching 1 requests"));
                assertEquals(Cursor.WAIT_CURSOR, wizard.getCursor().getType());
                assertFalse(field(wizard, "finish", JButton.class).isEnabled());
                assertFalse(field(wizard, "next", JButton.class).isVisible() && field(wizard, "next", JButton.class).isEnabled());
            });
            release.countDown();
            awaitVariableSearch(wizard);
            assertEquals(Boolean.FALSE, searchedOnEdt.get());
            onEdt(() -> {
                assertFalse(field(wizard, "variableSearchProgress", JProgressBar.class).isVisible());
                assertEquals(Cursor.DEFAULT_CURSOR, wizard.getCursor().getType());
                assertTrue(field(wizard, "variableHint", javax.swing.JLabel.class).getText().startsWith("1 matching value pairs"));
                assertTrue(field(wizard, "next", JButton.class).isVisible());
                assertTrue(field(wizard, "next", JButton.class).isEnabled());
                assertFalse(field(wizard, "finish", JButton.class).isVisible());
            });
        } finally {
            release.countDown();
            onEdt(wizard::dispose);
        }
    }

    @Test
    void uncheckingOrClosingDuringSearchDiscardsItsResult() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var captured = sample("app.test", false);
        captured.sampler.setPath("/10");
        var arguments = new org.apache.jmeter.config.Arguments();
        arguments.addArgument("limit", "10");
        for (boolean close : List.of(false, true)) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var recorder = new ProxyControl() {
                @Override
                public Supplier<List<RecordingVariableReview.Candidate>> prepareRecordingVariableSearch(List<RecordedSampler> samples) {
                    return () -> {
                        try {
                            started.countDown();
                            awaitUninterruptibly(release);
                            return RecordingVariableReview.find(samples, ignored -> List.of(arguments));
                        } finally {
                            finished.countDown();
                        }
                    };
                }
            };
            var wizard = onEdt(() -> new RecorderWizard(null, recorder, List.of(captured)));
            try {
                onEdt(() -> {
                    field(wizard, "next", JButton.class).doClick();
                    field(wizard, "matchVariables", JCheckBox.class).doClick();
                });
                // Cancel a search that is already running, not one still queued.
                assertTrue(started.await(10, TimeUnit.SECONDS));
                onEdt(() -> {
                    if (close) {
                        wizard.dispose();
                    } else {
                        field(wizard, "matchVariables", JCheckBox.class).doClick();
                    }
                    assertNull(field(wizard, "variableSearch", SwingWorker.class));
                    assertFalse(field(wizard, "variableSearchProgress", JProgressBar.class).isVisible());
                });
                release.countDown();
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                // Let any late done() callback run before checking that nothing was published.
                onEdt(() -> { });
                onEdt(() -> {
                    assertEquals(0, field(wizard, "variables", javax.swing.table.AbstractTableModel.class).getRowCount());
                    if (!close) {
                        assertTrue(field(wizard, "variableHint", javax.swing.JLabel.class).getText().startsWith("Recorded values are kept"));
                        assertTrue(field(wizard, "finish", JButton.class).isVisible());
                        assertTrue(field(wizard, "finish", JButton.class).isEnabled());
                    }
                });
            } finally {
                release.countDown();
                onEdt(wizard::dispose);
            }
        }
    }

    @Test
    void offersCorrelationReviewOnlyWhenSelectedCapturesHaveStoredExchanges() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var stored = sample("stored.test", false);
        stored.sampler.setProperty(org.apache.jmeter.recording.RecordedExchangeStore.EXCHANGE_ID_PROPERTY, "capture-1");
        var unstored = sample("unstored.test", false);
        SwingUtilities.invokeAndWait(() -> {
            var wizard = new RecorderWizard(null, new ProxyControl(), List.of(stored, unstored));
            try {
                var correlation = field(wizard, "processCorrelations", JCheckBox.class);
                assertTrue(correlation.isSelected());
                assertTrue(correlation.isEnabled());
                checkbox(field(wizard, "hosts", RecordingHostsPanel.class), "stored.test").doClick();
                assertFalse(correlation.isEnabled());
                checkbox(field(wizard, "hosts", RecordingHostsPanel.class), "stored.test").doClick();
                assertTrue(correlation.isEnabled());
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "remember", JCheckBox.class).setSelected(false);
                field(wizard, "finish", JButton.class).doClick();
                assertTrue(wizard.getResult().processCorrelations());
            } finally {
                wizard.dispose();
            }
            var withoutStorage = new RecorderWizard(null, new ProxyControl(), List.of(unstored));
            try {
                field(withoutStorage, "next", JButton.class).doClick();
                field(withoutStorage, "remember", JCheckBox.class).setSelected(false);
                field(withoutStorage, "finish", JButton.class).doClick();
                assertFalse(withoutStorage.getResult().processCorrelations());
            } finally {
                withoutStorage.dispose();
            }
        });
    }

    @Test
    void reviewsHostsFailuresAndSettingsWithoutSelectingFailuresByDefault() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var success = sample("app.test", false);
        var failure = sample("app.test", true);
        var advertisement = sample("ads.test", false);
        SwingUtilities.invokeAndWait(() -> {
            var recorder = new ProxyControl();
            recorder.getRecordingDiagnostics().incomplete("Server reset stream: HTTP/2 RST_STREAM error 2");
            var wizard = new RecorderWizard(null, recorder, List.of(success, failure, advertisement));
            try {
                RecordingHostsPanel hosts = field(wizard, "hosts", RecordingHostsPanel.class);
                checkbox(hosts, "ads.test").doClick();
                assertEquals(Set.of("app.test"), hosts.selectedHostnames());
                field(wizard, "next", JButton.class).doClick();
                JTable failures = find(wizard, JTable.class);
                assertEquals(1, failures.getRowCount());
                assertEquals(false, failures.getValueAt(0, 0));
                assertTrue(failures.getValueAt(0, 4).toString().contains("Connection reset"));
                failures.setValueAt(true, 0, 0);
                field(wizard, "back", JButton.class).doClick();
                field(wizard, "next", JButton.class).doClick();
                assertEquals(true, failures.getValueAt(0, 0), "Back/Next must preserve deliberate failure selections");
                capturePreview(wizard);
                field(wizard, "next", JButton.class).doClick();
                assertTrue(field(wizard, "transactions", JCheckBox.class).isSelected());
                field(wizard, "remember", JCheckBox.class).setSelected(false);
                field(wizard, "finish", JButton.class).doClick();
                assertEquals(Set.of("app.test"), wizard.getResult().hosts());
                assertEquals(Set.of(failure), wizard.getResult().failed());
                assertEquals(4, wizard.getResult().grouping());
            } finally {
                wizard.dispose();
            }
        });
    }

    @Test
    void permitsFinishingWithAllFailuresExcludedAndClosingWithoutApplying() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var failure = sample("app.test", true);
        SwingUtilities.invokeAndWait(() -> {
            var wizard = new RecorderWizard(null, new ProxyControl(), List.of(failure));
            try {
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "remember", JCheckBox.class).setSelected(false);
                JButton finish = field(wizard, "finish", JButton.class);
                assertTrue(finish.isEnabled());
                assertEquals("Finish without adding requests", finish.getText());
                finish.doClick();
                assertTrue(wizard.getResult().failed().isEmpty());
                assertTrue(ProxyControl.selectRecording(List.of(failure), wizard.getResult().hosts(), wizard.getResult().failed()).isEmpty());
            } finally {
                wizard.dispose();
            }
            var postponed = new RecorderWizard(null, new ProxyControl(), List.of(failure));
            postponed.dispose();
            assertNull(postponed.getResult());
        });
    }

    @Test
    void reviewsInspectionFailuresWithoutAnyCapturedSamplers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var recorder = new ProxyControl();
            recorder.getRecordingDiagnostics().issue("HTTP/2 inspection failed; recording is incomplete");
            assertTrue(recorder.hasPendingRecording());
            var wizard = new RecorderWizard(null, recorder, List.of());
            try {
                assertTrue(find(wizard, javax.swing.JTextArea.class).getText().contains("HTTP/2 inspection failed"));
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "remember", JCheckBox.class).setSelected(false);
                field(wizard, "finish", JButton.class).doClick();
                assertTrue(wizard.getResult().hosts().isEmpty());
            } finally {
                wizard.dispose();
            }
        });
    }

    @Test
    void showsFailuresForExcludedHostsButSkipsReviewWhenThereAreNoFailures() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var success = sample("app.test", false);
        var failure = sample("other.test", true);
        SwingUtilities.invokeAndWait(() -> {
            var wizard = new RecorderWizard(null, new ProxyControl(), List.of(success, failure));
            try {
                var hosts = field(wizard, "hosts", RecordingHostsPanel.class);
                checkbox(hosts, "other.test").doClick();
                assertTrue(field(wizard, "title", javax.swing.JLabel.class).getText().startsWith("1 of 3"));
                field(wizard, "next", JButton.class).doClick();
                JTable table = find(wizard, JTable.class);
                assertEquals(1, table.getRowCount());
                assertFalse(table.isCellEditable(0, 0));
                assertTrue(table.getValueAt(0, 5).toString().contains("Host excluded"));
                field(wizard, "back", JButton.class).doClick();
                checkbox(hosts, "other.test").doClick();
                assertTrue(field(wizard, "title", javax.swing.JLabel.class).getText().startsWith("1 of 3"));
                field(wizard, "next", JButton.class).doClick();
                assertTrue(field(wizard, "title", javax.swing.JLabel.class).getText().contains("Review failed captures"));
                assertFalse(field(wizard, "finish", JButton.class).isVisible());
            } finally {
                wizard.dispose();
            }
            var successful = new RecorderWizard(null, new ProxyControl(), List.of(success));
            try {
                field(successful, "next", JButton.class).doClick();
                assertTrue(field(successful, "finish", JButton.class).isVisible());
                field(successful, "back", JButton.class).doClick();
                assertTrue(field(successful, "title", javax.swing.JLabel.class).getText().startsWith("1 of 2"));
            } finally {
                successful.dispose();
            }
        });
    }

    @Test
    void showsTlsFailuresWithDestinationsEvenWithoutCapturedRequests() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var recorder = new ProxyControl();
            recorder.getRecordingDiagnostics().uncapturedFailure("https://secure.test:443", "CONNECT (TLS handshake)",
                    "SSLHandshakeException: certificate_unknown");
            var wizard = new RecorderWizard(null, recorder, List.of());
            try {
                field(wizard, "next", JButton.class).doClick();
                assertTrue(field(wizard, "title", javax.swing.JLabel.class).getText().contains("Review failed captures"));
                JTable table = find(wizard, JTable.class);
                assertEquals(1, table.getRowCount());
                assertEquals("secure.test", table.getValueAt(0, 1));
                assertEquals("https://secure.test:443", table.getValueAt(0, 3));
                assertTrue(table.getValueAt(0, 4).toString().contains("certificate_unknown"));
                assertFalse(table.isCellEditable(0, 0));
                assertTrue(table.getValueAt(0, 5).toString().contains("no HTTP request captured"));
            } finally {
                wizard.dispose();
            }
        });
    }

    @Test
    void finishingReviewDoesNotRewriteTheRecordersTransactionPause() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var success = sample("example.test", false);
        SwingUtilities.invokeAndWait(() -> {
            var recorder = new ProxyControl();
            recorder.setProxyPauseHTTPSample("40125");
            var wizard = new RecorderWizard(null, recorder, List.of(success));
            try {
                field(wizard, "next", JButton.class).doClick();
                field(wizard, "remember", JCheckBox.class).setSelected(false);
                field(wizard, "finish", JButton.class).doClick();
                assertEquals("40125", recorder.getProxyPauseHTTPSample());
                assertEquals(4, wizard.getResult().grouping());
            } finally {
                wizard.dispose();
            }
        });
    }

    @FunctionalInterface
    private interface EdtAction {
        void run() throws Exception;
    }

    private static void onEdt(EdtAction action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        if (failure.get() instanceof Exception e) {
            throw e;
        }
        if (failure.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }

    /** The match search runs in a SwingWorker; wait until the wizard has published its result. */
    private static void awaitVariableSearch(RecorderWizard wizard) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (onEdt(() -> field(wizard, "variableSearch", SwingWorker.class) != null)) {
            assertTrue(System.nanoTime() < deadline, "Variable search did not finish");
            Thread.sleep(10);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release the variable search");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void capturePreview(RecorderWizard wizard) {
        String destination = System.getenv("BREAKTEST_RECORDER_SCREENSHOT");
        if (destination == null) {
            return;
        }
        wizard.getContentPane().setSize(wizard.getSize());
        layout(wizard.getContentPane());
        var image = new java.awt.image.BufferedImage(wizard.getWidth(), wizard.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            wizard.getContentPane().printAll(graphics);
            javax.imageio.ImageIO.write(image, "png", new java.io.File(destination));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            graphics.dispose();
        }
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layout(child);
            }
        }
    }

    private static JCheckBox checkbox(Container parent, String startsWith) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JCheckBox checkbox && startsWith.equals(checkbox.getClientProperty("harHostname"))) {
                return checkbox;
            }
            if (component instanceof Container child) {
                JCheckBox found = checkbox(child, startsWith);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T find(Container parent, Class<T> type) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T field(Object object, String name, Class<T> type) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(object));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
