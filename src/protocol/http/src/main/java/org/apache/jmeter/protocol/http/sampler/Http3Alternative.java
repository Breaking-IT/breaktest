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
import java.net.URL;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** Conservative Alt-Svc discovery for the direct-authority JDK HTTP/3 API. */
final class Http3Alternative {
    private static final Pattern ENTRY = Pattern.compile(
            "(?:^|,)\\s*h3=\"([^\"]+)\"((?:\\s*;[^,]*)?)(?=,|$)");
    private static final Pattern MAX_AGE = Pattern.compile("(?:^|;)\\s*ma=(\\d+)\\s*(?=;|$)");

    private Http3Alternative() {
    }

    /** Null means no advertisement; zero means invalidate a previous advertisement. */
    static @Nullable Long expiry(URL origin, String headers, long now) {
        Long expiry = null;
        for (String line : headers.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0 || !line.substring(0, colon).equalsIgnoreCase("Alt-Svc")) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            if (value.equals("clear")) {
                return 0L;
            }
            // A replacement advertisement without a usable alternative invalidates the old one.
            expiry = 0L;
            var entries = ENTRY.matcher(value);
            while (entries.find()) {
                String authority = entries.group(1);
                try {
                    URI alternative = URI.create("https://" + (authority.startsWith(":")
                            ? origin.getHost() + authority : authority));
                    int port = origin.getPort() < 0 ? origin.getDefaultPort() : origin.getPort();
                    // Never rewrite the request URI: that would change SNI, certificate validation,
                    // cookies and :authority. The public JDK direct-H3 API cannot route alternatives
                    // at a different authority. Leave these origins on the measured TCP transport.
                    if (!origin.getHost().equalsIgnoreCase(alternative.getHost())
                            || alternative.getPort() != port || alternative.getRawUserInfo() != null
                            || !alternative.getRawPath().isEmpty() || alternative.getRawQuery() != null
                            || alternative.getRawFragment() != null) {
                        continue;
                    }
                    var maxAge = MAX_AGE.matcher(entries.group(2));
                    long seconds = maxAge.find() ? Long.parseLong(maxAge.group(1)) : 86400;
                    long candidate = Math.addExact(now, Math.multiplyExact(seconds, 1000));
                    expiry = Math.max(expiry, candidate);
                } catch (IllegalArgumentException | ArithmeticException ignored) {
                    // Malformed/unsupported alternatives do not change the request destination.
                }
            }
        }
        return expiry;
    }
}
