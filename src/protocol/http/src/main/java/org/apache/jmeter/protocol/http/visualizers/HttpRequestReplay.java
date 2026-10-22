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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.util.HttpProtocolDisplay;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.sampler.Http3RuntimeSupport;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.RequestReplay;

import com.google.auto.service.AutoService;

/** Replays captured HTTP requests through the same clients as HTTP Request samplers. */
@AutoService(RequestReplay.class)
public class HttpRequestReplay implements RequestReplay {
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=\\s*[\"]?([^;\\s\"]+)");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    @Override
    public Callable<SampleResult> prepare(SampleResult result) {
        if (!(result instanceof HTTPSampleResult http)) {
            return null;
        }
        try {
            HTTPSamplerProxy snapshot = createSampler(http);
            return () -> {
                HTTPSamplerProxy sampler = (HTTPSamplerProxy) snapshot.clone();
                if (sampler.isHttp3Protocol() && !Http3RuntimeSupport.isHttp3Supported()) {
                    throw new IllegalStateException("HTTP/3 replay requires a Java runtime with BreakTest HTTP/3 support (Java 27 or later).");
                }
                try {
                    SampleResult response = sampler.sample();
                    response.setThreadName("Replay");
                    return response;
                } finally {
                    sampler.threadFinished();
                }
            };
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static HTTPSamplerProxy createSampler(HTTPSampleResult result) {
        String url = result.getUrlAsString();
        String method = result.getHTTPMethod();
        String body = result.getQueryString();
        if (!(url.startsWith("http://") || url.startsWith("https://"))
                || method == null || !TOKEN.matcher(method).matches() || body == null
                || body.contains("<actual file content, not shown here>") || body.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Incomplete captured HTTP request");
        }
        String protocol = result.getProtocolVersion();
        if (protocol == null || protocol.isBlank()) {
            String status = result.getResponseHeaders();
            protocol = status.regionMatches(true, 0, "HTTP/", 0, 5) ? status.split("[ \r\n]", 2)[0] : "HTTP/1.1";
        }
        protocol = HttpProtocolDisplay.version(protocol);
        if (!Set.of("HTTP/1.0", "HTTP/1.1", "HTTP/2", "HTTP/3").contains(protocol)) {
            throw new IllegalArgumentException("Unsupported captured HTTP version");
        }
        HeaderManager headers = new HeaderManager();
        Charset charset = StandardCharsets.UTF_8;
        boolean declaredCharset = false;
        boolean hasCookies = false;
        long contentLength = -1;
        for (String line : result.getRequestHeaders().split("\\r?\\n")) {
            if (line.isBlank() || line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid captured header");
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            if (!TOKEN.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid captured header name");
            }
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (lowerName.equals("content-length")) {
                contentLength = Long.parseLong(value);
            }
            if (lowerName.equals("content-type")) {
                var match = CHARSET.matcher(value);
                if (match.find()) {
                    charset = Charset.forName(match.group(1));
                    declaredCharset = true;
                }
            }
            hasCookies |= lowerName.equals("cookie");
            // The client generates framing headers for the reconstructed body.
            if (!Set.of("content-length", "transfer-encoding", "connection").contains(lowerName)) {
                headers.add(new Header(name, value));
            }
        }
        if (!charset.newEncoder().canEncode(body)
                || !declaredCharset && contentLength < 0 && !StandardCharsets.US_ASCII.newEncoder().canEncode(body)
                || contentLength >= 0 && contentLength != body.getBytes(charset).length) {
            throw new IllegalArgumentException("Captured request body bytes are unavailable");
        }
        String cookies = result.getCookies();
        if (cookies != null && !cookies.isEmpty() && !hasCookies) {
            if (cookies.indexOf('\r') >= 0 || cookies.indexOf('\n') >= 0 || cookies.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid captured cookies");
            }
            headers.add(new Header("Cookie", cookies));
        }
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Replay: " + result.getSampleLabel());
        sampler.setMethod(method);
        sampler.setPath(url);
        sampler.setHttpProtocol(protocol);
        sampler.setHeaderManager(headers);
        sampler.setContentEncoding(charset.name());
        sampler.setPostBodyRaw(true);
        if (!body.isEmpty()) {
            sampler.addNonEncodedArgument("", body, "");
        }
        sampler.setFollowRedirects(false);
        sampler.setAutoRedirects(false);
        sampler.setConnectTimeout("60000");
        sampler.setResponseTimeout("60000");
        return sampler;
    }
}
