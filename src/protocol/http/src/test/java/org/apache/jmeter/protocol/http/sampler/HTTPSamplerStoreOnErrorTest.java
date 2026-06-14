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

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.ResponseProcessingMode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ResponseProcessingMode#STORE_ON_ERROR} and the validation-run override that
 * forces body-discarding modes to keep the body so it is visible while validating. A normal
 * run (GUI or non-GUI) always honours the configured mode.
 */
public class HTTPSamplerStoreOnErrorTest {

    private static final byte[] BODY = "response-body-contents".getBytes();

    @BeforeEach
    public void notValidationRun() {
        // validation-run forces storage; ensure it is off so the configured mode is honoured
        // (it is a static, reset to avoid cross-test leakage).
        JMeterContextService.setValidationRun(false);
    }

    @AfterEach
    public void restoreValidationRun() {
        JMeterContextService.setValidationRun(false);
    }

    private static byte[] readWith(ResponseProcessingMode mode, String responseCode) throws IOException {
        HTTPSamplerBase sampler = new HTTPNullSampler();
        sampler.setResponseProcessingMode(mode);
        SampleResult result = new SampleResult();
        result.sampleStart();
        result.setResponseCode(responseCode);
        sampler.readResponse(result, new ByteArrayInputStream(BODY), BODY.length, null);
        return result.getResponseData();
    }

    @Test
    public void storeOnErrorDiscardsSuccessfulResponseBody() throws IOException {
        assertEquals(0, readWith(ResponseProcessingMode.STORE_ON_ERROR, "200").length,
                "2xx response body must be discarded in STORE_ON_ERROR mode");
    }

    @Test
    public void storeOnErrorKeepsErrorResponseBody() throws IOException {
        assertEquals(BODY.length, readWith(ResponseProcessingMode.STORE_ON_ERROR, "500").length,
                "5xx response body must be kept in STORE_ON_ERROR mode");
        assertEquals(BODY.length, readWith(ResponseProcessingMode.STORE_ON_ERROR, "404").length,
                "4xx response body must be kept in STORE_ON_ERROR mode");
    }

    @Test
    public void storeOnErrorKeepsRedirectAndUnknownConservatively() throws IOException {
        assertEquals(0, readWith(ResponseProcessingMode.STORE_ON_ERROR, "302").length,
                "3xx is not an error, body discarded");
        assertEquals(BODY.length, readWith(ResponseProcessingMode.STORE_ON_ERROR, "(null)").length,
                "unparseable response code keeps the body conservatively");
        assertEquals(BODY.length, readWith(ResponseProcessingMode.STORE_ON_ERROR, "").length,
                "missing response code keeps the body conservatively");
    }

    @Test
    public void normalRunHonoursFetchAndDiscard() throws IOException {
        assertEquals(0, readWith(ResponseProcessingMode.FETCH_AND_DISCARD, "200").length);
        assertEquals(0, readWith(ResponseProcessingMode.FETCH_AND_DISCARD, "500").length,
                "FETCH_AND_DISCARD discards even on error (unlike STORE_ON_ERROR)");
    }

    @Test
    public void validationRunForcesStorage() throws IOException {
        JMeterContextService.setValidationRun(true);
        assertEquals(BODY.length, readWith(ResponseProcessingMode.FETCH_AND_DISCARD, "200").length,
                "a validation run must force FETCH_AND_DISCARD to keep the body for inspection");
        assertEquals(BODY.length, readWith(ResponseProcessingMode.STORE_ON_ERROR, "200").length,
                "a validation run must force STORE_ON_ERROR to keep the body for inspection");
    }
}
