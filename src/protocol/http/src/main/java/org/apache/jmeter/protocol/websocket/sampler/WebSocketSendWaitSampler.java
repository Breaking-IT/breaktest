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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.SampleResult;

@TestElementMetadata(labelResource = "displayName")
public class WebSocketSendWaitSampler extends WebSocketSendSampler {
    private static final long serialVersionUID = 1L;
    public static final String NEXT_MESSAGE = "Next message";
    public static final String MATCHING_MESSAGE = "Message matching regular expression";

    public static final String BINARY_MESSAGE = "Binary message containing hex sequence";

    @Override
    protected void execute(SampleResult result) throws Exception {
        if (getWaitTimeout() <= 0) {
            throw new IllegalArgumentException("Wait timeout must be positive");
        }
        Predicate<SampleResult> matcher;
        if (NEXT_MESSAGE.equals(getWaitMode())) {
            matcher = message -> true;
        } else if (MATCHING_MESSAGE.equals(getWaitMode())) {
            if (getResponsePattern().isEmpty()) {
                throw new IllegalArgumentException("A response regular expression is required in matching mode");
            }
            Pattern pattern = Pattern.compile(getResponsePattern());
            matcher = message -> SampleResult.TEXT.equals(message.getDataType())
                    && pattern.matcher(message.getResponseDataAsString()).find();
        } else if (BINARY_MESSAGE.equals(getWaitMode())) {
            matcher = WebSocketBinary.matcher(WebSocketBinary.parse(getResponseBinary()));
        } else {
            throw new IllegalArgumentException("Unknown WebSocket wait mode: " + getWaitMode());
        }
        WebSocketSession session = WebSocketSessions.current().get(getSessionName());
        // Subscribe first: the server may answer before sendText/sendBinary completes.
        CompletableFuture<SampleResult> response = session.waitForMatchingMessage(matcher);
        try {
            send(session, result);
            // A response timeout fails this sample but leaves the session usable.
            active(null);
            SampleResult message = await(response, getWaitTimeout(), TimeUnit.MILLISECONDS);
            result.setResponseData(message.getResponseData());
            result.setDataType(message.getDataType());
            result.setDataEncoding(message.getDataEncodingWithDefault());
        } finally {
            session.cancelWait(response);
        }
    }

    public String getResponseBinary() {
        return getPropertyAsString("responseBinary");
    }

    public void setResponseBinary(String value) {
        setProperty("responseBinary", value);
    }

    public int getWaitTimeout() {
        return getPropertyAsInt("waitTimeout", 10000);
    }

    public void setWaitTimeout(int value) {
        setProperty("waitTimeout", value);
    }

    public String getWaitMode() {
        return getPropertyAsString("waitMode", NEXT_MESSAGE);
    }

    public void setWaitMode(String value) {
        setProperty("waitMode", value);
    }

    public String getResponsePattern() {
        return getPropertyAsString("responsePattern");
    }

    public void setResponsePattern(String value) {
        setProperty("responsePattern", value);
    }
}
