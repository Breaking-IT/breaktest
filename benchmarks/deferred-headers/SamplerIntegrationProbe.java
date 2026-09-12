/*
 * Copyright 2024-2026 Breaking IT
 *
 * Licensed under the BreakTest Community Source License 1.0.
 * You may not use this file except in compliance with that license.
 * See the LICENSE file at the root of this distribution.
 */

package org.apache.jmeter.protocol.http.sampler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.util.JMeterUtils;

/** Bounded loopback smoke test for the default and explicit property settings, H1 and H2 facade with H1 fallback. */
public class SamplerIntegrationProbe {
    public static void main(String[] args) throws Exception {
        JMeterUtils.setJMeterHome(System.getProperty("user.dir"));
        JMeterUtils.loadJMeterProperties("bin/jmeter.properties");
        if ("default".equals(args[0])) {
            JMeterUtils.getJMeterProperties().remove("httpclient5.defer_diagnostic_headers");
        } else {
            JMeterUtils.setProperty("httpclient5.defer_diagnostic_headers", args[0]);
        }
        JMeterUtils.setLocale(java.util.Locale.ENGLISH);
        boolean deferred = "default".equals(args[0]) || Boolean.parseBoolean(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().add("X-Synthetic", path);
            exchange.getResponseHeaders().add("Set-Cookie", "synthetic=abc; Path=/");
            if (path.equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", "/plain");
            }
            int status = (path.equals("/redirect") || path.equals("/badredirect")) ? 302 : path.equals("/error") ? 500 : 200;
            byte[] body = "synthetic body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            for (String protocol : new String[] {HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1,
                    HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT}) {
                HTTPSamplerProxy config = new HTTPSamplerProxy();
                config.setHttpProtocol(protocol);
                config.setFollowRedirects(true);
                config.setUseKeepAlive(true);
                config.setConnectTimeout("2000");
                config.setResponseTimeout("2000");
                HeaderManager headers = new HeaderManager();
                headers.add(new org.apache.jmeter.protocol.http.control.Header("X-Request", "synthetic"));
                config.setHeaderManager(headers);
                CookieManager cookies = new CookieManager();
                cookies.testStarted();
                config.setCookieManager(cookies);
                try {
                    for (String path : new String[] {"/plain", "/redirect", "/error", "/badredirect"}) {
                        HTTPSampleResult result = config.sample(URI.create("http://127.0.0.1:"
                                + server.getAddress().getPort() + path).toURL(), "GET", false, 0);
                        check(result.isSuccessful() == !(path.equals("/error") || path.equals("/badredirect")), result.getResponseCode());
                        if (!path.equals("/redirect")) {
                            var snapshotField = HTTPSampleResult.class.getDeclaredField("deferredResponseHeaders");
                            snapshotField.setAccessible(true);
                            DeferredHttpHeaders snapshot = (DeferredHttpHeaders) snapshotField.get(result);
                            check((snapshot != null) == deferred, "Property did not control capture");
                            if (snapshot != null) {
                                check(!snapshot.isMaterialized(), "Headers unexpectedly read during sampling");
                            }
                        } else {
                            check(result.getSubResults().length == 2, "Missing redirect subresults");
                            for (var sub : result.getSubResults()) {
                                check(sub.getResponseHeaders().toLowerCase().contains("x-synthetic:"),
                                        "Subresult lost headers");
                            }
                        }
                        check(result.getRequestHeaders().contains("X-Request: synthetic"), "Request headers missing");
                        check(!result.getRequestHeaders().contains("Cookie:"), "Cookie leaked into diagnostic headers");
                        check(result.getResponseHeaders().toLowerCase().contains("x-synthetic:"), "Response headers missing");
                        if (path.equals("/badredirect")) {
                            check(result.getHeadersSize() == 0, "Malformed redirect accounting changed");
                            check(result.getResponseMessage().contains("Missing location header"), "Missing error diagnostics");
                        } else {
                            check(result.getHeadersSize() > 0 && result.getBodySizeAsLong() > 0, "Missing accounting");
                        }
                        check("synthetic=abc".equals(cookies.getCookieHeaderForURL(result.getURL())), "Cookie manager changed");
                    }
                } finally {
                    config.threadFinished();
                }
                System.out.println("PASS setting=" + args[0] + " deferred=" + deferred + " protocol=" + protocol
                        + " success/error/redirect/cookies/accounting");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
