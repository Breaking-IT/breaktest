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

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Isolated;

/** Run the same ownership, parallel-branch and cleanup checks with virtual reactors on every runtime. */
@Isolated("Changes the HTTP/2 reactor thread setting")
class HTTPHC5H2VirtualReactorLifecycleTest extends HTTPHC5H2ThreadLifecycleTest {
    private static final String PROPERTY = "httpsampler.http2.virtual_reactor_threads";
    private static Object previous;

    @BeforeAll
    static void useVirtualReactors() {
        previous = JMeterUtils.getJMeterProperties().setProperty(PROPERTY, "true");
    }

    @AfterAll
    static void restoreReactorSetting() {
        if (previous == null) {
            JMeterUtils.getJMeterProperties().remove(PROPERTY);
        } else {
            JMeterUtils.getJMeterProperties().put(PROPERTY, previous);
        }
    }
}
