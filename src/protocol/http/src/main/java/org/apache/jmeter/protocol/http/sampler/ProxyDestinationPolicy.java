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

import java.net.IDN;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.hc.core5.net.InetAddressUtils;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.locale.ResourceKeyed;

/** Immutable, DNS-free hostname rules. No regex or per-destination cache is used. */
public final class ProxyDestinationPolicy {
    public enum Mode implements ResourceKeyed {
        ALL("proxy_filter_all"), INCLUDE("proxy_filter_include"),
        EXCLUDE("proxy_filter_exclude"), DIRECT("proxy_filter_direct");

        private final String resourceKey;

        Mode(String resourceKey) {
            this.resourceKey = resourceKey;
        }

        @Override
        public String getResourceKey() {
            return resourceKey;
        }

        public static Mode parse(String value) {
            if (value.isEmpty()) {
                return ALL;
            }
            for (Mode mode : values()) {
                if (mode.resourceKey.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unknown proxy destination mode: " + value);
        }
    }

    public static final String MODE_PROPERTY = "HTTPSampler.proxyDestinationMode";
    public static final String PATTERNS_PROPERTY = "HTTPSampler.proxyDestinationPatterns";

    private static final String[] SETTING_PROPERTIES = {MODE_PROPERTY, PATTERNS_PROPERTY,
            "HTTPSampler.proxyScheme", "HTTPSampler.proxyHost", "HTTPSampler.proxyUser", "HTTPSampler.proxyPass"};

    /** A nonempty setting makes this scope's routing policy authoritative. */
    public static boolean hasSettings(TestElement element) {
        for (String name : SETTING_PROPERTIES) {
            if (!element.getPropertyAsString(name).isBlank()) {
                return true;
            }
        }
        String port = element.getPropertyAsString("HTTPSampler.proxyPort");
        return !port.isBlank() && !"0".equals(port);
    }

    private final Mode mode;
    private final Set<String> exact;
    private final List<String> suffixes;

    private ProxyDestinationPolicy(Mode mode, Set<String> exact, List<String> suffixes) {
        this.mode = mode;
        this.exact = Set.copyOf(exact);
        this.suffixes = List.copyOf(suffixes);
    }

    public static ProxyDestinationPolicy compile(String modeValue, String patterns) {
        Mode mode = Mode.parse(modeValue);
        Set<String> exact = new HashSet<>();
        Set<String> suffixes = new LinkedHashSet<>();
        if (mode == Mode.INCLUDE || mode == Mode.EXCLUDE) {
            int lineNumber = 0;
            for (String line : patterns.split("\\R")) {
                lineNumber++;
                for (String entry : line.split("[,;]")) {
                    String value = entry.trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    try {
                        if (value.startsWith("*.")) {
                            String host = normalize(value.substring(2));
                            if (host.indexOf(':') >= 0 || InetAddressUtils.isIPv4(host)) {
                                throw new IllegalArgumentException("Wildcards require a DNS hostname");
                            }
                            suffixes.add("." + host);
                        } else {
                            exact.add(normalize(value));
                        }
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Proxy pattern on line " + lineNumber + ": " + e.getMessage(), e);
                    }
                }
            }
            if (exact.isEmpty() && suffixes.isEmpty()) {
                throw new IllegalArgumentException("Proxy inclusion/exclusion mode requires at least one hostname pattern");
            }
        }
        return new ProxyDestinationPolicy(mode, exact, List.copyOf(suffixes));
    }

    public boolean allowsProxy(String host) {
        if (mode == Mode.ALL) {
            return true;
        }
        if (mode == Mode.DIRECT) {
            return false;
        }
        String normalized = normalize(host);
        boolean matched = exact.contains(normalized);
        if (!matched) {
            for (String suffix : suffixes) {
                if (normalized.length() > suffix.length() && normalized.endsWith(suffix)) {
                    matched = true;
                    break;
                }
            }
        }
        return mode == Mode.INCLUDE ? matched : !matched;
    }

    /** Optional diagnostics, evaluated only by the editor or with debug logging enabled. */
    public String matchingPattern(String host) {
        if (mode == Mode.ALL || mode == Mode.DIRECT) {
            return "";
        }
        String normalized = normalize(host);
        if (exact.contains(normalized)) {
            return normalized;
        }
        for (String suffix : suffixes) {
            if (normalized.length() > suffix.length() && normalized.endsWith(suffix)) {
                return "*" + suffix;
            }
        }
        return "";
    }

    public Mode mode() {
        return mode;
    }

    private static String normalize(String value) {
        String host = value;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf(':') >= 0) {
            if (!InetAddressUtils.isIPv6(host)) {
                throw new IllegalArgumentException("Expected a hostname or IP literal, without a port");
            }
            // Numeric parsing only: this cannot perform a DNS lookup.
            try {
                return java.net.InetAddress.getByName(host).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IPv6 literal", e);
            }
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Hostname is empty");
        }
        // Most request hosts are already ASCII. Avoid IDN's temporary buffers on every sample.
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) > 127) {
                return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            }
        }
        int labelStart = 0;
        for (int i = 0; i <= host.length(); i++) {
            if (i == host.length() || host.charAt(i) == '.') {
                if (i == labelStart || i - labelStart > 63
                        || host.charAt(labelStart) == '-' || host.charAt(i - 1) == '-') {
                    throw new IllegalArgumentException("Invalid hostname label");
                }
                labelStart = i + 1;
            } else {
                char c = host.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                        && !(c >= '0' && c <= '9') && c != '-') {
                    throw new IllegalArgumentException("Expected a hostname or IP literal; only *.domain wildcards are supported");
                }
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
