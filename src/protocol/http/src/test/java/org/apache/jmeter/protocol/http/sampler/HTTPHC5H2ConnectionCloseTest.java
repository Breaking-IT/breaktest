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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.http2.ssl.H2ServerTlsStrategy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.JOrphanUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A virtual user that leaves (thread end) or is replaced by a new visitor (iteration reset) must
 * close its HTTP/2 connections at once. A graceful close waits for the server's reply to the
 * TLS close, and HttpCore keeps its I/O thread busy for that whole round trip.
 * <p>
 * A proxy between the sampler and the server stops forwarding after the response, so the
 * server's close reply never arrives. The client connection must still close promptly.
 */
class HTTPHC5H2ConnectionCloseTest {

    /**
     * A graceful close waits about 1 s for a connection and 5 s for a whole client when the
     * server does not answer; an immediate close takes a few milliseconds.
     */
    private static final long MAX_CLOSE_MILLIS = 500;

    @TempDir
    static Path directory;

    private static SSLContext serverContext;

    private HttpAsyncServer server;

    private FreezingProxy proxy;

    @BeforeAll
    static void setUpServerContext() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = Files.createTempFile("jmeter", ".properties");
            JMeterUtils.loadJMeterProperties(properties.toString());
            Files.deleteIfExists(properties);
        }
        Path keyStorePath = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                "-storetype", "PKCS12", "-keystore", keyStorePath.toString(),
                "-storepass", "password", "-keypass", "password",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2")
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytool.waitFor(), output);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            keyStore.load(in, "password".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
    }

    @BeforeEach
    void startServer() throws Exception {
        server = H2ServerBootstrap.bootstrap()
                .setCanonicalHostName("localhost")
                .setVersionPolicy(HttpVersionPolicy.NEGOTIATE)
                .setTlsStrategy(new H2ServerTlsStrategy(serverContext))
                .register("*", new OkHandler())
                .create();
        server.start();
        ListenerEndpoint endpoint = server.listen(new InetSocketAddress("localhost", 0), URIScheme.HTTPS)
                .get(10, TimeUnit.SECONDS);
        proxy = new FreezingProxy(((InetSocketAddress) endpoint.getAddress()).getPort());
        JMeterContextService.getContext().setVariables(new JMeterVariables());
    }

    @AfterEach
    void stopServer() {
        proxy.close();
        server.close(CloseMode.IMMEDIATE);
    }

    @Test
    void threadEndClosesConnectionWithoutWaitingForServer() throws Exception {
        HTTPSamplerProxy sampler = createSampler();
        try {
            assertSuccessful(sampler.sample());
        } finally {
            proxy.freeze();
            sampler.threadFinished();
        }
        assertClosedPromptly("the HTTP/2 connection must be closed when the virtual user ends");
    }

    @Test
    void newIterationClosesConnectionWithoutWaitingForServer() throws Exception {
        HTTPSamplerProxy sampler = createSampler();
        try {
            assertSuccessful(sampler.sample());
            proxy.freeze();
            // Simulate the start of the next thread group iteration for a new visitor
            sampler.testIterationStart(null);
            assertClosedPromptly("the previous visitor's HTTP/2 connection must be closed on a new iteration");
        } finally {
            sampler.threadFinished();
        }
    }

    private void assertClosedPromptly(String message) throws InterruptedException {
        assertTrue(proxy.awaitClientClosed(10), message);
        long closeMillis = proxy.closeMillis();
        assertTrue(closeMillis < MAX_CLOSE_MILLIS,
                () -> message + " without waiting for the server, but it took " + closeMillis + " ms");
    }

    private HTTPSamplerProxy createSampler() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
        sampler.setDomain("localhost");
        sampler.setPort(proxy.getPort());
        sampler.setPath("/close");
        sampler.setMethod(HTTPConstants.GET);
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("5000");
        return sampler;
    }

    private static void assertSuccessful(SampleResult result) {
        assertTrue(result.isSuccessful(), () -> result.getResponseCode() + " " + result.getResponseMessage()
                + " " + result.getResponseDataAsString());
        assertEquals("ok", result.getResponseDataAsString());
        assertTrue(result.getResponseHeaders().startsWith(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2),
                result.getResponseHeaders());
    }

    private static final class OkHandler implements AsyncServerRequestHandler<Message<HttpRequest, Void>> {
        @Override
        public AsyncRequestConsumer<Message<HttpRequest, Void>> prepare(
                HttpRequest request, EntityDetails entityDetails, HttpContext context) {
            return new BasicRequestConsumer<>(entityDetails != null ? new DiscardingEntityConsumer<>() : null);
        }

        @Override
        public void handle(Message<HttpRequest, Void> request, ResponseTrigger responseTrigger,
                HttpContext context) throws IOException, HttpException {
            responseTrigger.submitResponse(AsyncResponseBuilder.create(200).setEntity("ok").build(), context);
        }
    }

    /**
     * Forwards one client connection to the server. After {@link #freeze()} it stops forwarding
     * in both directions, so the server never sees the client's close and never replies, but it
     * still notices when the client closes its socket.
     */
    private static final class FreezingProxy {
        private final ServerSocket listener;
        private final int serverPort;
        private final CountDownLatch clientClosed = new CountDownLatch(1);
        private volatile boolean frozen;
        private volatile long frozenAt;
        private volatile long clientClosedAt;
        private volatile Socket client;
        private volatile Socket upstream;

        FreezingProxy(int serverPort) throws IOException {
            this.serverPort = serverPort;
            this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::accept, "freezing-proxy-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int getPort() {
            return listener.getLocalPort();
        }

        void freeze() {
            frozenAt = System.nanoTime();
            frozen = true;
        }

        boolean awaitClientClosed(long seconds) throws InterruptedException {
            return clientClosed.await(seconds, TimeUnit.SECONDS);
        }

        /** Time from {@link #freeze()} until the client closed its socket. */
        long closeMillis() {
            return TimeUnit.NANOSECONDS.toMillis(clientClosedAt - frozenAt);
        }

        private void accept() {
            try {
                client = listener.accept();
                upstream = new Socket(InetAddress.getLoopbackAddress(), serverPort);
                pump(client, upstream, true);
                pump(upstream, client, false);
            } catch (IOException e) {
                // listener closed
            }
        }

        private void pump(Socket from, Socket to, boolean fromClient) {
            Thread thread = new Thread(() -> {
                byte[] buffer = new byte[16 * 1024];
                try {
                    InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream();
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (!frozen) {
                            out.write(buffer, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException e) {
                    // connection reset or closed
                }
                if (fromClient) {
                    clientClosedAt = System.nanoTime();
                    clientClosed.countDown();
                }
            }, fromClient ? "freezing-proxy-c2s" : "freezing-proxy-s2c");
            thread.setDaemon(true);
            thread.start();
        }

        void close() {
            JOrphanUtils.closeQuietly(listener);
            JOrphanUtils.closeQuietly(client);
            JOrphanUtils.closeQuietly(upstream);
        }
    }
}
