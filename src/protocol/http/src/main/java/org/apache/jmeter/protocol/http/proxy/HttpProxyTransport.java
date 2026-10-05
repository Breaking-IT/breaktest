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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;

/** HTTP/1.x relay. Sampler configuration never participates in live forwarding. */
final class HttpProxyTransport implements AutoCloseable {
    private volatile Socket upstream;
    private java.util.function.Supplier<RecordingRequestSettings> recordingSettings = () -> null;

    void setRecordingSettings(java.util.function.Supplier<RecordingRequestSettings> settings) {
        recordingSettings = settings;
    }

    private volatile boolean stopped;
    private InputStream serverInput;
    private String origin;
    private boolean absoluteForm;
    private String proxyAuthorization = "";

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    static long nextSequence() {
        return SEQUENCE.incrementAndGet();
    }

    private static final long CLOCK_ORIGIN_MS = System.currentTimeMillis();
    private static final long CLOCK_ORIGIN_NS = System.nanoTime();

    /** One monotonic clock for both protocols, retaining sub-millisecond overlap evidence. */
    static double captureTimeMillis() {
        return (double) CLOCK_ORIGIN_MS + (double) (System.nanoTime() - CLOCK_ORIGIN_NS) / 1_000_000d;
    }

    static final class Head {
        RecordingRequestSettings settings;
        private final byte[] bytes;
        private double receivedAt = captureTimeMillis();
        private long sequence = nextSequence();
        private final List<String> lines;

        Head(byte[] bytes) throws IOException {
            this.bytes = bytes;
            lines = Arrays.asList(new String(bytes, StandardCharsets.ISO_8859_1).split("\r\n"));
            if (lines.isEmpty() || lines.get(0).split(" ", 3).length < 2) {
                throw new IOException("Invalid HTTP start line");
            }
        }

        String firstLine() {
            return lines.get(0);
        }

        String method() {
            return firstLine().split(" ", 3)[0];
        }

        String target() {
            return firstLine().split(" ", 3)[1];
        }

        String value(String name) {
            return lines.stream().skip(1).filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .reduce((left, right) -> left + ", " + right).orElse("");
        }

        boolean token(String name, String token) {
            return Arrays.stream(value(name).split(",")).anyMatch(value -> token.equalsIgnoreCase(value.trim()));
        }

        boolean closes() {
            return token("Connection", "close")
                    || (firstLine().contains("HTTP/1.0") && !token("Connection", "keep-alive"));
        }

        byte[] bytes() {
            return bytes;
        }

        String headers() {
            return String.join("\r\n", lines.subList(1, lines.size())) + "\r\n";
        }

        URL url(String tunnelAuthority) throws IOException {
            String address = target();
            if (!address.startsWith("http://") && !address.startsWith("https://")) {
                String authority = tunnelAuthority == null ? value("Host") : tunnelAuthority;
                address = (tunnelAuthority == null ? "http://" : "https://") + authority
                        + (address.equals("*") ? "/" : address);
            }
            try {
                URL url = destinationUrl(address);
                if (!List.of("http", "https").contains(url.getProtocol()) || url.getHost().isEmpty()) {
                    throw new IOException("Unsupported proxy destination");
                }
                return url;
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid proxy destination", e);
            }
        }

