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

package org.apache.jmeter.protocol.http.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http2.hpack.HPackEncoder;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.apache.jmeter.junit.JMeterTestCase;
import org.junit.jupiter.api.Test;

class Http2ProxyRelayTest extends JMeterTestCase {
    @Test
    void stoppingAnOpenHttp2SseStreamRetainsCompleteEventsWithoutFailingCapture() throws Exception {
        byte[] requests = concat(Http2ProxyRelay.PREFACE, frame(1, 5, 1, headers(
                new HPackEncoder(4096, StandardCharsets.ISO_8859_1), ":method", "GET", ":scheme", "https",
                ":authority", "example.test", ":path", "/events")));
        byte[] responses = concat(frame(1, 4, 1, headers(new HPackEncoder(4096, StandardCharsets.ISO_8859_1),
                ":status", "200", "content-type", "text/event-stream")),
                frame(0, 0, 1, "data: complete\n\ndata: unfinished".getBytes(StandardCharsets.UTF_8)));
        var captures = exchange(requests, responses, new RecordingDiagnostics(), () -> null, () -> { }, false, true);
        assertEquals(1, captures.size());
        assertEquals("", captures.get(0).transportError());
        assertEquals(1, captures.get(0).sse.events().size());
        assertEquals("complete", captures.get(0).sse.events().get(0).data());
    }

