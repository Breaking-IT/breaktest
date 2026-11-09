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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.SSLManager;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Local HTTP/2 TLS fixture; run separately because trust-store properties are global. */
@EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_RESUMPTION_SERVER", matches = ".+")
class HTTP2SessionResumptionTest {
    private record Handshake(long connection, boolean resumed) { }

    @Test
    void newConnectionsResumeWithinUserIncludingParallelClients(@TempDir Path directory) throws Exception {
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        Path cert = directory.resolve("server.pem");
        Process server = new ProcessBuilder(System.getenv("BREAKTEST_HTTP3_RESUMPTION_SERVER"), cert.toString(), "h2")
                .redirectError(directory.resolve("server.log").toFile()).start();
        String previousTrust = System.getProperty("javax.net.ssl.trustStore");
        var workers = Executors.newFixedThreadPool(10);
        List<JMeterThread> owners = List.of(owner(), owner());
        try (var output = new BufferedReader(new InputStreamReader(server.getInputStream()))) {
            String ready = output.readLine();
            assertNotNull(ready, () -> {
                try {
                    return Files.readString(directory.resolve("server.log"));
                } catch (Exception e) {
                    return e.toString();
                }
            });
            String[] ports = ready.split(" ");
            int port = Integer.parseInt(ports[0]);
            int control = Integer.parseInt(ports[1]);
            KeyStore trust = KeyStore.getInstance("JKS");
            trust.load(null, null);
            try (var input = Files.newInputStream(cert)) {
                trust.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
            Path store = directory.resolve("trust.jks");
            try (var file = Files.newOutputStream(store)) {
                trust.store(file, "password".toCharArray());
            }
            System.setProperty("javax.net.ssl.trustStore", store.toString());
            SSLManager.reset();
            var initial = new ArrayList<Handshake>();
            var reconnected = new ArrayList<Handshake>();
            for (JMeterThread owner : owners) {
                Handshake first = sample(owner, port, 5000);
                assertFalse(first.resumed(), "A new virtual user must start with a full handshake");
                initial.add(first);
                close(control, first.connection());
                Handshake next = sample(owner, port, 5000);
                assertNotEquals(first.connection(), next.connection(), "Must establish a new TLS connection");
                assertTrue(next.resumed(), "New connection on the same client must resume TLS");
                reconnected.add(next);
            }
            assertNotEquals(initial.get(0).connection(), initial.get(1).connection());
            CyclicBarrier start = new CyclicBarrier(10);
            var results = new ArrayList<Future<Handshake>>();
            for (JMeterThread owner : owners) {
                for (int i = 0; i < 5; i++) {
                    int timeout = 6000 + i;
                    results.add(workers.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        // Negotiating HTTP/2 uses a different client from the initial forced HTTP/2 request.
                        return sample(owner, port, timeout);
                    }));
                }
            }
            for (int user = 0; user < 2; user++) {
                boolean resumed = false;
                for (int i = 0; i < 5; i++) {
                    Handshake result = results.get(user * 5 + i).get(20, TimeUnit.SECONDS);
                    assertNotEquals(reconnected.get(user).connection(), result.connection(),
                            "Negotiating client must establish a new connection");
                    resumed |= result.resumed();
                }
                assertTrue(resumed, "Parallel clients of user " + user + " must be able to resume a session");
            }
            owners.get(0).run();
            Handshake fresh = sample(owners.get(0), port, 5000);
            assertFalse(fresh.resumed(), "User cleanup must discard sessions as well as clients");
            Handshake surviving = sample(owners.get(1), port, 5000);
            assertEquals(reconnected.get(1).connection(), surviving.connection(),
                    "Cleaning up one user must leave the other user's connection open");
            close(control, surviving.connection());
            assertTrue(sample(owners.get(1), port, 5000).resumed(),
                    "The surviving user's TLS session cache must also remain usable");
        } finally {
            workers.shutdownNow();
            for (JMeterThread owner : owners) {
                owner.run();
            }
            server.destroyForcibly();
            server.waitFor(10, TimeUnit.SECONDS);
            if (previousTrust == null) {
                System.clearProperty("javax.net.ssl.trustStore");
            } else {
                System.setProperty("javax.net.ssl.trustStore", previousTrust);
            }
            SSLManager.reset();
            JMeterContextService.getContext().clear();
        }
    }

    private static JMeterThread owner() {
        LoopController loop = new LoopController();
        loop.setLoops(0);
        loop.setContinueForever(false);
        ListedHashTree tree = new ListedHashTree();
        tree.add(loop);
        ThreadGroup group = new ThreadGroup();
        JMeterThread owner = new JMeterThread(tree, group, new ListenerNotifier());
        owner.setThreadGroup(group);
        owner.setThreadName("h2-user");
        return owner;
    }

    private static Handshake sample(JMeterThread owner, int port, int timeout) throws Exception {
        JMeterContextService.getContext().setThread(owner);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        try {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setConnectTimeout(Integer.toString(timeout));
            sampler.setResponseTimeout("10000");
            sampler.setHttpProtocol(timeout == 5000 ? "HTTP/2" : "");
            HTTPHC5H2Impl impl = new HTTPHC5H2Impl(sampler);
            HTTPSampleResult result = impl.sample(new URI("https://localhost:" + port + "/").toURL(),
                    HTTPConstants.GET, false, 0);
            assertTrue(result.isSuccessful(), result.getResponseMessage() + " " + result.getResponseDataAsString());
            assertTrue(result.getResponseHeaders().startsWith("HTTP/2"), result.getResponseHeaders());
            String[] data = result.getResponseDataAsString().split(",");
            return new Handshake(Long.parseLong(data[0]), Boolean.parseBoolean(data[1]));
        } finally {
            JMeterContextService.getContext().clear();
        }
    }

    private static void close(int port, long connection) throws Exception {
        var request = new URI("http://127.0.0.1:" + port + "/?id=" + connection).toURL().openConnection();
        request.setConnectTimeout(5000);
        request.setReadTimeout(5000);
        try (var response = request.getInputStream()) {
            assertEquals(-1, response.read());
        }
    }
}
