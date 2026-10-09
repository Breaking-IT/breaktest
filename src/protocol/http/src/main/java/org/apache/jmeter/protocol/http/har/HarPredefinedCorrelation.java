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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.gui.RegexExtractorGui;
import org.apache.jmeter.extractor.json.jsonpath.JSONManager;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui;
import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;
import org.apache.jmeter.protocol.http.util.RecordedValueReplacer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.oro.text.regex.MatchResult;
import org.apache.oro.text.regex.PatternMatcherInput;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;

/** Predefined, evidence-backed correlations found in a recorded HTTP flow. */
final class HarPredefinedCorrelation {

    /**
     * Shortest extracted value that is accepted as evidence. Short values match by coincidence in
     * unrelated URLs and parameters, and replacing them mangles requests that never carried the
     * recorded value.
     */
    static final int MIN_CORRELATED_VALUE_LENGTH = 6;

    private static final List<String> NON_SCANNABLE_CONTENT_TYPES = List.of(
            "image/", "audio/", "video/", "font/", "text/css",
            "application/octet-stream", "application/pdf", "application/zip",
            "application/font", "application/x-font");

    enum ExtractorType {
        REGEX,
        JSON_PATH,
        BOUNDARY,
        CSS,
        XPATH,
        XPATH2,
        JSON_JMESPATH
    }

    enum ResponseField {
        BODY,
        HEADERS
    }

    enum RequestLocation {
        URL_PATH("URL path"),
        QUERY_PARAMETER("query parameter"),
        POST_PARAMETER("form parameter"),
        REQUEST_BODY("request body"),
        REQUEST_HEADER("request header");

        private final String displayName;

        RequestLocation(String displayName) {
            this.displayName = displayName;
        }

        String getDisplayName() {
            return displayName;
        }
    }

    static final class Rule {
        private final String id;
        private final String group;
        private final String name;
        private final String variableName;
        private final ExtractorType extractorType;
        private final ResponseField responseField;
        private final String expression;
        private final String template;
        private final int maxMatches;
        private final int minValueLength;
        private final Map<String, String> extractorSettings;
        private final String defaultValue;
        private final boolean emptyDefaultValue;
        private final boolean computeConcatenation;
        private final boolean failOnNoMatch;

        Rule(String id, String group, String name, String variableName, ExtractorType extractorType,
                ResponseField responseField, String expression, String template,
                String defaultValue, boolean emptyDefaultValue, boolean computeConcatenation,
                boolean failOnNoMatch) {
            this(id, group, name, variableName, extractorType, responseField, expression, template,
                    -1, defaultValue, emptyDefaultValue, computeConcatenation, failOnNoMatch);
        }

        Rule(String id, String group, String name, String variableName, ExtractorType extractorType,
                ResponseField responseField, String expression, String template, int maxMatches,
                String defaultValue, boolean emptyDefaultValue, boolean computeConcatenation,
                boolean failOnNoMatch) {
            this(id, group, name, variableName, extractorType, responseField, expression, template,
                    maxMatches, MIN_CORRELATED_VALUE_LENGTH, defaultValue, emptyDefaultValue,
                    computeConcatenation, failOnNoMatch);
        }

        Rule(String id, String group, String name, String variableName, ExtractorType extractorType,
                ResponseField responseField, String expression, String template, int maxMatches, int minValueLength,
                String defaultValue, boolean emptyDefaultValue, boolean computeConcatenation,
                boolean failOnNoMatch) {
            this(id, group, name, variableName, extractorType, responseField, expression, template,
                    maxMatches, minValueLength, defaultValue, emptyDefaultValue, computeConcatenation,
                    failOnNoMatch, Map.of());
        }

