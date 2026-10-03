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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.jmeter.samplers.SampleResult;

/** A session's immutable policy and serialized incoming-message lifecycle. */
final class WebSocketSession implements WebSocket.Listener {
    private final String name;
    private final boolean countIncoming;
    private final boolean failOnDisconnect;
    private final boolean ignoreControlFrames;
    private final Pattern textFilter;
    private final byte[] binaryFilter;
    private final int maxMessageBytes;
    private final Consumer<SampleResult> results;
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Queue<SampleResult> notifications = new ArrayDeque<>();
    private CompletableFuture<WebSocket> sending = CompletableFuture.completedFuture(null);
    private WebSocket socket;
    private boolean expectedClose;
    private boolean terminated;
    private boolean disposed;
    private int messageBytes;
    private boolean pendingHighSurrogate;
    private SampleResult incoming;
    private CompletableFuture<SampleResult> response;
    private Predicate<SampleResult> responseMatcher;

    WebSocketSession(String name, boolean countIncoming, boolean failOnDisconnect,
            boolean ignoreControlFrames, String textFilter, String binaryFilter,
            int maxMessageBytes, Consumer<SampleResult> results) {
        this.name = name;
        this.countIncoming = countIncoming;
        this.failOnDisconnect = failOnDisconnect;
        this.ignoreControlFrames = ignoreControlFrames;
        this.textFilter = textFilter.isEmpty() ? null : Pattern.compile(textFilter);
        this.binaryFilter = WebSocketBinary.parse(binaryFilter);
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("Maximum message size must be positive");
        }
        this.maxMessageBytes = maxMessageBytes;
        this.results = results;
    }

    @Override
    public synchronized void onOpen(WebSocket webSocket) {
        socket = webSocket;
        if (disposed) {
            webSocket.abort();
        } else {
            webSocket.request(1);
        }
    }

    synchronized WebSocket socket() {
        if (socket == null || terminated || disposed || expectedClose) {
            throw new IllegalStateException("WebSocket session is not open: " + name);
        }
        return socket;
    }

    private boolean begin(int size) {
        if (disposed || terminated) {
            return false;
        }
        if (size > maxMessageBytes - messageBytes) {
            handleError(new IllegalStateException("WebSocket message exceeds maximum message size"));
            socket.abort();
            return false;
        }
        messageBytes += size;
        if (incoming == null) {
            incoming = result("receive", true, "200", "OK");
            incoming.sampleStart();
        }
        return true;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            return handleText(webSocket, data, last);
        } finally {
            publishNotifications();
        }
    }

    private synchronized CompletionStage<?> handleText(WebSocket webSocket, CharSequence data, boolean last) {
        int bytes = data.toString().getBytes(StandardCharsets.UTF_8).length;
        // UTF-16 surrogate pairs may straddle listener callbacks. Each isolated
        // surrogate encoded above contributes one replacement byte, but the pair is four bytes.
        if (pendingHighSurrogate && data.length() > 0 && Character.isLowSurrogate(data.charAt(0))) {
            bytes += 2;
        }
        if (data.length() > 0) {
            pendingHighSurrogate = Character.isHighSurrogate(data.charAt(data.length() - 1));
        }
        if (begin(bytes)) {
            text.append(data);
            if (last) {
                String message = text.toString();
                complete(message.getBytes(StandardCharsets.UTF_8), SampleResult.TEXT,
                        textFilter != null && textFilter.matcher(message).find());
                text.setLength(0);
            }
            webSocket.request(1);
        }
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        try {
            return handleBinary(webSocket, data, last);
        } finally {
            publishNotifications();
        }
    }

    private synchronized CompletionStage<?> handleBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        if (begin(data.remaining())) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binary.writeBytes(bytes);
            if (last) {
                byte[] message = binary.toByteArray();
                boolean filtered = binaryFilter.length > 0 && message.length >= binaryFilter.length;
                for (int i = 0; filtered && i < binaryFilter.length; i++) {
                    filtered = message[i] == binaryFilter[i];
                }
                complete(message, SampleResult.BINARY, filtered);
                binary.reset();
            }
            webSocket.request(1);
        }
        return null;
    }

    private void complete(byte[] data, String type, boolean filtered) {
        incoming.sampleEnd();
        if (!filtered) {
            incoming.setResponseData(data);
            incoming.setDataType(type);
            incoming.setDataEncoding(StandardCharsets.UTF_8.name());
            boolean consumed = false;
            if (response != null && responseMatcher.test(incoming)) {
                consumed = response.complete(incoming);
                response = null;
                responseMatcher = null;
            }
            if (countIncoming && !consumed) {
                notifications.add(incoming);
            }
        }
        incoming = null;
        messageBytes = 0;
        pendingHighSurrogate = false;
    }

    synchronized CompletableFuture<WebSocket> send(byte[] bytes, boolean binaryMessage) {
        socket();
        // Do not let cancellation of one caller's wait cancel the ordering chain.
        sending = sending.handle((ignored, error) -> null).thenCompose(ignored -> {
            WebSocket open = socket();
            return binaryMessage ? open.sendBinary(ByteBuffer.wrap(bytes), true)
                    : open.sendText(new String(bytes, StandardCharsets.UTF_8), true);
        });
        return sending.copy();
    }

    private void publishNotifications() {
        while (true) {
            SampleResult notification;
            synchronized (this) {
                notification = notifications.poll();
            }
            if (notification == null) {
                return;
            }
            results.accept(notification);
        }
    }

    synchronized CompletableFuture<SampleResult> waitForMessage(Pattern pattern) {
        return waitForMatchingMessage(pattern == null ? result -> true
                : result -> SampleResult.TEXT.equals(result.getDataType())
                        && pattern.matcher(result.getResponseDataAsString()).find());
    }

    synchronized CompletableFuture<SampleResult> waitForMatchingMessage(Predicate<SampleResult> matcher) {
        socket();
        if (response != null) {
            throw new IllegalStateException("A message wait is already active for session: " + name);
        }
        response = new CompletableFuture<>();
        responseMatcher = matcher;
        return response;
    }

    synchronized void cancelWait(CompletableFuture<SampleResult> wait) {
        if (response == wait) {
            response = null;
            responseMatcher = null;
        }
        wait.cancel(false);
    }

    private void failWait(String message) {
        if (response != null) {
            response.completeExceptionally(new IOException(message));
            response = null;
            responseMatcher = null;
        }
    }

    private void control(String kind, ByteBuffer data) {
        if (countIncoming && !ignoreControlFrames && !disposed && !terminated) {
            SampleResult result = result(kind, true, "200", "OK");
            result.sampleStart();
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            result.setResponseData(bytes);
            result.setDataType(SampleResult.BINARY);
            result.sampleEnd();
            notifications.add(result);
        }
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer data) {
        try {
            return handlePing(webSocket, data);
        } finally {
            publishNotifications();
        }
    }

    private synchronized CompletionStage<?> handlePing(WebSocket webSocket, ByteBuffer data) {
        control("ping", data);
        webSocket.request(1);
        return null; // The JDK automatically sends pong replies.
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer data) {
        try {
            return handlePong(webSocket, data);
        } finally {
            publishNotifications();
        }
    }

    private synchronized CompletionStage<?> handlePong(WebSocket webSocket, ByteBuffer data) {
        control("pong", data);
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        try {
            return handleClose(statusCode, reason);
        } finally {
            publishNotifications();
        }
    }

    private synchronized CompletionStage<?> handleClose(int statusCode, String reason) {
        terminate(Integer.toString(statusCode), reason);
        closed.complete(null);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        try {
            handleError(error);
        } finally {
            publishNotifications();
        }
    }

    private synchronized void handleError(Throwable error) {
        terminate("WS_ERROR", error.toString());
        closed.completeExceptionally(error);
    }

    private void terminate(String code, String message) {
        if (socket != null && !terminated && !disposed && !expectedClose && failOnDisconnect) {
            SampleResult result = result("unexpected disconnect", false, code, message);
            result.sampleStart();
            result.sampleEnd();
            notifications.add(result);
        }
        terminated = true;
        failWait("WebSocket session disconnected: " + code + " " + message);
        text.setLength(0);
        binary.reset();
        incoming = null;
    }

    synchronized CompletableFuture<Void> close() {
        if (terminated) {
            return closed;
        }
        WebSocket open = socket();
        expectedClose = true; // Set before sendClose: the peer can answer immediately.
        failWait("WebSocket session is closing: " + name);
        return open.sendClose(WebSocket.NORMAL_CLOSURE, "").thenCompose(ignored -> closed);
    }

    synchronized void dispose() {
        disposed = true;
        expectedClose = true;
        failWait("WebSocket session was stopped: " + name);
        if (socket != null) {
            socket.abort();
        }
        closed.cancel(false);
        text.setLength(0);
        binary.reset();
        incoming = null;
    }

    private SampleResult result(String operation, boolean successful, String code, String message) {
        SampleResult result = new SampleResult();
        result.setSampleLabel("WebSocket " + name + " " + operation);
        result.setSuccessful(successful);
        result.setResponseCode(code);
        result.setResponseMessage(message);
        return result;
    }
}
