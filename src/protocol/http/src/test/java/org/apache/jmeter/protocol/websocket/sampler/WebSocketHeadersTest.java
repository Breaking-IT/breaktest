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

package org.apache.jmeter.protocol.websocket.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Locale;

import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.gui.HeaderTablePanel;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSocketHeadersTest extends JMeterTestCase {
    private static final URI WS = URI.create("wss://example.test/socket");

    @BeforeEach
    void setup() {
        JMeterUtils.setLocale(Locale.ENGLISH);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
    }

    @AfterEach
    void cleanup() {
        JMeterContextService.getContext().clear();
    }

    @Test
    void compiledHeaderScopeUsesNativeThenNearestAndPreservesRepeatedValues() throws Exception {
        HeaderManager outer = new HeaderManager();
        outer.add(new Header("X-Scope", "outer"));
        outer.add(new Header("Origin", "https://outer.test"));
        HeaderManager inner = new HeaderManager();
        inner.add(new Header("x-scope", "inner"));
        inner.add(new Header("X-Repeated", "one"));
        inner.add(new Header("X-Repeated", "two"));
        ListedHashTree tree = new ListedHashTree();
        var plan = tree.add(new TestPlan());
        plan.add(outer);
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        var children = plan.add(group);
        children.add(inner);
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.setHeaders(List.of(new Header("origin", "https://native.test")));
        children.add(sampler);
        TestCompiler compiler = new TestCompiler(tree);
        tree.traverse(compiler);
        for (int i = 0; i < 2; i++) {
            var pack = compiler.configureSampler(sampler);
            List<Header> headers = sampler.requestHeaders(WS);
            assertEquals(List.of("https://native.test"), values(headers, "origin"));
            assertEquals(List.of("inner"), values(headers, "x-scope"));
            assertEquals(List.of("one", "two"), values(headers, "x-repeated"));
            compiler.done(pack);
            assertEquals(1, sampler.requestHeaders(WS).size());
        }
        assertEquals("https://outer.test", outer.get(1).getValue());
        assertEquals(3, inner.size());
    }

    @Test
    void cookieManagerOverridesManualCookieWhenApplicable() throws Exception {
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.setHeaders(List.of(new Header("cOoKiE", "manual=yes")));
        assertEquals(List.of("manual=yes"), values(sampler.requestHeaders(WS), "cookie"));
        CookieManager cookies = new CookieManager();
        cookies.testStarted();
        cookies.addCookieFromHeader("session=login; Path=/; Secure", URI.create("https://example.test/login").toURL());
        sampler.addTestElement(cookies);
        assertEquals(List.of("session=login"), values(sampler.requestHeaders(WS), "cookie"));
    }

    @Test
    void nativeHeaderVariablesResolveOnTheUserThread() throws Exception {
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.setHeaders(List.of(new Header("Authorization", "Bearer ${token}")));
        new ValueReplacer().replaceValues(sampler);
        sampler.setRunningVersion(true);
        JMeterContextService.getContext().getVariables().put("token", "first");
        TestBeanHelper.prepare(sampler);
        assertEquals(List.of("Bearer first"), values(sampler.requestHeaders(WS), "authorization"));
    }

    @Test
    void scopedTransportHeadersDoNotBreakWebSocketHandshake() throws Exception {
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        HeaderManager scoped = new HeaderManager();
        scoped.add(new Header("Connection", "keep-alive"));
        scoped.add(new Header("Host", "http-host.example"));
        scoped.add(new Header("X-Application", "kept"));
        sampler.addTestElement(scoped);
        assertTrue(values(sampler.requestHeaders(WS), "connection").isEmpty());
        assertTrue(values(sampler.requestHeaders(WS), "host").isEmpty());
        assertEquals(List.of("kept"), values(sampler.requestHeaders(WS), "x-application"));
    }

    @Test
    void rejectsTransportOwnedHeadersBeforeConnecting() {
        for (String name : List.of("Host", "Connection", "Content-Length", "Upgrade", "Sec-WebSocket-Key")) {
            WebSocketConnectSampler sampler = new WebSocketConnectSampler();
            sampler.setHeaders(List.of(new Header(name, "value")));
            assertThrows(IllegalArgumentException.class, () -> sampler.requestHeaders(WS));
        }
    }

    @Test
    void headersTabCommitsEditsPersistsAndClearsBetweenSamplers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                TestBeanGUI gui = new TestBeanGUI(WebSocketConnectSampler.class);
                WebSocketConnectSampler sampler = (WebSocketConnectSampler) gui.createTestElement();
                sampler.setHeaders(List.of(new Header("X-Test", "before")));
                gui.configure(sampler);
                JTabbedPane tabs = find(gui, JTabbedPane.class);
                assertNotNull(tabs);
                assertEquals("HTTP Headers", tabs.getTitleAt(1));
                HeaderTablePanel panel = find(gui, HeaderTablePanel.class);
                assertNotNull(panel);
                JTable table = find(panel, JTable.class);
                assertNotNull(table);
                assertTrue(table.editCellAt(0, 1));
                ((JTextField) table.getEditorComponent()).setText("after");
                gui.modifyTestElement(sampler);
                assertEquals("after", sampler.getHeaders().get(0).getValue());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                SaveService.saveElement(sampler, output);
                WebSocketConnectSampler restored = (WebSocketConnectSampler) SaveService.loadElement(
                        new ByteArrayInputStream(output.toByteArray()));
                assertEquals("after", restored.getHeaders().get(0).getValue());
                gui.configure(restored);
                assertEquals("after", panel.getHeaders().get(0).getValue());
                gui.clearGui();
                WebSocketConnectSampler fresh = (WebSocketConnectSampler) gui.createTestElement();
                assertTrue(fresh.getHeaders().isEmpty());
                assertTrue(fresh.getIgnoreControlFrames());
                assertFalse(fresh.getSessionName().isEmpty());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private static List<String> values(List<Header> headers, String name) {
        return headers.stream().filter(header -> header.getName().equalsIgnoreCase(name)).map(Header::getValue).toList();
    }

    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
