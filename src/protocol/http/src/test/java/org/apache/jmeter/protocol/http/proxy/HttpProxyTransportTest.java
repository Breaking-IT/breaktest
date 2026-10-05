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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.junit.jupiter.api.Test;

class HttpProxyTransportTest extends JMeterTestCase {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://example.test/path", "https://example.test/path", "https://example.test:8443/path"})
    void fallbackSamplerUsesValidReplayPort(String address) throws Exception {
        var head = new HttpProxyTransport.Head(("GET " + address + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        var result = new HttpProxyTransport.Capture(head, head.url(null));
        var sampler = Proxy.fallbackSampler(result, new IllegalArgumentException("conversion failed"));
        sampler.setEnabled(true);
        assertEquals(address, sampler.getUrl().toString());
    }

    @Test
    void malformedRequestLineProduces502AndFailedCapture() throws Exception {
        try (ServerSocket origin = new ServerSocket(0); var transport = new HttpProxyTransport()) {
            var output = new ByteArrayOutputStream();
            var client = new Socket() {
                @Override public java.io.OutputStream getOutputStream() { return output; }
            };
            String address = "http://localhost:" + origin.getLocalPort() + "/";
            var head = new HttpProxyTransport.Head(("GET " + address + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            var capture = transport.forward(head, head.url(null), client, java.io.InputStream.nullInputStream());
            assertTrue(output.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 502 Bad Gateway"));
            assertTrue(capture.transportError().contains("Invalid HTTP request line"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void browserCancellationIsCleanButServerTruncationFailsSse(boolean gzip, boolean browserDisconnect) throws Exception {
        try (ServerSocket origin = new ServerSocket(0); var workers = Executors.newVirtualThreadPerTaskExecutor();
                var transport = new HttpProxyTransport()) {
            byte[] event = "data: complete\n\ndata: unfinished".getBytes(StandardCharsets.UTF_8);
            var compressed = new ByteArrayOutputStream();
            if (gzip) {
                var encoder = new GZIPOutputStream(compressed, true);
                encoder.write(event);
                encoder.flush();
                event = compressed.toByteArray(); // Intentionally no gzip trailer: browser cancels an open stream.
                encoder.close();
            }
            byte[] payload = event;
            var server = workers.submit(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    HttpProxyTransport.readHead(socket.getInputStream());
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                            + (gzip ? "Content-Encoding: gzip\r\n" : "")
                            + "Content-Length: " + (payload.length + 10) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(payload);
                    if (browserDisconnect) {
                        assertEquals(-1, socket.getInputStream().read());
                    }
                }
                return null;
            });
            var client = new Socket() {
                @Override public java.io.OutputStream getOutputStream() {
                    return new java.io.OutputStream() {
                        private int writes;
                        @Override public void write(int value) { }
                        @Override public void write(byte[] data, int offset, int length) throws java.io.IOException {
                            if (++writes > 1 && browserDisconnect) {
                                throw new java.net.SocketException("Browser closed connection");
                            }
                        }
                    };
                }
            };
            String address = "http://localhost:" + origin.getLocalPort() + "/events";
            var head = new HttpProxyTransport.Head(("GET " + address + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            var capture = transport.forward(head, head.url(null), client, java.io.InputStream.nullInputStream());
            server.get(5, TimeUnit.SECONDS);
            assertEquals(browserDisconnect, capture.transportError().isEmpty(), capture.transportError());
            assertEquals(browserDisconnect, capture.isSuccessful());
            assertEquals(1, capture.sse.events().size());
            assertEquals("complete", capture.sse.events().get(0).data());
        }
    }

    @Test
    void rawTargetsSurviveMetadataEscaping() throws Exception {
        String raw = "http://example.test/fonts?family=Roboto|Open+Sans&value=%2F{a}";
        var head = new HttpProxyTransport.Head(("GET " + raw + " HTTP/1.1\r\nHost: example.test\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        var url = head.url(null);
        assertEquals("/fonts?family=Roboto%7cOpen+Sans&value=%2F%7ba%7d", url.getFile());
        assertTrue(new String(head.forOrigin(url, false, ""), StandardCharsets.ISO_8859_1)
                .startsWith("GET /fonts?family=Roboto|Open+Sans&value=%2F{a} HTTP/1.1\r\n"));
        assertArrayEquals(head.bytes(), head.forOrigin(url, true, ""));
    }

    @Test
    void reconnectsAfterIdleUpstreamFinWithoutReplayingRequests() throws Exception {
        try (ServerSocket origin = new ServerSocket(0); ServerSocket listener = new ServerSocket(0);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            origin.setSoTimeout(5000);
            var closed = new java.util.concurrent.CountDownLatch(1);
            var server = workers.submit(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket socket = origin.accept()) {
                        socket.setSoTimeout(5000);
                        assertEquals("GET /" + i + " HTTP/1.1", HttpProxyTransport.readHead(socket.getInputStream()).firstLine());
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                    }
                    closed.countDown();
                }
                return null;
            });
            var relay = workers.submit(() -> {
                try (Socket browser = listener.accept(); var transport = new HttpProxyTransport()) {
                    browser.setSoTimeout(5000);
                    for (int i = 0; i < 2; i++) {
                        var head = HttpProxyTransport.readHead(browser.getInputStream());
                        var capture = transport.forward(head, head.url(null), browser, browser.getInputStream());
                        assertEquals("", capture.transportError());
                        assertEquals("200", capture.getResponseCode());
                    }
                }
                return null;
            });
            try (Socket browser = new Socket("localhost", listener.getLocalPort())) {
                browser.setSoTimeout(5000);
                for (int i = 0; i < 2; i++) {
                    browser.getOutputStream().write(("GET /" + i + " HTTP/1.1\r\nHost: localhost:"
                            + origin.getLocalPort() + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
                    assertTrue(closed.await(5, TimeUnit.SECONDS));
                }
            }
            relay.get(10, TimeUnit.SECONDS);
            server.get(10, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface Peer {
        void run(Socket socket) throws Exception;
    }

    private List<HttpProxyTransport.Capture> relay(Peer origin, Peer browser) throws Exception {
        try (ServerSocket server = new ServerSocket(0); ServerSocket listener = new ServerSocket(0);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(5000);
            listener.setSoTimeout(5000);
            var remote = workers.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    origin.run(socket);
                }
                return null;
            });
            var proxy = workers.submit(() -> {
                List<HttpProxyTransport.Capture> captures = new ArrayList<>();
                try (Socket socket = listener.accept(); HttpProxyTransport transport = new HttpProxyTransport()) {
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    HttpProxyTransport.Head head;
                    while ((head = HttpProxyTransport.readHead(input)) != null) {
                        var capture = transport.forward(head,
                                java.net.URI.create("http://localhost:" + server.getLocalPort() + head.target()).toURL(),
                                socket, input);
                        captures.add(capture);
                        if (capture.upgraded()) {
                            transport.tunnel(socket, input);
                            break;
                        }
                        if (!capture.keepAlive()) {
                            break;
                        }
                    }
                }
                return captures;
            });
            try (Socket socket = new Socket("localhost", listener.getLocalPort())) {
                socket.setSoTimeout(5000);
                browser.run(socket);
            }
            remote.get(10, TimeUnit.SECONDS);
            return proxy.get(10, TimeUnit.SECONDS);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void send(Socket socket, String value) throws Exception {
        socket.getOutputStream().write(bytes(value));
        socket.getOutputStream().flush();
    }

    @Test
    void snapshotsSettingsBeforeReadingTheRestOfTheRequestHeaders() throws Exception {
        var recorder = new ProxyControl();
        recorder.setTarget(new org.apache.jmeter.gui.tree.JMeterTreeNode());
        recorder.setPrefixHTTPSampleName("before");
        recorder.setHttpSampleNameFormat("original format");
        var input = new java.io.ByteArrayInputStream(bytes("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n")) {
            private int reads;

            @Override
            public synchronized int read() {
                if (++reads == 2) {
                    recorder.setPrefixHTTPSampleName("after");
                    recorder.setHttpSampleNameFormat("changed format");
                }
                return super.read();
            }
        };
        var head = HttpProxyTransport.readHead(input, () -> RecordingRequestSettings.capture(recorder));
        assertEquals("before", head.settings.prefix());
        assertEquals("original format", head.settings.format());
    }

    @Test
    void identifiesRecorderShutdownAsTheFailureCause() throws Exception {
        var transport = new HttpProxyTransport();
        transport.stop();
        try (var browser = new Socket()) {
            var request = new HttpProxyTransport.Head(bytes("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n"));
            var capture = transport.forward(request, java.net.URI.create("http://example.test/").toURL(),
                    browser, java.io.InputStream.nullInputStream());
            assertTrue(capture.transportError().startsWith("Recorder stopped:"));
        }
    }

    @Test
    void preservesGzipAndDuplicateHeadersAndPersistsExactMessages() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(bytes("hello"));
        }
        byte[] body = compressed.toByteArray();
        String request = "GET /test?a=%2f&a=+ HTTP/1.1\r\nHost: localhost\r\nX-Same: one\r\nX-Same: two\r\n\r\n";
        String headers = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nConnection: close\r\n\r\n";
        var captures = relay(server -> {
            assertArrayEquals(bytes(request), HttpProxyTransport.readHead(server.getInputStream()).bytes());
            send(server, headers);
            server.getOutputStream().write(body);
        }, browser -> {
            send(browser, request);
            assertArrayEquals(bytes(headers), HttpProxyTransport.readHead(browser.getInputStream()).bytes());
            assertArrayEquals(body, browser.getInputStream().readNBytes(body.length));
        });
        var capture = captures.get(0);
        assertEquals("hello", capture.getResponseDataAsString());
        var archive = RecordedExchangeStore.fromProxy(capture, capture.requestWire(), capture.responseWire(),
                capture.requestBody(), capture.transportError());
        var exchange = archive.resolveExchange(archive.exchangeIds().get(0)).orElseThrow();
        assertArrayEquals(bytes(request), Base64.getDecoder().decode(exchange.path("request").path("_breaktestWire").path("text").asText()));
        assertEquals("hello", exchange.path("response").path("content").path("text").asText());
        byte[] responseWire = Base64.getDecoder().decode(exchange.path("response").path("_breaktestWire").path("text").asText());
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(bytes(headers));
        expected.write(body);
        assertArrayEquals(expected.toByteArray(), responseWire);
    }

    @Test
    void preservesChunkedUploadExtensionsAndTrailers() throws Exception {
        String request = "POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n";
        String chunks = "3;custom=yes\r\na=b\r\n2\r\n c\r\n0\r\nX-Trailer: value\r\n\r\n";
        var captures = relay(server -> {
            assertArrayEquals(bytes(request), HttpProxyTransport.readHead(server.getInputStream()).bytes());
            assertArrayEquals(bytes(chunks), server.getInputStream().readNBytes(bytes(chunks).length));
            send(server, "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }, browser -> {
            send(browser, request + chunks);
            assertEquals("201", HttpProxyTransport.readHead(browser.getInputStream()).target());
        });
        assertArrayEquals(bytes(request + chunks), captures.get(0).requestWire());
        assertArrayEquals(bytes("a=b c"), captures.get(0).requestBody());
        assertEquals("", captures.get(0).transportError());
    }

    @Test
    void streamsResponseBeforeCompletionAndReusesTheConnection() throws Exception {
        String chunk = "5;part=1\r\nhello\r\n";
        var captures = relay(server -> {
            HttpProxyTransport.readHead(server.getInputStream());
            send(server, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: text/event-stream\r\n\r\n" + chunk);
            // The browser pipelines its next request only after receiving the first chunk.
            // The final chunk is gated externally below, so buffering the entire response would time out.
            assertTrue(firstChunkReceived.await(3, TimeUnit.SECONDS));
            send(server, "0\r\nX-End: yes\r\n\r\n");
            assertEquals("/second", HttpProxyTransport.readHead(server.getInputStream()).target());
            send(server, "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n");
        }, browser -> {
            send(browser, "GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n");
            HttpProxyTransport.readHead(browser.getInputStream());
            assertArrayEquals(bytes(chunk), browser.getInputStream().readNBytes(bytes(chunk).length));
            firstChunkReceived.countDown();
            assertArrayEquals(bytes("0\r\nX-End: yes\r\n\r\n"), browser.getInputStream().readNBytes(bytes("0\r\nX-End: yes\r\n\r\n").length));
            send(browser, "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals("204", HttpProxyTransport.readHead(browser.getInputStream()).target());
        });
        assertEquals(2, captures.size());
        assertEquals("hello", captures.get(0).getResponseDataAsString());
    }

    private final java.util.concurrent.CountDownLatch firstChunkReceived = new java.util.concurrent.CountDownLatch(1);

    @Test
    void relaysContinueAndEarlyRejectionWithoutWaitingForBody() throws Exception {
        var captures = relay(server -> {
            HttpProxyTransport.readHead(server.getInputStream());
            send(server, "HTTP/1.1 100 Continue\r\n\r\n");
            assertArrayEquals(bytes("a=1"), server.getInputStream().readNBytes(3));
            send(server, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
            HttpProxyTransport.readHead(server.getInputStream());
            send(server, "HTTP/1.1 413 Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }, browser -> {
            String request = "POST /post HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\n";
            send(browser, request);
            assertEquals("100", HttpProxyTransport.readHead(browser.getInputStream()).target());
            send(browser, "a=1");
            assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
            send(browser, request);
            assertEquals("413", HttpProxyTransport.readHead(browser.getInputStream()).target());
        });
        assertEquals(2, captures.size());
        assertEquals("", captures.get(1).transportError());
    }

    @Test
    void tunnelsWebSocketBytesAfterTheHandshake() throws Exception {
        var captures = relay(server -> {
            HttpProxyTransport.readHead(server.getInputStream());
            send(server, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n");
            assertArrayEquals(new byte[]{(byte) 0x81, 0}, server.getInputStream().readNBytes(2));
            server.getOutputStream().write(new byte[]{(byte) 0x88, 0});
        }, browser -> {
            send(browser, "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n");
            assertEquals("101", HttpProxyTransport.readHead(browser.getInputStream()).target());
            browser.getOutputStream().write(new byte[]{(byte) 0x81, 0});
            assertArrayEquals(new byte[]{(byte) 0x88, 0}, browser.getInputStream().readNBytes(2));
        });
        assertTrue(captures.get(0).upgraded());
    }

    @Test
    void retainsPartialResponseOnUpstreamReset() throws Exception {
        var captures = relay(server -> {
            HttpProxyTransport.readHead(server.getInputStream());
            send(server, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial");
        }, browser -> {
            send(browser, "GET /reset HTTP/1.1\r\nHost: localhost\r\n\r\n");
            HttpProxyTransport.readHead(browser.getInputStream());
            assertArrayEquals(bytes("partial"), browser.getInputStream().readAllBytes());
        });
        assertFalse(captures.get(0).isSuccessful());
        assertTrue(captures.get(0).transportError().contains("Incomplete HTTP body"));
        assertEquals("partial", captures.get(0).getResponseDataAsString());
    }

    @Test
    void doesNotReadABodyForHeadAndPreservesCloseDelimitedErrors() throws Exception {
        var captures = relay(server -> {
            assertEquals("HEAD", HttpProxyTransport.readHead(server.getInputStream()).method());
            send(server, "HTTP/1.1 200 OK\r\nContent-Length: 1234\r\n\r\n");
            assertEquals("GET", HttpProxyTransport.readHead(server.getInputStream()).method());
            send(server, "HTTP/1.1 500 Failed\r\nConnection: close\r\n\r\nserver error");
        }, browser -> {
            send(browser, "HEAD /test HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals("1234", HttpProxyTransport.readHead(browser.getInputStream()).value("Content-Length"));
            send(browser, "GET /test HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals("500", HttpProxyTransport.readHead(browser.getInputStream()).target());
            assertEquals("server error", new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });
        assertEquals(2, captures.size());
        assertEquals(0, captures.get(0).getResponseData().length);
        assertEquals("", captures.get(1).transportError());
    }

    @Test
    void retainsConnectionResetBeforeResponseHeaders() throws Exception {
        var captures = relay(server -> {
            HttpProxyTransport.readHead(server.getInputStream());
            server.setSoLinger(true, 0);
        }, browser -> {
            send(browser, "GET /reset HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals("502", HttpProxyTransport.readHead(browser.getInputStream()).target());
        });
        assertEquals("0", captures.get(0).getResponseCode());
        assertFalse(captures.get(0).transportError().isEmpty());
        assertFalse(captures.get(0).keepAlive());
    }

    @Test
    void forwardsIdenticalRepeatedContentLengths() throws Exception {
        var captures = relay(server -> {
            var head = HttpProxyTransport.readHead(server.getInputStream());
            assertEquals("3, 3", head.value("Content-Length"));
            assertArrayEquals(bytes("x=1"), server.getInputStream().readNBytes(3));
            send(server, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }, browser -> {
            send(browser, "POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\nx=1");
            assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
        });
        assertEquals("", captures.get(0).transportError());
    }

    @Test
    void honorsConfiguredUpstreamProxyWithoutLeakingBrowserProxyCredentials() throws Exception {
        String oldHost = System.getProperty("http.proxyHost");
        String oldPort = System.getProperty("http.proxyPort");
        String oldBypass = System.getProperty("http.nonProxyHosts");
        try (ServerSocket upstreamProxy = new ServerSocket(0); ServerSocket listener = new ServerSocket(0);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            upstreamProxy.setSoTimeout(5000);
            listener.setSoTimeout(5000);
            System.setProperty("http.proxyHost", "localhost");
            System.setProperty("http.proxyPort", Integer.toString(upstreamProxy.getLocalPort()));
            System.setProperty("http.nonProxyHosts", "");
            var remote = workers.submit(() -> {
                try (Socket socket = upstreamProxy.accept()) {
                    socket.setSoTimeout(5000);
                    var head = HttpProxyTransport.readHead(socket.getInputStream());
                    assertEquals("http://example.invalid/test?q=%2f", head.target());
                    assertFalse(head.headers().contains("browser-secret"));
                    assertEquals("", head.value("Proxy-Connection"));
                    send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                }
                return null;
            });
            var proxy = workers.submit(() -> {
                try (Socket socket = listener.accept(); HttpProxyTransport transport = new HttpProxyTransport()) {
                    var head = HttpProxyTransport.readHead(socket.getInputStream());
                    return transport.forward(head, head.url(null), socket, socket.getInputStream());
                }
            });
            try (Socket browser = new Socket("localhost", listener.getLocalPort())) {
                browser.setSoTimeout(5000);
                send(browser, "GET http://example.invalid/test?q=%2f HTTP/1.1\r\nHost: example.invalid\r\n"
                        + "Proxy-Authorization: browser-secret\r\nProxy-Connection: keep-alive\r\n\r\n");
                assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
            }
            remote.get(10, TimeUnit.SECONDS);
            assertEquals("", proxy.get(10, TimeUnit.SECONDS).transportError());
        } finally {
            restoreSystemProperty("http.proxyHost", oldHost);
            restoreSystemProperty("http.proxyPort", oldPort);
            restoreSystemProperty("http.nonProxyHosts", oldBypass);
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void forwardsMultipartBytesWithoutReconstruction() throws Exception {
        String body = "--original\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n\u0000\u00ff\r\n--original--\r\n";
        var captures = relay(server -> {
            var head = HttpProxyTransport.readHead(server.getInputStream());
            assertEquals("multipart/form-data; boundary=original", head.value("Content-Type"));
            assertArrayEquals(bytes(body), server.getInputStream().readNBytes(bytes(body).length));
            send(server, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }, browser -> {
            send(browser, "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=original\r\n"
                    + "Content-Length: " + bytes(body).length + "\r\n\r\n" + body);
            assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
        });
        assertArrayEquals(bytes(body), captures.get(0).requestBody());
    }
}
