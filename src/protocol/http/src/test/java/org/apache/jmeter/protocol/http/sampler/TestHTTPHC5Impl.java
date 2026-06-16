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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

public class TestHTTPHC5Impl {
    @Test
    public void factorySelectsHttp2ImplementationForHttpClient5Only() {
        HTTPSamplerProxy http2 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2.getImplementation(), http2).getClass());

        HTTPSamplerProxy http2Preferred = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2Preferred.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2_PREFERRED);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2Preferred.getImplementation(), http2Preferred).getClass());

        HTTPSamplerProxy http1 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http1.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        assertEquals(HTTPHC5Impl.class,
                HTTPSamplerFactory.getImplementation(http1.getImplementation(), http1).getClass());

        HTTPSamplerProxy hc4 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT4);
        hc4.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2_PREFERRED);
        assertEquals(HTTPHC4Impl.class,
                HTTPSamplerFactory.getImplementation(hc4.getImplementation(), hc4).getClass());

        HTTPSamplerProxy java = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_JAVA);
        java.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2_PREFERRED);
        assertEquals(HTTPJavaImpl.class,
                HTTPSamplerFactory.getImplementation(java.getImplementation(), java).getClass());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP2_LIVE", matches = "true")
    public void http2PreferredNegotiatesHttp2AgainstBreaktestApp() {
        SampleResult result = sampleBreaktestApp(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2_PREFERRED);

        assertTrue(result.isSuccessful(), result.getResponseMessage());
        assertTrue(result.getResponseHeaders().startsWith("HTTP/2"),
                () -> "Expected HTTP/2 response, got: " + result.getResponseHeaders());
        assertTrue(result.getConnectTime() > 0,
                () -> "Expected first HTTP/2 preferred request to report connect time, got: "
                        + result.getConnectTime());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP2_LIVE", matches = "true")
    public void strictHttp2ReportsConnectTimeAgainstBreaktestApp() {
        SampleResult result = sampleBreaktestApp(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);

        assertTrue(result.isSuccessful(), result.getResponseMessage());
        assertTrue(result.getResponseHeaders().startsWith("HTTP/2"),
                () -> "Expected HTTP/2 response, got: " + result.getResponseHeaders());
        assertTrue(result.getConnectTime() > 0,
                () -> "Expected first strict HTTP/2 request to report connect time, got: "
                        + result.getConnectTime());
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

    private static HTTPHC5Impl samplerWithAuth(String authUrl) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        AuthManager authManager = new AuthManager();
        authManager.set(-1, authUrl, "user", "pass", "", "*", Mechanism.BASIC);
        sampler.setAuthManager(authManager);
        return new HTTPHC5Impl(sampler);
    }
}
