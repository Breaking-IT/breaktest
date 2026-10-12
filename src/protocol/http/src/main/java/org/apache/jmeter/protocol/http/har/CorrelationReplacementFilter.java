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
import org.apache.jmeter.gui.ReplaceableField;
import org.apache.jmeter.gui.RowField;
import org.apache.jmeter.gui.SearchArea;
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
            case REQUEST_OTHER -> false;
            };
        }
    }

    static final CorrelationReplacementFilter ALL = new CorrelationReplacementFilter(Scope.ALL, false, "");
    private final Set<Scope> scopes;
    private final boolean wholeValue;
    private final String fieldPattern;
    private final Pattern pattern;
    private final String validationError;
    private volatile String matchingError = "";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CorrelationReplacementFilter.class);

    CorrelationReplacementFilter(Scope scope, boolean wholeValue, String fieldPattern) {
        this(Set.of(scope), wholeValue, fieldPattern);
    }

    CorrelationReplacementFilter(Set<Scope> scopes, boolean wholeValue, String fieldPattern) {
        this(scopes, wholeValue, fieldPattern, false);
    }

    CorrelationReplacementFilter(Set<Scope> scopes, boolean wholeValue, String fieldPattern, boolean preserveInvalid) {
        this.scopes = Collections.unmodifiableSet(scopes.isEmpty() || scopes.contains(Scope.ALL)
                ? EnumSet.of(Scope.ALL) : EnumSet.copyOf(scopes));
        this.wholeValue = wholeValue;
        this.fieldPattern = fieldPattern;
        Pattern compiled = null;
        String error = "";
        try {
            compiled = fieldPattern.isEmpty() ? null : Pattern.compile(fieldPattern);
        } catch (java.util.regex.PatternSyntaxException ex) {
            if (!preserveInvalid) {
                throw ex;
            }
            error = ex.getDescription();
            LOG.warn("Retaining inactive correlation filter with invalid field pattern: {}", error);
        }
        pattern = compiled;
        validationError = error;
    }

    String diagnostic() {
        return validationError.isEmpty() ? matchingError : validationError;
    }

    void clearMatchingDiagnostic() {
        matchingError = "";
    }

    boolean unrestricted() {
        return validationError.isEmpty() && scopes.contains(Scope.ALL) && !wholeValue && pattern == null;
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
        if (!validationError.isEmpty() || !includes(location) || text == null) {
            return List.of();
        }
        List<Match> matches = RecordedValueReplacer.matches(text, value, reference, decoded);
        if (wholeValue) {
            matches = matches.stream().filter(match -> wholeMatch(text, match, location)).toList();
        }
        if (pattern == null || matches.isEmpty()) {
            return matches;
        }
        String prefix = name.isEmpty() ? "" : name + (location == RequestLocation.REQUEST_HEADER ? ": " : "=");
        List<int[]> regions = new ArrayList<>();
        var budget = new RegexBudget(text.length());
        try {
            collectRegions(text, 0, budget, regions);
            if (!prefix.isEmpty()) {
                collectRegions(prefix + text, prefix.length(), budget, regions);
            }
        } catch (PatternBudgetExceeded | StackOverflowError ex) {
            matchingError = "Field pattern exceeded its work limit; this field was skipped";
            LOG.warn(matchingError);
            return List.of();
        }
        regions.sort(java.util.Comparator.comparingInt(region -> region[0]));
        List<Match> accepted = new ArrayList<>();
        int regionIndex = 0;
        int regionEnd = -1;
        for (Match match : matches) {
            while (regionIndex < regions.size() && regions.get(regionIndex)[0] <= match.start()) {
                regionEnd = Math.max(regionEnd, regions.get(regionIndex++)[1]);
            }
            if (match.end() <= regionEnd) {
                accepted.add(match);
            }
        }
        return accepted;
    }

    private void collectRegions(String text, int offset, RegexBudget budget, List<int[]> regions) {
        var matcher = pattern.matcher(new BoundedText(text, budget));
        while (matcher.find()) {
            regions.add(new int[] {matcher.start() - offset, matcher.end() - offset});
        }
    }

    private static boolean wholeMatch(String text, Match match, RequestLocation location) {
        if (location == RequestLocation.URL_PATH) {
            return (match.start() == 0 || text.charAt(match.start() - 1) == '/')
                    && (match.end() == text.length() || text.charAt(match.end()) == '/');
        }
        if (location == RequestLocation.REQUEST_BODY) {
            return (match.start() == 0 || !tokenCharacter(text.charAt(match.start() - 1)))
                    && (match.end() == text.length() || !tokenCharacter(text.charAt(match.end())));
        }
        return match.start() == 0 && match.end() == text.length();
    }

    private static boolean tokenCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '%';
    }

    private static final class PatternBudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // Interrupt Java regex evaluation cooperatively, including catastrophic backtracking.
    private static final class RegexBudget {
        private long remaining;

        RegexBudget(int length) {
            remaining = Math.min(10_000_000L, Math.max(100_000L, length * 100L));
        }

        void read() {
            HarPredefinedCorrelation.checkCancelled();
            if (--remaining < 0) {
                throw new PatternBudgetExceeded();
            }
        }
    }

    private record BoundedText(String text, RegexBudget budget) implements CharSequence {
        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            budget.read();
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedText(text.substring(start, end), budget);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    List<Match> urlMatches(String text, String value, String reference, RequestLocation location) {
        List<Match> result = new ArrayList<>();
        var parts = urlParts(text);
        int query = parts.query();
        int pathEnd = parts.pathEnd();
        int pathStart = parts.pathStart();
        int urlEnd = parts.urlEnd();
        if (location == RequestLocation.URL_PATH) {
            appendMatches(result, text.substring(pathStart, pathEnd), pathStart, value, reference, location, "");
        } else if (location == RequestLocation.REQUEST_OTHER) {
            appendMatches(result, text.substring(0, pathStart), 0, value, reference, location, "");
            if (urlEnd < text.length()) {
                appendMatches(result, text.substring(urlEnd + 1), urlEnd + 1, value, reference, location, "");
            }
        } else if (query >= 0) {
            int start = query + 1;
            while (start < urlEnd) {
                int end = text.indexOf('&', start);
                if (end < 0 || end > urlEnd) {
                    end = urlEnd;
                }
                int equals = text.indexOf('=', start);
                if (equals < start || equals >= end) {
                    start = end + 1;
                    continue; // A query component without '=' is a name with an empty value.
                }
                int valueStart = equals + 1;
                String name = text.substring(start, equals);
                appendMatches(result, text.substring(valueStart, end), valueStart, value, reference, location, name);
                start = end + 1;
            }
        }
        return result;
    }

    private record UrlParts(int pathStart, int pathEnd, int query, int urlEnd) { }

    private static UrlParts urlParts(String text) {
        int fragment = text.indexOf('#');
        int urlEnd = fragment < 0 ? text.length() : fragment;
        int query = text.indexOf('?');
        if (query >= urlEnd) {
            query = -1;
        }
        int pathEnd = query < 0 ? urlEnd : query;
        int scheme = text.indexOf("://");
        int pathStart = scheme < 0 ? 0 : text.indexOf('/', scheme + 3);
        if (pathStart < 0 || pathStart > pathEnd) {
            pathStart = pathEnd;
        }
        return new UrlParts(pathStart, pathEnd, query, urlEnd);
    }

    static RequestLocation urlLocation(String text, int offset) {
        var parts = urlParts(text);
        if (offset < parts.pathStart() || offset >= parts.urlEnd()) {
            return RequestLocation.REQUEST_OTHER;
        }
        return parts.query() >= 0 && offset > parts.query()
                ? RequestLocation.QUERY_PARAMETER : RequestLocation.URL_PATH;
    }

    static String urlLocationName(String text, int offset) {
        if (urlLocation(text, offset) != RequestLocation.QUERY_PARAMETER) {
            return "";
        }
        int start = Math.max(text.lastIndexOf('&', offset), text.indexOf('?')) + 1;
        int equals = text.indexOf('=', start);
        return equals >= start && equals < offset ? text.substring(start, equals) : "";
    }

    /** Evaluate patterns on recorded text, then map accepted spans onto decoded argument values. */
    List<Match> decodedMatches(String recorded, String value, String reference, RequestLocation location, String name) {
        var allowed = matches(recorded, value, reference, false, location, name);
        if (allowed.isEmpty()) {
            allowed = matches(recorded, value, reference, true, location, name);
        }
        List<int[]> ranges = new ArrayList<>();
        for (var match : allowed) {
            ranges.add(new int[] {HarConverter.percentDecode(recorded.substring(0, match.start())).length(),
                    HarConverter.percentDecode(recorded.substring(0, match.end())).length()});
        }
        return RecordedValueReplacer.matches(HarConverter.percentDecode(recorded), value, reference, true).stream()
                .filter(match -> ranges.stream().anyMatch(range -> range[0] == match.start() && range[1] == match.end()))
                .toList();
    }

    List<Match> urlFieldMatches(String text, String value, String reference, boolean fullUrl) {
        if (unrestricted()) {
            // Default rules retain opaque URL matching, including bare queries and delimiter-spanning values.
            // Explicit name=value keys still remain literal.
            return RecordedValueReplacer.matches(text, value, reference, false).stream()
                    .filter(match -> !isQueryName(text, match)).toList();
        }
        List<Match> result = new ArrayList<>(urlMatches(text, value, reference, RequestLocation.URL_PATH));
        result.addAll(urlMatches(text, value, reference, RequestLocation.QUERY_PARAMETER));
        if (fullUrl) {
            result.addAll(urlMatches(text, value, reference, RequestLocation.REQUEST_OTHER));
        }
        result.sort(java.util.Comparator.comparingInt(Match::start));
        return result;
    }

    private static boolean isQueryName(String text, Match match) {
        var parts = urlParts(text);
        if (parts.query() < 0 || match.start() <= parts.query() || match.end() > parts.urlEnd()) {
            return false;
        }
        int start = Math.max(parts.query(), text.lastIndexOf('&', match.start() - 1)) + 1;
        int end = text.indexOf('&', start);
        if (end < 0 || end > parts.urlEnd()) {
            end = parts.urlEnd();
        }
        int equals = text.indexOf('=', start);
        return equals >= start && equals < end && match.end() <= equals;
    }

    private void appendMatches(List<Match> result, String text, int offset, String value, String reference,
            RequestLocation location, String name) {
        for (Match match : matches(text, value, reference, false, location, name)) {
            result.add(new Match(offset + match.start(), offset + match.end(), match.reference()));
        }
    }

    List<Match> samplerMatches(Replaceable sampler, List<ReplaceableField> fields, String field, int row, String text,
            String value, String reference, boolean decoded) {
        var descriptor = fields.stream().filter(item -> item.name().equals(field))
                .skip(row).findFirst().orElse(null);
        if (descriptor != null && descriptor.rowField() == RowField.NAME) {
            return List.of();
        }
        if (field.equals("Path") || field.equals("URL")) {
            return urlFieldMatches(text, value, reference, field.equals("URL"));
        }
        if (field.equals("Body") || descriptor != null && descriptor.area() == SearchArea.BODY) {
            return matches(text, value, reference, false, RequestLocation.REQUEST_BODY, "");
        }
        if (!field.equals("Header value") && !field.equals("Parameter value")) {
            RequestLocation location = descriptor == null ? RequestLocation.REQUEST_OTHER : switch (descriptor.area()) {
            case PATH -> RequestLocation.URL_PATH;
            case PARAMETERS -> RequestLocation.POST_PARAMETER;
            case HEADERS -> RequestLocation.REQUEST_HEADER;
            default -> RequestLocation.REQUEST_OTHER;
            };
            return matches(text, value, reference, decoded, location, "");
        }
        boolean header = field.equals("Header value");
        String nameField = header ? "Header name" : "Parameter name";
        String name = fields.stream().filter(item -> item.name().equals(nameField))
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
        return RecordedValueReplacer.replace(text, matches);
    }
}
