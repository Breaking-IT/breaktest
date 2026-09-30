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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.security.auth.Subject;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.core5.http.HttpHost;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.wiremock.WireMockExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

class TestKerberosAuthentication {
    @BeforeAll
    static void setupProperties() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = Files.createTempFile("jmeter", ".properties");
            JMeterUtils.loadJMeterProperties(properties.toString());
            Files.delete(properties);
        }
    }

    @Test
    void kerberosFallbackIsSelectedPerMatchingUrlAndReused() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        AuthManager manager = new AuthManager();
        manager.set(-1, "http://localhost/secure", "user", "password", "", "", Mechanism.KERBEROS);
        sampler.setAuthManager(manager);
        HTTPHC5H2Impl client = new HTTPHC5H2Impl(sampler);
        URL secure = URI.create("http://localhost/secure").toURL();
        HTTPHC5Impl fallback = client.kerberosClientFor(secure);
        assertNotNull(fallback);
        assertSame(fallback, client.kerberosClientFor(secure));
        assertNull(client.kerberosClientFor(URI.create("http://localhost/public").toURL()));
        manager.get(0).setMechanism(Mechanism.BASIC);
        assertNull(client.kerberosClientFor(secure));
    }

    @Test
    @SuppressWarnings("deprecation")
    void kerberosCredentialsCannotAnswerPasswordChallenges() {
        AuthManager manager = new AuthManager();
        manager.set(-1, "http://localhost:8080/", "user", "password", "", "BREAKTEST.TEST", Mechanism.KERBEROS);
        HTTPHC5Impl.ManagedCredentialsProvider provider = new HTTPHC5Impl.ManagedCredentialsProvider(manager, null, null);
        for (String scheme : List.of(StandardAuthScheme.BASIC, StandardAuthScheme.DIGEST, StandardAuthScheme.NTLM)) {
            assertNull(provider.getCredentials(new AuthScope(new HttpHost("http", "localhost", 8080), null, scheme), null));
        }
        for (String scheme : List.of(StandardAuthScheme.SPNEGO, StandardAuthScheme.KERBEROS)) {
            var credentials = provider.getCredentials(new AuthScope(new HttpHost("http", "localhost", 8080), null, scheme), null);
            assertNotNull(credentials);
            assertNull(credentials.getPassword());
            assertNull(provider.getCredentials(new AuthScope(new HttpHost("http", "other.test", 8080), null, scheme), null));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "HTTP/2", "HTTP/3"})
    void kerberosDoesNotFallBackToBasic(String protocol) throws Exception {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        HTTPSamplerProxy sampler = sampler(protocol, server.port(), "/basic");
        HTTPJavaHttp3Impl http3 = new HTTPJavaHttp3Impl(sampler, HTTPJavaHttp3Impl.Http3Discovery.PREFER_HTTP3);
        try {
            server.stubFor(WireMock.get("/basic").willReturn(WireMock.aResponse().withStatus(401)
                    .withHeader("WWW-Authenticate", "Basic realm=\"fixture\"")));
            AuthManager manager = new AuthManager() {
                @Override
                public Subject getSubjectForUrl(URL url) {
                    return new Subject();
                }
            };
            manager.set(-1, sampler.getUrl().toString(), "user", "password", "", "", Mechanism.KERBEROS);
            sampler.setAuthManager(manager);
            SampleResult result = protocol.equals("HTTP/3")
                    ? http3.sample(sampler.getUrl(), "GET", false, 0) : sampler.sample();
            assertEquals("401", result.getResponseCode(), result.getResponseMessage());
            server.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo("/basic"))
                    .withHeader("Authorization", WireMock.matching("Basic .*")));
        } finally {
            sampler.threadFinished();
            http3.threadFinished();
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "HTTP/2"})
    void failedLoginStopsBeforeSendingHttpRequest(String protocol) throws Exception {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        HTTPSamplerProxy sampler = sampler(protocol, server.port(), "/secure");
        try {
            AuthManager manager = new AuthManager() {
                @Override
                public Subject getSubjectForUrl(URL url) {
                    return null;
                }
            };
            manager.set(-1, sampler.getUrl().toString(), "user", "password", "", "", Mechanism.KERBEROS);
            sampler.setAuthManager(manager);
            SampleResult result = sampler.sample();
            assertFalse(result.isSuccessful());
            assertTrue(result.getResponseMessage().contains("Kerberos login failed"), result.getResponseMessage());
            assertEquals(0, server.getAllServeEvents().size());
        } finally {
            sampler.threadFinished();
            server.stop();
        }
    }

    private static HTTPSamplerProxy sampler(String protocol, int port, String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProtocol("http");
        sampler.setDomain("localhost");
        sampler.setPort(port);
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setHttpProtocol(protocol);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("5000");
        return sampler;
    }
}