        byte[] forOrigin(URL url, boolean absoluteForm, String proxyAuthorization) {
            String path = target();
            if (path.startsWith("http://") || path.startsWith("https://")) {
                if (!absoluteForm) {
                    int authorityEnd = path.indexOf('/', path.indexOf("://") + 3);
                    int query = path.indexOf('?', path.indexOf("://") + 3);
                    if (query >= 0 && (authorityEnd < 0 || query < authorityEnd)) {
                        authorityEnd = query;
                    }
                    path = authorityEnd < 0 ? "/" : path.substring(authorityEnd);
                    if (path.startsWith("?")) {
                        path = "/" + path;
                    }
                }
            } else if (absoluteForm && !path.equals("*")) {
                path = url.getProtocol() + "://" + url.getAuthority() + path;
            }
            StringBuilder forwarded = new StringBuilder(method()).append(' ')
                    .append(path.isEmpty() ? "/" : path).append(' ')
                    .append(firstLine().split(" ", 3)[2]).append("\r\n");
            for (String line : lines.subList(1, lines.size())) {
                // Proxy credentials and the proxy-only connection header belong to this hop.
                if (!line.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)
                        && !line.regionMatches(true, 0, "Proxy-Connection:", 0, 17)) {
                    forwarded.append(line).append("\r\n");
                }
            }
            if (!proxyAuthorization.isEmpty()) {
                forwarded.append("Proxy-Authorization: ").append(proxyAuthorization).append("\r\n");
            }
            return forwarded.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    /** Escape only URI-illegal characters for metadata; forwarding uses the untouched request target. */
    static URL destinationUrl(String address) throws IOException {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < address.length(); i += Character.charCount(address.codePointAt(i))) {
            char character = address.charAt(i);
            boolean invalidPercent = character == '%' && (i + 2 >= address.length()
                    || Character.digit(address.charAt(i + 1), 16) < 0 || Character.digit(address.charAt(i + 2), 16) < 0);
            if (character <= 32 || character >= 127 || "\"<>\\^`{|}".indexOf(character) >= 0 || invalidPercent) {
                int codePoint = address.codePointAt(i);
                for (byte value : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    escaped.append('%').append(Character.forDigit((value & 255) >>> 4, 16))
                            .append(Character.forDigit(value & 15, 16));
                }
            } else {
                escaped.append(character);
            }
        }
        try {
            return URI.create(escaped.toString()).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid proxy destination", e);
        }
    }

    static Head readHead(InputStream in) throws IOException {
        return readHead(in, () -> null);
    }

