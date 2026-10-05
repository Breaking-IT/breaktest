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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HttpProxyConfiguration;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class WebSocketProxyTest extends JMeterTestCase {
    @BeforeEach
    void setup() {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
    }

    @AfterEach
    void cleanup() {
        WebSocketSessions.cleanup();
        JMeterContextService.getContext().clear();
    }

    static ConfigTestElement defaults(int port) {
        var defaults = new ConfigTestElement();
        defaults.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui");
        defaults.setProperty("HTTPSampler.proxyHost", "127.0.0.1");
        defaults.setProperty("HTTPSampler.proxyPort", Integer.toString(port));
        return defaults;
    }

    @Test
    void defaultsRouteRealHandshakeThroughConnectTunnel() throws Exception {
        try (var peer = new WebSocketSamplerTest.Peer(false); var proxy = new Tunnel(URI.create(peer.url()))) {
            var sampler = new WebSocketConnectSampler();
            sampler.setUrl(peer.url());
            assertTrue(sampler.applies(defaults(proxy.port())));
            var filtered = new ConfigTestElement();
            filtered.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui");
            filtered.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_include");
            filtered.setProperty("HTTPSampler.proxyDestinationPatterns", URI.create(peer.url()).getHost());
            sampler.addTestElement(filtered);
            sampler.addTestElement(defaults(proxy.port()));
            var result = sampler.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertTrue(proxy.request.get(3, TimeUnit.SECONDS).startsWith("CONNECT "));
            var close = new WebSocketCloseSampler();
            assertTrue(close.sample(null).isSuccessful());
        }
    }

    @Test
    void filtersAndBypassUseDirectConnectionsAndDoNotLeakAcrossIterations() throws Exception {
        for (String mode : new String[] {"proxy_filter_include", "proxy_filter_exclude", "proxy_filter_direct"}) {
            try (var peer = new WebSocketSamplerTest.Peer(false); var unused = new ServerSocket(0)) {
                var sampler = new WebSocketConnectSampler();
                sampler.setUrl(peer.url());
                sampler.setProperty("HTTPSampler.proxyDestinationMode", mode);
                sampler.setProperty("HTTPSampler.proxyDestinationPatterns",
                        mode.equals("proxy_filter_include") ? "different.invalid" : URI.create(peer.url()).getHost());
                sampler.setRunningVersion(true);
                sampler.addTestElement(defaults(unused.getLocalPort()));
                var result = sampler.sample(null);
                assertTrue(result.isSuccessful(), result::getResponseMessage);
                unused.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, unused::accept);
                sampler.recoverRunningVersion();
                assertEquals("", sampler.getPropertyAsString("HTTPSampler.proxyHost"));
                WebSocketSessions.cleanup();
            }
        }
    }

    @Test
    void transportPoolSeparatesProxyCredentialsAndDirectRoutes() throws Exception {
        var uri = URI.create("ws://example.com/");
        var alice = new HttpProxyConfiguration.Route("127.0.0.1", 8080, "alice", "secret");
        var bob = new HttpProxyConfiguration.Route("127.0.0.1", 8080, "bob", "other");
        try (var first = WebSocketTransportPool.acquire(uri, alice);
                var same = WebSocketTransportPool.acquire(uri, alice);
                var other = WebSocketTransportPool.acquire(uri, bob);
                var direct = WebSocketTransportPool.acquire(uri, HttpProxyConfiguration.Route.DIRECT)) {
            assertSame(first.client(), same.client());
            assertNotSame(first.client(), other.client());
            assertNotSame(first.client(), direct.client());
            assertEquals(Proxy.NO_PROXY, direct.client().proxy().orElseThrow().select(uri).get(0));
            var auth = first.client().authenticator().orElseThrow();
            assertNull(auth.requestPasswordAuthenticationInstance("127.0.0.1", null, 8080, "http", "realm", "basic",
                    URI.create("http://127.0.0.1:8080/").toURL(), Authenticator.RequestorType.SERVER));
            assertNull(auth.requestPasswordAuthenticationInstance("other.invalid", null, 8080, "http", "realm", "basic",
                    URI.create("http://other.invalid:8080/").toURL(), Authenticator.RequestorType.PROXY));
            assertEquals("alice", auth.requestPasswordAuthenticationInstance("127.0.0.1", null, 8080, "http", "realm", "basic",
                    URI.create("http://127.0.0.1:8080/").toURL(), Authenticator.RequestorType.PROXY).getUserName());
        }
    }

    @Test
    void unsupportedHttpsProxyIsRejectedOnlyWhenSelected() {
        var sampler = new WebSocketConnectSampler();
        var settings = defaults(8080);
        settings.setProperty("HTTPSampler.proxyScheme", "https");
        sampler.addTestElement(settings);
        assertThrows(IllegalArgumentException.class,
                () -> HttpProxyConfiguration.resolve(sampler, URI.create("wss://example.com/")));
        sampler.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_direct");
        assertTrue(HttpProxyConfiguration.resolve(sampler, URI.create("wss://example.com/")).direct());
    }

    @Test
    void unavailableProxyFailsWithoutDirectFallback() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try (var peer = new WebSocketSamplerTest.Peer(false)) {
            var sampler = new WebSocketConnectSampler();
            sampler.setUrl(peer.url());
            sampler.addTestElement(defaults(port));
            assertFalse(sampler.sample(null).isSuccessful());
        }
    }

    static final class Tunnel implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final CompletableFuture<String> request = new CompletableFuture<>();
        private volatile Socket client;
        private volatile Socket upstream;

        Tunnel(URI destination) throws IOException {
            Thread.ofVirtual().start(() -> {
                try {
                    client = server.accept();
                    var input = client.getInputStream();
                    var header = new ByteArrayOutputStream();
                    int next;
                    while ((next = input.read()) != -1) {
                        header.write(next);
                        if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) { break; }
                    }
                    String line = header.toString(StandardCharsets.US_ASCII).split("\r\n")[0];
                    request.complete(line);
                    if (!line.startsWith("CONNECT ")) { throw new IOException("Expected CONNECT: " + line); }
                    upstream = new Socket(destination.getHost(), destination.getPort());
                    client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    Thread.ofVirtual().start(() -> transfer(client, upstream));
                    transfer(upstream, client);
                } catch (IOException error) { request.completeExceptionally(error); }
            });
        }

        int port() { return server.getLocalPort(); }

        private static void transfer(Socket from, Socket to) {
            try { from.getInputStream().transferTo(to.getOutputStream()); }
            catch (IOException ignored) { /* Closed with the fixture. */ }
        }

        @Override
        public void close() throws IOException {
            server.close();
            if (client != null) { client.close(); }
            if (upstream != null) { upstream.close(); }
        }
    }
}