    @Test
    void recordsSseEventsAcrossHttp2DataFrames() throws Exception {
        var encoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        byte[] requests = concat(Http2ProxyRelay.PREFACE, frame(1, 5, 1, headers(encoder,
                ":method", "GET", ":scheme", "https", ":authority", "example.test", ":path", "/events")));
        byte[] first = "id: 7\nevent: update\ndata: caf".getBytes(StandardCharsets.UTF_8);
        byte[] last = "é\n\ndata: next\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] responses = concat(frame(1, 4, 1, headers(new HPackEncoder(4096, StandardCharsets.ISO_8859_1),
                ":status", "200", "content-type", "text/event-stream")), frame(0, 0, 1, first), frame(0, 1, 1, last));
        var captures = exchange(requests, responses);
        assertEquals(1, captures.size());
        var capture = captures.get(0);
        assertEquals("", capture.transportError());
        assertEquals(2, capture.sse.events().size());
        assertEquals("café", capture.sse.events().get(0).data());
        assertEquals("7", capture.sse.events().get(1).eventId());
        assertArrayEquals(responses, capture.responseWire());
    }

    @Test
    void capturesWebSocketFramesInsideExtendedConnectData() throws Exception {
        var encoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        byte[] request = concat(Http2ProxyRelay.PREFACE, frame(1, 4, 1, headers(encoder,
                ":method", "CONNECT", ":protocol", "websocket", ":scheme", "https", ":authority", "example.test", ":path", "/socket")));
        byte[] message = WebSocketProxyRecorderTest.frame(129, false, "hello".getBytes(StandardCharsets.UTF_8));
        byte[] response = concat(frame(1, 4, 1, headers(new HPackEncoder(4096, StandardCharsets.ISO_8859_1), ":status", "200")),
                frame(0, 1, 1, message), frame(3, 0, 1, ByteBuffer.allocate(4).putInt(0).array()));
        var captures = exchange(request, response);
        assertEquals(1, captures.size());
        var capture = captures.get(0);
        assertEquals("", capture.transportError());
        assertEquals("hello", capture.webSocket.messages().get(0).text());
        assertEquals("receive", capture.webSocket.messages().get(0).direction());
        assertArrayEquals(message, capture.webSocket.wire(false));
    }

    @Test
    void identifiesBrowserStreamCancellation() throws Exception {
        var encoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        byte[] requests = concat(Http2ProxyRelay.PREFACE,
                frame(1, 4, 1, headers(encoder, ":method", "GET", ":scheme", "https", ":authority", "example.test", ":path", "/")),
                frame(3, 0, 1, ByteBuffer.allocate(4).putInt(8).array()));
        var captures = exchange(requests, frame(4, 0, 0, new byte[0]));
        assertEquals(1, captures.size());
        assertTrue(captures.get(0).transportError().startsWith("Browser cancelled/reset stream:"));
    }

    @Test
    void preservesFramesCompressionContinuationsAndStreamResets() throws Exception {
        HPackEncoder requestEncoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        HPackEncoder responseEncoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        byte[] first = headers(requestEncoder, ":method", "GET", ":scheme", "https", ":authority", "example.test",
                ":path", "/compressed", "x-shared", "same-value");
        byte[] second = headers(requestEncoder, ":method", "GET", ":scheme", "https", ":authority", "example.test",
                ":path", "/reset", "x-shared", "same-value");
        byte[] request1 = concat(frame(1, 1, 1, Arrays.copyOfRange(first, 0, 2)),
                frame(9, 4, 1, Arrays.copyOfRange(first, 2, first.length)));
        byte[] request3 = frame(1, 5, 3, second);
        byte[] requests = concat(Http2ProxyRelay.PREFACE, frame(4, 0, 0, new byte[0]), request1, request3);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("original compressed response".getBytes(StandardCharsets.UTF_8));
        }
        byte[] response1 = concat(frame(1, 4, 1, headers(responseEncoder, ":status", "200",
                "content-type", "text/plain", "content-encoding", "gzip", "x-shared", "same-value")),
                frame(0, 1, 1, compressed.toByteArray()));
        byte[] response3 = concat(frame(1, 4, 3, headers(responseEncoder, ":status", "200",
                "content-type", "text/plain", "x-shared", "same-value")),
                frame(0, 0, 3, "partial".getBytes(StandardCharsets.UTF_8)),
                frame(3, 0, 3, ByteBuffer.allocate(4).putInt(2).array()));
        byte[] responses = concat(frame(4, 0, 0, new byte[0]), response1, response3);
        var captures = exchange(requests, responses);
        assertEquals(2, captures.size());
        var success = captures.get(0);
        assertArrayEquals(request1, success.requestWire());
        assertArrayEquals(response1, success.responseWire());
        assertEquals("original compressed response", success.getResponseDataAsString());
        assertTrue(success.getResponseHeaders().contains("content-encoding: gzip"));
        assertEquals("", success.transportError());
        var failure = captures.get(1);
        assertArrayEquals(request3, failure.requestWire());
        assertArrayEquals(response3, failure.responseWire());
        assertEquals("partial", failure.getResponseDataAsString());
        assertTrue(failure.transportError().contains("Server reset stream: HTTP/2 RST_STREAM error 2"));
    }

    @Test
    void lateTrailersDoNotRecreateResetStreamsOrDesynchronizeHpack() throws Exception {
        var encoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        byte[] requests = concat(Http2ProxyRelay.PREFACE,
                frame(1, 4, 1, headers(encoder, ":method", "POST", ":scheme", "https", ":authority", "example.test", ":path", "/1")),
                frame(3, 0, 1, ByteBuffer.allocate(4).putInt(8).array()),
                frame(1, 5, 1, headers(encoder, "x-trailer", "retained-hpack-value")),
                frame(1, 5, 3, headers(encoder, ":method", "GET", ":scheme", "https", ":authority", "example.test",
                        ":path", "/fonts?family=Roboto|Open+Sans", "x-trailer", "retained-hpack-value")));
        byte[] responses = frame(1, 5, 3, headers(new HPackEncoder(4096, StandardCharsets.ISO_8859_1), ":status", "200"));
        var diagnostics = new RecordingDiagnostics();
        var captures = exchange(requests, responses, diagnostics, () -> null, () -> { }, true);
        assertEquals(2, captures.size());
        assertEquals("200", captures.get(1).getResponseCode());
        assertTrue(captures.get(1).getRequestHeaders().contains("Roboto|Open+Sans"));
        assertEquals("", diagnostics.details());
    }

    @Test
    void inspectionFailureIsVisibleEvenWhenNoSamplerCanBeCreated() throws Exception {
        var diagnostics = new RecordingDiagnostics();
        byte[] requests = concat(Http2ProxyRelay.PREFACE,
                frame(1, 5, 1, new byte[]{(byte) 0x80}), frame(1, 5, 3, new byte[]{(byte) 0x80}));
        byte[] responses = frame(4, 0, 0, new byte[0]);
        var captures = exchange(requests, responses, diagnostics, () -> null, () -> { }, false);
        assertTrue(captures.isEmpty());
        assertTrue(diagnostics.details().contains("inspection failed"));
        assertTrue(diagnostics.summary().contains("Incomplete: 2"));
        assertTrue(diagnostics.pending());
        diagnostics.reviewed();
        assertTrue(!diagnostics.pending());
        assertTrue(diagnostics.details().contains("inspection failed"), "Review must not erase the warning");
    }

    @Test
    void snapshotsHttp2SettingsBeforeAsynchronousInspection() throws Exception {
        var original = new RecordingRequestSettings(null, "before", "", 0, "", false, true, 4, false, false, true, false, false, 5000, true);
        var changed = new RecordingRequestSettings(null, "after", "", 0, "", false, false, 0, false, true, true, false, false, 5000, true);
        var settings = new java.util.concurrent.atomic.AtomicReference<>(original);
        var requestEncoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        var responseEncoder = new HPackEncoder(4096, StandardCharsets.ISO_8859_1);
        byte[] requests = concat(Http2ProxyRelay.PREFACE,
                frame(1, 5, 1, headers(requestEncoder, ":method", "GET", ":scheme", "https", ":authority", "example.test", ":path", "/1")),
                frame(1, 5, 3, headers(requestEncoder, ":method", "GET", ":scheme", "https", ":authority", "example.test", ":path", "/2")));
        byte[] responses = concat(frame(1, 5, 1, headers(responseEncoder, ":status", "200")),
                frame(1, 5, 3, headers(responseEncoder, ":status", "200")));
        var diagnostics = new RecordingDiagnostics();
        var captures = exchange(requests, responses, diagnostics, settings::get, () -> settings.set(changed), true);
        assertEquals("", diagnostics.details(), "Closing a completed connection must not report lost captures");
        assertEquals(2, captures.size());
        assertEquals(original, captures.get(1).request().settings);
    }

    private static List<HttpProxyTransport.Capture> exchange(byte[] requests, byte[] responses) throws Exception {
        return exchange(requests, responses, new RecordingDiagnostics(), () -> null, () -> { }, true);
    }

    private static List<HttpProxyTransport.Capture> exchange(byte[] requests, byte[] responses, RecordingDiagnostics diagnostics,
            java.util.function.Supplier<RecordingRequestSettings> settings, Runnable forwarded, boolean expectCapture) throws Exception {
        return exchange(requests, responses, diagnostics, settings, forwarded, expectCapture, false);
    }

    private static List<HttpProxyTransport.Capture> exchange(byte[] requests, byte[] responses, RecordingDiagnostics diagnostics,
            java.util.function.Supplier<RecordingRequestSettings> settings, Runnable forwarded, boolean expectCapture, boolean stopped) throws Exception {
        try (ServerSocket origin = new ServerSocket(0); ServerSocket listener = new ServerSocket(0);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var releaseRecorder = new java.util.concurrent.CountDownLatch(1);
            var enteredRecorder = new java.util.concurrent.CountDownLatch(1);
            var relay = workers.submit(() -> {
                List<HttpProxyTransport.Capture> captures = new ArrayList<>();
                try (Socket browser = listener.accept(); Socket server = new Socket("localhost", origin.getLocalPort())) {
                    browser.setSoTimeout(5000);
                    server.setSoTimeout(5000);
                    new Http2ProxyRelay(capture -> {
                        captures.add(capture);
                        if (captures.size() == 1) {
                            enteredRecorder.countDown();
                            try {
                                assertTrue(releaseRecorder.await(10, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        }
                    }, settings, diagnostics, () -> stopped).relay(browser, browser.getInputStream(), server, server.getInputStream());
                }
                return captures;
            });
            try (Socket browser = new Socket("localhost", listener.getLocalPort()); Socket server = origin.accept()) {
                browser.setSoTimeout(5000);
                server.setSoTimeout(5000);
                browser.getOutputStream().write(requests);
                assertArrayEquals(requests, server.getInputStream().readNBytes(requests.length));
                server.getOutputStream().write(responses);
                assertArrayEquals(responses, browser.getInputStream().readNBytes(responses.length));
                forwarded.run();
                if (expectCapture) {
                    assertTrue(enteredRecorder.await(5, TimeUnit.SECONDS));
                }
            } finally {
                releaseRecorder.countDown();
            }
            return relay.get(10, TimeUnit.SECONDS);
        }
    }

    private static byte[] headers(HPackEncoder encoder, String... pairs) throws Exception {
        var headers = new ArrayList<BasicHeader>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new BasicHeader(pairs[i], pairs[i + 1]));
        }
        ByteArrayBuffer buffer = new ByteArrayBuffer(128);
        encoder.encodeHeaders(buffer, headers, true);
        return buffer.toByteArray();
    }

    private static byte[] frame(int type, int flags, int stream, byte[] payload) {
        return ByteBuffer.allocate(9 + payload.length).put((byte) (payload.length >> 16))
                .put((byte) (payload.length >> 8)).put((byte) payload.length).put((byte) type).put((byte) flags)
                .putInt(stream).put(payload).array();
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bytes.write(part);
        }
        return bytes.toByteArray();
    }
}
