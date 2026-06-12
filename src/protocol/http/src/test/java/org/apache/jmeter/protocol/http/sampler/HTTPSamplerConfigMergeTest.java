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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the per-iteration config merge (Header Manager, HTTP Request
 * Defaults) does not break property sharing of lightweight-cloned samplers.
 */
public class HTTPSamplerConfigMergeTest {

    private static HTTPSamplerProxy newSampler() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Request");
        sampler.setDomain("example.com");
        sampler.setMethod("GET");
        sampler.setPath("/api/${id}");
        return sampler;
    }

    @Test
    public void headerManagerMergeCycleKeepsSamplerShared() {
        HTTPSamplerProxy source = newSampler();
        source.setRunningVersion(true);
        HTTPSamplerProxy sampler = (HTTPSamplerProxy) source.lightweightClone();

        HeaderManager headerManager = new HeaderManager();
        headerManager.add(new Header("Accept", "application/json"));
        headerManager.setRunningVersion(true);

        // Two iterations of TestCompiler's configure/sample/recover cycle
        for (int iteration = 0; iteration < 2; iteration++) {
            sampler.clearTestElementChildren();
            sampler.addTestElement(headerManager);

            assertTrue(sampler.isPropertiesShared(),
                    "header manager merge must not break sharing (iteration " + iteration + ")");
            assertNotNull(sampler.getHeaderManager(), "merged header manager must be visible");
            assertNull(source.getHeaderManager(), "merge must not leak into the source");

            sampler.recoverRunningVersion();

            assertNull(sampler.getHeaderManager(), "header manager must be rolled back");
            assertTrue(sampler.isPropertiesShared(), "sharing survives recovery");
        }
    }

    @Test
    public void requestDefaultsMergeCycleKeepsSamplerShared() {
        HTTPSamplerProxy source = newSampler();
        source.setRunningVersion(true);
        HTTPSamplerProxy sampler = (HTTPSamplerProxy) source.lightweightClone();

        // HTTP Request Defaults providing a value the sampler doesn't set
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setProperty(new StringProperty("HTTPSampler.port", "8443"));
        defaults.setRunningVersion(true);

        for (int iteration = 0; iteration < 2; iteration++) {
            sampler.clearTestElementChildren();
            sampler.addTestElement(defaults);

            assertTrue(sampler.isPropertiesShared(),
                    "request defaults merge must not break sharing (iteration " + iteration + ")");
            assertEquals(8443, sampler.getPort(), "merged default must be effective");
            assertSame(source.getProperty("HTTPSampler.domain"), sampler.getProperty("HTTPSampler.domain"),
                    "sampler's own properties stay shared through the merge");

            sampler.recoverRunningVersion();
            assertTrue(sampler.isPropertiesShared(), "sharing survives recovery");
        }
    }
}
