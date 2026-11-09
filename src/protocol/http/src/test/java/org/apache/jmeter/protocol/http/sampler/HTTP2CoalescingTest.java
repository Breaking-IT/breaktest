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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Local HTTP/2 TLS fixture; run separately because trust-store properties are global. */
@EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_RESUMPTION_SERVER", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
class HTTP2CoalescingTest {
    private record Response(long connection, HTTPSampleResult result) { }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coalescesOnlyEligibleOriginsWithinOneUser(boolean explicit, @TempDir Path directory) throws Exception {
        Boolean enabled = explicit ? Boolean.TRUE : null;
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        Path cert = directory.resolve("server.pem");
        Process server = new ProcessBuilder(System.getenv("BREAKTEST_HTTP3_RESUMPTION_SERVER"), cert.toString(), "coalesce")
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
            Response first = sample(owners.get(0), port, "a.test", "127.0.0.1", enabled, false);
            Response shared = sample(owners.get(0), port, "b.test", "127.0.0.1", enabled, false);
            assertEquals(first.connection(), shared.connection(), "Eligible hosts must share the TLS connection");
            assertEquals(-1, shared.result().getConnectTime(), "Coalescing must not report a new handshake");
            assertNotEquals(first.connection(), sample(owners.get(0), port, "invalid.test", "127.0.0.1", enabled, false).connection(),
                    "Certificate mismatch must use a dedicated connection");
            assertNotEquals(first.connection(), sample(owners.get(0), port, "dns.test", "::1", enabled, false).connection(),
                    "DNS mismatch must use a dedicated connection");
            Response secondUser = sample(owners.get(1), port, "a.test", "127.0.0.1", enabled, false);
            assertNotEquals(first.connection(), secondUser.connection(), "Users must remain isolated");
            CyclicBarrier start = new CyclicBarrier(10);
            var results = new ArrayList<Future<Response>>();
            for (JMeterThread user : owners) {
                for (int i = 0; i < 5; i++) {
                    results.add(workers.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return sample(user, port, "b.test", "127.0.0.1", enabled, false);
                    }));
                }
            }
            for (int i = 0; i < 10; i++) {
                assertEquals(i < 5 ? first.connection() : secondUser.connection(),
                        results.get(i).get(20, TimeUnit.SECONDS).connection(), "Parallel requests must coalesce per user");
            }
            Response rejected = sample(owners.get(0), port, "reject.test", "127.0.0.1", enabled, true);
            assertNotEquals(first.connection(), rejected.connection(), "421 must retry on a dedicated connection");
            assertTrue(rejected.result().getResponseDataAsString().endsWith(",payload"), "Retry must preserve POST body");
            assertTrue(rejected.result().getResponseHeaders().contains("x-rejected: 1"), rejected.result().getResponseHeaders());
            assertEquals(rejected.connection(), sample(owners.get(0), port, "reject.test", "127.0.0.1", enabled, false).connection());
            // Explicit false remains an opt-out even though absent properties now enable coalescing.
            Response legacyA = sample(owners.get(0), port, "a.test", "127.0.0.1", false, false);
            Response legacyB = sample(owners.get(0), port, "b.test", "127.0.0.1", false, false);
            assertNotEquals(legacyA.connection(), legacyB.connection());
            owners.get(0).run();
            assertEquals(secondUser.connection(), sample(owners.get(1), port, "b.test", "127.0.0.1", enabled, false).connection());
            assertNotEquals(first.connection(), sample(owners.get(0), port, "a.test", "127.0.0.1", enabled, false).connection());
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

    private static Response sample(JMeterThread owner, int port, String host, String address,
            Boolean enabled, boolean post) throws Exception {
        JMeterContextService.getContext().setThread(owner);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        try {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setConnectTimeout("5000");
            sampler.setResponseTimeout("10000");
            sampler.setHttpProtocol("b.test".equals(host) ? "" : "HTTP/2");
            if (enabled != null) {
                sampler.setProperty(HTTPSamplerBase.HTTP2_COALESCING, enabled.booleanValue());
            }
            var dns = new org.apache.jmeter.protocol.http.control.DNSCacheManager();
            dns.setCustomResolver(true);
            dns.addHost(host, address);
            sampler.setDNSResolver(dns);
            if (post) {
                sampler.setPostBodyRaw(true);
                sampler.addNonEncodedArgument("", "payload", "");
            }
            HTTPHC5H2Impl impl = new HTTPHC5H2Impl(sampler);
            HTTPSampleResult result = impl.sample(new URI("https://" + host + ":" + port + "/").toURL(),
                    post ? HTTPConstants.POST : HTTPConstants.GET, false, 0);
            assertTrue(result.isSuccessful(), result.getResponseMessage() + " " + result.getResponseDataAsString());
            assertTrue(result.getResponseHeaders().startsWith("HTTP/2"), result.getResponseHeaders());
            String[] data = result.getResponseDataAsString().split(",");
            assertEquals(host, data[2], "Request authority must remain the destination hostname");
            return new Response(Long.parseLong(data[0]), result);
        } finally {
            JMeterContextService.getContext().clear();
        }
    }
}
