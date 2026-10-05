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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http2.hpack.HPackDecoder;
import org.apache.hc.core5.http2.hpack.HPackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Relays HTTP/2 frames unchanged; HPACK decoding is only used to observe and record streams. */
final class Http2ProxyRelay {
    private static final Logger LOG = LoggerFactory.getLogger(Http2ProxyRelay.class);
    static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private final Consumer<HttpProxyTransport.Capture> recorder;
    private final ExecutorService observer = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("proxy-http2-observer").factory());
    private final Map<Integer, Stream> streams = new LinkedHashMap<>();
    private boolean observationFailed;
    private final java.util.function.Supplier<RecordingRequestSettings> settings;
    private final RecordingDiagnostics diagnostics;
    private final java.util.function.BooleanSupplier stopped;
    private final java.util.Set<Integer> observedRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Integer> completedStreams = new java.util.HashSet<>();
    private final java.util.Set<Integer> recordedRequests = new java.util.HashSet<>();
    private final java.util.concurrent.atomic.AtomicReference<String> closure = new java.util.concurrent.atomic.AtomicReference<>();
    private final Direction requests = new Direction();
    private final Direction responses = new Direction();

    Http2ProxyRelay(Consumer<HttpProxyTransport.Capture> recorder) {
        this(recorder, () -> null, new RecordingDiagnostics(), () -> false);
    }

    Http2ProxyRelay(Consumer<HttpProxyTransport.Capture> recorder,
            java.util.function.Supplier<RecordingRequestSettings> settings, RecordingDiagnostics diagnostics,
            java.util.function.BooleanSupplier stopped) {
        this.recorder = recorder;
        this.settings = settings;
        this.diagnostics = diagnostics;
        this.stopped = stopped;
    }

    void relay(Socket browser, InputStream browserInput, Socket server, InputStream serverInput) throws IOException {
        Thread upload = Thread.ofVirtual().name("proxy-http2-request").start(() -> {
            try {
                byte[] preface = browserInput.readNBytes(PREFACE.length);
                server.getOutputStream().write(preface);
                server.getOutputStream().flush();
                if (!Arrays.equals(preface, PREFACE)) {
                    throw new IOException("Invalid HTTP/2 client preface");
                }
                copyFrames(RecordingIo.input(browserInput, "Browser"), RecordingIo.output(server.getOutputStream(), "Server"), true);
            } catch (IOException e) {
                closure.compareAndSet(null, e.getMessage());
                if (!stopped.getAsBoolean() && (e instanceof EOFException || e.getMessage().equals("Invalid HTTP/2 client preface"))) {
                    diagnostics.issue(e.toString());
                }
                LOG.debug("HTTP/2 request connection ended", e);
            } finally {
                closure.compareAndSet(null, "Browser connection closed/reset");
                close(server);
            }
        });
        try {
            copyFrames(RecordingIo.input(serverInput, "Server"), RecordingIo.output(browser.getOutputStream(), "Browser"), false);
        } catch (IOException e) {
            closure.compareAndSet(null, e.getMessage());
            if (!stopped.getAsBoolean() && e instanceof EOFException) {
                diagnostics.issue(e.toString());
            }
            // The upload reader also closes this socket when the browser disconnects.
            // Finalize pending streams below; a cleanly completed connection is not an error.
            LOG.debug("HTTP/2 response connection ended", e);
        } finally {
            closure.compareAndSet(null, "Server connection closed/reset");
            close(server);
            close(browser);
            try {
                upload.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            double closedAt = HttpProxyTransport.captureTimeMillis();
            observer.close();
            finishIncomplete(closedAt);
            for (int id : observedRequests) {
                if (!recordedRequests.contains(id)) {
                    diagnostics.incomplete("HTTP/2 request stream " + id + " could not be captured; " + endReason());
                }
            }
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing either side interrupts both frame readers, including on recorder stop.
        }
    }

    private void copyFrames(InputStream input, OutputStream output, boolean request) throws IOException {
        while (true) {
            int first = input.read();
            if (first == -1) {
                return;
            }
            double receivedAt = HttpProxyTransport.captureTimeMillis();
            long sequence = HttpProxyTransport.nextSequence();
            byte[] rest = input.readNBytes(8);
            byte[] header = new byte[rest.length + 1];
            header[0] = (byte) first;
            System.arraycopy(rest, 0, header, 1, rest.length);
            if (header.length != 9) {
                output.write(header);
                output.flush();
                throw new EOFException((request ? "Browser" : "Server") + " sent an incomplete HTTP/2 frame header");
            }
            int length = (header[0] & 255) << 16 | (header[1] & 255) << 8 | header[2] & 255;
            int streamId = ByteBuffer.wrap(header, 5, 4).getInt() & 0x7fffffff;
            RecordingRequestSettings snapshot = (request && header[3] == 1 && observedRequests.add(streamId))
                    || (!request && header[3] == 0) ? settings.get() : null;
            byte[] payload = input.readNBytes(length);
            double completedAt = HttpProxyTransport.captureTimeMillis();
            byte[] frame = Arrays.copyOf(header, 9 + payload.length);
            System.arraycopy(payload, 0, frame, 9, payload.length);
            if (payload.length != length) {
                observer.execute(() -> {
                    try {
                        partial(request, streamId, frame, payload, header[3] & 255);
                    } catch (IOException e) {
                        diagnostics.issue("Unable to retain partial HTTP/2 frame: " + e);
                        LOG.warn("Unable to retain partial HTTP/2 frame", e);
                    }
                });
                output.write(frame);
                output.flush();
                throw new EOFException((request ? "Browser" : "Server") + " sent an incomplete HTTP/2 frame payload");
            }
            // Publish before forwarding so a fast response cannot overtake its request in the observer.
            observer.execute(() -> observeSafely(request, header[3] & 255, header[4] & 255,
                    streamId, payload, frame, receivedAt, completedAt, sequence, snapshot));
            output.write(frame);
            output.flush();
        }
    }

    private synchronized void observeSafely(boolean request, int type, int flags, int id, byte[] payload, byte[] frame,
            double receivedAt, double completedAt, long sequence, RecordingRequestSettings snapshot) {
        if (!observationFailed) {
            try {
                observe(request, type, flags, id, payload, frame, receivedAt, completedAt, sequence, snapshot);
            } catch (IOException | RuntimeException e) {
                observationFailed = true;
                diagnostics.issue("HTTP/2 inspection failed; traffic continues but this connection's recording is incomplete: " + e);
                LOG.warn("Unable to inspect HTTP/2 recording; continuing to relay original frames", e);
            }
        }
    }

    private synchronized void partial(boolean request, int id, byte[] frame, byte[] payload, int type) throws IOException {
        Stream stream = streams.get(id);
        if (stream != null) {
            (request ? stream.requestWire : stream.responseWire).write(frame);
            if (type == 0) {
                (request ? stream.requestBody : stream.responseBody).write(payload);
            }
        }
    }

    private synchronized void observe(boolean request, int type, int flags, int id, byte[] payload, byte[] frame,
            double receivedAt, double completedAt, long sequence, RecordingRequestSettings snapshot)
            throws IOException {
        Direction direction = request ? requests : responses;
        Stream stream = streams.get(id);
        if (request && type == 1 && stream == null && !completedStreams.contains(id)) {
            stream = new Stream(id, receivedAt, sequence);
            stream.settings = snapshot;
            streams.put(id, stream);
        }
        if (stream != null) {
            stream.lastFrameAt = completedAt;
            (request ? stream.requestWire : stream.responseWire).write(frame);
        }
        if (type == 1 || type == 5 || type == 9) { // HEADERS, PUSH_PROMISE, CONTINUATION
            if (type != 9) {
                direction.block.reset();
                direction.streamId = id;
                direction.push = type == 5;
                direction.endStream = (flags & 1) != 0 && type == 1;
            } else if (direction.streamId != id) {
                throw new IOException("Unexpected HTTP/2 CONTINUATION stream");
            }
            int offset = 0;
            int padding = 0;
            if (type != 9 && (flags & 8) != 0) {
                if (payload.length == 0) {
                    throw new IOException("Missing HTTP/2 padding length");
                }
                padding = payload[offset++] & 255;
            }
            if (type == 1 && (flags & 32) != 0) {
                offset += 5;
            }
            if (type == 5) {
                offset += 4;
            }
            if (offset + padding > payload.length) {
                throw new IOException("Invalid HTTP/2 header padding");
            }
            direction.block.write(payload, offset, payload.length - offset - padding);
            if ((flags & 4) != 0) {
                List<Header> headers;
                try {
                    // Push promises must also be decoded: they share the response HPACK table.
                    headers = direction.decoder.decodeHeaders(ByteBuffer.wrap(direction.block.toByteArray()));
                } catch (HPackException e) {
                    throw new IOException("Unable to decode recorded HTTP/2 headers", e);
                }
                if (!direction.push && stream != null) {
                    try {
                        headers(stream, request, headers);
                    } catch (IOException invalidTarget) {
                        streams.remove(id);
                        completedStreams.add(id);
                        diagnostics.processingError("Unable to capture HTTP/2 stream " + id + ": " + invalidTarget);
                        return;
                    }
                    if (direction.endStream) {
                        end(stream, request);
                    }
                }
            }
        } else if (type == 0 && stream != null) { // DATA
            int padding = (flags & 8) == 0 ? 0 : payload.length == 0 ? -1 : payload[0] & 255;
            int offset = (flags & 8) == 0 ? 0 : 1;
            if (padding < 0 || padding + offset > payload.length) {
                throw new IOException("Invalid HTTP/2 data padding");
            }
            if (!request && stream.capture != null && stream.capture.sse != null) {
                stream.capture.sse.accept(payload, offset, payload.length - offset - padding, receivedAt, snapshot);
            }
            if (stream.capture != null && stream.capture.webSocket != null) {
                stream.capture.webSocket.accept(request, java.util.Arrays.copyOfRange(payload, offset, payload.length - padding), receivedAt);
            } else {
                (request ? stream.requestBody : stream.responseBody).write(payload, offset, payload.length - offset - padding);
            }
            if ((flags & 1) != 0) {
                end(stream, request);
            }
        } else if (type == 3 && stream != null) { // RST_STREAM
            int code = payload.length == 4 ? ByteBuffer.wrap(payload).getInt() : -1;
            String peer = request ? "Browser cancelled/reset stream: " : "Server reset stream: ";
            complete(stream, stream.responseEnded && code == 0 ? "" : peer + "HTTP/2 RST_STREAM error " + code);
        } else if (type == 7 && !request && payload.length >= 8) { // GOAWAY
            int last = ByteBuffer.wrap(payload).getInt() & 0x7fffffff;
            int code = ByteBuffer.wrap(payload, 4, 4).getInt();
            for (Stream pending : new ArrayList<>(streams.values())) {
                if (pending.id > last) {
                    pending.responseWire.write(frame);
                    complete(pending, "HTTP/2 GOAWAY error " + code + ", last accepted stream " + last);
                }
            }
        }
    }

    private static void headers(Stream stream, boolean request, List<Header> headers) throws IOException {
        if (request) {
            if (stream.capture == null) {
                String method = value(headers, ":method");
                String scheme = value(headers, ":scheme");
                String authority = value(headers, ":authority");
                String path = value(headers, ":path");
                String address = (scheme.isEmpty() ? "https" : scheme) + "://" + authority + (path.isEmpty() ? "/" : path);
                String head = method + " " + address + " HTTP/2\r\nHost: " + authority + "\r\n"
                        + headerText(headers, false) + "\r\n";
                try {
                    stream.capture = new HttpProxyTransport.Capture(
                            new HttpProxyTransport.Head(head.getBytes(StandardCharsets.ISO_8859_1)), HttpProxyTransport.destinationUrl(address));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid HTTP/2 request target", e);
                }
                stream.capture.request().settings = stream.settings;
                stream.webSocket = "websocket".equalsIgnoreCase(value(headers, ":protocol"));
                stream.capture.setRequestHeaders(headerText(headers, true));
                stream.capture.setProtocolVersion("HTTP/2");
            }
        } else if (stream.capture != null) {
            String status = value(headers, ":status");
            if (!status.isEmpty() && !status.startsWith("1")) {
                stream.capture.latencyEnd();
                stream.capture.setResponseCode(status);
                stream.capture.setResponseHeaders("HTTP/2 " + status + "\r\n" + headerText(headers, true));
                stream.capture.setContentType(value(headers, "content-type"));
                stream.capture.setEncodingAndType(value(headers, "content-type"));
                stream.capture.setRedirectLocation(value(headers, "location"));
                stream.capture.setSuccessful(status.startsWith("2") || status.startsWith("3"));
                stream.contentEncoding = value(headers, "content-encoding");
                if ("200".equals(status) && SseProxyRecorder.isEventStream(value(headers, "content-type"))) {
                    stream.capture.sse = new SseProxyRecorder(stream.startedAt, stream.contentEncoding);
                    stream.capture.sseHandshakeEnd = stream.lastFrameAt;
                }
                if (stream.webSocket && "200".equals(status)) {
                    stream.capture.webSocket = new WebSocketProxyRecorder(stream.startedAt, value(headers, "sec-websocket-extensions"));
                    stream.capture.webSocketHandshakeEnd = stream.lastFrameAt;
                }
            }
        }
    }

    private static String headerText(List<Header> headers, boolean pseudoHeaders) {
        StringBuilder text = new StringBuilder();
        for (Header header : headers) {
            if (pseudoHeaders || !header.getName().startsWith(":")) {
                text.append(header.getName()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        return text.toString();
    }

    private static String value(List<Header> headers, String name) {
        return headers.stream().filter(header -> header.getName().equalsIgnoreCase(name))
                .map(Header::getValue).findFirst().orElse("");
    }

    private void end(Stream stream, boolean request) throws IOException {
        if (request) {
            stream.requestEnded = true;
        } else {
            stream.responseEnded = true;
        }
        if (stream.requestEnded && stream.responseEnded) {
            complete(stream, "");
        }
    }

    private void complete(Stream stream, String failure) throws IOException {
        streams.remove(stream.id);
        completedStreams.add(stream.id);
        if (stream.capture != null) {
            if (stream.capture.webSocket != null) {
                stream.capture.webSocket.close();
                if (!stream.capture.webSocket.failure().isEmpty()) {
                    failure = stream.capture.webSocket.failure();
                }
            }
            if (stream.capture.sse != null) {
                stream.capture.sse.finish(stopped.getAsBoolean());
                if (!stream.capture.sse.failure().isEmpty()) {
                    failure = stream.capture.sse.failure();
                } else if (stopped.getAsBoolean()) {
                    failure = "";
                }
            }
            stream.capture.completeHttp2(stream.requestWire.toByteArray(), stream.responseWire.toByteArray(),
                    stream.requestBody.toByteArray(), stream.responseBody.toByteArray(), stream.contentEncoding, failure,
                    stream.startedAt, stream.lastFrameAt, stream.sequence);
            try {
                recorder.accept(stream.capture);
                recordedRequests.add(stream.id);
            } catch (RuntimeException e) {
                diagnostics.processingError("Unable to enqueue HTTP/2 stream " + stream.id + ": " + e);
                LOG.error("Unable to record HTTP/2 stream {}", stream.id, e);
            }
        }
    }

    private synchronized void finishIncomplete(double closedAt) throws IOException {
        for (Stream stream : new ArrayList<>(streams.values())) {
            stream.lastFrameAt = closedAt;
            complete(stream, stream.capture != null && stream.capture.webSocket != null && stopped.getAsBoolean()
                    ? "" : endReason() + ": HTTP/2 stream incomplete");
        }
    }

    private String endReason() {
        return stopped.getAsBoolean() ? "Recorder stopped" : observationFailed ? "HTTP/2 inspection failed" : closure.get();
    }

    private static final class Direction {
        private final HPackDecoder decoder = new HPackDecoder(4096, StandardCharsets.ISO_8859_1);
        private final ByteArrayOutputStream block = new ByteArrayOutputStream();
        private int streamId;
        private boolean push;
        private boolean endStream;

        Direction() {
            // Observe the encoder's own table-size updates without imposing our own SETTINGS.
            decoder.setMaxTableSize(Integer.MAX_VALUE);
        }
    }

    private static final class Stream {
        private boolean webSocket;
        private final int id;
        private final double startedAt;
        private final long sequence;
        private double lastFrameAt;
        private final ByteArrayOutputStream requestWire = new ByteArrayOutputStream();
        private final ByteArrayOutputStream responseWire = new ByteArrayOutputStream();
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private RecordingRequestSettings settings;
        private HttpProxyTransport.Capture capture;
        private String contentEncoding = "";
        private boolean requestEnded;
        private boolean responseEnded;

        Stream(int id, double startedAt, long sequence) {
            this.id = id;
            this.startedAt = startedAt;
            this.lastFrameAt = startedAt;
            this.sequence = sequence;
        }
    }
}
