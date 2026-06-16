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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.jupiter.api.Test;

public class TestHTTPHC5Impl {
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
