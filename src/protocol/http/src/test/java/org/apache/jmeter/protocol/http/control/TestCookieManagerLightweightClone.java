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

package org.apache.jmeter.protocol.http.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies that cookies added at runtime stay isolated per thread even though
 * lightweight clones share the initial (read-only) cookie property.
 */
public class TestCookieManagerLightweightClone {

    @Test
    public void runtimeCookiesDoNotLeakAcrossLightweightClones() {
        CookieManager master = new CookieManager();
        master.setRunningVersion(true);

        CookieManager thread1 = (CookieManager) master.lightweightClone();
        CookieManager thread2 = (CookieManager) master.lightweightClone();
        assertTrue(thread1.isPropertiesShared());
        assertTrue(thread2.isPropertiesShared());

        thread1.add(new Cookie("session", "abc", "example.com", "/", false, 0));

        assertEquals(1, thread1.getCookieCount(), "cookie should be stored in the adding thread");
        assertEquals(0, thread2.getCookieCount(), "cookie must not leak into sibling clones");
        assertEquals(0, master.getCookieCount(), "cookie must not leak into the source element");
    }

    @Test
    public void removeMatchingCookiesDoesNotAffectSiblingClones() {
        CookieManager master = new CookieManager();
        Cookie initial = new Cookie("session", "initial", "example.com", "/", false, 0);
        master.add(initial);
        master.setRunningVersion(true);

        CookieManager thread1 = (CookieManager) master.lightweightClone();
        CookieManager thread2 = (CookieManager) master.lightweightClone();

        // Same name/domain/path replaces the initial cookie in thread1 only
        thread1.add(new Cookie("session", "updated", "example.com", "/", false, 0));

        assertEquals(1, thread1.getCookieCount());
        assertEquals("updated", thread1.get(0).getValue());
        assertEquals(1, thread2.getCookieCount());
        assertEquals("initial", thread2.get(0).getValue(), "sibling clone must keep the initial cookie");
        assertEquals("initial", master.get(0).getValue(), "source element must keep the initial cookie");
    }
}
