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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.samplers.SampleResult;

/** A snapshot of the HTTP request, for clipboard export. */
final class CurlRequest {
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=\\s*[\"]?([^;\\s\"]+)");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private final List<String> arguments;
    private final byte[] body;
    private final String url;

    private CurlRequest(List<String> arguments, byte[] body, String url) {
        this.arguments = List.copyOf(arguments);
        this.body = body;
        this.url = url;
    }

    static CurlRequest from(SampleResult result) {
        String method = RequestViewRaw.invokeStringMethod(result, "getHTTPMethod");
        String body = RequestViewRaw.invokeStringMethod(result, "getQueryString");
        String url = result.getUrlAsString();
        if (method == null || !TOKEN.matcher(method).matches() || body == null
                || !(url.startsWith("http://") || url.startsWith("https://"))
                || body.contains("<actual file content, not shown here>") || body.indexOf('\0') >= 0) {
            return null;
        }
        List<String> args = new ArrayList<>(List.of("curl", "--disable", "--globoff", "--path-as-is",
                "--silent", "--show-error", "--compressed", "--include", "--max-time", "60"));
        String protocol = RequestViewRaw.getHttpProtocolVersion(result).toUpperCase(Locale.ROOT);
        switch (protocol) {
        case "HTTP/1.0" -> args.add("--http1.0");
        case "HTTP/1.1" -> args.add("--http1.1");
        case "HTTP/2" -> args.add("--http2-prior-knowledge");
        case "HTTP/3" -> args.add("--http3-only");
        default -> {
            if (!protocol.isEmpty()) {
                return null;
            }
        }
        }
        args.addAll(List.of("--request", method, "--url", url));
        if (method.equals("HEAD")) {
            args.add("--head");
        }
        Set<String> headers = new HashSet<>();
        Charset encoding = StandardCharsets.UTF_8;
        boolean charsetDeclared = false;
        long contentLength = -1;
        for (String line : result.getRequestHeaders().split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            // curl generates HTTP/2 and HTTP/3 pseudo headers from the URL and method.
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || line.indexOf('\0') >= 0 || line.indexOf('\r') >= 0) {
                return null;
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!TOKEN.matcher(name).matches()) {
                return null;
            }
            headers.add(name);
            String value = line.substring(colon + 1).trim();
            args.addAll(List.of("--header", value.isEmpty() ? line.substring(0, colon) + ";" : line));
            if (name.equals("content-length")) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (name.equals("content-type")) {
                Matcher charset = CHARSET.matcher(value);
                if (charset.find()) {
                    try {
                        encoding = Charset.forName(charset.group(1));
                        charsetDeclared = true;
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                }
            }
        }
        String cookies = RequestViewRaw.getCookies(result);
        if (!cookies.isEmpty() && !headers.contains("cookie")) {
            args.addAll(List.of("--header", "Cookie: " + cookies));
        }
        // Suppress curl defaults that were absent from the captured request.
        for (String name : List.of("Accept", "Accept-Encoding", "User-Agent", "Content-Type", "Expect")) {
            if (!headers.contains(name.toLowerCase(Locale.ROOT))) {
                args.addAll(List.of("--header", name + ":"));
            }
        }
        if (!body.isEmpty()) {
            args.addAll(List.of("--data-binary", "@-"));
        }
        // Without a declared charset or a Content-Length to verify against, the sent bytes of a
        // non-ASCII body are unknown (samplers may have used ISO-8859-1), so do not guess.
        if (args.stream().anyMatch(value -> value.indexOf('\0') >= 0)
                || cookies.indexOf('\r') >= 0 || cookies.indexOf('\n') >= 0
                || !encoding.newEncoder().canEncode(body)
                || !charsetDeclared && contentLength < 0 && !StandardCharsets.US_ASCII.newEncoder().canEncode(body)) {
            return null;
        }
        byte[] bytes = body.getBytes(encoding);
        if (contentLength >= 0 && contentLength != bytes.length) {
            return null;
        }
        return new CurlRequest(args, bytes, url);
    }

    String url() {
        return url;
    }

    String command() {
        String text = new String(body, StandardCharsets.UTF_8);
        boolean inlineBody = text.indexOf('\0') < 0 && Arrays.equals(body, text.getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        Set<String> optionsWithValues = Set.of("--max-time", "--request", "--url", "--header", "--data-binary");
        var iterator = arguments.iterator();
        while (iterator.hasNext()) {
            String argument = iterator.next();
            if (argument.equals("--data-binary") && inlineBody) {
                iterator.next(); // Replace the stdin reference with the literal request body.
                lines.add("--data-raw " + quote(text));
                continue;
            }
            String line = argument.equals("curl") ? "curl" : quote(argument);
            if (optionsWithValues.contains(argument)) {
                line += " " + quote(iterator.next());
            }
            lines.add(line);
        }
        String command = String.join(" \\\n  ", lines);
        if (body.length == 0 || inlineBody) {
            return command;
        }
        StringBuilder escaped = new StringBuilder();
        for (byte value : body) {
            escaped.append(String.format(Locale.ROOT, "\\0%03o", value & 0xff));
        }
        return "printf '%b' " + quote(escaped.toString()) + " | " + command;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

}
