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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.message.BufferedHeader;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.proxy.Proxy;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

class DeferredHttpHeadersTest extends JMeterTestCase {
    private HTTPSampleResult capture(boolean deferred) throws Exception {
        BasicHttpRequest request = new BasicHttpRequest("GET", "/");
        request.addHeader("X-Request", "abc");
        request.addHeader("Cookie", "private=1");
        BasicHttpResponse response = new BasicHttpResponse(200, "OK");
        response.setVersion(HttpVersion.HTTP_1_1);
        response.addHeader("X-Token", "abc");
        response.addHeader("X-Token", "def");
        response.addHeader(new BasicHeader("X-Null", null));
        CharArrayBuffer buffer = new CharArrayBuffer(32);
        buffer.append("x-Spaces:   raw value  ");
        response.addHeader(BufferedHeader.create(buffer));
        HTTPSampleResult result = new HTTPSampleResult();
        result.setSuccessful(true);
        HTTPHC5Impl.captureRequestHeaders(result, request, deferred);
        HTTPHC5Impl.captureResponseHeaders(result, response, deferred);
        return result;
    }

    @Test
    void http2StatusLineAndAccountingMatchWithoutReasonPhrase() {
        BasicHttpResponse response = new BasicHttpResponse(200, "OK");
        response.setVersion(HttpVersion.HTTP_2);
        response.addHeader("content-type", "application/json");
        HTTPSampleResult eager = new HTTPSampleResult();
        HTTPSampleResult lazy = new HTTPSampleResult();
        HTTPHC5H2Impl.captureResponseHeaders(eager, response, false);
        HTTPHC5H2Impl.captureResponseHeaders(lazy, response, true);
        assertEquals("HTTP/2 200\ncontent-type: application/json\n", lazy.getResponseHeaders());
        assertEquals(eager.getResponseHeaders(), lazy.getResponseHeaders());
        assertEquals(eager.getHeadersSize(), lazy.getHeadersSize());
    }

    @Test
    void textAndByteAccountingMatchEagerPath() throws Exception {
        HTTPSampleResult eager = capture(false);
        HTTPSampleResult lazy = capture(true);
        assertEquals(eager.getHeadersSize(), lazy.getHeadersSize());
        assertEquals(eager.getRequestHeaders(), lazy.getRequestHeaders());
        assertEquals(eager.getResponseHeaders(), lazy.getResponseHeaders());
        assertFalse(lazy.getRequestHeaders().contains("Cookie"));
        assertTrue(lazy.getResponseHeaders().contains("x-Spaces:   raw value  \n"));
        assertSame(lazy.getResponseHeaders(), lazy.getResponseHeaders());
    }

    @Test
    void snapshotSurvivesParserBufferMutationAndAccountsWithoutFormatting() throws Exception {
        CharArrayBuffer buffer = new CharArrayBuffer(32);
        buffer.append("X-Raw: original");
        DeferredHttpHeaders headers = new DeferredHttpHeaders("", new Header[] {
                BufferedHeader.create(buffer)}, name -> true);
        buffer.clear();
        buffer.append("X-Raw: changed");
        assertEquals("X-Raw: original\n".length(), headers.length());
        assertFalse(headers.isMaterialized());
        assertEquals("X-Raw: original\n", headers.text());
    }

    @Test
    void settersOverrideSnapshotsAndCopiesKeepHeaders() throws Exception {
        HTTPSampleResult lazy = capture(true);
        assertEquals(capture(false).getResponseHeaders(), new SampleResult(lazy).getResponseHeaders());
        assertEquals(capture(false).getRequestHeaders(), new HTTPSampleResult(lazy).getRequestHeaders());
        lazy.setRequestHeaders("replacement request");
        lazy.setResponseHeaders("replacement response");
        assertEquals("replacement request", lazy.getRequestHeaders());
        assertEquals("replacement response", lazy.getResponseHeaders());
    }

    @Test
    void cloneKeepsSnapshotWhenOriginalIsOverwritten() throws Exception {
        HTTPSampleResult original = capture(true);
        HTTPSampleResult clone = (HTTPSampleResult) original.clone();
        original.setRequestHeaders(null);
        original.setResponseHeaders("replaced");
        assertEquals(capture(false).getRequestHeaders(), clone.getRequestHeaders());
        assertEquals(capture(false).getResponseHeaders(), clone.getResponseHeaders());
        clone.setResponseHeaders("clone replacement");
        assertEquals("replaced", original.getResponseHeaders());
    }

