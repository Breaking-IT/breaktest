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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.EditorMatchHighlighter;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.config.gui.UrlConfigGui;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.RecordedValueReplacer;
import org.junit.jupiter.api.Test;

class CorrelationReviewStepTest extends JMeterTestCase {
    private static final String TOKEN = "recorded-token";

    private static HTTPSamplerProxy sampler() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setDomain("example.test");
        sampler.setProtocol("https");
        sampler.setMethod("POST");
        sampler.setPath("/send");
        return sampler;
    }

    private static List<CorrelationReviewStep> steps(org.apache.jmeter.testelement.TestElement sampler, String token) {
        var source = FindPredefinedCorrelationsAction.toHarEntry(sampler(), "", "token=" + token + ";", 0);
        var target = FindPredefinedCorrelationsAction.toHarEntry(sampler, "", "", 1);
        var rule = new HarPredefinedCorrelation.Rule("test", "test", "Test", "token",
                HarPredefinedCorrelation.ExtractorType.REGEX, HarPredefinedCorrelation.ResponseField.BODY,
                "token=([^;]+);", "$1$", "", false, false, false);
        var correlations = HarPredefinedCorrelation.find(List.of(source, target), List.of(rule));
        assertEquals(1, correlations.size());
        return CorrelationReviewStep.create(correlations, Map.of(1, new JMeterTreeNode(sampler, null)));
    }

    @Test
    void acceptOnlyOneOccurrenceAndKeepRejectedValueAfterOffsetsMove() {
        var sampler = sampler();
        sampler.addArgument("first", TOKEN + ":" + TOKEN);
        sampler.addArgument("second", TOKEN);
        var steps = steps(sampler, TOKEN);
        assertEquals(3, steps.size());
        steps.get(1).decision = CorrelationReviewStep.State.REJECTED;
        assertTrue(steps.get(0).accept(steps));
        assertEquals("${token}:" + TOKEN, sampler.getArguments().getArgument(0).getValue());
        assertEquals(TOKEN, sampler.getArguments().getArgument(1).getValue());
        assertTrue(steps.get(1).current());
        assertEquals(9, steps.get(1).start);
        assertFalse(steps.get(1).accept(steps));
        assertTrue(steps.get(2).accept(steps));
        assertFalse(steps.get(0).accept(steps));
    }

    @Test
    void acceptsRemainingOccurrencesIncludingSkippedEarlierStepsWithoutChangingRejectedOnes() {
        var sampler = sampler();
        sampler.addArgument("first", TOKEN + ":" + TOKEN + ":" + TOKEN);
        sampler.addArgument("second", TOKEN);
        var steps = steps(sampler, TOKEN);
        // Review the middle occurrence first, leaving an earlier match undecided.
        assertTrue(steps.get(1).accept(steps));
        steps.get(2).decision = CorrelationReviewStep.State.REJECTED;
        var remaining = CorrelationReviewStep.pending(steps);
        assertEquals(List.of(steps.get(0), steps.get(3)), remaining);
        remaining.forEach(step -> assertTrue(step.accept(steps)));
        assertEquals("${token}:${token}:" + TOKEN, sampler.getArguments().getArgument(0).getValue());
        assertEquals("${token}", sampler.getArguments().getArgument(1).getValue());
        assertEquals(CorrelationReviewStep.State.REJECTED, steps.get(2).decision);
        assertTrue(CorrelationReviewStep.pending(steps).isEmpty());
    }

    @Test
    void acceptingRemainingMatchesDoesNotOverwriteStaleFields() {
        var sampler = sampler();
        sampler.addArgument("first", TOKEN);
        sampler.addArgument("second", TOKEN);
        var steps = steps(sampler, TOKEN);
        sampler.getArguments().getArgument(0).setValue("edited after scan");
        var remaining = CorrelationReviewStep.pending(steps);
        assertFalse(remaining.get(0).accept(steps));
        assertTrue(remaining.get(1).accept(steps));
        assertEquals("edited after scan", sampler.getArguments().getArgument(0).getValue());
        assertEquals("${token}", sampler.getArguments().getArgument(1).getValue());
    }

    @Test
    void resolvesCurrentArgumentObjectsAndRefusesExternalEdits() {
        var sampler = sampler();
        sampler.addArgument("first", TOKEN);
        var steps = steps(sampler, TOKEN);
        sampler.setArguments((org.apache.jmeter.config.Arguments) sampler.getArguments().clone());
        assertTrue(steps.get(0).accept(steps));
        assertEquals("${token}", sampler.getArguments().getArgument(0).getValue());
        var other = sampler();
        other.addArgument("first", TOKEN);
        var stale = steps(other, TOKEN);
        other.getArguments().getArgument(0).setValue("manually edited");
        assertFalse(stale.get(0).accept(stale));
        assertEquals("manually edited", other.getArguments().getArgument(0).getValue());
    }

    @Test
    void findsNamesHeadersPathsAndBodyOccurrences() {
        var sampler = sampler();
        sampler.setPath("/" + TOKEN);
        sampler.addArgument(TOKEN, "fixed");
        sampler.setNativeHeaders(List.of(new Header(TOKEN, TOKEN)));
        var steps = steps(sampler, TOKEN);
        assertEquals(List.of("Path", "Parameter name", "Header name", "Header value"),
                steps.stream().map(step -> step.field.name()).toList());
        steps.forEach(step -> assertTrue(step.accept(steps)));
        assertEquals("${token}", sampler.getNativeHeaderList().get(0).getName());
        var body = sampler();
        body.setPostBodyRaw(true);
        body.addArgument("", "first\r\n" + TOKEN + " " + TOKEN);
        var bodySteps = steps(body, TOKEN);
        assertEquals(2, bodySteps.size());
        assertEquals("Body", bodySteps.get(0).field.name());
        assertTrue(bodySteps.get(1).accept(bodySteps));
        assertEquals("first\r\n" + TOKEN + " ${token}", body.getArguments().getArgument(0).getValue());
    }

    @Test
    void discoversMatchesThatOccurOnlyInNames() {
        var sampler = sampler();
        sampler.addArgument(TOKEN, "fixed");
        sampler.setNativeHeaders(List.of(new Header("X-" + TOKEN, "fixed")));
        var steps = steps(sampler, TOKEN);
        assertEquals(List.of("Parameter name", "Header name"),
                steps.stream().map(step -> step.field.name()).toList());
        steps.forEach(step -> assertTrue(step.accept(steps)));
        assertEquals("${token}", sampler.getArguments().getArgument(0).getName());
        assertEquals("X-${token}", sampler.getNativeHeaderList().get(0).getName());
    }

    @Test
    void protectsVariablesAndPreservesEncodedReferences() {
        var matches = RecordedValueReplacer.matches("${outer(" + TOKEN + ")}" + TOKEN, TOKEN, "${token}", false);
        assertEquals(1, matches.size());
        var sampler = sampler();
        sampler.setPath("/token%2bvalue/token+value");
        var steps = steps(sampler, "token+value");
        assertEquals(2, steps.size());
        assertTrue(steps.get(0).accept(steps));
        assertTrue(steps.get(1).accept(steps));
        assertEquals("/${__urlencode(${token})}/${token}", sampler.getPath());
    }

    @Test
    void usesTheDecodedExpressionForTheMatchingHeaderOnly() {
        var sampler = sampler();
        sampler.setNativeHeaders(List.of(new Header("X-Raw", "captured%2Btoken"),
                new Header("X-Decoded", "captured+token")));
        var steps = steps(sampler, "captured%2Btoken");
        assertEquals(2, steps.size());
        steps.forEach(step -> assertTrue(step.accept(steps)));
        assertEquals("${token}", sampler.getNativeHeaderList().get(0).getValue());
        assertEquals("${__urldecode(${token})}", sampler.getNativeHeaderList().get(1).getValue());
    }

    @Test
    void reviewsWebSocketUrlAndHeadersIndividually() {
        var socket = new org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler();
        socket.setUrl("wss://example.test/" + TOKEN);
        socket.setHeaders(List.of(new Header("X-Token", TOKEN)));
        var steps = steps(socket, TOKEN);
        assertEquals(2, steps.size());
        assertTrue(steps.get(0).accept(steps));
        assertEquals("wss://example.test/${token}", socket.getUrl());
        assertEquals(TOKEN, socket.getHeaders().get(0).getValue());
        assertTrue(steps.get(1).accept(steps));
        assertEquals("${token}", socket.getHeaders().get(0).getValue());
    }

    @Test
    void revealsWebSocketUrlAndHeaderInTheirTabs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectCustomizer();
            Map<String, Object> properties = new java.util.HashMap<>();
            properties.put("url", "wss://example.test/" + TOKEN);
            properties.put("headers", List.of(new Header("X-Token", TOKEN)));
            editor.setObject(properties);
            var clear = editor.highlightReviewField("Header value", 0, TOKEN, 0, TOKEN.length());
            assertNotNull(clear);
            clear.run();
            String url = "wss://example.test/" + TOKEN;
            clear = editor.highlightReviewField("URL", 0, url, url.indexOf(TOKEN), url.length());
            assertNotNull(clear);
            clear.run();
        });
    }

    @Test
    void webSocketUrlHighlightUsesPropertyIdentityWhenValuesAreIdentical() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            class Editor extends org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectCustomizer {
                java.awt.Component property(String name) { return propertyEditorComponent(name); }
            }
            var editor = new Editor();
            Map<String, Object> properties = new java.util.HashMap<>();
            properties.put("url", TOKEN);
            properties.put("sessionName", TOKEN);
            properties.put("textFilter", TOKEN);
            editor.setObject(properties);
            var clear = editor.highlightReviewField("URL", 0, TOKEN, 0, TOKEN.length());
            assertNotNull(clear);
            var url = EditorMatchHighlighter.find(editor.property("url"), JTextComponent.class);
            var session = EditorMatchHighlighter.find(editor.property("sessionName"), JTextComponent.class);
            assertEquals(1, url.getHighlighter().getHighlights().length);
            assertEquals(0, session.getHighlighter().getHighlights().length);
            clear.run();
        });
    }

    @Test
    void legacySamplerDoesNotGainHeadersTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var legacy = new UrlConfigGui(true, true, true, false);
            var modern = new UrlConfigGui(true, true, true, true);
            var defaults = new UrlConfigGui(false, true, true, false);
            assertEquals(legacy.getContentTabbedPane().getTabCount() + 1,
                    modern.getContentTabbedPane().getTabCount());
            assertEquals(modern.getContentTabbedPane().getTabCount(), defaults.getContentTabbedPane().getTabCount());
        });
    }

    @Test
    void revealsExactParameterAndHeaderCellsAndBodyRange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var sampler = sampler();
            sampler.addArgument("duplicate", TOKEN);
            sampler.addArgument("duplicate", TOKEN);
            sampler.setNativeHeaders(List.of(new Header("X-Token", TOKEN)));
            var editor = new UrlConfigGui(true, true, true, true);
            editor.configure(sampler);
            Runnable clear = editor.highlightReviewField("Parameter value", 1, TOKEN, 0, TOKEN.length());
            assertNotNull(clear);
            var table = EditorMatchHighlighter.find(editor.getContentTabbedPane().getSelectedComponent(), javax.swing.JTable.class);
            assertEquals(1, table.getSelectedRow());
            var cell = (JTextComponent) table.getEditorComponent();
            assertEquals(TOKEN, cell.getSelectedText());
            assertEquals(1, cell.getHighlighter().getHighlights().length);
            clear.run();
            assertFalse(table.isEditing());
            clear = editor.highlightReviewField("Header value", 0, TOKEN, 0, TOKEN.length());
            assertNotNull(clear);
            clear.run();
            sampler.setPostBodyRaw(true);
            sampler.getArguments().removeAllArguments();
            sampler.addArgument("", "line\r\n" + TOKEN);
            editor.configure(sampler);
            clear = editor.highlightReviewField("Body", 0, "line\r\n" + TOKEN, 6, 6 + TOKEN.length());
            assertNotNull(clear);
            var body = EditorMatchHighlighter.find(editor.getContentTabbedPane().getSelectedComponent(), JTextComponent.class);
            assertEquals(TOKEN, body.getSelectedText());
            clear.run();
        });
    }
}
