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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Cache;

/**
 * Test {@link CacheManager} thread iteration behavior.
 */
public class TestCacheManagerThreadIteration {
    private JMeterContext jmctx;
    private JMeterVariables jmvars;
    private static final String SAME_USER="__jmv_SAME_USER";
    protected static final String LOCAL_HOST = "http://localhost/";
    protected static final String EXPECTED_ETAG = "0xCAFEBABEDEADBEEF";
    protected static final ZoneId GMT = ZoneId.of("GMT");
    protected CacheManager cacheManager;
    protected String currentTimeInGMT;
    protected String vary = null;
    protected URL url;
    protected HTTPSampleResult sampleResultOK;
    private String lastModified;
    private String expires;
    private String cacheControl;

    protected String makeDate(Instant d) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z")
                .withLocale(Locale.US)
                .withZone(GMT);
        return formatter.format(d);
    }

    protected HTTPSampleResult getSampleResultWithSpecifiedResponseCode(String code) {
        HTTPSampleResult sampleResult = new HTTPSampleResult();
        sampleResult.setResponseCode(code);
        sampleResult.setHTTPMethod("GET");
        sampleResult.setURL(url);
        return sampleResult;
    }

    private HttpPost httpMethod;

    @BeforeEach
    public void setUp() throws Exception {
        this.cacheManager = new CacheManager();
        this.currentTimeInGMT = makeDate(Instant.now());
        this.url = toUrl(LOCAL_HOST);
        this.sampleResultOK = getSampleResultWithSpecifiedResponseCode("200");
        this.lastModified = currentTimeInGMT;
        this.httpMethod = new HttpPost(this.url.toURI());
        jmctx = JMeterContextService.getContext();
        jmvars = new JMeterVariables();
    }

    @AfterEach
    public void tearDown() throws Exception {
        this.url = null;
        this.httpMethod = null;
        this.cacheManager =  new CacheManager();
        this.currentTimeInGMT = null;
        this.lastModified = null;
        this.expires = null;
        this.cacheControl = null;
        this.sampleResultOK = null;
    }

    protected void setExpires(String expires) {
        this.expires = expires;
    }

    protected void setCacheControl(String cacheControl) {
        this.cacheControl = cacheControl;
    }

    protected void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    protected void cacheResult(HTTPSampleResult result) {
        BasicHttpResponse response = new BasicHttpResponse(200);
        response.setHeader(new BasicHeader(HTTPConstants.DATE, currentTimeInGMT));
        response.setHeader(new BasicHeader(HTTPConstants.LAST_MODIFIED, lastModified));
        response.setHeader(new BasicHeader(HTTPConstants.ETAG, EXPECTED_ETAG));
        if (expires != null) {
            response.setHeader(new BasicHeader(HTTPConstants.EXPIRES, expires));
        }
        if (cacheControl != null) {
            response.setHeader(new BasicHeader(HTTPConstants.CACHE_CONTROL, cacheControl));
        }
        if (vary != null) {
            response.setHeader(new BasicHeader(HTTPConstants.VARY, vary));
        }
        this.cacheManager.saveDetails(response, result);
    }

    protected void addRequestHeader(String requestHeader, String value) {
        this.httpMethod.addHeader(new BasicHeader(requestHeader, value));
    }

    protected void setRequestHeaders() {
        this.cacheManager.setHeaders(this.url, this.httpMethod);
    }

    private Cache<String, CacheManager.CacheEntry> getThreadCache() throws Exception {
        Field threadLocalfield = CacheManager.class.getDeclaredField("threadCache");
        threadLocalfield.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Cache<String, CacheManager.CacheEntry>> threadLocal = (ThreadLocal<Cache<String, CacheManager.CacheEntry>>) threadLocalfield
                .get(this.cacheManager);
        return threadLocal.get();
    }

    protected CacheManager.CacheEntry getThreadCacheEntry(String url) throws Exception {
        return getThreadCache().getIfPresent(url);
    }
    @Test
    public void testCacheControlCleared() throws Exception {
        this.cacheManager.setUseExpires(true);
        this.cacheManager.testIterationStart(null);
        assertNull(getThreadCacheEntry(LOCAL_HOST), "Should not find entry");
        Header[] headers = new Header[1];
        assertFalse(this.cacheManager.inCache(url, headers), "Should not find valid entry");
        long start = System.currentTimeMillis();
        setExpires(makeDate(Instant.ofEpochMilli(start)));
        setCacheControl("public, max-age=1");
        cacheResult(sampleResultOK);
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "Before iternation, should find entry");
        assertTrue(this.cacheManager.inCache(url, headers), "Before iternation, should find valid entry");
        this.cacheManager.setClearEachIteration(true);
        this.cacheManager.testIterationStart(null);
        assertNull(getThreadCacheEntry(LOCAL_HOST), "After iterantion, should not find entry");
        assertFalse(this.cacheManager.inCache(url, headers), "After iterantion, should not find valid entry");
    }

    @Test
    public void testCacheInitializedForChildSamplerThreads() throws Exception {
        jmctx.setVariables(jmvars);
        this.cacheManager.setUseExpires(true);
        this.cacheManager.testIterationStart(null);
        assertNull(getThreadCacheEntry(LOCAL_HOST), "Should not find entry");

        long start = System.currentTimeMillis();
        setExpires(makeDate(Instant.ofEpochMilli(start)));
        setCacheControl("public, max-age=1");

        AtomicReference<Throwable> childFailure = new AtomicReference<>();
        Thread child = new Thread(() -> {
            try {
                cacheResult(sampleResultOK);
            } catch (Throwable t) {
                childFailure.set(t);
            }
        });
        child.start();
        child.join();

        assertNull(childFailure.get(), "Child sampler thread should cache without failure");
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "Parent thread should see cache entry added by child sampler thread");
    }

    @Test
    public void testJmeterVariableCacheWhenThreadIterationIsANewUser() {
        jmvars.putObject(SAME_USER, true);
        jmctx.setVariables(jmvars);
        HTTPSamplerBase sampler = (HTTPSamplerBase) new HttpTestSampleGui().createTestElement();
        cacheManager.setControlledByThread(true);
        sampler.setCacheManager(cacheManager);
        sampler.setThreadContext(jmctx);
        boolean res = (boolean) cacheManager.getThreadContext().getVariables().getObject(SAME_USER);
        assertTrue(res, "When test different user on the different iternation, the cache should be cleared");
    }

    @Test
    public void testJmeterVariableWhenThreadIterationIsSameUser() {
        jmvars.putObject(SAME_USER, false);
        jmctx.setVariables(jmvars);
        HTTPSamplerBase sampler = (HTTPSamplerBase) new HttpTestSampleGui().createTestElement();
        cacheManager.setControlledByThread(true);
        sampler.setCacheManager(cacheManager);
        sampler.setThreadContext(jmctx);
        boolean res = (boolean) cacheManager.getThreadContext().getVariables().getObject(SAME_USER);
        assertFalse(res, "When test different user on the different iternation, the cache shouldn't be cleared");
    }

    @Test
    public void testCacheManagerWhenThreadIterationIsANewUser() throws Exception {
        //Controlled by ThreadGroup
        jmvars.putObject(SAME_USER, false);
        jmctx.setVariables(jmvars);
        this.cacheManager.setUseExpires(true);
        this.cacheManager.testIterationStart(null);
        assertNull(getThreadCacheEntry(LOCAL_HOST), "Should not find entry");
        Header[] headers = new Header[1];
        assertFalse(this.cacheManager.inCache(url, headers), "Should not find valid entry");
        long start = System.currentTimeMillis();
        setExpires(makeDate(Instant.ofEpochMilli(start)));
        setCacheControl("public, max-age=1");
        cacheResult(sampleResultOK);
        this.cacheManager.setThreadContext(jmctx);
        this.cacheManager.setControlledByThread(true);
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "Before iternation, should find entry");
        assertTrue(this.cacheManager.inCache(url, headers), "Before iternation, should find valid entry");
        this.cacheManager.testIterationStart(null);
        assertNull(getThreadCacheEntry(LOCAL_HOST), "After iterantion, should not find entry");
        assertFalse(this.cacheManager.inCache(url, headers), "After iterantion, should not find valid entry");

        //Controlled by cacheManager
        jmvars.putObject(SAME_USER, true);
        jmctx.setVariables(jmvars);
        this.cacheManager.setThreadContext(jmctx);
        start = System.currentTimeMillis();
        setExpires(makeDate(Instant.ofEpochMilli(start)));
        setCacheControl("public, max-age=1");
        cacheResult(sampleResultOK);
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "Before iternation, should find entry");
        assertTrue(this.cacheManager.inCache(url, headers), "Before iternation, should find valid entry");
        this.cacheManager.setControlledByThread(false);
        this.cacheManager.setClearEachIteration(true);
        this.cacheManager.testIterationStart(null);
        assertNull(getThreadCacheEntry(LOCAL_HOST), "After iterantion, should not find entry");
        assertFalse(this.cacheManager.inCache(url, headers), "After iterantion, should not find valid entry");
    }

    @Test
    public void testCacheManagerWhenThreadIterationIsSameUser() throws Exception {
        // Controlled by ThreadGroup
        jmvars.putObject(SAME_USER, true);
        jmctx.setVariables(jmvars);
        this.cacheManager.setUseExpires(true);
        this.cacheManager.testIterationStart(null);
        assertNull(getThreadCacheEntry(LOCAL_HOST), "Should not find entry");
        Header[] headers = new Header[1];
        assertFalse(this.cacheManager.inCache(url, headers), "Should not find valid entry");
        long start = System.currentTimeMillis();
        setExpires(makeDate(Instant.ofEpochMilli(start)));
        setCacheControl("public, max-age=1");
        cacheResult(sampleResultOK);
        this.cacheManager.setThreadContext(jmctx);
        this.cacheManager.setControlledByThread(true);
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "Before iteration, should find entry");
        assertTrue(this.cacheManager.inCache(url, headers), "Before iteration, should find valid entry");
        this.cacheManager.testIterationStart(null);
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "After iteration, should find entry");
        assertTrue(this.cacheManager.inCache(url, headers), "After iteration, should find valid entry");
        // Controlled by cacheManager
        jmvars.putObject(SAME_USER, false);
        jmctx.setVariables(jmvars);
        this.cacheManager.setThreadContext(jmctx);
        start = System.currentTimeMillis();
        setExpires(makeDate(Instant.ofEpochMilli(start)));
        setCacheControl("public, max-age=1");
        cacheResult(sampleResultOK);
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "Before iteration, should find entry");
        assertTrue(this.cacheManager.inCache(url, headers), "Before iteration, should find valid entry");
        this.cacheManager.setControlledByThread(false);
        this.cacheManager.setClearEachIteration(false);
        this.cacheManager.testIterationStart(null);
        assertNotNull(getThreadCacheEntry(LOCAL_HOST), "After iteration, should find entry");
        assertTrue(this.cacheManager.inCache(url, headers), "After iteration, should find valid entry");
    }

}
