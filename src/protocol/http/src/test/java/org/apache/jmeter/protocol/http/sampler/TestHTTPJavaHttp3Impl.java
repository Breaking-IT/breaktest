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
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests for HTTP/3 protocol selection, runtime capability detection, and the
 * {@link HTTPJavaHttp3Impl} sampler. Runtime-independent behavior is exercised via the
 * package-private {@link HTTPSamplerFactory#getImplementation(String, HTTPSamplerBase, boolean)}
 * overload so both the Java-26+ and pre-26 paths are covered on any test JVM.
 */
public class TestHTTPJavaHttp3Impl {

    @BeforeAll
    public static void setupJMeterProperties() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = Files.createTempFile("jmeter", ".properties");
            JMeterUtils.loadJMeterProperties(properties.toString());
            Files.deleteIfExists(properties);
        }
    }

    @Test
    public void http3AliasesAreNormalized() {
        assertEquals(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3, HTTPSamplerBase.normalizeHttpProtocol("h3"));
        assertEquals(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3, HTTPSamplerBase.normalizeHttpProtocol("HTTP/3.0"));
        assertEquals(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3, HTTPSamplerBase.normalizeHttpProtocol("HTTP 3.0"));
        assertEquals(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3, HTTPSamplerBase.normalizeHttpProtocol("HTTP/3"));

        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setHttpProtocol("h3");
        assertTrue(sampler.isHttp3Protocol());
        assertEquals(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3, sampler.getHttpProtocol());
        assertFalse(sampler.isHttp2Protocol());
        assertFalse(sampler.isHttp11Protocol());
    }

    @Test
    public void factorySelectsHttp3ImplementationOnSupportedRuntime() {
        HTTPSamplerProxy http3 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http3.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);

        assertEquals(HTTPJavaHttp3Impl.class,
                HTTPSamplerFactory.getImplementation(http3.getImplementation(), http3, true).getClass());
    }

    @Test
    public void factoryFallsBackToNegotiatingHttp2OnUnsupportedRuntime() {
        HTTPSamplerProxy http3 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http3.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);

        HTTPAbstractImpl fallback = HTTPSamplerFactory.getImplementation(http3.getImplementation(), http3, false);

        assertEquals(HTTPHC5H2Impl.class, fallback.getClass());
        assertEquals(HttpVersionPolicy.NEGOTIATE, ((HTTPHC5H2Impl) fallback).versionPolicy());
    }

    @Test
    public void fallbackWarningIsLoggedOnce() {
        Http3RuntimeSupport.resetFallbackWarningForTests();
        assertFalse(Http3RuntimeSupport.fallbackWarned());

        HTTPSamplerProxy http3 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http3.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);
        HTTPSamplerFactory.getImplementation(http3.getImplementation(), http3, false);
        assertTrue(Http3RuntimeSupport.fallbackWarned());

        // second dispatch keeps the flag set (the warning itself is guarded by compareAndSet)
        HTTPSamplerFactory.getImplementation(http3.getImplementation(), http3, false);
        assertTrue(Http3RuntimeSupport.fallbackWarned());
    }

    @Test
    public void factoryLeavesOtherProtocolsUntouchedRegardlessOfHttp3Support() {
        HTTPSamplerProxy http2 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2.getImplementation(), http2, false).getClass());
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2.getImplementation(), http2, true).getClass());

        HTTPSamplerProxy http1 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http1.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        assertEquals(HTTPHC5Impl.class,
                HTTPSamplerFactory.getImplementation(http1.getImplementation(), http1, true).getClass());
    }

    @Test
    public void preferForDefaultRoutesBlankProtocolToHttp3Client() {
        HTTPSamplerProxy blank = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);

        assertEquals(HTTPJavaHttp3Impl.class,
                HTTPSamplerFactory.getImplementation(blank.getImplementation(), blank, true, true).getClass());
        // without the opt-in, or without runtime support, blank stays on HttpClient5
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(blank.getImplementation(), blank, true, false).getClass());
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(blank.getImplementation(), blank, false, true).getClass());
    }

    @Test
    public void preferForDefaultLeavesExplicitProtocolsUntouched() {
        HTTPSamplerProxy http2 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http2.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(http2.getImplementation(), http2, true, true).getClass());

        HTTPSamplerProxy http1 = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        http1.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        assertEquals(HTTPHC5Impl.class,
                HTTPSamplerFactory.getImplementation(http1.getImplementation(), http1, true, true).getClass());
    }

    @Test
    public void preferForDefaultKeepsUnsupportedConfigurationsOnHttpClient5() {
        HTTPSamplerProxy multipart = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        multipart.setDoMultipart(true);
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(multipart.getImplementation(), multipart, true, true).getClass());

        HTTPSamplerProxy proxied = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        proxied.setProxyHost("proxy.example.test");
        proxied.setProxyPortInt("8080");
        assertEquals(HTTPHC5H2Impl.class,
                HTTPSamplerFactory.getImplementation(proxied.getImplementation(), proxied, true, true).getClass());
    }

    @Test
    public void destinationEndpointResolvesToAddressAndPort() throws Exception {
        String endpoint = HTTPJavaHttp3Impl.resolveDestinationEndpoint(
                new URI("https://localhost/").toURL());

        assertTrue(endpoint != null && endpoint.endsWith(":443"),
                () -> "Expected resolved ip:443 for localhost, got: " + endpoint);
        assertFalse(endpoint.startsWith("localhost"),
                () -> "Expected a resolved address, not the host name, got: " + endpoint);

        assertEquals("192.0.2.7:8443", HTTPJavaHttp3Impl.resolveDestinationEndpoint(
                new URI("https://192.0.2.7:8443/").toURL()));
    }

    @Test
    public void capabilityDetectionMatchesRuntimeFeatureVersion() {
        // HTTP/3 arrived in the JDK HttpClient with Java 26 (JEP 517); capability
        // detection must agree with the running JVM on any test toolchain.
        assertEquals(Runtime.version().feature() >= 26, Http3RuntimeSupport.isHttp3Supported());
    }

    @Test
    public void versionEnumIsFormattedForDisplay() {
        assertEquals("HTTP/1.1", HTTPJavaHttp3Impl.formatVersion(HttpClient.Version.HTTP_1_1));
        assertEquals("HTTP/2", HTTPJavaHttp3Impl.formatVersion(HttpClient.Version.HTTP_2));
    }

    @Test
    public void proxyConfigurationProducesClearErrorResult() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);
        sampler.setProxyHost("proxy.example.test");
        sampler.setProxyPortInt("8080");
        HTTPJavaHttp3Impl impl = new HTTPJavaHttp3Impl(sampler);

        URL url = new URI("https://example.test/").toURL();
        HTTPSampleResult result = impl.sample(url, HTTPConstants.GET, false, 0);

        assertFalse(result.isSuccessful());
        assertTrue(result.getResponseDataAsString().contains("does not support proxies"),
                () -> "Expected proxy error, got: " + result.getResponseDataAsString());
    }

    @Test
    public void multipartConfigurationProducesClearErrorResult() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);
        sampler.setDoMultipart(true);
        HTTPJavaHttp3Impl impl = new HTTPJavaHttp3Impl(sampler);

        URL url = new URI("https://example.test/upload").toURL();
        HTTPSampleResult result = impl.sample(url, HTTPConstants.POST, false, 0);

        assertFalse(result.isSuccessful());
        assertTrue(result.getResponseDataAsString().contains("does not support multipart"),
                () -> "Expected multipart error, got: " + result.getResponseDataAsString());
    }

    @Test
    public void jdkConnectTimeoutIsClassifiedAsConnectTimeout() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setConnectTimeout("3000");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(new URI("https://example.test/").toURL());

        sampler.errorResult(new java.net.http.HttpConnectTimeoutException("quic connect timeout"), result);

        assertEquals("Connect timeout", result.getResponseCode());
        assertEquals("Connection timeout after 3000 ms for example.test:443", result.getResponseMessage());
        assertFalse(result.getResponseDataAsString().contains("\tat "));
    }

    @Test
    public void http3ConnectTimeoutIdentifiesQuicTransport() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setConnectTimeout("3000");
        HTTPJavaHttp3Impl impl = new HTTPJavaHttp3Impl(sampler);
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(new URI("https://example.test/").toURL());

        impl.errorResult(new java.net.http.HttpConnectTimeoutException("quic connect timeout"), result);

        assertEquals("Connect timeout", result.getResponseCode());
        assertEquals("HTTP/3 over QUIC: Connection timeout after 3000 ms for example.test:443",
                result.getResponseMessage());
        assertEquals(result.getResponseMessage(), result.getResponseDataAsString());
    }

    @Test
    public void http3UnclassifiedErrorIdentifiesQuicTransport() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPJavaHttp3Impl impl = new HTTPJavaHttp3Impl(sampler);
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(new URI("https://example.test/").toURL());

        impl.errorResult(new IllegalStateException("unexpected failure"), result);

        assertTrue(result.getResponseMessage().startsWith("HTTP/3 over QUIC: "));
        assertTrue(result.getResponseDataAsString().startsWith("HTTP/3 over QUIC: "));
    }

    @Test
    public void jdkConnectTimeoutBoundedByResponseTimeoutReportsThatTimeout() throws Exception {
        // With no connect timeout configured, the JDK request timeout bounds the QUIC
        // handshake; the reported duration falls back to the response timeout
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setResponseTimeout("5000");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(new URI("https://example.test/").toURL());

        // The JDK chains ConnectException below HttpConnectTimeoutException; the timeout
        // classification must win over the generic connection-failure classification
        java.net.http.HttpConnectTimeoutException timeout =
                new java.net.http.HttpConnectTimeoutException("HTTP connect timed out");
        timeout.initCause(new java.net.ConnectException("HTTP connect timed out"));
        sampler.errorResult(timeout, result);

        assertEquals("Connect timeout", result.getResponseCode());
        assertEquals("Connection timeout after 5000 ms for example.test:443", result.getResponseMessage());
    }

    @Test
    public void jdkRequestTimeoutIsClassifiedAsResponseTimeout() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setResponseTimeout("15000");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(new URI("https://example.test/").toURL());

        sampler.errorResult(new java.net.http.HttpTimeoutException("request timed out"), result);

        assertEquals("Response timeout", result.getResponseCode());
        assertEquals("Response timeout after 15000 ms waiting for response: https://example.test:443",
                result.getResponseMessage());
    }

    @Test
    public void jdkUnresolvedAddressIsClassifiedAsUnknownHost() throws Exception {
        // The JDK client reports resolution failures as a message-less ConnectException
        // caused by UnresolvedAddressException
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(new URI("https://no-such-host.invalid/").toURL());

        java.net.ConnectException resolutionFailure = new java.net.ConnectException();
        resolutionFailure.initCause(new java.nio.channels.UnresolvedAddressException());
        sampler.errorResult(resolutionFailure, result);

        assertEquals("Unknown host", result.getResponseCode());
        assertFalse(result.getResponseDataAsString().contains("\tat "));
    }

    @Test
    public void jdkTimeoutsAreRecognizedAsExpectedTimeouts() {
        assertTrue(HTTPSamplerBase.isExpectedTimeout(
                new java.net.http.HttpConnectTimeoutException("quic connect timeout")));
        assertTrue(HTTPSamplerBase.isExpectedTimeout(
                new java.net.http.HttpTimeoutException("request timed out")));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIf(
            "org.apache.jmeter.protocol.http.sampler.Http3RuntimeSupport#isHttp3Supported")
    public void closedUdpPortProducesConnectTimeoutResult() throws Exception {
        // QUIC/UDP has no connection-refused signal: a closed port behaves like a black
        // hole and must surface as a connect timeout after the configured timeout
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);
        sampler.setConnectTimeout("1000");
        HTTPJavaHttp3Impl impl = new HTTPJavaHttp3Impl(sampler);

        URL url = new URI("https://127.0.0.1:4433/").toURL();
        try {
            long start = System.nanoTime();
            HTTPSampleResult result = impl.sample(url, HTTPConstants.GET, false, 0);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertFalse(result.isSuccessful());
            assertEquals("Connect timeout", result.getResponseCode(),
                    () -> "Expected connect timeout classification, got: " + result.getResponseCode()
                            + " / " + result.getResponseMessage());
            assertTrue(elapsedMs < 10_000,
                    () -> "Connect timeout should fire near the configured 1000 ms, took " + elapsedMs + " ms");
            assertEquals("127.0.0.1:4433", result.getDestinationEndpoint(),
                    "Failures should record the resolved destination endpoint");
            assertTrue(result.getResponseMessage().contains("127.0.0.1:4433"),
                    () -> "Timeout message should name the destination, got: " + result.getResponseMessage());
        } finally {
            impl.threadFinished();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_LIVE", matches = "true")
    public void preferredModeUpgradesToHttp3AfterAltSvcDiscovery() throws Exception {
        // Browser-like behavior: a fresh client's first request runs over TCP (HTTP/2 or
        // HTTP/1.1), the server advertises HTTP/3 via Alt-Svc, and subsequent requests
        // to the same origin upgrade to HTTP/3.
        assertTrue(Http3RuntimeSupport.isHttp3Supported(),
                "HTTP/3 live test requires a Java 26+ runtime");

        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
        sampler.setDomain("cloudflare-quic.com");
        sampler.setPath("/");
        sampler.setMethod(HTTPConstants.GET);
        HTTPJavaHttp3Impl impl =
                new HTTPJavaHttp3Impl(sampler, HTTPJavaHttp3Impl.Http3Discovery.ALT_SVC_UPGRADE);

        URL url = new URI("https://cloudflare-quic.com/").toURL();
        try {
            // Drop any client cached by other tests on this thread: a reused client may
            // already have learned Alt-Svc (or hold an open QUIC connection) for the origin
            impl.threadFinished();
            HTTPSampleResult first = impl.sample(url, HTTPConstants.GET, false, 0);
            HTTPSampleResult second = impl.sample(url, HTTPConstants.GET, false, 0);

            assertTrue(first.isSuccessful(), first.getResponseMessage());
            assertTrue(second.isSuccessful(), second.getResponseMessage());
            assertTrue(first.getProtocolVersion().startsWith("HTTP/2")
                            || first.getProtocolVersion().startsWith("HTTP/1"),
                    () -> "Expected first request over TCP, got: " + first.getProtocolVersion());
            assertEquals("HTTP/3", second.getProtocolVersion(),
                    () -> "Expected Alt-Svc upgrade to HTTP/3, got: " + second.getProtocolVersion());
        } finally {
            impl.threadFinished();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_LIVE", matches = "true")
    public void http3SamplesLiveEndpointOnSupportedRuntime() {
        // Requires a Java 26+ test JVM (-PjdkTestVersion=26) and network access to an
        // HTTP/3-capable endpoint; opt in with BREAKTEST_HTTP3_LIVE=true.
        assertTrue(Http3RuntimeSupport.isHttp3Supported(),
                "HTTP/3 live test requires a Java 26+ runtime");

        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
        sampler.setDomain("cloudflare-quic.com");
        sampler.setPath("/");
        sampler.setMethod(HTTPConstants.GET);
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3);

        org.apache.jmeter.samplers.SampleResult result = sampler.sample();

        assertTrue(result.isSuccessful(), result.getResponseMessage());
        assertTrue(result.getResponseHeaders().startsWith("HTTP/3"),
                () -> "Expected HTTP/3 response, got: " + result.getResponseHeaders());
        assertEquals("TLSv1.3", result.getTlsVersion(),
                "HTTP/3 responses should report the negotiated TLS version");
        assertTrue(result.getDestinationEndpoint().endsWith(":443"),
                () -> "Expected resolved destination ip:443, got: " + result.getDestinationEndpoint());
    }
}
