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

package org.apache.jmeter.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;

class CurlRequestTest {
    public static class HttpResult extends SampleResult {
        String body = "";
        String cookies = "session=abc";

        public String getHTTPMethod() {
            return "POST";
        }

        public String getQueryString() {
            return body;
        }

        public String getCookies() {
            return cookies;
        }
    }

    private HttpResult result() throws Exception {
        HttpResult result = new HttpResult();
        result.setURL(URI.create("https://example.invalid/a?x=%20").toURL());
        result.setProtocolVersion("HTTP/1.1");
        return result;
    }

    @Test
    void selectsRecordedProtocolAndDoesNotDuplicateCookies() throws Exception {
        for (String[] version : List.of(new String[]{"HTTP/1.0", "--http1.0"},
                new String[]{"HTTP/1.1", "--http1.1"}, new String[]{"HTTP/2.0", "--http2-prior-knowledge"},
                new String[]{"HTTP/3", "--http3-only"})) {
            HttpResult result = result();
            result.setProtocolVersion(version[0]);
            result.setRequestHeaders("Cookie: captured=1\nX-Repeat: one\nX-Repeat: two\nEmpty: \n");
            String command = CurlRequest.from(result).command();
            assertTrue(command.contains(version[1]));
            assertTrue(command.contains("'Cookie: captured=1'"));
            assertTrue(command.contains("'--header' 'X-Repeat: one' \\\n  '--header' 'X-Repeat: two'"));
            assertTrue(command.contains("'Empty;'"));
            assertEquals(1, command.split("Cookie:", -1).length - 1);
        }
    }

    @Test
    void rejectsMissingOrOmittedRequestData() throws Exception {
        assertNull(CurlRequest.from(new SampleResult()));
        HttpResult result = result();
        result.body = "<actual file content, not shown here>";
        assertNull(CurlRequest.from(result));
        result.body = "";
        result.setRequestHeaders("Content-Type: text/plain; charset=nonexistent");
        assertNull(CurlRequest.from(result));
    }

    @Test
    void rejectsMissingBodyAndKeepsHeadSemantics() throws Exception {
        HttpResult result = result();
        result.setRequestHeaders("Content-Length: 42\n");
        assertNull(CurlRequest.from(result));
        HttpResult head = new HttpResult() {
            @Override
            public String getHTTPMethod() {
                return "HEAD";
            }
        };
        head.setURL(result.getURL());
        assertTrue(CurlRequest.from(head).command().contains("'--head'"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false, UTF-8", "true, UTF-8",
            "false, ISO-8859-1", "true, ISO-8859-1"})
    @Timeout(20)
    void exportedCommandDecodesGzipWithoutChangingRequest(boolean recordedAcceptEncoding, String charset) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        try {
            Assumptions.assumeTrue(new ProcessBuilder("curl", "--version").start().waitFor() == 0);
        } catch (IOException ex) {
            Assumptions.abort("curl is not installed");
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("Decoded response".getBytes(StandardCharsets.UTF_8));
        }
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            CompletableFuture<List<String>> received = CompletableFuture.supplyAsync(() -> {
                List<String> requests = new ArrayList<>();
                try {
                    for (int i = 0; i < 1; i++) {
                        try (var socket = server.accept()) {
                            socket.setSoTimeout(10000);
                            var input = socket.getInputStream();
                            StringBuilder headers = new StringBuilder();
                            while (!headers.toString().endsWith("\r\n\r\n")) {
                                int next = input.read();
                                if (next < 0) {
                                    throw new IllegalStateException("Unexpected EOF");
                                }
                                headers.append((char) next);
                            }
                            int length = headers.toString().lines()
                                    .filter(line -> line.startsWith("Content-Length:"))
                                    .mapToInt(line -> Integer.parseInt(line.substring(15).trim())).findFirst().orElse(0);
                            requests.add(headers + new String(input.readNBytes(length), StandardCharsets.ISO_8859_1));
                            socket.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\nContent-Encoding: gzip\r\n"
                                    + "Content-Length: " + compressed.size() + "\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            compressed.writeTo(socket.getOutputStream());
                        }
                    }
                    return requests;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            HttpResult result = result();
            result.setURL(URI.create("http://localhost:" + server.getLocalPort() + "/a/../b?x=%20").toURL());
            result.body = "@café'$(echo unsafe)\r\n";
            result.setRequestHeaders("Content-Type: text/plain; charset=" + charset + "\nX-Quote: a'b$(echo unsafe)\n"
                    + (recordedAcceptEncoding ? "Accept-Encoding: gzip, deflate\n" : ""));
            CurlRequest request = CurlRequest.from(result);
            assertNotNull(request);
            if (charset.equals("UTF-8")) {
                assertTrue(request.command().startsWith("curl "));
                assertTrue(request.command().contains("--data-raw "));
                assertFalse(request.command().contains("printf"));
            }
            Process exported = new ProcessBuilder("sh", "-c", request.command()).redirectErrorStream(true).start();
            assertTrue(new String(exported.getInputStream().readAllBytes(), StandardCharsets.UTF_8).endsWith("Decoded response"));
            assertEquals(0, exported.waitFor());
            List<String> requests = received.get(10, TimeUnit.SECONDS);
            if (recordedAcceptEncoding) {
                assertTrue(requests.get(0).contains("Accept-Encoding: gzip, deflate\r\n"));
            } else {
                assertFalse(requests.get(0).contains("Accept-Encoding:"));
            }
            assertTrue(requests.get(0).startsWith("POST /a/../b?x=%20 HTTP/1.1\r\n"));
            assertTrue(requests.get(0).contains("Cookie: session=abc\r\n"));
            assertTrue(requests.get(0).contains("X-Quote: a'b$(echo unsafe)\r\n"));
            assertTrue(requests.get(0).endsWith(new String(
                    result.body.getBytes(java.nio.charset.Charset.forName(charset)), StandardCharsets.ISO_8859_1)));
        }
    }
}
