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

import java.nio.charset.StandardCharsets;

import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.SampleResult;

@TestElementMetadata(labelResource = "displayName")
public class WebSocketSendSampler extends AbstractWebSocketSampler {
    private static final long serialVersionUID = 1L;

    @Override
    protected void execute(SampleResult result) throws Exception {
        send(WebSocketSessions.current().get(getSessionName()), result);
    }

    protected final void send(WebSocketSession session, SampleResult result) throws Exception {
        String payload = getPayload();
        if (!getBinary() && !StandardCharsets.UTF_8.newEncoder().canEncode(payload)) {
            throw new IllegalArgumentException("WebSocket text contains malformed UTF-16");
        }
        byte[] bytes = getBinary() ? WebSocketBinary.parse(payload) : payload.getBytes(StandardCharsets.UTF_8);
        active(session);
        await(session.send(bytes, getBinary()));
        result.setSamplerData(getPayload());
        result.setSentBytes(bytes.length);
    }

    public String getPayload() {
        return getPropertyAsString("payload");
    }

    public void setPayload(String value) {
        setProperty("payload", value);
    }

    public boolean getBinary() {
        return getPropertyAsBoolean("binary", false);
    }

    public void setBinary(boolean value) {
        setProperty("binary", value);
    }
}
