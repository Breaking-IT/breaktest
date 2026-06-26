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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

public class TestHTTPHC5Impl {
    private static final String HTTP2_IO_THREAD_COUNT = "httpclient5.http2.io_thread_count";

    @BeforeAll
    public static void setupJMeterProperties() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = Files.createTempFile("jmeter", ".properties");
            JMeterUtils.loadJMeterProperties(properties.toString());
            Files.deleteIfExists(properties);
        }
    }

    @Test
    public void factorySelectsHttpClient5Implementations() {
        assertArrayEquals(new String[] {
                HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT,
                HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1,
                HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2
        }, HTTPSamplerBase.getHttpProtocolList());

        HTTPSamplerProxy defaultProtocol = new HTTPSamplerProxy();
        assertEquals(HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT, defaultProtocol.getHttpProtocol());
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(defaultProtocol.getImplementation(), defaultProtocol).getClass());

        HTTPSamplerProxy http2 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2.getImplementation(), http2).getClass());

        HTTPSamplerProxy http2SpaceLegacy = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2SpaceLegacy.set(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol(), "HTTP 2.0");
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2SpaceLegacy.getImplementation(), http2SpaceLegacy).getClass());

        HTTPSamplerProxy http2DotLegacy = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2DotLegacy.set(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol(), "HTTP/2.0");
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2DotLegacy.getImplementation(), http2DotLegacy).getClass());

        HTTPSamplerProxy http2PreferredLegacy = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2PreferredLegacy.set(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol(), "HTTP/2 preferred");
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2PreferredLegacy.getImplementation(), http2PreferredLegacy).getClass());

        HTTPSamplerProxy http1 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http1.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        assertEquals(HTTPHC5Impl.class,
                HTTPSamplerFactory.getImplementation(http1.getImplementation(), http1).getClass());

        HTTPSamplerProxy legacyHc4 = new HTTPSamplerProxy("HttpClient4");
        legacyHc4.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(legacyHc4.getImplementation(), legacyHc4).getClass());

        HTTPSamplerProxy legacyJava = new HTTPSamplerProxy("Java");
        legacyJava.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        assertEquals(HTTPHC5Impl.class,
                HTTPSamplerFactory.getImplementation(legacyJava.getImplementation(), legacyJava).getClass());
    }

    @Test
    public void http2VersionPolicyDistinguishesDefaultFromExplicitHttp2() {
        HTTPSamplerProxy defaultProtocol = new HTTPSamplerProxy();
        assertEquals(HttpVersionPolicy.NEGOTIATE, new HTTPHC5H2Impl(defaultProtocol).versionPolicy());

        HTTPSamplerProxy explicitHttp2 = new HTTPSamplerProxy();
        explicitHttp2.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HttpVersionPolicy.FORCE_HTTP_2, new HTTPHC5H2Impl(explicitHttp2).versionPolicy());
    }

    @Test
    public void protocolVersionIsFormattedForDisplay() {
        assertEquals("HTTP/1.1", HTTPHC5Impl.formatProtocolVersion(HttpVersion.HTTP_1_1));
        assertEquals("HTTP/2", HTTPHC5Impl.formatProtocolVersion(HttpVersion.HTTP_2_0));
    }

    @Test
    public void http2RemovesHttp1SpecificRequestHeaders() throws Exception {
        HttpGet request = new HttpGet(new URI("https://example.test/resource"));
        request.addHeader(HTTPConstants.HEADER_HOST, "example.test");
        request.addHeader(HTTPConstants.HEADER_CONNECTION, "Foo, Bar");
        request.addHeader("Foo", "foo-value");
        request.addHeader("Bar", "bar-value");
        request.addHeader("X-Custom", "custom-value");

        HTTPHC5H2Impl.removeHeadersUnsupportedByHttp2(request);

        assertFalse(request.containsHeader(HTTPConstants.HEADER_HOST));
        assertFalse(request.containsHeader(HTTPConstants.HEADER_CONNECTION));
        assertFalse(request.containsHeader("Foo"));
        assertFalse(request.containsHeader("Bar"));
        assertTrue(request.containsHeader("X-Custom"));
        assertEquals("custom-value", request.getFirstHeader("X-Custom").getValue());
    }

    @Test
    public void http2ResponseHeadersUseHttp2StatusLineWithoutReasonPhrase() {
        BasicHttpResponse response = new BasicHttpResponse(200, "OK");
        response.setVersion(HttpVersion.HTTP_2);
        response.addHeader("Content-Type", "text/plain");

        assertEquals("HTTP/2 200\nContent-Type: text/plain\n", HTTPHC5H2Impl.getResponseHeaders(response));
    }

    @Test
    public void http2UsesSingleIoReactorThreadByDefault() {
        Object previous = JMeterUtils.getJMeterProperties().remove(HTTP2_IO_THREAD_COUNT);
        try {
            IOReactorConfig config = new HTTPHC5H2Impl(new HTTPSamplerProxy()).createIOReactorConfig();

            assertEquals(1, config.getIoThreadCount());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void http2IoReactorThreadCountCanBeOverridden() {
        Object previous = JMeterUtils.setProperty(HTTP2_IO_THREAD_COUNT, "3");
        try {
            IOReactorConfig config = new HTTPHC5H2Impl(new HTTPSamplerProxy()).createIOReactorConfig();

            assertEquals(3, config.getIoThreadCount());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void http2IoReactorThreadCountHasMinimumOfOne() {
        Object previous = JMeterUtils.setProperty(HTTP2_IO_THREAD_COUNT, "0");
        try {
            IOReactorConfig config = new HTTPHC5H2Impl(new HTTPSamplerProxy()).createIOReactorConfig();

            assertEquals(1, config.getIoThreadCount());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void http11ReportsSentBytes() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        try {
            server.stubFor(WireMock.get("/sent-bytes").willReturn(WireMock.aResponse().withBody("ok")));

            HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
            sampler.setDomain("localhost");
            sampler.setPort(server.port());
            sampler.setPath("/sent-bytes");
            sampler.setMethod(HTTPConstants.GET);
            sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);

            SampleResult result = sampler.sample();

            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertTrue(result.getSentBytes() > 0,
                    () -> "Expected HTTP/1.1 sent bytes, got: " + result.getSentBytes());
            assertEquals("ok", result.getResponseDataAsString());
        } finally {
            server.stop();
        }
    }

    @Test
    public void defaultProtocolFallsBackToHttp11WhenServerDoesNotSupportHttp2() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        try {
            server.stubFor(WireMock.get("/fallback").willReturn(WireMock.aResponse().withBody("ok")));

            HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
            sampler.setDomain("localhost");
            sampler.setPort(server.port());
            sampler.setPath("/fallback");
            sampler.setMethod(HTTPConstants.GET);

            SampleResult result = sampler.sample();

            assertTrue(result.isSuccessful(), result.getResponseMessage());
            assertTrue(result.getResponseHeaders().startsWith("HTTP/1.1"),
                    () -> "Expected HTTP/1.1 fallback, got: " + result.getResponseHeaders());
            assertTrue(result.getHeadersSize() > 0,
                    () -> "Expected fallback response headers size, got: " + result.getHeadersSize());
            assertTrue(result.getSentBytes() > 0,
                    () -> "Expected HTTP/2 fallback sent bytes, got: " + result.getSentBytes());
            assertEquals("ok", result.getResponseDataAsString());
        } finally {
            server.stop();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP2_LIVE", matches = "true")
    public void http2NegotiatesHttp2AgainstBreaktestApp() {
        SampleResult result = sampleBreaktestApp(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);

        assertTrue(result.isSuccessful(), result.getResponseMessage());
        assertTrue(result.getResponseHeaders().startsWith("HTTP/2"),
                () -> "Expected HTTP/2 response, got: " + result.getResponseHeaders());
        assertTrue(result.getConnectTime() > 0,
                () -> "Expected first HTTP/2 request to report connect time, got: "
                        + result.getConnectTime());
        assertTrue(result.getHeadersSize() > 0,
                () -> "Expected HTTP/2 response headers size, got: " + result.getHeadersSize());
        assertTrue(result.getSentBytes() > 0,
                () -> "Expected HTTP/2 sent bytes, got: " + result.getSentBytes());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP_LIVE", matches = "true")
    public void http11ReportsConnectTimeAgainstBreaktestApp() {
        SampleResult result = sampleBreaktestApp(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);

        assertTrue(result.isSuccessful(), result.getResponseMessage());
        assertTrue(result.getResponseHeaders().startsWith("HTTP/1.1"),
                () -> "Expected HTTP/1.1 response, got: " + result.getResponseHeaders());
        assertTrue(result.getConnectTime() > 0,
                () -> "Expected first HTTP/1.1 request to report connect time, got: "
                        + result.getConnectTime());
        assertTrue(result.getHeadersSize() > 0,
                () -> "Expected HTTP/1.1 response headers size, got: " + result.getHeadersSize());
        assertTrue(result.getSentBytes() > 0,
                () -> "Expected HTTP/1.1 sent bytes, got: " + result.getSentBytes());
    }

    private static SampleResult sampleBreaktestApp(String httpProtocol) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
        sampler.setDomain("breaktest.app");
        sampler.setPath("/");
        sampler.setMethod(HTTPConstants.GET);
        sampler.setHttpProtocol(httpProtocol);
        return sampler.sample();
    }

    @Test
    public void preemptiveBasicAuthAddsAuthorizationHeaderForMatchingUrl() throws Exception {
        HTTPHC5Impl sampler = samplerWithAuth("http://example.test/secure/");
        HttpGet request = new HttpGet(new URI("http://example.test/secure/resource"));

        sampler.setupPreemptiveBasicAuth(new URI("http://example.test/secure/resource").toURL(), request);

        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8)),
                request.getLastHeader(HTTPConstants.HEADER_AUTHORIZATION).getValue());
    }

    @Test
    public void preemptiveBasicAuthDoesNotAddAuthorizationHeaderForDifferentUrl() throws Exception {
        HTTPHC5Impl sampler = samplerWithAuth("http://example.test/secure/");
        HttpGet request = new HttpGet(new URI("http://example.test/public/resource"));

        sampler.setupPreemptiveBasicAuth(new URI("http://example.test/public/resource").toURL(), request);

        assertFalse(request.containsHeader(HTTPConstants.HEADER_AUTHORIZATION));
    }

    @Test
    public void preemptiveBasicAuthDoesNotOverrideExistingAuthorizationHeader() throws Exception {
        HTTPHC5Impl sampler = samplerWithAuth("http://example.test/secure/");
        HttpGet request = new HttpGet(new URI("http://example.test/secure/resource"));
        request.setHeader(HTTPConstants.HEADER_AUTHORIZATION, "Bearer token");

        sampler.setupPreemptiveBasicAuth(new URI("http://example.test/secure/resource").toURL(), request);

        assertEquals("Bearer token", request.getLastHeader(HTTPConstants.HEADER_AUTHORIZATION).getValue());
    }

    @Test
    public void authManagerCredentialsWithoutDomainUseUsernamePasswordCredentials() {
        Credentials credentials = HTTPHC5Impl.credentialsForAuthorization(authorizationWithDomain(""));

        assertTrue(credentials instanceof UsernamePasswordCredentials);
        UsernamePasswordCredentials userPassCredentials = (UsernamePasswordCredentials) credentials;
        assertEquals("user", userPassCredentials.getUserName());
        assertArrayEquals("pass".toCharArray(), userPassCredentials.getUserPassword());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void authManagerCredentialsWithDomainUseNtCredentials() {
        Credentials credentials = HTTPHC5Impl.credentialsForAuthorization(authorizationWithDomain("DOMAIN"));

        assertTrue(credentials instanceof NTCredentials);
        NTCredentials ntCredentials = (NTCredentials) credentials;
        assertEquals("user", ntCredentials.getUserName());
        assertArrayEquals("pass".toCharArray(), ntCredentials.getPassword());
        assertEquals("DOMAIN", ntCredentials.getDomain());
        assertEquals("DOMAIN", ntCredentials.getNetbiosDomain());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void requestConfigPrefersNtlmForChallengeAuthentication() throws Exception {
        HTTPHC5Impl sampler = new HTTPHC5Impl(new HTTPSamplerProxy());
        HttpGet request = new HttpGet(new URI("https://example.test/secure"));

        sampler.setupRequest(new URI("https://example.test/secure").toURL(), request, null);

        RequestConfig config = request.getConfig();
        List<String> expected = List.of(
                StandardAuthScheme.NTLM,
                StandardAuthScheme.SPNEGO,
                StandardAuthScheme.KERBEROS,
                StandardAuthScheme.DIGEST,
                StandardAuthScheme.BASIC);
        assertEquals(expected, config.getTargetPreferredAuthSchemes());
        assertEquals(expected, config.getProxyPreferredAuthSchemes());
    }

    @Test
    public void authManagerUrlsResolveJMeterVariables() {
        JMeterVariables variables = new JMeterVariables();
        variables.put("fedHost", "acc.fed.example.test");
        JMeterContextService.getContext().setVariables(variables);

        assertEquals("https://acc.fed.example.test/adfs/ls/",
                HTTPHC5Impl.resolveVariables("https://${fedHost}/adfs/ls/"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void authManagerCredentialsResolveJMeterVariables() {
        JMeterVariables variables = new JMeterVariables();
        variables.put("authUser", "resolved-user");
        variables.put("authPass", "resolved-pass");
        variables.put("authDomain", "RESOLVED");
        JMeterContextService.getContext().setVariables(variables);
        AuthManager authManager = new AuthManager();
        authManager.set(-1,
                "http://example.test/secure/",
                "${authUser}",
                "${authPass}",
                "${authDomain}",
                "",
                Mechanism.BASIC);

        Credentials credentials = HTTPHC5Impl.credentialsForAuthorization(authManager.get(0));

        assertTrue(credentials instanceof NTCredentials);
        NTCredentials ntCredentials = (NTCredentials) credentials;
        assertEquals("resolved-user", ntCredentials.getUserName());
        assertArrayEquals("resolved-pass".toCharArray(), ntCredentials.getPassword());
        assertEquals("RESOLVED", ntCredentials.getDomain());
    }

    private static HTTPHC5Impl samplerWithAuth(String authUrl) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        AuthManager authManager = new AuthManager();
        authManager.set(-1, authUrl, "user", "pass", "", "*", Mechanism.BASIC);
        sampler.setAuthManager(authManager);
        return new HTTPHC5Impl(sampler);
    }

    private static Authorization authorizationWithDomain(String domain) {
        AuthManager authManager = new AuthManager();
        authManager.set(-1, "http://example.test/secure/", "user", "pass", domain, "", Mechanism.BASIC);
        return authManager.get(0);
    }

    private static void restoreProperty(Object previous) {
        if (previous == null) {
            JMeterUtils.getJMeterProperties().remove(HTTP2_IO_THREAD_COUNT);
        } else {
            JMeterUtils.getJMeterProperties().put(HTTP2_IO_THREAD_COUNT, previous);
        }
    }
}
