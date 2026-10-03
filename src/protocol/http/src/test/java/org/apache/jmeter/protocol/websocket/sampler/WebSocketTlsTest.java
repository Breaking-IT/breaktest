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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.SSLManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class WebSocketTlsTest extends JMeterTestCase {
    @TempDir
    static Path directory;
    private static Path clientStore;
    private static SSLContext serverContext;
    private final Map<String, String> savedProperties = new HashMap<>();
    private SSLSocketFactory previousFactory;
    private HostnameVerifier previousVerifier;

    @BeforeAll
    static void certificates() throws Exception {
        Path serverStore = directory.resolve("server.p12");
        clientStore = directory.resolve("clients.p12");
        generate(serverStore, "server", "deliberately-not-localhost.test");
        generate(clientStore, "alice", "Alice");
        generate(clientStore, "bob", "Bob");
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(load(serverStore), "password".toCharArray());
        KeyStore clientKeys = load(clientStore);
        KeyStore trustedClients = KeyStore.getInstance("PKCS12");
        trustedClients.load(null, null);
        for (String name : List.of("alice", "bob")) {
            trustedClients.setCertificateEntry(name, clientKeys.getCertificate(name));
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustedClients);
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    }

    @BeforeEach
    void configureSharedSslManager() {
        previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        setProperty("javax.net.ssl.keyStore", clientStore.toString());
        setProperty("javax.net.ssl.keyStoreType", "PKCS12");
        setProperty("javax.net.ssl.keyStorePassword", "password");
        SSLManager.reset();
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        SSLManager.getInstance().configureKeystore(true, 0, -1, "certAlias");
        JMeterContextService.getContext().getVariables().put("certAlias", "alice");
    }

    @AfterEach
    void restoreConfiguration() {
        WebSocketSessions.cleanup();
        savedProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
        SSLManager.reset();
        HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
        HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier);
        JMeterContextService.getContext().clear();
    }

    @Test
    void usesHttpCertificatePolicyForSelfSignedAndMismatchedServerCertificate() throws Exception {
        try (WebSocketSamplerTest.Peer peer = peer(false)) {
            WebSocketConnectSampler connect = connect("tls", peer.url());
            SampleResult result = connect.sample(null);
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("tls");
            assertTrue(close.sample(null).isSuccessful());
        }
    }

    @Test
    void existingKeystoreAndVariableAliasWorkOnTlsWorkerThreadsAndReconnect() throws Exception {
        try (WebSocketSamplerTest.Peer alice = peer(true); WebSocketSamplerTest.Peer bob = peer(true)) {
            SampleResult first = connect("identity", alice.url()).sample(null);
            assertTrue(first.isSuccessful(), first::getResponseMessage);
            assertEquals("CN=Alice", alice.clientPrincipal.get(3, TimeUnit.SECONDS));
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("identity");
            assertTrue(close.sample(null).isSuccessful());
            JMeterContextService.getContext().getVariables().put("certAlias", "bob");
            SampleResult second = connect("identity", bob.url()).sample(null);
            assertTrue(second.isSuccessful(), second::getResponseMessage);
            assertEquals("CN=Bob", bob.clientPrincipal.get(3, TimeUnit.SECONDS));
            assertTrue(close.sample(null).isSuccessful());
        }
    }

    @Test
    void concurrentSessionsShareOnlyTheSameCertificateIdentity() throws Exception {
        try (WebSocketSamplerTest.Peer alice = peer(true);
                WebSocketSamplerTest.Peer secondAlice = peer(true);
                WebSocketSamplerTest.Peer bob = peer(true)) {
            assertTrue(connect("alice", alice.url()).sample(null).isSuccessful());
            assertTrue(connect("secondAlice", secondAlice.url()).sample(null).isSuccessful());
            var sessions = WebSocketSessions.current();
            var aliceClient = sessions.client("alice", sessions.get("alice"), URI.create(alice.url()));
            assertSame(aliceClient, sessions.client("secondAlice", sessions.get("secondAlice"), URI.create(secondAlice.url())));
            JMeterContextService.getContext().getVariables().put("certAlias", "bob");
            SampleResult connected = connect("bob", bob.url()).sample(null);
            assertTrue(connected.isSuccessful(), connected::getResponseMessage);
            assertNotSame(aliceClient, sessions.client("bob", sessions.get("bob"), URI.create(bob.url())));
            assertEquals("CN=Alice", alice.clientPrincipal.get(3, TimeUnit.SECONDS));
            assertEquals("CN=Alice", secondAlice.clientPrincipal.get(3, TimeUnit.SECONDS));
            assertEquals("CN=Bob", bob.clientPrincipal.get(3, TimeUnit.SECONDS));
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setSessionName("alice");
            assertTrue(close.sample(null).isSuccessful());
            WebSocketSendWaitSampler send = new WebSocketSendWaitSampler();
            send.setSessionName("secondAlice");
            send.setPayload("still open");
            assertEquals("still open", send.sample(null).getResponseDataAsString());
            close.setSessionName("secondAlice");
            assertTrue(close.sample(null).isSuccessful());
            assertTrue(aliceClient.isTerminated() || aliceClient.awaitTermination(java.time.Duration.ofSeconds(3)));
        }
    }

    @Test
    void configuredTlsProtocolsSeparateTransportClients() throws Exception {
        String previous = org.apache.jmeter.util.JMeterUtils.getProperty("https.socket.protocols");
        try {
            org.apache.jmeter.util.JMeterUtils.setProperty("https.socket.protocols", "TLSv1.2");
            try (var first = WebSocketTransportPool.acquire(URI.create("wss://localhost/"))) {
                assertEquals(List.of("TLSv1.2"), List.of(first.client().sslParameters().getProtocols()));
                org.apache.jmeter.util.JMeterUtils.setProperty("https.socket.protocols", "TLSv1.3");
                try (var second = WebSocketTransportPool.acquire(URI.create("wss://localhost/"))) {
                    assertNotSame(first.client(), second.client());
                    assertEquals(List.of("TLSv1.3"), List.of(second.client().sslParameters().getProtocols()));
                }
            }
        } finally {
            if (previous == null) {
                org.apache.jmeter.util.JMeterUtils.getJMeterProperties().remove("https.socket.protocols");
            } else {
                org.apache.jmeter.util.JMeterUtils.setProperty("https.socket.protocols", previous);
            }
        }
    }

    @Test
    void invalidAliasFailsWithoutLeavingTheSessionNameReserved() throws Exception {
        try (WebSocketSamplerTest.Peer peer = peer(true)) {
            JMeterContextService.getContext().getVariables().put("certAlias", "missing");
            assertFalse(connect("identity", peer.url()).sample(null).isSuccessful());
            JMeterContextService.getContext().getVariables().put("certAlias", "alice");
            SampleResult retry = connect("identity", peer.url()).sample(null);
            assertTrue(retry.isSuccessful(), retry::getResponseMessage);
            assertEquals("CN=Alice", peer.clientPrincipal.get(3, TimeUnit.SECONDS));
        }
    }

    private static WebSocketConnectSampler connect(String name, String url) {
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.setSessionName(name);
        sampler.setUrl(url);
        sampler.setTimeout(3000);
        return sampler;
    }

    private static WebSocketSamplerTest.Peer peer(boolean clientAuth) throws Exception {
        SSLServerSocket socket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        socket.setNeedClientAuth(clientAuth);
        return new WebSocketSamplerTest.Peer(socket);
    }

    private void setProperty(String name, String value) {
        savedProperties.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private static KeyStore load(Path file) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(file)) {
            store.load(input, "password".toCharArray());
        }
        return store;
    }

    private static void generate(Path file, String alias, String commonName) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "password", "-keypass", "password",
                "-dname", "CN=" + commonName, "-validity", "2").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
