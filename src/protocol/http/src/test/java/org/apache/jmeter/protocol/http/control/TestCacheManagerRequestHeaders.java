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

package org.apache.jmeter.protocol.http.control;

import static org.apache.jmeter.protocol.http.util.ConversionUtils.toUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URL;
import java.net.URLConnection;
import java.util.stream.Stream;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TestCacheManagerRequestHeaders extends JMeterTestCase {
    private static final String ETAG = "\"cached\"";
    private static final String LAST_MODIFIED = "Wed, 01 Jan 2025 00:00:00 GMT";

    static Stream<Arguments> responses() {
        return Stream.of(false, true).flatMap(httpClient ->
                Stream.of("200", "304").flatMap(code ->
                        Stream.of(null, "", " ", "*", "Accept-Encoding", "Missing",
                                "Accept-Encoding, Accept-Language", "Accept-Encoding, Accept-Encoding")
                                .map(vary -> Arguments.of(httpClient, code, vary))));
    }

    @ParameterizedTest
    @MethodSource("responses")
    void readsRequestHeadersOnlyForVaryAndPreservesConditionalRequests(
            boolean httpClient, String code, String vary) throws Exception {
        CacheManager cache = new CacheManager();
        cache.testIterationStart(null);
        URL url = toUrl("http://localhost/cache");
        CountingResult result = new CountingResult();
        result.setURL(url);
        result.setHTTPMethod(HTTPConstants.GET);
        result.setResponseCode(code);
        result.setRequestHeaders("Accept-Encoding: gzip\nAccept-Encoding: br\nAccept-Language: en\n");
        BasicHttpResponse response = new BasicHttpResponse(Integer.parseInt(code));
        response.setHeader(HTTPConstants.ETAG, ETAG);
        response.setHeader(HTTPConstants.LAST_MODIFIED, LAST_MODIFIED);
        if (vary != null) {
            response.setHeader(HTTPConstants.VARY, vary);
        }
        if (httpClient) {
            cache.saveDetails(response, result);
        } else {
            cache.saveDetails(new URLConnection(url) {
                @Override
                public void connect() {
                }

                @Override
                public String getHeaderField(String name) {
                    Header header = response.getLastHeader(name);
                    return header == null ? null : header.getValue();
                }
            }, result);
        }
        assertEquals(vary == null || "*".equals(vary) ? 0 : 1, result.requestHeaderReads);

        HttpGet matching = new HttpGet(url.toURI());
        matching.addHeader("Accept-Encoding", "gzip");
        matching.addHeader("Accept-Encoding", "br");
        matching.addHeader("Accept-Language", "en");
        cache.setHeaders(url, matching);
        assertConditionalHeaders(matching, !"*".equals(vary));

        HttpGet different = new HttpGet(url.toURI());
        different.addHeader("Accept-Encoding", "deflate");
        different.addHeader("Accept-Language", "nl");
        different.addHeader("Missing", "now present");
        cache.setHeaders(url, different);
        assertConditionalHeaders(different, vary == null || vary.isBlank());
    }

    private static void assertConditionalHeaders(HttpGet request, boolean cached) {
        if (cached) {
            assertEquals(ETAG, request.getLastHeader(HTTPConstants.IF_NONE_MATCH).getValue());
            assertEquals(LAST_MODIFIED, request.getLastHeader(HTTPConstants.IF_MODIFIED_SINCE).getValue());
        } else {
            assertNull(request.getLastHeader(HTTPConstants.IF_NONE_MATCH));
            assertNull(request.getLastHeader(HTTPConstants.IF_MODIFIED_SINCE));
        }
    }

    private static class CountingResult extends HTTPSampleResult {
        private int requestHeaderReads;

        @Override
        public String getRequestHeaders() {
            requestHeaderReads++;
            return super.getRequestHeaders();
        }
    }
}
