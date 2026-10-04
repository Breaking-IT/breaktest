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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.ReplaceableField;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.util.JOrphanUtils;

@GUIMenuSortOrder(102)
@TestElementMetadata(labelResource = "displayName")
public class WebSocketSendWaitSampler extends AbstractWebSocketSampler implements Replaceable {
    private static final long serialVersionUID = 1L;
    public static final String SEND_ONLY = "Send only";
    public static final String SEND_AND_WAIT = "Send and Wait";
    public static final String NEXT_MESSAGE = "Next message";
    public static final String MATCHING_MESSAGE = "Message matching regular expression";

    public static final String BINARY_MESSAGE = "Binary message containing hex sequence";

    @Override
    public List<ReplaceableField> getReplaceableFields() {
        return List.of(new ReplaceableField("Message", this::getPayload, this::setPayload, SearchArea.BODY));
    }

    @Override
    public int replace(String regex, String replaceBy, boolean caseSensitive) throws Exception {
        return JOrphanUtils.replaceValue(regex, replaceBy, caseSensitive, getPayload(), this::setPayload);
    }

    @Override
    public int replaceLiteral(String literal, String replaceBy) {
        return JOrphanUtils.replaceLiteralValue(literal, replaceBy, true, getPayload(), this::setPayload);
    }

    @Override
    public List<String> getSearchableTokens() {
        List<String> tokens = super.getSearchableTokens();
        addBinaryTextSearchToken(tokens);
        return tokens;
    }

    @Override
    public List<String> getSearchableTokens(Set<SearchArea> areas) {
        List<String> tokens = super.getSearchableTokens(areas);
        if (areas.size() != SearchArea.values().length && areas.contains(SearchArea.BODY)) {
            addBinaryTextSearchToken(tokens);
        }
        return tokens;
    }

    private void addBinaryTextSearchToken(List<String> tokens) {
        if (getBinary()) {
            try {
                // Search the bytes continuously; the viewer's 16-byte rows are only presentation.
                tokens.add(new String(WebSocketBinary.parse(getPayload()), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Unresolved variables or incomplete hex remain searchable as the original payload.
            }
        }
    }

    @Override
    protected SearchArea searchAreaForProperty(String propertyName) {
        return "payload".equals(propertyName) ? SearchArea.BODY
                : super.searchAreaForProperty(propertyName);
    }

    @Override
    protected void execute(SampleResult result) throws Exception {
        if (!SEND_ONLY.equals(getAction()) && !SEND_AND_WAIT.equals(getAction())) {
            throw new IllegalArgumentException("Unknown WebSocket send action: " + getAction());
        }
        WebSocketSession session = WebSocketSessions.current().get(getSessionName());
        waitForRecordedTime(session, result, getSendOffset());
        if (SEND_ONLY.equals(getAction())) {
            send(session, result);
            return;
        }
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

    public String getAction() {
        return getPropertyAsString("action", SEND_AND_WAIT);
    }

    public void setAction(String value) {
        setProperty("action", value);
    }

    public String getSendOffset() {
        return getPropertyAsString("sendOffset");
    }

    public void setSendOffset(String value) {
        setProperty("sendOffset", value);
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
    private void send(WebSocketSession session, SampleResult result) throws Exception {
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
