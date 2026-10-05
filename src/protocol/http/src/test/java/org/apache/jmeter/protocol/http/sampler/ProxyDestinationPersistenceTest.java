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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.swing.SwingUtilities;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class ProxyDestinationPersistenceTest extends JMeterTestCase implements JMeterSerialTest {
    private static final String MODE = "HTTPSampler.proxyDestinationMode";
    private static final String PATTERNS = "HTTPSampler.proxyDestinationPatterns";

    @Test
    void absentModeInheritsAndExplicitAllSurvivesMerge() {
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setProperty(MODE, "proxy_filter_exclude");
        defaults.setProperty(PATTERNS, "*.internal");
        var inherited = new HTTPSamplerProxy();
        inherited.addTestElement(defaults);
        assertEquals("proxy_filter_exclude", inherited.getPropertyAsString(MODE));
        assertEquals("*.internal", inherited.getPropertyAsString(PATTERNS));
        var pinned = new HTTPSamplerProxy();
        pinned.setProperty(MODE, "proxy_filter_all");
        pinned.addTestElement(defaults);
        assertEquals("proxy_filter_all", pinned.getPropertyAsString(MODE));
    }

    @Test
    void filterOnlyOverrideKeepsBroaderEndpointAndSamplerBypassWins() throws Exception {
        ConfigTestElement endpoint = new ConfigTestElement();
        endpoint.setProperty("HTTPSampler.proxyHost", "proxy.example.com");
        endpoint.setProperty("HTTPSampler.proxyPort", "8888");
        ConfigTestElement filtered = new ConfigTestElement();
        filtered.setProperty(MODE, "proxy_filter_include");
        filtered.setProperty(PATTERNS, "*.example.com");
        var sampler = new HTTPSamplerProxy();
        sampler.addTestElement(filtered);
        sampler.addTestElement(endpoint);
        var impl = new HTTPHC5Impl(sampler);
        var url = java.net.URI.create("https://api.example.com").toURL();
        var resolved = impl.resolveProxy(url, null);
        assertEquals("proxy.example.com", resolved.host());
        assertEquals(8888, resolved.port());
        assertTrue(resolved.policy().allowsProxy("api.example.com"));
        assertFalse(resolved.policy().allowsProxy("elsewhere.test"));

        var direct = new HTTPSamplerProxy();
        direct.setProperty(MODE, "proxy_filter_direct");
        direct.addTestElement(filtered);
        direct.addTestElement(endpoint);
        assertFalse(new HTTPHC5Impl(direct).resolveProxy(url, null).enabled());
        assertEquals("", direct.getPropertyAsString(PATTERNS));
    }

    @Test
    void anyLocalProxySettingReplacesBroaderFilters() {
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setProperty(MODE, "proxy_filter_exclude");
        defaults.setProperty(PATTERNS, "*.internal");
        defaults.setProperty("HTTPSampler.proxyPort", "8888");
        var sampler = new HTTPSamplerProxy();
        sampler.setProxyHost("custom.proxy");
        sampler.addTestElement(defaults);
        assertEquals("", sampler.getPropertyAsString(MODE));
        assertEquals("", sampler.getPropertyAsString(PATTERNS));
        assertEquals(8888, sampler.getProxyPortInt());
    }

    @Test
    void scopedPolicyDoesNotLeakAcrossIterations() {
        var sampler = new HTTPSamplerProxy();
        sampler.setRunningVersion(true);
        for (String mode : new String[] {"proxy_filter_include", "proxy_filter_exclude"}) {
            ConfigTestElement defaults = new ConfigTestElement();
            defaults.setProperty(MODE, mode);
            defaults.setProperty(PATTERNS, "example.com");
            sampler.addTestElement(defaults);
            assertEquals(mode, sampler.getPropertyAsString(MODE));
            sampler.recoverRunningVersion();
            assertEquals("", sampler.getPropertyAsString(MODE));
            assertEquals("", sampler.getPropertyAsString(PATTERNS));
        }
    }

    @Test
    void jmxRoundTripPreservesPatternsAndExplicitMode() throws Exception {
        var sampler = new HTTPSamplerProxy();
        sampler.setProperty(TestElement.GUI_CLASS, HttpTestSampleGui.class.getName());
        sampler.setProperty(TestElement.TEST_CLASS, HTTPSamplerProxy.class.getName());
        sampler.setProperty(MODE, "proxy_filter_include");
        sampler.setProperty(PATTERNS, "*.example.com\nlocalhost\n${proxy_host}");
        var output = new ByteArrayOutputStream();
        SaveService.saveElement(sampler, output);
        var loaded = (HTTPSamplerProxy) SaveService.loadElement(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(sampler.getPropertyAsString(MODE), loaded.getPropertyAsString(MODE));
        assertEquals(sampler.getPropertyAsString(PATTERNS), loaded.getPropertyAsString(PATTERNS));
    }

    @Test
    void bothEditorsPreserveModesAndClearBackToInheritance() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var gui : new org.apache.jmeter.gui.JMeterGUIComponent[] {new HttpTestSampleGui(), new HttpDefaultsGui()}) {
                for (var mode : ProxyDestinationPolicy.Mode.values()) {
                    TestElement element = gui.createTestElement();
                    element.setProperty(MODE, mode.getResourceKey());
                    element.setProperty(PATTERNS, "*.example.com\nlocalhost");
                    gui.configure(element);
                    TestElement saved = gui.createTestElement();
                    gui.modifyTestElement(saved);
                    assertEquals(mode == ProxyDestinationPolicy.Mode.ALL ? "" : mode.getResourceKey(), saved.getPropertyAsString(MODE));
                    assertEquals(mode == ProxyDestinationPolicy.Mode.INCLUDE || mode == ProxyDestinationPolicy.Mode.EXCLUDE
                            ? "*.example.com\nlocalhost" : "", saved.getPropertyAsString(PATTERNS));
                }
                gui.clearGui();
                assertNull(gui.createTestElement().getPropertyOrNull(MODE));
                assertNull(gui.createTestElement().getPropertyOrNull(PATTERNS));
            }
        });
    }
}
