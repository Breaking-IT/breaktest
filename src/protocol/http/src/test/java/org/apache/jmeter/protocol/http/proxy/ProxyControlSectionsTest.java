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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.swing.DefaultComboBoxModel;
import javax.swing.SwingUtilities;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.gui.TreeNodeWrapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.proxy.gui.ProxyControlGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** The recorder in a test plan organised in sections. */
class ProxyControlSectionsTest extends JMeterTestCase {
    private final JMeterTreeModel model = new JMeterTreeModel();

    private JMeterTreeNode add(TestElement element, JMeterTreeNode parent) {
        JMeterTreeNode node = new JMeterTreeNode(element, model);
        model.insertNodeInto(node, parent, parent.getChildCount());
        return node;
    }

    private JMeterTreeNode recordMe() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("RecordMe");
        return add(threadGroup, model.getNodesOfType(ThreadGroupsSection.class).get(0));
    }

    @Test
    void recordingUsesTheConfigurationOfTheSharedProfile() throws Exception {
        Arguments shared = new Arguments();
        shared.addArgument("host", "example.com");
        add(shared, model.getNodesOfType(SharedProfile.class).get(0));
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);

        Method find = ProxyControl.class.getDeclaredMethod(
                "findApplicableElements", JMeterTreeNode.class, Class.class, boolean.class);
        find.setAccessible(true);
        Collection<?> variables = (Collection<?>) find.invoke(proxy, recordMe(), Arguments.class, false);

        assertTrue(variables.contains(shared), "variables of the Shared Profile apply to recorded samplers");
    }

    @Test
    void targetControllersIncludeThreadGroupsInsideSections() throws Exception {
        recordMe();
        GuiPackage previousGui = GuiPackage.getInstance();
        List<String> targets = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                GuiPackage.initInstance(new JMeterTreeListener(model), model);
                ProxyControlGui gui = new ProxyControlGui();
                ProxyControl configured = new ProxyControl();
                configured.setIgnoreHttpErrors(true);
                configured.setStoreRecordedExchanges(false);
                gui.configure(configured);
                ProxyControl saved = new ProxyControl();
                gui.modifyTestElement(saved);
                assertTrue(saved.getIgnoreHttpErrors());
                assertTrue(!saved.getStoreRecordedExchanges());
                DefaultComboBoxModel<?> combo = targetModel(gui);
                for (int i = 0; i < combo.getSize(); i++) {
                    targets.add(((TreeNodeWrapper) combo.getElementAt(i)).toString());
                }
            });
        } finally {
            var field = GuiPackage.class.getDeclaredField("guiPack");
            field.setAccessible(true);
            field.set(null, previousGui);
        }
        assertTrue(targets.stream().anyMatch(target -> target.endsWith("RecordMe")), "targets: " + targets);
    }

    @Test
    void recordsIntoTheTargetThreadGroupOfASectionedPlan(@TempDir Path directory) throws Exception {
        JMeterTreeNode target = recordMe();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(target);
        proxy.setPrefixHTTPSampleName("Before edit ");
        proxy.setSamplerFollowRedirects(false);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        proxy.setPort(port);
        model.addComponent(proxy, (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        proxy.startProxy();
        var releaseWorker = new java.util.concurrent.CountDownLatch(1);
        proxy.submitCapture(() -> {
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            HttpClient client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), port)))
                    .build();
            URI uri = URI.create("http://localhost:" + site.getAddress().getPort() + "/login");
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .header("Cookie", "session=recorded")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"login\":\"alice\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(!proxy.hasPendingRecording(), "Forwarding must finish even when capture processing is blocked");
            proxy.setPrefixHTTPSampleName("After edit ");
            proxy.setSamplerFollowRedirects(true);
            proxy.setStoreRecordedExchanges(false);
            proxy.setTarget(recordMe());
            releaseWorker.countDown();

            long deadline = System.currentTimeMillis() + 10_000;
            while (!proxy.hasPendingRecording() && System.currentTimeMillis() < deadline) {
                SwingUtilities.invokeAndWait(() -> { });
                Thread.sleep(100);
            }
            assertTrue(samplerNames(target).isEmpty(), "Recorded samplers wait for the stop/review step");
        } finally {
            releaseWorker.countDown();
            proxy.stopProxy();
            site.stop(0);
        }
        assertTrue(samplerNames(target).stream().anyMatch(name -> name.contains("/login")),
                "recorded into RecordMe: " + samplerNames(target));
        JMeterTreeNode samplerNode = model.getNodesOfType(HTTPSamplerBase.class).get(0);
        assertTrue(samplerNode.getName().contains("Before edit"));
        assertTrue(!((HTTPSamplerBase) samplerNode.getTestElement()).getFollowRedirects());
        assertTrue(proxy.getRecordingDiagnostics().summary().contains("Captured: 1"));
        var recorded = RecordedHarExchangeResolver.findFor(samplerNode, null).orElseThrow();
        assertTrue(recorded.request().startsWith("POST http://localhost:"));
        assertTrue(recorded.request().contains("{\"login\":\"alice\"}"));
        assertTrue(recorded.requestHeaders().contains("session=recorded"));
        assertEquals("200", recorded.responseCode());
        assertEquals("ok", recorded.responseBody());

        TestElement sampler = samplerNode.getTestElement();
        Path savedPlan = directory.resolve("recorded.jmx");
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);
        SaveService.saveTreeToFile(tree, savedPlan);
        String manifest = sampler.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY);
        String checksum = sampler.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY);
        var entries = SaveService.readRecordingBundle(savedPlan.toFile(), manifest, checksum).orElseThrow();
        var exchange = RecordedExchangeStore.resolveExchange(entries.get(manifest),
                sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY),
                name -> Optional.ofNullable(entries.get(name))).orElseThrow();
        assertEquals("{\"login\":\"alice\"}", new String(java.util.Base64.getDecoder().decode(
                exchange.path("request").path("postData").path("text").asText()), StandardCharsets.UTF_8));
        assertEquals("ok", exchange.path("response").path("content").path("text").asText());
        String requestWire = new String(java.util.Base64.getDecoder().decode(
                exchange.path("request").path("_breaktestWire").path("text").asText()), StandardCharsets.ISO_8859_1);
        assertTrue(requestWire.contains("session=recorded"));
        assertTrue(requestWire.endsWith("{\"login\":\"alice\"}"));
        assertTrue(!exchange.path("response").path("_breaktestWire").path("text").asText().isEmpty());

    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fallsBackToHttp11WhenOriginDoesNotSupportHttp2AndRecordsConnectionFailures(boolean rejectUpload,
            @TempDir Path directory) throws Exception {
        String password = "recording-test";
        Path storeFile = directory.resolve("server.p12");
        org.apache.jorphan.exec.KeyToolUtils.genkeypair(storeFile.toFile(), "localhost", password, 1,
                "CN=localhost", "SAN=dns:localhost");
        java.security.KeyStore keys = java.security.KeyStore.getInstance(storeFile.toFile(), password.toCharArray());
        javax.net.ssl.KeyManagerFactory managers = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
        javax.net.ssl.SSLContext serverContext = javax.net.ssl.SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(recordMe());
        try (ServerSocket available = new ServerSocket(0)) {
            proxy.setPort(available.getLocalPort());
        }
        model.addComponent(proxy, (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        proxy.startProxy();
        try (javax.net.ssl.SSLServerSocket origin = (javax.net.ssl.SSLServerSocket)
                serverContext.getServerSocketFactory().createServerSocket(0);
                var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            origin.setSoTimeout(5000);
            var parameters = origin.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{"http/1.1"});
            origin.setSSLParameters(parameters);
            var server = workers.submit(() -> {
                try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) origin.accept()) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    assertEquals("http/1.1", socket.getApplicationProtocol());
                    var request = HttpProxyTransport.readHead(socket.getInputStream());
                    assertEquals("/secure", request.target());
                    socket.getOutputStream().write(((rejectUpload ? "HTTP/1.1 413 Content Too Large" : "HTTP/1.1 200 OK")
                            + "\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").getBytes(StandardCharsets.ISO_8859_1));
                }
                return null;
            });
            try (java.net.Socket browser = new java.net.Socket("localhost", proxy.getPort())) {
                browser.setSoTimeout(5000);
                String authority = "localhost:" + origin.getLocalPort();
                browser.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
                // Use JMeter's configured TLS trust behavior for the recorder's generated certificate.
                var clientContext = ((org.apache.jmeter.util.JsseSSLManager) org.apache.jmeter.util.SSLManager.getInstance()).getContext();
                try (javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) clientContext.getSocketFactory()
                        .createSocket(browser, "localhost", proxy.getPort(), true)) {
                    var browserParameters = tls.getSSLParameters();
                    browserParameters.setApplicationProtocols(new String[]{"h2", "http/1.1"});
                    tls.setSSLParameters(browserParameters);
                    tls.startHandshake();
                    assertEquals("http/1.1", tls.getApplicationProtocol());
                    String uploadHeaders = rejectUpload ? "Content-Length: 100000\r\nExpect: 100-continue\r\n" : "";
                    tls.getOutputStream().write(((rejectUpload ? "POST" : "GET") + " /secure HTTP/1.1\r\nHost: "
                            + authority + "\r\n" + uploadHeaders + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    assertEquals(rejectUpload ? "413" : "200", HttpProxyTransport.readHead(tls.getInputStream()).target());
                    assertEquals("ok", new String(tls.getInputStream().readNBytes(2), StandardCharsets.UTF_8));
                }
            }
            server.get(10, java.util.concurrent.TimeUnit.SECONDS);
            int closedPort;
            try (ServerSocket unused = new ServerSocket(0)) {
                closedPort = unused.getLocalPort();
            }
            try (java.net.Socket browser = new java.net.Socket("localhost", proxy.getPort())) {
                browser.setSoTimeout(5000);
                browser.getOutputStream().write(("GET http://localhost:" + closedPort + "/failure HTTP/1.1\r\nHost: localhost\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                assertEquals("502", HttpProxyTransport.readHead(browser.getInputStream()).target());
            }
        } finally {
            proxy.stopProxy();
        }
        var samplers = model.getNodesOfType(HTTPSamplerBase.class);
        assertEquals(2, samplers.size());
        var failed = samplers.stream().filter(node -> node.getName().contains("/failure")).findFirst().orElseThrow();
        assertTrue(!failed.getTestElement().isEnabled());
        assertTrue(failed.getTestElement().getComment().contains("ConnectException"));
        var success = samplers.stream().filter(node -> node.getName().contains("/secure")).findFirst().orElseThrow();
        assertTrue(success.getTestElement().isEnabled(), "A fully forwarded HTTP error is not a failed capture");
        assertEquals("ok", RecordedHarExchangeResolver.findFor(success, null).orElseThrow().responseBody());
    }

    @Test
    void negotiatesHttp2AndRecordsMultipleStreams(@TempDir Path directory) throws Exception {
        String password = "recording-test";
        Path storeFile = directory.resolve("h2-server.p12");
        org.apache.jorphan.exec.KeyToolUtils.genkeypair(storeFile.toFile(), "localhost", password, 1,
                "CN=localhost", "SAN=dns:localhost");
        var keys = java.security.KeyStore.getInstance(storeFile.toFile(), password.toCharArray());
        var managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
        var serverContext = javax.net.ssl.SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        var server = org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap.bootstrap()
                .setCanonicalHostName("localhost")
                .setVersionPolicy(org.apache.hc.core5.http2.HttpVersionPolicy.FORCE_HTTP_2)
                .setTlsStrategy(new org.apache.hc.core5.http2.ssl.H2ServerTlsStrategy(serverContext))
                .register("*", new Http2Handler()).create();
        server.start();
        var endpoint = server.listen(new InetSocketAddress("localhost", 0), org.apache.hc.core5.http.URIScheme.HTTPS)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        int port = ((InetSocketAddress) endpoint.getAddress()).getPort();
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(recordMe());
        try (ServerSocket available = new ServerSocket(0)) {
            proxy.setPort(available.getLocalPort());
        }
        model.addComponent(proxy, (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        proxy.startProxy();
        var context = ((org.apache.jmeter.util.JsseSSLManager) org.apache.jmeter.util.SSLManager.getInstance()).getContext();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(context)
                .proxy(ProxySelector.of(new InetSocketAddress("localhost", proxy.getPort()))).build()) {
            // Warm up one connection, then exercise concurrent streams and the shared HPACK table.
            var warmup = client.sendAsync(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/warmup"))
                    .timeout(java.time.Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString(),
                    (initial, push, accept) -> { }).get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(HttpClient.Version.HTTP_2, warmup.version());
            var futures = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 5; i++) {
                futures.add(client.sendAsync(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/stream/" + i))
                        .timeout(java.time.Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("body-" + i))
                        .build(), HttpResponse.BodyHandlers.ofString(), (initial, push, accept) -> { }));
            }
            for (var future : futures) {
                var response = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(HttpClient.Version.HTTP_2, response.version());
                assertEquals(200, response.statusCode());
                assertEquals("http2 response", response.body());
            }
        } finally {
            proxy.stopProxy();
            server.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
        }
        var samplers = model.getNodesOfType(HTTPSamplerBase.class);
        assertEquals(6, samplers.size());
        var parallel = model.getNodesOfType(org.apache.jmeter.control.ParallelController.class);
        assertEquals(1, parallel.size(), "Actual overlapping HTTP/2 streams must remain parallel after recording");
        assertEquals(5, parallel.get(0).getChildCount());
        for (var node : samplers) {
            var sampler = (HTTPSamplerBase) node.getTestElement();
            assertTrue(sampler.isHttp2Protocol());
            assertTrue(sampler.isEnabled(), sampler.getComment());
            assertEquals("http2 response", RecordedHarExchangeResolver.findFor(node, null).orElseThrow().responseBody());
        }
    }

    private static final class Http2Handler implements org.apache.hc.core5.http.nio.AsyncServerRequestHandler<
            org.apache.hc.core5.http.Message<org.apache.hc.core5.http.HttpRequest, Void>> {
        private record Pending(ResponseTrigger trigger, org.apache.hc.core5.http.protocol.HttpContext context) { }
        private final List<Pending> pending = new ArrayList<>();

        @Override
        public org.apache.hc.core5.http.nio.AsyncRequestConsumer<org.apache.hc.core5.http.Message<org.apache.hc.core5.http.HttpRequest, Void>> prepare(
                org.apache.hc.core5.http.HttpRequest request, org.apache.hc.core5.http.EntityDetails entity,
                org.apache.hc.core5.http.protocol.HttpContext context) {
            return new org.apache.hc.core5.http.nio.support.BasicRequestConsumer<>(
                    entity == null ? null : new org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer<>());
        }

        @Override
        public void handle(org.apache.hc.core5.http.Message<org.apache.hc.core5.http.HttpRequest, Void> request,
                ResponseTrigger trigger, org.apache.hc.core5.http.protocol.HttpContext context)
                throws java.io.IOException, org.apache.hc.core5.http.HttpException {
            List<Pending> ready;
            synchronized (pending) {
                if (request.getHead().getRequestUri().startsWith("/stream/")) {
                    pending.add(new Pending(trigger, context));
                    if (pending.size() < 5) {
                        return;
                    }
                    ready = List.copyOf(pending);
                    pending.clear();
                } else {
                    ready = List.of(new Pending(trigger, context));
                }
            }
            for (Pending response : ready) {
                response.trigger().submitResponse(org.apache.hc.core5.http.nio.support.AsyncResponseBuilder.create(200)
                        .setEntity("http2 response").build(), response.context());
            }
        }
    }

    @Test
    void overlappingHttp1ConnectionsAreInsertedInParallel() throws Exception {
        var arrived = new java.util.concurrent.CountDownLatch(2);
        var site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            site.setExecutor(executor);
            site.createContext("/", exchange -> {
                arrived.countDown();
                try {
                    if (!arrived.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new java.io.IOException("Both browser requests must reach the server before responding");
                    }
                    exchange.sendResponseHeaders(200, -1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            site.start();
            var proxy = new ProxyControl();
            proxy.setNonGuiTreeModel(model);
            proxy.setStoreRecordedExchanges(false);
            proxy.setProperty("ProxyControlGui.capture_http_headers", false);
            proxy.setTarget(recordMe());
            try (ServerSocket available = new ServerSocket(0)) {
                proxy.setPort(available.getLocalPort());
            }
            proxy.startProxy();
            try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .proxy(ProxySelector.of(new InetSocketAddress("localhost", proxy.getPort()))).build()) {
                URI address = URI.create("http://localhost:" + site.getAddress().getPort() + "/");
                var first = client.sendAsync(HttpRequest.newBuilder(address).header("X-Recorded", "kept").build(), HttpResponse.BodyHandlers.discarding());
                var second = client.sendAsync(HttpRequest.newBuilder(address.resolve("/second"))
                        .header("X-Recorded", "kept").build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(200, first.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
                assertEquals(200, second.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            } finally {
                proxy.stopProxy();
                site.stop(0);
            }
        }
        var parallel = model.getNodesOfType(org.apache.jmeter.control.ParallelController.class);
        assertEquals(1, parallel.size());
        assertEquals(2, parallel.get(0).getChildCount());
        for (var node : model.getNodesOfType(HTTPSamplerBase.class)) {
            var sampler = (HTTPSamplerBase) node.getTestElement();
            assertTrue(sampler.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty());
            assertTrue(RecordedHarExchangeResolver.findFor(node, null).isEmpty());
            assertTrue(sampler.getNativeHeaders() != null);
            boolean headerFound = false;
            for (var property : sampler.getNativeHeaders()) {
                var header = (org.apache.jmeter.protocol.http.control.Header) property.getObjectValue();
                if (header.getName().equalsIgnoreCase("X-Recorded")) {
                    assertEquals("kept", header.getValue());
                    headerFound = true;
                }
            }
            assertTrue(headerFound, "Turning storage off and loading the legacy flag must still retain replay headers");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void stoppingRecorderFlushesAnUnfinishedStream(boolean sse) throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/events", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", sse ? "text/event-stream" : "text/plain");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        site.start();
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(recordMe());
        try (ServerSocket available = new ServerSocket(0)) {
            proxy.setPort(available.getLocalPort());
        }
        model.addComponent(proxy, (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        proxy.startProxy();
        try (java.net.Socket browser = new java.net.Socket("localhost", proxy.getPort())) {
            browser.setSoTimeout(5000);
            browser.getOutputStream().write(("GET http://localhost:" + site.getAddress().getPort()
                    + "/events HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
            assertEquals("4\r\ndata\r\n", new String(browser.getInputStream().readNBytes(9), StandardCharsets.UTF_8));
            proxy.stopProxy();
            var samplers = model.getNodesOfType(HTTPSamplerBase.class);
            assertEquals(1, samplers.size());
            assertEquals(sse, samplers.get(0).getTestElement().isEnabled(),
                    "Stopping an SSE session is expected; an interrupted ordinary response is a failed capture");
            assertEquals("data", RecordedHarExchangeResolver.findFor(samplers.get(0), null).orElseThrow().responseBody());
        } finally {
            release.countDown();
            proxy.stopProxy();
            site.stop(0);
        }
    }

    @Test
    void retainsTlsNegotiationFailureWithItsConnectDestination() throws Exception {
        var proxy = new ProxyControl();
        // Generate the certificate during setup, outside the browser's handshake timeout.
        proxy.setSslDomains("localhost");
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(recordMe());
        try (ServerSocket available = new ServerSocket(0)) {
            proxy.setPort(available.getLocalPort());
        }
        model.addComponent(proxy, (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        proxy.startProxy();
        try (var browser = new java.net.Socket("localhost", proxy.getPort())) {
            browser.setSoTimeout(5000);
            browser.getOutputStream().write(("CONNECT localhost:443 HTTP/1.1\r\nHost: localhost:443\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertEquals("200", HttpProxyTransport.readHead(browser.getInputStream()).target());
            // Plain HTTP on the TLS socket fails before there can be a captured HTTP request.
            browser.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            try {
                browser.getInputStream().readAllBytes();
            } catch (java.net.SocketException ignored) {
                // The failed handshake may close with a reset instead of EOF.
            }
        } finally {
            proxy.stopProxy();
        }
        var failures = proxy.getRecordingDiagnostics().uncapturedFailures();
        assertEquals(1, failures.size());
        assertEquals("https://localhost:443", failures.get(0).url());
        assertEquals("CONNECT (TLS handshake)", failures.get(0).method());
        assertTrue(failures.get(0).reason().contains("SSL"));
        assertTrue(model.getNodesOfType(HTTPSamplerBase.class).isEmpty());
    }

    @Test
    void disablesTransportFailuresAndOnlyAddsOptInAssertionsForHttpErrors() throws Exception {
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(recordMe());
        proxy.setIgnoreHttpErrors(true);
        for (String status : List.of("0", "500", "404", "200")) {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setName(status);
            sampler.setDomain("example.invalid");
            sampler.setPath("/" + status);
            HTTPSampleResult result = new HTTPSampleResult();
            result.setURL(sampler.getUrl());
            result.setResponseCode(status);
            result.setResponseMessage("0".equals(status) ? "Connection reset" : "HTTP response");
            proxy.deliverSampler(sampler, new TestElement[0], result);
        }
        proxy.setIgnoreHttpErrors(false);
        HTTPSamplerProxy withoutAssertion = new HTTPSamplerProxy();
        withoutAssertion.setName("without assertion");
        withoutAssertion.setDomain("example.invalid");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(withoutAssertion.getUrl());
        result.setResponseCode("503");
        proxy.deliverSampler(withoutAssertion, new TestElement[0], result);
        proxy.stopProxy(); // Also flushes the final recording batch.

        var samplers = model.getNodesOfType(HTTPSamplerBase.class);
        assertEquals(5, samplers.size());
        var failure = samplers.stream().filter(node -> node.getName().equals("0")).findFirst().orElseThrow();
        assertTrue(!failure.getTestElement().isEnabled());
        assertTrue(failure.getTestElement().getComment().contains("Connection reset"));
        assertTrue(RecordedHarExchangeResolver.findFor(failure, null).isPresent());
        var assertions = model.getNodesOfType(org.apache.jmeter.assertions.ResponseAssertion.class);
        assertEquals(2, assertions.size());
        for (var assertion : assertions) {
            assertTrue(((org.apache.jmeter.assertions.ResponseAssertion) assertion.getTestElement()).getAssumeSuccess());
            assertTrue(((JMeterTreeNode) assertion.getParent()).getTestElement().isEnabled());
        }
    }

    @Test
    void filteredRequestsDoNotCreateRecordings() throws Exception {
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(recordMe());
        proxy.addExcludedPattern(".*excluded.*");
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProtocol("https");
        sampler.setDomain("example.invalid");
        sampler.setPath("/excluded");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(sampler.getUrl());
        result.setContentType("text/plain");
        result.setResponseData("excluded", "UTF-8");
        result.setResponseCode("200");

        proxy.deliverSampler(sampler, new TestElement[0], result);

        assertEquals("", sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY));
        assertEquals("", sampler.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
    }

    private static List<String> samplerNames(JMeterTreeNode node) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof HTTPSamplerBase) {
                names.add(child.getName());
            }
            names.addAll(samplerNames(child));
        }
        return names;
    }

    private static DefaultComboBoxModel<?> targetModel(ProxyControlGui gui) {
        try {
            var field = ProxyControlGui.class.getDeclaredField("targetNodesModel");
            field.setAccessible(true);
            return (DefaultComboBoxModel<?>) field.get(gui);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
