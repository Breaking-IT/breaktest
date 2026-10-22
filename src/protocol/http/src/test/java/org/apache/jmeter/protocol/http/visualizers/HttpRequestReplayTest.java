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

package org.apache.jmeter.protocol.http.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerFactory;
import org.apache.jmeter.protocol.http.sampler.Http3RuntimeSupport;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.RequestReplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sun.net.httpserver.HttpServer;

class HttpRequestReplayTest {
    @Test
    void serviceIsDiscoverable() {
        assertTrue(ServiceLoader.load(RequestReplay.class).stream()
                .anyMatch(provider -> provider.type() == HttpRequestReplay.class));
    }

    @ParameterizedTest
    @CsvSource({"HTTP/1.1, HTTPHC5Impl", "HTTP/2, HTTPHC5H2Impl", "HTTP/2.0, HTTPHC5H2Impl"})
    void usesExistingClientForRecordedProtocol(String protocol, String implementation) throws Exception {
        HTTPSampleResult result = request();
        result.setProtocolVersion(protocol);
        var sampler = HttpRequestReplay.createSampler(result);
        var client = HTTPSamplerFactory.getImplementation(sampler.getImplementation(), sampler);
        assertEquals(implementation, client.getClass().getSimpleName());
        assertFalse(sampler.getFollowRedirects());
        assertFalse(sampler.getAutoRedirects());
    }

    @Test
    void http3UsesJavaClientOrReportsUnsupportedRuntime() throws Exception {
        HTTPSampleResult result = request();
        result.setProtocolVersion("HTTP/3");
        if (Http3RuntimeSupport.isHttp3Supported()) {
            var sampler = HttpRequestReplay.createSampler(result);
            var client = HTTPSamplerFactory.getImplementation(sampler.getImplementation(), sampler);
            assertEquals("HTTPJavaHttp3Impl", client.getClass().getSimpleName());
        } else {
            var replay = new HttpRequestReplay().prepare(result);
            assertNotNull(replay);
            assertTrue(assertThrows(IllegalStateException.class, replay::call).getMessage().contains("HTTP/3"));
        }
    }

    @Test
    @Timeout(20)
    void replaysSnapshotWithEncodedBodyCookiesAndHeadersAndDisplaysHttpErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", exchange -> {
            String received = exchange.getRequestMethod() + "|" + exchange.getRequestURI().getRawQuery()
                    + "|" + exchange.getRequestHeaders().getFirst("Cookie")
                    + "|" + exchange.getRequestHeaders().getFirst("X-Test")
                    + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            byte[] bytes = received.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(403, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            HTTPSampleResult result = request();
            result.setURL(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/submit?q=%20").toURL());
            result.setRequestHeaders("Content-Type: text/plain; charset=ISO-8859-1\nContent-Length: 4\nX-Test: captured\n");
            result.setCookies("session=abc");
            result.setQueryString("café");
            var replay = new HttpRequestReplay().prepare(result);
            assertNotNull(replay);
            result.setQueryString("changed after selection");
            SampleResult response = replay.call();
            assertEquals("403", response.getResponseCode());
            assertEquals("Replay", response.getThreadName());
            assertEquals("Replay: original", response.getSampleLabel());
            assertTrue(response.getStartTime() > 0);
            assertEquals("POST|q=%20|session=abc|captured|café", response.getResponseDataAsString());
            assertTrue(response.getResponseHeaders().contains("403"));
            assertEquals("changed after selection", result.getQueryString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingBodyBytesAndNonHttpResults() throws Exception {
        HttpRequestReplay provider = new HttpRequestReplay();
        assertNull(provider.prepare(new SampleResult()));
        HTTPSampleResult result = request();
        result.setQueryString("<actual file content, not shown here>");
        assertNull(provider.prepare(result));
        result.setQueryString("café");
        assertNull(provider.prepare(result));
        result.setRequestHeaders("Content-Length: 100\n");
        assertNull(provider.prepare(result));
    }

    @Test
    @Timeout(20)
    void connectionFailuresRemainInspectableResults() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }
        HTTPSampleResult original = request();
        original.setURL(URI.create("http://127.0.0.1:" + port + "/unavailable").toURL());
        SampleResult failed = new HttpRequestReplay().prepare(original).call();
        assertFalse(failed.isSuccessful());
        assertEquals("Connection refused", failed.getResponseCode());
        assertEquals("Replay", failed.getThreadName());
        assertEquals("Replay: original", failed.getSampleLabel());
    }

    private static HTTPSampleResult request() throws Exception {
        HTTPSampleResult result = new HTTPSampleResult();
        result.setURL(URI.create("https://example.invalid/request").toURL());
        result.setHTTPMethod("POST");
        result.setSampleLabel("original");
        result.setProtocolVersion("HTTP/1.1");
        result.setQueryString("{}");
        result.setRequestHeaders("Content-Type: application/json\n");
        return result;
    }
}
