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

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class WebSocketSessionTest extends JMeterTestCase {
    private final List<SampleResult> results = new ArrayList<>();

    private WebSocketSession session(boolean count, boolean fail, boolean ignoreControl, String text, String binary) {
        WebSocketSession session = new WebSocketSession("chat", count, fail, ignoreControl, text, binary, 32, results::add);
        session.onOpen(new SocketStub(session));
        return session;
    }

    @Test
    void listenerIsCalledOutsideSessionMonitor() {
        List<Boolean> monitorHeld = new ArrayList<>();
        WebSocketSession[] holder = new WebSocketSession[1];
        holder[0] = new WebSocketSession("chat", true, true, false, "", "", 32,
                result -> monitorHeld.add(Thread.holdsLock(holder[0])));
        WebSocketSession session = holder[0];
        session.onOpen(new SocketStub(session));
        session.onText(session.socket(), "data", true);
        session.onPing(session.socket(), ByteBuffer.allocate(0));
        session.onClose(session.socket(), 1000, "bye");
        assertEquals(List.of(false, false, false), monitorHeld);
    }

    @Test
    void sendsAreSerializedEvenIfCallerCancelsItsWait() {
        WebSocketSession session = session(false, true, true, "", "");
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<WebSocket> first = new CompletableFuture<>();
        SocketStub socket = new SocketStub(session) {
            @Override
            public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
                return calls.incrementAndGet() == 1 ? first : CompletableFuture.completedFuture(this);
            }
        };
        session.onOpen(socket);
        CompletableFuture<WebSocket> waiting = session.send("one".getBytes(StandardCharsets.UTF_8), false);
        CompletableFuture<WebSocket> second = session.send("two".getBytes(StandardCharsets.UTF_8), false);
        waiting.cancel(false);
        assertEquals(1, calls.get());
        assertFalse(second.isDone());
        first.complete(socket);
        assertTrue(second.isDone());
        assertEquals(2, calls.get());
    }

    @Test
    void assemblesAndFiltersCompleteTextMessages() {
        WebSocketSession session = session(true, true, true, "^heartbeat$", "");
        WebSocket socket = session.socket();
        session.onText(socket, "heart", false);
        assertTrue(results.isEmpty());
        session.onText(socket, "beat", true);
        assertTrue(results.isEmpty());
        session.onText(socket, "hello ", false);
        session.onText(socket, "world", true);
        assertEquals(1, results.size());
        assertEquals("hello world", results.get(0).getResponseDataAsString());
        assertTrue(results.get(0).isSuccessful());
    }

    @Test
    void assemblesBinaryAndFiltersPrefixAcrossFragments() {
        WebSocketSession session = session(true, true, true, "", "0102");
        WebSocket socket = session.socket();
        session.onBinary(socket, ByteBuffer.wrap(new byte[] {1}), false);
        session.onBinary(socket, ByteBuffer.wrap(new byte[] {2, 3}), true);
        assertTrue(results.isEmpty());
        session.onBinary(socket, ByteBuffer.wrap(new byte[] {4}), false);
        session.onBinary(socket, ByteBuffer.wrap(new byte[] {5}), true);
        assertEquals(1, results.size());
        assertEquals(SampleResult.BINARY, results.get(0).getDataType());
        assertEquals(2, results.get(0).getResponseData().length);
    }

    @Test
    void ignoreIncomingStillReportsDisconnect() {
        WebSocketSession session = session(false, true, false, "", "");
        WebSocket socket = session.socket();
        session.onText(socket, "ignore me", true);
        session.onBinary(socket, ByteBuffer.wrap(new byte[] {1}), true);
        session.onPing(socket, ByteBuffer.allocate(0));
        session.onPong(socket, ByteBuffer.allocate(0));
        assertTrue(results.isEmpty());
        session.onClose(socket, 1000, "server closed");
        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccessful(), "Even a normal server close is unexpected");
    }

    @Test
    void optionalDisconnectFailureAndDuplicateCallbacks() {
        WebSocketSession ignored = session(true, false, true, "", "");
        ignored.onClose(ignored.socket(), 1001, "gone");
        assertTrue(results.isEmpty());
        WebSocketSession failed = session(true, true, true, "", "");
        WebSocket socket = failed.socket();
        failed.onError(socket, new IllegalStateException("broken"));
        failed.onClose(socket, 1006, "gone");
        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccessful());
        assertThrows(IllegalStateException.class, failed::socket);
    }

    @Test
    void deliberateCloseCanCompleteSynchronouslyWithoutFailure() {
        WebSocketSession session = session(true, true, true, "", "");
        assertTrue(session.close().isDone());
        assertTrue(results.isEmpty());
        assertThrows(IllegalStateException.class, session::socket);
    }

    @Test
    void cleanupSuppressesLateCallbacksAndLateConnect() {
        WebSocketSession session = session(true, true, true, "", "");
        SocketStub socket = (SocketStub) session.socket();
        session.dispose();
        session.onError(socket, new IllegalStateException("shutdown"));
        session.onText(socket, "late", true);
        session.onOpen(socket);
        assertTrue(socket.aborted);
        assertTrue(results.isEmpty());
    }

    @Test
    void controlFramesAreOptionalAndDoNotBreakFragmentAssembly() {
        WebSocketSession session = session(true, true, false, "", "");
        WebSocket socket = session.socket();
        session.onText(socket, "a", false);
        session.onPing(socket, ByteBuffer.wrap(new byte[] {1}));
        session.onPong(socket, ByteBuffer.wrap(new byte[] {2}));
        session.onText(socket, "b", true);
        assertEquals(3, results.size());
        assertEquals("ab", results.get(2).getResponseDataAsString());
        WebSocketSession ignored = session(true, true, true, "", "");
        ignored.onPing(ignored.socket(), ByteBuffer.allocate(0));
        assertEquals(3, results.size());
    }

    @Test
    void oversizedMessageAbortsAndReportsFailureOnce() {
        WebSocketSession session = session(true, true, true, "", "");
        SocketStub socket = (SocketStub) session.socket();
        session.onText(socket, "a".repeat(20), false);
        session.onText(socket, "b".repeat(20), true);
        assertTrue(socket.aborted);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccessful());
    }

    @Test
    void sizeLimitCountsUtf8WhenSurrogatePairSpansFragments() {
        WebSocketSession session = session(true, true, true, "", "");
        SocketStub socket = (SocketStub) session.socket();
        session.onText(socket, "a".repeat(29) + "\uD83D", false);
        session.onText(socket, "\uDE00", true);
        assertTrue(socket.aborted);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccessful());
    }

    @Test
    void validatesFiltersBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> session(true, true, true, "[", ""));
        assertThrows(IllegalArgumentException.class, () -> session(true, true, true, "", "zz"));
    }

    @Test
    void waitsForMatchingCompleteMessageWithoutDoubleCounting() {
        WebSocketSession session = session(true, true, true, "^heartbeat$", "");
        WebSocket socket = session.socket();
        session.onText(socket, "hallo", true); // History cannot satisfy a new wait.
        CompletableFuture<SampleResult> wait = session.waitForMessage(Pattern.compile("^hallo$"));
        assertThrows(IllegalStateException.class, () -> session.waitForMessage(null));
        session.onText(socket, "heartbeat", true);
        session.onText(socket, "unrelated", true);
        session.onPing(socket, ByteBuffer.allocate(0));
        session.onText(socket, "hal", false);
        assertFalse(wait.isDone());
        session.onText(socket, "lo", true);
        assertEquals("hallo", wait.join().getResponseDataAsString());
        assertEquals(2, results.size());
        assertEquals("unrelated", results.get(1).getResponseDataAsString());
    }

    @Test
    void binaryWaitMatchesInsideCompleteMessagesAcrossFragments() {
        WebSocketSession session = session(true, true, true, "", "");
        WebSocket socket = session.socket();
        CompletableFuture<SampleResult> wait = session.waitForMatchingMessage(
                WebSocketBinary.matcher(WebSocketBinary.parse("07 95 03 80 A1 30 03 C0")));
        session.onText(socket, "07 95 03 80 A1 30 03 C0", true);
        session.onBinary(socket, ByteBuffer.wrap(WebSocketBinary.parse("07 95")), true);
        session.onBinary(socket, ByteBuffer.wrap(WebSocketBinary.parse("03 80 A1 30 03 C0")), true);
        assertFalse(wait.isDone()); // Separate messages must not be concatenated.
        session.onBinary(socket, ByteBuffer.wrap(WebSocketBinary.parse("FF 07 95 03")), false);
        assertFalse(wait.isDone());
        session.onBinary(socket, ByteBuffer.wrap(WebSocketBinary.parse("80 A1 30 03 C0 EE")), true);
        assertEquals("ff07950380a13003c0ee", java.util.HexFormat.of().formatHex(wait.join().getResponseData()));
        assertEquals(3, results.size()); // Matched response is consumed, not counted twice.
    }

    @Test
    void binaryMatcherHandlesOverlapsAndRejectsInvalidHex() {
        var matcher = WebSocketBinary.matcher(WebSocketBinary.parse("AA AA AB"));
        SampleResult sample = new SampleResult();
        sample.setDataType(SampleResult.BINARY);
        sample.setResponseData(WebSocketBinary.parse("AA AA AA AB"));
        assertTrue(matcher.test(sample));
        sample.setResponseData(WebSocketBinary.parse("AA AA"));
        assertFalse(matcher.test(sample));
        assertThrows(IllegalArgumentException.class, () -> WebSocketBinary.matcher(WebSocketBinary.parse(" \t\n")));
        assertThrows(IllegalArgumentException.class, () -> WebSocketBinary.parse("0 12"));
        assertThrows(IllegalArgumentException.class, () -> WebSocketBinary.parse("GG"));
    }

    @Test
    void nextMessageCanBeBinaryEvenWhenIncomingSamplesAreDisabled() {
        WebSocketSession session = session(false, true, true, "", "");
        CompletableFuture<SampleResult> wait = session.waitForMessage(null);
        session.onPong(session.socket(), ByteBuffer.allocate(0));
        assertFalse(wait.isDone());
        session.onBinary(session.socket(), ByteBuffer.wrap(new byte[] {42}), true);
        assertEquals(SampleResult.BINARY, wait.join().getDataType());
        assertEquals(42, wait.join().getResponseData()[0]);
        assertTrue(results.isEmpty());
    }

    @Test
    void cancelledWaitDoesNotConsumeLateMessagesAndCanBeReplaced() {
        WebSocketSession session = session(true, true, true, "", "");
        CompletableFuture<SampleResult> wait = session.waitForMessage(null);
        session.cancelWait(wait);
        session.onText(session.socket(), "late", true);
        assertEquals(1, results.size());
        CompletableFuture<SampleResult> replacement = session.waitForMessage(null);
        session.onText(session.socket(), "new", true);
        assertEquals("new", replacement.join().getResponseDataAsString());
    }

    @Test
    void disconnectFailsWaitEvenWhenDisconnectSamplesAreDisabled() {
        WebSocketSession session = session(false, false, true, "", "");
        CompletableFuture<SampleResult> wait = session.waitForMessage(null);
        session.onClose(session.socket(), 1000, "closed");
        assertTrue(wait.isCompletedExceptionally());
        assertTrue(results.isEmpty());
    }

    private static class SocketStub implements WebSocket {
        private final WebSocketSession listener;
        private boolean aborted;

        SocketStub(WebSocketSession listener) {
            this.listener = listener;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer data) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer data) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            listener.onClose(this, statusCode, reason);
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return aborted;
        }

        @Override
        public boolean isInputClosed() {
            return aborted;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
