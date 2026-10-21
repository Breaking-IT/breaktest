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

package org.apache.jmeter.threads;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.jmeter.config.ConfigTestElement;
import org.junit.jupiter.api.Test;

class JMeterContextServiceTest {
    @Test
    void replacingContextInvalidatesTestElementCache() {
        JMeterContext original = JMeterContextService.getContext();
        ConfigTestElement element = new ConfigTestElement();
        try {
            element.setThreadContext(original);
            JMeterContext replacement = JMeterContextService.createContext();
            JMeterContextService.replaceContext(replacement);
            assertSame(replacement, element.getThreadContext());
            assertSame(replacement, new ConfigTestElement().getThreadContext());
        } finally {
            JMeterContextService.replaceContext(original);
        }
    }

    @Test
    void removingContextInvalidatesTestElementCache() {
        JMeterContext original = JMeterContextService.getContext();
        ConfigTestElement element = new ConfigTestElement();
        try {
            element.setThreadContext(original);
            JMeterContextService.removeContext();
            JMeterContext fresh = JMeterContextService.getContext();
            assertNotSame(original, fresh);
            assertSame(fresh, element.getThreadContext());
            assertSame(fresh, new ConfigTestElement().getThreadContext());
        } finally {
            JMeterContextService.replaceContext(original);
        }
    }
}