    static Head readHead(InputStream in, java.util.function.Supplier<RecordingRequestSettings> settings) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }
        double receivedAt = captureTimeMillis();
        long sequence = nextSequence();
        RecordingRequestSettings snapshot = settings.get();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] line = readLine(in, first);
        while (true) {
            if (line.length == 0) {
                throw new EOFException("Incomplete HTTP headers");
            }
            bytes.write(line);
            if (bytes.size() > 1024 * 1024) {
                throw new IOException("HTTP headers exceed 1 MiB");
            }
            if (line.length == 2) {
                Head head = new Head(bytes.toByteArray());
                head.settings = snapshot;
                head.receivedAt = receivedAt;
                head.sequence = sequence;
                return head;
            }
            line = readLine(in);
        }
    }

    private static byte[] readLine(InputStream in) throws IOException {
        return readLine(in, in.read());
    }

    private static byte[] readLine(InputStream in, int first) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current = first;
        while (current != -1) {
            line.write(current);
            if (previous == '\r' && current == '\n') {
                return line.toByteArray();
            }
            if (line.size() > 1024 * 1024) {
                throw new IOException("HTTP line exceeds 1 MiB");
            }
            previous = current;
            current = in.read();
        }
        if (line.size() != 0) {
            throw new EOFException("Incomplete HTTP line");
        }
        return new byte[0];
    }

    /** Includes the exact HTTP messages on this proxy's client-facing connection (before TLS encryption). */
    static final class Capture extends HTTPSampleResult {
        SseProxyRecorder sse;
        double sseHandshakeEnd;
        WebSocketProxyRecorder webSocket;
        double webSocketHandshakeEnd;
        private final Head request;
        private final ByteArrayOutputStream requestWire = new ByteArrayOutputStream();
        private final ByteArrayOutputStream responseWire = new ByteArrayOutputStream();
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private String transportError = "";
        private long sequence;
        private double startedAt;
        private double finishedAt;
        private boolean keepAlive;
        private boolean upgraded;

        Capture(Head request, URL url) throws IOException {
            this.request = request;
            requestWire.write(request.bytes());
            setURL(url);
            setHTTPMethod(request.method());
            setSamplerData(request.firstLine());
            setRequestHeaders(request.headers());
            setSampleLabel(url.toString());
            setResponseCode("0");
            startedAt = request.receivedAt;
            setStartTime((long) startedAt);
            sequence = request.sequence;
        }

        long sequence() {
            return sequence;
        }

        void completeHttp2(byte[] originalRequest, byte[] originalResponse, byte[] payload,
                byte[] responsePayload, String contentEncoding, String failure, double start, double end, long order) throws IOException {
            requestWire.reset();
            requestWire.write(originalRequest);
            responseWire.write(originalResponse);
            requestBody.write(payload);
            transportError = failure;
            setResponseData(responsePayload, contentEncoding);
            setProtocolVersion("HTTP/2");
            setQueryString(new String(payload, StandardCharsets.UTF_8));
            setBodySize((long) responsePayload.length);
            setSentBytes(originalRequest.length);
            if (!failure.isEmpty()) {
                setSuccessful(false);
            }
            sequence = order;
            startedAt = start;
            setStartTime((long) start);
            finishAt(end);
        }

        double startedAt() { return startedAt; }
        double finishedAt() { return Math.max(startedAt, finishedAt); }

        void finishAt(double end) {
            finishedAt = Math.max(startedAt, end);
            setEndTime((long) finishedAt);
        }

        byte[] requestWire() {
            return requestWire.toByteArray();
        }
        byte[] responseWire() {
            return responseWire.toByteArray();
        }
        byte[] requestBody() {
            return requestBody.toByteArray();
        }
        String transportError() {
            return transportError;
        }
        boolean keepAlive() {
            return keepAlive;
        }
        boolean upgraded() {
            return upgraded;
        }
        Head request() {
            return request;
        }
    }

    Capture forward(Head request, URL url, Socket client, InputStream clientInput) throws IOException {
        Capture capture = new Capture(request, url);
        Head response = null;
        Thread upload = null;
        AtomicReference<IOException> uploadFailure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean uploaded = new java.util.concurrent.atomic.AtomicBoolean();
        boolean sentFinalHeaders = false;
        try {
            connect(url);
            OutputStream serverOutput = RecordingIo.output(upstream.getOutputStream(), "Server");
            OutputStream browserOutput = RecordingIo.output(client.getOutputStream(), "Browser");
            InputStream browserInput = RecordingIo.input(clientInput, "Browser");
            InputStream responseInput = RecordingIo.input(serverInput, "Server");
            serverOutput.write(request.forOrigin(url, absoluteForm, absoluteForm ? proxyAuthorization : ""));
            serverOutput.flush();
            // Reading the response concurrently handles 100 Continue and early upload rejections.
            if (request.token("Transfer-Encoding", "chunked") || !request.value("Content-Length").isEmpty()) {
                upload = Thread.ofVirtual().name("proxy-upload").start(() -> {
                    try {
                        copyBody(request, browserInput, serverOutput, capture.requestWire, capture.requestBody, false);
                        uploaded.set(true);
                        serverOutput.flush();
                    } catch (IOException e) {
                        uploadFailure.set(e instanceof EOFException ? new IOException("Browser upload incomplete: " + e, e) : e);
                        close();
                    }
                });
            }
            int status;
            do {
                response = readHead(responseInput);
                if (response == null) {
                    throw new EOFException("Upstream closed before sending a response");
                }
                try {
                    status = Integer.parseInt(response.target());
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid HTTP response status", e);
                }
                sentFinalHeaders = status >= 200 || status == 101;
                capture.responseWire.write(response.bytes());
                browserOutput.write(response.bytes());
                browserOutput.flush();
            } while (status >= 100 && status < 200 && status != 101);
            sentFinalHeaders = true;
            capture.latencyEnd();
            capture.setResponseCode(Integer.toString(status));
            capture.setProtocolVersion(response.firstLine().split(" ", 2)[0]);
            capture.setHeadersSize(response.bytes().length);
            capture.setResponseMessage(response.firstLine().split(" ", 3).length == 3
                    ? response.firstLine().split(" ", 3)[2] : "");
            capture.setResponseHeaders(new String(response.bytes(), StandardCharsets.ISO_8859_1));
            capture.setContentType(response.value("Content-Type"));
            capture.setEncodingAndType(response.value("Content-Type"));
            capture.setRedirectLocation(response.value("Location"));
            capture.setSuccessful(status >= 100 && status < 400);
            capture.upgraded = status == 101;
            if (capture.upgraded && request.token("Upgrade", "websocket") && response.token("Upgrade", "websocket")) {
                capture.webSocket = new WebSocketProxyRecorder(capture.startedAt(), response.value("Sec-WebSocket-Extensions"));
            }
            boolean body = !request.method().equals("HEAD") && status != 204 && status != 304 && !capture.upgraded;
            OutputStream responseBody = capture.responseBody;
            if (body && status == 200 && SseProxyRecorder.isEventStream(response.value("Content-Type"))) {
                capture.sseHandshakeEnd = captureTimeMillis();
                capture.sse = new SseProxyRecorder(capture.startedAt(), response.value("Content-Encoding"));
                responseBody = new OutputStream() {
                    @Override
                    public void write(int value) {
                        write(new byte[]{(byte) value}, 0, 1);
                    }
                    @Override
                    public void write(byte[] bytes, int offset, int length) {
                        capture.responseBody.write(bytes, offset, length);
                        capture.sse.accept(bytes, offset, length, captureTimeMillis(), recordingSettings.get());
                    }
                };
            }
            if (body) {
                copyBody(response, responseInput, browserOutput, capture.responseWire, responseBody, true);
            }
            browserOutput.flush();
            // A final response may arrive while the browser is still waiting for 100 Continue.
            if (upload != null && !uploaded.get()) {
                closeClient(client);
                close();
                upload.join();
                capture.keepAlive = false;
            } else {
                if (upload != null) {
                    upload.join();
                }
                capture.keepAlive = uploadFailure.get() == null && !request.closes() && !response.closes()
                        && (!body || response.token("Transfer-Encoding", "chunked") || !response.value("Content-Length").isEmpty());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            capture.transportError = (stopped ? "Recorder stopped: " : uploadFailure.get() != null
                    ? "Request upload failed: " : "Response transfer or server connection failed: ")
                    + (uploadFailure.get() == null ? e.toString() : uploadFailure.get().toString());
            capture.setSuccessful(false);
            if (!sentFinalHeaders) {
                capture.setResponseMessage(e.toString());
                byte[] error = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1);
                try {
                    client.getOutputStream().write(error);
                    client.getOutputStream().flush();
                    capture.responseWire.write(error);
                } catch (IOException ignored) {
                    // The browser may have disconnected too; retain the upstream failure.
                }
            }
            capture.keepAlive = false;
            close();
        } finally {
            if (upload != null && upload.isAlive()) {
                try {
                    closeClient(client);
                    close();
                    upload.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Request arrival and completion must use the same clock, before decoding/conversion work.
            capture.finishAt(captureTimeMillis());
            capture.webSocketHandshakeEnd = capture.finishedAt();
            capture.setQueryString(new String(capture.requestBody(), StandardCharsets.UTF_8));
            capture.setResponseData(capture.responseBody.toByteArray(),
                    response == null ? null : response.value("Content-Encoding"));
            if (capture.sse != null) {
                capture.sse.finish(stopped);
                if (!capture.sse.failure().isEmpty()) {
                    capture.transportError = capture.sse.failure();
                } else if (stopped) {
                    capture.transportError = "";
                    capture.setSuccessful(true);
                }
            }
            capture.setBodySize((long) capture.responseBody.size());
            capture.setSentBytes(capture.requestWire.size());
        }
        return capture;
    }

    private static void closeClient(Socket client) {
        try {
            client.close();
        } catch (IOException ignored) {
            // Cleanup must not turn a completely forwarded HTTP response into a transport failure.
        }
    }

    private boolean reusable() throws IOException {
        Socket socket = upstream;
        if (socket == null || socket.isClosed()) {
            return false;
        }
        int timeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(1);
            serverInput.mark(1);
            int next = serverInput.read();
            if (next < 0) {
                return false;
            }
            serverInput.reset();
            return true;
        } catch (java.net.SocketTimeoutException expected) {
            return true;
        } catch (IOException closed) {
            return false;
        } finally {
            if (!socket.isClosed()) {
                socket.setSoTimeout(timeout);
            }
        }
    }

    String negotiate(URL url, List<String> browserProtocols) throws IOException {
        String[] protocols = browserProtocols.stream().filter(value -> value.equals("h2") || value.equals("http/1.1"))
                .toArray(String[]::new);
        if (protocols.length == 0) {
            return "";
        }
        connect(url, protocols);
        String selected = ((SSLSocket) upstream).getApplicationProtocol();
        return selected.isEmpty() && browserProtocols.contains("http/1.1") ? "http/1.1" : selected;
    }

    void relayHttp2(Socket client, InputStream input, java.util.function.Consumer<Capture> recorder,
            java.util.function.Supplier<RecordingRequestSettings> settings, RecordingDiagnostics diagnostics) throws IOException {
        new Http2ProxyRelay(recorder, settings, diagnostics, () -> stopped).relay(client, input, upstream, serverInput);
    }

    private void connect(URL url) throws IOException {
        connect(url, new String[]{"http/1.1"});
    }

    private void connect(URL url, String[] protocols) throws IOException {
        if (stopped) {
            throw new IOException("Recorder stopped");
        }
        int port = url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
        String nextOrigin = url.getProtocol() + "://" + url.getHost() + ":" + port;
        if (upstream != null && nextOrigin.equals(origin) && reusable()) {
            return;
        }
        close();
        String proxyHost = System.getProperty("http.proxyHost", "");
        int proxyPort = Integer.getInteger("http.proxyPort", 0);
        boolean chained = !proxyHost.isEmpty() && proxyPort > 0 && !bypassProxy(url.getHost());
        absoluteForm = chained && !url.getProtocol().equals("https");
        proxyAuthorization = "";
        String user = JMeterUtils.getPropDefault("http.proxyUser", "");
        if (chained && !user.isEmpty()) {
            proxyAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + JMeterUtils.getPropDefault("http.proxyPass", "")).getBytes(StandardCharsets.ISO_8859_1));
        }
        Socket socket = new Socket();
        upstream = socket;
        if (stopped) {
            close();
            throw new IOException("Recorder stopped");
        }
        String local = JMeterUtils.getPropDefault("httpclient.localaddress", "");
        if (!local.isEmpty()) {
            socket.bind(new InetSocketAddress(local, 0));
        }
        socket.connect(new InetSocketAddress(chained ? proxyHost : url.getHost(), chained ? proxyPort : port),
                JMeterUtils.getPropDefault("proxy.connect_timeout", 10000));
        if (chained && System.getProperty("http.proxyScheme", "http").equalsIgnoreCase("https")) {
            secure(proxyHost, proxyPort, new String[]{"http/1.1"});
        }
        if (chained && url.getProtocol().equals("https")) {
            String authority = url.getHost() + ":" + port;
            String connect = "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n"
                    + (proxyAuthorization.isEmpty() ? "" : "Proxy-Authorization: " + proxyAuthorization + "\r\n") + "\r\n";
            upstream.getOutputStream().write(connect.getBytes(StandardCharsets.ISO_8859_1));
            upstream.getOutputStream().flush();
            Head response = readHead(upstream.getInputStream());
            if (response == null || !"200".equals(response.target())) {
                throw new IOException("Upstream proxy refused CONNECT: " + (response == null ? "EOF" : response.firstLine()));
            }
        }
        if (url.getProtocol().equals("https")) {
            secure(url.getHost(), port, protocols);
        }
        serverInput = new BufferedInputStream(upstream.getInputStream());
        origin = nextOrigin;
    }

    private static boolean bypassProxy(String host) {
        return Arrays.stream(System.getProperty("http.nonProxyHosts", "").split("\\|"))
                .filter(pattern -> !pattern.isEmpty())
                .anyMatch(pattern -> host.matches("(?i)" + Pattern.quote(pattern).replace("*", "\\E.*\\Q")));
    }

    private void secure(String host, int port, String[] protocols) throws IOException {
        try {
            SSLSocket ssl = (SSLSocket) ((JsseSSLManager) SSLManager.getInstance()).getContext()
                    .getSocketFactory().createSocket(upstream, host, port, true);
            upstream = ssl;
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setApplicationProtocols(protocols);
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
        } catch (GeneralSecurityException e) {
            throw new IOException("Unable to initialize upstream TLS", e);
        }
    }

    /** Relay upgraded protocols without interpreting or recording their frames as HTTP messages. */
    void tunnel(Socket client, InputStream clientInput) throws IOException {
        tunnel(client, clientInput, null);
    }

    void tunnel(Socket client, InputStream clientInput, Capture capture) throws IOException {
        Socket server = upstream;
        WebSocketProxyRecorder recorder = capture == null ? null : capture.webSocket;
        Thread outgoing = Thread.ofVirtual().name("proxy-upgrade").start(() -> {
            try {
                relayUpgrade(clientInput, server.getOutputStream(), recorder, true);
                server.shutdownOutput();
            } catch (IOException ignored) {
                close();
            }
        });
        try {
            relayUpgrade(serverInput, client.getOutputStream(), recorder, false);
        } finally {
            close();
            closeClient(client);
            try {
                outgoing.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (recorder != null) {
                recorder.close();
                if (!recorder.failure().isEmpty()) {
                    capture.transportError = recorder.failure();
                }
            }
        }
    }

    private static void relayUpgrade(InputStream input, OutputStream output, WebSocketProxyRecorder recorder,
            boolean request) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            double receivedAt = captureTimeMillis();
            try {
                output.write(buffer, 0, count);
                output.flush();
            } finally {
                if (recorder != null) {
                    recorder.accept(request, Arrays.copyOf(buffer, count), receivedAt);
                }
            }
        }
    }

    private static void copyBody(Head head, InputStream in, OutputStream out,
            ByteArrayOutputStream wire, OutputStream body, boolean untilClose) throws IOException {
        if (head.token("Transfer-Encoding", "chunked")) {
            while (true) {
                byte[] line = readLine(in);
                if (line.length == 0) {
                    throw new EOFException("Missing chunk size");
                }
                relay(line, out, wire);
                long size;
                try {
                    size = Long.parseLong(new String(line, StandardCharsets.US_ASCII).trim().split(";", 2)[0], 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid HTTP chunk size", e);
                }
                if (size < 0) {
                    throw new IOException("Negative HTTP chunk size");
                }
                if (size == 0) {
                    do {
                        line = readLine(in);
                        if (line.length == 0) {
                            throw new EOFException("Incomplete HTTP trailers");
                        }
                        relay(line, out, wire);
                    } while (line.length != 2);
                    return;
                }
                copy(in, out, wire, body, size);
                byte[] end = readLine(in);
                if (!Arrays.equals(end, new byte[]{'\r', '\n'})) {
                    throw new IOException("Invalid HTTP chunk delimiter");
                }
                relay(end, out, wire);
            }
        }
        String length = head.value("Content-Length");
        if (!length.isEmpty()) {
            try {
                String[] values = length.split(",");
                long count = Long.parseLong(values[0].trim());
                for (String value : values) {
                    if (count < 0 || Long.parseLong(value.trim()) != count) {
                        throw new NumberFormatException("Conflicting or negative Content-Length");
                    }
                }
                copy(in, out, wire, body, count);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length", e);
            }
        } else if (untilClose) {
            copy(in, out, wire, body, -1);
        }
    }

    private static void copy(InputStream in, OutputStream out, ByteArrayOutputStream wire,
            OutputStream body, long remaining) throws IOException {
        byte[] buffer = new byte[8192];
        while (remaining != 0) {
            int read = in.read(buffer, 0, remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                if (remaining > 0) {
                    throw new EOFException("Incomplete HTTP body: " + remaining + " bytes missing");
                }
                return;
            }
            wire.write(buffer, 0, read);
            body.write(buffer, 0, read);
            out.write(buffer, 0, read);
            out.flush();
            if (remaining > 0) {
                remaining -= read;
            }
        }
    }

    private static void relay(byte[] bytes, OutputStream out, ByteArrayOutputStream wire) throws IOException {
        wire.write(bytes);
        out.write(bytes);
        out.flush();
    }

    void stop() {
        stopped = true;
        close();
    }

    @Override
    public void close() {
        Socket socket = upstream;
        upstream = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing either side is also how recorder shutdown interrupts a relay.
            }
        }
    }
}
