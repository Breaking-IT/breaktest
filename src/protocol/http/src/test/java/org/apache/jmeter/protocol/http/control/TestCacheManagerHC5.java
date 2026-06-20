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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CacheManager} integration with Apache HttpClient 5 request types.
 */
public class TestCacheManagerHC5 extends JMeterTestCase {
    private static final String LOCAL_HOST = "http://localhost/";
    private static final String EXPECTED_ETAG = "0xCAFEBABEDEADBEEF";
    private static final ZoneId GMT = ZoneId.of("GMT");

    private CacheManager cacheManager;
    private URL url;
    private String currentTimeInGMT;

    @BeforeEach
    public void setUp() throws Exception {
        cacheManager = new CacheManager();
        cacheManager.setUseExpires(false);
        cacheManager.testIterationStart(null);
        url = toUrl(LOCAL_HOST);
        currentTimeInGMT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z")
                .withLocale(Locale.US)
                .withZone(GMT)
                .format(Instant.now());
    }

    @Test
    public void setHeadersAddsConditionalHeadersToHttpClient5Requests() throws Exception {
        cacheResult(sampleResult());

        HttpGet request = new HttpGet(url.toURI());
        cacheManager.setHeaders(url, request);

        assertEquals(currentTimeInGMT, request.getLastHeader(HTTPConstants.IF_MODIFIED_SINCE).getValue());
        assertEquals(EXPECTED_ETAG, request.getLastHeader(HTTPConstants.IF_NONE_MATCH).getValue());
    }

    @Test
    public void setHeadersMatchesHttpClient5VaryHeaders() throws Exception {
        cacheManager.setUseExpires(true);
        HTTPSampleResult result = sampleResult();
        result.setRequestHeaders("Accept-Encoding: gzip\n");
        cacheResult(result, "Accept-Encoding");

        HttpGet matchingRequest = new HttpGet(url.toURI());
        matchingRequest.addHeader("Accept-Encoding", "gzip");
        cacheManager.setHeaders(url, matchingRequest);

        assertEquals(currentTimeInGMT, matchingRequest.getLastHeader(HTTPConstants.IF_MODIFIED_SINCE).getValue());
        assertEquals(EXPECTED_ETAG, matchingRequest.getLastHeader(HTTPConstants.IF_NONE_MATCH).getValue());
    }

    @Test
    public void setHeadersDoesNotMatchDifferentHttpClient5VaryHeaders() throws Exception {
        cacheManager.setUseExpires(true);
        HTTPSampleResult result = sampleResult();
        result.setRequestHeaders("Accept-Encoding: gzip\n");
        cacheResult(result, "Accept-Encoding");

        HttpGet differentRequest = new HttpGet(url.toURI());
        differentRequest.addHeader("Accept-Encoding", "br");
        cacheManager.setHeaders(url, differentRequest);

        assertNull(differentRequest.getLastHeader(HTTPConstants.IF_MODIFIED_SINCE));
        assertNull(differentRequest.getLastHeader(HTTPConstants.IF_NONE_MATCH));
    }

    private HTTPSampleResult sampleResult() {
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(url);
        result.setHTTPMethod(HTTPConstants.GET);
        result.setResponseCode("200");
        return result;
    }

    private void cacheResult(HTTPSampleResult result) {
        cacheResult(result, null);
    }

    private void cacheResult(HTTPSampleResult result, String vary) {
        BasicHttpResponse response = new BasicHttpResponse(200, "OK");
        response.setVersion(HttpVersion.HTTP_1_1);
        response.setHeader(new BasicHeader(HTTPConstants.DATE, currentTimeInGMT));
        response.setHeader(new BasicHeader(HTTPConstants.LAST_MODIFIED, currentTimeInGMT));
        response.setHeader(new BasicHeader(HTTPConstants.ETAG, EXPECTED_ETAG));
        response.setHeader(new BasicHeader(HTTPConstants.EXPIRES, DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z")
                .withLocale(Locale.US)
                .withZone(GMT)
                .format(Instant.now().plusSeconds(60))));
        if (vary != null) {
            response.setHeader(new BasicHeader(HTTPConstants.VARY, vary));
        }
        cacheManager.saveDetails(response, result);
    }
}
