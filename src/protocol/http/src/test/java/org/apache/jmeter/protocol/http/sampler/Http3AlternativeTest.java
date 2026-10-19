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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.URL;

import org.junit.jupiter.api.Test;

class Http3AlternativeTest {
    @Test
    void honorsExpiryAndClearWithoutChangingAuthority() throws Exception {
        URL origin = URI.create("https://example.test/").toURL();
        assertNull(Http3Alternative.expiry(origin, "HTTP/2 200\r\nContent-Type: text/plain\r\n", 1000));
        assertEquals(61000L, Http3Alternative.expiry(origin, "Alt-Svc: h3=\":443\"; ma=60", 1000));
        assertEquals(1000L, Http3Alternative.expiry(origin, "Alt-Svc: h3=\":443\"; ma=0", 1000));
        assertEquals(0L, Http3Alternative.expiry(origin, "Alt-Svc: clear", 1000));
        assertEquals(61000L, Http3Alternative.expiry(origin,
                "alt-svc: h2=\":443\", h3=\"example.test:443\"; ma=60; persist=1", 1000));
        assertEquals(0L, Http3Alternative.expiry(origin, "Alt-Svc: h3=\"other.test:443\"", 1000));
        assertEquals(0L, Http3Alternative.expiry(origin, "Alt-Svc: h3=\":8443\"", 1000));
        assertEquals(0L, Http3Alternative.expiry(origin, "Alt-Svc: h3=\"user@example.test:443\"", 1000));
        assertEquals(0L, Http3Alternative.expiry(origin, "Alt-Svc: h3=\":443\"; ma=999999999999999999999", 1000));
    }
}
