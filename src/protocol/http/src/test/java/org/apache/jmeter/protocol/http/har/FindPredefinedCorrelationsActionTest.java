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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import javax.swing.JMenuItem;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindPredefinedCorrelationsActionTest extends JMeterTestCase {

    @TempDir
    private Path tempDir;

    @Test
    void contributesPredefinedCorrelationsToToolsMenu() {
        FindPredefinedCorrelationsAction action = new FindPredefinedCorrelationsAction();

        JMenuItem[] items = action.getMenuItemsAtLocation(MenuCreator.MENU_LOCATION.TOOLS);

        assertEquals(1, items.length);
        assertEquals(ActionNames.FIND_PREDEFINED_CORRELATIONS, items[0].getActionCommand());
        assertEquals(0, action.getMenuItemsAtLocation(MenuCreator.MENU_LOCATION.FILE).length);
    }

    @Test
    void convertsCurrentSamplersAndRecordedResponsesIntoCorrelationEvidence() {
        HTTPSamplerProxy source = sampler("GET", "/token");
        HTTPSamplerProxy target = sampler("POST", "/session");
        target.setNativeHeaders(List.of(new Header("Authorization", "Bearer access-token-123")));
        target.setPostBodyRaw(true);
        Arguments arguments = new Arguments();
        arguments.addArgument(new HTTPArgument(
                "", "{\"refreshToken\":\"refresh-token-456\"}", false));
        target.setArguments(arguments);

        HarEntry sourceEntry = FindPredefinedCorrelationsAction.toHarEntry(
                source,
                "HTTP/2 200\r\nContent-Type:application/json\r\n\r\n"
                        + "{\"access_token\":\"access-token-123\","
                        + "\"refresh_token\":\"refresh-token-456\"}",
                "{\"access_token\":\"access-token-123\","
                        + "\"refresh_token\":\"refresh-token-456\"}", 0);
        HarEntry targetEntry = FindPredefinedCorrelationsAction.toHarEntry(target, "", "", 1);

        List<HarPredefinedCorrelation> matches =
                HarPredefinedCorrelation.find(List.of(sourceEntry, targetEntry));

        assertEquals(List.of("oauth-access-token", "oauth-refresh-token"),
                matches.stream().map(match -> match.getRule().getId()).toList());
        assertEquals("application/json", sourceEntry.getResponseHeaders().get(0).getValue());
    }

    @Test
    void appliesReviewedReplacementsToExistingSamplerData() {
        HTTPSamplerProxy source = sampler("GET", "/token");
        HTTPSamplerProxy target = sampler("POST", "/session");
        target.setNativeHeaders(List.of(new Header("Authorization", "Bearer access-token-123")));
        target.setPostBodyRaw(true);
        Arguments arguments = new Arguments();
        arguments.addArgument(new HTTPArgument(
                "", "{\"accessToken\":\"access-token-123\"}", false));
        target.setArguments(arguments);
        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(List.of(
                FindPredefinedCorrelationsAction.toHarEntry(
                        source, "HTTP/1.1 200\n\n{\"access_token\":\"access-token-123\"}",
                        "{\"access_token\":\"access-token-123\"}", 0),
                FindPredefinedCorrelationsAction.toHarEntry(target, "", "", 1)));

        int replacementCount = matches.get(0).getReplacements().stream()
                .mapToInt(replacement -> FindPredefinedCorrelationsAction.applyReplacement(
                        target, matches.get(0), replacement))
                .sum();

        assertEquals(2, replacementCount);
        assertEquals("Bearer ${oauth_access_token}", target.getNativeHeaderList().get(0).getValue());
        assertEquals("{\"accessToken\":\"${oauth_access_token}\"}",
                target.getArguments().getArgument(0).getValue());
        assertEquals("/session", target.getName(), "element names remain static");
    }

    @Test
    void scansLinkedResponseAndCurrentRequestData() throws Exception {
        String har = """
                {"log":{"entries":[{
                  "startedDateTime":"2026-08-14T10:00:00Z",
                  "request":{"method":"GET","url":"https://example.test/token"},
                  "response":{"status":200,"httpVersion":"HTTP/2","headers":[
                    {"name":"Content-Type","value":"application/json"}],
                    "content":{"text":"{\\\"access_token\\\":\\\"linked-token-123\\\"}"}}
                }]}}
                """;
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Recorded flow");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, "recording.har");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, md5(har));
        HTTPSamplerProxy source = sampler("GET", "/token");
        source.setProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX, "0");
        HTTPSamplerProxy target = sampler("POST", "/session");
        target.setNativeHeaders(List.of(new Header("Authorization", "Bearer linked-token-123")));

        JMeterTreeNode groupNode = new JMeterTreeNode(threadGroup, null);
        JMeterTreeNode sourceNode = new JMeterTreeNode(source, null);
        JMeterTreeNode targetNode = new JMeterTreeNode(target, null);
        groupNode.add(sourceNode);
        groupNode.add(targetNode);

        FindPredefinedCorrelationsAction.ScanResult result = FindPredefinedCorrelationsAction.scan(
                groupNode, tempDir.resolve("plan.jmx"));

        assertEquals(0, result.unavailableCount());
        assertEquals(List.of("oauth-access-token"),
                result.correlations().stream().map(match -> match.getRule().getId()).toList());
        assertEquals(sourceNode, result.nodesByEntryIndex().get(0));
        assertEquals(targetNode, result.nodesByEntryIndex().get(1));
    }

    @Test
    void movesConsumersOutOfTheParallelControllerThatHoldsTheirExtractor() throws Exception {
        String har = """
                {"log":{"entries":[{
                  "startedDateTime":"2026-08-14T10:00:00Z",
                  "request":{"method":"GET","url":"https://example.test/token"},
                  "response":{"status":200,"httpVersion":"HTTP/2","headers":[
                    {"name":"Content-Type","value":"application/json"}],
                    "content":{"text":"{\\"access_token\\":\\"linked-token-123\\"}"}}
                }]}}
                """;
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Recorded flow");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, "recording.har");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, md5(har));
        HTTPSamplerProxy source = sampler("GET", "/token");
        source.setProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX, "0");
        HTTPSamplerProxy unrelated = sampler("GET", "/asset.js");
        HTTPSamplerProxy consumer = sampler("POST", "/concurrent");
        consumer.setNativeHeaders(List.of(new Header("Authorization", "Bearer linked-token-123")));

        JMeterTreeNode groupNode = new JMeterTreeNode(threadGroup, null);
        ParallelController parallelController = new ParallelController();
        parallelController.setName("Parallel Requests 1");
        parallelController.setMaxParallel(100);
        JMeterTreeNode parallelNode = new JMeterTreeNode(parallelController, null);
        groupNode.add(parallelNode);
        JMeterTreeNode sourceNode = new JMeterTreeNode(source, null);
        JMeterTreeNode consumerNode = new JMeterTreeNode(consumer, null);
        parallelNode.add(sourceNode);
        parallelNode.add(new JMeterTreeNode(unrelated, null));
        parallelNode.add(consumerNode);

        FindPredefinedCorrelationsAction.ScanResult result = FindPredefinedCorrelationsAction.scan(
                groupNode, tempDir.resolve("plan.jmx"));
        assertEquals(1, result.correlations().size(), "the correlation is kept, not dropped");

        int movedRequests = FindPredefinedCorrelationsAction.splitParallelControllers(
                null, result.correlations(), result.nodesByEntryIndex());

        assertEquals(1, movedRequests);
        assertEquals(2, parallelNode.getChildCount(), "the extractor keeps its parallel sibling");
        assertEquals(parallelNode, sourceNode.getParent());
        assertEquals(groupNode, consumerNode.getParent(),
                "a lone consumer is placed next to the controller instead of in one of its own");
        assertEquals(groupNode.getIndex(parallelNode) + 1, groupNode.getIndex(consumerNode));
    }

    @Test
    void scansQueryValuesStoredDirectlyInPostSamplerPath() {
        HTTPSamplerProxy source = sampler("GET", "/authorize");
        HTTPSamplerProxy target = sampler("POST", "/callback?code=authorization-code-123");

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(List.of(
                FindPredefinedCorrelationsAction.toHarEntry(
                        source,
                        "HTTP/1.1 302 Found\nLocation: https://example.test/callback?code=authorization-code-123\n",
                        "", 0),
                FindPredefinedCorrelationsAction.toHarEntry(target, "", "", 1)));

        assertEquals(1, matches.size());
        assertEquals(HarPredefinedCorrelation.RequestLocation.QUERY_PARAMETER,
                matches.get(0).getReplacements().get(0).getLocation());
        assertTrue(matches.get(0).getReplacements().get(0).getLocationName().isEmpty());
    }

    private static HTTPSamplerProxy sampler(String method, String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(path);
        sampler.setProtocol("https");
        sampler.setDomain("example.test");
        sampler.setMethod(method);
        sampler.setPath(path);
        sampler.setArguments(new Arguments());
        return sampler;
    }

    private static String md5(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
