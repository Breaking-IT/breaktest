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

package org.apache.jmeter.visualizers.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class BackendListenerMetadataTest {

    public static final class SourcePathClient extends AbstractBackendListenerClient {
        @Override
        public boolean needsSourceTestElementPath() {
            return true;
        }

        @Override
        public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
            // NOOP
        }
    }

    @Test
    void delegatesMetadataRequirementsToClient() {
        BackendListener listener = new BackendListener();
        listener.setName(getClass().getSimpleName() + '-' + System.nanoTime());
        listener.setClassname(SourcePathClient.class.getName());

        assertFalse(listener.needsSourceTestElementPath());
        assertFalse(listener.needsJMeterVariables());

        listener.testStarted();
        try {
            assertTrue(listener.needsSourceTestElementPath());
            assertFalse(listener.needsJMeterVariables());
        } finally {
            listener.testEnded();
        }
    }
}