        Rule(String id, String group, String name, String variableName, ExtractorType extractorType,
                ResponseField responseField, String expression, String template, int maxMatches, int minValueLength,
                String defaultValue, boolean emptyDefaultValue, boolean computeConcatenation,
                boolean failOnNoMatch, Map<String, String> extractorSettings) {
            this.extractorSettings = Map.copyOf(extractorSettings);
            this.id = id;
            this.group = group;
            this.name = name;
            this.variableName = variableName;
            this.extractorType = extractorType;
            this.responseField = responseField;
            this.expression = expression;
            this.template = template;
            this.maxMatches = maxMatches;
            this.minValueLength = minValueLength;
            this.defaultValue = defaultValue;
            this.emptyDefaultValue = emptyDefaultValue;
            this.computeConcatenation = computeConcatenation;
            this.failOnNoMatch = failOnNoMatch;
        }

        String getId() {
            return id;
        }

        String getGroup() {
            return group;
        }

        String getName() {
            return name;
        }

        String getVariableName() {
            return variableName;
        }

        ExtractorType getExtractorType() {
            return extractorType;
        }

        ResponseField getResponseField() {
            return responseField;
        }

        String getExpression() {
            return expression;
        }

        String getTemplate() {
            return template;
        }

        int getMaxMatches() {
            return maxMatches;
        }

        Map<String, String> getExtractorSettings() {
            return extractorSettings;
        }

        Rule withExtractorSettings(Map<String, String> settings) {
            return new Rule(id, group, name, variableName, extractorType, responseField, expression, template,
                    maxMatches, minValueLength, defaultValue, emptyDefaultValue, computeConcatenation, failOnNoMatch, settings);
        }

        Rule withGroup(String group) {
            return new Rule(id, group, name, variableName, extractorType, responseField, expression, template,
                    maxMatches, minValueLength, defaultValue, emptyDefaultValue, computeConcatenation,
                    failOnNoMatch, extractorSettings);
        }

        Rule withMinValueLength(int minValueLength) {
            return new Rule(id, group, name, variableName, extractorType, responseField, expression, template,
                    maxMatches, minValueLength, defaultValue, emptyDefaultValue, computeConcatenation,
                    failOnNoMatch, extractorSettings);
        }

        int getMinValueLength() {
            return minValueLength;
        }

        String getDefaultValue() {
            return defaultValue;
        }

        boolean isEmptyDefaultValue() {
            return emptyDefaultValue;
        }

        boolean isComputeConcatenation() {
            return computeConcatenation;
        }

        boolean isFailOnNoMatch() {
            return failOnNoMatch;
        }

    }

    static final class Replacement {
        private final int targetEntryIndex;
        private final String requestMethod;
        private final String requestUrl;
        private final RequestLocation location;
        private final String locationName;
        private final String matchedLiteral;

        Replacement(int targetEntryIndex, String requestMethod, String requestUrl,
                RequestLocation location, String locationName, String matchedLiteral) {
            this.targetEntryIndex = targetEntryIndex;
            this.requestMethod = requestMethod;
            this.requestUrl = requestUrl;
            this.location = location;
            this.locationName = locationName;
            this.matchedLiteral = matchedLiteral;
        }

        int getTargetEntryIndex() {
            return targetEntryIndex;
        }

        String getRequestMethod() {
            return requestMethod;
        }

        String getRequestUrl() {
            return requestUrl;
        }

        RequestLocation getLocation() {
            return location;
        }

        String getLocationName() {
            return locationName;
        }

        String getMatchedLiteral() {
            return matchedLiteral;
        }
    }

    private final Rule rule;
    private final String variableName;
    private final int sourceEntryIndex;
    private final String sourceUrl;
    private final String extractedValue;
    private final int matchNumber;
    private final List<Replacement> replacements;

    private HarPredefinedCorrelation(Rule rule, String variableName, int sourceEntryIndex,
            String sourceUrl, String extractedValue, int matchNumber, List<Replacement> replacements) {
        this.rule = rule;
        this.variableName = variableName;
        this.sourceEntryIndex = sourceEntryIndex;
        this.sourceUrl = sourceUrl;
        this.extractedValue = extractedValue;
        this.matchNumber = matchNumber;
        this.replacements = List.copyOf(replacements);
    }

    HarPredefinedCorrelation withReplacements(List<Replacement> selected) {
        return new HarPredefinedCorrelation(rule, variableName, sourceEntryIndex, sourceUrl,
                extractedValue, matchNumber, selected);
    }

