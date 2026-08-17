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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.gui.RegexExtractorGui;
import org.apache.jmeter.extractor.json.jsonpath.JSONManager;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui;
import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;
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
        JSON_PATH
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
        private final String defaultValue;
        private final boolean emptyDefaultValue;
        private final boolean computeConcatenation;
        private final boolean failOnNoMatch;

        Rule(String id, String group, String name, String variableName, ExtractorType extractorType,
                ResponseField responseField, String expression, String template,
                String defaultValue, boolean emptyDefaultValue, boolean computeConcatenation,
                boolean failOnNoMatch) {
            this(id, group, name, variableName, extractorType, responseField, expression, template,
                    1, defaultValue, emptyDefaultValue, computeConcatenation, failOnNoMatch);
        }

        Rule(String id, String group, String name, String variableName, ExtractorType extractorType,
                ResponseField responseField, String expression, String template, int maxMatches,
                String defaultValue, boolean emptyDefaultValue, boolean computeConcatenation,
                boolean failOnNoMatch) {
            this.id = id;
            this.group = group;
            this.name = name;
            this.variableName = variableName;
            this.extractorType = extractorType;
            this.responseField = responseField;
            this.expression = expression;
            this.template = template;
            this.maxMatches = maxMatches;
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
    private final int sourceEntryIndex;
    private final String sourceUrl;
    private final String extractedValue;
    private final int matchNumber;
    private final List<Replacement> replacements;

    private HarPredefinedCorrelation(Rule rule, int sourceEntryIndex, String sourceUrl,
            String extractedValue, int matchNumber, List<Replacement> replacements) {
        this.rule = rule;
        this.sourceEntryIndex = sourceEntryIndex;
        this.sourceUrl = sourceUrl;
        this.extractedValue = extractedValue;
        this.matchNumber = matchNumber;
        this.replacements = List.copyOf(replacements);
    }

    Rule getRule() {
        return rule;
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
        List<HarEntry> selected = entries.stream()
                .sorted(Comparator.comparingDouble(HarEntry::getStartMs)
                        .thenComparingInt(HarEntry::getOriginalIndex))
                .toList();
        List<HarPredefinedCorrelation> result = new ArrayList<>();
        JSONManager jsonManager = new JSONManager();
        for (int sourcePosition = 0; sourcePosition < selected.size(); sourcePosition++) {
            HarEntry source = selected.get(sourcePosition);
            String responseHeaders = responseHeaders(source);
            boolean scanBody = hasScannableBody(source);
            for (Rule rule : rules) {
                if (!scanBody && rule.getResponseField() == ResponseField.BODY) {
                    continue;
                }
                List<ExtractedValue> extractedValues = extract(
                        rule, source.getResponseContentText(), responseHeaders, jsonManager);
                CandidateMatch matched = findMatchingCandidate(selected, sourcePosition, extractedValues);
                if (matched == null) {
                    continue;
                }
                result.add(new HarPredefinedCorrelation(
                        rule, source.getOriginalIndex(), source.getUrl(), matched.extractedValue().value(),
                        matched.extractedValue().matchNumber(), matched.replacements()));
            }
        }
        return List.copyOf(result);
    }

    private static CandidateMatch findMatchingCandidate(
            List<HarEntry> entries, int sourcePosition, List<ExtractedValue> extractedValues) {
        Map<String, CandidateMatch> matchesByValue = new LinkedHashMap<>();
        for (ExtractedValue extractedValue : extractedValues) {
            if (extractedValue.value() == null
                    || extractedValue.value().strip().length() < MIN_CORRELATED_VALUE_LENGTH) {
                continue;
            }
            List<Replacement> replacements = new ArrayList<>();
            for (int targetPosition = sourcePosition + 1; targetPosition < entries.size(); targetPosition++) {
                replacements.addAll(findReplacements(entries.get(targetPosition), extractedValue.value()));
            }
            if (!replacements.isEmpty()) {
                matchesByValue.putIfAbsent(
                        extractedValue.value(), new CandidateMatch(extractedValue, replacements));
            }
        }
        return matchesByValue.size() == 1 ? matchesByValue.values().iterator().next() : null;
    }

    private static List<ExtractedValue> extract(
            Rule rule, String responseBody, String responseHeaders, JSONManager jsonManager) {
        if (rule.getExtractorType() == ExtractorType.JSON_PATH) {
            try {
                List<Object> values = jsonManager.extractWithJsonPath(responseBody, rule.getExpression());
                if (values.size() > rule.getMaxMatches()) {
                    return List.of();
                }
                List<ExtractedValue> extractedValues = new ArrayList<>(values.size());
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) != null) {
                        extractedValues.add(new ExtractedValue(i + 1, String.valueOf(values.get(i))));
                    }
                }
                return extractedValues;
            } catch (ParseException | RuntimeException ignored) {
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
            List<ExtractedValue> extractedValues = new ArrayList<>();
            while (matcher.contains(input, pattern)) {
                matchNumber++;
                if (matchNumber > rule.getMaxMatches()) {
                    return List.of();
                }
                extractedValues.add(new ExtractedValue(
                        matchNumber, applyTemplate(rule.getTemplate(), matcher.getMatch())));
            }
            return extractedValues;
        } catch (RuntimeException ignored) {
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

    private static List<Replacement> findReplacements(HarEntry entry, String extractedValue) {
        List<Replacement> result = new ArrayList<>();
        addReplacement(result, entry, RequestLocation.URL_PATH, "", urlPath(entry.getUrl()), extractedValue);
        for (NameValue header : entry.getRequestHeaders()) {
            addReplacement(result, entry, RequestLocation.REQUEST_HEADER,
                    header.getName(), header.getValue(), extractedValue);
        }
        for (NameValue parameter : entry.getQueryString()) {
            addReplacement(result, entry, RequestLocation.QUERY_PARAMETER,
                    parameter.getName(), parameter.getValue(), extractedValue);
        }
        if (entry.getQueryString().isEmpty()) {
            addReplacement(result, entry, RequestLocation.QUERY_PARAMETER,
                    "", urlQuery(entry.getUrl()), extractedValue);
        }
        PostData postData = entry.getPostData();
        if (postData != null) {
            if (postData.getParams().isEmpty()) {
                addReplacement(result, entry, RequestLocation.REQUEST_BODY, "",
                        postData.getText(), extractedValue);
            } else {
                for (NameValue parameter : postData.getParams()) {
                    addReplacement(result, entry, RequestLocation.POST_PARAMETER,
                            parameter.getName(), parameter.getValue(), extractedValue);
                }
            }
        }
        return result;
    }

    private static void addReplacement(List<Replacement> result, HarEntry entry,
            RequestLocation location, String locationName, String text, String extractedValue) {
        String matchedLiteral = matchedLiteral(text, extractedValue);
        if (matchedLiteral != null) {
            result.add(new Replacement(entry.getOriginalIndex(), entry.getMethod(), entry.getUrl(),
                    location, locationName, matchedLiteral));
        }
    }

    private static String matchedLiteral(String text, String extractedValue) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Set<String> variants = new LinkedHashSet<>();
        variants.add(extractedValue);
        String formEncoded = URLEncoder.encode(extractedValue, StandardCharsets.UTF_8);
        variants.add(formEncoded);
        variants.add(formEncoded.replace("+", "%20"));
        for (String variant : variants) {
            if (!variant.isEmpty() && text.contains(variant)) {
                return variant;
            }
        }
        return null;
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

    static List<String> replacementVariants(HarPredefinedCorrelation correlation, Replacement replacement) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(replacement.getMatchedLiteral());
        variants.add(percentDecode(replacement.getMatchedLiteral()));
        variants.add(correlation.getExtractedValue());
        variants.remove("");
        return List.copyOf(variants);
    }

    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    static TestElement buildExtractor(HarPredefinedCorrelation correlation) {
        return buildExtractor(correlation.getRule(), correlation.getMatchNumber());
    }

    static TestElement buildExtractor(Rule rule, int matchNumber) {
        if (rule.getExtractorType() == ExtractorType.JSON_PATH) {
            JSONPostProcessor extractor = new JSONPostProcessor();
            extractor.setProperty(TestElement.GUI_CLASS, JSONPostProcessorGui.class.getName());
            extractor.setName("Extract " + rule.getName());
            extractor.setRefNames(rule.getVariableName());
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
        extractor.setRefName(rule.getVariableName());
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
