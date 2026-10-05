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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class ProxyDestinationRoutingTest extends JMeterTestCase {
    private HttpServer origin;
    private HttpServer proxy;
    private final AtomicInteger originRequests = new AtomicInteger();
    private final AtomicInteger leakedCredentials = new AtomicInteger();
    private final AtomicInteger proxyRequests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            originRequests.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey("Proxy-Authorization")) {
                leakedCredentials.incrementAndGet();
            }
            if (exchange.getRequestURI().getPath().equals("/page")) {
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                reply(exchange, 200, "<html><img src='http://proxy-only.invalid/image'></html>");
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", "http://proxy-only.invalid/done");
                reply(exchange, 302, "redirect");
            } else {
                reply(exchange, 200, "direct");
            }
        });
        proxy.createContext("/", exchange -> {
            proxyRequests.incrementAndGet();
            if (exchange.getRequestURI().getPath().equals("/auth-redirect")
                    && !"Basic dXNlcjpwYXNz".equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"proxy\"");
                reply(exchange, 407, "proxy authentication required");
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/redirect")
                    || exchange.getRequestURI().getPath().equals("/auth-redirect")) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + origin.getAddress().getPort() + "/done");
                reply(exchange, 302, "redirect");
            } else {
                reply(exchange, 200, "proxied");
            }
        });
        origin.start();
        proxy.start();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }

    @AfterEach
    void stop() {
        origin.stop(0);
        proxy.stop(0);
        JMeterContextService.getContext().clear();
    }

    private HTTPSamplerProxy sampler(String protocol, String host, String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setProtocol("http");
        sampler.setHttpProtocol(protocol);
        sampler.setDomain(host);
        sampler.setPort(host.equals("127.0.0.1") ? origin.getAddress().getPort() : 80);
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setProxyHost("127.0.0.1");
        sampler.setProxyPortInt(Integer.toString(proxy.getAddress().getPort()));
        sampler.setConnectTimeout("2000");
        sampler.setResponseTimeout("2000");
        sampler.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_include");
        sampler.setProperty("HTTPSampler.proxyDestinationPatterns", "proxy-only.invalid");
        return sampler;
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", ""})
    void automaticRedirectsSwitchRoutesInBothDirections(String protocol) {
        for (boolean startDirect : new boolean[] {true, false}) {
            var sampler = sampler(protocol, startDirect ? "127.0.0.1" : "proxy-only.invalid", "/redirect");
            sampler.setAutoRedirects(true);
            try {
                var result = sampler.sample();
                assertTrue(result.isSuccessful(), result::getResponseMessage);
                assertEquals(startDirect ? "proxied" : "direct", result.getResponseDataAsString());
            } finally {
                sampler.threadFinished();
            }
        }
        assertEquals(2, originRequests.get());
        assertEquals(2, proxyRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", ""})
    void changingRulesOnReusedClientsTakesEffect(String protocol) {
        var sampler = sampler(protocol, "127.0.0.1", "/done");
        try {
            assertEquals("direct", sampler.sample().getResponseDataAsString());
            sampler.setProperty("HTTPSampler.proxyDestinationPatterns", "127.0.0.1");
            assertEquals("proxied", sampler.sample().getResponseDataAsString());
            sampler.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_direct");
            assertEquals("direct", sampler.sample().getResponseDataAsString());
            sampler.setProperty("HTTPSampler.proxyDestinationMode", "proxy_filter_exclude");
            assertEquals("direct", sampler.sample().getResponseDataAsString());
        } finally {
            sampler.threadFinished();
        }
        assertEquals(3, originRequests.get());
        assertEquals(1, proxyRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", ""})
    void invalidRulesFailWithoutSendingTraffic(String protocol) {
        var sampler = sampler(protocol, "127.0.0.1", "/done");
        sampler.setProperty("HTTPSampler.proxyDestinationPatterns", "*");
        try {
            var result = sampler.sample();
            assertFalse(result.isSuccessful());
            assertTrue(result.getResponseMessage().contains("Proxy pattern"), result::getResponseMessage);
        } finally {
            sampler.threadFinished();
        }
        assertEquals(0, originRequests.get());
        assertEquals(0, proxyRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", ""})
    void proxyAuthenticationDoesNotLeakOnDirectRedirect(String protocol) {
        var sampler = sampler(protocol, "proxy-only.invalid", "/auth-redirect");
        sampler.setProxyUser("user");
        sampler.setProxyPass("pass");
        sampler.setAutoRedirects(true);
        try {
            var result = sampler.sample();
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("direct", result.getResponseDataAsString());
            assertTrue(proxyRequests.get() >= 2, "proxy must challenge and accept credentials");
            assertEquals(1, originRequests.get());
            assertEquals(0, leakedCredentials.get());
        } finally {
            sampler.threadFinished();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1", ""})
    void embeddedResourcesUseTheirOwnDestination(String protocol) {
        var sampler = sampler(protocol, "127.0.0.1", "/page");
        HTTPSamplerBase.registerParser("text/html", "org.apache.jmeter.protocol.http.parser.LagartoBasedHtmlParser");
        sampler.setImageParser(true);
        sampler.setConcurrentDwn(true);
        try {
            var result = sampler.sample();
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals(1, originRequests.get());
            assertEquals(1, proxyRequests.get());
        } finally {
            sampler.threadFinished();
        }
    }

    @Test
    void policyIsCachedButContextKeepsItsOwnSnapshot() throws Exception {
        var sampler = sampler("HTTP/1.1", "127.0.0.1", "/done");
        var impl = new HTTPHC5Impl(sampler);
        var context = HttpClientContext.create();
        var url = URI.create("http://example.com").toURL();
        var first = impl.resolveProxy(url, context);
        assertSame(first.policy(), impl.resolveProxy(url, null).policy());
        sampler.setProperty("HTTPSampler.proxyDestinationPatterns", "different.invalid");
        var second = impl.resolveProxy(url, null);
        assertFalse(second.policy().allowsProxy("proxy-only.invalid"));
        var endpoint = new HttpHost("http", "localhost", 8080);
        assertEquals(endpoint, HTTPHCAbstractImpl.selectProxy(endpoint,
                new HttpHost("http", "proxy-only.invalid", 80), context));
    }
}
