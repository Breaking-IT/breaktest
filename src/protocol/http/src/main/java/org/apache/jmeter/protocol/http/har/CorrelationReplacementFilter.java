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

package org.apache.jmeter.protocol.http.har;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.RequestLocation;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.RecordedValueReplacer;
import org.apache.jmeter.protocol.http.util.RecordedValueReplacer.Match;

/** Restricts literal replacement without replacing regex matches or field names themselves. */
final class CorrelationReplacementFilter {
    enum Scope {
        ALL("All"), PARAMETERS("Parameters"), QUERY_PARAMETERS("Query parameters"),
        POST_PARAMETERS("Form parameters"), PATH("Path"), BODY("Body"), HEADERS("Headers");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        boolean includes(RequestLocation location) {
            return this == ALL || switch (location) {
            case URL_PATH -> this == PATH;
            case QUERY_PARAMETER -> this == PARAMETERS || this == QUERY_PARAMETERS;
            case POST_PARAMETER -> this == PARAMETERS || this == POST_PARAMETERS;
            case REQUEST_BODY -> this == BODY;
            case REQUEST_HEADER -> this == HEADERS;
            };
        }
    }

    static final CorrelationReplacementFilter ALL = new CorrelationReplacementFilter(Scope.ALL, false, "");
    private final Set<Scope> scopes;
    private final boolean wholeValue;
    private final String fieldPattern;
    private final Pattern pattern;

    CorrelationReplacementFilter(Scope scope, boolean wholeValue, String fieldPattern) {
        this(Set.of(scope), wholeValue, fieldPattern);
    }

    CorrelationReplacementFilter(Set<Scope> scopes, boolean wholeValue, String fieldPattern) {
        this.scopes = Collections.unmodifiableSet(scopes.isEmpty() || scopes.contains(Scope.ALL)
                ? EnumSet.of(Scope.ALL) : EnumSet.copyOf(scopes));
        this.wholeValue = wholeValue;
        this.fieldPattern = fieldPattern;
        pattern = fieldPattern.isEmpty() ? null : Pattern.compile(fieldPattern);
    }

    boolean unrestricted() {
        return scopes.contains(Scope.ALL) && !wholeValue && pattern == null;
    }

    Set<Scope> scopes() {
        return scopes;
    }

    boolean includes(RequestLocation location) {
        return scopes.stream().anyMatch(scope -> scope.includes(location));
    }

    boolean wholeValue() {
        return wholeValue;
    }

    String fieldPattern() {
        return fieldPattern;
    }

    List<Match> matches(String text, String value, String reference, boolean decoded,
            RequestLocation location, String name) {
        if (!includes(location) || text == null) {
            return List.of();
        }
        List<Match> matches = RecordedValueReplacer.matches(text, value, reference, decoded);
        if (wholeValue) {
            matches = matches.stream().filter(match -> match.start() == 0 && match.end() == text.length()).toList();
        }
        if (pattern == null || matches.isEmpty()) {
            return matches;
        }
        String prefix = name.isEmpty() ? "" : name + (location == RequestLocation.REQUEST_HEADER ? ": " : "=");
        List<int[]> regions = new ArrayList<>();
        var valueMatcher = pattern.matcher(text);
        while (valueMatcher.find()) {
            regions.add(new int[] {valueMatcher.start(), valueMatcher.end()});
        }
        var matcher = pattern.matcher(prefix + text);
        while (matcher.find()) {
            regions.add(new int[] {matcher.start() - prefix.length(), matcher.end() - prefix.length()});
        }
        return matches.stream().filter(match -> regions.stream().anyMatch(region ->
                match.start() >= region[0] && match.end() <= region[1])).toList();
    }

    List<Match> urlMatches(String text, String value, String reference, RequestLocation location) {
        List<Match> result = new ArrayList<>();
        int query = text.indexOf('?');
        int pathEnd = query < 0 ? text.length() : query;
        int scheme = text.indexOf("://");
        int pathStart = scheme < 0 ? 0 : text.indexOf('/', scheme + 3);
        if (pathStart < 0 || pathStart > pathEnd) {
            pathStart = pathEnd;
        }
        if (location == RequestLocation.URL_PATH) {
            appendMatches(result, text.substring(pathStart, pathEnd), pathStart, value, reference, location, "");
        } else if (query >= 0) {
            int start = query + 1;
            while (start < text.length()) {
                int end = text.indexOf('&', start);
                if (end < 0) {
                    end = text.length();
                }
                int equals = text.indexOf('=', start);
                int valueStart = equals >= start && equals < end ? equals + 1 : start;
                String name = valueStart == start ? "" : text.substring(start, equals);
                appendMatches(result, text.substring(valueStart, end), valueStart, value, reference, location, name);
                start = end + 1;
            }
        }
        return result;
    }

    private void appendMatches(List<Match> result, String text, int offset, String value, String reference,
            RequestLocation location, String name) {
        for (Match match : matches(text, value, reference, false, location, name)) {
            result.add(new Match(offset + match.start(), offset + match.end(), match.reference()));
        }
    }

    List<Match> samplerMatches(Replaceable sampler, String field, int row, String text,
            String value, String reference, boolean decoded) {
        if (field.equals("Path") || field.equals("URL")) {
            List<Match> result = new ArrayList<>(urlMatches(text, value, reference, RequestLocation.URL_PATH));
            result.addAll(urlMatches(text, value, reference, RequestLocation.QUERY_PARAMETER));
            return result;
        }
        if (field.equals("Body")) {
            return matches(text, value, reference, false, RequestLocation.REQUEST_BODY, "");
        }
        if (!field.equals("Header value") && !field.equals("Parameter value")) {
            return List.of();
        }
        boolean header = field.equals("Header value");
        String nameField = header ? "Header name" : "Parameter name";
        String name = sampler.getReplaceableFields().stream().filter(item -> item.name().equals(nameField))
                .skip(row).map(item -> item.value()).findFirst().orElse("");
        RequestLocation location = header ? RequestLocation.REQUEST_HEADER : RequestLocation.QUERY_PARAMETER;
        if (!header && sampler instanceof HTTPSamplerBase http) {
            if (http.getPostBodyRaw() || http.getSendParameterValuesAsPostBody()) {
                location = RequestLocation.REQUEST_BODY;
                name = "";
            } else if (HarConverter.isBodyMethod(http.getMethod())) {
                location = RequestLocation.POST_PARAMETER;
            }
        }
        return matches(text, value, reference, decoded, location, name);
    }

    static String replace(String text, List<Match> matches) {
        StringBuilder result = new StringBuilder();
        int offset = 0;
        for (Match match : matches) {
            result.append(text, offset, match.start()).append(match.reference());
            offset = match.end();
        }
        return result.append(text, offset, text.length()).toString();
    }
}
