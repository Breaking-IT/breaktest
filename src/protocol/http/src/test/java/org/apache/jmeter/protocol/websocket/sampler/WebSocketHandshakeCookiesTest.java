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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

class WebSocketHandshakeCookiesTest {
    private static final URI WS = URI.create("wss://example.test/chat");
    private static final URI HTTP = URI.create("https://example.test/chat");

    @Test
    void responseOrderDoesNotAffectOwnershipAndClosedCapturesIgnoreLateCookies() throws Exception {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        try (var alice = bridge.begin(WS)) {
            request(bridge, alice, "alice-key");
            try (var bob = bridge.begin(WS)) {
                request(bridge, bob, "bob-key");
                bridge.put(HTTP, response("bob-key", "user=bob"));
                bridge.put(URI.create("https://other.test/chat"), response("alice-key", "wrong=host"));
                bridge.put(HTTP, response("unknown-key", "wrong=key"));
                bridge.put(HTTP, response("alice-key", "user=alice"));
                assertEquals(List.of("user=alice"), alice.cookies());
                assertEquals(List.of("user=bob"), bob.cookies());
            }
        }
        try (var next = bridge.begin(WS)) {
            request(bridge, next, "next-key");
            bridge.put(HTTP, response("alice-key", "late=alice"));
            assertTrue(next.cookies().isEmpty());
        }
    }

    @Test
    void stalledHandshakeNeverBlocksOrCapturesAnotherUsersHandshake() throws Exception {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        Queue<Runnable> transportTasks = new ArrayDeque<>();
        Executor executor = WebSocketHandshakeCookies.ownerAwareExecutor(transportTasks::add);
        try (var alice = bridge.begin(WS); var bob = bridge.begin(WS)) {
            // Alice's request filter has not run yet when Bob starts and completes his handshake.
            submitFilter(alice, executor, bridge, "alice-key");
            submitFilter(bob, executor, bridge, "bob-key");
            Runnable aliceTask = transportTasks.poll();
            transportTasks.poll().run();
            bridge.put(HTTP, response("bob-key", "user=bob"));
            assertEquals(List.of("user=bob"), bob.cookies());
            aliceTask.run();
            bridge.put(HTTP, response("alice-key", "user=alice"));
            assertEquals(List.of("user=alice"), alice.cookies());
        }
    }

    @Test
    void cancelledHandshakeKeepsLateRequestAwayFromOtherUsers() throws Exception {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        Queue<Runnable> transportTasks = new ArrayDeque<>();
        Executor executor = WebSocketHandshakeCookies.ownerAwareExecutor(transportTasks::add);
        var cancelled = bridge.begin(WS);
        submitFilter(cancelled, executor, bridge, "cancelled-key");
        cancelled.close();
        try (var next = bridge.begin(WS)) {
            request(bridge, next, "next-key");
            transportTasks.poll().run(); // The late filter still belongs to the cancelled capture.
            bridge.put(HTTP, response("cancelled-key", "late=ignored"));
            bridge.put(HTTP, response("next-key", "user=next"));
            assertTrue(cancelled.cookies().isEmpty());
            assertEquals(List.of("user=next"), next.cookies());
        }
    }

    @Test
    void requestWithoutItsOwnerIsRejectedInsteadOfGuessed() {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        try (var pending = bridge.begin(WS)) {
            assertThrows(IOException.class,
                    () -> bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of("orphan-key"))));
            WebSocketHandshakeCookies otherClient = new WebSocketHandshakeCookies();
            assertThrows(UncheckedIOException.class, () -> request(otherClient, pending, "other-client-key"));
            assertTrue(pending.cookies().isEmpty());
        }
    }

    @Test
    void registeredFailureAllowsFurtherRequestsWithoutKeepingCookieCaptures() throws Exception {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        for (int i = 0; i < 100; i++) {
            String key = "rejected-" + i;
            try (var capture = bridge.begin(WS)) {
                request(bridge, capture, key);
            }
            bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of(key))); // Late retry is harmless.
        }
        var field = WebSocketHandshakeCookies.class.getDeclaredField("responses");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(bridge)).isEmpty());
    }

    /** Runs the request filter as the JDK does when it is applied on the submitting thread. */
    private static void request(WebSocketHandshakeCookies bridge, WebSocketHandshakeCookies.Capture capture,
            String key) {
        capture.start(() -> {
            filter(bridge, key);
            return null;
        });
    }

    /** Submits the request filter as the JDK does from {@code buildAsync}; it runs later. */
    private static void submitFilter(WebSocketHandshakeCookies.Capture capture, Executor executor,
            WebSocketHandshakeCookies bridge, String key) {
        capture.start(() -> {
            executor.execute(() -> filter(bridge, key));
            return null;
        });
    }

    private static void filter(WebSocketHandshakeCookies bridge, String key) {
        try {
            bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of(key)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, List<String>> response(String key, String cookie) throws Exception {
        return Map.of("sec-websocket-accept", List.of(WebSocketHandshakeCookies.acceptFor(key)),
                "set-cookie", List.of(cookie));
    }
}
