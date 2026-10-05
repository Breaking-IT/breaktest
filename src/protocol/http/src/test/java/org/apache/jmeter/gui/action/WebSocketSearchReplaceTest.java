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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jmeter.extractor.gui.RegexExtractorGui;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.sse.SseSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class WebSocketSearchReplaceTest {
    @Test
    void regexTesterFindsStreamingRequestsAfterSourceAndPreviewDoesNotMutateThem() throws Exception {
        var group = new JMeterTreeNode(new ThreadGroup(), null);
        var before = new WebSocketConnectSampler();
        before.setUrl("wss://example.test/token");
        group.add(new JMeterTreeNode(before, null));
        var source = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        group.add(source);

        var connect = new WebSocketConnectSampler();
        connect.setUrl("wss://example.test/socket?id=token&existing=${token}");
        connect.setHeaders(List.of(new Header("token", "Bearer token ${token}")));
        connect.setName("token");
        connect.setComment("token");
        connect.setProperty("BreakTest.recordedResponse", "token");
        var send = new WebSocketSendWaitSampler();
        send.setPayload("token ${token}");
        var sse = new SseSampler();
        sse.setPath("/events/token");
        sse.addArgument("token", "token");
        sse.setNativeHeaders(List.of(new Header("token", "Bearer token")));
        for (TestElement element : List.of(connect, send, sse)) {
            group.add(new JMeterTreeNode(element, null));
        }

        var find = RegexExtractorGui.class.getDeclaredMethod(
                "findReplacementTargets", JMeterTreeNode.class, String.class);
        find.setAccessible(true);
        var targets = (List<?>) find.invoke(null, source, "token");
        assertEquals(3, targets.size());
        var occurrences = targets.get(0).getClass().getDeclaredMethod("occurrences");
        occurrences.setAccessible(true);
        assertEquals(List.of(2, 1, 3), List.of(
                occurrences.invoke(targets.get(0)), occurrences.invoke(targets.get(1)), occurrences.invoke(targets.get(2))));
        assertEquals("wss://example.test/socket?id=token&existing=${token}", connect.getUrl());
        assertEquals("Bearer token ${token}", connect.getHeaders().get(0).getValue());
        assertEquals("token ${token}", send.getPayload());
        assertEquals("/events/token", sse.getPath());
        assertEquals("token", sse.getArguments().getArgument(0).getValue());
        assertEquals("Bearer token", sse.getNativeHeaderList().get(0).getValue());

        for (Replaceable request : List.of(connect, send, sse)) {
            request.replaceLiteral("token", "${extracted}");
        }
        assertEquals("wss://example.test/socket?id=${extracted}&existing=${token}", connect.getUrl());
        assertEquals("Bearer ${extracted} ${token}", connect.getHeaders().get(0).getValue());
        assertEquals("token", connect.getHeaders().get(0).getName());
        assertEquals("token", connect.getName());
        assertEquals("token", connect.getComment());
        assertEquals("token", connect.getPropertyAsString("BreakTest.recordedResponse"));
        assertEquals("${extracted} ${token}", send.getPayload());
        assertEquals("/events/${extracted}", sse.getPath());
        assertEquals("${extracted}", sse.getArguments().getArgument(0).getValue());
        assertEquals("Bearer ${extracted}", sse.getNativeHeaderList().get(0).getValue());
        assertTrue(((List<?>) find.invoke(null, source, "token")).isEmpty());
        assertEquals("wss://example.test/token", before.getUrl());
    }

    @Test
    void connectReplacementRespectsSearchAreasAndSupportsDirectRegex() throws Exception {
        var connect = new WebSocketConnectSampler();
        connect.setUrl("wss://example.test/old");
        connect.setHeaders(List.of(new Header("X-old", "old OLD")));
        var node = new JMeterTreeNode(connect, null);
        var changes = SearchTreeDialog.replacementChanges(node, Pattern.compile("old"),
                "${value}", false, Set.of(SearchArea.PATH));
        assertEquals(1, SearchTreeDialog.applyChanges(changes));
        assertEquals("wss://example.test/${value}", connect.getUrl());
        assertEquals("old OLD", connect.getHeaders().get(0).getValue());
        changes = SearchTreeDialog.replacementChanges(node, Pattern.compile("old", Pattern.CASE_INSENSITIVE),
                "new", true, Set.of(SearchArea.HEADERS));
        assertEquals(3, SearchTreeDialog.applyChanges(changes));
        assertEquals("X-new", connect.getHeaders().get(0).getName());
        assertEquals("new new", connect.getHeaders().get(0).getValue());
        assertEquals(3, connect.replace("NEW", "next", false));
        assertEquals("X-next", connect.getHeaders().get(0).getName());
        assertEquals("next next", connect.getHeaders().get(0).getValue());
    }

    @Test
    void findsAndReplacesTextPayloadInBodyArea() throws Exception {
        var send = new WebSocketSendWaitSampler();
        send.setName("old-value");
        send.setPayload("old-value old-value");
        var node = new JMeterTreeNode(send, null);
        var body = Set.of(SearchArea.BODY);
        assertTrue(SearchTreeDialog.searchableTokens(node, null, body).contains(send.getPayload()));
        var changes = SearchTreeDialog.replacementChanges(node, Pattern.compile("old-value"),
                "${replacement}", false, body);
        assertEquals(1, changes.size());
        assertEquals(2, SearchTreeDialog.applyChanges(changes));
        assertEquals("${replacement} ${replacement}", send.getPayload());
        assertEquals("old-value", send.getName());
        assertTrue(SearchTreeDialog.replacementChanges(node, Pattern.compile("replacement"),
                "ignored", false, Set.of(SearchArea.OTHER)).isEmpty());
    }

    @Test
    void replacesBinaryHexPayloadWithRegexAndPreservesMode() {
        var send = new WebSocketSendWaitSampler();
        send.setBinary(true);
        send.setPayload("00 ff 41 ff");
        var changes = SearchTreeDialog.replacementChanges(new JMeterTreeNode(send, null),
                Pattern.compile("(ff)", Pattern.CASE_INSENSITIVE), "01", true, Set.of(SearchArea.BODY));
        assertEquals(2, SearchTreeDialog.applyChanges(changes));
        assertEquals("00 01 41 01", send.getPayload());
        assertTrue(send.getBinary());
    }

    @Test
    void directReplacementSupportsLiteralVariablesAndCaseInsensitiveRegex() throws Exception {
        var send = new WebSocketSendWaitSampler();
        send.setPayload("hello HELLO");
        assertEquals(2, send.replace("hello", "world", false));
        assertEquals(2, send.replaceLiteral("world", "${message}"));
        assertEquals("${message} ${message}", send.getPayload());
    }
}
