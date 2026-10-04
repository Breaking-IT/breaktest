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

import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler;
import org.junit.jupiter.api.Test;

class WebSocketSearchReplaceTest {
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
