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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.swing.JCheckBox;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.gui.util.SampleResultNodeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.TransactionRef;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

public class ViewResultsFullVisualizerTest extends JMeterTestCase implements JMeterSerialTest {

    private GuiPackage previousGui;
    private boolean previousValidation;

    @BeforeEach
    public void captureGuiSingleton() {
        previousGui = GuiPackage.getInstance();
        previousValidation = JMeterContextService.isValidationRun();
    }

    @AfterEach
    public void restoreGuiSingleton() throws Exception {
        var field = GuiPackage.class.getDeclaredField("guiPack");
        field.setAccessible(true);
        field.set(null, previousGui);
        JMeterContextService.setValidationRun(previousValidation);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void dynamicNodeNamesKeepNavigationAndReplayTargets(boolean throughModule) throws Exception {
        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        ThreadGroup group = new ThreadGroup();
        group.setName("${ACR} Debug");
        group.setSamplerController(new LoopController());
        JMeterTreeNode groupNode = new JMeterTreeNode(group, treeModel);
        root.add(groupNode);
        var sourcePath = new ArrayList<TestElement>();
        sourcePath.add(group);
        JMeterTreeNode parent = groupNode;
        if (throughModule) {
            ModuleController module = new ModuleController();
            module.setName("${ACR} module");
            groupNode.add(new JMeterTreeNode(module, treeModel));
            TestFragmentController fragment = new TestFragmentController();
            fragment.setName("${ACR} fragment");
            fragment.setEnabled(false);
            parent = new JMeterTreeNode(fragment, treeModel);
            root.add(parent);
            module.setSelectedNode(parent);
            sourcePath.add(module);
            sourcePath.add(fragment);
        }
        TransactionController transaction = new TransactionController();
        transaction.setName("${ACR}_01_Starten");
        JMeterTreeNode transactionNode = new JMeterTreeNode(transaction, treeModel);
        parent.add(transactionNode);
        sourcePath.add(transaction);

        var context = JMeterContextService.getContext();
        JMeterVariables previousVariables = context.getVariables();
        JMeterVariables variables = new JMeterVariables();
        variables.put("ACR", "BemVac");
        variables.put("OTHER", "BemVac");
        context.setVariables(variables);
        try {
            var execution = new ListedHashTree();
            var subtree = execution;
            ValueReplacer replacer = new ValueReplacer();
            TransactionController runtimeTransaction = null;
            for (var original : sourcePath) {
                var runtime = (TestElement) original.clone();
                replacer.replaceValues(runtime);
                runtime.setRunningVersion(true);
                subtree = (ListedHashTree) subtree.add(runtime);
                if (runtime instanceof TransactionController controller) {
                    runtimeTransaction = controller;
                }
            }
            var runtimeSamplers = new ArrayList<DebugSampler>();
            var samplerNodes = new ArrayList<JMeterTreeNode>();
            // Equal runtime labels must not alter occurrences of the original expressions.
            for (String name : List.of("${OTHER}_01_Starten", "${ACR}_01_Starten", "${ACR}_01_Starten")) {
                DebugSampler sampler = new DebugSampler();
                sampler.setName(name);
                JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
                transactionNode.add(samplerNode);
                samplerNodes.add(samplerNode);
                DebugSampler runtime = (DebugSampler) sampler.clone();
                replacer.replaceValues(runtime);
                runtime.setRunningVersion(true);
                subtree.add(runtime);
                runtimeSamplers.add(runtime);
            }
            TestCompiler.initialize();
            TestCompiler compiler = new TestCompiler(execution);
            execution.traverse(compiler);
            Map<JMeterTreeNode, SampleResult> replays = new LinkedHashMap<>();
            for (int i = 0; i < runtimeSamplers.size(); i++) {
                DebugSampler runtime = runtimeSamplers.get(i);
                assertEquals("BemVac_01_Starten", runtime.getName());
                SampleResult result = replayResult("response-" + i);
                result.setSampleLabel(runtime.getName());
                var path = compiler.configureSampler(runtime).getSourceTestElementPath();
                result.setSourceTestElementPath(path);
                assertEquals(samplerNodes.get(i).getName(), path.get(path.size() - 1).name());
                assertEquals(i == 2 ? 1 : 0, path.get(path.size() - 1).occurrence());
                assertSame(samplerNodes.get(i), SampleResultNodeResolver.findForNavigation(result));
                assertTrue(ViewResultsFullVisualizer.createJumpToMenuItem(result).isEnabled());
                ViewResultsFullVisualizer.collectReplayableSamples(result, replays);
            }
            assertEquals(Set.copyOf(samplerNodes), replays.keySet());
            ReplayRecordingStore.store(replays, RecordingStorageMode.ALL);
            for (int i = 0; i < samplerNodes.size(); i++) {
                var exchange = RecordedHarExchangeResolver.resolveFor(samplerNodes.get(i), null).exchange().orElseThrow();
                assertEquals("response-" + i, exchange.responseBody());
            }
            SampleResult transactionResult = new SampleResult();
            transactionResult.setSampleLabel(runtimeTransaction.getName());
            transactionResult.setSourceTestElementPath(
                    compiler.getTransactionControllerPackage(runtimeTransaction).getSourceTestElementPath());
            assertSame(transactionNode, SampleResultNodeResolver.findForNavigation(transactionResult));
        } finally {
            context.setVariables(previousVariables);
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
    public void storesReplayInTheRecordingCarriedByACopiedSampler() throws Exception {
        TestFragmentController fragment = new TestFragmentController();
        fragment.setName("Fragment");
        DebugSampler sampler = new DebugSampler();
        sampler.setName("Copied request");
        sampler.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, "recordings/manifests/original.json");
        sampler.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, "original-checksum");
        sampler.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, "copied-exchange");

        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        JMeterTreeNode fragmentNode = new JMeterTreeNode(fragment, treeModel);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(fragmentNode);
        fragmentNode.add(samplerNode);

        ReplayRecordingStore.store(Map.of(samplerNode, replayResult("replayed-in-fragment")),
                RecordingStorageMode.ALL);

        assertTrue(fragment.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());
        assertNotEquals("recordings/manifests/original.json",
                sampler.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        assertTrue(RecordedHarExchangeResolver.resolveFor(samplerNode, null).responseText()
                .contains("replayed-in-fragment"));
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

    @Test
    public void transactionShowsWhileRunningAndGroupsItsSamples() throws Exception {
        TransactionRef outer = TransactionRef.start("outer", null);
        TransactionRef inner = TransactionRef.start("inner", outer);
        SampleResult outerStarted = transactionSample(outer);
        SampleResult innerStarted = transactionSample(inner);
        SampleResult outerFinished = transactionSample(outer);
        SampleResult innerFinished = transactionSample(inner);
        SampleResult outside = new SampleResult();
        outside.setSampleLabel("outside");

        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                visualizer.addStartedTransaction(new SampleEvent(outerStarted, "tg"));
                visualizer.add(childSample("first", outer));
                visualizer.addStartedTransaction(new SampleEvent(innerStarted, "tg"));
                refresh(visualizer);
                assertEquals("[outer (running)[first, inner (running)]]", describeTree(visualizer));

                visualizer.add(childSample("second", inner));
                visualizer.add(innerFinished);
                refresh(visualizer);
                assertEquals("[outer (running)[first, inner[second]]]", describeTree(visualizer));

                visualizer.add(outerFinished);
                visualizer.add(outside);
                refresh(visualizer);
                assertEquals("[outer[first, inner[second]], outside]", describeTree(visualizer));
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @Test
    public void runningSamplerIsReplacedByItsSampleOrRemoved() throws Exception {
        TransactionRef transaction = TransactionRef.start("transaction", null);
        SampleResult started = childSample("login", transaction);
        SampleResult finished = childSample("login", transaction);
        SampleResult startedWithoutResult = childSample("think", transaction);

        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                visualizer.addStartedTransaction(new SampleEvent(transactionSample(transaction), "tg"));
                visualizer.addStartedSample(new SampleEvent(started, "tg"));
                refresh(visualizer);
                assertEquals("[transaction (running)[login (running)]]", describeTree(visualizer));

                SampleEvent finishedEvent = new SampleEvent(finished, "tg");
                finishedEvent.setStartedSample(started);
                visualizer.add(finishedEvent);
                visualizer.removeStartedSample(new SampleEvent(started, "tg"));
                visualizer.addStartedSample(new SampleEvent(startedWithoutResult, "tg"));
                refresh(visualizer);
                assertEquals("[transaction (running)[login, think (running)]]", describeTree(visualizer));

                visualizer.removeStartedSample(new SampleEvent(startedWithoutResult, "tg"));
                visualizer.add(transactionSample(transaction));
                refresh(visualizer);
                assertEquals("[transaction[login]]", describeTree(visualizer));
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @Test
    public void samplesOfAnUnseenTransactionCreateItsNode() throws Exception {
        TransactionRef outer = TransactionRef.start("outer", null);
        TransactionRef inner = TransactionRef.start("inner", outer);

        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                visualizer.add(childSample("first", inner));
                refresh(visualizer);
                assertEquals("[outer[inner[first]]]", describeTree(visualizer));

                visualizer.add(transactionSample(inner));
                visualizer.add(transactionSample(outer));
                refresh(visualizer);
                assertEquals("[outer[inner[first]]]", describeTree(visualizer));
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @Test
    public void filteredTransactionCompletionDoesNotLeaveRunningAncestor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            ResultCollector collector = new ResultCollector();
            collector.setListener(visualizer);
            collector.setSuccessOnlyLogging(true);
            TransactionRef transaction = TransactionRef.start("mixed", null);
            try {
                collector.sampleOccurred(new SampleEvent(childSample("success", transaction), "tg"));
                SampleResult failed = transactionSample(transaction);
                failed.setSuccessful(false);
                collector.sampleOccurred(new SampleEvent(failed, "tg"));
                refresh(visualizer);
                assertEquals("[mixed[success]]", describeTree(visualizer));
                var tableField = ViewResultsFullVisualizer.class.getDeclaredField("resultTableModel");
                tableField.setAccessible(true);
                ResultTableModel table = (ResultTableModel) tableField.get(visualizer);
                assertNull(table.getValueAt(0, ResultTableModel.TIME),
                        "An inferred ancestor has no known final measurements");
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void liveTreeFollowsSamplesAndPreservesOnlyManualExpansion(boolean manual) throws Exception {
        JMeterContextService.setValidationRun(true);
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                var treeField = ViewResultsFullVisualizer.class.getDeclaredField("jTree");
                treeField.setAccessible(true);
                JTree tree = (JTree) treeField.get(visualizer);
                TransactionRef first = TransactionRef.start("first", null);
                visualizer.addStartedTransaction(new SampleEvent(transactionSample(first), "tg"));
                SampleResult started = childSample("request", first);
                visualizer.addStartedSample(new SampleEvent(started, "tg"));
                refresh(visualizer);
                TreePath firstPath = new TreePath(((DefaultMutableTreeNode)
                        ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0)).getPath());
                assertTrue(tree.isExpanded(firstPath));
                assertSame(started, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());
                if (manual) {
                    tree.collapsePath(firstPath);
                    tree.expandPath(firstPath);
                }

                SampleResult finished = childSample("request", first);
                SampleEvent event = new SampleEvent(finished, "tg");
                event.setStartedSample(started);
                visualizer.add(event);
                visualizer.add(transactionSample(first));
                refresh(visualizer);
                assertSame(finished, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());

                TransactionRef next = TransactionRef.start("next", null);
                visualizer.addStartedTransaction(new SampleEvent(transactionSample(next), "tg"));
                SampleResult latest = childSample("latest", next);
                visualizer.addStartedSample(new SampleEvent(latest, "tg"));
                refresh(visualizer);
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                assertEquals(manual, tree.isExpanded(new TreePath(
                        ((DefaultMutableTreeNode) root.getChildAt(0)).getPath())));
                assertTrue(tree.isExpanded(new TreePath(
                        ((DefaultMutableTreeNode) root.getChildAt(1)).getPath())));
                assertSame(latest, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());
                var resultField = ViewResultsFullVisualizer.class.getDeclaredField("resultsObject");
                resultField.setAccessible(true);
                assertSame(latest, resultField.get(visualizer), "The details pane must follow the selection");
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @Test
    public void fastLiveSampleIsSelectedButImportedResultsDoNotMoveSelection() throws Exception {
        JMeterContextService.setValidationRun(true);
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                var treeField = ViewResultsFullVisualizer.class.getDeclaredField("jTree");
                treeField.setAccessible(true);
                JTree tree = (JTree) treeField.get(visualizer);
                SampleResult started = new SampleResult();
                SampleResult finished = new SampleResult();
                visualizer.addStartedSample(new SampleEvent(started, "tg"));
                SampleEvent event = new SampleEvent(finished, "tg");
                event.setStartedSample(started);
                visualizer.add(event);
                JMeterContextService.setValidationRun(false);
                refresh(visualizer);
                assertSame(finished, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());
                visualizer.add(new SampleResult());
                refresh(visualizer);
                assertSame(finished, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());
                visualizer.clearData();
                visualizer.add(new SampleResult());
                refresh(visualizer);
                assertNull(tree.getSelectionPath());
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @Test
    public void normalTestRunDoesNotFollowOrExpandResults() throws Exception {
        JMeterContextService.setValidationRun(false);
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                JTree tree = (JTree) visualizerField(visualizer, "jTree");
                TransactionRef transaction = TransactionRef.start("transaction", null);
                visualizer.addStartedTransaction(new SampleEvent(transactionSample(transaction), "tg"));
                visualizer.addStartedSample(new SampleEvent(childSample("request", transaction), "tg"));
                refresh(visualizer);
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                TreePath path = new TreePath(((DefaultMutableTreeNode) root.getChildAt(0)).getPath());
                assertFalse(tree.isExpanded(path));
                assertNull(tree.getSelectionPath());
                tree.expandPath(path);
                tree.setSelectionPath(path);
                visualizer.addStartedTransaction(new SampleEvent(
                        transactionSample(TransactionRef.start("next", null)), "tg"));
                refresh(visualizer);
                path = new TreePath(((DefaultMutableTreeNode) root.getChildAt(0)).getPath());
                assertTrue(tree.isExpanded(path));
                assertEquals(path, tree.getSelectionPath());
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @Test
    public void viewSettingsSurviveSavingAndLoadingAndResetForOldPlans() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            ViewResultsFullVisualizer restored = new ViewResultsFullVisualizer();
            try {
                ((JTabbedPane) visualizerField(visualizer, "resultListTabs")).setSelectedIndex(1);
                ((JCheckBox) visualizerField(visualizer, "calculateResponseDiffCB")).doClick();
                ((JCheckBox) visualizerField(visualizer, "autoScrollCB")).setSelected(true);
                ResultTableColumnSettings columns = (ResultTableColumnSettings)
                        visualizerField(visualizer, "resultTableColumnSettings");
                ResultCollector choices = new ResultCollector();
                choices.setProperty("ViewResultsFullVisualizer.column.http_response_code", false);
                choices.setProperty("ViewResultsFullVisualizer.column.view_results_table_compression", true);
                columns.configure(choices);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                SaveService.saveElement(visualizer.createTestElement(), output);
                restored.configure((ResultCollector) SaveService.loadElement(new ByteArrayInputStream(output.toByteArray())));
                assertEquals(1, ((JTabbedPane) visualizerField(restored, "resultListTabs")).getSelectedIndex());
                assertTrue(((JCheckBox) visualizerField(restored, "calculateResponseDiffCB")).isSelected());
                assertTrue(((ResultTableModel) visualizerField(restored, "resultTableModel")).isResponseBodyDiffEnabled());
                assertTrue(((JCheckBox) visualizerField(restored, "autoScrollCB")).isSelected());
                JTable table = (JTable) visualizerField(restored, "resultTable");
                assertEquals(-1, table.convertColumnIndexToView(ResultTableModel.HTTP_CODE));
                assertTrue(table.convertColumnIndexToView(ResultTableModel.COMPRESSION) >= 0);

                restored.configure(new ResultCollector());
                assertDefaultViewSettings(restored);
                restored.configure((ResultCollector) SaveService.loadElement(new ByteArrayInputStream(output.toByteArray())));
                restored.clearGui();
                assertDefaultViewSettings(restored);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
                restored.clearData();
            }
        });
    }

    @Test
    public void defaultColumnSettingsDoNotDirtyExistingPlans() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                ResultCollector oldPlan = (ResultCollector) visualizer.createTestElement();
                for (String column : ResultTableModel.COLUMNS) {
                    oldPlan.removeProperty("ViewResultsFullVisualizer.column." + (column.isEmpty() ? "status" : column));
                }
                ResultCollector unchanged = (ResultCollector) oldPlan.clone();
                visualizer.configure(oldPlan);
                visualizer.modifyTestElement(oldPlan);
                assertEquals(unchanged, oldPlan, "Opening a listener must not mark an old plan dirty");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                SaveService.saveElement(oldPlan, output);
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("ViewResultsFullVisualizer.column."));

                ResultTableColumnSettings columns = (ResultTableColumnSettings)
                        visualizerField(visualizer, "resultTableColumnSettings");
                ResultCollector choices = new ResultCollector();
                choices.setProperty("ViewResultsFullVisualizer.column.http_response_code", false);
                choices.setProperty("ViewResultsFullVisualizer.column.view_results_table_compression", true);
                columns.configure(choices);
                visualizer.modifyTestElement(oldPlan);
                assertFalse(oldPlan.getPropertyAsBoolean("ViewResultsFullVisualizer.column.http_response_code", true));
                assertTrue(oldPlan.getPropertyAsBoolean("ViewResultsFullVisualizer.column.view_results_table_compression"));
                columns.configure(new ResultCollector());
                visualizer.modifyTestElement(oldPlan);
                assertEquals(unchanged, oldPlan, "Restoring defaults must remove previously saved overrides");
            } catch (Exception ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void validationFollowsOwningRequestAndPreservesNestedSelectionAfterwards(boolean inTransaction) throws Exception {
        JMeterContextService.setValidationRun(true);
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                TransactionRef transaction = inTransaction ? TransactionRef.start("transaction", null) : null;
                if (transaction != null) {
                    visualizer.addStartedTransaction(new SampleEvent(transactionSample(transaction), "tg"));
                }
                SampleResult started = childSample("request", transaction);
                visualizer.addStartedSample(new SampleEvent(started, "tg"));
                SampleResult finished = childSample("request", transaction);
                SampleResult redirect = new SampleResult();
                SampleResult resource = new SampleResult();
                redirect.addSubResult(resource, false);
                finished.addSubResult(redirect, false);
                SampleEvent event = new SampleEvent(finished, "tg");
                event.setStartedSample(started);
                visualizer.add(event);
                if (transaction != null) {
                    visualizer.add(transactionSample(transaction));
                }
                refresh(visualizer);
                JTree tree = (JTree) visualizerField(visualizer, "jTree");
                TreePath requestPath = tree.getSelectionPath();
                assertSame(finished, ((DefaultMutableTreeNode) requestPath.getLastPathComponent()).getUserObject());
                assertSame(finished, visualizerField(visualizer, "resultsObject"));
                assertFalse(tree.isExpanded(requestPath), "Automatic following must not expand protocol sub-results");

                DefaultMutableTreeNode requestNode = (DefaultMutableTreeNode) requestPath.getLastPathComponent();
                DefaultMutableTreeNode resourceNode = (DefaultMutableTreeNode) requestNode.getChildAt(0).getChildAt(0);
                tree.setSelectionPath(new TreePath(resourceNode.getPath()));
                visualizer.add(new SampleResult());
                refresh(visualizer);
                assertSame(resource, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    public void selectedPendingResultRefreshesDetailsOnCompletion(
            boolean tableMode, boolean transaction, boolean sameObject) throws Exception {
        JMeterContextService.setValidationRun(false);
        SwingUtilities.invokeAndWait(() -> {
            ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();
            try {
                TransactionRef ref = TransactionRef.start("transaction", null);
                SampleResult started = transaction ? transactionSample(ref) : childSample("request", null);
                started.setDataType(SampleResult.TEXT);
                SampleResult other = new SampleResult();
                other.setResponseCode("100");
                visualizer.add(other);
                if (transaction) {
                    visualizer.addStartedTransaction(new SampleEvent(started, "tg"));
                } else {
                    visualizer.addStartedSample(new SampleEvent(started, "tg"));
                }
                refresh(visualizer);
                JTree tree = (JTree) visualizerField(visualizer, "jTree");
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                // Leave a different selection in the hidden tree when testing table mode.
                tree.setSelectionPath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(tableMode ? 0 : 1)).getPath()));
                JTable table = (JTable) visualizerField(visualizer, "resultTable");
                if (tableMode) {
                    ((JTabbedPane) visualizerField(visualizer, "resultListTabs")).setSelectedIndex(1);
                    table.getRowSorter().toggleSortOrder(ResultTableModel.HTTP_CODE);
                    int viewRow = table.convertRowIndexToView(1);
                    table.setRowSelectionInterval(viewRow, viewRow);
                }
                JTabbedPane details = (JTabbedPane) visualizerField(visualizer, "rightSide");
                String responseTab = JMeterUtils.getResString("view_results_tab_response");
                details.setSelectedIndex(details.indexOfTab(responseTab));
                SamplerResultTab renderer = (SamplerResultTab) visualizerField(visualizer, "resultsRender");
                assertSame(started, visualizerField(visualizer, "resultsObject"));
                assertFalse(renderer.responseDataText().contains("finished response"));

                SampleResult finished = sameObject ? started
                        : transaction ? transactionSample(ref) : childSample("request", null);
                finished.setDataType(SampleResult.TEXT);
                finished.setResponseCode("201");
                finished.setResponseHeaders("HTTP/1.1 201 Created\nX-Completed: yes\n");
                finished.setResponseData("finished response", StandardCharsets.UTF_8.name());
                if (transaction) {
                    visualizer.add(finished);
                } else {
                    SampleEvent event = new SampleEvent(finished, "tg");
                    event.setStartedSample(started);
                    visualizer.add(event);
                }
                refresh(visualizer);
                assertSame(finished, visualizerField(visualizer, "resultsObject"));
                assertTrue(renderer.responseDataText().contains("finished response"));
                assertTrue(renderer.responseDataText().contains("X-Completed: yes"));
                assertEquals(responseTab, details.getTitleAt(details.getSelectedIndex()));
                if (tableMode) {
                    ResultTableModel model = (ResultTableModel) table.getModel();
                    assertSame(finished, model.sampleAt(table.convertRowIndexToModel(table.getSelectedRow())));
                    assertEquals(1, table.getSelectedRow(), "Selection follows the result after its sort position changes");
                } else {
                    assertSame(finished, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());
                }
                visualizer.add(new SampleResult());
                refresh(visualizer);
                assertSame(finished, visualizerField(visualizer, "resultsObject"));
                assertTrue(renderer.responseDataText().contains("finished response"));
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                visualizer.clearData();
            }
        });
    }

    private static void assertDefaultViewSettings(ViewResultsFullVisualizer visualizer) throws ReflectiveOperationException {
        assertEquals(0, ((JTabbedPane) visualizerField(visualizer, "resultListTabs")).getSelectedIndex());
        assertFalse(((JCheckBox) visualizerField(visualizer, "calculateResponseDiffCB")).isSelected());
        assertFalse(((ResultTableModel) visualizerField(visualizer, "resultTableModel")).isResponseBodyDiffEnabled());
        assertFalse(((JCheckBox) visualizerField(visualizer, "autoScrollCB")).isSelected());
        JTable table = (JTable) visualizerField(visualizer, "resultTable");
        assertTrue(table.convertColumnIndexToView(ResultTableModel.HTTP_CODE) >= 0);
        assertEquals(-1, table.convertColumnIndexToView(ResultTableModel.COMPRESSION));
    }

    private static Object visualizerField(ViewResultsFullVisualizer visualizer, String name)
            throws ReflectiveOperationException {
        var field = ViewResultsFullVisualizer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(visualizer);
    }

    private static void refresh(ViewResultsFullVisualizer visualizer) throws ReflectiveOperationException {
        var refresh = ViewResultsFullVisualizer.class.getDeclaredMethod("updateGui");
        refresh.setAccessible(true);
        refresh.invoke(visualizer);
    }

    @SuppressWarnings("unchecked")
    private static String describeTree(ViewResultsFullVisualizer visualizer) throws ReflectiveOperationException {
        var rootField = ViewResultsFullVisualizer.class.getDeclaredField("root");
        rootField.setAccessible(true);
        var runningField = ViewResultsFullVisualizer.class.getDeclaredField("runningResults");
        runningField.setAccessible(true);
        return describeChildren((DefaultMutableTreeNode) rootField.get(visualizer),
                (Set<SampleResult>) runningField.get(visualizer));
    }

    private static String describeChildren(DefaultMutableTreeNode node, Set<SampleResult> running) {
        List<String> children = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            SampleResult result = (SampleResult) child.getUserObject();
            String description = result.getSampleLabel() + (running.contains(result) ? " (running)" : "");
            if (child.getChildCount() > 0) {
                description += describeChildren(child, running);
            }
            children.add(description);
        }
        return children.toString();
    }

    private static SampleResult transactionSample(TransactionRef transaction) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(transaction.getName());
        result.setTransaction(transaction);
        result.setSuccessful(true);
        return result;
    }

    private static SampleResult childSample(String label, TransactionRef parent) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.setParentTransaction(parent);
        result.setSuccessful(true);
        return result;
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
