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

package org.apache.jmeter.protocol.websocket.sampler;

import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.SampleResult;

@GUIMenuSortOrder(103)
@TestElementMetadata(labelResource = "displayName")
public class WebSocketCloseSampler extends AbstractWebSocketSampler {
    private static final long serialVersionUID = 1L;

    @Override
    protected void execute(SampleResult result) throws Exception {
        WebSocketSessions sessions = WebSocketSessions.current();
        WebSocketSession session = sessions.get(getSessionName());
        active(session);
        try {
            await(session.close());
        } finally {
            sessions.remove(getSessionName(), session);
        }
    }

}
