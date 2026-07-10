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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

public class ViewResultsFullVisualizerTest {

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
        assertEquals(1, replayedSamples.size());
        assertSame(lastLoop, replayedSamples.get(samplerNode));

        ReplayRecordingStore.store(replayedSamples, RecordingStorageMode.ALL);

        assertFalse(sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY).isEmpty());
        assertFalse(threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());

        ReplayRecordingStore.store(replayedSamples, RecordingStorageMode.NONE);

        assertTrue(sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY).isEmpty());
        assertTrue(threadGroup.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());
    }

    @Test
    public void resolvesAndStoresSamplerExpandedFromTestFragment() throws Exception {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        ModuleController moduleController = new ModuleController();
        moduleController.setName("Shared module");
        TestFragmentController fragment = new TestFragmentController();
        fragment.setName("Shared fragment");
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

    private static SampleResult replayResult(String body) throws Exception {
        SampleResult result = new SampleResult();
        result.setSampleLabel("GET /api/users");
        result.setThreadName("Thread Group 1-1");
        result.setURL(URI.create("https://example.invalid/api/users").toURL());
        result.setResponseData(body, StandardCharsets.UTF_8.name());
        return result;
    }
}
