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

package org.apache.jmeter.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.swing.JMenuItem;
import javax.swing.JTree;
import javax.swing.SwingUtilities;

import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.gui.util.SampleResultNodeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class ViewResultsFullVisualizerTest extends JMeterTestCase implements JMeterSerialTest {

    private GuiPackage previousGui;

    @BeforeEach
    public void captureGuiSingleton() {
        previousGui = GuiPackage.getInstance();
    }

    @AfterEach
    public void restoreGuiSingleton() throws Exception {
        var field = GuiPackage.class.getDeclaredField("guiPack");
        field.setAccessible(true);
        field.set(null, previousGui);
    }

    @Test
    public void responseTimeoutStackTraceIsCompactedForDisplay() throws Exception {
        SampleResult result = new SampleResult();
        result.setDataType(SampleResult.TEXT);
        result.setDataEncoding(StandardCharsets.UTF_8.name());
        result.setURL(URI.create("https://whitehouse.gov/").toURL());
        result.setResponseData("""
                java.io.InterruptedIOException: Timeout blocked waiting for input (30 MILLISECONDS)
                \tat org.apache.hc.core5.http.nio.support.classic.ClassicToAsyncResponseConsumer.blockWaiting(ClassicToAsyncResponseConsumer.java:119)
                \tat org.apache.hc.client5.http.impl.compat.ClassicToAsyncAdaptor.doExecute(ClassicToAsyncAdaptor.java:85)
                """, StandardCharsets.UTF_8.name());

        assertEquals("Response timeout after 30 ms waiting for response: "
                + "local IP unavailable -> https://whitehouse.gov:443",
                ViewResultsFullVisualizer.getResponseAsString(result));
    }

    @Test
    public void nonTimeoutStackTraceIsNotCompactedForDisplay() {
        SampleResult result = new SampleResult();
        result.setDataType(SampleResult.TEXT);
        result.setDataEncoding(StandardCharsets.UTF_8.name());
        String stackTrace = """
                java.lang.IllegalStateException: unexpected
                \tat example.Test.run(Test.java:1)
                """;
        result.setResponseData(stackTrace, StandardCharsets.UTF_8.name());

        assertEquals(stackTrace, ViewResultsFullVisualizer.getResponseAsString(result));
    }

    @Test
    public void threadGroupNameIsDerivedFromJMeterThreadName() {
        assertEquals("Checkout Group", ViewResultsFullVisualizer.threadGroupName("Checkout Group 1-7"));
        assertEquals("remote-a-Checkout Group", ViewResultsFullVisualizer.threadGroupName("remote-a-Checkout Group 12-42"));
    }

    @Test
    public void nonJMeterThreadNameIsUsedAsThreadGroupFallback() {
        assertEquals("imported-thread", ViewResultsFullVisualizer.threadGroupName("imported-thread"));
        assertEquals("", ViewResultsFullVisualizer.threadGroupName(""));
    }

    @Test
    public void labelFilterMatchesDirectSampleLabel() {
        SampleResult result = new SampleResult();
        result.setSampleLabel("GET /api/users");

        assertTrue(ViewResultsFullVisualizer.matchesLabel(result, "GET /api/users"));
        assertFalse(ViewResultsFullVisualizer.matchesLabel(result, "GET /api/orders"));
    }

    @Test
    public void labelFilterKeepsParentWhenNestedSampleMatches() {
        SampleResult parent = new SampleResult();
        parent.setSampleLabel("Transaction");
        SampleResult child = new SampleResult();
        child.setSampleLabel("GET /api/users");
        parent.addSubResult(child, false);

        assertTrue(ViewResultsFullVisualizer.sampleOrSubResultMatchesLabel(parent, "GET /api/users"));
        assertFalse(ViewResultsFullVisualizer.sampleOrSubResultMatchesLabel(parent, "GET /api/orders"));
    }

    @Test
    public void resolvesAndKeepsLatestReplayWithoutSourceMetadata() throws Exception {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        DebugSampler sampler = new DebugSampler();
        sampler.setName("GET /api/users");

        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, treeModel);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(threadGroupNode);
        threadGroupNode.add(samplerNode);

        SampleResult firstLoop = replayResult("first");
        SampleResult lastLoop = replayResult("last");
        Map<JMeterTreeNode, SampleResult> replayedSamples = new LinkedHashMap<>();
        ViewResultsFullVisualizer.collectReplayableSamples(firstLoop, replayedSamples);
        ViewResultsFullVisualizer.collectReplayableSamples(lastLoop, replayedSamples);

        assertSame(samplerNode, ViewResultsFullVisualizer.findTestPlanNode(lastLoop));
        JMenuItem jumpTo = ViewResultsFullVisualizer.createJumpToMenuItem(lastLoop);
        assertEquals("Jump to", jumpTo.getText());
        assertTrue(jumpTo.isEnabled());
        assertEquals(1, replayedSamples.size());
        assertSame(lastLoop, replayedSamples.get(samplerNode));

        ReplayRecordingStore.store(replayedSamples, RecordingStorageMode.ALL);

        assertFalse(sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY).isEmpty());
        assertFalse(threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());

        ReplayRecordingStore.store(replayedSamples, RecordingStorageMode.NONE);

        assertTrue(sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY).isEmpty());
        assertTrue(threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(RecordingStorageMode.class)
    public void storesReplayWhenPreviousRecordingIsUnavailable(RecordingStorageMode mode) throws Exception {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, "recordings/manifests/missing.json");
        threadGroup.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, "missing-checksum");
        DebugSampler sampler = new DebugSampler();
        sampler.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, "existing-exchange");
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode groupNode = new JMeterTreeNode(threadGroup, treeModel);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(groupNode);
        groupNode.add(samplerNode);
        SampleResult replay = replayResult("new-response");
        replay.setURL(URI.create("https://example.invalid/application.js").toURL());
        replay.setContentType("application/javascript");

        ReplayRecordingStore.store(Map.of(samplerNode, replay), mode);

        if (mode == RecordingStorageMode.NONE || mode == RecordingStorageMode.OMIT_STATICS) {
            assertEquals("", sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
            assertEquals("", threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
            assertEquals("", threadGroup.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY));
        } else {
            assertEquals("existing-exchange", sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
            var exchange = RecordedHarExchangeResolver.resolveFor(samplerNode, null).exchange().orElseThrow();
            assertEquals(mode == RecordingStorageMode.ALL ? "new-response" : "",
                    exchange.responseBody());
            assertEquals("https://example.invalid/application.js", exchange.requestUrl());
        }
    }

    @ParameterizedTest
    @EnumSource(RecordingStorageMode.class)
    public void preservesSiblingRecordingFromDiskWhenStoringReplay(
            RecordingStorageMode mode, @TempDir Path tempDir) throws Exception {
        var recording = RecordedExchangeStore.storeReplays("", Map.of(), Map.of(
                "replayed", replayResult("old-response"), "sibling", replayResult("sibling-response")));
        Path original = tempDir.resolve("original.jmx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(original))) {
            for (var entry : recording.entries().entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        assertTrue(JmxArchiveEntryStore.findBundle(recording.manifestEntryName(), recording.checksum()).isEmpty());
        ThreadGroup group = new ThreadGroup();
        group.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, recording.manifestEntryName());
        group.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, recording.checksum());
        DebugSampler sampler = new DebugSampler();
        sampler.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, "replayed");
        DebugSampler sibling = new DebugSampler();
        sibling.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, "sibling");
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode groupNode = new JMeterTreeNode(group, treeModel);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        groupNode.add(samplerNode);
        groupNode.add(new JMeterTreeNode(sibling, treeModel));
        ((JMeterTreeNode) treeModel.getRoot()).add(groupNode);
        SampleResult replay = replayResult("new-response");
        replay.setURL(URI.create("https://example.invalid/application.js").toURL());
        replay.setContentType("application/javascript");

        ReplayRecordingStore.store(Map.of(samplerNode, replay), mode, original.toFile());

        assertEquals(recording.manifestEntryName(), group.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        assertEquals("sibling", sibling.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        var tree = new ListedHashTree();
        tree.add(group).add(sampler);
        tree.getTree(group).add(sibling);
        Path saved = tempDir.resolve("saved.jmx");
        SaveService.saveTreeToFile(tree, saved);
        byte[] manifest = SaveService.readArchiveEntry(saved.toFile(), recording.manifestEntryName()).orElseThrow();
        var siblingExchange = RecordedExchangeStore.resolveExchange(manifest, "sibling",
                entry -> SaveService.readArchiveEntry(saved.toFile(), entry)).orElseThrow();
        assertEquals("sibling-response", siblingExchange.path("response").path("content").path("text").asText());
        var replayExchange = RecordedExchangeStore.resolveExchange(manifest, "replayed",
                entry -> SaveService.readArchiveEntry(saved.toFile(), entry));
        if (mode == RecordingStorageMode.NONE || mode == RecordingStorageMode.OMIT_STATICS) {
            assertTrue(replayExchange.isEmpty());
        } else {
            assertEquals(mode == RecordingStorageMode.ALL ? "new-response" : "",
                    replayExchange.orElseThrow().path("response").path("content").path("text").asText());
        }
    }

    @Test
    public void reportNavigatesToTransactionAndSamplerInTestFragment() {
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        ThreadGroup group = new ThreadGroup();
        group.setName("Users");
        JMeterTreeNode groupNode = new JMeterTreeNode(group, treeModel);
        root.add(groupNode);
        ModuleController module = new ModuleController();
        module.setName("Shared module");
        groupNode.add(new JMeterTreeNode(module, treeModel));
        TestFragmentController fragment = new TestFragmentController();
        fragment.setName("Shared fragment");
        fragment.setEnabled(false);
        JMeterTreeNode fragmentNode = new JMeterTreeNode(fragment, treeModel);
        root.add(fragmentNode);
        module.setSelectedNode(fragmentNode);
        TransactionController transaction = new TransactionController();
        transaction.setName("Checkout");
        JMeterTreeNode transactionNode = new JMeterTreeNode(transaction, treeModel);
        fragmentNode.add(transactionNode);
        DebugSampler sampler = new DebugSampler();
        sampler.setName("Request");
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        transactionNode.add(samplerNode);

        var path = new java.util.ArrayList<SampleResult.TestElementPathEntry>();
        for (var element : java.util.List.of(group, module, fragment, transaction, sampler)) {
            path.add(new SampleResult.TestElementPathEntry(element.getClass().getName(), element.getName(), 0));
        }
        for (JMeterTreeNode expected : java.util.List.of(samplerNode, transactionNode)) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(expected.getName());
            result.setSourceTestElementPath(path);
            PerformanceReport.PerformanceReportData row =
                    new PerformanceReport.PerformanceReportData(expected.getName());
            row.addSample(result);

            assertSame(expected, PerformanceReport.findTestPlanNode(row));
            assertSame(expected, SampleResultNodeResolver.findForNavigation(result));
            assertTrue(ViewResultsFullVisualizer.createJumpToMenuItem(result).isEnabled());
            path.remove(path.size() - 1);
        }
        assertNull(PerformanceReport.findTestPlanNode(null));
        assertNull(PerformanceReport.findTestPlanNode(new PerformanceReport.PerformanceReportData("TOTAL")));
    }

    @Test
    public void resolvesAndStoresSamplerExpandedFromTestFragment() throws Exception {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        ModuleController moduleController = new ModuleController();
        moduleController.setName("Shared module");
        TestFragmentController fragment = new TestFragmentController();
        fragment.setName("Shared fragment");
        fragment.setEnabled(false);
        DebugSampler sampler = new DebugSampler();
        sampler.setName("Shared request");

        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, treeModel);
        JMeterTreeNode moduleNode = new JMeterTreeNode(moduleController, treeModel);
        JMeterTreeNode fragmentNode = new JMeterTreeNode(fragment, treeModel);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        root.add(threadGroupNode);
        threadGroupNode.add(moduleNode);
        root.add(fragmentNode);
        fragmentNode.add(samplerNode);
        moduleController.setSelectedNode(fragmentNode);

        SampleResult replay = replayResult("fragment-response");
        replay.setSampleLabel("Shared request");
        replay.setSourceTestElementPath(java.util.List.of(
                new SampleResult.TestElementPathEntry(ThreadGroup.class.getName(), "Thread Group", 0),
                new SampleResult.TestElementPathEntry(ModuleController.class.getName(), "Shared module", 0),
                new SampleResult.TestElementPathEntry(
                        TestFragmentController.class.getName(), "Shared fragment", 0),
                new SampleResult.TestElementPathEntry(DebugSampler.class.getName(), "Shared request", 0)));

        assertSame(samplerNode, ViewResultsFullVisualizer.findTestPlanNode(replay));

        ReplayRecordingStore.store(Map.of(samplerNode, replay), RecordingStorageMode.ALL);

        assertFalse(sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY).isEmpty());
        assertFalse(fragment.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());
        assertTrue(threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());
        assertTrue(RecordedHarExchangeResolver.resolveFor(samplerNode, null).exchange().isPresent());
        assertTrue(RecordedHarExchangeResolver.resolveFor(replay).exchange().isPresent());
    }

    @Test
    public void jumpToFallsBackToNearestResolvableParent() throws Exception {
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        JMeterTreeListener listener = new JMeterTreeListener(treeModel);
        JTree testPlanTree = new JTree(treeModel);
        listener.setJTree(testPlanTree);
        GuiPackage.initInstance(listener, treeModel);
        DebugSampler sampler = new DebugSampler();
        sampler.setName("GET /api/users");
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(samplerNode);

        SampleResult parent = replayResult("parent");
        SampleResult child = new SampleResult();
        child.setSampleLabel("redirect without a test plan element");
        SampleResult grandchild = new SampleResult();
        grandchild.setSampleLabel("embedded resource without a test plan element");
        parent.addSubResult(child, false);
        child.addSubResult(grandchild, false);

        assertSame(samplerNode, SampleResultNodeResolver.findForNavigation(child));
        assertSame(samplerNode, SampleResultNodeResolver.findForNavigation(grandchild));
        assertTrue(ViewResultsFullVisualizer.createJumpToMenuItem(grandchild).isEnabled());
        SwingUtilities.invokeAndWait(() -> ViewResultsFullVisualizer.createJumpToMenuItem(grandchild).doClick());
        assertSame(samplerNode, testPlanTree.getLastSelectedPathComponent());
        Map<JMeterTreeNode, SampleResult> replayed = new LinkedHashMap<>();
        child.setURL(URI.create("https://example.test/redirect").toURL());
        grandchild.setURL(URI.create("https://example.test/image.svg").toURL());
        ViewResultsFullVisualizer.collectReplayableSamples(parent, replayed);
        assertEquals(Map.of(samplerNode, parent), replayed);
        assertNull(ViewResultsFullVisualizer.findTestPlanNode(grandchild),
                "Navigation fallback must not associate a child response with the parent's replay recording");

        DebugSampler childSampler = new DebugSampler();
        childSampler.setName(child.getSampleLabel());
        JMeterTreeNode childNode = new JMeterTreeNode(childSampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(childNode);
        assertSame(childNode, SampleResultNodeResolver.findForNavigation(child));
        assertSame(childNode, SampleResultNodeResolver.findForNavigation(grandchild));

        SampleResult orphan = new SampleResult();
        orphan.setSampleLabel("missing");
        assertNull(SampleResultNodeResolver.findForNavigation(orphan));
        assertFalse(ViewResultsFullVisualizer.createJumpToMenuItem(orphan).isEnabled());
        assertNull(SampleResultNodeResolver.findForNavigation(null));
    }

    @Test
    public void jumpToKeepsFailedResultLinkedAfterRenameAndMove() throws Exception {
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        JMeterTreeListener listener = new JMeterTreeListener(treeModel);
        JTree testPlanTree = new JTree(treeModel);
        listener.setJTree(testPlanTree);
        GuiPackage.initInstance(listener, treeModel);
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        DebugSampler sampler = new DebugSampler();
        sampler.setName("GET /api/users");
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        root.add(samplerNode);
        SampleResult result = replayResult("error");
        result.setSuccessful(false);
        SampleResult child = new SampleResult();
        child.setSampleLabel("redirect");
        result.addSubResult(child, false);

        SampleResultNodeResolver.rememberNavigationTargets(result);
        sampler.setName("Renamed request");
        ThreadGroup group = new ThreadGroup();
        group.setName("Moved group");
        JMeterTreeNode groupNode = new JMeterTreeNode(group, treeModel);
        root.add(groupNode);
        groupNode.add(samplerNode);
        DebugSampler replacement = new DebugSampler();
        replacement.setName(result.getSampleLabel());
        root.add(new JMeterTreeNode(replacement, treeModel));

        assertSame(samplerNode, SampleResultNodeResolver.findForNavigation(result));
        assertSame(samplerNode, SampleResultNodeResolver.findForNavigation(child));
        assertTrue(ViewResultsFullVisualizer.createJumpToMenuItem(result).isEnabled());
        SwingUtilities.invokeAndWait(() -> ViewResultsFullVisualizer.createJumpToMenuItem(result).doClick());
        assertSame(samplerNode, testPlanTree.getLastSelectedPathComponent());

        samplerNode.removeFromParent();
        assertFalse(ViewResultsFullVisualizer.createJumpToMenuItem(result).isEnabled(),
                "Deleted targets must not resolve to a different sampler with the old name");
    }

    @Test
    public void jumpToRetriesResultsThatInitiallyHaveNoTarget() throws Exception {
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        SampleResult result = replayResult("error");
        SampleResultNodeResolver.rememberNavigationTargets(result);
        assertFalse(ViewResultsFullVisualizer.createJumpToMenuItem(result).isEnabled());

        DebugSampler sampler = new DebugSampler();
        sampler.setName(result.getSampleLabel());
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(samplerNode);
        assertSame(samplerNode, SampleResultNodeResolver.findForNavigation(result));
    }

    @Test
    public void jumpToUsesEnabledDuplicateThreadGroupAndSiblingOccurrences() {
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        JMeterTreeNode expected = null;
        for (boolean enabled : new boolean[] {false, true}) {
            ThreadGroup group = new ThreadGroup();
            group.setName("SHP_W01_Buy3Ticket");
            group.setEnabled(enabled);
            JMeterTreeNode groupNode = new JMeterTreeNode(group, treeModel);
            root.add(groupNode);
            for (String name : new String[] {"Earlier transaction", "SHP_W01_13_NaarAfrekenen"}) {
                TransactionController transaction = new TransactionController();
                transaction.setName(name);
                JMeterTreeNode transactionNode = new JMeterTreeNode(transaction, treeModel);
                groupNode.add(transactionNode);
                for (int i = 0; i < 3; i++) {
                    DebugSampler sampler = new DebugSampler();
                    sampler.setName("/api/v1/productorder/verify");
                    sampler.setEnabled(i != 0);
                    JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
                    transactionNode.add(samplerNode);
                    if (enabled && i == 2) {
                        expected = samplerNode;
                    }
                }
            }
        }
        SampleResult result = new SampleResult();
        result.setSampleLabel("/api/v1/productorder/verify");
        result.setThreadName("SHP_W01_Buy3Ticket 1-1");
        result.setSourceTestElementPath(java.util.List.of(
                new SampleResult.TestElementPathEntry(ThreadGroup.class.getName(), "SHP_W01_Buy3Ticket", 0),
                new SampleResult.TestElementPathEntry(
                        TransactionController.class.getName(), "SHP_W01_13_NaarAfrekenen", 0),
                new SampleResult.TestElementPathEntry(DebugSampler.class.getName(), result.getSampleLabel(), 1)));

        assertSame(expected, SampleResultNodeResolver.find(result));
        assertSame(expected, SampleResultNodeResolver.findForNavigation(result));
        assertTrue(ViewResultsFullVisualizer.createJumpToMenuItem(result).isEnabled());
    }

    @Test
    public void refreshDoesNotRetryUnresolvedBufferedResults() throws Exception {
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        AtomicInteger lookups = new AtomicInteger();
        SampleResult unresolved = new SampleResult() {
            @Override
            public List<TestElementPathEntry> getSourceTestElementPath() {
                lookups.incrementAndGet();
                return super.getSourceTestElementPath();
            }
        };
        unresolved.setSampleLabel("unresolved redirect");
        SampleResult parent = new SampleResult();
        parent.setSampleLabel("unresolved parent");
        parent.addSubResult(unresolved, false);
        var refresh = ViewResultsFullVisualizer.class.getDeclaredMethod("updateGui");
        refresh.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                visualizer.add(parent);
                visualizer.add(new SampleResult());
                refresh.invoke(visualizer);
                assertEquals(1, lookups.get());
                for (int i = 0; i < 5; i++) {
                    visualizer.add(new SampleResult());
                    refresh.invoke(visualizer);
                }
                assertEquals(1, lookups.get(), "Refreshes must not retry old unresolved subresults");

                DebugSampler sampler = new DebugSampler();
                sampler.setName(unresolved.getSampleLabel());
                JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
                ((JMeterTreeNode) treeModel.getRoot()).add(samplerNode);
                assertSame(samplerNode, SampleResultNodeResolver.findForNavigation(unresolved));
                assertEquals(2, lookups.get(), "Explicit navigation must still retry an unresolved result");
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    private static SampleResult replayResult(String body) throws Exception {
        SampleResult result = new SampleResult();
        result.setSampleLabel("GET /api/users");
        result.setThreadName("Thread Group 1-1");
        result.setURL(URI.create("https://example.invalid/api/users").toURL());
        result.setResponseData(body, StandardCharsets.UTF_8.name());
        return result;
    }
}
