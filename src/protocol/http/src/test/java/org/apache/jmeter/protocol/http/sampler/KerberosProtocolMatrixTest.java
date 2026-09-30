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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.security.auth.login.Configuration;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.test.samplers.CollectSamplesListener;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real KDC tests, including JMX loading and the engine's config/variable/iteration lifecycle. */
@EnabledIfEnvironmentVariable(named = "BREAKTEST_KERBEROS_FIXTURE", matches = ".+")
@Timeout(60)
class KerberosProtocolMatrixTest extends JMeterTestCase {
    @TempDir
    Path temporary;
    private Path fixture;
    private String previousKrb5;
    private String previousJaas;
    private JMeterVariables previousVariables;
    private final List<HTTPSamplerProxy> samplers = new ArrayList<>();

    @BeforeEach
    void configureFixture() {
        fixture = Path.of(System.getenv("BREAKTEST_KERBEROS_FIXTURE"));
        previousKrb5 = System.getProperty("java.security.krb5.conf");
        previousJaas = System.getProperty("java.security.auth.login.config");
        previousVariables = JMeterContextService.getContext().getVariables();
        System.setProperty("java.security.krb5.conf", fixture.resolve("krb5.conf").toString());
        System.setProperty("java.security.auth.login.config", fixture.resolve("jaas.conf").toString());
        Configuration.getConfiguration().refresh();
        JMeterContextService.getContext().setVariables(new JMeterVariables());
    }

    @AfterEach
    void restoreConfiguration() {
        samplers.forEach(HTTPSamplerProxy::threadFinished);
        JMeterContextService.getContext().setVariables(previousVariables);
        restore("java.security.krb5.conf", previousKrb5);
        restore("java.security.auth.login.config", previousJaas);
        Configuration.getConfiguration().refresh();
    }

