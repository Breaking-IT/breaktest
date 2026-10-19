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

package org.apache.jmeter.readiness;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in readiness evidence and bounds its size. Evidence stays in memory and is never logged at INFO or
 * above, but is shown on screen, so header values of {@code Authorization}, {@code Proxy-Authorization},
 * {@code Cookie} and {@code Set-Cookie}, and values of parameters and JSON keys whose name contains
 * {@code pass|pwd|secret|token|key|auth|session}, are masked.
 */
public final class Redactor {
    /** Characters kept on each side of the place an excerpt is about */
    public static final int EXCERPT_RADIUS = 40;
    /** Maximum characters of evidence per finding */
    public static final int MAX_EVIDENCE = 512;
    public static final String MASK = "***"; // $NON-NLS-1$

    private static final String SENSITIVE = "[\\w.\\-]*(?:pass|pwd|secret|token|key|auth|session)[\\w.\\-]*"; // $NON-NLS-1$
    private static final Pattern HEADER = Pattern.compile(
            "(?im)^(\\s*(?:proxy-authorization|authorization|set-cookie|cookie)\\s*:\\s*)(.*)$"); // $NON-NLS-1$
    private static final Pattern JSON_VALUE = Pattern.compile(
            "(?i)(\"" + SENSITIVE + "\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)"); // $NON-NLS-1$
    private static final Pattern PARAMETER = Pattern.compile(
            "(?i)((?:^|[?&;\\s,])" + SENSITIVE + "\\s*=\\s*)([^&;\\s,\"]*)"); // $NON-NLS-1$

    private Redactor() {
    }

    /**
     * @param text request or response text, headers included
     * @return the text with secrets masked
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = replace(HEADER, text);
        result = replace(JSON_VALUE, result);
        return replace(PARAMETER, result);
    }

    /**
     * @param text text to take an excerpt from
     * @param index position the excerpt is about
     * @param length length of the part at that position
     * @return a redacted excerpt of about {@value #EXCERPT_RADIUS} characters around the part
     */
    public static String excerpt(String text, int index, int length) {
        if (text == null || text.isEmpty() || index < 0) {
            return "";
        }
        // Redact a wider window first, so a value straddling the excerpt edge is still recognized
        int windowStart = Math.max(0, index - EXCERPT_RADIUS * 4);
        int windowEnd = Math.min(text.length(), index + length + EXCERPT_RADIUS * 4);
        String window = text.substring(windowStart, windowEnd);
        String redacted = redact(window);
        int relative = index - windowStart;
        // Redaction can change lengths; locate the excerpt proportionally when it did
        if (redacted.length() != window.length()) {
            relative = (int) ((long) relative * redacted.length() / Math.max(1, window.length()));
        }
        int start = Math.max(0, relative - EXCERPT_RADIUS);
        int end = Math.min(redacted.length(), relative + length + EXCERPT_RADIUS);
        String excerpt = redacted.substring(start, end).replace('\r', ' ').replace('\n', ' ');
        return bound((start > 0 || windowStart > 0 ? "…" : "") + excerpt
                + (end < redacted.length() || windowEnd < text.length() ? "…" : ""));
    }

    /**
     * @param text evidence
     * @return the text cut to {@value #MAX_EVIDENCE} characters
     */
    public static String bound(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_EVIDENCE ? text : text.substring(0, MAX_EVIDENCE - 1) + "…";
    }

    private static String replace(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        do {
            String value = matcher.group(2);
            String masked = value.startsWith("\"") ? "\"" + MASK + "\"" : MASK;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1) + masked));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }
}
