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

package org.apache.jmeter.protocol.http.proxy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.protocol.sse.SseSampler;
import org.apache.jmeter.protocol.websocket.sampler.AbstractWebSocketSampler;
import org.apache.jorphan.collections.HashTree;

/** Readable session names, unique across the existing plan and this recording. */
final class RecordingSessionNames {
    private final Set<String> used = new HashSet<>();
    private final Map<String, String> webSockets = new HashMap<>();

    RecordingSessionNames(JMeterTreeModel model) {
        model.getNodesOfType(AbstractWebSocketSampler.class).forEach(node ->
                used.add(((AbstractWebSocketSampler) node.getTestElement()).getSessionName()));
        model.getNodesOfType(SseSampler.class).forEach(node ->
                used.add(((SseSampler) node.getTestElement()).getSseSessionName()));
    }

    String next(String prefix) {
        int number = 1;
        while (!used.add(prefix + number)) {
            number++;
        }
        return prefix + number;
    }

    void renameWebSockets(HashTree tree) {
        for (Object element : tree.list()) {
            if (element instanceof AbstractWebSocketSampler sampler) {
                sampler.setSessionName(webSockets.computeIfAbsent(sampler.getSessionName(), ignored -> next("websocket-")));
            }
            renameWebSockets(tree.getTree(element));
        }
    }
}
