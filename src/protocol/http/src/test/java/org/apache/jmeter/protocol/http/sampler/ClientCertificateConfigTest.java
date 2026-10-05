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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.http2.ssl.H2ServerTlsStrategy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.IOSessionListener;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ClientCertificateConfig;
import org.apache.jmeter.config.gui.ClientCertificateConfigGui;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
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
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.SSLManager;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The server reports the authenticated peer, rather than inspecting client-side configuration. */
class ClientCertificateConfigTest {
    @TempDir
    static Path directory;
    private static SSLContext serverContext;
    private HttpAsyncServer server;
    private int port;
    private final Set<String> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger resources = new AtomicInteger();
    private javax.net.ssl.SSLSocketFactory previousFactory;
    private javax.net.ssl.HostnameVerifier previousVerifier;

    @BeforeAll
    static void certificates() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = directory.resolve("jmeter.properties");
            Files.writeString(properties, "");
            JMeterUtils.loadJMeterProperties(properties.toString());
        }
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        KeyStore combined = KeyStore.getInstance("PKCS12");
        combined.load(null, null);
        for (String name : List.of("server", "alice", "bob")) {
            Path path = directory.resolve(name + ".p12");
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1",
                    "-storetype", "PKCS12", "-keystore", path.toString(),
                    "-storepass", "password", "-keypass", "password",
                    "-dname", "CN=" + name, "-ext", "SAN=dns:localhost", "-validity", "2")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            KeyStore keys = load(path);
            trusted.setCertificateEntry(name, keys.getCertificate(name));
            if (!"server".equals(name)) {
                combined.setKeyEntry(name, keys.getKey(name, "password".toCharArray()),
                        "password".toCharArray(), keys.getCertificateChain(name));
            }
        }
        try (var output = Files.newOutputStream(directory.resolve("users.p12"))) {
            combined.store(output, "password".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(directory.resolve("server.p12")), "password".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trusted);
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            keys.load(input, "password".toCharArray());
        }
        return keys;
    }

    @BeforeEach
    void startServer() throws Exception {
        previousFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
        previousVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
        server = H2ServerBootstrap.bootstrap()
                .setCanonicalHostName("localhost")
                .setVersionPolicy(HttpVersionPolicy.NEGOTIATE)
                .setTlsStrategy(new H2ServerTlsStrategy(serverContext,
                        (endpoint, engine) -> engine.setNeedClientAuth(true), null))
                .setIOSessionListener(new IOSessionListener() {
                    @Override
                    public void connected(IOSession session) {
                        connections.add(session.getId());
                    }
                    @Override
                    public void disconnected(IOSession session) {
                        connections.remove(session.getId());
                    }
                    @Override
                    public void startTls(IOSession session) {
                    }
                    @Override
                    public void inputReady(IOSession session) {
                    }
                    @Override
                    public void outputReady(IOSession session) {
                    }
                    @Override
                    public void timeout(IOSession session) {
                    }
                    @Override
                    public void exception(IOSession session, Exception error) {
                    }
                })
                .register("*", new PeerHandler()).create();
        server.start();
        port = ((InetSocketAddress) server.listen(new InetSocketAddress("localhost", 0), URIScheme.HTTPS)
                .get(10, TimeUnit.SECONDS).getAddress()).getPort();
        JMeterContextService.getContext().setVariables(new JMeterVariables());
    }

    @AfterEach
    void cleanup() {
        server.close(CloseMode.IMMEDIATE);
        SSLManager.reset();
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier);
        JMeterContextService.getContext().clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "HTTP/2"})
    void concurrentThreadGroupsPresentDifferentCertificates(String protocol) throws Exception {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        Results results = new Results();
        plan.add(results);
        Rendezvous rendezvous = new Rendezvous();
        for (String name : List.of("alice", "bob")) {
            HashTree group = plan.add(group(name, 1, 3));
            group.add(config(name + ".p12", ""));
            group.add(rendezvous);
            group.add(sampler(protocol));
        }
        run(tree);
        assertEquals(6, results.events.size());
        assertIdentities(results, Map.of("alice", "CN=alice", "bob", "CN=bob"), protocol);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "HTTP/2"})
    void profilesProvideDefaultsAndThreadGroupOverridesWin(String protocol) throws Exception {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        Results results = new Results();
        plan.add(results);
        HashTree profiles = plan.add(new ProfilesSection());
        profiles.add(new SharedProfile()).add(config("bob.p12", ""));
        profiles.add(new Profile("employees")).add(config("alice.p12", ""));
        profiles.add(new Profile("shared-only"));
        HashTree groups = plan.add(new ThreadGroupsSection());
        Scenario scenario = new Scenario("Certificates");
        java.util.ArrayList<ScenarioWorkload> workloads = new java.util.ArrayList<>();
        for (String name : List.of("profile", "override", "shared", "inherit")) {
            ThreadGroup threadGroup = group(name, 1, 2);
            HashTree group = groups.add(threadGroup);
            group.add(sampler(protocol));
            if (name.equals("override")) {
                group.add(config("bob.p12", ""));
            } else if (name.equals("inherit")) {
                ClientCertificateConfig inherited = config("missing.p12", "");
                inherited.setProperty(ClientCertificateConfig.MODE, ClientCertificateConfig.INHERIT);
                group.add(inherited);
            }
            ScenarioWorkload workload = new ScenarioWorkload();
            workload.setName(name);
            workload.setThreadGroupId(name);
            workload.setThreads("1");
            workload.setLoops("2");
            workload.setProfile(name.equals("shared") ? "shared-only" : "employees");
            workloads.add(workload);
        }
        scenario.setWorkloads(workloads);
        plan.add(new ScenariosSection()).add(scenario);
        run(tree);
        assertEquals(8, results.events.size());
        assertIdentities(results, Map.of("profile", "CN=alice", "override", "CN=bob",
                "shared", "CN=bob", "inherit", "CN=alice"), protocol);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "HTTP/2"})
    void csvAliasesSelectPerUserCertificates(String protocol) throws Exception {
        Results results = runCsv(protocol, 2, 1);
        assertEquals(2, results.events.size());
        assertEquals(Set.of("CN=alice", "CN=bob"), results.events.stream()
                .map(event -> event.getResult().getResponseDataAsString()).collect(Collectors.toSet()));
        results.events.forEach(event -> assertSuccessful(event.getResult(), protocol));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "HTTP/2"})
    void changingAliasDoesNotReusePreviousAuthenticatedConnection(String protocol) throws Exception {
        Results results = runCsv(protocol, 1, 3);
        assertEquals(List.of("CN=alice", "CN=bob", "CN=alice"), results.events.stream()
                .map(event -> event.getResult().getResponseDataAsString()).toList());
        results.events.forEach(event -> assertSuccessful(event.getResult(), protocol));
    }

    private Results runCsv(String protocol, int users, int loops) throws Exception {
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
        group.add(config("users.p12", "${clientCertAlias}"));
        group.add(sampler(protocol));
        run(tree);
        return results;
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "HTTP/2"})
    void globalFallbackAndAliasOverrideDoNotChangeGlobalProperties(String protocol) {
        Map<String, String> saved = new java.util.HashMap<>();
        for (String property : List.of("javax.net.ssl.keyStore", "javax.net.ssl.keyStoreType", "javax.net.ssl.keyStorePassword")) {
            saved.put(property, System.getProperty(property));
        }
        try {
            System.setProperty("javax.net.ssl.keyStore", directory.resolve("users.p12").toString());
            System.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
            System.setProperty("javax.net.ssl.keyStorePassword", "password");
            SSLManager.reset();
            ClientCertificateConfig override = config("", "bob");
            override.setProperty(ClientCertificateConfig.STORE, "");
            override.setProperty(ClientCertificateConfig.PASSWORD, "");
            HTTPSamplerProxy scoped = sampler(protocol);
            scoped.addTestElement(override);
            try {
                SampleResult result = scoped.sample();
                assertSuccessful(result, protocol);
                assertEquals("CN=bob", result.getResponseDataAsString());
                assertEquals(directory.resolve("users.p12").toString(), System.getProperty("javax.net.ssl.keyStore"));
                assertEquals("password", System.getProperty("javax.net.ssl.keyStorePassword"));
            } finally {
                scoped.threadFinished();
            }
            // An unchanged legacy plan still uses the global certificate.
            System.setProperty("javax.net.ssl.keyStore", directory.resolve("alice.p12").toString());
            SSLManager.reset();
            HTTPSamplerProxy global = sampler(protocol);
            try {
                SampleResult result = global.sample();
                assertSuccessful(result, protocol);
                assertEquals("CN=alice", result.getResponseDataAsString());
            } finally {
                global.threadFinished();
            }
        } finally {
            saved.forEach((property, value) -> {
                if (value == null) {
                    System.clearProperty(property);
                } else {
                    System.setProperty(property, value);
                }
            });
            SSLManager.reset();
        }
    }

    @Test
    void parallelEmbeddedDownloadsReuseOwnerPoolAndCloseAllSockets() throws Exception {
        HTTPSamplerBase.registerParser("text/html", "org.apache.jmeter.protocol.http.parser.LagartoBasedHtmlParser");
        HTTPSamplerProxy sampler = sampler("HTTP/1.1");
        sampler.setPath("/page");
        sampler.setImageParser(true);
        sampler.setConcurrentDwn(true);
        sampler.setConcurrentPool("3");
        sampler.addTestElement(config("alice.p12", ""));
        try {
            for (int iteration = 0; iteration < 4; iteration++) {
                SampleResult page = sampler.sample();
                assertTrue(page.isSuccessful(), page::getResponseDataAsString);
                assertEquals(3 * (iteration + 1), resources.get(), "All embedded images must be downloaded");
                assertEquals(1, http11Clients().size(), "Embedded downloads must share the owner's pool");
            }
        } finally {
            sampler.threadFinished();
        }
        for (int attempt = 0; attempt < 100 && !connections.isEmpty(); attempt++) {
            Thread.sleep(25);
        }
        assertTrue(connections.isEmpty(), "Finishing the virtual user must close every embedded-resource connection");
    }

    private static Map<?, ?> http11Clients() throws Exception {
        var field = HTTPHC5Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_THREAD_AND_HTTPCLIENTKEY");
        field.setAccessible(true);
        return (Map<?, ?>) ((ThreadLocal<?>) field.get(null)).get();
    }

    @Test
    void rotatingGlobalAliasesAreSelectedOnlyWhenCreatingAnHttp2Client() throws Exception {
        Map<String, String> saved = new java.util.HashMap<>();
        for (String property : List.of("javax.net.ssl.keyStore", "javax.net.ssl.keyStoreType", "javax.net.ssl.keyStorePassword")) {
            saved.put(property, System.getProperty(property));
        }
        HTTPSamplerProxy sampler = sampler("HTTP/2");
        try {
            System.setProperty("javax.net.ssl.keyStore", directory.resolve("users.p12").toString());
            System.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
            System.setProperty("javax.net.ssl.keyStorePassword", "password");
            SSLManager.reset();
            SSLManager.getInstance().configureKeystore(true, 0, -1, "");
            SampleResult first = sampler.sample();
            assertSuccessful(first, "HTTP/2");
            String identity = first.getResponseDataAsString();
            for (int request = 0; request < 12; request++) {
                SampleResult result = sampler.sample();
                assertSuccessful(result, "HTTP/2");
                assertEquals(identity, result.getResponseDataAsString(), "A reused client must not advance global aliases");
            }
            var field = HTTPHC5H2Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_JMETER_THREAD");
            field.setAccessible(true);
            Map<?, ?> clients = (Map<?, ?>) ((Map<?, ?>) field.get(null)).get(Thread.currentThread());
            assertEquals(1, clients.size(), "Global rotation must not create one client per alias");
            sampler.threadFinished();
            SampleResult nextUser = sampler.sample();
            assertSuccessful(nextUser, "HTTP/2");
            assertNotEquals(identity, nextUser.getResponseDataAsString(), "A new client still advances rotation");
        } finally {
            sampler.threadFinished();
            saved.forEach((property, value) -> {
                if (value == null) {
                    System.clearProperty(property);
                } else {
                    System.setProperty(property, value);
                }
            });
            SSLManager.reset();
        }
    }

    @Test
    void invalidOverridesFailInsteadOfUsingAnotherIdentity() {
        for (ClientCertificateConfig config : List.of(config("absent.p12", ""),
                config("alice.p12", "missing"), config("users.p12", ""), config("alice.p12", "${missing}"))) {
            HTTPSamplerProxy sampler = sampler("HTTP/1.1");
            sampler.addTestElement(config);
            try {
                assertFalse(sampler.sample().isSuccessful());
            } finally {
                sampler.threadFinished();
            }
        }
        ClientCertificateConfig wrongPassword = config("alice.p12", "");
        wrongPassword.setProperty(ClientCertificateConfig.PASSWORD, "wrong");
        HTTPSamplerProxy sampler = sampler("HTTP/1.1");
        sampler.addTestElement(wrongPassword);
        try {
            assertFalse(sampler.sample().isSuccessful());
        } finally {
            sampler.threadFinished();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", "HTTP/2"})
    void noCertificateOverridesAnOuterCertificate(String protocol) throws Exception {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        plan.add(config("alice.p12", ""));
        Results results = new Results();
        plan.add(results);
        HashTree group = plan.add(group("anonymous", 1, 1));
        ClientCertificateConfig none = config("", "");
        none.setProperty(ClientCertificateConfig.MODE, ClientCertificateConfig.NONE);
        group.add(none);
        group.add(sampler(protocol));
        run(tree);
        assertEquals(1, results.events.size());
        assertFalse(results.events.element().getResult().isSuccessful(), "mTLS server requires a client certificate");
    }

    private static void run(HashTree tree) throws Exception {
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(tree);
        engine.runTest();
        try {
            engine.awaitTermination(Duration.ofSeconds(30));
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

    private HTTPSamplerProxy sampler(String protocol) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Authenticated peer");
        sampler.setDomain("localhost");
        sampler.setPort(port);
        sampler.setProtocol("https");
        sampler.setPath("/identity");
        sampler.setMethod("GET");
        sampler.setHttpProtocol(protocol);
        sampler.setUseKeepAlive(true);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("5000");
        return sampler;
    }

    private static ClientCertificateConfig config(String file, String alias) {
        ClientCertificateConfig config = new ClientCertificateConfig();
        config.setName("Client certificate");
        config.setProperty(TestElement.GUI_CLASS, ClientCertificateConfigGui.class.getName());
        config.setProperty(ClientCertificateConfig.MODE, ClientCertificateConfig.CERTIFICATE);
        config.setProperty(ClientCertificateConfig.STORE, directory.resolve(file).toString());
        config.setProperty(ClientCertificateConfig.TYPE, "PKCS12");
        config.setProperty(ClientCertificateConfig.PASSWORD, "password");
        config.setProperty(ClientCertificateConfig.ALIAS, alias);
        return config;
    }

    private static void assertIdentities(Results results, Map<String, String> expected, String protocol) {
        for (SampleEvent event : results.events) {
            assertSuccessful(event.getResult(), protocol);
            assertEquals(expected.get(event.getThreadGroup()), event.getResult().getResponseDataAsString(),
                    event.getThreadGroup());
        }
    }

    private static void assertSuccessful(SampleResult result, String protocol) {
        assertTrue(result.isSuccessful(), () -> result.getResponseCode() + " " + result.getResponseDataAsString());
        assertTrue(result.getResponseHeaders().startsWith(protocol), result.getResponseHeaders());
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

    /** Every iteration starts with both thread groups alive and ready to send. */
    private static final class Rendezvous extends AbstractTestElement implements PreProcessor, NoThreadClone {
        private final CyclicBarrier barrier = new CyclicBarrier(2);

        @Override
        public void process() {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final class PeerHandler implements AsyncServerRequestHandler<Message<HttpRequest, Void>> {
        @Override
        public AsyncRequestConsumer<Message<HttpRequest, Void>> prepare(
                HttpRequest request, EntityDetails entityDetails, HttpContext context) {
            return new BasicRequestConsumer<>(entityDetails == null ? null : new DiscardingEntityConsumer<>());
        }

        @Override
        public void handle(Message<HttpRequest, Void> request, ResponseTrigger trigger, HttpContext context)
                throws IOException, HttpException {
            String peer = HttpCoreContext.cast(context).getSSLSession().getPeerPrincipal().getName();
            if (request.getHead().getPath().equals("/page")) {
                trigger.submitResponse(AsyncResponseBuilder.create(200)
                        .setEntity("<html><img src='/resource/a'><img src='/resource/b'><img src='/resource/c'></html>",
                                ContentType.TEXT_HTML).build(), context);
            } else {
                if (request.getHead().getPath().startsWith("/resource/")) {
                    resources.incrementAndGet();
                }
                trigger.submitResponse(AsyncResponseBuilder.create(200).setEntity(peer).build(), context);
            }
        }
    }
}
