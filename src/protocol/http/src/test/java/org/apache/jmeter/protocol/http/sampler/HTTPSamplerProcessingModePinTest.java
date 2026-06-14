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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.ResponseProcessingMode;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.Test;

/**
 * Verifies that an explicit STORE_COMPRESSED is a real pin (no longer silently stripped),
 * that an absent property inherits, and that the legacy usemd5 conversion behaves correctly.
 */
public class HTTPSamplerProcessingModePinTest {

    private static String modeName() {
        return HTTPSamplerBaseSchema.INSTANCE.getResponseProcessingMode().getName();
    }

    @Test
    public void explicitStoreCompressedIsNotStripped() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty(new StringProperty(modeName(),
                ResponseProcessingMode.STORE_COMPRESSED.getResourceKey()));

        assertNotNull(sampler.getPropertyOrNull(modeName()),
                "an explicitly pinned STORE_COMPRESSED must be persisted, not stripped");
        assertEquals(ResponseProcessingMode.STORE_COMPRESSED, sampler.getResponseProcessingMode());
    }

    @Test
    public void setterPinsStoreCompressed() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setResponseProcessingMode(ResponseProcessingMode.STORE_COMPRESSED);

        assertNotNull(sampler.getPropertyOrNull(modeName()),
                "the response processing mode setter must persist explicit STORE_COMPRESSED pins");
        assertEquals(ResponseProcessingMode.STORE_COMPRESSED, sampler.getResponseProcessingMode());
    }

    @Test
    public void absentPropertyInheritsAndDefaultsToStoreCompressed() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        assertNull(sampler.getPropertyOrNull(modeName()), "a fresh sampler does not pin the mode");
        assertEquals(ResponseProcessingMode.STORE_COMPRESSED, sampler.getResponseProcessingMode(),
                "absent property resolves to the built-in default");
    }

    @Test
    public void pinnedStoreCompressedSurvivesRequestDefaultsMerge() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty(new StringProperty(modeName(),
                ResponseProcessingMode.STORE_COMPRESSED.getResourceKey()));

        // HTTP Request Defaults trying to switch the scope to fetch-and-discard
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setProperty(new StringProperty(modeName(),
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey()));
        sampler.addTestElement(defaults);

        assertEquals(ResponseProcessingMode.STORE_COMPRESSED, sampler.getResponseProcessingMode(),
                "a pinned sampler value must win over merged Request Defaults");
    }

    @Test
    public void inheritingSamplerTakesDefaultsValueOnMerge() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(); // no pin -> inherits
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setProperty(new StringProperty(modeName(),
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey()));
        sampler.addTestElement(defaults);

        assertEquals(ResponseProcessingMode.FETCH_AND_DISCARD, sampler.getResponseProcessingMode(),
                "an inheriting sampler must take the merged Request Defaults value");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void legacyUseMd5FalseInheritsRatherThanPinning() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty(new BooleanProperty(HTTPSamplerBaseSchema.INSTANCE.getStoreAsMD5().getName(), false));

        assertNull(sampler.getPropertyOrNull(modeName()),
                "usemd5=false must leave the mode absent (inherit), not pin STORE_COMPRESSED");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void legacyUseMd5TrueSelectsChecksumDecoded() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty(new BooleanProperty(HTTPSamplerBaseSchema.INSTANCE.getStoreAsMD5().getName(), true));

        assertEquals(ResponseProcessingMode.CHECKSUM_DECODED_MD5, sampler.getResponseProcessingMode());
    }
}