    private record ProtocolCase(String name, String sampler, String defaults, String effective) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> scriptProtocols() {
        List<ProtocolCase> cases = List.of(
                new ProtocolCase("property absent", null, null, ""),
                new ProtocolCase("explicit blank", "", null, ""),
                new ProtocolCase("fixed HTTP/1.1", "HTTP/1.1", null, "HTTP/1.1"),
                new ProtocolCase("fixed HTTP/2", "HTTP/2", null, "HTTP/2"),
                new ProtocolCase("legacy HTTP/2.0", "HTTP/2.0", null, "HTTP/2"),
                new ProtocolCase("legacy HTTP 2.0", "HTTP 2.0", null, "HTTP/2"),
                new ProtocolCase("legacy HTTP/2 preferred", "HTTP/2 preferred", null, "HTTP/2"),
                new ProtocolCase("inherited HTTP/1.1", null, "HTTP/1.1", "HTTP/1.1"),
                new ProtocolCase("inherited HTTP/2", null, "HTTP/2", "HTTP/2"),
                new ProtocolCase("blank inherits HTTP/2", "", "HTTP/2", "HTTP/2"),
                new ProtocolCase("HTTP/1.1 overrides defaults", "HTTP/1.1", "HTTP/2", "HTTP/1.1"),
                new ProtocolCase("HTTP/2 overrides defaults", "HTTP/2", "HTTP/1.1", "HTTP/2"),
                new ProtocolCase("protocol variable", "${wireProtocol}", null, "HTTP/2"),
                new ProtocolCase("variable in defaults", null, "${wireProtocol}", "HTTP/2"));
        return Stream.of("http", "https").flatMap(scheme -> cases.stream()
                .map(setting -> org.junit.jupiter.params.provider.Arguments.of(scheme, setting)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("scriptProtocols")
    void savedJmxAuthenticatesWithDefaultsAndVariables(String scheme, ProtocolCase setting) throws Exception {
        TestPlan plan = new TestPlan();
        plan.setName("Kerberos protocol matrix");
        plan.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        Arguments variables = new Arguments();
        variables.addArgument("wireProtocol", "HTTP/2");
        variables.addArgument("principal", "tester@BREAKTEST.TEST");
        variables.addArgument("password", "fixture-password");
        plan.setUserDefinedVariables(variables);
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.LoopControlPanel");
        org.apache.jmeter.threads.ThreadGroup group = new org.apache.jmeter.threads.ThreadGroup();
        group.setName("Two concurrent users, two iterations");
        group.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        group.setNumThreads(2);
        group.setRampUp(0);
        group.setSamplerController(loop);
        group.setIsSameUserOnNextIteration(false);
        ListedHashTree tree = new ListedHashTree();
        HashTree planTree = tree.add(plan);
        HashTree groupTree = planTree.add(group);
        if (setting.defaults() != null) {
            ConfigTestElement defaults = new ConfigTestElement();
            defaults.setName("HTTP Request Defaults");
            defaults.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui");
            defaults.setProperty(HTTPSamplerBase.HTTP_PROTOCOL, setting.defaults());
            groupTree.add(defaults);
        }
        AuthManager manager = manager(scheme, "${principal}", "${password}");
        manager.setClearEachIteration(true);
        manager.get(0).setURL(origin(scheme) + "/secure");
        manager.set(-1, origin(scheme) + "/mixed", "${principal}", "${password}", "", "", Mechanism.KERBEROS);
        groupTree.add(manager);
        List<String> paths = scheme.equals("https")
                ? List.of("/secure", "/mixed", "/public", "/secure") : List.of("/secure", "/mixed", "/secure");
        for (String path : paths) {
            HTTPSamplerProxy sampler = sampler(scheme, setting.sampler(), path);
            sampler.setName(path + " " + groupTree.size());
            groupTree.add(sampler);
        }
        Path jmx = temporary.resolve("matrix.jmx");
        try (OutputStream output = Files.newOutputStream(jmx)) {
            SaveService.saveTree(tree, output);
        }
        String xml = new String(SaveService.readArchiveEntry(jmx.toFile(), SaveService.TEST_PLAN_ZIP_ENTRY).orElseThrow(),
                StandardCharsets.UTF_8);
        if (setting.sampler() == null && setting.defaults() == null) {
            assertFalse(xml.contains(HTTPSamplerBase.HTTP_PROTOCOL), "unset really must be absent from the script");
        }
        HashTree reloaded = SaveService.loadTree(jmx.toFile());
        CollectSamplesListener listener = new CollectSamplesListener();
        reloaded.getTree(reloaded.getArray()[0]).add(listener);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        try {
            engine.configure(reloaded);
            engine.runTest();
            engine.awaitTermination(Duration.ofSeconds(45));
            assertEquals(paths.size() * 4, listener.getEvents().size(), "No sampler/iteration may be silently skipped");
            for (var event : listener.getEvents()) {
                SampleResult result = event.getResult();
                if (result.getSampleLabel().startsWith("/public")) {
                    assertTrue(result.isSuccessful(), diagnostic(result));
                    assertHeader(result, "X-Fixture-Auth", "none");
                    assertHeader(result, "X-Fixture-Protocol", setting.effective().equals("HTTP/1.1") ? "HTTP/1.1" : "HTTP/2.0");
                } else {
                    assertAuthenticated(result, "tester@BREAKTEST.TEST");
                }
            }
        } finally {
            if (engine.isActive()) {
                engine.stopTest(true);
                engine.awaitTermination(Duration.ofSeconds(5));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "HTTP/2", "HTTP/2.0", "HTTP 2.0", "HTTP/2 preferred"})
    void httpsFixtureReallyNegotiatesHttp2WhenKerberosIsNotSelected(String protocol) throws Exception {
        HTTPSamplerProxy sampler = sampler("https", protocol, "/public");
        SampleResult result = sampler.sample();
        assertTrue(result.isSuccessful(), diagnostic(result));
        assertEquals("public", result.getResponseDataAsString());
        assertHeader(result, "X-Fixture-Protocol", protocol.equals("HTTP/1.1") ? "HTTP/1.1" : "HTTP/2.0");
        assertHeader(result, "X-Fixture-Auth", "none");
    }

    @Test
    void protocolVariableCanSwitchOnOneSamplerBetweenIterations() throws Exception {
        HTTPSamplerProxy sampler = sampler("https", "${wireProtocol}", "/public");
        new ValueReplacer().replaceValues(sampler);
        sampler.setRunningVersion(true);
        int iteration = 0;
        for (String protocol : List.of("HTTP/1.1", "HTTP/2", "", "HTTP/1.1", "HTTP/2.0")) {
            JMeterContextService.getContext().getVariables().put("wireProtocol", protocol);
            sampler.testIterationStart(new LoopIterationEvent(sampler, ++iteration));
            SampleResult result = sampler.sample();
            assertTrue(result.isSuccessful(), diagnostic(result));
            assertHeader(result, "X-Fixture-Protocol", protocol.equals("HTTP/1.1") ? "HTTP/1.1" : "HTTP/2.0");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void changingProtocolCredentialsAndAuthScopeDoesNotReuseAnotherIdentity(String scheme) throws Exception {
        HTTPSamplerProxy sampler = sampler(scheme, null, "/secure");
        int iteration = 0;
        for (String protocol : List.of("", "HTTP/1.1", "HTTP/2", "HTTP/1.1", "")) {
            sampler.setHttpProtocol(protocol);
            boolean other = ++iteration % 2 == 0;
            String principal = other ? "other@BREAKTEST.TEST" : "tester@BREAKTEST.TEST";
            sampler.setAuthManager(manager(scheme, principal, other ? "other-password" : "fixture-password"));
            sampler.testIterationStart(new LoopIterationEvent(sampler, iteration));
            sampler.setPath("/secure");
            assertAuthenticated(sampler.sample(), principal);
            sampler.setPath("/public");
            sampler.getAuthManager().get(0).setURL(origin(scheme) + "/secure");
            SampleResult publicResult = sampler.sample();
            if (scheme.equals("http") && protocol.equals("HTTP/2")) {
                assertFalse(publicResult.isSuccessful(), "The cleartext fixture only speaks HTTP/1.1; forced h2 must fail");
                continue;
            }
            assertTrue(publicResult.isSuccessful(), diagnostic(publicResult));
            assertHeader(publicResult, "X-Fixture-Auth", "none");
            if (scheme.equals("https")) {
                assertHeader(publicResult, "X-Fixture-Protocol", protocol.equals("HTTP/1.1") ? "HTTP/1.1" : "HTTP/2.0");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "HTTP/2"})
    void authenticationSupportsPostPutHeadAndRedirects(String protocol) throws Exception {
        HTTPSamplerProxy sampler = sampler("https", protocol, "/echo");
        sampler.setAuthManager(manager("https", "tester@BREAKTEST.TEST", "fixture-password"));
        for (String method : List.of("POST", "PUT")) {
            sampler.setMethod(method);
            sampler.setPostBodyRaw(true);
            sampler.getArguments().removeAllArguments();
            sampler.addNonEncodedArgument("", "kerberos replay body", "");
            SampleResult result = sampler.sample();
            assertTrue(result.isSuccessful(), diagnostic(result));
            assertEquals("kerberos replay body", result.getResponseDataAsString());
            assertHeader(result, "X-Fixture-Principal", "tester@BREAKTEST.TEST");
            assertHeader(result, "X-Fixture-Method", method);
        }
        sampler.getArguments().removeAllArguments();
        sampler.setMethod("HEAD");
        sampler.setPath("/secure");
        SampleResult head = sampler.sample();
        assertTrue(head.isSuccessful(), diagnostic(head));
        assertHeader(head, "X-Fixture-Principal", "tester@BREAKTEST.TEST");
        assertEquals(0, head.getResponseData().length);
        sampler.setMethod("GET");
        sampler.setPath("/redirect");
        sampler.setFollowRedirects(true);
        assertAuthenticated(sampler.sample(), "tester@BREAKTEST.TEST");
        sampler.setFollowRedirects(false);
        sampler.setAutoRedirects(true);
        assertAuthenticated(sampler.sample(), "tester@BREAKTEST.TEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "HTTP/2"})
    void missingAuthWrongPasswordAndBasicChallengeFailThenRecover(String protocol) throws Exception {
        HTTPSamplerProxy sampler = sampler("https", protocol, "/secure");
        assertEquals("401", sampler.sample().getResponseCode());
        AuthManager wrong = manager("https", "tester@BREAKTEST.TEST", "wrong-password");
        sampler.setAuthManager(wrong);
        SampleResult failed = sampler.sample();
        assertFalse(failed.isSuccessful());
        assertTrue(failed.getResponseMessage().contains("Kerberos login failed"), diagnostic(failed));
        wrong.get(0).setPass("fixture-password");
        wrong.testStarted(); // A fresh test must clear cached failed logins.
        assertAuthenticated(sampler.sample(), "tester@BREAKTEST.TEST");
        sampler.setPath("/basic");
        assertEquals("401", sampler.sample().getResponseCode(), "Basic credentials must never be sent");
        sampler.setPath("/mixed");
        assertAuthenticated(sampler.sample(), "tester@BREAKTEST.TEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "HTTP/2"})
    void kerberosTimeoutPreservesClassificationAndCanRecover(String protocol) throws Exception {
        HTTPSamplerProxy sampler = sampler("https", protocol, "/slow");
        sampler.setAuthManager(manager("https", "tester@BREAKTEST.TEST", "fixture-password"));
        sampler.setResponseTimeout("200");
        SampleResult result = sampler.sample();
        assertFalse(result.isSuccessful());
        assertEquals("Response timeout", result.getResponseCode(), diagnostic(result));
        assertFalse(result.getResponseDataAsString().contains("Private Credential"));
        assertFalse(result.getResponseMessage().contains("Subject:"));
        sampler.setResponseTimeout("5000");
        sampler.setPath("/secure");
        assertAuthenticated(sampler.sample(), "tester@BREAKTEST.TEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "HTTP/2"})
    void kerberosRequestCanBeInterruptedAfterAuthentication(String protocol) throws Exception {
        String requestId = UUID.randomUUID().toString();
        HTTPSamplerProxy sampler = sampler("https", protocol, "/slow?id=" + requestId);
        sampler.setAuthManager(manager("https", "tester@BREAKTEST.TEST", "fixture-password"));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> {
                JMeterContextService.getContext().setVariables(new JMeterVariables());
                try {
                    return sampler.sample();
                } finally {
                    sampler.threadFinished();
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean authenticated = false;
            while (System.nanoTime() < deadline && !pending.isDone()) {
                HttpURLConnection readiness = (HttpURLConnection)
                        URI.create(origin("http") + "/slow-ready?id=" + requestId).toURL().openConnection();
                readiness.setConnectTimeout(1000);
                readiness.setReadTimeout(1000);
                try {
                    authenticated = readiness.getResponseCode() == 200;
                } finally {
                    readiness.disconnect();
                }
                if (authenticated) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(authenticated, "The server must verify the ticket before the request is interrupted");
            assertTrue(sampler.interrupt(), "The fallback must forward cancellation to its active client");
            SampleResult result = pending.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccessful(), diagnostic(result));
            assertFalse(result.getResponseDataAsString().contains("Private Credential"));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void http3SelectionThroughSamplerFactoryHasDocumentedOutcome() throws Exception {
        HTTPSamplerProxy sampler = sampler("https", "HTTP/3", "/secure");
        sampler.setAuthManager(manager("https", "tester@BREAKTEST.TEST", "fixture-password"));
        SampleResult result = sampler.sample();
        if (Http3RuntimeSupport.isHttp3Supported()) {
            assertFalse(result.isSuccessful());
            assertTrue(result.getResponseMessage().contains("disable HTTP/3-only mode"), diagnostic(result));
        } else {
            assertAuthenticated(result, "tester@BREAKTEST.TEST");
        }
    }

    @Test
    void explicitHttp3OnlyRejectsKerberosWithAnActionableError() throws Exception {
        HTTPSamplerProxy sampler = sampler("https", "HTTP/3", "/secure");
        sampler.setAuthManager(manager("https", "tester@BREAKTEST.TEST", "fixture-password"));
        HTTPJavaHttp3Impl client = new HTTPJavaHttp3Impl(sampler, HTTPJavaHttp3Impl.Http3Discovery.HTTP3_ONLY);
        try {
            SampleResult result = client.sample(sampler.getUrl(), "GET", false, 0);
            assertFalse(result.isSuccessful());
            assertTrue(result.getResponseMessage().contains("disable HTTP/3-only mode"), diagnostic(result));
        } finally {
            client.threadFinished();
        }
    }

    private HTTPSamplerProxy sampler(String scheme, String protocol, String path) throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        sampler.setProtocol(scheme);
        sampler.setDomain("localhost");
        sampler.setPort(port(scheme));
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setUseKeepAlive(true);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("5000");
        if (protocol != null) {
            // Raw properties reproduce legacy/imported JMX values without setter normalization.
            sampler.setProperty(HTTPSamplerBase.HTTP_PROTOCOL, protocol);
        }
        samplers.add(sampler);
        return sampler;
    }

    private AuthManager manager(String scheme, String principal, String password) throws Exception {
        AuthManager manager = new AuthManager();
        manager.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.gui.AuthPanel");
        manager.set(-1, origin(scheme) + "/", principal, password, "", "", Mechanism.KERBEROS);
        return manager;
    }

    private String origin(String scheme) throws Exception {
        return scheme + "://localhost:" + port(scheme);
    }

    private int port(String scheme) throws Exception {
        return Integer.parseInt(Files.readString(fixture.resolve(scheme + "-port")).trim());
    }

    private static void assertAuthenticated(SampleResult result, String principal) {
        assertTrue(result.isSuccessful(), diagnostic(result));
        assertEquals(principal, result.getResponseDataAsString());
        assertHeader(result, "X-Fixture-Principal", principal);
        assertHeader(result, "X-Fixture-Protocol", "HTTP/1.1");
    }

    private static void assertHeader(SampleResult result, String name, String value) {
        assertTrue(result.getResponseHeaders().lines().anyMatch(line -> line.equalsIgnoreCase(name + ": " + value)),
                name + " should be " + value + "\n" + diagnostic(result));
    }

    private static String diagnostic(SampleResult result) {
        return result.getSampleLabel() + ": " + result.getResponseCode() + " " + result.getResponseMessage()
                + "\n" + result.getResponseHeaders() + "\n" + result.getResponseDataAsString();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
