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

package org.apache.jmeter.protocol.http.sampler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.support.classic.TransportException;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.wiremock.WireMockExtension;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

@ExtendWith(WireMockExtension.class)
public class TestHTTPHC5Impl {
    private static final String HTTP2_IO_THREAD_COUNT = "httpclient5.http2.io_thread_count";

    @BeforeAll
    public static void setupJMeterProperties() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = Files.createTempFile("jmeter", ".properties");
            JMeterUtils.loadJMeterProperties(properties.toString());
            Files.deleteIfExists(properties);
        }
    }

    @Test
    void sequentialAndParallelRequestsReuseOnlyTheirUsersConnections() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool();
        var users = Executors.newFixedThreadPool(2);
        Map<String, Set<Integer>> sockets = new ConcurrentHashMap<>();
        Map<String, CyclicBarrier> barriers = new ConcurrentHashMap<>();
        CountDownLatch firstFinished = new CountDownLatch(1);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            String[] path = exchange.getRequestURI().getPath().split("/");
            String key = path[1] + "/" + path[2];
            sockets.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                    .add(exchange.getRemoteAddress().getPort());
            try {
                if (key.equals("second/0") && !firstFinished.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("First user did not finish");
                }
                int parallelism = Integer.parseInt(path[2]);
                if (parallelism > 1) {
                    // Hold every response until the whole group has an active connection.
                    barriers.computeIfAbsent(key, ignored -> new CyclicBarrier(parallelism))
                            .await(10, TimeUnit.SECONDS);
                }
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[] {'o', 'k'});
            } catch (Exception failure) {
                throw new IOException(failure);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var first = users.submit(() -> {
                try {
                    runConnectionSequence(server.getAddress().getPort(), "first");
                } finally {
                    firstFinished.countDown();
                }
            });
            var second = users.submit(() -> runConnectionSequence(server.getAddress().getPort(), "second"));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
            for (String user : List.of("first", "second")) {
                Set<Integer> initial = sockets.get(user + "/1");
                Set<Integer> two = sockets.get(user + "/2");
                Set<Integer> four = sockets.get(user + "/4");
                assertEquals(1, initial.size());
                assertEquals(2, two.size());
                assertEquals(4, four.size());
                assertTrue(two.containsAll(initial), "First parallel group must reuse the initial connection");
                assertTrue(four.containsAll(two), "Second parallel group must reuse both existing connections");
                assertTrue(four.containsAll(sockets.get(user + "/0")), "Sequential tail must reuse the shared pool");
            }
            assertTrue(Collections.disjoint(sockets.get("first/4"), sockets.get("second/4")),
                    "Different virtual users must never share sockets");
        } finally {
            users.shutdownNow();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void parallelConnectionsResumeTlsOnlyWithinTheirUser(String protocol, @TempDir Path directory)
            throws Exception {
        Path store = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "password", "-keypass", "password",
                "-dname", "CN=localhost", "-validity", "2")
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytool.waitFor(), output);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, "password".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "password".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override
            public void configure(HttpsParameters parameters) {
                var ssl = tls.getDefaultSSLParameters();
                ssl.setProtocols(new String[] {protocol});
                parameters.setSSLParameters(ssl);
            }
        });
        var handlers = Executors.newCachedThreadPool();
        Map<String, Long> sessions = new ConcurrentHashMap<>();
        Map<String, Set<Integer>> sockets = new ConcurrentHashMap<>();
        CyclicBarrier parallel = new CyclicBarrier(5);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String user = path.split("/")[1];
            // SunJSSE preserves the original session creation time on resumption,
            // including TLS 1.3 where session IDs themselves change.
            sessions.put(path, ((HttpsExchange) exchange).getSSLSession().getCreationTime());
            sockets.computeIfAbsent(user, ignored -> ConcurrentHashMap.newKeySet())
                    .add(exchange.getRemoteAddress().getPort());
            try {
                if (!path.endsWith("/initial")) {
                    parallel.await(10, TimeUnit.SECONDS);
                }
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[] {'o', 'k'});
            } catch (Exception failure) {
                throw new IOException(failure);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            for (String user : List.of("first", "second")) {
                LoopController loop = new LoopController();
                loop.setLoops(1);
                loop.setContinueForever(false);
                ListedHashTree tree = new ListedHashTree();
                var flow = tree.add(loop);
                SuccessfulSamples listener = new SuccessfulSamples();
                flow.add(listener);
                HTTPSamplerProxy initial = classicSampler(server.getAddress().getPort(), "/" + user + "/initial");
                initial.setProtocol("https");
                initial.setMaxConnectionsPerHost("6");
                flow.add(initial);
                var branches = flow.add(new ParallelController());
                for (int i = 0; i < 5; i++) {
                    HTTPSamplerProxy sampler = classicSampler(server.getAddress().getPort(), "/" + user + "/" + i);
                    sampler.setProtocol("https");
                    sampler.setMaxConnectionsPerHost("6");
                    branches.add(sampler);
                }
                ThreadGroup group = new ThreadGroup();
                group.setNumThreads(1);
                JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier());
                thread.setThreadGroup(group);
                thread.setThreadName(user);
                thread.run();
                assertEquals(6, listener.results.size());
                for (SampleResult result : listener.results) {
                    assertTrue(result.isSuccessful(), result.getResponseMessage());
                }
                assertEquals(6, sockets.get(user).size(), "Every request must establish a new socket");
                Long firstSession = sessions.get("/" + user + "/initial");
                assertTrue(java.util.stream.IntStream.range(0, 5)
                        .anyMatch(i -> firstSession.equals(sessions.get("/" + user + "/" + i))),
                        "At least one parallel connection must resume the initial TLS session: " + sessions);
            }
            Set<Long> firstUser = ConcurrentHashMap.newKeySet();
            sessions.forEach((path, session) -> {
                if (path.startsWith("/first/")) {
                    firstUser.add(session);
                }
            });
            sessions.forEach((path, session) -> {
                if (path.startsWith("/second/")) {
                    assertFalse(firstUser.contains(session), "Users must not share TLS sessions");
                }
            });
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    private static void runConnectionSequence(int port, String user) {
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        ListedHashTree tree = new ListedHashTree();
        var flow = tree.add(loop);
        SuccessfulSamples listener = new SuccessfulSamples();
        flow.add(listener);
        flow.add(classicSampler(port, "/" + user + "/1/home"));
        for (int count : List.of(2, 4)) {
            ParallelController parallel = new ParallelController();
            parallel.setName("parallel-" + count);
            var group = flow.add(parallel);
            for (int i = 0; i < count; i++) {
                group.add(classicSampler(port, "/" + user + "/" + count + "/" + i));
            }
        }
        flow.add(classicSampler(port, "/" + user + "/0/photo"));
        ThreadGroup group = new ThreadGroup();
        group.setNumThreads(1);
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier());
        thread.setThreadGroup(group);
        thread.setThreadName(user);
        thread.run();
        assertEquals(8, listener.results.size());
        for (SampleResult result : listener.results) {
            assertTrue(result.isSuccessful(), result.getSampleLabel() + ": " + result.getResponseMessage());
        }
    }

    private static class SuccessfulSamples extends AbstractTestElement implements SampleListener {
        private final Queue<SampleResult> results = new ConcurrentLinkedQueue<>();

        @Override
        public void sampleOccurred(SampleEvent event) {
            results.add(event.getResult());
        }

        @Override
        public void sampleStarted(SampleEvent event) {
        }

        @Override
        public void sampleStopped(SampleEvent event) {
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 6})
    void parallelRequestsRespectDefaultsLimitIndependentlyPerUser(int configuredLimit) throws Exception {
        int limit = configuredLimit > 0 ? configuredLimit : 6;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool();
        var users = Executors.newFixedThreadPool(2);
        CountDownLatch firstWave = new CountDownLatch(2 * limit);
        CountDownLatch excessRequest = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<String, Set<Integer>> sockets = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> arrivals = new ConcurrentHashMap<>();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            String user = exchange.getRequestURI().getPath().split("/")[1];
            sockets.computeIfAbsent(user, ignored -> ConcurrentHashMap.newKeySet())
                    .add(exchange.getRemoteAddress().getPort());
            boolean queued = arrivals.computeIfAbsent(user, ignored -> new AtomicInteger()).incrementAndGet() > limit;
            if (queued) {
                excessRequest.countDown();
            }
            firstWave.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release connections");
                }
                byte[] body = (queued ? "queued" : "initial").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var first = users.submit(() -> runLimitedParallelUser(server.getAddress().getPort(), "first", configuredLimit, 0, limit + 2));
            var second = users.submit(() -> runLimitedParallelUser(server.getAddress().getPort(), "second", configuredLimit, 0, limit + 2));
            assertTrue(firstWave.await(5, TimeUnit.SECONDS), "Both users must get their full connection allowance independently");
            assertFalse(excessRequest.await(200, TimeUnit.MILLISECONDS), "Remaining requests must wait in the pool");
            assertEquals(limit, arrivals.get("first").get());
            assertEquals(limit, arrivals.get("second").get());
            long released = System.currentTimeMillis();
            release.countDown();
            for (var result : List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))) {
                assertEquals(limit + 2, result.size());
                for (SampleResult sample : result) {
                    assertTrue(sample.isSuccessful(), sample.getResponseCode() + ": " + sample.getResponseMessage());
                    if (sample.getResponseDataAsString().equals("queued")) {
                        assertTrue(sample.getStartTime() >= released - 50,
                                "Queued request start must advance past the 200 ms pool wait");
                        assertEquals(-1, sample.getConnectTime(), "Queued requests reuse an existing connection");
                        assertTrue(sample.getTime() < sample.getEndTime() - released + 50,
                                "Elapsed time must not include the pool wait");
                    }
                }
            }
            assertEquals(limit, sockets.get("first").size(), "Queued requests must reuse connections");
            assertEquals(limit, sockets.get("second").size());
            assertTrue(Collections.disjoint(sockets.get("first"), sockets.get("second")));
        } finally {
            release.countDown();
            users.shutdownNow();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disabledReuseWinsOverCustomKeepAliveHeader(boolean reuse) throws Exception {
        HTTPSamplerProxy sampler = classicSampler(80, "/");
        sampler.setUseKeepAlive(reuse);
        var headers = new org.apache.jmeter.protocol.http.control.HeaderManager();
        headers.add(new Header("Connection", "keep-alive"));
        sampler.setHeaderManager(headers);
        HttpGet request = new HttpGet("http://localhost/");
        new HTTPHC5Impl(sampler).setupRequest(request.getUri().toURL(), request, null);
        BasicHttpResponse response = new BasicHttpResponse(200);
        response.setVersion(HttpVersion.HTTP_1_1);
        response.setHeader("Content-Length", "0");
        response.setHeader("Connection", "keep-alive");
        assertEquals(reuse, org.apache.hc.client5.http.impl.DefaultClientConnectionReuseStrategy.INSTANCE
                .keepAlive(request, response, HttpClientContext.create()),
                "The client must discard the connection when reuse is disabled, even if keep-alive is advertised");
    }

    @Test
    void unsetAvailabilityTimeoutKeepsClientDefault() throws Exception {
        HTTPSamplerProxy sampler = classicSampler(80, "/");
        assertEquals(0, sampler.getMaxConnectionsPerHost());
        assertEquals(-1, sampler.getConnectionAvailabilityTimeout());
        HttpGet request = new HttpGet("http://localhost/");
        new HTTPHC5Impl(sampler).setupRequest(request.getUri().toURL(), request, null);
        assertEquals(RequestConfig.DEFAULT.getConnectionRequestTimeout(), request.getConfig().getConnectionRequestTimeout());
    }

    @Test
    void connectionAvailabilityTimeoutFailsQueuedRequestWithoutConnecting() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool();
        var users = Executors.newSingleThreadExecutor();
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            arrived.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[] {'o', 'k'});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var future = users.submit(() -> runLimitedParallelUser(server.getAddress().getPort(), "user", 1, 100, 2));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            var results = future.get(10, TimeUnit.SECONDS);
            assertEquals(2, results.size());
            assertEquals(1, requests.get(), "Timed-out request must never reach the server");
            assertEquals(1, results.stream().filter(SampleResult::isSuccessful).count());
            SampleResult failure = results.stream().filter(result -> !result.isSuccessful()).findFirst().orElseThrow();
            assertEquals("Connection availability timeout", failure.getResponseCode());
            assertEquals(-1, failure.getConnectTime());
            assertTrue(failure.getTime() >= 90, "Pool wait must be included in elapsed time");
        } finally {
            release.countDown();
            users.shutdownNow();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    private static Queue<SampleResult> runLimitedParallelUser(
            int port, String user, int limit, int timeout, int count) {
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        ListedHashTree tree = new ListedHashTree();
        var flow = tree.add(loop);
        var defaults = new ConfigTestElement();
        defaults.setProperty(TestElement.GUI_CLASS,
                HttpDefaultsGui.class.getName());
        defaults.set(HTTPSamplerBaseSchema.INSTANCE.getMaxConnectionsPerHost(), limit);
        defaults.set(HTTPSamplerBaseSchema.INSTANCE.getConnectionAvailabilityTimeout(), timeout);
        // Embedded-download concurrency must not raise the configured or default cap.
        defaults.set(HTTPSamplerBaseSchema.INSTANCE.getConcurrentDownload(), true);
        defaults.set(HTTPSamplerBaseSchema.INSTANCE.getConcurrentDownloadPoolSize(), "12");
        flow.add(defaults);
        SuccessfulSamples listener = new SuccessfulSamples();
        flow.add(listener);
        ParallelController parallel = new ParallelController();
        parallel.setName("parallel");
        parallel.setMaxParallel(count);
        var branches = flow.add(parallel);
        for (int i = 0; i < count; i++) {
            branches.add(classicSampler(port, "/" + user + "/" + i));
        }
        ThreadGroup group = new ThreadGroup();
        group.setNumThreads(1);
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier());
        thread.setThreadGroup(group);
        thread.setThreadName(user);
        thread.run();
        return listener.results;
    }

    @Test
    public void childUserTeardownDoesNotCloseAnotherUsersActiveConnection() throws Exception {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch slowRequestReceived = new CountDownLatch(1);
        CountDownLatch releaseSlowResponse = new CountDownLatch(1);
        server.addMockServiceRequestListener((request, response) -> {
            if (request.getUrl().equals("/slow")) {
                slowRequestReceived.countDown();
                try {
                    if (!releaseSlowResponse.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for the other user's teardown");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        JMeterVariables previous = JMeterContextService.getContext().getVariables();
        HTTPSamplerProxy parent = classicSampler(server.port(), "/fast");
        try {
            server.stubFor(WireMock.get("/fast").willReturn(WireMock.ok("ok")));
            server.stubFor(WireMock.get("/slow").willReturn(WireMock.ok("ok")));
            JMeterContextService.getContext().setVariables(new JMeterVariables());
            // Populate the parent's pool before creating child threads. An inheritable
            // pool map makes both children use (and eventually close) this same map.
            assertTrue(parent.sample().isSuccessful());
            var slow = workers.submit(() -> sampleAsChildUser(server.port(), "/slow"));
            assertTrue(slowRequestReceived.await(5, TimeUnit.SECONDS));
            assertFalse(slow.isDone(), "The first user's request must still be in flight");
            var fast = workers.submit(() -> sampleAsChildUser(server.port(), "/fast"));
            assertTrue(fast.get(5, TimeUnit.SECONDS).isSuccessful());
            releaseSlowResponse.countDown();
            SampleResult slowResult = slow.get(5, TimeUnit.SECONDS);
            assertTrue(slowResult.isSuccessful(), slowResult.getResponseMessage());
        } finally {
            releaseSlowResponse.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            parent.threadFinished();
            JMeterContextService.getContext().setVariables(previous);
            server.stop();
        }
    }

    private static SampleResult sampleAsChildUser(int port, String path) {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        HTTPSamplerProxy sampler = classicSampler(port, path);
        try {
            return sampler.sample();
        } finally {
            sampler.threadFinished();
        }
    }

    private static HTTPSamplerProxy classicSampler(int port, String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProtocol("http");
        sampler.setDomain("localhost");
        sampler.setPort(port);
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setHttpProtocol("HTTP/1.1");
        sampler.setUseKeepAlive(true);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("5000");
        return sampler;
    }

    @Test
    public void factorySelectsHttpClient5Implementations() {
        assertArrayEquals(new String[] {
                HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT,
                HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1,
                HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2,
                HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3
        }, HTTPSamplerBase.getHttpProtocolList());

        HTTPSamplerProxy defaultProtocol = new HTTPSamplerProxy();
        assertEquals(HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT, defaultProtocol.getHttpProtocol());
        defaultProtocol.setProperty(HTTPSamplerBase.HTTP3_UPGRADE, true);
        assertEquals(Http3RuntimeSupport.isHttp3Supported() ? HTTPJavaHttp3Impl.class : HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(defaultProtocol.getImplementation(), defaultProtocol).getClass());

        HTTPSamplerProxy http2 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2.getImplementation(), http2).getClass());

        HTTPSamplerProxy http2SpaceLegacy = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2SpaceLegacy.set(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol(), "HTTP 2.0");
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2SpaceLegacy.getImplementation(), http2SpaceLegacy).getClass());

        HTTPSamplerProxy http2DotLegacy = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2DotLegacy.set(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol(), "HTTP/2.0");
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2DotLegacy.getImplementation(), http2DotLegacy).getClass());

        HTTPSamplerProxy http2PreferredLegacy = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2PreferredLegacy.set(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol(), "HTTP/2 preferred");
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2PreferredLegacy.getImplementation(), http2PreferredLegacy).getClass());

        HTTPSamplerProxy http1 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http1.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        assertEquals(HTTPHC5Impl.class,
                HTTPSamplerFactory.getImplementation(http1.getImplementation(), http1).getClass());

        HTTPSamplerProxy legacyHc4 = new HTTPSamplerProxy("HttpClient4");
        legacyHc4.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(legacyHc4.getImplementation(), legacyHc4).getClass());

        HTTPSamplerProxy legacyJava = new HTTPSamplerProxy("Java");
        legacyJava.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        assertEquals(HTTPHC5Impl.class,
                HTTPSamplerFactory.getImplementation(legacyJava.getImplementation(), legacyJava).getClass());
    }

    @Test
    public void http2VersionPolicyDistinguishesDefaultFromExplicitHttp2() {
        HTTPSamplerProxy defaultProtocol = new HTTPSamplerProxy();
        assertEquals(HttpVersionPolicy.NEGOTIATE, new HTTPHC5H2Impl(defaultProtocol).versionPolicy());

        HTTPSamplerProxy explicitHttp2 = new HTTPSamplerProxy();
        explicitHttp2.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HttpVersionPolicy.FORCE_HTTP_2, new HTTPHC5H2Impl(explicitHttp2).versionPolicy());
    }

    @Test
    public void protocolVersionIsFormattedForDisplay() {
        assertEquals("HTTP/1.1", HTTPHC5Impl.formatProtocolVersion(HttpVersion.HTTP_1_1));
        assertEquals("HTTP/2", HTTPHC5Impl.formatProtocolVersion(HttpVersion.HTTP_2_0));
    }

    @Test
    public void http2RemovesHttp1SpecificRequestHeaders() throws Exception {
        HttpGet request = new HttpGet(new URI("https://example.test/resource"));
        request.addHeader(HTTPConstants.HEADER_HOST, "example.test");
        request.addHeader(HTTPConstants.HEADER_CONNECTION, "Foo, Bar");
        request.addHeader("Foo", "foo-value");
        request.addHeader("Bar", "bar-value");
        request.addHeader("X-Custom", "custom-value");

        HTTPHC5H2Impl.removeHeadersUnsupportedByHttp2(request);

        assertFalse(request.containsHeader(HTTPConstants.HEADER_HOST));
        assertFalse(request.containsHeader(HTTPConstants.HEADER_CONNECTION));
        assertFalse(request.containsHeader("Foo"));
        assertFalse(request.containsHeader("Bar"));
        assertTrue(request.containsHeader("X-Custom"));
        assertEquals("custom-value", request.getFirstHeader("X-Custom").getValue());
    }

    @Test
    public void http2KeepsTeTrailersAndDropsOtherTeValues() throws Exception {
        HttpGet trailers = new HttpGet(new URI("https://example.test/resource"));
        trailers.addHeader(HTTPConstants.HEADER_CONNECTION, "keep-alive, TE");
        trailers.addHeader("te", "trailers");

        HTTPHC5H2Impl.removeHeadersUnsupportedByHttp2(trailers);

        assertEquals("trailers", trailers.getFirstHeader("TE").getValue());

        HttpGet gzip = new HttpGet(new URI("https://example.test/resource"));
        gzip.addHeader("TE", "trailers, gzip");

        HTTPHC5H2Impl.removeHeadersUnsupportedByHttp2(gzip);

        assertFalse(gzip.containsHeader("TE"));
    }

    @Test
    public void http2AddsContentLengthForKnownFileEntity() throws Exception {
        Path file = Files.createTempFile("jmeter-http2-content-length", ".txt");
        Files.writeString(file, "body", StandardCharsets.UTF_8);
        HttpPost request = new HttpPost(new URI("https://example.test/resource"));
        request.setEntity(new FileEntity(file.toFile(), ContentType.TEXT_PLAIN));

        setHttp2ContentLength(request);

        assertEquals("4", request.getFirstHeader(HTTPConstants.HEADER_CONTENT_LENGTH).getValue());
    }

    @Test
    public void contentLengthInterceptorLeavesHttp1FramingToDefaultProcessor() throws Exception {
        Path file = Files.createTempFile("jmeter-http-content-length", ".txt");
        Files.writeString(file, "body", StandardCharsets.UTF_8);
        HttpPost request = new HttpPost(new URI("http://example.test/resource"));
        request.setEntity(new FileEntity(file.toFile(), ContentType.TEXT_PLAIN));

        HttpCoreContext context = HttpCoreContext.create();
        context.setProtocolVersion(HttpVersion.HTTP_1_1);
        HTTPHC5H2Impl.setContentLengthForHttp2(request, request.getEntity(), context);

        assertFalse(request.containsHeader(HTTPConstants.HEADER_CONTENT_LENGTH));
    }

    @Test
    public void http2ReplacesMismatchedContentLengthWithActualLength() throws Exception {
        HttpPost request = new HttpPost(new URI("https://example.test/resource"));
        request.setHeader(HTTPConstants.HEADER_CONTENT_LENGTH, "123");
        request.setEntity(new ByteArrayEntity("body".getBytes(StandardCharsets.UTF_8), ContentType.TEXT_PLAIN));

        setHttp2ContentLength(request);

        assertEquals("4", request.getFirstHeader(HTTPConstants.HEADER_CONTENT_LENGTH).getValue());
    }

    @Test
    public void http2AddsContentLengthForKnownStringEntity() throws Exception {
        HttpPost request = new HttpPost(new URI("https://example.test/resource"));
        request.setEntity(new StringEntity("body", ContentType.TEXT_PLAIN));

        setHttp2ContentLength(request);

        assertEquals("4", request.getFirstHeader(HTTPConstants.HEADER_CONTENT_LENGTH).getValue());
    }

    @Test
    public void http2AddsZeroContentLengthAndOmitsUnknownLength() throws Exception {
        HttpPost emptyRequest = new HttpPost(new URI("https://example.test/empty"));
        emptyRequest.setEntity(new ByteArrayEntity(new byte[0], ContentType.APPLICATION_OCTET_STREAM));

        setHttp2ContentLength(emptyRequest);

        assertEquals("0", emptyRequest.getFirstHeader(HTTPConstants.HEADER_CONTENT_LENGTH).getValue());

        HttpPost streamingRequest = new HttpPost(new URI("https://example.test/stream"));
        streamingRequest.setEntity(new InputStreamEntity(new ByteArrayInputStream(new byte[] {1}), -1,
                ContentType.APPLICATION_OCTET_STREAM));

        streamingRequest.setHeader(HTTPConstants.HEADER_CONTENT_LENGTH, "1");

        setHttp2ContentLength(streamingRequest);

        assertFalse(streamingRequest.containsHeader(HTTPConstants.HEADER_CONTENT_LENGTH));
    }

    @Test
    public void http2AddsZeroContentLengthForEmptyPostButNotEmptyGet() throws Exception {
        HttpPost post = new HttpPost(new URI("https://example.test/post"));
        setHttp2ContentLength(post);

        assertEquals("0", post.getFirstHeader(HTTPConstants.HEADER_CONTENT_LENGTH).getValue());

        HttpGet get = new HttpGet(new URI("https://example.test/get"));
        get.setHeader(HTTPConstants.HEADER_CONTENT_LENGTH, "0");
        setHttp2ContentLength(get);

        assertFalse(get.containsHeader(HTTPConstants.HEADER_CONTENT_LENGTH));
    }

    private static void setHttp2ContentLength(HttpUriRequestBase request) {
        HttpCoreContext context = HttpCoreContext.create();
        context.setProtocolVersion(HttpVersion.HTTP_2);
        HTTPHC5H2Impl.setContentLengthForHttp2(request, request.getEntity(), context);
    }

    @Test
    public void http2ResponseHeadersUseHttp2StatusLineWithoutReasonPhrase() {
        BasicHttpResponse response = new BasicHttpResponse(200, "OK");
        response.setVersion(HttpVersion.HTTP_2);
        response.addHeader("Content-Type", "text/plain");

        assertEquals("HTTP/2 200\nContent-Type: text/plain\n", HTTPHC5H2Impl.getResponseHeaders(response));
    }

    @Test
    public void connectTimeoutErrorResultUsesSingleLineWhenEndpointsAreKnown() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("http://example.test:999/path").toURL());
        result.setLocalEndpoint("10.0.0.5:54000");
        result.setDestinationEndpoint("192.168.4.203:999");

        sampler.errorResult(new TransportException(connectTimeout("example.test", 999)), result);

        String expected = "Connection timeout after 10000 ms for 192.168.4.203:999";
        assertEquals("Connect timeout", result.getResponseCode());
        assertEquals(expected, result.getResponseMessage());
        assertEquals(expected, result.getResponseDataAsString());
        assertFalse(result.getResponseDataAsString().contains("\tat "));
        assertFalse(result.isSuccessful());
    }

    @Test
    public void connectTimeoutErrorResultFallsBackWhenLocalEndpointIsUnavailable() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("http://192.168.4.203:999/path").toURL());

        sampler.errorResult(connectTimeout("192.168.4.203", 999), result);

        assertEquals("Connection timeout after 10000 ms for 192.168.4.203:999",
                result.getResponseDataAsString());
        assertFalse(result.getResponseDataAsString().contains(ConnectTimeoutException.class.getName()));
        assertFalse(result.getResponseDataAsString().contains("\tat "));
    }

    @Test
    public void responseTimeoutErrorResultUsesSingleLineWhenEndpointsAreKnown() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("http://example.test:999/path").toURL());
        result.setLocalEndpoint("10.0.0.5:54000");
        result.setDestinationEndpoint("192.168.4.203:999");

        sampler.errorResult(new TransportException(responseTimeout()), result);

        String expected = "Response timeout after 15 ms waiting for response: "
                + "10.0.0.5 -> http://192.168.4.203:999 (example.test)";
        assertEquals("Response timeout", result.getResponseCode());
        assertEquals(expected, result.getResponseMessage());
        assertEquals(expected, result.getResponseDataAsString());
        assertFalse(result.getResponseDataAsString().contains("\tat "));
        assertFalse(result.isSuccessful());
    }

    @Test
    public void responseTimeoutErrorResultFallsBackWhenLocalEndpointIsUnavailable() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("http://192.168.4.203:999/path").toURL());

        sampler.errorResult(responseTimeout(), result);

        assertEquals("Response timeout after 15 ms waiting for response: http://192.168.4.203:999",
                result.getResponseDataAsString());
        assertFalse(result.getResponseMessage().contains("unavailable"));
        assertFalse(result.getResponseDataAsString().contains(InterruptedIOException.class.getName()));
        assertFalse(result.getResponseDataAsString().contains("\tat "));
    }

    @Test
    public void socketTimeoutMatchingConnectSettingUsesConnectTimeoutResult() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setConnectTimeout("10000");
        sampler.setResponseTimeout("60000");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("https://example.test/path").toURL());
        result.setDestinationEndpoint("192.168.4.203:443");

        sampler.errorResult(new TransportException(new SocketTimeoutException("10000 MILLISECONDS")), result);

        String expected = "Connection timeout after 10000 ms for example.test/192.168.4.203:443";
        assertEquals("Connect timeout", result.getResponseCode());
        assertEquals(expected, result.getResponseMessage());
        assertEquals(expected, result.getResponseDataAsString());
    }

    @Test
    public void socketTimeoutMatchingResponseSettingUsesResponseTimeoutResult() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setConnectTimeout("10000");
        sampler.setResponseTimeout("60000");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("https://example.test/path").toURL());
        result.setLocalEndpoint("10.0.0.5:54000");
        result.setDestinationEndpoint("192.168.4.203:443");

        sampler.errorResult(new TransportException(new SocketTimeoutException("60000 MILLISECONDS")), result);

        String expected = "Response timeout after 60000 ms waiting for response: "
                + "10.0.0.5 -> https://192.168.4.203:443 (example.test)";
        assertEquals("Response timeout", result.getResponseCode());
        assertEquals(expected, result.getResponseMessage());
        assertEquals(expected, result.getResponseDataAsString());
    }

    @Test
    public void connectionFailureErrorResultUsesSingleLineWhenTransportWrapsCause() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("http://whitehouse.gov/").toURL());

        sampler.errorResult(new TransportException(new NoRouteToHostException("No route to host")), result);

        String expected = "Connection error: No route to host: http://whitehouse.gov:80";
        assertEquals("No route to host", result.getResponseCode());
        assertEquals("No route to host", result.getResponseMessage());
        assertEquals(expected, result.getResponseDataAsString());
        assertFalse(result.getResponseDataAsString().contains("unavailable"));
        assertFalse(result.getResponseDataAsString().contains("ClassicToAsyncResponseConsumer"));
        assertFalse(result.getResponseDataAsString().contains("\tat "));
    }

    @Test
    public void connectionResetErrorResultUsesConciseCodeAndMessage() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("https://example.test/path").toURL());
        result.setLocalEndpoint("10.0.0.5:54000");
        result.setDestinationEndpoint("192.168.4.203:443");

        sampler.errorResult(new TransportException(new SocketException("Connection reset by peer")), result);

        assertEquals("Connection reset", result.getResponseCode());
        assertEquals("Connection reset by peer for example.test/192.168.4.203:443", result.getResponseMessage());
        assertEquals("Connection error: Connection reset by peer: "
                + "10.0.0.5 -> https://192.168.4.203:443 (example.test)",
                result.getResponseDataAsString());
        assertFalse(result.getResponseDataAsString().contains(TransportException.class.getName()));
        assertFalse(result.getResponseDataAsString().contains(SocketException.class.getName()));
        assertFalse(result.getResponseDataAsString().contains("\tat "));
    }

    @Test
    public void http2ResponseTimeoutSampleUsesSingleLineResponseData() {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        try {
            server.stubFor(WireMock.get("/slow")
                    .willReturn(WireMock.aResponse()
                            .withFixedDelay(250)
                            .withBody("late")));

            HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
            sampler.setDomain("localhost");
            sampler.setPort(server.port());
            sampler.setPath("/slow");
            sampler.setMethod(HTTPConstants.GET);
            sampler.setResponseTimeout("30");

            SampleResult result = sampler.sample();

            assertFalse(result.isSuccessful());
            assertTrue(result.getResponseDataAsString().startsWith("Response timeout after 30 ms waiting for response: "),
                    () -> "Expected compact response timeout, got: " + result.getResponseDataAsString());
            assertFalse(result.getResponseDataAsString().contains("\tat "));
        } finally {
            server.stop();
        }
    }

    @Test
    public void http2UsesSingleIoReactorThreadByDefault() {
        Object previous = JMeterUtils.getJMeterProperties().remove(HTTP2_IO_THREAD_COUNT);
        try {
            IOReactorConfig config = new HTTPHC5H2Impl(new HTTPSamplerProxy()).createIOReactorConfig();

            assertEquals(1, config.getIoThreadCount());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void http2IoReactorThreadCountCanBeOverridden() {
        Object previous = JMeterUtils.setProperty(HTTP2_IO_THREAD_COUNT, "3");
        try {
            IOReactorConfig config = new HTTPHC5H2Impl(new HTTPSamplerProxy()).createIOReactorConfig();

            assertEquals(3, config.getIoThreadCount());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void http2IoReactorThreadCountHasMinimumOfOne() {
        Object previous = JMeterUtils.setProperty(HTTP2_IO_THREAD_COUNT, "0");
        try {
            IOReactorConfig config = new HTTPHC5H2Impl(new HTTPSamplerProxy()).createIOReactorConfig();

            assertEquals(1, config.getIoThreadCount());
        } finally {
            restoreProperty(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "HTTP/2"})
    public void httpsIgnoresUntrustedCertificateAndWrongHostname(String protocol, @TempDir Path directory)
            throws Exception {
        Path keyStore = directory.resolve("wrong-host.p12");
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "password", "-keypass", "password",
                "-dname", "CN=wrong.example", "-ext", "SAN=dns:wrong.example", "-validity", "2")
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytool.waitFor(), output);
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig()
                .dynamicHttpsPort().keystorePath(keyStore.toString())
                .keystoreType("PKCS12").keystorePassword("password").keyManagerPassword("password"));
        server.start();
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        try {
            server.stubFor(WireMock.get("/tls").willReturn(WireMock.aResponse().withBody("ok")));
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
            sampler.setDomain("localhost");
            sampler.setPort(server.httpsPort());
            sampler.setPath("/tls");
            sampler.setMethod(HTTPConstants.GET);
            sampler.setHttpProtocol(protocol);
            sampler.setConnectTimeout("5000");
            sampler.setResponseTimeout("5000");
            SampleResult result = sampler.sample();
            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertEquals("ok", result.getResponseDataAsString());
            assertTrue(result.getResponseHeaders().startsWith(protocol), result.getResponseHeaders());
            assertTrue(result.getConnectTime() > 0,
                    () -> "Expected first TLS request to report connect time, got: " + result.getConnectTime());
            assertTrue(result.getHeadersSize() > 0,
                    () -> "Expected response headers size, got: " + result.getHeadersSize());
            assertTrue(result.getSentBytes() > 0,
                    () -> "Expected sent bytes, got: " + result.getSentBytes());
        } finally {
            sampler.threadFinished();
            server.stop();
        }
    }

    @Test
    public void http11ReportsSentBytes() {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        try {
            server.stubFor(WireMock.get("/sent-bytes").willReturn(WireMock.aResponse().withBody("ok")));

            HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
            sampler.setDomain("localhost");
            sampler.setPort(server.port());
            sampler.setPath("/sent-bytes");
            sampler.setMethod(HTTPConstants.GET);
            sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);

            SampleResult result = sampler.sample();

            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertTrue(result.getSentBytes() > 0,
                    () -> "Expected HTTP/1.1 sent bytes, got: " + result.getSentBytes());
            assertEquals("ok", result.getResponseDataAsString());
        } finally {
            server.stop();
        }
    }

    @Test
    public void defaultProtocolFallsBackToHttp11WhenServerDoesNotSupportHttp2() {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        try {
            server.stubFor(WireMock.get("/fallback").willReturn(WireMock.aResponse().withBody("ok")));

            HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
            sampler.setDomain("localhost");
            sampler.setPort(server.port());
            sampler.setPath("/fallback");
            sampler.setMethod(HTTPConstants.GET);

            SampleResult result = sampler.sample();

            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertTrue(result.getResponseHeaders().startsWith("HTTP/1.1"),
                    () -> "Expected HTTP/1.1 fallback, got: " + result.getResponseHeaders());
            assertTrue(result.getHeadersSize() > 0,
                    () -> "Expected fallback response headers size, got: " + result.getHeadersSize());
            assertTrue(result.getSentBytes() > 0,
                    () -> "Expected HTTP/2 fallback sent bytes, got: " + result.getSentBytes());
            assertEquals("ok", result.getResponseDataAsString());
        } finally {
            server.stop();
        }
    }

    @Test
    public void preemptiveBasicAuthAddsAuthorizationHeaderForMatchingUrl() throws Exception {
        HTTPHC5Impl sampler = samplerWithAuth("http://example.test/secure/");
        HttpGet request = new HttpGet(new URI("http://example.test/secure/resource"));

        sampler.setupPreemptiveBasicAuth(new URI("http://example.test/secure/resource").toURL(), request);

        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8)),
                request.getLastHeader(HTTPConstants.HEADER_AUTHORIZATION).getValue());
    }

    @Test
    public void preemptiveBasicAuthDoesNotAddAuthorizationHeaderForDifferentUrl() throws Exception {
        HTTPHC5Impl sampler = samplerWithAuth("http://example.test/secure/");
        HttpGet request = new HttpGet(new URI("http://example.test/public/resource"));

        sampler.setupPreemptiveBasicAuth(new URI("http://example.test/public/resource").toURL(), request);

        assertFalse(request.containsHeader(HTTPConstants.HEADER_AUTHORIZATION));
    }

    @Test
    public void preemptiveBasicAuthDoesNotOverrideExistingAuthorizationHeader() throws Exception {
        HTTPHC5Impl sampler = samplerWithAuth("http://example.test/secure/");
        HttpGet request = new HttpGet(new URI("http://example.test/secure/resource"));
        request.setHeader(HTTPConstants.HEADER_AUTHORIZATION, "Bearer token");

        sampler.setupPreemptiveBasicAuth(new URI("http://example.test/secure/resource").toURL(), request);

        assertEquals("Bearer token", request.getLastHeader(HTTPConstants.HEADER_AUTHORIZATION).getValue());
    }

    @Test
    public void authManagerCredentialsWithoutDomainUseUsernamePasswordCredentials() {
        Credentials credentials = HTTPHC5Impl.credentialsForAuthorization(authorizationWithDomain(""));

        assertTrue(credentials instanceof UsernamePasswordCredentials);
        UsernamePasswordCredentials userPassCredentials = (UsernamePasswordCredentials) credentials;
        assertEquals("user", userPassCredentials.getUserName());
        assertArrayEquals("pass".toCharArray(), userPassCredentials.getUserPassword());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void authManagerCredentialsWithDomainUseNtCredentials() {
        Credentials credentials = HTTPHC5Impl.credentialsForAuthorization(authorizationWithDomain("DOMAIN"));

        assertTrue(credentials instanceof NTCredentials);
        NTCredentials ntCredentials = (NTCredentials) credentials;
        assertEquals("user", ntCredentials.getUserName());
        assertArrayEquals("pass".toCharArray(), ntCredentials.getPassword());
        assertEquals("DOMAIN", ntCredentials.getDomain());
        assertEquals("DOMAIN", ntCredentials.getNetbiosDomain());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void managedCredentialsProviderServesProxyCredentialsForChallengeScope() {
        NTCredentials proxyCredentials = new NTCredentials("proxyUser", "proxyPass".toCharArray(), "localhost", "");
        HTTPHC5Impl.ManagedCredentialsProvider provider = new HTTPHC5Impl.ManagedCredentialsProvider(
                null, new AuthScope("proxy.test", 8888), proxyCredentials);
        // A challenge scope carries the protocol and the scheme name, the client key scope does not
        AuthScope challengeScope = new AuthScope(
                new HttpHost("http", "proxy.test", 8888), "proxy realm", StandardAuthScheme.BASIC);

        assertSame(proxyCredentials, provider.getCredentials(challengeScope, null));

        provider.clear();

        assertSame(proxyCredentials, provider.getCredentials(challengeScope, null),
                "proxy credentials must survive a clear() by the preemptive interceptor");
    }

    @Test
    public void managedCredentialsProviderMatchesChallengeRealmWhenAuthorizationHasNone() {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        AuthManager authManager = new AuthManager();
        authManager.set(-1, "http://example.test:8080/secure/", "user", "pass", "", "", Mechanism.DIGEST);
        HTTPHC5Impl.ManagedCredentialsProvider provider =
                new HTTPHC5Impl.ManagedCredentialsProvider(authManager, null, null);
        AuthScope challengeScope = new AuthScope(
                new HttpHost("http", "example.test", 8080), "server-realm", StandardAuthScheme.DIGEST);

        assertEquals("user", userNameOf(provider.getCredentials(challengeScope, null)));
    }

    @Test
    public void managedCredentialsProviderResolvesVariablesForEveryLookup() {
        JMeterVariables variables = new JMeterVariables();
        variables.put("authUser", "first-user");
        JMeterContextService.getContext().setVariables(variables);
        AuthManager authManager = new AuthManager();
        authManager.set(-1, "http://example.test:8080/secure/", "${authUser}", "pass", "", "", Mechanism.BASIC);
        HTTPHC5Impl.ManagedCredentialsProvider provider =
                new HTTPHC5Impl.ManagedCredentialsProvider(authManager, null, null);
        AuthScope challengeScope = new AuthScope(
                new HttpHost("http", "example.test", 8080), null, StandardAuthScheme.BASIC);

        assertEquals("first-user", userNameOf(provider.getCredentials(challengeScope, null)));

        variables.put("authUser", "second-user");

        assertEquals("second-user", userNameOf(provider.getCredentials(challengeScope, null)),
                "the HttpClient is cached per thread, its credentials must not be");
    }

    @Test
    public void managedCredentialsProviderPrefersAuthManagerOfCurrentSample() {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        AuthManager clientAuthManager = new AuthManager();
        clientAuthManager.set(-1, "http://example.test:8080/secure/", "client-user", "pass", "", "", Mechanism.BASIC);
        AuthManager sampleAuthManager = new AuthManager();
        sampleAuthManager.set(-1, "http://example.test:8080/secure/", "sample-user", "pass", "", "", Mechanism.BASIC);
        HTTPHC5Impl.ManagedCredentialsProvider provider =
                new HTTPHC5Impl.ManagedCredentialsProvider(clientAuthManager, null, null);
        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(HTTPHC5Impl.CONTEXT_ATTRIBUTE_AUTH_MANAGER, sampleAuthManager);
        AuthScope challengeScope = new AuthScope(
                new HttpHost("http", "example.test", 8080), null, StandardAuthScheme.BASIC);

        assertEquals("sample-user", userNameOf(provider.getCredentials(challengeScope, context)));
    }

    @Test
    public void managedCredentialsProviderServesCredentialsStoredPerRequest() {
        HTTPHC5Impl.ManagedCredentialsProvider provider =
                new HTTPHC5Impl.ManagedCredentialsProvider(null, null, null);
        AuthScope challengeScope = new AuthScope(
                new HttpHost("http", "example.test", 8080), "realm", StandardAuthScheme.BASIC);
        UsernamePasswordCredentials credentials =
                new UsernamePasswordCredentials("stored-user", "pass".toCharArray());

        provider.setCredentials(new AuthScope("http", "example.test", 8080, null, null), credentials);

        assertSame(credentials, provider.getCredentials(challengeScope, null));

        provider.clear();

        assertNull(provider.getCredentials(challengeScope, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "", "HTTP/3"})
    public void ntlmChallengeIsAnsweredWithVariableAuthManagerCredentials(String httpProtocol) throws Exception {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        HTTPJavaHttp3Impl http3 = new HTTPJavaHttp3Impl(sampler, HTTPJavaHttp3Impl.Http3Discovery.ALT_SVC_UPGRADE);
        JMeterVariables previousVariables = JMeterContextService.getContext().getVariables();
        try {
            // The stub accepts the type 1 message, it does not replay a full NTLM handshake:
            // what is under test is that the NTLM scheme is handed the AuthManager credentials
            server.stubFor(WireMock.get("/ntlm")
                    .atPriority(1)
                    .withHeader(HTTPConstants.HEADER_AUTHORIZATION, WireMock.matching("NTLM .+"))
                    .willReturn(WireMock.aResponse().withStatus(200).withBody("ok")));
            server.stubFor(WireMock.get("/ntlm")
                    .atPriority(2)
                    .willReturn(WireMock.aResponse()
                            .withStatus(401)
                            .withHeader("WWW-Authenticate", "NTLM")));

            JMeterVariables variables = new JMeterVariables();
            variables.put("authHost", "localhost");
            variables.put("authUser", "user");
            variables.put("authPass", "pass");
            variables.put("authDomain", "DOMAIN");
            JMeterContextService.getContext().setVariables(variables);
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
            sampler.setDomain("localhost");
            sampler.setPort(server.port());
            sampler.setPath("/ntlm");
            sampler.setMethod(HTTPConstants.GET);
            sampler.setHttpProtocol(httpProtocol);
            AuthManager authManager = new AuthManager();
            // The domain turns the credentials into NTCredentials, which is what lets the NTLM
            // scheme answer the challenge, the mechanism only drives the preemptive header
            authManager.set(-1,
                    "http://${authHost}:" + server.port() + "/ntlm",
                    "${authUser}",
                    "${authPass}",
                    "${authDomain}",
                    "",
                    Mechanism.DIGEST);
            new ValueReplacer().replaceValues(authManager);
            authManager.setRunningVersion(true);
            sampler.setAuthManager(authManager);

            SampleResult result = sampler.isHttp3Protocol()
                    ? http3.sample(sampler.getUrl(), HTTPConstants.GET, false, 0) : sampler.sample();

            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertEquals("ok", result.getResponseDataAsString());
        } finally {
            sampler.threadFinished();
            http3.threadFinished();
            JMeterContextService.getContext().setVariables(previousVariables);
            server.stop();
        }
    }

    @Test
    public void asyncCredentialsAreResolvedPerSampleBeforeIoThreadLookup() throws Exception {
        JMeterVariables previousVariables = JMeterContextService.getContext().getVariables();
        try (var executor = Executors.newSingleThreadExecutor()) {
            JMeterVariables variables = new JMeterVariables();
            JMeterContextService.getContext().setVariables(variables);
            variables.put("authHost", "example.test");
            variables.put("authUser", "first-user");
            variables.put("authPass", "first-pass");
            AuthManager manager = new AuthManager();
            manager.set(-1, "https://${authHost}/", "${authUser}", "${authPass}", "", "", Mechanism.DIGEST);
            HTTPHC5Impl.ManagedCredentialsProvider provider =
                    new HTTPHC5Impl.ManagedCredentialsProvider(manager, null, null);
            AuthScope scope = new AuthScope("https", "example.test", 443, null, StandardAuthScheme.DIGEST);
            HttpClientContext first = HttpClientContext.create();
            HTTPHC5Impl.resolveAuthManagerForAsyncRequest(first, manager);

            variables.put("authUser", "second-user");
            variables.put("authPass", "second-pass");
            HttpClientContext second = HttpClientContext.create();
            HTTPHC5Impl.resolveAuthManagerForAsyncRequest(second, manager);
            HttpClientContext unauthenticated = HttpClientContext.create();
            HTTPHC5Impl.resolveAuthManagerForAsyncRequest(unauthenticated, null);

            executor.submit(() -> {
                Credentials firstCredentials = provider.getCredentials(scope, first);
                assertEquals("first-user", userNameOf(firstCredentials));
                assertArrayEquals("first-pass".toCharArray(),
                        ((UsernamePasswordCredentials) firstCredentials).getUserPassword());
                Credentials secondCredentials = provider.getCredentials(scope, second);
                assertEquals("second-user", userNameOf(secondCredentials));
                assertArrayEquals("second-pass".toCharArray(),
                        ((UsernamePasswordCredentials) secondCredentials).getUserPassword());
                assertNull(provider.getCredentials(scope, unauthenticated),
                        "a sample without an AuthManager must not inherit cached credentials");
            }).get(10, TimeUnit.SECONDS);
        } finally {
            JMeterContextService.getContext().setVariables(previousVariables);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void requestConfigPrefersNtlmForChallengeAuthentication() throws Exception {
        HTTPHC5Impl sampler = new HTTPHC5Impl(new HTTPSamplerProxy());
        HttpGet request = new HttpGet(new URI("https://example.test/secure"));

        sampler.setupRequest(new URI("https://example.test/secure").toURL(), request, null);

        RequestConfig config = request.getConfig();
        List<String> expected = List.of(
                StandardAuthScheme.NTLM,
                StandardAuthScheme.SPNEGO,
                StandardAuthScheme.KERBEROS,
                StandardAuthScheme.DIGEST,
                StandardAuthScheme.BASIC);
        assertEquals(expected, config.getTargetPreferredAuthSchemes());
        assertEquals(expected, config.getProxyPreferredAuthSchemes());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void http2RequestConfigOnlyPrefersRegisteredAuthenticationSchemes() throws Exception {
        HTTPHC5H2Impl sampler = new HTTPHC5H2Impl(new HTTPSamplerProxy());
        HttpGet request = new HttpGet(new URI("https://example.test/secure"));

        sampler.setupRequest(new URI("https://example.test/secure").toURL(), request, null);

        RequestConfig config = request.getConfig();
        List<String> expected = List.of(
                StandardAuthScheme.NTLM,
                StandardAuthScheme.DIGEST,
                StandardAuthScheme.BASIC);
        assertEquals(expected, config.getTargetPreferredAuthSchemes());
        assertEquals(expected, config.getProxyPreferredAuthSchemes());
    }

    @Test
    public void authManagerUrlsResolveJMeterVariables() {
        JMeterVariables variables = new JMeterVariables();
        variables.put("fedHost", "acc.fed.example.test");
        JMeterContextService.getContext().setVariables(variables);

        assertEquals("https://acc.fed.example.test/adfs/ls/",
                HTTPHC5Impl.resolveVariables("https://${fedHost}/adfs/ls/"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void authManagerCredentialsResolveJMeterVariables() {
        JMeterVariables variables = new JMeterVariables();
        variables.put("authUser", "resolved-user");
        variables.put("authPass", "resolved-pass");
        variables.put("authDomain", "RESOLVED");
        JMeterContextService.getContext().setVariables(variables);
        AuthManager authManager = new AuthManager();
        authManager.set(-1,
                "http://example.test/secure/",
                "${authUser}",
                "${authPass}",
                "${authDomain}",
                "",
                Mechanism.BASIC);

        Credentials credentials = HTTPHC5Impl.credentialsForAuthorization(authManager.get(0));

        assertTrue(credentials instanceof NTCredentials);
        NTCredentials ntCredentials = (NTCredentials) credentials;
        assertEquals("resolved-user", ntCredentials.getUserName());
        assertArrayEquals("resolved-pass".toCharArray(), ntCredentials.getPassword());
        assertEquals("RESOLVED", ntCredentials.getDomain());
    }

    private static String userNameOf(Credentials credentials) {
        assertTrue(credentials instanceof UsernamePasswordCredentials,
                () -> "expected username/password credentials but got " + credentials);
        return ((UsernamePasswordCredentials) credentials).getUserName();
    }

    private static HTTPHC5Impl samplerWithAuth(String authUrl) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        AuthManager authManager = new AuthManager();
        authManager.set(-1, authUrl, "user", "pass", "", "*", Mechanism.BASIC);
        sampler.setAuthManager(authManager);
        return new HTTPHC5Impl(sampler);
    }

    private static Authorization authorizationWithDomain(String domain) {
        AuthManager authManager = new AuthManager();
        authManager.set(-1, "http://example.test/secure/", "user", "pass", domain, "", Mechanism.BASIC);
        return authManager.get(0);
    }

    private static ConnectTimeoutException connectTimeout(String host, int port) {
        return new ConnectTimeoutException(
                "Connect to http://" + host + ":" + port + " [/192.168.4.203] failed: 10000 MILLISECONDS",
                new NamedEndpoint() {
                    @Override
                    public String getHostName() {
                        return host;
                    }

                    @Override
                    public int getPort() {
                        return port;
                    }
                });
    }

    private static InterruptedIOException responseTimeout() {
        return new InterruptedIOException("Timeout blocked waiting for input (15 MILLISECONDS)");
    }

    private static void restoreProperty(Object previous) {
        if (previous == null) {
            JMeterUtils.getJMeterProperties().remove(HTTP2_IO_THREAD_COUNT);
        } else {
            JMeterUtils.getJMeterProperties().put(HTTP2_IO_THREAD_COUNT, previous);
        }
    }

    @Test
    public void hasArgumentsIncludesDisabledParameters() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        assertFalse(sampler.hasArguments());
        assertFalse(sampler.hasEnabledArguments());
        sampler.addArgument("ignored", "value");
        sampler.getArguments().getArgument(0).setEnabled(false);
        assertTrue(sampler.hasArguments());
        assertFalse(sampler.hasEnabledArguments());
        assertFalse(sampler.hasRequestBody());
        sampler.getArguments().getArgument(0).setEnabled(true);
        assertTrue(sampler.hasArguments());
        assertTrue(sampler.hasEnabledArguments());
        assertTrue(sampler.hasRequestBody());
    }

    static Stream<Arguments> bodylessRequests() {
        return Stream.of("classic", "http2", "jdk").flatMap(client ->
                Stream.of("GET", "HEAD", "OPTIONS", "DELETE", "POST", "PUT", "PATCH").flatMap(method ->
                        Stream.of("empty", "raw", "disabled", "disabledRaw", "explicitHeader")
                                .map(body -> Arguments.of(client, method, body))));
    }

    @ParameterizedTest
    @MethodSource("bodylessRequests")
    void bodylessRequestsDoNotInferContentType(String client, String method, String body, WireMockServer server)
            throws Exception {
        HTTPSamplerProxy sampler = sampler(server, method);
        sampler.setPostBodyRaw(body.equals("raw") || body.equals("disabledRaw"));
        if (body.startsWith("disabled")) {
            sampler.addArgument("ignored", "value");
            sampler.getArguments().getArgument(0).setEnabled(false);
        }
        boolean explicitHeader = body.equals("explicitHeader");
        if (explicitHeader) {
            sampler.setNativeHeaders(List.of(new Header("Content-Type", "application/json")));
        }
        sample(client, sampler);
        var request = server.getAllServeEvents().get(0).getRequest();
        assertEquals(method, request.getMethod().getName());
        assertEquals(0, request.getBody().length);
        String length = request.getHeader("Content-Length");
        if (client.equals("jdk")) {
            assertTrue(length == null || length.equals("0"), "Unexpected Content-Length: " + length);
        } else {
            boolean anticipatesBody = List.of("POST", "PUT", "PATCH").contains(method);
            assertEquals(anticipatesBody ? "0" : null, length);
        }
        if (explicitHeader) {
            assertEquals("application/json", request.getHeader("Content-Type"));
        } else {
            assertFalse(request.containsHeader("Content-Type"), request.getHeaders().toString());
        }
    }

    static Stream<Arguments> configuredBodies() {
        return Stream.of("classic", "http2", "jdk").flatMap(client ->
                Stream.of("DELETE", "POST", "PUT", "PATCH").flatMap(method ->
                        Stream.of("form", "raw", "multipart")
                                .filter(body -> !client.equals("jdk") || !body.equals("multipart"))
                                .map(body -> Arguments.of(client, method, body))));
    }

    @ParameterizedTest
    @MethodSource("configuredBodies")
    void configuredBodiesKeepTheirContentType(String client, String method, String body, WireMockServer server)
            throws Exception {
        HTTPSamplerProxy sampler = sampler(server, method);
        String expectedBody = "payload";
        String expectedType = "application/json";
        switch (body) {
        case "form":
            sampler.addArgument("key", "value");
            expectedBody = "key=value";
            expectedType = "application/x-www-form-urlencoded";
            break;
        case "raw":
            sampler.setPostBodyRaw(true);
            sampler.addNonEncodedArgument("", expectedBody, "");
            sampler.setNativeHeaders(List.of(new Header("Content-Type", expectedType)));
            break;
        case "multipart":
            sampler.setDoMultipart(true);
            sampler.addArgument("key", expectedBody);
            expectedType = "multipart/form-data";
            break;
        default:
            throw new IllegalArgumentException(body);
        }
        sample(client, sampler);
        var request = server.getAllServeEvents().get(0).getRequest();
        assertTrue(request.getHeader("Content-Type").startsWith(expectedType), request.getHeaders().toString());
        if (body.equals("multipart")) {
            assertTrue(request.getBodyAsString().contains(expectedBody));
        } else {
            assertEquals(expectedBody, request.getBodyAsString());
        }
    }

    static Stream<Arguments> fileBodies() {
        return Stream.of("classic", "http2", "jdk").flatMap(client ->
                Stream.of("GET", "DELETE", "POST", "PUT", "PATCH").flatMap(method ->
                        Stream.of("file", "disabled", "disabledRaw", "emptyFileWithDisabled")
                                .map(body -> Arguments.of(client, method, body))));
    }

    @ParameterizedTest
    @MethodSource("fileBodies")
    void fileBodiesIgnoreDisabledParameters(String client, String method, String body,
            WireMockServer server, @TempDir Path bodyDirectory) throws Exception {
        HTTPSamplerProxy sampler = sampler(server, method);
        String expectedBody = body.equals("emptyFileWithDisabled") ? "" : "file payload";
        Path file = bodyDirectory.resolve("body.json");
        Files.writeString(file, expectedBody);
        sampler.setHTTPFiles(new HTTPFileArg[] {new HTTPFileArg(file.toString(), "", "application/json")});
        if (!body.equals("file")) {
            sampler.addArgument("ignored", "value");
            sampler.getArguments().getArgument(0).setEnabled(false);
        }
        sampler.setPostBodyRaw(body.equals("disabledRaw"));
        sample(client, sampler);
        var request = server.getAllServeEvents().get(0).getRequest();
        assertEquals(method, request.getMethod().getName());
        assertEquals(expectedBody, request.getBodyAsString());
        assertEquals("application/json", request.getHeader("Content-Type"));
        String length = request.getHeader("Content-Length");
        if (client.equals("jdk") && expectedBody.isEmpty()) {
            // The JDK client can omit Content-Length for a zero-byte file body.
            assertTrue(length == null || length.equals("0"), "Unexpected Content-Length: " + length);
        } else {
            assertEquals(Integer.toString(expectedBody.length()), length);
        }
    }

    private static HTTPSamplerProxy sampler(WireMockServer server, String method) {
        server.stubFor(WireMock.any(WireMock.urlPathEqualTo("/body")).willReturn(WireMock.ok()));
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProtocol("http");
        sampler.setDomain("localhost");
        sampler.setPort(server.port());
        sampler.setPath("/body");
        sampler.setMethod(method);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("5000");
        return sampler;
    }

    private static void sample(String client, HTTPSamplerProxy sampler) throws Exception {
        assumeTrue(!client.equals("jdk") || Http3RuntimeSupport.isHttp3Supported(), "JDK HTTP/3 requires Java 27+");
        sampler.setHttpProtocol(client.equals("http2") ? "HTTP/2" : "HTTP/1.1");
        HTTPAbstractImpl impl = switch (client) {
        case "classic" -> new HTTPHC5Impl(sampler);
        case "http2" -> new HTTPHC5H2Impl(sampler);
        // Exercise the HTTP/3 implementation's body builder through its TCP discovery path.
        case "jdk" -> new HTTPJavaHttp3Impl(sampler, HTTPJavaHttp3Impl.Http3Discovery.ALT_SVC_UPGRADE);
        default -> throw new IllegalArgumentException(client);
        };
        try {
            HTTPSampleResult result = impl.sample(sampler.getUrl(), sampler.getMethod(), false, 0);
            assertTrue(result.isSuccessful(), result.getResponseMessage() + "\n" + result.getResponseDataAsString());
            if (client.equals("http2")) {
                assertTrue(result.getResponseHeaders().startsWith("HTTP/2"), result.getResponseHeaders());
            }
        } finally {
            impl.threadFinished();
        }
    }
}
