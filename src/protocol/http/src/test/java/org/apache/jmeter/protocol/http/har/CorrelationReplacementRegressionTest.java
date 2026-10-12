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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.RequestLocation;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.udp.sampler.UDPSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class CorrelationReplacementRegressionTest extends JMeterTestCase {
    private static Rule rule(CorrelationReplacementFilter filter) {
        return new Rule("test", "Custom", "Test", "token", HarPredefinedCorrelation.ExtractorType.REGEX,
                HarPredefinedCorrelation.ResponseField.BODY, "token=([^;]+);", "$1$", -1, 1, "", false, false, true)
                .withReplacementFilter(filter);
    }

    private static List<HarPredefinedCorrelation> find(TestElement target, String token, Rule rule) {
        var source = FindPredefinedCorrelationsAction.toHarEntry(new UDPSampler(), "", "token=" + token + ";", 0);
        var consumer = FindPredefinedCorrelationsAction.toHarEntry(target, "", "", 1);
        var result = HarPredefinedCorrelation.find(List.of(source, consumer), List.of(rule));
        assertEquals(1, result.size());
        return result;
    }

    private static void apply(TestElement target, String token, Rule rule, boolean review) {
        var correlations = find(target, token, rule);
        if (review) {
            var steps = CorrelationReviewStep.create(correlations, Map.of(1, new JMeterTreeNode(target, null)));
            assertTrue(!steps.isEmpty());
            for (var step : steps) {
                assertTrue(step.accept(steps));
            }
        } else {
            for (var replacement : correlations.get(0).getReplacements()) {
                FindPredefinedCorrelationsAction.applyReplacement((Replaceable) target, correlations.get(0), replacement);
            }
        }
    }

    @Test
    void defaultUrlsRetainOpaqueAndDelimiterSpanningValues() {
        for (String token : List.of("abc123token", "abc&def=ghi", "abc=def")) {
            var socket = new WebSocketConnectSampler();
            socket.setUrl("wss://example.test/socket?" + token);
            apply(socket, token, rule(CorrelationReplacementFilter.ALL), true);
            assertEquals("wss://example.test/socket?${token}", socket.getUrl());
            var http = new HTTPSamplerProxy();
            http.setProtocol("https");
            http.setDomain("example.test");
            http.setMethod("POST");
            http.setPath("/target?" + token);
            var source = FindPredefinedCorrelationsAction.toHarEntry(http, "", "token=" + token + ";", 0);
            var consumer = FindPredefinedCorrelationsAction.toHarEntry(http, "", "", 1);
            var found = HarPredefinedCorrelation.find(List.of(source, consumer), List.of(rule(CorrelationReplacementFilter.ALL)));
            assertEquals(1, found.size());
            var options = new HarImportOptions();
            options.setPredefinedCorrelations(found);
            var tree = new HarConverter(List.of(source, consumer), options, "opaque.har", "md5")
                    .convert(java.util.Set.of("example.test"));
            var samplers = new org.apache.jorphan.collections.SearchByClass<>(HTTPSamplerProxy.class);
            tree.traverse(samplers);
            assertTrue(samplers.getSearchResults().stream().anyMatch(sampler -> sampler.getPath().equals("/target?${token}")));
        }
        var filtered = new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.QUERY_PARAMETERS, true, "");
        assertTrue(filtered.urlFieldMatches("wss://example.test/?abc123token", "abc123token", "${token}", true).isEmpty());
    }

    @Test
    void webSocketQueryNamesStayLiteralAndRepeatedValuesProduceOneCandidatePerField() {
        for (boolean review : List.of(false, true)) {
            var socket = new WebSocketConnectSampler();
            socket.setUrl("wss://example.test/socket?id&id=id&id=id&other=id");
            var found = find(socket, "id", rule(CorrelationReplacementFilter.ALL));
            assertEquals(List.of("", "id", "other"), found.get(0).getReplacements().stream()
                    .map(HarPredefinedCorrelation.Replacement::getLocationName).toList());
            apply(socket, "id", rule(CorrelationReplacementFilter.ALL), review);
            assertEquals("wss://example.test/socket?${token}&id=${token}&id=${token}&other=${token}", socket.getUrl());
            var message = new WebSocketSendWaitSampler();
            message.setPayload("id ".repeat(100));
            var correlations = find(message, "id", rule(CorrelationReplacementFilter.ALL));
            assertEquals(1, correlations.get(0).getReplacements().size());
            apply(message, "id", rule(CorrelationReplacementFilter.ALL), review);
            assertEquals("${token} ".repeat(100), message.getPayload());
        }
    }

    @Test
    void encodedParameterPatternSurvivesHarImportForQueryAndForm() throws Exception {
        for (String method : List.of("GET", "POST")) {
            String har = """
                    {"log":{"entries":[
                      {"startedDateTime":"2026-10-12T00:00:00Z","time":10,"serverIPAddress":"127.0.0.1",
                       "request":{"method":"GET","url":"https://example.test/source","headers":[]},
                       "response":{"status":200,"headers":[],"content":{"text":"token=abc/def;"}}},
                      {"startedDateTime":"2026-10-12T00:00:01Z","time":10,"serverIPAddress":"127.0.0.1",
                       "request":{"method":"%s","url":"https://example.test/target","headers":[],
                           "queryString":[{"name":"id","value":"abc%%2Fdef"}],
                           "postData":{"mimeType":"application/x-www-form-urlencoded",
                             "params":[{"name":"id","value":"abc%%2Fdef"}]}},
                       "response":{"status":200,"headers":[],"content":{"text":""}}}
                    ]}}
                    """.formatted(method);
            var entries = HarParser.parse(har.getBytes(StandardCharsets.UTF_8));
            var filtered = rule(new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.PARAMETERS,
                    true, "^id=abc%2Fdef$"));
            var found = HarPredefinedCorrelation.find(entries, List.of(filtered));
            assertEquals(1, found.size());
            var options = new HarImportOptions();
            options.setPredefinedCorrelations(found);
            var tree = new HarConverter(entries, options, "probe.har", "md5").convert(java.util.Set.of("example.test"));
            var samplers = new org.apache.jorphan.collections.SearchByClass<>(HTTPSamplerProxy.class);
            tree.traverse(samplers);
            var target = samplers.getSearchResults().stream().filter(sampler -> sampler.getPath().equals("/target"))
                    .findFirst().orElseThrow();
            assertEquals("${token}", target.getArguments().getArgument(0).getValue());
        }
    }

    @Test
    void webSocketHeaderNamesStayLiteralAndQueryLocationsAreReportedAccurately() {
        for (boolean review : List.of(false, true)) {
            var socket = new WebSocketConnectSampler();
            socket.setUrl("wss://example.test/socket?id=recorded-token");
            socket.setHeaders(List.of(new org.apache.jmeter.protocol.http.control.Header("recorded-token", "recorded-token")));
            var found = find(socket, "recorded-token", rule(CorrelationReplacementFilter.ALL));
            assertTrue(found.get(0).getReplacements().stream().anyMatch(r -> r.getLocation() == RequestLocation.QUERY_PARAMETER));
            assertTrue(found.get(0).getReplacements().stream().noneMatch(r -> r.getLocation() == RequestLocation.URL_PATH));
            apply(socket, "recorded-token", rule(CorrelationReplacementFilter.ALL), review);
            assertEquals("recorded-token", socket.getHeaders().get(0).getName());
            assertEquals("${token}", socket.getHeaders().get(0).getValue());
        }
    }

    @Test
    void defaultBulkApplyPreservesBodyArgumentsAndTheirMetadata() {
        var http = new HTTPSamplerProxy();
        http.setDomain("example.test");
        http.setProtocol("https");
        http.setMethod("POST");
        http.setPath("/");
        http.setPostBodyRaw(true);
        http.addNonEncodedArgument("", "recorded-token", "=");
        http.addNonEncodedArgument("", "/recorded-token", "");
        var arguments = http.getArguments();
        var first = arguments.getArgument(0);
        var second = arguments.getArgument(1);
        first.setDescription("keep first");
        second.setDescription("keep second");
        arguments.setName("body metadata");
        apply(http, "recorded-token", rule(CorrelationReplacementFilter.ALL), false);
        org.junit.jupiter.api.Assertions.assertSame(arguments, http.getArguments());
        org.junit.jupiter.api.Assertions.assertSame(first, http.getArguments().getArgument(0));
        org.junit.jupiter.api.Assertions.assertSame(second, http.getArguments().getArgument(1));
        assertEquals("${token}", first.getValue());
        assertEquals("/${token}", second.getValue());
        assertEquals("keep first", first.getDescription());
        assertEquals("keep second", second.getDescription());
        assertEquals("body metadata", arguments.getName());
    }

    @Test
    void expensivePatternDoesNotAbortScanImportOrBulkApply() {
        var slow = rule(new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.BODY, false, "(a+)+$"));
        var good = new Rule("good", "Custom", "Good", "good", HarPredefinedCorrelation.ExtractorType.REGEX,
                HarPredefinedCorrelation.ResponseField.BODY, "safe=([^;]+);", "$1$", -1, 1, "", false, false, true);
        var source = new HTTPSamplerProxy();
        source.setProtocol("https");
        source.setDomain("example.test");
        source.setMethod("GET");
        source.setPath("/source");
        var target = (HTTPSamplerProxy) source.clone();
        target.setMethod("POST");
        target.setPostBodyRaw(true);
        target.addNonEncodedArgument("", "a", "");
        var sourceEntry = FindPredefinedCorrelationsAction.toHarEntry(source, "", "token=a;safe=good;", 0);
        var benign = FindPredefinedCorrelationsAction.toHarEntry(target, "", "", 1);
        var earlySlow = HarPredefinedCorrelation.find(List.of(sourceEntry, benign), List.of(slow));
        assertEquals(1, earlySlow.size());
        String body = "a".repeat(2000) + "!good";
        target.getArguments().getArgument(0).setValue(body);
        var targetEntry = FindPredefinedCorrelationsAction.toHarEntry(target, "", "", 1);
        var found = HarPredefinedCorrelation.find(List.of(sourceEntry, targetEntry), List.of(slow, good));
        assertEquals(List.of("good"), found.stream().map(c -> c.getRule().getId()).toList());
        var pending = new java.util.ArrayList<>(earlySlow);
        pending.addAll(found);
        var options = new HarImportOptions();
        options.setPredefinedCorrelations(pending);
        var prepared = CorrelationReviewDialog.prepare(pending, Map.of(1, new JMeterTreeNode(target, null)));
        assertEquals(1, prepared.warnings().size());
        assertTrue(prepared.warnings().get(0).contains("work limit"));
        var converter = new HarConverter(List.of(sourceEntry, targetEntry), options, "patterns.har", "md5");
        var tree = converter.convert(java.util.Set.of("example.test"));
        assertEquals(prepared.warnings(), converter.getCorrelationWarnings());
        slow.getReplacementFilter().clearMatchingDiagnostic();
        assertEquals(1, converter.getCorrelationWarnings().size(), "Import warning is a snapshot");
        var samplers = new org.apache.jorphan.collections.SearchByClass<>(HTTPSamplerProxy.class);
        tree.traverse(samplers);
        assertEquals("a".repeat(2000) + "!${good}", samplers.getSearchResults().stream()
                .filter(sampler -> sampler.getMethod().equals("POST")).findFirst().orElseThrow()
                .getArguments().getArgument(0).getValue());
        for (var correlation : pending) {
            for (var replacement : correlation.getReplacements()) {
                FindPredefinedCorrelationsAction.applyReplacement(target, correlation, replacement);
            }
        }
        assertEquals("a".repeat(2000) + "!${good}", target.getArguments().getArgument(0).getValue());
        assertTrue(HarPredefinedCorrelation.diagnostics(List.of(slow)).get(0).contains("work limit"));
    }

    @Test
    void harImportCorrelatesWebSocketHostAndFragment() throws Exception {
        String har = """
                {"log":{"entries":[
                  {"startedDateTime":"2026-10-12T00:00:00Z","time":10,"serverIPAddress":"127.0.0.1",
                   "request":{"method":"GET","url":"https://example.test/token","headers":[]},
                   "response":{"status":200,"headers":[],"content":{"text":"token=recorded-token;"}}},
                  {"startedDateTime":"2026-10-12T00:00:01Z","time":10,"serverIPAddress":"127.0.0.1",
                   "request":{"method":"GET","url":"wss://recorded-token.example.test/socket?recorded-token=recorded-token&recorded-token#recorded-token","headers":[]},
                   "response":{"status":101,"headers":[],"content":{"text":""}},
                   "_webSocketMessages":[{"type":"send","time":1791763202,"opcode":1,"data":"id: recorded-token"}]}
                ]}}
                """;
        var entries = HarParser.parse(har.getBytes(StandardCharsets.UTF_8));
        var rules = List.of(rule(CorrelationReplacementFilter.ALL));
        var correlations = HarPredefinedCorrelation.find(entries, rules);
        assertEquals(1, correlations.size());
        var options = new HarImportOptions();
        options.setPredefinedCorrelations(correlations);
        var tree = new HarConverter(entries, options, "messages.har", "md5").convert(java.util.Set.of("example.test", "recorded-token.example.test"));
        var samplers = new org.apache.jorphan.collections.SearchByClass<>(WebSocketConnectSampler.class);
        tree.traverse(samplers);
        assertEquals("wss://${token}.example.test/socket?recorded-token=${token}&${token}#${token}",
                samplers.getSearchResults().iterator().next().getUrl());
        assertEquals(3, correlations.get(0).getReplacements().size());
        assertEquals("recorded-token", correlations.get(0).getReplacements().stream()
                .filter(replacement -> replacement.getLocation() == RequestLocation.QUERY_PARAMETER)
                .findFirst().orElseThrow().getLocationName());
    }

    @Test
    void udpResponsesCanCorrelateUdpAndWebSocketPayloads() {
        for (boolean review : List.of(false, true)) {
            for (var filter : List.of(CorrelationReplacementFilter.ALL,
                    new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.BODY, true, "id: \\d+"))) {
                var udp = new UDPSampler();
                udp.setRequestData("id: 123, other: 1234");
                apply(udp, "123", rule(filter), review);
                assertEquals(filter.unrestricted() ? "id: ${token}, other: ${token}4" : "id: ${token}, other: 1234", udp.getRequestData());
                var socket = new WebSocketSendWaitSampler();
                socket.setPayload("id: 123, other: 1234");
                apply(socket, "123", rule(filter), review);
                assertEquals(udp.getRequestData(), socket.getPayload());
            }
        }
    }

    @Test
    void defaultWebSocketCorrelationIncludesHostAndFragment() {
        for (boolean review : List.of(false, true)) {
            var socket = new WebSocketConnectSampler();
            socket.setUrl("wss://recorded-token.example.test/socket#recorded-token");
            apply(socket, "recorded-token", rule(CorrelationReplacementFilter.ALL), review);
            assertEquals("wss://${token}.example.test/socket#${token}", socket.getUrl());
        }
    }

    @Test
    void filteredFullParameterReplacementPreservesEncodingNormalization() {
        String value = "abc+def/ghi";
        for (boolean review : List.of(false, true)) {
            for (var filter : List.of(CorrelationReplacementFilter.ALL,
                    new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.PARAMETERS, true, "^id=.+$"))) {
                var http = new HTTPSamplerProxy();
                http.setProtocol("https");
                http.setDomain("example.test");
                http.setMethod("POST");
                http.setPath("/");
                http.addNonEncodedArgument("id", URLEncoder.encode(value, StandardCharsets.UTF_8), "=");
                apply(http, value, rule(filter), review);
                var argument = (HTTPArgument) http.getArguments().getArgument(0);
                assertEquals("${token}", argument.getValue());
                assertTrue(argument.isAlwaysEncoded());
            }
        }
    }

    @Test
    void wholePathSegmentsAndQueryValuesExcludeFragments() {
        var filter = new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.ALL, true, "");
        String url = "/orders/123?id=123#123";
        assertEquals("/orders/${token}?id=123#123", CorrelationReplacementFilter.replace(url,
                filter.urlMatches(url, "123", "${token}", RequestLocation.URL_PATH)));
        filter = new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.QUERY_PARAMETERS, true, "^id=123$");
        assertEquals("/orders/123?id=${token}#123", CorrelationReplacementFilter.replace(url,
                filter.urlMatches(url, "123", "${token}", RequestLocation.QUERY_PARAMETER)));
        assertTrue(filter.urlMatches("/orders#fragment?id=123", "123", "${token}", RequestLocation.QUERY_PARAMETER).isEmpty());
    }

    @Test
    void invalidPatternDoesNotDiscardOtherRulesWhenLoadingButImportIsStrict() throws Exception {
        var mapper = new ObjectMapper();
        var json = mapper.readTree(HarCorrelationRuleCatalog.serialize(List.of(rule(CorrelationReplacementFilter.ALL))));
        var rules = (com.fasterxml.jackson.databind.node.ArrayNode) json.path("groups").get(0).path("rules");
        var invalid = ((ObjectNode) rules.get(0)).deepCopy();
        invalid.put("id", "invalid");
        invalid.put("replacementFieldPattern", "[");
        rules.add(invalid);
        byte[] content = mapper.writeValueAsBytes(json);
        assertThrows(java.io.IOException.class, () -> HarCorrelationRuleCatalog.parse(content));
        var restored = HarCorrelationRuleCatalog.parse(content, true);
        assertEquals(List.of("test", "invalid"), restored.stream().map(Rule::getId).toList());
        assertEquals("[", restored.get(1).getReplacementFilter().fieldPattern());
        assertTrue(!restored.get(1).getReplacementFilter().diagnostic().isEmpty());
        assertTrue(restored.get(1).getReplacementFilter().matches("token", "token", "${token}", false,
                RequestLocation.REQUEST_BODY, "").isEmpty());
    }

    @Test
    void pathologicalRegexFailsWithinABoundedAmountOfWork() {
        var filter = new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.BODY, false, "(a+)+$");
        assertTimeout(Duration.ofSeconds(2), () -> assertTrue(
                filter.matches("a".repeat(2000) + "!", "a", "${token}", false, RequestLocation.REQUEST_BODY, "").isEmpty()));
        assertTrue(!filter.diagnostic().isEmpty());
    }
}
