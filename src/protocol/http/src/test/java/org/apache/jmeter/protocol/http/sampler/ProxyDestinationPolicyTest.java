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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProxyDestinationPolicyTest {
    @Test
    void hostnameBoundariesAndNormalization() {
        var policy = ProxyDestinationPolicy.compile("proxy_filter_include",
                "API.EXAMPLE.COM.\n*.service.example.com\n bücher.example \n[::1]\n127.0.0.1");
        assertTrue(policy.allowsProxy("api.example.com"));
        assertTrue(policy.allowsProxy("API.EXAMPLE.COM."));
        assertTrue(policy.allowsProxy("a.b.service.example.com"));
        assertTrue(policy.allowsProxy("xn--bcher-kva.example"));
        assertTrue(policy.allowsProxy("0:0:0:0:0:0:0:1"));
        assertTrue(policy.allowsProxy("[::1]"));
        assertTrue(policy.allowsProxy("127.0.0.1"));
        assertFalse(policy.allowsProxy("service.example.com"));
        assertFalse(policy.allowsProxy("evilservice.example.com"));
        assertFalse(policy.allowsProxy("api.example.com.evil"));
    }

    @Test
    void exclusionAndUnfilteredModes() {
        var policy = ProxyDestinationPolicy.compile("proxy_filter_exclude", "localhost\r\n*.internal");
        assertFalse(policy.allowsProxy("LOCALHOST"));
        assertFalse(policy.allowsProxy("api.internal"));
        assertTrue(policy.allowsProxy("example.com"));
        assertTrue(ProxyDestinationPolicy.compile("", "").allowsProxy("example.com"));
        assertTrue(ProxyDestinationPolicy.compile("proxy_filter_all", "unused").allowsProxy("example.com"));
        assertFalse(ProxyDestinationPolicy.compile("proxy_filter_direct", "unused").allowsProxy("example.com"));
    }

    @Test
    void invalidRulesFailInsteadOfSilentlyBypassingProxy() {
        for (String rule : new String[] {"", " ", "*", "foo.*", "a*b.example", "https://example.com",
                "example.com:443", "example.com/path", "*.127.0.0.1", "*.::1", "example.com|localhost"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ProxyDestinationPolicy.compile("proxy_filter_include", rule), rule);
        }
        assertThrows(IllegalArgumentException.class, () -> ProxyDestinationPolicy.compile("misspelled", ""));
    }
}
