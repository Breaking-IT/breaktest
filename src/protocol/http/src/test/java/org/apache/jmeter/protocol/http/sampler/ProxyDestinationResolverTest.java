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
    void legacySamplerAndDefaultsWithMissingPortRemainDirect() throws Exception {
        var url = URI.create("http://example.com").toURL();
        var sampler = new HTTPSamplerProxy();
        sampler.setProxyHost("proxy.invalid");
        assertFalse(new HTTPHC5Impl(sampler).resolveProxy(url, null).enabled());
        var defaults = new org.apache.jmeter.config.ConfigTestElement();
        defaults.setProperty("HTTPSampler.proxyHost", "proxy.invalid");
        var inherited = new HTTPSamplerProxy();
        inherited.addTestElement(defaults);
        assertFalse(new HTTPHC5Impl(inherited).resolveProxy(url, null).enabled());
        sampler.addTestElement(new org.apache.jmeter.config.ConfigTestElement());
        assertFalse(new HTTPHC5Impl(sampler).resolveProxy(url, null).enabled());
    }

    @Test
    void globalEndpointAndCredentialRegressionsInFreshJvm() throws Exception {
        // Proxy globals are static finals, so each scenario needs a fresh JVM.
        var entries = new java.util.LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    entries.add(java.nio.file.Path.of(url.toURI()).toString());
                }
            }
        }
        for (String scenario : new String[] {"missing-port", "credentials"}) {
            var process = new ProcessBuilder(
                    java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", String.join(java.io.File.pathSeparator, entries),
                    getClass().getName(), scenario).inheritIO().start();
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Child JVM timed out");
                assertEquals(0, process.exitValue(), scenario);
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("http.proxyHost", "corporate.invalid");
        if (args[0].equals("missing-port")) {
            System.clearProperty("http.proxyPort");
        } else {
            System.setProperty("http.proxyPort", "8080");
        }
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties("../../../bin/jmeter.properties");
        org.apache.jmeter.util.JMeterUtils.setProperty(org.apache.jmeter.JMeter.HTTP_PROXY_USER, "corporate-user");
        org.apache.jmeter.util.JMeterUtils.setProperty(org.apache.jmeter.JMeter.HTTP_PROXY_PASS, "corporate-secret");
        var sampler = new HTTPSamplerProxy();
        var url = URI.create("http://my_service:8080/").toURL();
        if (args[0].equals("missing-port")) {
            assertFalse(new HTTPHC5Impl(sampler).resolveProxy(url, null).enabled());
            return;
        }
        sampler.setProperty(ProxyDestinationPolicy.MODE_PROPERTY, "proxy_filter_exclude");
        sampler.setProperty(ProxyDestinationPolicy.PATTERNS_PROPERTY, "*.example.com");
        var global = new HTTPHC5Impl(sampler).resolveProxy(url, null);
        assertEquals("corporate-user", global.username());
        assertEquals("corporate-secret", global.password());
        assertTrue(global.policy().allowsProxy(url.getHost()));
        var context = HttpClientContext.create();
        context.setAttribute(HTTPHCAbstractImpl.PROXY_POLICY, global);
        var endpoint = new HttpHost("http", global.host(), global.port());
        assertEquals(endpoint, HTTPHCAbstractImpl.selectProxy(endpoint,
                new HttpHost("http", "my_service", 8080), context));
        sampler.setProxyHost("capture.invalid");
        sampler.setProxyPortInt("8888");
        var local = new HTTPHC5Impl(sampler).resolveProxy(url, null);
        assertEquals("", local.username());
        assertEquals("", local.password());
        sampler.setProxyUser("local-user");
        local = new HTTPHC5Impl(sampler).resolveProxy(url, null);
        assertEquals("local-user", local.username());
        assertEquals("", local.password());
        sampler.setProxyPass("local-secret");
        local = new HTTPHC5Impl(sampler).resolveProxy(url, null);
        assertEquals("local-user", local.username());
        assertEquals("local-secret", local.password());
        var defaults = new org.apache.jmeter.config.ConfigTestElement();
        defaults.setProperty("HTTPSampler.proxyHost", "capture.invalid");
        defaults.setProperty("HTTPSampler.proxyPort", "8888");
        var inherited = new HTTPSamplerProxy();
        inherited.addTestElement(defaults);
        local = new HTTPHC5Impl(inherited).resolveProxy(url, null);
        assertEquals("", local.username());
        assertEquals("", local.password());
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
