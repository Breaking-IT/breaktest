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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.apache.jmeter.protocol.http.util.ConversionUtils.toUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.ResponseProcessingMode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.wiremock.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Body-discarding processing modes must still parse the page for embedded resources, then
 * discard the page body as configured.
 */
class HTTPSamplerEmbeddedResourcesProcessingModeTest extends JMeterTestCase {

    private static final String PAGE =
            "<html><head><title>page</title></head><body><img src=\"/image.png\"></body></html>";

    private WireMockServer server;

    @BeforeEach
    void startServer() throws IOException {
        JMeterContextService.setValidationRun(false);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        server = new WireMockServer(WireMockExtension.loopbackConfig());
        server.start();
        server.stubFor(get(urlPathEqualTo("/page.html")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody(PAGE)));
        server.stubFor(get(urlPathEqualTo("/page.html.gz")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withHeader("Content-Encoding", "gzip")
                .withBody(compressedPage())));
        server.stubFor(get(urlPathEqualTo("/image.png")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "image/png")
                .withBody(new byte[]{1, 2, 3, 4})));
    }

    @AfterEach
    void stopServer() {
        server.stop();
        JMeterContextService.getContext().setVariables(new JMeterVariables());
    }

    static List<Arguments> params() {
        List<Arguments> res = new ArrayList<>();
        for (String httpImpl : HTTPSamplerFactory.getImplementations()) {
            for (ResponseProcessingMode mode : Arrays.asList(
                    ResponseProcessingMode.STORE_ON_ERROR, ResponseProcessingMode.FETCH_AND_DISCARD)) {
                res.add(Arguments.of(httpImpl, mode, "/page.html"));
                res.add(Arguments.of(httpImpl, mode, "/page.html.gz"));
            }
        }
        return res;
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("params")
    void embeddedResourcesAreRetrieved(String httpImpl, ResponseProcessingMode mode, String path)
            throws MalformedURLException {
        HTTPSamplerBase http = HTTPSamplerFactory.newInstance(httpImpl);
        http.setResponseProcessingMode(mode);
        http.setImageParser(true);

        HTTPSampleResult res = http.sample(toUrl(server.url(path)), "GET", false, 0);

        SampleResult[] subResults = res.getSubResults();
        String urls = Arrays.stream(subResults)
                .map(r -> String.valueOf(r.getURL()))
                .collect(Collectors.joining(", "));
        assertTrue(Arrays.stream(subResults).anyMatch(r -> r.getURL().getPath().equals("/image.png")),
                () -> "embedded resource sub-result expected, got [" + urls + "] for " + res.getResponseCode());
        server.verify(1, getRequestedFor(urlPathEqualTo("/image.png")));
        for (SampleResult subResult : subResults) {
            assertEquals(0, subResult.getResponseData().length,
                    () -> "successful body must be discarded after parsing: " + subResult.getURL());
        }
        assertEquals(0, res.getResponseData().length, "container must not keep the page body");
    }

    @Test
    void ordinaryStoredResponseStaysLazy() throws IOException {
        AtomicInteger bodyReads = new AtomicInteger();
        HTTPSampleResult result = new HTTPSampleResult() {
            @Override
            public byte[] getResponseData() {
                bodyReads.incrementAndGet();
                return super.getResponseData();
            }
        };
        result.setResponseData(compressedPage(), "gzip");
        result.setResponseCode("200");
        result.setSuccessful(true);
        result.setDataType(SampleResult.TEXT);
        HTTPSamplerBase sampler = new HTTPSamplerProxy();
        sampler.setImageParser(false);
        sampler.setResponseProcessingMode(ResponseProcessingMode.STORE_COMPRESSED);
        sampler.resultProcessing(false, 0, result);
        assertEquals(0, bodyReads.get(), "Without resource parsing, stored bodies should remain lazy");
    }

    private static byte[] compressedPage() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(PAGE.getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }
}
