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

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLSocket;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.sse.SseSampler;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordedSseEvent;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SseProxyRecordingTest extends JMeterTestCase {
    @ParameterizedTest
    @CsvSource({"false,false,false,true", "true,false,false,true", "false,true,false,true",
        "false,false,true,true", "false,true,true,true", "false,false,false,false"})
    void recordsEventsWithoutChangingTheStream(boolean secure, boolean gzip, boolean stopEarly, boolean store,
            @TempDir Path directory) throws Exception {
        var context = WebSocketProxyRecordingTest.serverContext(directory);
        var model = new JMeterTreeModel();
        var group = new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), model);
        model.insertNodeInto(group, model.getNodesOfType(ThreadGroupsSection.class).get(0), 0);
        var proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(group);
        proxy.setStoreRecordedExchanges(store);
        proxy.setPrefixHTTPSampleName("Open stream");
        try (ServerSocket available = new ServerSocket(0)) {
            proxy.setPort(available.getLocalPort());
        }
        String first = "\uFEFF: heartbeat\r\nid: 42\r\nevent: ready\r\ndata: café\r\n\r\n";
        String second = "data: first line\ndata: second line\n\n" + (stopEarly ? "data: unfinished" : "");
        byte[][] parts = payloads(first, second, gzip, stopEarly);
        byte[] chunk1 = chunk(parts[0]);
        byte[] chunk2 = chunk(parts[1]);
        var proceed = new CountDownLatch(1);
        proxy.startProxy();
        try (ServerSocket origin = secure ? context.getServerSocketFactory().createServerSocket(0) : new ServerSocket(0);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            origin.setSoTimeout(5000);
            var server = workers.submit(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    assertEquals("/events", HttpProxyTransport.readHead(socket.getInputStream()).target());
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                            + "Transfer-Encoding: chunked\r\n" + (gzip ? "Content-Encoding: gzip\r\n" : "")
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().write(chunk1);
                    socket.getOutputStream().flush();
                    assertTrue(proceed.await(5, TimeUnit.SECONDS));
                    socket.getOutputStream().write(chunk2);
                    socket.getOutputStream().flush();
                    if (stopEarly) {
                        assertEquals(-1, socket.getInputStream().read());
                    } else {
                        socket.getOutputStream().write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
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
                    var clientTls = ((org.apache.jmeter.util.JsseSSLManager) org.apache.jmeter.util.SSLManager.getInstance()).getContext();
                    browser = clientTls.getSocketFactory().createSocket(raw, "localhost", proxy.getPort(), false);
                    ((SSLSocket) browser).startHandshake();
                }
                try (Socket connection = browser) {
                    connection.getOutputStream().write(("GET " + (secure ? "" : "http://" + authority)
                            + "/events HTTP/1.1\r\nHost: " + authority + "\r\nAccept: text/event-stream\r\nLast-Event-ID: 41\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    var response = HttpProxyTransport.readHead(connection.getInputStream());
                    assertEquals(gzip ? "gzip" : "", response.value("Content-Encoding"));
                    assertArrayEquals(chunk1, connection.getInputStream().readNBytes(chunk1.length));
                    proxy.setPrefixHTTPSampleName("Next action");
                    proceed.countDown();
                    assertArrayEquals(chunk2, connection.getInputStream().readNBytes(chunk2.length));
                    if (stopEarly) {
                        proxy.stopProxy();
                    } else {
                        assertEquals("0\r\n\r\n", new String(connection.getInputStream().readNBytes(5), StandardCharsets.US_ASCII));
                    }
                }
            }
            server.get(10, TimeUnit.SECONDS);
        } finally {
            proceed.countDown();
            proxy.stopProxy();
        }
        var nodes = model.getNodesOfType(SseSampler.class);
        assertEquals(1, nodes.size());
        var sampler = (SseSampler) nodes.get(0).getTestElement();
        assertEquals("sse-1", sampler.getSseSessionName());
        assertTrue(sampler.isEnabled(), sampler.getComment());
        assertEquals(0, sampler.getResponseTimeout());
        assertTrue(sampler.getNativeHeaderList().stream().anyMatch(header -> "Last-Event-ID".equals(header.getName())));
        var recording = RecordedHarExchangeResolver.findFor(nodes.get(0), null);
        assertEquals(store, recording.isPresent());
        if (store) {
            var events = recording.orElseThrow().serverSentEvents();
            assertEquals(2, events.size());
            assertEquals("ready", events.get(0).eventName());
            assertEquals("café", events.get(0).data());
            assertEquals("Open stream", events.get(0).transactionName());
            assertEquals("Next action", events.get(1).transactionName());
            assertEquals("42", events.get(1).eventId());
            assertEquals("first line\nsecond line", events.get(1).data());
            assertTrue(events.get(1).relativeTimeMs().compareTo(events.get(0).relativeTimeMs()) >= 0);
            var tree = new ListedHashTree();
            tree.add(sampler);
            Path saved = directory.resolve("sse.jmx");
            SaveService.saveTreeToFile(tree, saved);
            String manifest = sampler.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY);
            var archive = SaveService.readRecordingBundle(saved.toFile(), manifest,
                    sampler.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY)).orElseThrow();
            var restored = RecordedExchangeStore.resolveExchange(archive.get(manifest),
                    sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY),
                    name -> java.util.Optional.ofNullable(archive.get(name))).orElseThrow();
            assertEquals(events, RecordedSseEvent.fromExchange(restored));
        }
    }

    private static byte[][] payloads(String first, String second, boolean gzip, boolean partial) throws Exception {
        if (!gzip) {
            return new byte[][]{first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8)};
        }
        var bytes = new ByteArrayOutputStream();
        try (var encoder = new GZIPOutputStream(bytes, true)) {
            encoder.write(first.getBytes(StandardCharsets.UTF_8));
            encoder.flush();
            byte[] start = bytes.toByteArray();
            bytes.reset();
            encoder.write(second.getBytes(StandardCharsets.UTF_8));
            encoder.flush();
            if (!partial) {
                encoder.finish();
            }
            return new byte[][]{start, bytes.toByteArray()};
        }
    }

    private static byte[] chunk(byte[] body) {
        var output = new ByteArrayOutputStream();
        output.writeBytes((Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(body);
        output.writeBytes(new byte[]{'\r', '\n'});
        return output.toByteArray();
    }
}
