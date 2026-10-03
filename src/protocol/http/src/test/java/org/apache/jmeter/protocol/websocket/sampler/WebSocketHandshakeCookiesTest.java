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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class WebSocketHandshakeCookiesTest {
    private static final URI WS = URI.create("wss://example.test/chat");
    private static final URI HTTP = URI.create("https://example.test/chat");

    @Test
    void responseOrderDoesNotAffectOwnershipAndClosedCapturesIgnoreLateCookies() throws Exception {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        try (var alice = bridge.begin(WS, 100)) {
            bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of("alice-key")));
            try (var bob = bridge.begin(WS, 100)) {
                bridge.get(HTTP, Map.of("sec-websocket-key", List.of("bob-key")));
                bridge.put(HTTP, response("bob-key", "user=bob"));
                bridge.put(URI.create("https://other.test/chat"), response("alice-key", "wrong=host"));
                bridge.put(HTTP, response("unknown-key", "wrong=key"));
                bridge.put(HTTP, response("alice-key", "user=alice"));
                assertEquals(List.of("user=alice"), alice.cookies());
                assertEquals(List.of("user=bob"), bob.cookies());
                alice.succeeded();
                bob.succeeded();
            }
        }
        try (var next = bridge.begin(WS, 100)) {
            bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of("next-key")));
            bridge.put(HTTP, response("alice-key", "late=alice"));
            assertTrue(next.cookies().isEmpty());
            next.succeeded();
        }
        assertTrue(bridge.acceptsHandshakes());
    }

    @Test
    void cancellationBeforeRequestFilterRetiresTransportAndNeverReassignsLateRequest() throws Exception {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        var cancelled = bridge.begin(WS, 100);
        assertThrows(TimeoutException.class, () -> bridge.begin(WS, 1));
        cancelled.close();
        assertFalse(bridge.acceptsHandshakes());
        assertThrows(IllegalStateException.class, () -> bridge.begin(WS, 1));
        bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of("cancelled-key")));
        bridge.put(HTTP, response("cancelled-key", "late=ignored"));
        assertTrue(cancelled.cookies().isEmpty());
    }

    @Test
    void cancelledRegisteredRequestCannotStealAnotherUsersRegistrationOnRetry() throws Exception {
        WebSocketHandshakeCookies bridge = new WebSocketHandshakeCookies();
        var first = bridge.begin(WS, 100);
        bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of("first-key")));
        try (var second = bridge.begin(WS, 100)) {
            first.close();
            bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of("first-key")));
            bridge.get(HTTP, Map.of("Sec-WebSocket-Key", List.of("second-key")));
            bridge.put(HTTP, response("first-key", "late=first"));
            bridge.put(HTTP, response("second-key", "user=second"));
            assertTrue(first.cookies().isEmpty());
            assertEquals(List.of("user=second"), second.cookies());
            second.succeeded();
        }
    }

    private static Map<String, List<String>> response(String key, String cookie) throws Exception {
        return Map.of("sec-websocket-accept", List.of(WebSocketHandshakeCookies.acceptFor(key)),
                "set-cookie", List.of(cookie));
    }
}
