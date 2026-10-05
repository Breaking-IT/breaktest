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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarImportOptions;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderWorkflowTest extends JMeterTestCase {
    private final JMeterTreeModel model = new JMeterTreeModel();

    private JMeterTreeNode target() {
        JMeterTreeNode parent = model.getNodesOfType(ThreadGroupsSection.class).get(0);
        JMeterTreeNode target = new JMeterTreeNode(new ThreadGroup(), model);
        model.insertNodeInto(target, parent, parent.getChildCount());
        return target;
    }

    private static HTTPSamplerProxy sampler(String host, String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setDomain(host);
        sampler.setProtocol("https");
        sampler.setPath(path);
        sampler.setName(path);
        sampler.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui.class.getName());
        return sampler;
    }

    private static HTTPSampleResult result(HTTPSamplerBase sampler, long start, long end, String status) throws Exception {
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(sampler.getUrl());
        result.setHTTPMethod("GET");
        result.setProtocolVersion("HTTP/2");
        result.setResponseCode(status);
        result.setResponseMessage("0".equals(status) ? "Connection reset" : "OK");
        result.setStampAndTime(start, 0);
        result.setEndTime(end);
        return result;
    }

    @Test
    void ordersByStartAndGroupsOverlapsUsingLatestEndForThinkTime() throws Exception {
        ProxyControl recorder = new ProxyControl();
        recorder.setNonGuiTreeModel(model);
        recorder.setTarget(target());
        assertEquals(4, recorder.getGroupingMode());
        assertTrue(recorder.getStoreRecordedExchanges());
        assertFalse(recorder.getSamplerFollowRedirects());
        assertFalse(recorder.getSamplerRedirectAutomatically());
        // Completion order B, C, A, D differs from start order A, B, C, D.
        String[] names = {"B", "C", "A", "D"};
        long[] starts = {1100, 7000, 1000, 16000};
        long[] ends = {1200, 7100, 10000, 16100};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals("D")) {
                recorder.setPrefixHTTPSampleName("Next action");
            }
            var sampler = sampler("example.test", names[i]);
            recorder.deliverSampler(sampler, new TestElement[0], result(sampler, starts[i], ends[i], "200"));
        }
        assertEquals(0, model.getNodesOfType(HTTPSamplerBase.class).size());
        recorder.stopProxy();
        var samplers = model.getNodesOfType(HTTPSamplerBase.class);
        assertEquals(List.of("A", "B", "C", "D"), samplers.stream().map(JMeterTreeNode::getName).toList());
        assertEquals(samplers.get(0).getParent(), samplers.get(1).getParent());
        assertEquals(samplers.get(0).getParent(), samplers.get(2).getParent(), "C overlaps the still-running A");
        assertEquals("Parallel Requests 1", ((JMeterTreeNode) samplers.get(0).getParent()).getName());
        var transactions = model.getNodesOfType(TransactionController.class);
        assertEquals(2, transactions.size(), "C must not start a transaction while A is still in flight");
        assertEquals("Disabled", transactions.get(0).getTestElement().getPropertyAsString("TransactionController.delayMode"));
        assertEquals("6000", transactions.get(1).getTestElement().getPropertyAsString("TransactionController.fixedDelay"));
    }

    @Test
    void hostAndFailureReviewKeepsHttpErrorsAndOnlyEnablesChosenTransportFailures() throws Exception {
        JMeterTreeNode target = target();
        var ok = sampler("app.test", "ok");
        var httpError = sampler("app.test", "http-error");
        var failed = sampler("app.test", "failed");
        failed.setEnabled(false);
        failed.setComment("Connection reset");
        var excluded = sampler("ads.test", "excluded");
        List<RecordedSampler> samples = List.of(
                new RecordedSampler(failed, new TestElement[0], target, "", 4, result(failed, 1200, 1300, "0"), true, null),
                new RecordedSampler(excluded, new TestElement[0], target, "", 4, result(excluded, 1300, 1400, "200"), false, null),
                new RecordedSampler(httpError, new TestElement[0], target, "", 4, result(httpError, 1100, 1150, "500"), false, null),
                new RecordedSampler(ok, new TestElement[0], target, "", 4, result(ok, 1000, 1050, "200"), false, null));
        var defaults = ProxyControl.selectRecording(samples, Set.of("app.test"), Set.of());
        assertEquals(List.of("https://app.test/ok", "https://app.test/http-error"),
                defaults.stream().map(sample -> sample.entry().getUrl()).toList());
        var selected = ProxyControl.selectRecording(samples, Set.of("app.test"), Set.of(samples.get(0)));
        ProxyControl recorder = new ProxyControl();
        recorder.setNonGuiTreeModel(model);
        SwingUtilities.invokeAndWait(() -> recorder.applyRecording(selected, new HarImportOptions(), 4, true));
        assertEquals(3, model.getNodesOfType(HTTPSamplerBase.class).size());
        assertTrue(failed.isEnabled(), "Explicitly included failed requests are replayable");
        assertFalse(ProxyControl.selectRecording(samples, Set.of("ads.test"), Set.of(samples.get(0))).contains(samples.get(0)));
    }

    @Test
    void overlappingChainsStayParallelButRedirectsAndTouchingIntervalsStaySequential() throws Exception {
        var recorder = new ProxyControl();
        recorder.setNonGuiTreeModel(model);
        recorder.setTarget(target());
        String[] names = {"A", "B", "C", "D", "redirect", "destination"};
        long[] starts = {1000, 1050, 1150, 1400, 1500, 1599};
        long[] ends = {1100, 1200, 1400, 1500, 1600, 1700};
        for (int i = 0; i < names.length; i++) {
            var sampler = sampler("example.test", "/" + names[i]);
            var result = result(sampler, starts[i], ends[i], i == 4 ? "302" : "200");
            if (i == 4) {
                result.setRedirectLocation("/destination");
            }
            recorder.deliverSampler(sampler, new TestElement[0], result);
        }
        recorder.stopProxy();
        var parallel = model.getNodesOfType(org.apache.jmeter.control.ParallelController.class);
        assertEquals(1, parallel.size());
        assertEquals(100, ((org.apache.jmeter.control.ParallelController) parallel.get(0).getTestElement()).getMaxParallel());
        assertEquals(3, parallel.get(0).getChildCount(), "A/B/C form one connected overlap group");
        var samplers = model.getNodesOfType(HTTPSamplerBase.class);
        assertEquals(List.of("/A", "/B", "/C", "/D", "/redirect", "/destination"),
                samplers.stream().map(JMeterTreeNode::getName).toList());
        assertFalse(samplers.get(3).getParent() == parallel.get(0), "Starting exactly at the previous end is sequential");
        assertFalse(samplers.get(5).getParent().equals(parallel.get(0)), "Redirect target must follow its response");
    }

    @Test
    void expandsRecordingThreadGroupsAndNestedTargetsWithoutCollapsingOtherGroups() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var group = target();
            var other = target();
            var nested = new JMeterTreeNode(new org.apache.jmeter.control.GenericController(), model);
            model.insertNodeInto(nested, group, 0);
            model.insertNodeInto(new JMeterTreeNode(sampler("example.test", "/"), model), nested, 0);
            model.insertNodeInto(new JMeterTreeNode(sampler("example.test", "/other"), model), other, 0);
            var tree = new javax.swing.JTree(model);
            var groupPath = new javax.swing.tree.TreePath(group.getPath());
            var otherPath = new javax.swing.tree.TreePath(other.getPath());
            tree.expandPath(otherPath);
            tree.collapsePath(groupPath);
            RecordingTreeExpansion.expand(tree, List.of(nested));
            assertTrue(tree.isExpanded(groupPath));
            assertTrue(tree.isExpanded(new javax.swing.tree.TreePath(nested.getPath())));
            assertTrue(tree.isExpanded(otherPath));
        });
    }

    @Test
    void retainsSubMillisecondOverlapWhenSampleResultTimesRoundToTheSameMillisecond() throws Exception {
        var recorder = new ProxyControl();
        recorder.setNonGuiTreeModel(model);
        recorder.setTarget(target());
        double[] starts = {1000.1, 1000.3, 1000.7};
        double[] ends = {1000.5, 1000.7, 1000.9};
        for (int i = 0; i < starts.length; i++) {
            var sampler = sampler("example.test", "/" + i);
            byte[] head = ("GET /" + i + " HTTP/2\r\nHost: example.test\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            var capture = new HttpProxyTransport.Capture(new HttpProxyTransport.Head(head), sampler.getUrl());
            capture.setResponseCode("200");
            capture.completeHttp2(head, new byte[0], new byte[0], new byte[0], "", "", starts[i], ends[i], i);
            assertEquals(1000, capture.getStartTime());
            assertEquals(1000, capture.getEndTime());
            recorder.deliverSampler(sampler, new TestElement[0], capture);
        }
        recorder.stopProxy();
        var parallel = model.getNodesOfType(org.apache.jmeter.control.ParallelController.class);
        assertEquals(1, parallel.size());
        assertEquals(2, parallel.get(0).getChildCount(), "First two overlap; third starts exactly at the second's end");
        assertEquals(3, model.getNodesOfType(HTTPSamplerBase.class).size());
    }

    @Test
    void ignoresLegacyPauseValuesWhenGroupingNamedTransactions() throws Exception {
        var recorder = new ProxyControl();
        recorder.setNonGuiTreeModel(model);
        recorder.setTarget(target());
        recorder.setProxyPauseHTTPSample("1");
        for (long start : new long[]{1000, 12000}) {
            var sampler = sampler("example.test", "/" + start);
            recorder.deliverSampler(sampler, new TestElement[0], result(sampler, start, start + 100, "200"));
        }
        recorder.setProxyPauseHTTPSample("500");
        recorder.stopProxy();
        assertEquals(1, model.getNodesOfType(TransactionController.class).size(), "Elapsed time and legacy pause values must not split a transaction");
        assertEquals(2, model.getNodesOfType(HTTPSamplerBase.class).size());
    }

    @Test
    void removingIntermediateHostsDoesNotMergeSeparateNamedTransactions() throws Exception {
        var destination = target();
        var recorder = new ProxyControl();
        recorder.setNonGuiTreeModel(model);
        var samples = new java.util.ArrayList<RecordedSampler>();
        String[] names = {"Action", "Other action", "Action"};
        for (int i = 0; i < names.length; i++) {
            var sampler = sampler(i == 1 ? "excluded.test" : "example.test", "/" + i);
            samples.add(new RecordedSampler(sampler, new TestElement[0], destination, names[i], 4,
                    result(sampler, 1000 + i * 100, 1050 + i * 100, "200"), false, null));
        }
        RecordingTransactions.assign(samples);
        var selected = ProxyControl.selectRecording(samples, Set.of("example.test"), Set.of());
        var options = new HarImportOptions();
        options.setIdleTimeSeconds(1000);
        recorder.applyRecording(selected, options, 4, true);
        assertEquals(2, model.getNodesOfType(TransactionController.class).size());
        assertEquals(List.of("Action", "Action"), model.getNodesOfType(TransactionController.class)
                .stream().map(JMeterTreeNode::getName).toList());
    }

    @Test
    void excludedIncompleteCaptureCannotBridgeTransactions() throws Exception {
        var destination = target();
        var first = sampler("example.test", "/unfinished");
        var next = sampler("example.test", "/next-action");
        var incomplete = new RecordedSampler(first, new TestElement[0], destination, "", 4,
                result(first, 1000, 60000, "0"), true, null);
        var completed = new RecordedSampler(next, new TestElement[0], destination, "", 4,
                result(next, 10000, 10100, "200"), false, null);
        RecordingTransactions.assign(List.of(incomplete, completed));
        assertFalse(incomplete.entry.getTransactionId().equals(completed.entry.getTransactionId()));
        assertEquals(1000d, incomplete.entry.getEndMs());
    }

    @Test
    void legacyGroupingUsesRecordedNamesAfterRecorderEdits() throws Exception {
        var recorder = new ProxyControl();
        recorder.setNonGuiTreeModel(model);
        recorder.setTarget(target());
        recorder.setGroupingMode(2);
        recorder.setPrefixHTTPSampleName("First action");
        var first = sampler("example.test", "/first");
        recorder.deliverSampler(first, new TestElement[0], result(first, 1000, 1100, "200"));
        recorder.setPrefixHTTPSampleName("Second action");
        var second = sampler("example.test", "/second");
        recorder.deliverSampler(second, new TestElement[0], result(second, 1200, 1300, "200"));
        recorder.setPrefixHTTPSampleName("Edited after capture");
        recorder.stopProxy();
        var samplers = model.getNodesOfType(HTTPSamplerBase.class);
        assertEquals("First action", ((JMeterTreeNode) samplers.get(0).getParent()).getName());
        assertEquals("Second action", ((JMeterTreeNode) samplers.get(1).getParent()).getName());
    }

    @Test
    void savesRecorderPreferencesWithoutChangingOtherUserProperties(@TempDir Path directory) throws Exception {
        var properties = JMeterUtils.getJMeterProperties();
        var previous = (java.util.Properties) properties.clone();
        try {
            Path file = directory.resolve("user.properties");
            Files.writeString(file, "# Keep my settings\ncustom.property=unchanged\n", StandardCharsets.ISO_8859_1);
            JMeterUtils.setProperty("user.properties", file.toString());
            ProxyControl recorder = new ProxyControl();
            recorder.setStoreRecordedExchanges(false);
            recorder.setHTTPSampleNamingMode(3);
            recorder.setHttpSampleNameFormat("#{counter} / Unicode café ${path}");
            recorder.setProxyPauseHTTPSample("7000");
            recorder.addExcludedPattern("https://ads\\.example/.*");
            HarImportOptions options = new HarImportOptions();
            options.setDelayMode(HarImportOptions.DelayMode.FIXED);
            options.setFixedDelay("${ThinkTime}");
            RecorderSettings.save(recorder, options);
            ProxyControl anotherPlan = new ProxyControl();
            assertTrue(anotherPlan.getStoreRecordedExchanges());
            RecorderSettings.applyDefaults(anotherPlan);
            assertEquals(recorder.getHttpSampleNameFormat(), anotherPlan.getHttpSampleNameFormat());
            assertEquals(3, anotherPlan.getHTTPSampleNamingMode());
            assertFalse(anotherPlan.getStoreRecordedExchanges());
            assertEquals(4, anotherPlan.getGroupingMode());
            assertFalse(anotherPlan.getSamplerFollowRedirects());
            assertTrue(anotherPlan.getUseKeepalive());
            assertEquals(1, anotherPlan.getExcludePatterns().size());
            assertEquals("${ThinkTime}", RecorderSettings.options(anotherPlan).getFixedDelay());
            assertTrue(Files.readString(file, StandardCharsets.ISO_8859_1).startsWith("# Keep my settings\ncustom.property=unchanged\n"));
        } finally {
            properties.clear();
            properties.putAll(previous);
        }
    }
}
