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

package org.apache.jmeter.gui.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical protocol labels for recorded and sampled HTTP displays. */
public final class HttpProtocolDisplay {
    private static final Pattern STATUS_LINE = Pattern.compile("^HTTP/[23] ([0-9]{3})(?:[ \t]|\r?\n|$)");
    private static final Pattern PSEUDO_STATUS =
            Pattern.compile("(?m)^:status:[ \t]*([0-9]{3})[ \t]*(?:\r?\n|$)");

    private HttpProtocolDisplay() {
    }

    public static String version(String version) {
        if (version == null) {
            return "";
        }
        return switch (version.toUpperCase(Locale.ROOT)) {
        case "HTTP/1.0" -> "HTTP/1.0";
        case "HTTP/1.1" -> "HTTP/1.1";
        case "HTTP/2", "HTTP/2.0" -> "HTTP/2";
        case "HTTP/3", "HTTP/3.0" -> "HTTP/3";
        default -> version;
        };
    }

    public static String responseHeaders(String headers) {
        int end = 0;
        while (end < headers.length() && !Character.isWhitespace(headers.charAt(end))) {
            end++;
        }
        String normalized = version(headers.substring(0, end)) + headers.substring(end);
        var status = STATUS_LINE.matcher(normalized);
        if (!status.find()) {
            return normalized;
        }
        String code = status.group(1);
        // HTTP/2 and HTTP/3 clients may include both a synthetic status line and :status.
        // Only remove duplicates; conflicting status values must remain visible in diffs.
        return PSEUDO_STATUS.matcher(normalized)
                .replaceAll(match -> code.equals(match.group(1)) ? "" : match.group());
    }
}