    Rule getRule() {
        return rule;
    }

    /**
     * The variable this correlation writes. A rule can match several responses holding different
     * values, and every one of those extractions needs its own variable: sharing the rule's name
     * would make each extractor overwrite the previous one, and a request replaced with the shared
     * name would send whichever value happened to be extracted last.
     */
    String getVariableName() {
        return variableName;
    }

    int getSourceEntryIndex() {
        return sourceEntryIndex;
    }

    String getSourceUrl() {
        return sourceUrl;
    }

    String getExtractedValue() {
        return extractedValue;
    }

    int getMatchNumber() {
        return matchNumber;
    }

    List<Replacement> getReplacements() {
        return replacements;
    }

    static List<Rule> rules() {
        return HarCorrelationRuleCatalog.sharedRules();
    }

    static List<HarPredefinedCorrelation> find(
            List<HarEntry> entries, Set<String> selectedHostnames) {
        return find(entries, selectedHostnames, rules());
    }

    static List<HarPredefinedCorrelation> find(
            List<HarEntry> entries, Set<String> selectedHostnames, List<Rule> rules) {
        return find(entries.stream()
                .filter(entry -> selectedHostnames.contains(HarConverter.hostnameOf(entry.getUrl())))
                .toList(), rules);
    }

    static List<HarPredefinedCorrelation> find(List<HarEntry> entries) {
        return find(entries, rules());
    }

    static List<HarPredefinedCorrelation> find(List<HarEntry> entries, List<Rule> rules) {
        return find(entries, rules, ignored -> { });
    }

    static List<HarPredefinedCorrelation> find(
            List<HarEntry> entries, List<Rule> rules, IntConsumer progress) {
        checkCancelled();
        progress.accept(0);
        List<HarEntry> selected = entries.stream()
                .sorted(Comparator.comparingDouble(HarEntry::getStartMs)
                        .thenComparingInt(HarEntry::getOriginalIndex))
                .toList();
        List<Candidate> candidates = new ArrayList<>();
        JSONManager jsonManager = new JSONManager();
        boolean hasJsonRules = rules.stream().anyMatch(rule -> rule.getExtractorType() == ExtractorType.JSON_PATH);
        for (int sourcePosition = 0; sourcePosition < selected.size(); sourcePosition++) {
            checkCancelled();
            HarEntry source = selected.get(sourcePosition);
            String responseHeaders = responseHeaders(source);
            boolean scanBody = hasScannableBody(source);
            Object jsonDocument = scanBody && hasJsonRules
                    ? parseJsonBody(source.getResponseContentText(), jsonManager) : null;
            for (Rule rule : rules) {
                checkCancelled();
                if (!scanBody && rule.getResponseField() == ResponseField.BODY) {
                    continue;
                }
                List<ExtractedValue> extractedValues = extract(
                        rule, source.getResponseContentText(), responseHeaders, jsonManager, jsonDocument);
                CandidateMatch matched = findMatchingCandidate(selected, sourcePosition, extractedValues, rule.getMinValueLength());
                if (matched == null) {
                    continue;
                }
                candidates.add(new Candidate(rule, sourcePosition, source, matched.extractedValue(),
                        new ArrayList<>(matched.replacements())));
            }
            progress.accept((int) (99L * (sourcePosition + 1) / selected.size()));
        }
        checkCancelled();
        List<HarPredefinedCorrelation> result = build(keepNearestSource(candidates), rules);
        checkCancelled();
        progress.accept(100);
        return result;
    }

