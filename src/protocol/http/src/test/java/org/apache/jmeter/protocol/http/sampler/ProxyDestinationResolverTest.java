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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class ProxyDestinationResolverTest implements JMeterSerialTest {
    @Test
    void variablesAreResolvedBeforeCompilingRules() throws Exception {
        var variables = new JMeterVariables();
        variables.put("proxy_pattern", "*.example.com");
        JMeterContextService.getContext().setVariables(variables);
        try {
            var sampler = new HTTPSamplerProxy();
            sampler.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_include");
            sampler.setProperty("HTTPSampler.proxyDestinationPatterns", "${proxy_pattern}");
            new ValueReplacer().replaceValues(sampler);
            sampler.setRunningVersion(true);
            var impl = new HTTPHC5Impl(sampler);
            var url = URI.create("http://api.example.com").toURL();
            assertTrue(impl.resolveProxy(url, null).policy().allowsProxy("api.example.com"));
            variables.put("proxy_pattern", "different.example.com");
            assertFalse(impl.resolveProxy(url, null).policy().allowsProxy("api.example.com"));
        } finally {
            JMeterContextService.getContext().clear();
        }
    }

    @Test
    void globalExclusionsAndDirectModeNeverFallBackToProxy() {
        String host = "proxy-filter-global-test.invalid";
        var endpoint = new HttpHost("http", "global-proxy.invalid", 8080);
        var target = new HttpHost("http", host, 80);
        var context = HttpClientContext.create();
        boolean added = HTTPHCAbstractImpl.nonProxyHostFull.add(host);
        try {
            context.setAttribute(HTTPHCAbstractImpl.PROXY_POLICY, settings("proxy_filter_all", true));
            assertNull(HTTPHCAbstractImpl.selectProxy(endpoint, target, context));
            context.setAttribute(HTTPHCAbstractImpl.PROXY_POLICY, settings("proxy_filter_all", false));
            assertEquals(endpoint, HTTPHCAbstractImpl.selectProxy(endpoint, target, context),
                    "request proxy continues to take priority over global exclusions");
            context.setAttribute(HTTPHCAbstractImpl.PROXY_POLICY, settings("proxy_filter_direct", true));
            assertNull(HTTPHCAbstractImpl.selectProxy(endpoint, target, context));
            context.setAttribute(HTTPHCAbstractImpl.PROXY_POLICY, settings("proxy_filter_include", true));
            assertNull(HTTPHCAbstractImpl.selectProxy(endpoint, target, context));
        } finally {
            if (added) {
                HTTPHCAbstractImpl.nonProxyHostFull.remove(host);
            }
        }
    }

    private static HTTPHCAbstractImpl.ProxySettings settings(String mode, boolean global) {
        return new HTTPHCAbstractImpl.ProxySettings(true, "http", "global-proxy.invalid", 8080,
                "user", "secret", ProxyDestinationPolicy.compile(mode, "different.invalid"), global, false);
    }

    @Test
    void incompleteProxyFailsForNewPolicyButDirectCanOverrideIt() throws Exception {
        var sampler = new HTTPSamplerProxy();
        sampler.setProxyHost("proxy.invalid");
        sampler.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_all");
        var impl = new HTTPHC5Impl(sampler);
        var url = URI.create("http://example.com").toURL();
        assertThrows(IllegalArgumentException.class, () -> impl.resolveProxy(url, null));
        sampler.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_direct");
        assertFalse(impl.resolveProxy(url, null).enabled());
        assertFalse(settings("proxy_filter_all", false).toString().contains("secret"));
    }
}
