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

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Captures upgrade cookies without installing a shared cookie store.
 * <p>
 * The JDK generates the handshake key internally and applies request filters to the
 * first handshake request in an executor task submitted by the thread that calls
 * {@code buildAsync}. The client's executor carries the submitting user's capture into
 * exactly those tasks, so each request is attributed to its own user and concurrent
 * users sharing a client never wait for each other.
 */
final class WebSocketHandshakeCookies extends CookieHandler {
    private static final ThreadLocal<Capture> SUBMITTING = new ThreadLocal<>();
    private static final ThreadLocal<Capture> RUNNING = new ThreadLocal<>();
    private final Map<String, Capture> responses = new ConcurrentHashMap<>();
    // Retried requests retain their original header key. Weak keys avoid retaining
    // completed/cancelled requests for the lifetime of a shared transport client.
    private final Map<String, Boolean> registeredRequests = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Wraps a client's executor so tasks submitted while a user starts a handshake run
     * with that user's capture. Tasks submitted later, by JDK threads, carry no owner.
     */
    static Executor ownerAwareExecutor(Executor delegate) {
        return task -> {
            Capture owner = SUBMITTING.get();
            if (owner == null) {
                delegate.execute(task);
                return;
            }
            delegate.execute(() -> {
                Capture previous = RUNNING.get();
                RUNNING.set(owner);
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        RUNNING.remove();
                    } else {
                        RUNNING.set(previous);
                    }
                }
            });
        };
    }

    Capture begin(URI uri) {
        return new Capture(httpUri(uri));
    }

    static URI httpUri(URI uri) {
        String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
        return URI.create(scheme + uri.toString().substring(uri.getScheme().length()));
    }

    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) throws IOException {
        String key = singleHeader(headers, "Sec-WebSocket-Key");
        if (key == null) {
            throw new IOException("Missing WebSocket handshake key");
        }
        String accept = acceptFor(key);
        if (registeredRequests.containsKey(key)) {
            return Map.of(); // Retry of an already registered request.
        }
        Capture capture = RUNNING.get();
        if (capture == null) {
            capture = SUBMITTING.get();
        }
        if (capture == null || capture.bridge() != this || !capture.uri.equals(uri)) {
            // Never guess an owner: attributing cookies to the wrong user is worse than failing.
            throw new IOException("Unregistered WebSocket handshake");
        }
        synchronized (capture) {
            if (capture.registered) {
                return Map.of();
            }
            if (!capture.closed) {
                capture.requestHeaders = formatRequestHeaders(uri, headers);
                capture.accept = accept;
                if (responses.putIfAbsent(accept, capture) != null) {
                    throw new IOException("Duplicate WebSocket handshake key");
                }
            }
            registeredRequests.put(key, Boolean.TRUE);
            capture.registered = true;
        }
        return Map.of(); // Outgoing cookies are already supplied by the user's Cookie Manager.
    }

    @Override
    public void put(URI uri, Map<String, List<String>> headers) {
        String accept = singleHeader(headers, "Sec-WebSocket-Accept");
        Capture capture = accept == null ? null : responses.get(accept.trim());
        if (capture == null || !capture.uri.equals(uri)) {
            return;
        }
        synchronized (capture) {
            if (!capture.closed) {
                capture.responseHeaders = formatHeaders(headers);
                headers.forEach((name, values) -> {
                    if ("Set-Cookie".equalsIgnoreCase(name)) {
                        capture.cookies.addAll(values);
                    }
                });
            }
        }
    }

    private static String formatRequestHeaders(URI uri, Map<String, List<String>> headers) {
        StringBuilder text = new StringBuilder(formatHeaders(headers));
        // CookieHandler sees user headers. The JDK adds these required transport
        // headers separately when serializing its HTTP/1.1 WebSocket upgrade.
        if (singleHeader(headers, "Host") == null) {
            int port = uri.getPort();
            boolean defaultPort = port == -1 || port == ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            text.append("Host: ").append(uri.getHost());
            if (!defaultPort) {
                text.append(':').append(port);
            }
            text.append("\n");
        }
        if (singleHeader(headers, "Connection") == null) {
            text.append("Connection: Upgrade\n");
        }
        if (singleHeader(headers, "Upgrade") == null) {
            text.append("Upgrade: websocket\n");
        }
        return text.toString();
    }

    private static String formatHeaders(Map<String, List<String>> headers) {
        StringBuilder text = new StringBuilder();
        headers.forEach((name, values) -> {
            if (name != null) {
                values.forEach(value -> text.append(name).append(": ").append(value).append("\n"));
            }
        });
        return text.toString();
    }

    private static String singleHeader(Map<String, List<String>> headers, String name) {
        for (var entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue().size() == 1) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    static String acceptFor(String key) throws IOException {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is required by the WebSocket protocol", e);
        }
    }

    final class Capture implements AutoCloseable {
        private final URI uri;
        private final List<String> cookies = new ArrayList<>();
        private String accept;
        private String requestHeaders = "";
        private String responseHeaders = "";
        private boolean registered;
        private boolean closed;

        private Capture(URI uri) {
            this.uri = uri;
        }

        private WebSocketHandshakeCookies bridge() {
            return WebSocketHandshakeCookies.this;
        }

        /** Starts the handshake request; its request filter is attributed to this capture. */
        <T> T start(Supplier<T> request) {
            Capture previous = SUBMITTING.get();
            SUBMITTING.set(this);
            try {
                return request.get();
            } finally {
                if (previous == null) {
                    SUBMITTING.remove();
                } else {
                    SUBMITTING.set(previous);
                }
            }
        }

        synchronized String requestHeaders() {
            return requestHeaders;
        }

        synchronized String responseHeaders() {
            return responseHeaders;
        }

        synchronized List<String> cookies() {
            return List.copyOf(cookies);
        }

        @Override
        public synchronized void close() {
            // A request filter arriving after cancellation still belongs to this capture,
            // which now ignores it; it can never be attributed to another user.
            closed = true;
            if (accept != null) {
                responses.remove(accept, this);
            }
            cookies.clear();
            requestHeaders = "";
            responseHeaders = "";
        }
    }
}
