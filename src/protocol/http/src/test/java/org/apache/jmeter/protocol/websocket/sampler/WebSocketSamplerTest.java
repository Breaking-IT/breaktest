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
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String host = server.getAddress().getAddress().getHostAddress();
            String url = "ws://" + (host.contains(":") ? "[" + host + "]" : host)
                    + ":" + server.getAddress().getPort() + "/";
            SampleResult result = connect("rejected", url).sample(null);
            assertFalse(result.isSuccessful());
            assertEquals("404", result.getResponseCode());
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
    void realHandshakeEchoFilteringAndClose() throws Exception {
        JMeterContextService.getContext().getVariables().put("callbackMarker", "own-user");
        try (Peer peer = new Peer(false)) {
            WebSocketConnectSampler connect = connect("chat", peer.url());
            connect.setTextFilter("^heartbeat$");
            HeaderManager inherited = new HeaderManager();
            inherited.add(new Header("X-Application", "from-manager"));
            connect.addTestElement(inherited);
            connect.setHeaders(List.of(new Header("Origin", "https://example.test")));
            SampleResult connected = connect.sample(null);
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertEquals("101", connected.getResponseCode());
            assertEquals("from-manager", peer.applicationHeader.get(3, TimeUnit.SECONDS));
            assertEquals("https://example.test", peer.originHeader.get(3, TimeUnit.SECONDS));
            WebSocketSendSampler send = new WebSocketSendSampler();
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
            WebSocketSendSampler send = new WebSocketSendSampler();
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
            assertFalse(connect("first", first.url()).sample(null).isSuccessful());
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("first");
            assertTrue(close.sample(null).isSuccessful());
            assertNotNull(WebSocketSessions.current().get("second").socket());
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
        original.setTextFilter("^heartbeat$");
        original.setBinaryFilter("0102");
        original.setCountIncoming(false);
        original.setFailOnDisconnect(false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(original, output);
        WebSocketConnectSampler restored = (WebSocketConnectSampler) SaveService.loadElement(
                new ByteArrayInputStream(output.toByteArray()));
        assertEquals(original.getUrl(), restored.getUrl());
        assertEquals("chat", restored.getSessionName());
        assertEquals("^heartbeat$", restored.getTextFilter());
        assertEquals("0102", restored.getBinaryFilter());
        assertFalse(restored.getCountIncoming());
        assertFalse(restored.getFailOnDisconnect());
        assertEquals("WebSocket Connect", Introspector.getBeanInfo(WebSocketConnectSampler.class)
                .getBeanDescriptor().getDisplayName());
        assertEquals("WebSocket Send", Introspector.getBeanInfo(WebSocketSendSampler.class)
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
            assertEquals("WebSocket Send and Wait", Introspector.getBeanInfo(WebSocketSendWaitSampler.class)
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
        assertFalse(new WebSocketSendSampler().sample(null).isSuccessful());
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
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                            + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
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
