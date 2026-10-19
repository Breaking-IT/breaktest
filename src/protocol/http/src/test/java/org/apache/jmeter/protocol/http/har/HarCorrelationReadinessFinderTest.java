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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ServiceLoader;

import org.apache.jmeter.readiness.check.HardcodedValues;
import org.junit.jupiter.api.Test;

class HarCorrelationReadinessFinderTest {

    @Test
    void isRegisteredForScriptReadiness() {
        assertTrue(ServiceLoader.load(HardcodedValues.Finder.class).stream()
                .anyMatch(p -> p.type() == HarCorrelationReadinessFinder.class));
    }

    @Test
    void neverShowsRecordedSecrets() {
        assertEquals("abcd… (10 characters)", HarCorrelationReadinessFinder.mask("page", "abcdefghij"));
        assertEquals("***(10 characters)".replace("***", "*** "),
                HarCorrelationReadinessFinder.mask("csrf_token", "abcdefghij"));
        assertFalse(HarCorrelationReadinessFinder.mask("SessionId", "s3cr3t-value").contains("s3cr"));
    }

    @Test
    void withoutTheGuiTheScanIsUnavailable() {
        HardcodedValues.Scan scan = new HarCorrelationReadinessFinder().find(List.of());
        assertFalse(scan.available());
    }
}
