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

package org.apache.jmeter.protocol.http.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.action.validation.TreeClonerForValidation;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

class ValidationResumeHttpTest extends JMeterTestCase {
    private String testStartProperty;

    @BeforeEach
    void rememberGlobalState() {
        testStartProperty = JMeterUtils.getProperty("TESTSTART.MS");
    }

    @AfterEach
    void restoreGlobalState() {
        // Engine runs publish TESTSTART.MS as a JMeter property; later tests must not see it.
        if (testStartProperty == null) {
            JMeterUtils.getJMeterProperties().remove("TESTSTART.MS");
        } else {
            JMeterUtils.setProperty("TESTSTART.MS", testStartProperty);
        }
        JMeterContextService.getContext().clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Timeout(20)
    void resumesWithCookiesAndCacheFromBeforeEditedSampler(boolean useProfile) throws Exception {
        ValidationCheckpoints.clear();
        JMeterContextService.setValidationRun(true);
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(path + ":" + exchange.getRequestHeaders().getFirst("Cookie")
                    + ":" + exchange.getRequestHeaders().getFirst("If-None-Match"));
            if (path.equals("/login")) {
                exchange.getResponseHeaders().add("Set-Cookie", "session=checkpoint; Path=/");
            } else {
                exchange.getResponseHeaders().add("Set-Cookie", "session=after; Path=/");
                exchange.getResponseHeaders().add("ETag", "saved-etag");
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ThreadGroup group = new ThreadGroup();
            LoopController loop = new LoopController();
            loop.setLoops(1);
            group.setSamplerController(loop);
            group.setNumThreads(1);
            ListedHashTree source = new ListedHashTree();
            var plan = source.add(new TestPlan());
            // Plan-level managers are injected into the worker tree by the engine.
            CookieManager cookies = new CookieManager();
            cookies.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS,
                    "org.apache.jmeter.protocol.http.gui.CookiePanel");
            CacheManager cache = new CacheManager();
            cache.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS,
                    "org.apache.jmeter.protocol.http.gui.CacheManagerGui");
            var configuration = useProfile
                    ? plan.add(new org.apache.jmeter.scenario.ProfilesSection())
                            .add(new org.apache.jmeter.scenario.Profile("local"))
                    : plan;
            configuration.add(cookies);
            configuration.add(cache);
            group.setThreadGroupId("replay-group");
            var flow = useProfile
                    ? plan.add(new org.apache.jmeter.scenario.ThreadGroupsSection()).add(group)
                    : plan.add(group);
            flow.add(sampler(server, "/login"));
            flow.add(sampler(server, "/resource"));
            HTTPSamplerProxy target = sampler(server, "/resource");
            flow.add(new TransactionController()).add(target);
            flow.add(sampler(server, "/tail"));
            ListedHashTree first = cloneTree(source);
            ValidationCheckpoints.rememberStructure(first);
            run(first);
            assertEquals(4, calls.size());
            // The checkpoint contains the resource's ETag and the cookie from its first response.
            ValidationCheckpoints.Checkpoint checkpoint = ValidationCheckpoints.get(target);
            // Preparing the unchanged plan must accept the checkpoint too.
            ValidationCheckpoints.prepareResume(cloneTree(source), checkpoint);
            target.setMethod("POST");
            ListedHashTree resumed = cloneTree(source);
            ValidationCheckpoints.prepareResume(resumed, checkpoint);
            calls.clear();
            run(resumed);
            assertEquals(List.of("/resource:session=after:saved-etag", "/tail:session=after:null"), calls);
            // Login's checkpoint is earlier than the cookie-changing resource request.
            var firstResource = (HTTPSamplerProxy) flow.list().stream()
                    .filter(element -> element instanceof HTTPSamplerProxy sampler
                            && sampler.getPath().equals("/resource")).findFirst().orElseThrow();
            ListedHashTree earlier = cloneTree(source);
            ValidationCheckpoints.prepareResume(earlier, ValidationCheckpoints.get(firstResource));
            calls.clear();
            run(earlier);
            assertEquals("/resource:session=checkpoint:null", calls.get(0));
            assertEquals("", cookies.getPropertyAsString("__breaktest_validation_id"));
            // Real manager additions must still invalidate the old checkpoint.
            configuration.add(new DNSCacheManager());
            assertThrows(IllegalStateException.class,
                    () -> ValidationCheckpoints.prepareResume(cloneTree(source), checkpoint));
        } finally {
            server.stop(0);
            ValidationCheckpoints.clear();
            JMeterContextService.setValidationRun(false);
        }
    }

    @Test
    void dnsSnapshotIsIndependentAndKerberosResumeIsRejected() throws Exception {
        DNSCacheManager dns = new DNSCacheManager();
        InetAddress saved = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        InetAddress changed = InetAddress.getByAddress(new byte[]{127, 0, 0, 2});
        dns.cache.put("example.invalid", new InetAddress[]{saved});
        Object state = dns.captureValidationState();
        dns.cache.get("example.invalid")[0] = changed;
        dns.restoreValidationState(state);
        assertEquals(saved, dns.cache.get("example.invalid")[0]);
        dns.cache.get("example.invalid")[0] = changed;
        dns.restoreValidationState(state);
        assertEquals(saved, dns.cache.get("example.invalid")[0]);
        AuthManager auth = new AuthManager();
        Authorization authorization = new Authorization();
        authorization.setMechanism(AuthManager.Mechanism.KERBEROS);
        auth.getAuthObjects().addItem(authorization);
        assertThrows(IllegalStateException.class, auth::captureValidationState);
    }

    private static HTTPSamplerProxy sampler(HttpServer server, String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(path);
        sampler.setDomain("127.0.0.1");
        sampler.setPort(server.getAddress().getPort());
        sampler.setProtocol("http");
        sampler.setPath(path);
        sampler.setMethod("GET");
        return sampler;
    }

    private static ListedHashTree cloneTree(ListedHashTree source) {
        TreeClonerForValidation cloner = new TreeClonerForValidation();
        org.apache.jmeter.scenario.ScenarioResolver.flattenIgnoringScenarios(source).traverse(cloner);
        return cloner.getClonedTree();
    }

    private static void run(ListedHashTree tree) throws Exception {
        JMeterContextService.setValidationRun(true);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(tree);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(5));
    }
}
