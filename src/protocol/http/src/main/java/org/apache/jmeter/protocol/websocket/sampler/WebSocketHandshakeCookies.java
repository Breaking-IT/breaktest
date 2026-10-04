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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Captures upgrade cookies without installing a shared cookie store. */
final class WebSocketHandshakeCookies extends CookieHandler {
    // Serialize only until the request filter exposes the generated handshake key.
    // Network handshakes proceed concurrently once their keys have been registered.
    private final Semaphore requestSlot = new Semaphore(1);
    private final Map<String, Capture> responses = new ConcurrentHashMap<>();
    // Retried requests retain their original header key. Weak keys avoid retaining
    // completed/cancelled requests for the lifetime of a shared transport client.
    private final Map<String, Boolean> registeredRequests = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile Capture starting;
    private volatile boolean accepting = true;

    boolean acceptsHandshakes() {
        return accepting;
    }

    Capture begin(URI uri, long timeoutMillis) throws InterruptedException, TimeoutException {
        if (!accepting) {
            throw new IllegalStateException("WebSocket handshake transport was retired");
        }
        if (!requestSlot.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timed out registering WebSocket handshake cookies");
        }
        if (!accepting) {
            requestSlot.release();
            throw new IllegalStateException("WebSocket handshake transport was retired");
        }
        Capture capture = new Capture(httpUri(uri));
        starting = capture;
        return capture;
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
        Capture capture = starting;
        if (capture == null || !capture.uri.equals(uri)) {
            throw new IOException("Unregistered WebSocket handshake");
        }
        synchronized (capture) {
            if (capture.registered) {
                return Map.of();
            }
            if (!capture.closed) {
                capture.accept = accept;
                if (responses.putIfAbsent(accept, capture) != null) {
                    throw new IOException("Duplicate WebSocket handshake key");
                }
            }
            registeredRequests.put(key, Boolean.TRUE);
            starting = null;
            capture.registered = true;
            requestSlot.release();
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
                headers.forEach((name, values) -> {
                    if ("Set-Cookie".equalsIgnoreCase(name)) {
                        capture.cookies.addAll(values);
                    }
                });
            }
        }
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
        private boolean registered;
        private boolean closed;

        private Capture(URI uri) {
            this.uri = uri;
        }

        synchronized List<String> cookies() {
            return List.copyOf(cookies);
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (accept != null) {
                responses.remove(accept, this);
            }
            if (!registered) {
                // An unregistered request filter may still arrive after cancellation.
                // Never assign that late request to another user's capture. Existing
                // sockets stay usable; the pool creates a fresh client for new connects.
                accepting = false;
            }
            cookies.clear();
        }
    }
}
