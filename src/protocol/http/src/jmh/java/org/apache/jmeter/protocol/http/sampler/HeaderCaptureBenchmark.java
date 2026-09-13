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

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.message.BufferedHeader;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Capture, response accounting and independently selected header consumers. Synthetic ASCII fixtures. */
@State(Scope.Thread)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HeaderCaptureBenchmark {
    @Param({"false", "true"})
    public boolean deferred;
    @Param({"400", "1600", "8192"})
    public int totalBytes;
    @Param({"h1", "h2", "h3"})
    public String protocol;
    @Param({"unread", "request", "response", "both25", "both50", "both75", "both100"})
    public String readers;

    private BasicHttpRequest request;
    private BasicHttpResponse response;
    private HttpRequest javaRequest;
    private Map<String, List<String>> javaResponseHeaders;
    private int sequence;
    private int threshold;
    private boolean requestOnly;
    private boolean responseOnly;

    @Setup
    public void setup() throws Exception {
        request = new BasicHttpRequest("GET", "/synthetic");
        response = new BasicHttpResponse(200, "OK");
        response.setVersion("h1".equals(protocol) ? HttpVersion.HTTP_1_1 : HttpVersion.HTTP_2);
        int count = totalBytes == 400 ? 4 : 16;
        int cookieBytes = totalBytes / 4;
        request.addHeader("Cookie", "synthetic=" + "c".repeat(cookieBytes - "Cookie: synthetic=\n".length()));
        int requestBytes = totalBytes / 2 - cookieBytes;
        int responseBytes = totalBytes - totalBytes / 2;
        int framing = 0;
        for (int i = 0; i < count; i++) {
            framing += ("X-" + i + ": \n").length();
        }
        int requestValues = requestBytes - framing;
        int responseValues = responseBytes - framing
                - ("h1".equals(protocol) ? "HTTP/1.1 200 OK\n" : "HTTP/2 200\n").length();
        for (int i = 0; i < count; i++) {
            String name = "X-" + i;
            request.addHeader(name, "q".repeat(requestValues / count + (i < requestValues % count ? 1 : 0)));
            String value = "r".repeat(responseValues / count + (i < responseValues % count ? 1 : 0));
            if ("h1".equals(protocol)) {
                CharArrayBuffer buffer = new CharArrayBuffer(value.length() + 16);
                buffer.append(name + ": " + value);
                response.addHeader(BufferedHeader.create(buffer));
            } else {
                response.addHeader(name, value);
            }
        }
        if ("h3".equals(protocol)) {
            var builder = HttpRequest.newBuilder(URI.create("https://example.test/synthetic"));
            for (var header : request.getHeaders()) {
                builder.header(header.getName(), header.getValue());
            }
            javaRequest = builder.build();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (var header : response.getHeaders()) {
                headers.put(header.getName(), List.of(header.getValue()));
            }
            javaResponseHeaders = HttpHeaders.of(headers, (name, value) -> true).map();
        }
        HTTPSampleResult check = captureResult();
        if (check.getRequestHeaders().getBytes(StandardCharsets.UTF_8).length != requestBytes
                || check.getResponseHeaders().getBytes(StandardCharsets.UTF_8).length != responseBytes) {
            throw new IllegalStateException("Fixture byte counts changed");
        }
        threshold = readers.startsWith("both") ? Integer.parseInt(readers.substring(4)) : 0;
        requestOnly = "request".equals(readers);
        responseOnly = "response".equals(readers);
    }

    private HTTPSampleResult captureResult() {
        HTTPSampleResult result = new HTTPSampleResult();
        if ("h3".equals(protocol)) {
            HTTPJavaHttp3Impl.captureRequestHeaders(result, javaRequest, deferred);
            HTTPJavaHttp3Impl.captureResponseHeaders(result, "HTTP/3", 200, javaResponseHeaders, deferred);
            return result;
        }
        HTTPHC5Impl.captureRequestHeaders(result, request, deferred);
        if ("h1".equals(protocol)) {
            HTTPHC5Impl.captureResponseHeaders(result, response, deferred);
        } else {
            HTTPHC5H2Impl.captureResponseHeaders(result, response, deferred);
        }
        return result;
    }

    @Benchmark
    public HTTPSampleResult capture(Blackhole sink) {
        HTTPSampleResult result = captureResult();
        // Permute 0..99 so mixed consumers are interleaved, identically for eager/deferred.
        sequence = (sequence + 37) % 100;
        boolean both = sequence < threshold;
        if (requestOnly || both) {
            sink.consume(result.getRequestHeaders());
        }
        if (responseOnly || both) {
            sink.consume(result.getResponseHeaders());
        }
        sink.consume(result.getHeadersSize());
        return result;
    }
}
