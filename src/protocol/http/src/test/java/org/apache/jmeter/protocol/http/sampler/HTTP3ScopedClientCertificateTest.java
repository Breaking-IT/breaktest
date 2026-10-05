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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ClientCertificateConfig;
import org.apache.jmeter.config.gui.ClientCertificateConfigGui;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.scenario.Profile;
import org.apache.jmeter.scenario.ProfilesSection;
import org.apache.jmeter.scenario.Scenario;
import org.apache.jmeter.scenario.ScenarioWorkload;
import org.apache.jmeter.scenario.ScenariosSection;
import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.SSLManager;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Run in a fresh Java 26 test JVM, once with strict trust and once with certificate retries. */
@EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_FIXTURE", matches = ".+")
@EnabledIf("org.apache.jmeter.protocol.http.sampler.Http3RuntimeSupport#isHttp3Supported")
class HTTP3ScopedClientCertificateTest {
    private static Path fixture;
    private static final Map<String, String> SAVED_SYSTEM = new HashMap<>();
    private static final Map<String, String> SAVED_JMETER = new HashMap<>();
    private static javax.net.ssl.SSLSocketFactory previousFactory;
    private static javax.net.ssl.HostnameVerifier previousVerifier;
    @TempDir
    Path directory;

    @BeforeAll
    static void configureTls() throws Exception {
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        fixture = Path.of(System.getenv("BREAKTEST_HTTP3_FIXTURE"));
        previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        // Default JSSE state must not accidentally supply our client keys or fixture CA.
        SSLContext.getDefault();
        for (String property : List.of("javax.net.ssl.trustStore", "javax.net.ssl.keyStore", "javax.net.ssl.keyStorePassword")) {
            SAVED_SYSTEM.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
        boolean retry = Boolean.getBoolean("httpsampler.http3.ignore_certificate_errors");
        if (!retry) {
            System.setProperty("javax.net.ssl.trustStore", fixture.resolve("trust.jks").toString());
        }
        Map<String, String> properties = Map.of(
                "httpsampler.http3.ignore_certificate_errors", Boolean.toString(retry),
                "httpsampler.http3.prefer_for_default", "true",
                "httpsampler.http3.force_http3", "true");
        properties.forEach((name, value) -> {
            SAVED_JMETER.put(name, JMeterUtils.getProperty(name));
            JMeterUtils.setProperty(name, value);
        });
        SSLManager.reset();
    }

    @AfterAll
    static void restoreTls() {
        SAVED_SYSTEM.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
        SAVED_JMETER.forEach((name, value) -> {
            if (value == null) {
                JMeterUtils.getJMeterProperties().remove(name);
            } else {
                JMeterUtils.setProperty(name, value);
            }
        });
        SSLManager.reset();
        HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
        HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentGroupsKeepTheirIdentityOverQuicAndAltSvcUpgrade(boolean upgrade) throws Exception {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        Results results = new Results();
        plan.add(results);
        Rendezvous rendezvous = new Rendezvous();
        for (String name : List.of("alice", "bob")) {
            HashTree group = plan.add(group(name, 1, 3));
            group.add(certificate(name + ".p12", ""));
            group.add(rendezvous);
            group.add(sampler(upgrade));
        }
        run(tree);
        assertEquals(6, results.events.size());
        for (String name : List.of("alice", "bob")) {
            List<SampleEvent> events = results.events.stream().filter(event -> name.equals(event.getThreadGroup())).toList();
            assertEquals(3, events.size());
            for (int i = 0; i < events.size(); i++) {
                assertPeer(events.get(i), "CN=" + name, upgrade && i == 0 ? "HTTP/2" : "HTTP/3");
            }
        }
    }

    @Test
    void profileCertificateAndThreadGroupOverrideUseDifferentHttp3Identities() throws Exception {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        Results results = new Results();
        plan.add(results);
        HashTree profiles = plan.add(new ProfilesSection());
        profiles.add(new SharedProfile()).add(certificate("bob.p12", ""));
        profiles.add(new Profile("employees")).add(certificate("alice.p12", ""));
        HashTree groups = plan.add(new ThreadGroupsSection());
        Scenario scenario = new Scenario("QUIC identities");
        java.util.ArrayList<ScenarioWorkload> workloads = new java.util.ArrayList<>();
        for (String name : List.of("profile", "override")) {
            HashTree group = groups.add(group(name, 1, 3));
            if (name.equals("override")) {
                group.add(certificate("bob.p12", ""));
            }
            group.add(sampler(false));
            ScenarioWorkload workload = new ScenarioWorkload();
            workload.setName(name);
            workload.setThreadGroupId(name);
            workload.setThreads("1");
            workload.setLoops("3");
            workload.setProfile("employees");
            workloads.add(workload);
        }
        scenario.setWorkloads(workloads);
        plan.add(new ScenariosSection()).add(scenario);
        run(tree);
        assertEquals(6, results.events.size());
        for (SampleEvent event : results.events) {
            assertPeer(event, event.getThreadGroup().equals("profile") ? "CN=alice" : "CN=bob", "HTTP/3");
        }
    }

    @Test
    void virtualUsersSelectDifferentAliasesFromOneKeystore() throws Exception {
        Results results = runCsv(2, 1);
        assertEquals(2, results.events.size());
        assertEquals(Set.of("CN=alice", "CN=bob"), results.events.stream()
                .map(event -> event.getResult().getResponseDataAsString()).collect(Collectors.toSet()));
        results.events.forEach(event -> assertPeer(event, event.getResult().getResponseDataAsString(), "HTTP/3"));
    }

    @Test
    void aliasChangeDoesNotReuseAnotherCertificateQuicConnection() throws Exception {
        Results results = runCsv(1, 3);
        assertEquals(List.of("CN=alice", "CN=bob", "CN=alice"), results.events.stream()
                .map(event -> event.getResult().getResponseDataAsString()).toList());
        results.events.forEach(event -> assertPeer(event, event.getResult().getResponseDataAsString(), "HTTP/3"));
    }

    @Test
    void sendingNoCertificateFailsTheMutualTlsHandshake() throws Exception {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        plan.add(certificate("alice.p12", ""));
        Results results = new Results();
        plan.add(results);
        HashTree group = plan.add(group("no-certificate", 1, 1));
        ClientCertificateConfig none = certificate("", "");
        none.setProperty(ClientCertificateConfig.MODE, ClientCertificateConfig.NONE);
        group.add(none);
        group.add(sampler(false));
        run(tree);
        assertEquals(1, results.events.size());
        assertFalse(results.events.element().getResult().isSuccessful(), "Fixture must require a client certificate");
    }

    @Test
    void rotatingGlobalAliasesRemainBoundToCachedClientIncludingCertificateRetry() throws Exception {
        HTTPSamplerProxy sampler = sampler(false);
        try {
            System.setProperty("javax.net.ssl.keyStore", fixture.resolve("users.p12").toString());
            System.setProperty("javax.net.ssl.keyStorePassword", "password");
            SSLManager.reset();
            SSLManager.getInstance().configureKeystore(true, 0, -1, "");
            var first = sampler.sample();
            assertTrue(first.isSuccessful(), first::getResponseDataAsString);
            String identity = first.getResponseDataAsString();
            for (int request = 0; request < 12; request++) {
                var result = sampler.sample();
                assertTrue(result.isSuccessful(), result::getResponseDataAsString);
                assertTrue(result.getResponseHeaders().startsWith("HTTP/3"), result.getResponseHeaders());
                assertEquals(identity, result.getResponseDataAsString(), "Reusing a QUIC client must not rotate aliases");
            }
            var field = HTTPJavaHttp3Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_JMETER_THREAD");
            field.setAccessible(true);
            Map<?, ?> clients = (Map<?, ?>) ((Map<?, ?>) field.get(null)).get(Thread.currentThread());
            assertEquals(Boolean.getBoolean("httpsampler.http3.ignore_certificate_errors") ? 2 : 1, clients.size(),
                    "Only the original client and optional certificate-retry client may be cached");
            sampler.threadFinished();
            var nextUser = sampler.sample();
            assertTrue(nextUser.isSuccessful(), nextUser::getResponseDataAsString);
            assertNotEquals(identity, nextUser.getResponseDataAsString(),
                    "A new client advances rotation once; a certificate retry must not advance it again");
        } finally {
            sampler.threadFinished();
            System.clearProperty("javax.net.ssl.keyStore");
            System.clearProperty("javax.net.ssl.keyStorePassword");
            SSLManager.reset();
        }
    }

    private Results runCsv(int users, int loops) throws Exception {
        Path csv = directory.resolve("aliases.csv");
        Files.writeString(csv, "alice\nbob\nalice\n");
        CSVDataSet data = new CSVDataSet();
        data.setProperty("filename", csv.toString());
        data.setProperty("variableNames", "clientCertAlias");
        data.setProperty("delimiter", ",");
        data.setProperty("recycle", false);
        data.setProperty("stopThread", true);
        data.setProperty("shareMode", "shareMode.all");
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        Results results = new Results();
        plan.add(results);
        HashTree group = plan.add(group("users", users, loops));
        group.add(data);
        group.add(certificate("users.p12", "${clientCertAlias}"));
        group.add(sampler(false));
        run(tree);
        return results;
    }

    private static void assertPeer(SampleEvent event, String identity, String protocol) {
        var result = event.getResult();
        assertTrue(result.isSuccessful(), () -> result.getResponseCode() + " " + result.getResponseDataAsString());
        assertEquals(identity, result.getResponseDataAsString());
        assertTrue(result.getResponseHeaders().startsWith(protocol), result.getResponseHeaders());
    }

    private static void run(HashTree tree) throws Exception {
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(tree);
        engine.runTest();
        try {
            engine.awaitTermination(Duration.ofSeconds(45));
        } finally {
            if (engine.isActive()) {
                engine.stopTest(true);
            }
        }
    }

    private static ThreadGroup group(String name, int users, int loops) {
        ThreadGroup group = new ThreadGroup();
        group.setName(name);
        group.setThreadGroupId(name);
        group.setNumThreads(users);
        group.setRampUp(0);
        group.setIsSameUserOnNextIteration(true);
        LoopController loop = new LoopController();
        loop.setLoops(loops);
        group.setSamplerController(loop);
        return group;
    }

    private static HTTPSamplerProxy sampler(boolean upgrade) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("QUIC authenticated peer");
        sampler.setDomain("mtls.example.test");
        sampler.setPort(19446);
        sampler.setProtocol("https");
        sampler.setPath("/identity");
        sampler.setMethod("GET");
        sampler.setHttpProtocol(upgrade ? "" : HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("10000");
        return sampler;
    }

    private static ClientCertificateConfig certificate(String store, String alias) {
        ClientCertificateConfig config = new ClientCertificateConfig();
        config.setProperty(TestElement.GUI_CLASS, ClientCertificateConfigGui.class.getName());
        config.setProperty(ClientCertificateConfig.MODE, ClientCertificateConfig.CERTIFICATE);
        config.setProperty(ClientCertificateConfig.STORE, fixture.resolve(store).toString());
        config.setProperty(ClientCertificateConfig.TYPE, "PKCS12");
        config.setProperty(ClientCertificateConfig.PASSWORD, "password");
        config.setProperty(ClientCertificateConfig.ALIAS, alias);
        return config;
    }

    private static final class Results extends AbstractTestElement implements SampleListener, NoThreadClone {
        final ConcurrentLinkedQueue<SampleEvent> events = new ConcurrentLinkedQueue<>();

        @Override
        public void sampleOccurred(SampleEvent event) {
            events.add(event);
        }

        @Override
        public void sampleStarted(SampleEvent event) {
        }

        @Override
        public void sampleStopped(SampleEvent event) {
        }
    }

    private static final class Rendezvous extends AbstractTestElement implements PreProcessor, NoThreadClone {
        private final CyclicBarrier barrier = new CyclicBarrier(2);

        @Override
        public void process() {
            try {
                barrier.await(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
