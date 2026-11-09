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

package org.apache.jmeter.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.config.ConfigTestElement;
import org.junit.jupiter.api.Test;

class ReadinessExceptionsTest {

    @Test
    void roundTripsKeysWithSeparatorsAndReasons() {
        ConfigTestElement element = new ConfigTestElement();
        AcceptedException exception = new AcceptedException(CheckId.UNRESOLVED_VARIABLES, "user.name;x=1",
                "Literal template; sent on purpose = yes", "Jo", "2026-10-17");
        ReadinessExceptions.put(element, exception);
        String property = ReadinessExceptions.propertyName(CheckId.UNRESOLVED_VARIABLES, "user.name;x=1");
        assertTrue(property.startsWith("breaktest.readiness.exception.unresolved-variables."), property);
        assertFalse(property.substring(ReadinessExceptions.PREFIX.length()).contains("user.name"), property);
        assertEquals(List.of(exception), ReadinessExceptions.read(element));
        assertEquals(exception, ReadinessExceptions.get(element, CheckId.UNRESOLVED_VARIABLES, "user.name;x=1"));
        assertTrue(ReadinessExceptions.remove(element, CheckId.UNRESOLVED_VARIABLES, "user.name;x=1"));
        assertTrue(ReadinessExceptions.read(element).isEmpty());
        assertFalse(ReadinessExceptions.remove(element, CheckId.UNRESOLVED_VARIABLES, "user.name;x=1"));
    }

    @Test
    void anyKeyCoversEveryFindingOfItsCheckOnly() {
        ConfigTestElement element = new ConfigTestElement();
        ReadinessExceptions.put(element, new AcceptedException(CheckId.THINK_TIME, ReadinessExceptions.ANY_KEY,
                "API calls", "", ""));
        ElementRef ref = new ElementRef("1:2", "api", "x", List.of());
        Finding thinkTime = new Finding(CheckId.THINK_TIME, ref, "missing-delay", Severity.REVIEW, "", List.of(), null);
        Finding other = new Finding(CheckId.UNUSED_EXTRACTIONS, ref, "token", Severity.WARNING, "", List.of(), null);
        assertEquals("API calls", ReadinessExceptions.find(element, thinkTime).reason());
        assertNull(ReadinessExceptions.find(element, other));
    }

    @Test
    void ignoresUnknownChecksAndOtherProperties() {
        ConfigTestElement element = new ConfigTestElement();
        element.setProperty(ReadinessExceptions.PREFIX + "no-such-check.key", "reason=x");
        element.setProperty("other", "value");
        assertTrue(ReadinessExceptions.read(element).isEmpty());
    }
}