    @Test
    void messageReplacementDoesNotChangeSnapshot() {
        BasicHttpRequest request = new BasicHttpRequest("GET", "/");
        request.addHeader("X-Request", "original");
        HTTPSampleResult result = new HTTPSampleResult();
        HTTPHC5Impl.captureRequestHeaders(result, request, true);
        request.setHeader("X-Request", "changed");
        assertEquals("X-Request: original\n", result.getRequestHeaders());
        assertTrue(result.toDebugString().contains("X-Request: original"));
        HTTPHC5Impl.captureRequestHeaders(result, null, true);
        assertEquals("", result.getRequestHeaders());
    }

    @Test
    void javaSerializationMaterializesUnreadHeaders() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(capture(true));
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            HTTPSampleResult restored = (HTTPSampleResult) in.readObject();
            assertEquals(capture(false).getRequestHeaders(), restored.getRequestHeaders());
            assertEquals(capture(false).getResponseHeaders(), restored.getResponseHeaders());
        }
    }

    @Test
    void xmlSavingMaterializesUnreadHeaders() throws Exception {
        HTTPSampleResult lazy = capture(true);
        lazy.setSaveConfig(new SampleSaveConfiguration(true));
        StringWriter xml = new StringWriter();
        SaveService.saveSampleResult(new SampleEvent(lazy, "group"), xml);
        assertTrue(xml.toString().contains("X-Token: abc"));
        assertTrue(xml.toString().contains("X-Request: abc"));
    }

    @Test
    void recordingResponsePreservesHeadersAndDecodedBodyLength() throws Exception {
        BasicHttpResponse response = new BasicHttpResponse(200, "OK");
        response.setVersion(HttpVersion.HTTP_1_1);
        response.addHeader("Content-Encoding", "gzip");
        response.addHeader("Content-Length", "99");
        response.addHeader("Transfer-Encoding", "chunked");
        response.addHeader("Set-Cookie", "synthetic=abc; Path=/");
        var rewrite = Proxy.class.getDeclaredMethod("messageResponseHeaders", SampleResult.class);
        rewrite.setAccessible(true);
        HTTPSampleResult eager = new HTTPSampleResult();
        HTTPSampleResult lazy = new HTTPSampleResult();
        eager.setResponseData(new byte[4]);
        lazy.setResponseData(new byte[4]);
        HTTPHC5Impl.captureResponseHeaders(eager, response, false);
        HTTPHC5Impl.captureResponseHeaders(lazy, response, true);
        String recorded = (String) rewrite.invoke(null, lazy);
        assertEquals(rewrite.invoke(null, eager), recorded);
        assertTrue(recorded.contains("Content-Length: 4\r\n"));
        assertTrue(recorded.contains("Set-Cookie: synthetic=abc; Path=/\r\n"));
        assertFalse(recorded.contains("Transfer-Encoding"));
        assertFalse(recorded.contains("Content-Encoding"));
    }

    @Test
    void accountingPreservesCharacterConventionAndLargeBodySizes() {
        for (var version : new HttpVersion[] {HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1, HttpVersion.HTTP_2}) {
            BasicHttpResponse response = new BasicHttpResponse(204, null);
            response.setVersion(version);
            response.addHeader("X-Unicode", "caf\u00e9-\u2603");
            HTTPSampleResult eager = new HTTPSampleResult();
            HTTPSampleResult lazy = new HTTPSampleResult();
            HTTPHC5H2Impl.captureResponseHeaders(eager, response, false);
            HTTPHC5H2Impl.captureResponseHeaders(lazy, response, true);
            eager.setBodySize(3_000_000_000L);
            lazy.setBodySize(3_000_000_000L);
            assertEquals(eager.getHeadersSize(), lazy.getHeadersSize());
            assertEquals(eager.getBodySizeAsLong(), lazy.getBodySizeAsLong());
            assertEquals(eager.getBytesAsLong(), lazy.getBytesAsLong());
            assertEquals(eager.getResponseHeaders(), lazy.getResponseHeaders());
        }
    }

    @Test
    void concurrentReadersShareOneFormattedString() throws Exception {
        DeferredHttpHeaders headers = new DeferredHttpHeaders("", new Header[] {
                new BasicHeader("X-Token", "abc")}, name -> true);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 100; i++) {
                tasks.add(headers::text);
            }
            for (var value : executor.invokeAll(tasks)) {
                assertSame(headers.text(), value.get());
            }
        }
    }

    @Test
    void headerExtractorAndAssertionWorkAfterSuccessfulHttpResponse() throws Exception {
        var context = JMeterContextService.getContext();
        var previousVariables = context.getVariables();
        var previousResult = context.getPreviousResult();
        context.setVariables(new JMeterVariables());
        context.setPreviousResult(capture(true));
        try {
            RegexExtractor extractor = new RegexExtractor();
            extractor.setUseField(RegexExtractor.USE_HDRS);
            extractor.setRefName("token");
            extractor.setRegex("X-Token: (abc)");
            extractor.setTemplate("$1$");
            extractor.setMatchNumber(1);
            extractor.process();
            assertEquals("abc", context.getVariables().get("token"));
            extractor.setUseField(RegexExtractor.USE_REQUEST_HDRS);
            extractor.setRegex("X-Request: (abc)");
            extractor.process();
            assertEquals("abc", context.getVariables().get("token"));
            ResponseAssertion requestAssertion = new ResponseAssertion();
            requestAssertion.setTestFieldRequestHeaders();
            requestAssertion.setToContainsType();
            requestAssertion.addTestString("X-Request: abc");
            assertFalse(requestAssertion.getResult(context.getPreviousResult()).isFailure());
            ResponseAssertion assertion = new ResponseAssertion();
            assertion.setTestFieldResponseHeaders();
            assertion.setToContainsType();
            assertion.addTestString("X-Token: missing");
            assertTrue(assertion.getResult(context.getPreviousResult()).isFailure());
            // Error diagnostics remain available after the assertion fails an HTTP-success sample.
            context.getPreviousResult().setSuccessful(false);
            assertTrue(context.getPreviousResult().getRequestHeaders().contains("X-Request: abc"));
        } finally {
            context.setVariables(previousVariables);
            context.setPreviousResult(previousResult);
        }
    }

    @Test
    void realHttpSamplerCapturesHeadersForSuccessAndHttpError() throws Exception {
        WireMockServer server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        try {
            for (int code : new int[] {200, 500, 302}) {
                server.stubFor(WireMock.get("/status" + code).willReturn(
                        WireMock.aResponse().withStatus(code).withHeader("X-Token", "abc")
                                .withHeader("Set-Cookie", "synthetic=abc; Path=/").withBody("body")));
                HTTPSamplerProxy config = new HTTPSamplerProxy();
                HeaderManager headers = new HeaderManager();
                headers.add(new org.apache.jmeter.protocol.http.control.Header("X-Request", "synthetic"));
                config.setHeaderManager(headers);
                CookieManager cookies = new CookieManager();
                cookies.testStarted();
                config.setCookieManager(cookies);
                HTTPHC5Impl sampler = new HTTPHC5Impl(config) {
                    @Override
                    protected boolean deferDiagnosticHeaders() {
                        return true;
                    }
                };
                try {
                    HTTPSampleResult result = sampler.sample(URI.create("http://localhost:" + server.port()
                            + "/status" + code).toURL(), "GET", false, 0);
                    if (code == 302) {
                        assertTrue(result.getResponseMessage().contains("Missing location header"));
                        assertEquals(0, result.getHeadersSize());
                    } else {
                        assertEquals(Integer.toString(code), result.getResponseCode());
                    }
                    assertEquals(code == 200, result.isSuccessful());
                    assertTrue(result.getResponseHeaders().contains("X-Token: abc"));
                    assertTrue(result.getRequestHeaders().contains("X-Request: synthetic"));
                    if (code != 302) {
                        assertEquals("synthetic=abc", cookies.getCookieHeaderForURL(result.getURL()));
                        assertTrue(result.getHeadersSize() > result.getResponseHeaders().length());
                    }
                } finally {
                    sampler.threadFinished();
                }
            }
        } finally {
            server.stop();
        }
    }
}
