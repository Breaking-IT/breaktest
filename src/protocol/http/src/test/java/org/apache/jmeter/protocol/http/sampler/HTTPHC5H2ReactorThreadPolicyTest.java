/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated("Changes the HTTP/2 reactor thread setting")
class HTTPHC5H2ReactorThreadPolicyTest {
    private static final String PROPERTY = "httpsampler.http2.virtual_reactor_threads";
    private Object previous;

    @BeforeAll
    static void setupProperties() throws Exception {
        HTTPHC5H2ThreadLifecycleTest.setupJMeterProperties();
    }

    @BeforeEach
    void clearReactorSetting() {
        previous = JMeterUtils.getJMeterProperties().remove(PROPERTY);
    }

    @AfterEach
    void restoreReactorSetting() {
        if (previous == null) {
            JMeterUtils.getJMeterProperties().remove(PROPERTY);
        } else {
            JMeterUtils.getJMeterProperties().put(PROPERTY, previous);
        }
    }

    @ParameterizedTest
    @CsvSource({"21,false", "22,false", "23,false", "24,false", "25,true", "26,true", "27,true"})
    void defaultDependsOnRuntime(int javaVersion, boolean expected) {
        assertEquals(expected, HTTPHC5H2Impl.useVirtualReactorThreads(javaVersion));
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 24, 25, 27})
    void explicitOptInOverridesRuntimeDefault(int javaVersion) {
        JMeterUtils.setProperty(PROPERTY, "true");
        assertTrue(HTTPHC5H2Impl.useVirtualReactorThreads(javaVersion));
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 24, 25, 27})
    void explicitOptOutOverridesRuntimeDefault(int javaVersion) {
        JMeterUtils.setProperty(PROPERTY, "false");
        assertFalse(HTTPHC5H2Impl.useVirtualReactorThreads(javaVersion));
    }
}
