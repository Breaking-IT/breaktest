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

package org.apache.jmeter.protocol.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

class SseEditorTest extends JMeterTestCase {
    @Test
    void streamAndMatchSettingsSurviveSaveAndLoad(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var request = new SseSampler();
        request.setSseSessionName("notifications");
        request.setSseExistingSessionAction(SseSampler.REUSE);
        request.setProperty(HTTPSamplerProxy.SSE_NAME_MODE, SseSampler.FIXED_NAME);
        request.setProperty(HTTPSamplerProxy.SSE_SAMPLE_NAME, "Notifications");
        request.setProperty(HTTPSamplerProxy.SSE_COUNT, false);
        var match = new SseMatchController();
        match.setEventName("ready");
        match.setMatchMode(SseMatchController.REGEX);
        match.setMatchValue("page (.*)");
        match.setSaveMessageVariable("data");
        var tree = new org.apache.jorphan.collections.ListedHashTree();
        var close = new SseCloseSampler();
        for (var element : java.util.List.of(request, match, close)) {
            element.setProperty(org.apache.jmeter.testelement.TestElement.TEST_CLASS, element.getClass().getName());
            element.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS,
                    element instanceof HTTPSamplerProxy ? SseSamplerGui.class.getName()
                            : org.apache.jmeter.testbeans.gui.TestBeanGUI.class.getName());
        }
        tree.add(request).add(match).add(close);
        var output = new java.io.ByteArrayOutputStream();
        org.apache.jmeter.save.SaveService.saveTree(tree, output);
        var file = directory.resolve("sse.jmx");
        java.nio.file.Files.write(file, output.toByteArray());
        var loaded = org.apache.jmeter.save.SaveService.loadTree(file.toFile());
        var loadedRequest = (SseSampler) loaded.getArray()[0];
        var loadedMatch = (SseMatchController) loaded.getTree(loadedRequest).getArray()[0];
        assertTrue(loadedRequest.isSseEnabled());
        assertEquals("notifications", loadedRequest.getSseSessionName());
        assertEquals(SseSampler.REUSE, loadedRequest.getSseExistingSessionAction());
        assertFalse(loadedRequest.getSseCountIncoming());
        assertEquals("Notifications", loadedRequest.getSseIncomingSampleName("ready"));
        assertEquals("ready", loadedMatch.getEventName());
        assertEquals("page (.*)", loadedMatch.getMatchValue());
        assertEquals("data", loadedMatch.getSaveMessageVariable());
    }

    @Test
    void sseEditorPreservesHttpAndStreamSettingsAndClearsBetweenSamplers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SseSamplerGui editor = new SseSamplerGui();
            SseSampler original = new SseSampler();
            original.setDomain("events.example.test");
            original.setPath("/events");
            original.setMethod("POST");
            original.setHttpProtocol("HTTP/2");
            original.addArgument("token", "abc");
            original.setSseSessionName("notifications");
            original.setSseExistingSessionAction(SseSampler.FAIL);
            original.setProperty(HTTPSamplerProxy.SSE_SAMPLE_NAME, "Notifications / ");
            original.setProperty(HTTPSamplerProxy.SSE_COUNT, false);
            original.setProperty(HTTPSamplerProxy.SSE_MAX, "2048");
            editor.configure(original);
            SseSampler saved = (SseSampler) editor.makeTestElement();
            editor.modifyTestElement(saved);
            assertTrue(saved.isSseEnabled());
            assertEquals("events.example.test", saved.getDomain());
            assertEquals("/events", saved.getPath());
            assertEquals("POST", saved.getMethod());
            assertEquals("HTTP/2", saved.getHttpProtocol());
            assertEquals("abc", saved.getArguments().getArgument(0).getValue());
            assertEquals("notifications", saved.getSseSessionName());
            assertFalse(saved.getSseCountIncoming());
            assertEquals(SseSampler.FAIL, saved.getSseExistingSessionAction());
            assertEquals("Notifications / ready", saved.getSseIncomingSampleName("ready"));
            assertEquals(2048, saved.getSseMaxEventCharacters());
            assertTrue(saved.acceptsChildController(new SseMatchController()));
            assertFalse(saved.acceptsChildController(new org.apache.jmeter.control.LoopController()));
            editor.clearGui();
            editor.modifyTestElement(saved);
            assertTrue(saved.isSseEnabled());
            assertEquals("sse", saved.getSseSessionName());
            assertEquals(SseSampler.RECONNECT, saved.getSseExistingSessionAction());
            assertTrue(saved.getSseCountIncoming());
            assertEquals(saved.getName() + " / message", saved.getSseIncomingSampleName("message"));
        });
    }
    @Test
    void httpAndSseHaveSeparateEditorsAndMenuEntries() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var http = new HttpTestSampleGui();
            var sse = new SseSamplerGui();
            assertEquals("HTTP Request", http.getStaticLabel());
            assertEquals("SSE Request", sse.getStaticLabel());
            assertFalse(tabs(http).stream().anyMatch(title -> title.contains("SSE") || title.contains("Events")));
            assertTrue(tabs(sse).contains("Recorded Events"));
            assertTrue(tabs(sse).contains("SSE"));
            assertFalse(((HTTPSamplerProxy) http.makeTestElement()).acceptsChildController(new SseMatchController()));
            assertTrue(((SseSampler) sse.makeTestElement()).acceptsChildController(new SseMatchController()));
            assertTrue(sse.getMenuCategories().contains(org.apache.jmeter.gui.util.MenuFactory.SAMPLERS));
            var samplers = org.apache.jmeter.gui.util.MenuFactory.makeMenu(
                    org.apache.jmeter.gui.util.MenuFactory.SAMPLERS, org.apache.jmeter.gui.action.ActionNames.ADD);
            assertTrue(java.util.stream.IntStream.range(0, samplers.getItemCount())
                    .mapToObj(samplers::getItem).filter(java.util.Objects::nonNull)
                    .anyMatch(item -> SseSamplerGui.class.getName().equals(item.getName())),
                    "SSE Request must be discoverable in the Add > Sampler menu");
        });
    }

    private static java.util.List<String> tabs(java.awt.Container container) {
        java.util.List<String> titles = new java.util.ArrayList<>();
        if (container instanceof javax.swing.JTabbedPane pane) {
            for (int i = 0; i < pane.getTabCount(); i++) {
                titles.add(pane.getTitleAt(i));
            }
        }
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof java.awt.Container child) {
                titles.addAll(tabs(child));
            }
        }
        return titles;
    }

}