    static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Correlation scan cancelled");
        }
    }

    static int observedMinimumLength(Rule rule, String body, String headers) {
        JSONManager manager = new JSONManager();
        Object document = rule.getExtractorType() == ExtractorType.JSON_PATH ? parseJsonBody(body, manager) : null;
        return extract(rule, body, headers, manager, document).stream()
                .mapToInt(value -> value.value().strip().length()).filter(length -> length > 0)
                .min().stream().map(length -> Math.min(MIN_CORRELATED_VALUE_LENGTH, length))
                .findFirst().orElse(MIN_CORRELATED_VALUE_LENGTH);
    }

    private static Object parseJsonBody(String body, JSONManager jsonManager) {
        if (body == null || body.isBlank()) {
            return null;
        }
        char first = body.stripLeading().charAt(0);
        // Preserve scalar JSON roots as well as objects and arrays.
        if ("{[\"-0123456789tfn".indexOf(first) < 0) {
            return null;
        }
        try {
            return jsonManager.parse(body);
        } catch (RuntimeException | StackOverflowError ignored) {
            return null;
        }
    }

    /**
     * Keeps one candidate per replaced value. The same value is often extractable from several
     * responses - a redirect header, the page that embeds it, a later page that repeats it - and
     * replacing it more than once would leave the request pointing at whichever extractor was
     * applied last. The extraction closest before the request that uses it wins, because it is the
     * one guaranteed to still hold that value when the request runs.
     */
    private static List<Candidate> keepNearestSource(List<Candidate> candidates) {
        Map<String, Integer> nearestCandidateByReplacement = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            checkCancelled();
            Candidate candidate = candidates.get(i);
            for (Replacement replacement : candidate.replacements()) {
                String key = replacementKey(replacement);
                Integer previous = nearestCandidateByReplacement.get(key);
                if (previous == null
                        || candidates.get(previous).sourcePosition() < candidate.sourcePosition()) {
                    nearestCandidateByReplacement.put(key, i);
                }
            }
        }
        List<Candidate> kept = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            checkCancelled();
            Candidate candidate = candidates.get(i);
            int candidateIndex = i;
            List<Replacement> replacements = candidate.replacements().stream()
                    .filter(replacement -> nearestCandidateByReplacement
                            .get(replacementKey(replacement)) == candidateIndex)
                    .toList();
            if (!replacements.isEmpty()) {
                kept.add(new Candidate(candidate.rule(), candidate.sourcePosition(),
                        candidate.source(), candidate.extractedValue(), replacements));
            }
        }
        return kept;
    }

    private static String replacementKey(Replacement replacement) {
        return replacement.getTargetEntryIndex() + "\u0000" + replacement.getLocation()
                + "\u0000" + replacement.getLocationName() + "\u0000" + replacement.getMatchedLiteral();
    }

    private static List<HarPredefinedCorrelation> build(List<Candidate> candidates, List<Rule> rules) {
        // Every other rule's variable is reserved: a generated suffix that happens to equal a rule
        // name would collide with that rule's own extraction and the two would overwrite each other.
        Set<String> reserved = rules.stream().map(Rule::getVariableName).collect(Collectors.toSet());
        Set<String> assigned = new LinkedHashSet<>();
        List<HarPredefinedCorrelation> result = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            checkCancelled();
            String variableName = allocateVariableName(
                    candidate.rule().getVariableName(), reserved, assigned);
            result.add(new HarPredefinedCorrelation(candidate.rule(), variableName,
                    candidate.source().getOriginalIndex(), candidate.source().getUrl(),
                    candidate.extractedValue().value(), candidate.extractedValue().matchNumber(),
                    candidate.replacements()));
        }
        return List.copyOf(result);
    }

    private static String allocateVariableName(
            String baseName, Set<String> reserved, Set<String> assigned) {
        if (assigned.add(baseName)) {
            return baseName;
        }
        for (int occurrence = 2; occurrence < Integer.MAX_VALUE; occurrence++) {
            String candidateName = baseName + "_" + occurrence;
            if (!reserved.contains(candidateName) && assigned.add(candidateName)) {
                return candidateName;
            }
        }
        throw new IllegalStateException("Unable to allocate a variable name for " + baseName);
    }

    private record Candidate(Rule rule, int sourcePosition, HarEntry source,
            ExtractedValue extractedValue, List<Replacement> replacements) {
    }

    private static CandidateMatch findMatchingCandidate(
            List<HarEntry> entries, int sourcePosition, List<ExtractedValue> extractedValues, int minValueLength) {
        Map<String, CandidateMatch> matchesByValue = new LinkedHashMap<>();
        for (ExtractedValue extractedValue : extractedValues) {
            if (extractedValue.value() == null
                    || extractedValue.value().strip().length() < minValueLength) {
                continue;
            }
            List<Replacement> replacements = new ArrayList<>();
            for (int targetPosition = sourcePosition + 1; targetPosition < entries.size(); targetPosition++) {
                checkCancelled();
                replacements.addAll(findReplacements(entries.get(targetPosition), extractedValue.value(), minValueLength));
            }
            if (!replacements.isEmpty()) {
                matchesByValue.putIfAbsent(
                        extractedValue.value(), new CandidateMatch(extractedValue, replacements));
            }
        }
        return matchesByValue.size() == 1 ? matchesByValue.values().iterator().next() : null;
    }

    private static List<ExtractedValue> extract(
            Rule rule, String responseBody, String responseHeaders, JSONManager jsonManager, Object jsonDocument) {
        if (HarNativeExtractorSupport.supports(rule.getExtractorType())) {
            List<String> values = HarNativeExtractorSupport.extract(rule, responseBody, responseHeaders);
            if (rule.getMaxMatches() != -1 && values.size() > rule.getMaxMatches()) {
                return List.of();
            }
            Map<String, ExtractedValue> distinct = new LinkedHashMap<>();
            for (int i = 0; i < values.size(); i++) {
                distinct.putIfAbsent(values.get(i), new ExtractedValue(i + 1, values.get(i)));
            }
            return List.copyOf(distinct.values());
        }
        if (rule.getExtractorType() == ExtractorType.JSON_PATH) {
            if (jsonDocument == null) {
                return List.of();
            }
            try {
                List<Object> values = jsonManager.extractFromParsedJson(jsonDocument, rule.getExpression());
                if (rule.getMaxMatches() != -1 && values.size() > rule.getMaxMatches()) {
                    return List.of();
                }
                Map<String, ExtractedValue> extractedValues = new LinkedHashMap<>();
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) != null) {
                        String value = String.valueOf(values.get(i));
                        extractedValues.putIfAbsent(value, new ExtractedValue(i + 1, value));
                    }
                }
                return List.copyOf(extractedValues.values());
            } catch (RuntimeException | StackOverflowError ignored) {
                return List.of();
            }
        }
        String source = rule.getResponseField() == ResponseField.HEADERS ? responseHeaders : responseBody;
        Perl5Matcher matcher = JMeterUtils.getMatcher();
        org.apache.oro.text.regex.Pattern pattern = null;
        try {
            pattern = JMeterUtils.getPatternCache().getPattern(
                    rule.getExpression(), Perl5Compiler.READ_ONLY_MASK);
            PatternMatcherInput input = new PatternMatcherInput(source);
            int matchNumber = 0;
            Map<String, ExtractedValue> extractedValues = new LinkedHashMap<>();
            while (matcher.contains(input, pattern)) {
                checkCancelled();
                matchNumber++;
                if (rule.getMaxMatches() != -1 && matchNumber > rule.getMaxMatches()) {
                    return List.of();
                }
                String value = applyTemplate(rule.getTemplate(), matcher.getMatch());
                extractedValues.putIfAbsent(value, new ExtractedValue(matchNumber, value));
            }
            return List.copyOf(extractedValues.values());
        } catch (CancellationException ex) {
            throw ex;
        } catch (RuntimeException | StackOverflowError ignored) {
            return List.of();
        } finally {
            JMeterUtils.clearMatcherMemory(matcher, pattern);
        }
    }

    private record ExtractedValue(int matchNumber, String value) {
    }

    private record CandidateMatch(ExtractedValue extractedValue, List<Replacement> replacements) {
    }

    private static String applyTemplate(String template, MatchResult match) {
        Matcher templateMatcher = Pattern.compile("\\$(\\d+)\\$").matcher(template);
        StringBuilder result = new StringBuilder();
        int previousEnd = 0;
        while (templateMatcher.find()) {
            result.append(template, previousEnd, templateMatcher.start());
            int group = Integer.parseInt(templateMatcher.group(1));
            if (group >= match.groups()) {
                return null;
            }
            String value = match.group(group);
            if (value != null) {
                result.append(value);
            }
            previousEnd = templateMatcher.end();
        }
        result.append(template, previousEnd, template.length());
        return result.toString();
    }

    /**
     * Whether the response body is worth running body rules over. Images, fonts, media and other
     * binary downloads never carry correlation values, and a recording is mostly made of them.
     */
    private static boolean hasScannableBody(HarEntry entry) {
        if (entry.getResponseContentText().isEmpty()) {
            return false;
        }
        String contentType = "";
        for (NameValue header : entry.getResponseHeaders()) {
            if ("content-type".equalsIgnoreCase(header.getName())) {
                contentType = header.getValue().toLowerCase(Locale.ROOT).trim();
                break;
            }
        }
        if (contentType.isEmpty()) {
            return true;
        }
        for (String binaryType : NON_SCANNABLE_CONTENT_TYPES) {
            if (contentType.startsWith(binaryType)) {
                return false;
            }
        }
        return true;
    }

    private static String responseHeaders(HarEntry entry) {
        StringBuilder result = new StringBuilder();
        for (NameValue header : entry.getResponseHeaders()) {
            result.append(header.getName()).append(": ").append(header.getValue()).append('\n');
        }
        return result.toString();
    }

    private static List<Replacement> findReplacements(HarEntry entry, String extractedValue, int minValueLength) {
        List<Replacement> result = new ArrayList<>();
        addReplacement(result, entry, RequestLocation.URL_PATH, "", urlPath(entry.getUrl()), extractedValue, minValueLength);
        for (NameValue header : entry.getRequestHeaders()) {
            // Headers the converter drops (HTTP/2 pseudo-headers, Cookie, Host, Content-Length)
            // would be listed as replacements that silently do nothing.
            if (HarConverter.isExportableHeader(header.getName())) {
                addReplacement(result, entry, RequestLocation.REQUEST_HEADER,
                        header.getName(), header.getValue(), extractedValue, minValueLength);
                addReplacement(result, entry, RequestLocation.REQUEST_HEADER,
                        header.getName(), header.getName(), extractedValue, minValueLength);
            }
        }
        for (NameValue parameter : entry.getQueryString()) {
            addReplacement(result, entry, RequestLocation.QUERY_PARAMETER,
                    parameter.getName(), parameter.getValue(), extractedValue, minValueLength);
            addReplacement(result, entry, RequestLocation.QUERY_PARAMETER,
                    parameter.getName(), parameter.getName(), extractedValue, minValueLength);
        }
        if (entry.getQueryString().isEmpty()) {
            addReplacement(result, entry, RequestLocation.QUERY_PARAMETER,
                    "", urlQuery(entry.getUrl()), extractedValue, minValueLength);
        }
        PostData postData = entry.getPostData();
        if (postData != null) {
            if (postData.getParams().isEmpty()) {
                addReplacement(result, entry, RequestLocation.REQUEST_BODY, "",
                        postData.getText(), extractedValue, minValueLength);
            } else {
                for (NameValue parameter : postData.getParams()) {
                    addReplacement(result, entry, RequestLocation.POST_PARAMETER,
                            parameter.getName(), parameter.getValue(), extractedValue, minValueLength);
                    addReplacement(result, entry, RequestLocation.POST_PARAMETER,
                            parameter.getName(), parameter.getName(), extractedValue, minValueLength);
                }
            }
        }
        return result;
    }

    private static void addReplacement(List<Replacement> result, HarEntry entry,
            RequestLocation location, String locationName, String text, String extractedValue, int minValueLength) {
        String matchedLiteral = matchedLiteral(text, extractedValue);
        if (matchedLiteral == null && (location == RequestLocation.QUERY_PARAMETER
                || location == RequestLocation.POST_PARAMETER)) {
            matchedLiteral = RecordedValueReplacer.matchedDecodedLiteral(text, extractedValue);
        }
        if (matchedLiteral == null && location == RequestLocation.REQUEST_HEADER && text != null) {
            String decoded = decodedHeaderValue(extractedValue);
            if (!decoded.equals(extractedValue) && decoded.strip().length() >= minValueLength
                    && text.contains(decoded)) {
                matchedLiteral = decoded;
            }
        }
        if (matchedLiteral != null) {
            result.add(new Replacement(entry.getOriginalIndex(), entry.getMethod(), entry.getUrl(),
                    location, locationName, matchedLiteral));
        }
    }

    private static String matchedLiteral(String text, String extractedValue) {
        return RecordedValueReplacer.matchedLiteral(text, extractedValue);
    }

    private static String urlPath(String url) {
        try {
            return URI.create(url).getRawPath();
        } catch (IllegalArgumentException ignored) {
            int query = url.indexOf('?');
            return query >= 0 ? url.substring(0, query) : url;
        }
    }

    private static String urlQuery(String url) {
        try {
            return URI.create(url).getRawQuery();
        } catch (IllegalArgumentException ignored) {
            int query = url.indexOf('?');
            return query >= 0 ? url.substring(query + 1) : "";
        }
    }

    private static String decodedHeaderValue(String value) {
        if (!value.contains("%")) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static boolean usesDecodedHeader(HarPredefinedCorrelation correlation, Replacement replacement) {
        return replacement.getLocation() == RequestLocation.REQUEST_HEADER
                && !correlation.getExtractedValue().equals(replacement.getMatchedLiteral())
                && decodedHeaderValue(correlation.getExtractedValue()).equals(replacement.getMatchedLiteral());
    }

    static String variableReference(HarPredefinedCorrelation correlation, Replacement replacement) {
        String reference = "${" + correlation.getVariableName() + "}";
        return usesDecodedHeader(correlation, replacement) ? "${__urldecode(" + reference + ")}" : reference;
    }

    static TestElement buildExtractor(HarPredefinedCorrelation correlation) {
        return buildExtractor(correlation.getRule(), correlation.getMatchNumber(),
                correlation.getVariableName());
    }

    static TestElement buildExtractor(Rule rule, int matchNumber) {
        return buildExtractor(rule, matchNumber, rule.getVariableName());
    }

    static TestElement buildExtractor(Rule rule, int matchNumber, String variableName) {
        if (HarNativeExtractorSupport.supports(rule.getExtractorType())) {
            return HarNativeExtractorSupport.build(rule, matchNumber, variableName);
        }
        if (rule.getExtractorType() == ExtractorType.JSON_PATH) {
            JSONPostProcessor extractor = new JSONPostProcessor();
            extractor.setProperty(TestElement.GUI_CLASS, JSONPostProcessorGui.class.getName());
            extractor.setName("Extract " + rule.getName());
            extractor.setRefNames(variableName);
            extractor.setJsonPathExpressions(rule.getExpression());
            extractor.setMatchNumbers(Integer.toString(matchNumber));
            extractor.setDefaultValues(rule.getDefaultValue());
            extractor.setComputeConcatenation(rule.isComputeConcatenation());
            extractor.setFailOnNoMatch(rule.isFailOnNoMatch());
            return extractor;
        }
        RegexExtractor extractor = new RegexExtractor();
        extractor.setProperty(TestElement.GUI_CLASS, RegexExtractorGui.class.getName());
        extractor.setName("Extract " + rule.getName());
        extractor.setRefName(variableName);
        extractor.setRegex(rule.getExpression());
        extractor.setTemplate(rule.getTemplate());
        extractor.setMatchNumber(matchNumber);
        extractor.setDefaultValue(rule.getDefaultValue());
        extractor.setDefaultEmptyValue(rule.isEmptyDefaultValue());
        extractor.setFailOnNoMatch(rule.isFailOnNoMatch());
        extractor.setUseField(rule.getResponseField() == ResponseField.HEADERS
                ? RegexExtractor.USE_HDRS : RegexExtractor.USE_BODY);
        return extractor;
    }
}
