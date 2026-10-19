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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_RESUMPTION_SERVER", matches = ".+")
class HTTPDefaultProtocolUpgradeTest {
    @ParameterizedTest
    @ValueSource(strings = {"h3", "h1", "no-alt", "no-alt-h1"})
    void blankProtocolStartsOnTcpThenUsesAdvertisedHttp3(String mode, @TempDir Path directory) throws Exception {
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        assertTrue(Http3RuntimeSupport.isHttp3Supported(), "Run on Java 27");
        Process server = new ProcessBuilder(System.getenv("BREAKTEST_HTTP3_RESUMPTION_SERVER"),
                directory.resolve("server.pem").toString(), mode)
                .redirectError(directory.resolve("server.log").toFile()).start();
        var workers = Executors.newFixedThreadPool(5);
        List<JMeterThread> users = List.of(owner(), owner());
        try (var output = new BufferedReader(new InputStreamReader(server.getInputStream()))) {
            String ready = output.readLine();
            assertNotNull(ready, () -> {
                try {
                    return Files.readString(directory.resolve("server.log"));
                } catch (Exception e) {
                    return e.toString();
                }
            });
            int port = Integer.parseInt(ready.split(" ")[0]);
            String tcp = mode.endsWith("h1") ? "HTTP/1.1" : "HTTP/2";
            String subsequent = mode.startsWith("no-alt") ? tcp : "HTTP/3";
            for (JMeterThread user : users) {
                HTTPSampleResult first = sampleResult(user, port, "", "");
                assertEquals(tcp, first.getProtocolVersion(), "Each new user must discover over TCP first");
                assertConnectTime(first);
                if (mode.startsWith("no-alt")) {
                    HTTPSampleResult reused = sampleResult(user, port, "", "");
                    assertEquals(first.getResponseDataAsString(), reused.getResponseDataAsString());
                    assertEquals(-1, reused.getConnectTime(), "Reused TCP connection has no handshake");
                    HTTPSampleResult fresh = sampleResult(user, port, "", "127.0.0.1");
                    assertNotEquals(first.getResponseDataAsString(), fresh.getResponseDataAsString());
                    assertConnectTime(fresh);
                }
                CyclicBarrier start = new CyclicBarrier(5);
                var results = new ArrayList<Future<String>>();
                for (int i = 0; i < 5; i++) {
                    results.add(workers.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        HTTPSampleResult result = sampleResult(user, port, "", "");
                        if (mode.equals("no-alt")) {
                            assertEquals(first.getResponseDataAsString(), result.getResponseDataAsString());
                            assertEquals(-1, result.getConnectTime(), "Multiplexed H2 requests reuse the connection");
                        }
                        return result.getProtocolVersion();
                    }));
                }
                for (var result : results) {
                    assertEquals(subsequent, result.get(20, TimeUnit.SECONDS));
                }
                assertEquals(subsequent, sample(user, port, ""), "Sequential samplers share discovery too");
                HTTPSampleResult disabled = sampleResult(user, port, "", "", false);
                assertEquals(tcp, disabled.getProtocolVersion(), "A saved opt-out wins even after discovery");
                assertEquals(-1, disabled.getConnectTime(), "Opt-out reuses the HC5 TCP connection");
                assertEquals("HTTP/1.1", sample(user, port, "HTTP/1.1"), "Explicit HTTP/1.1 stays pinned");
                if (!mode.endsWith("h1")) {
                    assertEquals("HTTP/2", sample(user, port, "HTTP/2"), "Explicit HTTP/2 stays pinned");
                }
            }
        } finally {
            workers.shutdownNow();
            users.forEach(JMeterThread::run);
            server.destroyForcibly();
            server.waitFor(10, TimeUnit.SECONDS);
            JMeterContextService.getContext().clear();
        }
    }

    private static void assertConnectTime(HTTPSampleResult result) {
        assertTrue(result.getConnectTime() >= 90, "Connect time must include the delayed TLS handshake: "
                + result.getConnectTime());
        assertTrue(result.getLatency() - result.getConnectTime() >= 250,
                "Connect time must exclude the delayed response: " + result.getConnectTime() + "/" + result.getLatency());
    }

    private static String sample(JMeterThread user, int port, String protocol) {
        return sampleResult(user, port, protocol, "").getProtocolVersion();
    }

    private static HTTPSampleResult sampleResult(JMeterThread user, int port, String protocol, String localAddress) {
        return sampleResult(user, port, protocol, localAddress, true);
    }

    private static HTTPSampleResult sampleResult(JMeterThread user, int port, String protocol,
            String localAddress, boolean upgrade) {
        JMeterContextService.getContext().setThread(user);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        try {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setProtocol("https");
            sampler.setDomain("localhost");
            sampler.setPort(port);
            sampler.setPath("/");
            sampler.setMethod("GET");
            sampler.setHttpProtocol(protocol);
            sampler.setProperty(HTTPSamplerBase.HTTP3_UPGRADE, upgrade);
            sampler.setConnectTimeout("5000");
            sampler.setIpSource(localAddress);
            sampler.setResponseTimeout("10000");
            var result = sampler.sample();
            assertTrue(result.isSuccessful(), result.getResponseMessage() + " " + result.getResponseDataAsString());
            return (HTTPSampleResult) result;
        } finally {
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
        JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier());
        user.setThreadGroup(group);
        user.setThreadName("protocol-discovery-user");
        return user;
    }
}
