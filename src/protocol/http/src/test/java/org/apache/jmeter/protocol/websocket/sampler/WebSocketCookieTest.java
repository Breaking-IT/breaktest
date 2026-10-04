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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class WebSocketCookieTest extends JMeterTestCase {
    private static CookieManager manager() {
        CookieManager cookies = new CookieManager();
        cookies.testStarted();
        return cookies;
    }

    @Test
    void appliesHttpDomainPathSecureAndExpiryRulesToWebSocketUrls() throws Exception {
        CookieManager cookies = manager();
        URI login = URI.create("https://app.example.test/login");
        cookies.addCookieFromHeader("session=abc; Path=/chat; Secure; HttpOnly", login.toURL());
        cookies.add(new Cookie("expired", "old", "app.example.test", "/", false, 1));
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.addTestElement(cookies);
        assertEquals("session=abc", sampler.cookieHeader(URI.create("wss://app.example.test/chat/socket?room=1")));
        assertNull(sampler.cookieHeader(URI.create("ws://app.example.test/chat/socket")));
        assertNull(sampler.cookieHeader(URI.create("wss://app.example.test/chatty")));
        assertNull(sampler.cookieHeader(URI.create("wss://other.example.test/chat/socket")));
        assertNull(sampler.cookieHeader(URI.create("wss://child.app.example.test/chat/socket")));
    }

    @Test
    void supportsDomainCookiesAndReadsLatestSharedCookieState() throws Exception {
        CookieManager cookies = manager();
        URI login = URI.create("https://login.example.test/login");
        cookies.addCookieFromHeader("session=first; Domain=example.test; Path=/", login.toURL());
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.addTestElement(cookies);
        URI ws = URI.create("ws://chat.example.test:8080/");
        assertEquals("session=first", sampler.cookieHeader(ws));
        cookies.addCookieFromHeader("session=refreshed; Domain=example.test; Path=/", login.toURL());
        assertEquals("session=refreshed", sampler.cookieHeader(ws));
        assertNull(sampler.cookieHeader(URI.create("wss://example.test.attacker.test/")));
        cookies.clear();
        assertNull(sampler.cookieHeader(ws));
    }

    @Test
    void preservesEncodedPathsAndDoesNotShareCookiesBetweenUsers() throws Exception {
        URI login = URI.create("https://app.example.test/login");
        CookieManager first = manager();
        first.addCookieFromHeader("session=alice; Path=/chat%2Fprivate", login.toURL());
        CookieManager second = manager();
        second.addCookieFromHeader("session=bob; Path=/chat%2Fprivate", login.toURL());
        WebSocketConnectSampler alice = new WebSocketConnectSampler();
        alice.addTestElement(first);
        WebSocketConnectSampler bob = new WebSocketConnectSampler();
        bob.addTestElement(second);
        URI ws = URI.create("wss://app.example.test/chat%2Fprivate/socket");
        assertEquals("session=alice", alice.cookieHeader(ws));
        assertEquals("session=bob", bob.cookieHeader(ws));
        assertNull(alice.cookieHeader(URI.create("wss://app.example.test/chat/private/socket")));
        assertNull(new WebSocketConnectSampler().cookieHeader(ws));
    }

    @Test
    void cookieManagerIsAppliedThroughNormalTestPlanScopeAndRecovery() throws Exception {
        CookieManager cookies = manager();
        cookies.addCookieFromHeader("session=shared; Path=/", URI.create("http://example.test/login").toURL());
        ListedHashTree tree = new ListedHashTree();
        var plan = tree.add(new TestPlan());
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        var children = plan.add(group);
        children.add(cookies);
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        children.add(sampler);
        TestCompiler compiler = new TestCompiler(tree);
        tree.traverse(compiler);
        assertTrue(sampler.applies(cookies));
        assertTrue(sampler.applies(new HeaderManager()));
        URI ws = URI.create("ws://example.test/socket");
        for (int i = 0; i < 2; i++) {
            var pack = compiler.configureSampler(sampler);
            assertEquals("session=shared", sampler.cookieHeader(ws));
            compiler.done(pack);
            assertNull(sampler.cookieHeader(ws));
        }
    }
}
