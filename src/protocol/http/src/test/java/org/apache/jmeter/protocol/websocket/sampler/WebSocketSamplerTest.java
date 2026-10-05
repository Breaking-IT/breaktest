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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.Introspector;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

@Timeout(15)
class WebSocketSamplerTest extends JMeterTestCase {
    private final Collector collector = new Collector();

    @BeforeEach
    void setup() {
        JMeterUtils.setLocale(Locale.ENGLISH);
        JMeterVariables variables = new JMeterVariables();
        variables.putObject(JMeterThread.PACKAGE_OBJECT,
                new SamplePackage(List.of(), List.of(collector), List.of(), List.of(), List.of(), List.of(), List.of()));
        JMeterContextService.getContext().setVariables(variables);
    }

    @AfterEach
    void cleanup() {
        WebSocketSessions.cleanup();
        JMeterContextService.getContext().clear();
    }

    private WebSocketConnectSampler connect(String name, String url) {
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.setSessionName(name);
        sampler.setUrl(url);
        sampler.setTimeout(2000);
        return sampler;
    }

    @Test
    void searchesReadableContentInsideBinarySendPayloads() {
        WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
        send.setBinary(true);
        send.setPayload("ff 61 62 63 64 65 66 67 68 69 6a 6b 31 32 37 33\n34 37 30 32 00");
        var body = java.util.Set.of(org.apache.jmeter.gui.SearchArea.BODY);
        assertTrue(send.getSearchableTokens(body).stream().anyMatch(value -> value.contains("12734702")));
        assertTrue(send.getSearchableTokens().stream().anyMatch(value -> value.contains("12734702")));
        assertFalse(send.getSearchableTokens(java.util.Set.of(org.apache.jmeter.gui.SearchArea.NAME))
                .stream().anyMatch(value -> value.contains("12734702")));
        send.setPayload("${binaryMessage}");
        assertTrue(send.getSearchableTokens(body).contains("${binaryMessage}"));
    }

    @Test
    void searchesSendBodiesAndConnectionUrlsAndHeadersInTheirAreas() {
        WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
        send.setPayload("message-content");
        var body = java.util.Set.of(org.apache.jmeter.gui.SearchArea.BODY);
        assertTrue(send.getSearchableTokens(body).contains("message-content"));
        assertFalse(send.getSearchableTokens(java.util.Set.of(org.apache.jmeter.gui.SearchArea.OTHER))
                .contains("message-content"));
        send.setBinary(true);
        send.setPayload("00 ff 41");
        assertTrue(send.getSearchableTokens(body).contains("00 ff 41"));
        WebSocketConnectSampler connect = new WebSocketConnectSampler();
        connect.setUrl("wss://example.test/chat");
        connect.setHeaders(java.util.List.of(new org.apache.jmeter.protocol.http.control.Header("X-Chat", "chat-header")));
        assertTrue(connect.getSearchableTokens(java.util.Set.of(org.apache.jmeter.gui.SearchArea.PATH))
                .contains("wss://example.test/chat"));
        assertTrue(connect.getSearchableTokens(java.util.Set.of(org.apache.jmeter.gui.SearchArea.HEADERS))
                .containsAll(java.util.List.of("X-Chat", "chat-header")));
        assertTrue(connect.getSearchableTokens().containsAll(java.util.List.of("X-Chat", "chat-header")));
        assertTrue(connect.getSearchableTokens(java.util.Set.of()).isEmpty());
        assertFalse(connect.getSearchableTokens(body).contains("chat-header"));
    }

