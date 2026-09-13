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
import java.nio.file.Path;

import javax.net.ssl.SSLContext;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.SSLManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Run in a fresh test JVM, with certificate retries disabled before sampler initialization. */
@EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_FIXTURE", matches = ".+")
class HTTP3MutualTlsTest {
    @Test
    void configuredClientCertificateIsUsedWithoutCertificateRetry() throws Exception {
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        Path fixture = Path.of(System.getenv("BREAKTEST_HTTP3_FIXTURE"));
        // Initialize the default context without client keys. Reusing it would fail mTLS.
        SSLContext.getDefault();
        System.setProperty("javax.net.ssl.trustStore", fixture.resolve("trust.jks").toString());
        System.setProperty("javax.net.ssl.keyStore", fixture.resolve("client.p12").toString());
        System.setProperty("javax.net.ssl.keyStorePassword", "password");
        JMeterUtils.setProperty("httpsampler.http3.ignore_certificate_errors", "false");
        SSLManager.reset();
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("10000");
        HTTPJavaHttp3Impl impl = new HTTPJavaHttp3Impl(sampler);
        try {
            HTTPSampleResult result = impl.sample(new URI("https://mtls.example.test:19446/").toURL(),
                    HTTPConstants.GET, false, 0);
            assertTrue(result.isSuccessful(), result.getResponseCode() + " " + result.getResponseMessage()
                    + " " + result.getResponseHeaders() + " " + result.getResponseDataAsString());
            assertTrue(result.getResponseHeaders().startsWith("HTTP/3"), result.getResponseHeaders());
            assertEquals("mtls", result.getResponseDataAsString());
            impl.threadFinished();
            System.clearProperty("javax.net.ssl.keyStore");
            System.clearProperty("javax.net.ssl.keyStorePassword");
            SSLManager.reset();
            HTTPJavaHttp3Impl withoutClientKey = new HTTPJavaHttp3Impl(sampler);
            try {
                HTTPSampleResult rejected = withoutClientKey.sample(
                        new URI("https://mtls.example.test:19446/").toURL(), HTTPConstants.GET, false, 0);
                assertFalse(rejected.isSuccessful(), "The server must require a client certificate");
            } finally {
                withoutClientKey.threadFinished();
            }
        } finally {
            impl.threadFinished();
            SSLManager.reset();
            System.clearProperty("javax.net.ssl.trustStore");
            System.clearProperty("javax.net.ssl.keyStore");
            System.clearProperty("javax.net.ssl.keyStorePassword");
            JMeterUtils.getJMeterProperties().remove("httpsampler.http3.ignore_certificate_errors");
        }
    }
}
