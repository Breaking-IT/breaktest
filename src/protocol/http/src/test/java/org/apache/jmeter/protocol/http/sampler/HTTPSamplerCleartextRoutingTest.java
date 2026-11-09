/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.wiremock.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Cleartext requests that leave the HTTP version to negotiation can only ever speak HTTP/1.1,
 * so they go through the classic {@link HTTPHC5Impl} instead of the per-user async client.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HTTPSamplerCleartextRoutingTest {

    private static final URL HTTP_URL = url("http://localhost/path");
    private static final URL HTTPS_URL = url("https://localhost/path");

    @BeforeAll
    static void setupJMeterProperties() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = Files.createTempFile("jmeter", ".properties");
            JMeterUtils.loadJMeterProperties(properties.toString());
            Files.deleteIfExists(properties);
        }
    }

    @AfterEach
    void cleanUp() {
        JMeterContextService.getContext().clear();
        HTTPSamplerFactory.forgetNegotiatedProtocols();
        JMeterUtils.getJMeterProperties().remove(HTTPSamplerFactory.TLS_HTTP11_IMPL);
    }

    private static URL url(String spec) {
        try {
            return URI.create(spec).toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException(spec, e);
        }
    }

    private static HTTPSamplerProxy sampler(String httpProtocol) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setHttpProtocol(httpProtocol);
        return sampler;
    }

    private static boolean routesToHttp11(HTTPSamplerProxy sampler, URL url) {
        return HTTPSamplerFactory.usesHttp11ForCleartext(sampler.getImplementation(), sampler, url, true, false);
    }

    @Test
    void negotiatedCleartextRequestUsesHttp11Implementation() {
        assertTrue(routesToHttp11(sampler(""), HTTP_URL));
    }

    @Test
    void negotiatedTlsRequestKeepsNegotiatingImplementation() {
        // A redirect or embedded resource can be https on an http sampler: decide per URL.
        assertFalse(routesToHttp11(sampler(""), HTTPS_URL));
    }

    @Test
    void explicitHttpVersionKeepsItsImplementation() {
        assertFalse(routesToHttp11(sampler(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2), HTTP_URL));
        assertFalse(routesToHttp11(sampler(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_3), HTTP_URL));
    }

    @Test
    void serverSentEventsFollowOrdinaryRequestsSoTheyCanShareTheirConnection() {
        HTTPSamplerProxy sse = sampler("");
        sse.setSseEnabled(true);
        assertTrue(routesToHttp11(sse, HTTP_URL));
    }

    @Test
    void canBeDisabledAndDefersToHttp3Preference() {
        HTTPSamplerProxy negotiated = sampler("");
        assertFalse(HTTPSamplerFactory.usesHttp11ForCleartext(
                negotiated.getImplementation(), negotiated, HTTP_URL, false, false));
        assertFalse(HTTPSamplerFactory.usesHttp11ForCleartext(
                negotiated.getImplementation(), negotiated, HTTP_URL, true, true));
    }

    @Test
    void unknownImplementationIsLeftToTheFactoryToReject() {
        assertFalse(HTTPSamplerFactory.usesHttp11ForCleartext("NoSuchClient", sampler(""), HTTP_URL, true, false));
    }

    @Test
    void tlsHostUsesHttp11ImplementationOnlyAfterNegotiatingHttp11() {
        HTTPSamplerProxy negotiated = sampler("");
        String impl = negotiated.getImplementation();
        assertFalse(HTTPSamplerFactory.usesHttp11Impl(impl, negotiated, HTTPS_URL),
                "the first TLS request must negotiate");

        HTTPSamplerFactory.recordNegotiatedProtocol(impl, negotiated, HTTPS_URL, "HTTP/1.1");

        assertTrue(HTTPSamplerFactory.usesHttp11Impl(impl, negotiated, HTTPS_URL));
        assertFalse(HTTPSamplerFactory.usesHttp11Impl(impl, negotiated, url("https://other.example/path")),
                "what one host negotiated says nothing about another");
        assertFalse(HTTPSamplerFactory.usesHttp11Impl(impl, sampler(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2), HTTPS_URL),
                "an explicit HTTP/2 sampler keeps its implementation");
    }

    @Test
    void tlsHostThatNegotiatedHttp2KeepsNegotiatingImplementation() {
        HTTPSamplerProxy negotiated = sampler("");
        String impl = negotiated.getImplementation();

        HTTPSamplerFactory.recordNegotiatedProtocol(impl, negotiated, HTTPS_URL, "HTTP/2");
        HTTPSamplerFactory.recordNegotiatedProtocol(impl, negotiated, HTTPS_URL, "");

        assertFalse(HTTPSamplerFactory.usesHttp11Impl(impl, negotiated, HTTPS_URL));
    }

    @Test
    void tlsRoutingAfterNegotiationCanBeDisabled() {
        JMeterUtils.setProperty(HTTPSamplerFactory.TLS_HTTP11_IMPL, "false");
        HTTPSamplerProxy negotiated = sampler("");
        String impl = negotiated.getImplementation();

        HTTPSamplerFactory.recordNegotiatedProtocol(impl, negotiated, HTTPS_URL, "HTTP/1.1");

        assertFalse(HTTPSamplerFactory.usesHttp11Impl(impl, negotiated, HTTPS_URL));
    }

    @Test
    void negotiatedCleartextSampleDoesNotStartAsyncClient() throws Exception {
        WireMockServer server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        HTTPSamplerProxy sampler = sampler("");
        try {
            server.stubFor(WireMock.get(WireMock.urlEqualTo("/resource"))
                    .willReturn(WireMock.aResponse().withStatus(200).withBody("ok")));
            sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
            sampler.setDomain("localhost");
            sampler.setPort(server.port());
            sampler.setPath("/resource");
            sampler.setMethod(HTTPConstants.GET);
            int asyncClients = asyncClientCache().size();

            HTTPSampleResult result = (HTTPSampleResult) sampler.sample();

            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("ok", result.getResponseDataAsString());
            assertEquals(asyncClients, asyncClientCache().size(),
                    "a negotiated cleartext request must not create a per-user async client");
        } finally {
            sampler.threadFinished();
            server.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asyncClientCache() throws Exception {
        Field cache = HTTPHC5H2Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_JMETER_THREAD");
        cache.setAccessible(true);
        return (Map<Object, Object>) cache.get(null);
    }
}
