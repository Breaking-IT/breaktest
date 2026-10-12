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
    void harImportCorrelatesWebSocketHostAndFragment() throws Exception {
        String har = """
                {"log":{"entries":[
                  {"startedDateTime":"2026-10-12T00:00:00Z","time":10,"serverIPAddress":"127.0.0.1",
                   "request":{"method":"GET","url":"https://example.test/token","headers":[]},
                   "response":{"status":200,"headers":[],"content":{"text":"token=recorded-token;"}}},
                  {"startedDateTime":"2026-10-12T00:00:01Z","time":10,"serverIPAddress":"127.0.0.1",
                   "request":{"method":"GET","url":"wss://recorded-token.example.test/socket#recorded-token","headers":[]},
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
        assertEquals("wss://${token}.example.test/socket#${token}", samplers.getSearchResults().iterator().next().getUrl());
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
        assertEquals(List.of("test"), HarCorrelationRuleCatalog.parse(content, true).stream().map(Rule::getId).toList());
    }

    @Test
    void pathologicalRegexFailsWithinABoundedAmountOfWork() {
        var filter = new CorrelationReplacementFilter(CorrelationReplacementFilter.Scope.BODY, false, "(a+)+$");
        assertTimeout(Duration.ofSeconds(2), () -> assertThrows(IllegalArgumentException.class,
                () -> filter.matches("a".repeat(2000) + "!", "a", "${token}", false, RequestLocation.REQUEST_BODY, "")));
    }
}
