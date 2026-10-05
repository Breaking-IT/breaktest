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
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

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