    @Test
    void unifiedSendOnlyIgnoresWaitingSettingsAndPersistsAction() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setSessionName("chat");
            send.setAction(WebSocketSendWaitSampler.SEND_ONLY);
            send.setPayload("hello" + (char) 30);
            send.setWaitMode("unused invalid mode");
            send.setResponsePattern("[");
            send.setResponseBinary("not hex");
            send.setWaitTimeout(0);
            send.setSendOffset("0.125");
            send.setProperty("TestElement.test_class", WebSocketSendWaitSampler.class.getName());
            send.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveService.saveElement(send, output);
            WebSocketSendWaitSampler restored = (WebSocketSendWaitSampler) SaveService.loadElement(
                    new ByteArrayInputStream(output.toByteArray()));
            TestBeanHelper.prepare(restored);
            assertEquals(WebSocketSendWaitSampler.SEND_ONLY, restored.getAction());
            assertEquals("0.125", restored.getSendOffset());
            SampleResult result = restored.sample(null);
            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertEquals(6, result.getSentBytes());
            assertEquals("hello" + (char) 30,
                    collector.results.poll(2, TimeUnit.SECONDS).getResponseDataAsString());
            assertFalse(Introspector.getBeanInfo(WebSocketSendWaitSampler.class).getBeanDescriptor().isHidden());
        }
    }

    @Test
    void closeIgnoresLegacyRecordedTime() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("chat");
            close.setProperty("closeOffset", "60000");
            close.setTimeout(2000);
            SampleResult result = close.sample(null);
            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertEquals(0, result.getIdleTime(), "Close must not wait for a legacy recorded offset");
            assertFalse(close.sample(null).isSuccessful(), "Closed session must have been removed");
        }
    }

    @Test
    void recordedSendTimeIsRelativeToConnectionAndDoesNotRepeatTheDelay() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            WebSocketSession session = WebSocketSessions.current().get("chat");
            long offsetMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - session.startedAtNanos()) + 600;
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setSessionName("chat");
            send.setAction(WebSocketSendWaitSampler.SEND_ONLY);
            send.setPayload("scheduled");
            send.setSendOffset(Long.toString(offsetMs));
            // The recorded delay is independent of the send timeout.
            send.setTimeout(200);
            SampleResult first = send.sample(null);
            assertTrue(first.isSuccessful(), first.getResponseMessage());
            assertTrue(first.getIdleTime() >= 500, "Recorded pacing must be excluded from send latency");
            assertTrue(System.nanoTime() - session.startedAtNanos() >= TimeUnit.MILLISECONDS.toNanos(offsetMs));
            SampleResult second = send.sample(null);
            assertTrue(second.isSuccessful(), second.getResponseMessage());
            assertTrue(second.getTime() < 500, "A past offset must not repeat the recorded delay");
        }
    }

    @Test
    void recordedDelayCanBeInterruptedWithoutClosingTheSession() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            JMeterVariables variables = JMeterContextService.getContext().getVariables();
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setSessionName("chat");
            send.setAction(WebSocketSendWaitSampler.SEND_ONLY);
            send.setSendOffset("60000");
            send.setPayload("must not be sent");
            CompletableFuture<SampleResult> pending = CompletableFuture.supplyAsync(() -> {
                JMeterContextService.getContext().setVariables(variables);
                try {
                    return send.sample(null);
                } finally {
                    JMeterContextService.getContext().clear();
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!send.interrupt() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(pending.get(2, TimeUnit.SECONDS).isSuccessful());
            assertTrue(WebSocketSessions.current().get("chat").isOpen());
            assertTrue(collector.results.isEmpty());
        }
    }

    @Test
    void parallelVariableViewsShareOneRegistryForTheirOwner() throws Exception {
        JMeterVariables parent = JMeterContextService.getContext().getVariables();
        org.apache.jmeter.threads.ThreadGroup group = new org.apache.jmeter.threads.ThreadGroup();
        JMeterThread owner = new JMeterThread(new ListedHashTree(new LoopController()), group, new ListenerNotifier());
        var constructor = Class.forName("org.apache.jmeter.threads.ParallelWorkerVariables")
                .getDeclaredConstructor(JMeterVariables.class);
        constructor.setAccessible(true);
        List<CompletableFuture<WebSocketSessions>> results = new java.util.ArrayList<>();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 32; i++) {
            JMeterVariables view = (JMeterVariables) constructor.newInstance(parent);
            CompletableFuture<WebSocketSessions> registry = new CompletableFuture<>();
            results.add(registry);
            Thread.ofVirtual().start(() -> {
                try {
                    JMeterContextService.getContext().setThread(owner);
                    JMeterContextService.getContext().setVariables(view);
                    start.await();
                    registry.complete(WebSocketSessions.current());
                } catch (Exception error) {
                    registry.completeExceptionally(error);
                } finally {
                    JMeterContextService.getContext().clear();
                }
            });
        }
        start.countDown();
        WebSocketSessions first = results.get(0).get(3, TimeUnit.SECONDS);
        for (CompletableFuture<WebSocketSessions> registry : results) {
            assertSame(first, registry.get(3, TimeUnit.SECONDS));
        }
        assertSame(first, WebSocketSessions.current());
    }

    @Test
    void engineCleanupClosesConnectionsAfterVariablesHaveBeenCleared() throws Exception {
        try (Peer peer = new Peer(false)) {
            LoopController loop = new LoopController();
            loop.setLoops(1);
            loop.setContinueForever(false);
            ListedHashTree tree = new ListedHashTree();
            tree.add(loop).add(connect("chat", peer.url()));
            org.apache.jmeter.threads.ThreadGroup group = new org.apache.jmeter.threads.ThreadGroup();
            JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier());
            user.setThreadGroup(group);
            user.setThreadName("websocket-cleanup-test");
            Thread carrier = Thread.ofVirtual().start(user);
            carrier.join(5000);
            assertFalse(carrier.isAlive());
            // The fixture completes only when the client closes/aborts the socket.
            peer.done.get(3, TimeUnit.SECONDS);
            assertTrue(peer.cookieHeader.isDone(), "The handshake must actually have run");
        }
        JMeterContextService.getContext().clear();
        assertDoesNotThrow(() -> new WebSocketConnectSampler().threadFinished());
    }

    private void assertExistingSessionAction(String action, Peer first, Peer second,
            WebSocketSessions registry, WebSocketSession previous) throws Exception {
        WebSocketConnectSampler next = connect("chat", second.url());
        next.setExistingSessionAction(action);
        SampleResult connected = next.sample(null);
        if (WebSocketConnectSampler.FAIL.equals(action)) {
            assertFalse(connected.isSuccessful());
            assertSame(previous, registry.get("chat"));
            assertNotNull(previous.socket());
        } else if (WebSocketConnectSampler.REUSE.equals(action)) {
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertEquals("200", connected.getResponseCode());
            assertSame(previous, registry.get("chat"));
            assertFalse(second.requestLine.isDone(), "Reuse must not send another handshake");
        } else {
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertNotSame(previous, registry.get("chat"));
            assertThrows(IllegalStateException.class, () -> previous.socket());
            first.done.get(3, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false,Close and reconnect", "false,Reuse if connected", "false,Fail if exists",
            "true,Close and reconnect", "true,Reuse if connected", "true,Fail if exists"})
    void iterationBoundaryClosesOnlyNewUserSessionsEvenWhenCloseWasSkipped(boolean sameUser, String action) throws Exception {
        try (Peer first = new Peer(false); Peer second = new Peer(false)) {
            var iteration = new java.util.concurrent.atomic.AtomicInteger();
            var previous = new java.util.concurrent.atomic.AtomicReference<WebSocketSession>();
            var registry = new java.util.concurrent.atomic.AtomicReference<WebSocketSessions>();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var step = new org.apache.jmeter.samplers.AbstractSampler() {
                private static final long serialVersionUID = 1L;
                @Override
                public SampleResult sample(org.apache.jmeter.samplers.Entry entry) {
                    SampleResult result = new SampleResult();
                    result.sampleStart();
                    int current = iteration.incrementAndGet();
                    try {
                        if (current == 1) {
                            SampleResult connected = connect("chat", first.url()).sample(null);
                            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
                            registry.set(WebSocketSessions.current());
                            previous.set(registry.get().get("chat"));
                        } else if (sameUser) {
                            assertSame(registry.get(), WebSocketSessions.current());
                            assertNotNull(previous.get().socket());
                            assertExistingSessionAction(action, first, second, registry.get(), previous.get());
                        } else {
                            first.done.get(3, TimeUnit.SECONDS);
                            assertThrows(IllegalStateException.class, () -> previous.get().socket());
                            registry.get().notifyListeners(() -> {
                                throw new AssertionError("Old user must not publish notifications");
                            });
                            assertNotSame(registry.get(), WebSocketSessions.current());
                            WebSocketConnectSampler next = connect("chat", second.url());
                            next.setExistingSessionAction(action);
                            SampleResult connected = next.sample(null);
                            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                    result.setSuccessful(current != 1); // Skip the first iteration's Close sampler.
                    result.sampleEnd();
                    return result;
                }
            };
            LoopController loop = new LoopController();
            loop.setLoops(2);
            loop.setContinueForever(false);
            ListedHashTree tree = new ListedHashTree();
            org.apache.jmeter.threads.ThreadGroup group = new org.apache.jmeter.threads.ThreadGroup();
            group.setSamplerController(loop);
            var children = tree.add(group);
            children.add(step);
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("chat");
            children.add(close);
            JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
            user.setThreadGroup(group);
            user.setThreadName("websocket-iteration-test");
            user.setOnErrorStartNextLoop(true);
            Thread carrier = Thread.ofVirtual().start(user);
            try {
                carrier.join(8000);
                assertFalse(carrier.isAlive());
            } finally {
                user.stop();
                carrier.interrupt();
                carrier.join(2000);
            }
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            assertEquals(2, iteration.get());
            first.done.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void urlVariablesResolveBeforeHandshake() throws Exception {
        try (Peer peer = new Peer(false)) {
            WebSocketConnectSampler sampler = connect("chat",
                    peer.url() + "?hub=livehub&id=${connectionId}&access_token=${json_access_token}");
            JMeterVariables variables = JMeterContextService.getContext().getVariables();
            variables.put("connectionId", "connection-123");
            variables.put("json_access_token", "test-token");
            new ValueReplacer().replaceValues(sampler);
            sampler.setRunningVersion(true);
            TestBeanHelper.prepare(sampler);
            SampleResult result = sampler.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("GET /?hub=livehub&id=connection-123&access_token=test-token HTTP/1.1",
                    peer.requestLine.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedHandshakeReportsHttpStatusAndResponseHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("X-Rejection-Reason", "unknown-connection");
            exchange.getResponseHeaders().add("Set-Cookie", "retry=token; Path=/");
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String host = server.getAddress().getAddress().getHostAddress();
            String url = "ws://" + (host.contains(":") ? "[" + host + "]" : host)
                    + ":" + server.getAddress().getPort() + "/";
            CookieManager cookies = new CookieManager();
            cookies.testStarted();
            WebSocketConnectSampler connect = connect("rejected", url);
            connect.addTestElement(cookies);
            SampleResult result;
            try (var existing = WebSocketTransportPool.acquire(URI.create(url))) {
                result = connect.sample(null);
                try (var next = WebSocketTransportPool.acquire(URI.create(url))) {
                    assertSame(existing.client(), next.client());
                }
            }
            assertEquals("retry=token", connect.cookieHeader(URI.create(url)));
            assertFalse(result.isSuccessful());
            assertEquals("404", result.getResponseCode());
            assertTrue(result instanceof HTTPSampleResult);
            assertEquals("GET", ((HTTPSampleResult) result).getHTTPMethod());
            assertTrue(result.getRequestHeaders().toLowerCase(Locale.ROOT).contains("sec-websocket-key:"));
            assertTrue(result.getResponseHeaders().startsWith("HTTP/1.1 404"));
            assertEquals("WebSocket handshake rejected: HTTP 404", result.getResponseMessage());
            assertTrue(result.getResponseHeaders().contains("unknown-connection"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void websocketHandshakeReusesCookieReceivedByHttpSampler() throws Exception {
        HttpServer login = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        login.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=logged-in; Path=/; HttpOnly");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        login.start();
        CookieManager cookies = new CookieManager();
        cookies.testStarted();
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        try (Peer peer = new Peer(false)) {
            URI ws = URI.create(peer.url());
            http.setProtocol("http");
            http.setDomain(ws.getHost());
            http.setPort(login.getAddress().getPort());
            http.setPath("/login");
            http.setMethod("GET");
            http.setImplementation("HttpClient5");
            http.setCookieManager(cookies);
            SampleResult loginResult = http.sample();
            assertTrue(loginResult.isSuccessful(), loginResult::getResponseMessage);
            assertEquals(1, cookies.getCookieCount());

            WebSocketConnectSampler connect = connect("authenticated", peer.url());
            connect.addTestElement(cookies);
            SampleResult connected = connect.sample(null);
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertEquals("session=logged-in", peer.cookieHeader.get(3, TimeUnit.SECONDS));
        } finally {
            http.threadFinished();
            cookies.testEnded();
            login.stop(0);
        }
    }

    @Test
    void handshakeCookiesAreValidatedAndReusedByHttp() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        HttpServer endpoint = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        endpoint.createContext("/", exchange -> {
            received.complete(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        endpoint.start();
        CookieManager cookies = new CookieManager();
        cookies.testStarted();
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        try (Peer peer = new Peer(false)) {
            peer.responseCookies = "Set-Cookie: upgraded=yes; Path=/; HttpOnly\r\n"
                    + "Set-Cookie: rejected=no; Domain=other.example; Path=/\r\n"
                    + "Set-Cookie: removed=gone; Max-Age=0; Path=/\r\n";
            WebSocketConnectSampler connect = connect("cookies", peer.url());
            connect.addTestElement(cookies);
            SampleResult result = connect.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            http.setProtocol("http");
            http.setDomain(URI.create(peer.url()).getHost());
            http.setPort(endpoint.getAddress().getPort());
            http.setPath("/");
            http.setMethod("GET");
            http.setImplementation("HttpClient5");
            http.setCookieManager(cookies);
            assertTrue(http.sample().isSuccessful());
            assertEquals("upgraded=yes", received.get(3, TimeUnit.SECONDS));
        } finally {
            http.threadFinished();
            cookies.testEnded();
            endpoint.stop(0);
        }
    }

    @Test
    void cancelledHandshakeKeepsSharedClientAvailableToOtherUsers() throws Exception {
        URI uri = URI.create("ws://localhost/");
        try (var original = WebSocketTransportPool.acquire(uri)) {
            var bridge = (WebSocketHandshakeCookies) original.client().cookieHandler().orElseThrow();
            var pending = bridge.begin(uri);
            try (var next = WebSocketTransportPool.acquire(uri)) {
                // A handshake that is still pending, or was cancelled, never holds up other users.
                assertSame(original.client(), next.client());
                bridge.begin(uri).close();
                pending.close();
                assertFalse(original.client().isTerminated());
            }
        }
    }

    @Test
    void concurrentSameUrlHandshakesKeepResponseCookiesWithTheirUser() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
                Peer first = new Peer(server); Peer second = new Peer(server);
                var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new java.util.concurrent.CountDownLatch(2);
            first.handshakeBarrier = barrier;
            second.handshakeBarrier = barrier;
            first.echoUserCookie = true;
            second.echoUserCookie = true;
            var keepConnected = new java.util.concurrent.CountDownLatch(2);
            var alice = workers.submit(() -> handshakeForUser(first.url(), "alice", keepConnected));
            var bob = workers.submit(() -> handshakeForUser(first.url(), "bob", keepConnected));
            assertSame(alice.get(5, TimeUnit.SECONDS), bob.get(5, TimeUnit.SECONDS));
        }
    }

    private java.net.http.HttpClient handshakeForUser(String url, String user,
            java.util.concurrent.CountDownLatch connected) throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        CookieManager cookies = new CookieManager();
        cookies.testStarted();
        try {
            cookies.addCookieFromHeader("user=" + user + "; Path=/", WebSocketHandshakeCookies.httpUri(URI.create(url)).toURL());
            WebSocketConnectSampler connect = connect("same", url);
            connect.addTestElement(cookies);
            SampleResult result = connect.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("user=" + user + "; upgrade=" + user, connect.cookieHeader(URI.create(url)));
            var sessions = WebSocketSessions.current();
            var client = sessions.client("same", sessions.get("same"), URI.create(url));
            connected.countDown();
            assertTrue(connected.await(3, TimeUnit.SECONDS));
            return client;
        } finally {
            WebSocketSessions.cleanup();
            cookies.testEnded();
            JMeterContextService.getContext().clear();
        }
    }

    @Test
    void aiConfiguredNativeElementsNegotiateExchangeAndClose() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var catalog = org.apache.jmeter.ai.AgentElementCatalog.INSTANCE;
        try (Peer peer = new Peer(false)) {
            var connectProperties = json.createObjectNode()
                    .put("sessionName", "generated").put("url", peer.url()).put("timeout", 2000);
            var connect = (WebSocketConnectSampler) catalog.configured(new WebSocketConnectSampler(),
                    connectProperties, json.readTree("""
                            {"headers":[{"name":"Sec-WebSocket-Protocol","value":"test.protocol"}]}
                            """));
            SampleResult connected = connect.sample(null);
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertTrue(connected.getResponseHeaders().toLowerCase(Locale.ROOT)
                    .contains("sec-websocket-protocol: test.protocol"));
            var send = (WebSocketSendWaitSampler) catalog.configured(new WebSocketSendWaitSampler(),
                    json.createObjectNode().put("sessionName", "generated").put("payload", "request-1")
                            .put("action", WebSocketSendWaitSampler.SEND_AND_WAIT)
                            .put("waitMode", WebSocketSendWaitSampler.MATCHING_MESSAGE)
                            .put("responsePattern", "^request-1$").put("waitTimeout", 2000), null);
            SampleResult reply = send.sample(null);
            assertTrue(reply.isSuccessful(), reply::getResponseMessage);
            assertEquals("request-1", reply.getResponseDataAsString());
            var close = (WebSocketCloseSampler) catalog.configured(new WebSocketCloseSampler(),
                    json.createObjectNode().put("sessionName", "generated").put("timeout", 2000), null);
            assertTrue(close.sample(null).isSuccessful());
        }
    }

    @Test
    void realHandshakeEchoFilteringAndClose() throws Exception {
        JMeterContextService.getContext().getVariables().put("callbackMarker", "own-user");
        try (Peer peer = new Peer(false)) {
            WebSocketConnectSampler connect = connect("chat", peer.url());
            connect.setTextFilter("^heartbeat$");
            HeaderManager inherited = new HeaderManager();
            inherited.add(new Header("X-Application", "from-manager"));
            connect.addTestElement(inherited);
            connect.setHeaders(List.of(new Header("Origin", "https://example.test"),
                    new Header("Sec-WebSocket-Protocol", "v1.push.openbet.com, fallback")));
            SampleResult connected = connect.sample(null);
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertEquals("101", connected.getResponseCode());
            assertTrue(connected instanceof HTTPSampleResult);
            assertEquals("GET", ((HTTPSampleResult) connected).getHTTPMethod());
            assertEquals("http", connected.getURL().getProtocol());
            assertEquals(peer.url(), connected.getUrlAsString());
            assertEquals(peer.url(), new HTTPSampleResult((HTTPSampleResult) connected).getUrlAsString());
            assertEquals("HTTP/1.1", connected.getProtocolVersion());
            assertEquals("Switching Protocols", connected.getResponseMessage());
            String requestHeaders = connected.getRequestHeaders().toLowerCase(Locale.ROOT);
            assertTrue(requestHeaders.contains("sec-websocket-key:"), requestHeaders);
            assertTrue(requestHeaders.contains("upgrade: websocket"), requestHeaders);
            assertTrue(connected.getResponseHeaders().startsWith("HTTP/1.1 101 Switching Protocols"));
            assertTrue(connected.getResponseHeaders().toLowerCase(Locale.ROOT).contains("sec-websocket-accept:"));
            assertEquals("from-manager", peer.applicationHeader.get(3, TimeUnit.SECONDS));
            assertEquals("https://example.test", peer.originHeader.get(3, TimeUnit.SECONDS));
            assertTrue(requestHeaders.contains("sec-websocket-protocol: v1.push.openbet.com, fallback"), requestHeaders);
            assertTrue(connected.getResponseHeaders().contains("Sec-WebSocket-Protocol: v1.push.openbet.com")
                    || connected.getResponseHeaders().contains("sec-websocket-protocol: v1.push.openbet.com"));
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setAction(WebSocketSendWaitSampler.SEND_ONLY);
            send.setSessionName("chat");
            send.setPayload("heartbeat");
            assertTrue(send.sample(null).isSuccessful());
            send.setPayload("hello");
            assertTrue(send.sample(null).isSuccessful());
            SampleResult incoming = collector.results.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertEquals("hello", incoming.getResponseDataAsString());
            assertEquals("own-user", collector.callbackUsers.poll(3, TimeUnit.SECONDS));
            assertEquals("WebSocket chat receive", incoming.getSampleLabel());
            send.setBinary(true);
            send.setPayload(" 00 01\nff ");
            assertTrue(send.sample(null).isSuccessful());
            SampleResult binary = collector.results.poll(3, TimeUnit.SECONDS);
            assertNotNull(binary);
            assertEquals(SampleResult.BINARY, binary.getDataType());
            assertEquals(3, binary.getResponseData().length);
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("chat");
            assertTrue(close.sample(null).isSuccessful());
            peer.done.get(3, TimeUnit.SECONDS);
            assertTrue(collector.results.isEmpty());
            assertFalse(send.sample(null).isSuccessful());
        }
    }

    @Test
    void scriptListenerUsesTheOriginatingUserVariables() throws Exception {
        var variables = JMeterContextService.getContext().getVariables();
        variables.put("callbackMarker", "origin-user");
        var script = new org.apache.jmeter.visualizers.JSR223Listener();
        script.setProperty("scriptLanguage", "groovy");
        script.setProperty("script", "vars.put('callbackObserved', vars.get('callbackMarker'))");
        variables.putObject(JMeterThread.PACKAGE_OBJECT,
                new SamplePackage(List.of(), List.of(script, collector), List.of(), List.of(), List.of(), List.of(), List.of()));
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setAction(WebSocketSendWaitSampler.SEND_ONLY);
            send.setSessionName("chat");
            send.setPayload("event");
            assertTrue(send.sample(null).isSuccessful());
            assertNotNull(collector.results.poll(3, TimeUnit.SECONDS));
            assertEquals("origin-user", variables.get("callbackObserved"));
        }
    }

    @Test
    void nativePingIsAnsweredWithoutProducingSamplesByDefault() throws Exception {
        try (Peer peer = new Peer(false, false, true)) {
            WebSocketConnectSampler connect = connect("ping", peer.url());
            assertTrue(connect.getIgnoreControlFrames());
            assertTrue(connect.sample(null).isSuccessful());
            assertEquals(17, peer.pong.get(3, TimeUnit.SECONDS)[0]);
            assertTrue(collector.results.isEmpty());
        }
    }

    @Test
    void remoteDisconnectProducesFailureWithoutAnotherSampler() throws Exception {
        try (Peer peer = new Peer(true)) {
            WebSocketConnectSampler connect = connect("push", peer.url());
            connect.setCountIncoming(false);
            assertTrue(connect.sample(null).isSuccessful());
            SampleResult disconnect = collector.results.poll(3, TimeUnit.SECONDS);
            assertNotNull(disconnect);
            assertFalse(disconnect.isSuccessful());
            assertEquals("1000", disconnect.getResponseCode());
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("push");
            SampleResult closed = close.sample(null);
            assertFalse(closed.isSuccessful());
            assertEquals("WS_ERROR", closed.getResponseCode());
            assertTrue(closed.getResponseMessage().contains("WebSocket session is not open: push"));
            assertThrows(IllegalStateException.class, () -> WebSocketSessions.current().get("push"));
            assertFalse(close.sample(null).isSuccessful(), "A missing session must also fail Close");
        }
    }

    @Test
    void staleCloseAndConnectCannotAffectReplacementSession() throws Exception {
        try (Peer first = new Peer(false); Peer replacement = new Peer(false)) {
            assertTrue(connect("same", first.url()).sample(null).isSuccessful());
            WebSocketSessions registry = WebSocketSessions.current();
            WebSocketSession old = registry.get("same");
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("same");
            assertTrue(close.sample(null).isSuccessful());
            assertTrue(connect("same", replacement.url()).sample(null).isSuccessful());
            registry.remove("same", old);
            assertThrows(IllegalStateException.class, () -> registry.client("same", old, URI.create(first.url())));
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setSessionName("same");
            send.setPayload("replacement survives");
            SampleResult response = send.sample(null);
            assertTrue(response.isSuccessful(), response::getResponseMessage);
            assertEquals("replacement survives", response.getResponseDataAsString());
        }
    }

    @Test
    void multipleNamedSessionsAndDuplicateNameProtection() throws Exception {
        try (Peer first = new Peer(false); Peer second = new Peer(false)) {
            assertTrue(connect("first", first.url()).sample(null).isSuccessful());
            assertTrue(connect("second", second.url()).sample(null).isSuccessful());
            assertNotSame(WebSocketSessions.current().get("first"), WebSocketSessions.current().get("second"));
            WebSocketConnectSampler duplicate = connect("first", first.url());
            duplicate.setExistingSessionAction(WebSocketConnectSampler.FAIL);
            assertFalse(duplicate.sample(null).isSuccessful());
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("first");
            assertTrue(close.sample(null).isSuccessful());
            assertNotNull(WebSocketSessions.current().get("second").socket());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Close and reconnect", "Reuse if connected"})
    void reconnectReplacesOnlyNamedSessionIncludingDisconnectedSessions(String action) throws Exception {
        try (Peer first = new Peer(false); Peer other = new Peer(false); Peer replacement = new Peer(false)) {
            assertEquals(WebSocketConnectSampler.RECONNECT, new WebSocketConnectSampler().getExistingSessionAction());
            assertTrue(connect("chat", first.url()).sample(null).isSuccessful());
            assertTrue(connect("other", other.url()).sample(null).isSuccessful());
            WebSocketSessions registry = WebSocketSessions.current();
            WebSocketSession old = registry.get("chat");
            WebSocketSession untouched = registry.get("other");
            if (WebSocketConnectSampler.REUSE.equals(action)) {
                old.close().get(3, TimeUnit.SECONDS);
            }
            WebSocketConnectSampler next = connect("chat", replacement.url());
            next.setExistingSessionAction(action);
            SampleResult connected = next.sample(null);
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertEquals("101", connected.getResponseCode());
            assertTrue(connected instanceof HTTPSampleResult);
            assertEquals("GET", ((HTTPSampleResult) connected).getHTTPMethod());
            assertEquals("http", connected.getURL().getProtocol());
            assertEquals(replacement.url(), connected.getUrlAsString());
            assertEquals(replacement.url(), new HTTPSampleResult((HTTPSampleResult) connected).getUrlAsString());
            assertEquals("HTTP/1.1", connected.getProtocolVersion());
            assertEquals("Switching Protocols", connected.getResponseMessage());
            String requestHeaders = connected.getRequestHeaders().toLowerCase(Locale.ROOT);
            assertTrue(requestHeaders.contains("sec-websocket-key:"), requestHeaders);
            assertTrue(requestHeaders.contains("upgrade: websocket"), requestHeaders);
            assertTrue(connected.getResponseHeaders().startsWith("HTTP/1.1 101 Switching Protocols"));
            assertTrue(connected.getResponseHeaders().toLowerCase(Locale.ROOT).contains("sec-websocket-accept:"));
            first.done.get(3, TimeUnit.SECONDS);
            assertNotSame(old, registry.get("chat"));
            assertSame(untouched, registry.get("other"));
            assertNotNull(untouched.socket());
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setSessionName("chat");
            send.setPayload("replacement works");
            SampleResult response = send.sample(null);
            assertTrue(response.isSuccessful(), response::getResponseMessage);
            assertEquals("replacement works", response.getResponseDataAsString());
        }
    }

    @Test
    void sameNameIsIsolatedBetweenUsersAndCleanupReleasesIt() throws Exception {
        try (Peer first = new Peer(false); Peer second = new Peer(false)) {
            assertTrue(connect("same", first.url()).sample(null).isSuccessful());
            WebSocketSession firstSession = WebSocketSessions.current().get("same");
            CompletableFuture<Void> other = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try {
                    JMeterContextService.getContext().setVariables(new JMeterVariables());
                    assertTrue(connect("same", second.url()).sample(null).isSuccessful());
                    assertNotSame(firstSession, WebSocketSessions.current().get("same"));
                    WebSocketSessions.cleanup();
                    other.complete(null);
                } catch (Throwable e) {
                    other.completeExceptionally(e);
                } finally {
                    JMeterContextService.getContext().clear();
                }
            });
            thread.start();
            other.get(5, TimeUnit.SECONDS);
            assertNotNull(firstSession.socket());
            new WebSocketConnectSampler().threadFinished();
            assertTrue(collector.results.isEmpty());
        }
    }

    @Test
    void closeTimeoutFailsCloseWithoutUnexpectedDisconnectResult() throws Exception {
        try (Peer peer = new Peer(false, true)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("chat");
            close.setTimeout(100);
            SampleResult result = close.sample(null);
            assertFalse(result.isSuccessful());
            assertTrue(result.getResponseMessage().contains("TimeoutException"), result::getResponseMessage);
            peer.done.get(3, TimeUnit.SECONDS);
            assertTrue(collector.results.isEmpty());
        }
    }

    @Test
    void testPlanRoundTripAndGuiMetadata() throws Exception {
        WebSocketConnectSampler original = connect("chat", "wss://example.test/socket");
        original.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        original.setProperty("TestElement.test_class", WebSocketConnectSampler.class.getName());
        original.setExistingSessionAction(WebSocketConnectSampler.REUSE);
        original.setTextFilter("^heartbeat$");
        original.setBinaryFilter("0102");
        original.setCountIncoming(false);
        original.setFailOnDisconnect(false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(original, output);
        WebSocketConnectSampler restored = (WebSocketConnectSampler) SaveService.loadElement(
                new ByteArrayInputStream(output.toByteArray()));
        assertEquals(original.getUrl(), restored.getUrl());
        assertEquals(WebSocketConnectSampler.REUSE, restored.getExistingSessionAction());
        assertEquals("chat", restored.getSessionName());
        assertEquals("^heartbeat$", restored.getTextFilter());
        assertEquals("0102", restored.getBinaryFilter());
        assertFalse(restored.getCountIncoming());
        assertFalse(restored.getFailOnDisconnect());
        assertEquals("WebSocket Connect", Introspector.getBeanInfo(WebSocketConnectSampler.class)
                .getBeanDescriptor().getDisplayName());
        assertEquals("WebSocket Send", Introspector.getBeanInfo(WebSocketSendWaitSampler.class)
                .getBeanDescriptor().getDisplayName());
        assertEquals("WebSocket Close", Introspector.getBeanInfo(WebSocketCloseSampler.class)
                .getBeanDescriptor().getDisplayName());
    }

    @Test
    void savedSignalRRecordSeparatorIsSentUnchanged() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            WebSocketSendWaitSampler original = new WebSocketSendWaitSampler();
            original.setSessionName("chat");
            original.setPayload("{\"protocol\":\"json\",\"version\":1}" + (char) 0x1e);
            original.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
            original.setProperty("TestElement.test_class", WebSocketSendWaitSampler.class.getName());
            ByteArrayOutputStream saved = new ByteArrayOutputStream();
            SaveService.saveElement(original, saved);
            WebSocketSendWaitSampler restored = (WebSocketSendWaitSampler) SaveService.loadElement(
                    new ByteArrayInputStream(saved.toByteArray()));
            SampleResult response = restored.sample(null);
            assertTrue(response.isSuccessful(), response::getResponseMessage);
            assertEquals(original.getPayload(), response.getResponseDataAsString());
            assertEquals(0x1e, response.getResponseData()[response.getResponseData().length - 1]);
        }
    }

    @Test
    void sendWaitSkipsUnrelatedMessagesAndReturnsMatchedResponse() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            peer.notifyBeforeEcho = true;
            WebSocketSendWaitSampler wait = new WebSocketSendWaitSampler();
            wait.setSessionName("chat");
            wait.setPayload("hallo");
            wait.setWaitMode(WebSocketSendWaitSampler.MATCHING_MESSAGE);
            wait.setResponsePattern("^hallo$");
            wait.setWaitTimeout(2000);
            SampleResult result = wait.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("hallo", result.getResponseDataAsString());
            assertEquals("notification", collector.results.poll(3, TimeUnit.SECONDS).getResponseDataAsString());
            assertTrue(collector.results.isEmpty());
            assertEquals("WebSocket Send", Introspector.getBeanInfo(WebSocketSendWaitSampler.class)
                    .getBeanDescriptor().getDisplayName());
            wait.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
            wait.setProperty("TestElement.test_class", WebSocketSendWaitSampler.class.getName());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveService.saveElement(wait, output);
            WebSocketSendWaitSampler restored = (WebSocketSendWaitSampler) SaveService.loadElement(
                    new ByteArrayInputStream(output.toByteArray()));
            assertEquals(2000, restored.getWaitTimeout());
            assertEquals("^hallo$", restored.getResponsePattern());
            assertEquals(wait.getWaitMode(), restored.getWaitMode());
        }
    }

    @Test
    void binarySendWaitAcceptsPastedHexAndPersistsMatcher() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            peer.notifyBeforeEcho = true;
            WebSocketSendWaitSampler wait = new WebSocketSendWaitSampler();
            wait.setSessionName("chat");
            wait.setBinary(true);
            wait.setPayload(" FF 07 95 03\n80 a1\t30 03 C0 EE ");
            wait.setWaitMode(WebSocketSendWaitSampler.BINARY_MESSAGE);
            wait.setResponseBinary(" 07 95 03 80\r\nA1 30 03 C0 ");
            wait.setWaitTimeout(2000);
            wait.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
            wait.setProperty("TestElement.test_class", WebSocketSendWaitSampler.class.getName());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveService.saveElement(wait, output);
            WebSocketSendWaitSampler restored = (WebSocketSendWaitSampler) SaveService.loadElement(
                    new ByteArrayInputStream(output.toByteArray()));
            assertEquals(wait.getResponseBinary(), restored.getResponseBinary());
            assertEquals(wait.getWaitMode(), restored.getWaitMode());
            SampleResult result = restored.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals(SampleResult.BINARY, result.getDataType());
            assertEquals("ff07950380a13003c0ee", java.util.HexFormat.of().formatHex(result.getResponseData()));
            assertEquals(10, result.getSentBytes());
            for (String invalid : new String[] {" \n", "GG", "0 12"}) {
                restored.setResponseBinary(invalid);
                assertFalse(restored.sample(null).isSuccessful());
                assertNotNull(WebSocketSessions.current().get("chat").socket());
            }
            restored.setResponseBinary("DE AD");
            restored.setWaitTimeout(100);
            SampleResult timeout = restored.sample(null);
            assertFalse(timeout.isSuccessful());
            assertTrue(timeout.getResponseMessage().contains("TimeoutException"), timeout::getResponseMessage);
            restored.setResponseBinary("03 C0");
            restored.setWaitTimeout(2000);
            assertTrue(restored.sample(null).isSuccessful());
        }
    }

    @Test
    void waitTimeoutIsIndependentAndLeavesSessionUsable() throws Exception {
        try (Peer peer = new Peer(false)) {
            WebSocketConnectSampler connect = connect("chat", peer.url());
            connect.setCountIncoming(false);
            assertTrue(connect.sample(null).isSuccessful());
            WebSocketSendWaitSampler wait = new WebSocketSendWaitSampler();
            wait.setSessionName("chat");
            wait.setPayload("other");
            wait.setTimeout(5000);
            wait.setWaitTimeout(150);
            wait.setWaitMode(WebSocketSendWaitSampler.MATCHING_MESSAGE);
            wait.setResponsePattern("^hallo$");
            SampleResult timeout = wait.sample(null);
            assertFalse(timeout.isSuccessful());
            assertTrue(timeout.getResponseMessage().contains("TimeoutException"), timeout::getResponseMessage);
            assertTrue(timeout.getTime() >= 100 && timeout.getTime() < 3000);
            wait.setWaitMode(WebSocketSendWaitSampler.NEXT_MESSAGE);
            wait.setWaitTimeout(2000);
            SampleResult next = wait.sample(null);
            assertTrue(next.isSuccessful(), next::getResponseMessage);
            assertEquals("other", next.getResponseDataAsString());
            wait.setWaitMode(WebSocketSendWaitSampler.MATCHING_MESSAGE);
            wait.setResponsePattern("[");
            assertFalse(wait.sample(null).isSuccessful());
            assertNotNull(WebSocketSessions.current().get("chat").socket());
            wait.setWaitTimeout(0);
            assertFalse(wait.sample(null).isSuccessful());
        }
    }

    @Test
    void invalidTextDoesNotAbortExistingConnection() throws Exception {
        try (Peer peer = new Peer(false)) {
            assertTrue(connect("chat", peer.url()).sample(null).isSuccessful());
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setSessionName("chat");
            send.setPayload(String.valueOf((char) 0xd800));
            assertFalse(send.sample(null).isSuccessful());
            send.setPayload("valid");
            SampleResult result = send.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("valid", result.getResponseDataAsString());
        }
    }

    @Test
    void invalidSettingsAndMissingSessionsFailAsResults() {
        assertFalse(connect("", "ws://localhost/").sample(null).isSuccessful());
        assertFalse(connect("x", "https://localhost/").sample(null).isSuccessful());
        WebSocketConnectSampler invalid = connect("x", "ws://localhost/");
        invalid.setTextFilter("[");
        assertFalse(invalid.sample(null).isSuccessful());
        invalid.setTextFilter("");
        invalid.setTimeout(0);
        assertFalse(invalid.sample(null).isSuccessful());
        assertFalse(new WebSocketSendWaitSampler().sample(null).isSuccessful());
        assertFalse(new WebSocketCloseSampler().sample(null).isSuccessful());
    }

    private static final class Collector extends AbstractTestElement implements SampleListener, org.apache.jmeter.engine.util.NoThreadClone {
        private final BlockingQueue<SampleResult> results = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> callbackUsers = new LinkedBlockingQueue<>();

        @Override
        public void sampleOccurred(SampleEvent event) {
            var variables = JMeterContextService.getContext().getVariables();
            callbackUsers.add(variables == null ? "missing-context" : String.valueOf(variables.get("callbackMarker")));
            results.add(event.getResult());
        }

        @Override
        public void sampleStarted(SampleEvent event) {
        }

        @Override
        public void sampleStopped(SampleEvent event) {
        }
    }

    /** Small local RFC 6455 fixture: validates client masking and echoes data frames. */
    static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        final CompletableFuture<String> clientPrincipal = new CompletableFuture<>();
        private final CompletableFuture<String> requestLine = new CompletableFuture<>();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final CompletableFuture<String> cookieHeader = new CompletableFuture<>();
        private final CompletableFuture<String> applicationHeader = new CompletableFuture<>();
        private final CompletableFuture<String> originHeader = new CompletableFuture<>();
        private final CompletableFuture<byte[]> pong = new CompletableFuture<>();
        private volatile Socket accepted;
        private volatile boolean notifyBeforeEcho;
        volatile String responseCookies = "";
        volatile boolean echoUserCookie;
        volatile java.util.concurrent.CountDownLatch handshakeBarrier;

        Peer(boolean remoteClose) throws IOException {
            this(remoteClose, false);
        }

        Peer(boolean remoteClose, boolean ignoreClose) throws IOException {
            this(remoteClose, ignoreClose, false);
        }

        Peer(boolean remoteClose, boolean ignoreClose, boolean sendPing) throws IOException {
            this(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()), remoteClose, ignoreClose, sendPing);
        }

        Peer(ServerSocket server) {
            this(server, false, false, false);
        }

        private Peer(ServerSocket server, boolean remoteClose, boolean ignoreClose, boolean sendPing) {
            this.server = server;
            Thread worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII));
                    requestLine.complete(reader.readLine());
                    String key = null;
                    String protocol = "";
                    String cookies = "";
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Cookie:", 0, 7)) {
                            cookies = line.substring(7).trim();
                        }
                        if (line.regionMatches(true, 0, "X-Application:", 0, 14)) {
                            applicationHeader.complete(line.substring(14).trim());
                        }
                        if (line.regionMatches(true, 0, "Origin:", 0, 7)) {
                            originHeader.complete(line.substring(7).trim());
                        }
                        if (line.regionMatches(true, 0, "Sec-WebSocket-Protocol:", 0, 23)) {
                            protocol = line.substring(23).trim().split(",")[0].trim();
                        }
                        if (line.startsWith("Sec-WebSocket-Key:")) {
                            key = line.substring(line.indexOf(':') + 1).trim();
                        }
                    }
                    if (socket instanceof SSLSocket ssl && ssl.getNeedClientAuth()) {
                        clientPrincipal.complete(ssl.getSession().getPeerPrincipal().getName());
                    }
                    cookieHeader.complete(cookies);
                    if (key == null) {
                        throw new IOException("Missing WebSocket key");
                    }
                    if (handshakeBarrier != null) {
                        handshakeBarrier.countDown();
                        if (!handshakeBarrier.await(3, TimeUnit.SECONDS)) {
                            throw new IOException("Concurrent handshakes did not reach the peer");
                        }
                    }
                    String cookieResponse = echoUserCookie
                            ? "Set-Cookie: upgrade=" + cookies.substring("user=".length()) + "; Path=/\r\n"
                            : responseCookies;
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                            + accept + "\r\n" + (protocol.isEmpty() ? "" : "Sec-WebSocket-Protocol: " + protocol + "\r\n")
                            + cookieResponse + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    if (sendPing) {
                        frame(socket, 9, new byte[] {17});
                    }
                    if (remoteClose) {
                        frame(socket, 8, new byte[] {3, (byte) 232});
                    }
                    while (true) {
                        int opcode = input.read();
                        if (opcode < 0) {
                            break;
                        }
                        int length = input.read();
                        if ((length & 128) == 0 || (length & 127) >= 126) {
                            throw new IOException("Expected a masked, short client frame");
                        }
                        byte[] mask = input.readNBytes(4);
                        byte[] data = input.readNBytes(length & 127);
                        if (mask.length != 4 || data.length != (length & 127)) {
                            throw new EOFException();
                        }
                        for (int i = 0; i < data.length; i++) {
                            data[i] ^= mask[i % 4];
                        }
                        int type = opcode & 15;
                        if (type == 10) {
                            pong.complete(data);
                            continue;
                        }
                        if (type == 8 && ignoreClose) {
                            input.read(); // Wait until the client aborts its timed-out close.
                            break;
                        }
                        if (!remoteClose) {
                            if (notifyBeforeEcho && type == 1) {
                                frame(socket, 1, "notification".getBytes(StandardCharsets.UTF_8));
                            }
                            frame(socket, type, data);
                        }
                        if (type == 8) {
                            break;
                        }
                    }
                    done.complete(null);
                } catch (Exception e) {
                    done.completeExceptionally(e);
                    clientPrincipal.completeExceptionally(e);
                }
            }, "websocket-test-peer");
            worker.setDaemon(true);
            worker.start();
        }

        String url() {
            return (server instanceof SSLServerSocket ? "wss://" : "ws://") + (server.getInetAddress().getHostAddress().contains(":")
                    ? "[" + server.getInetAddress().getHostAddress() + "]" : server.getInetAddress().getHostAddress())
                    + ":" + server.getLocalPort() + "/";
        }

        private static void frame(Socket socket, int opcode, byte[] data) throws IOException {
            socket.getOutputStream().write(128 | opcode);
            socket.getOutputStream().write(data.length);
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            Socket socket = accepted;
            if (socket != null) {
                socket.close();
            }
        }
    }
}
