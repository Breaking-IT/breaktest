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

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketCloseSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WebSocketProxyRecordingTest extends JMeterTestCase {
    @ParameterizedTest
    @CsvSource({"false,true,4,false", "true,true,4,false", "false,false,2,false", "false,true,1,false",
        "false,true,3,false", "false,true,4,true"})
    void recordsNativeMessagesAndReplayOverPlainAndTls(boolean secure, boolean store, int grouping, boolean stopEarly,
            @TempDir Path directory) throws Exception {
        SSLContext tlsContext = serverContext(directory);
        JMeterTreeModel model = new JMeterTreeModel();
        var group = new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), model);
        model.insertNodeInto(group, model.getNodesOfType(ThreadGroupsSection.class).get(0), 0);
        var proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(group);
        proxy.setStoreRecordedExchanges(store);
        proxy.setGroupingMode(grouping);
        proxy.setPort(0);
        try (ServerSocket available = new ServerSocket(0)) {
            proxy.setPort(available.getLocalPort());
        }
        byte[] sent = WebSocketProxyRecorderTest.frame(129, true, "hello".getBytes(StandardCharsets.UTF_8));
        byte[] received = WebSocketProxyRecorderTest.frame(130, false, new byte[]{0, (byte) 255, 1});
        byte[] close = WebSocketProxyRecorderTest.frame(136, true, new byte[]{3, (byte) 232});
        proxy.startProxy();
        try (ServerSocket origin = secure ? tlsContext.getServerSocketFactory().createServerSocket(0) : new ServerSocket(0);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            origin.setSoTimeout(5000);
            var server = workers.submit(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    assertEquals("/socket", HttpProxyTransport.readHead(socket.getInputStream()).target());
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n"
                            + "Upgrade: websocket\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    assertArrayEquals(sent, socket.getInputStream().readNBytes(sent.length));
                    socket.getOutputStream().write(received);
                    if (stopEarly) {
                        assertEquals(-1, socket.getInputStream().read());
                    } else {
                        assertArrayEquals(close, socket.getInputStream().readNBytes(close.length));
                        socket.getOutputStream().write(WebSocketProxyRecorderTest.frame(136, false, new byte[]{3, (byte) 232}));
                    }
                }
                return null;
            });
            try (Socket raw = new Socket("localhost", proxy.getPort())) {
                raw.setSoTimeout(5000);
                String authority = "localhost:" + origin.getLocalPort();
                Socket browser = raw;
                if (secure) {
                    raw.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    assertEquals("200", HttpProxyTransport.readHead(raw.getInputStream()).target());
                    var context = ((org.apache.jmeter.util.JsseSSLManager) org.apache.jmeter.util.SSLManager.getInstance()).getContext();
                    browser = context.getSocketFactory().createSocket(raw, "localhost", proxy.getPort(), false);
                    ((SSLSocket) browser).startHandshake();
                }
                try (Socket connection = browser) {
                    connection.getOutputStream().write(("GET " + (secure ? "" : "http://" + authority)
                            + "/socket HTTP/1.1\r\nHost: " + authority + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
                            + "Sec-WebSocket-Key: old-key\r\nSec-WebSocket-Protocol: chat\r\nOrigin: https://example.test\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    assertEquals("101", HttpProxyTransport.readHead(connection.getInputStream()).target());
                    connection.getOutputStream().write(sent);
                    assertArrayEquals(received, connection.getInputStream().readNBytes(received.length));
                    if (stopEarly) {
                        proxy.stopProxy();
                    } else {
                        connection.getOutputStream().write(close);
                        assertEquals(4, connection.getInputStream().readNBytes(4).length);
                    }
                }
            }
            server.get(10, TimeUnit.SECONDS);
        } finally {
            proxy.stopProxy();
        }
        var connects = model.getNodesOfType(WebSocketConnectSampler.class);
        assertEquals(1, connects.size());
        var connect = (WebSocketConnectSampler) connects.get(0).getTestElement();
        assertTrue(connect.getUrl().startsWith(secure ? "wss://" : "ws://"));
        assertTrue(connect.getHeaders().stream().noneMatch(header -> header.getName().equalsIgnoreCase("Sec-WebSocket-Key")));
        var recording = RecordedHarExchangeResolver.findFor(connects.get(0), null);
        assertEquals(store, recording.isPresent());
        if (store) {
            var messages = recording.orElseThrow().webSocketMessages();
            assertEquals(stopEarly ? 2 : 4, messages.size());
            assertEquals("hello", messages.get(0).text());
            assertEquals("00 ff 01", messages.get(1).hex());
            var tree = new org.apache.jorphan.collections.ListedHashTree();
            tree.add(connect);
            Path saved = directory.resolve("websocket.jmx");
            org.apache.jmeter.save.SaveService.saveTreeToFile(tree, saved);
            String manifest = connect.getPropertyAsString(org.apache.jmeter.recording.RecordedExchangeStore.MANIFEST_PROPERTY);
            String checksum = connect.getPropertyAsString(org.apache.jmeter.recording.RecordedExchangeStore.CHECKSUM_PROPERTY);
            var archive = org.apache.jmeter.save.SaveService.readRecordingBundle(saved.toFile(), manifest, checksum).orElseThrow();
            var restored = org.apache.jmeter.recording.RecordedExchangeStore.resolveExchange(archive.get(manifest),
                    connect.getPropertyAsString(org.apache.jmeter.recording.RecordedExchangeStore.EXCHANGE_ID_PROPERTY),
                    name -> java.util.Optional.ofNullable(archive.get(name))).orElseThrow();
            assertEquals(messages, org.apache.jmeter.recording.RecordedWebSocketMessage.fromExchange(restored));
            byte[] storedFrames = java.util.Base64.getDecoder().decode(restored.path("request")
                    .path("_breaktestWebSocketWire").path("text").asText());
            assertEquals(sent.length + (stopEarly ? 0 : close.length), storedFrames.length);
        }
        var sends = model.getNodesOfType(WebSocketSendWaitSampler.class);
        assertEquals(1, sends.size());
        var send = (WebSocketSendWaitSampler) sends.get(0).getTestElement();
        assertEquals(connect.getSessionName(), send.getSessionName());
        assertEquals("hello", send.getPayload());
        assertEquals(stopEarly ? 0 : 1, model.getNodesOfType(WebSocketCloseSampler.class).size());
        assertTrue(connect.isEnabled());
    }

    static SSLContext serverContext(Path directory) throws Exception {
        String password = "test-recording";
        Path file = directory.resolve("server.p12");
        org.apache.jorphan.exec.KeyToolUtils.genkeypair(file.toFile(), "localhost", password, 1, "CN=localhost", "SAN=dns:localhost");
        var keys = java.security.KeyStore.getInstance(file.toFile(), password.toCharArray());
        var managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }
}
