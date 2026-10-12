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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.ReplaceableField;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.RecordedValueReplacer;

/** A single occurrence. Fields are resolved again after GUI commits, which can replace argument/header objects. */
final class CorrelationReviewStep implements org.apache.jmeter.gui.util.ReviewStep {

    record Field(JMeterTreeNode node, String name, int row) {
        ReplaceableField resolve() {
            if (name.equals("Body") && node.getTestElement() instanceof HTTPSamplerBase sampler
                    && sampler.getSendParameterValuesAsPostBody()) {
                return new ReplaceableField(name, () -> {
                    StringBuilder body = new StringBuilder();
                    sampler.getArguments().forEach(property -> body.append(((HTTPArgument) property.getObjectValue()).getValue()));
                    return body.toString();
                }, value -> RecordedValueReplacer.setRawBodyValue(sampler, value));
            }
            return ((Replaceable) node.getTestElement()).getReplaceableFields().stream()
                    .filter(field -> field.name().equals(name)).skip(row).findFirst().orElse(null);
        }

        boolean decoded() {
            if (name.startsWith("Parameter") && node.getTestElement() instanceof HTTPSamplerBase sampler) {
                return !sampler.getSendParameterValuesAsPostBody()
                        && ((HTTPArgument) sampler.getArguments().getArgument(row)).isAlwaysEncoded();
            }
            return false;
        }
    }

    final HarPredefinedCorrelation correlation;
    final HarPredefinedCorrelation.Replacement replacement;
    final Field field;
    final String literal;
    final String reference;
    String expected;
    int start;
    int end;
    State decision = State.PENDING;
    private boolean encodeArgument;

    private CorrelationReviewStep(HarPredefinedCorrelation correlation,
            HarPredefinedCorrelation.Replacement replacement, Field field, String expected,
            RecordedValueReplacer.Match match) {
        this.correlation = correlation;
        this.replacement = replacement;
        this.field = field;
        this.expected = expected;
        start = match.start();
        end = match.end();
        literal = expected.substring(start, end);
        reference = match.reference();
    }

    static List<CorrelationReviewStep> create(List<HarPredefinedCorrelation> correlations,
            Map<Integer, JMeterTreeNode> nodes) {
        List<CorrelationReviewStep> steps = new ArrayList<>();
        for (HarPredefinedCorrelation correlation : correlations) {
            for (var target : correlation.getReplacements().stream()
                    .map(HarPredefinedCorrelation.Replacement::getTargetEntryIndex).distinct().toList()) {
                JMeterTreeNode node = nodes.get(target);
                if (node == null || !(node.getTestElement() instanceof Replaceable sampler)) {
                    continue;
                }
                Map<String, Integer> rows = new LinkedHashMap<>();
                List<Field> fields = new ArrayList<>();
                boolean raw = node.getTestElement() instanceof HTTPSamplerBase http && http.getSendParameterValuesAsPostBody();
                var descriptors = sampler.getReplaceableFields();
                for (var candidate : descriptors) {
                    int row = rows.merge(candidate.name(), 1, Integer::sum) - 1;
                    if (candidate.rowField() != org.apache.jmeter.gui.RowField.NAME
                            && (!(sampler instanceof HTTPSamplerBase) || List.of("Path", "Parameter value", "Header value")
                            .contains(candidate.name())) && !(raw && candidate.name().startsWith("Parameter"))) {
                        fields.add(new Field(node, candidate.name(), row));
                    }
                }
                if (raw) {
                    fields.add(new Field(node, "Body", 0));
                }
                var candidates = correlation.getReplacements().stream()
                        .filter(item -> item.getTargetEntryIndex() == target).toList();
                for (Field field : fields) {
                    String text = field.name().equals("Body") && raw ? field.resolve().value()
                            : descriptors.stream().filter(item -> item.name().equals(field.name()))
                                    .skip(field.row()).findFirst().orElseThrow().value();
                    List<CorrelationReviewStep> fieldSteps = new ArrayList<>();
                    addMatches(fieldSteps, correlation, candidates.get(0), field, descriptors, text,
                            correlation.getExtractedValue(), "${" + correlation.getVariableName() + "}");
                    if (field.name().startsWith("Header")) {
                        String headerName = descriptors.stream().filter(item -> item.name().equals("Header name"))
                                .skip(field.row()).map(ReplaceableField::value).findFirst().orElse("");
                        for (var replacement : candidates) {
                            String reference = HarPredefinedCorrelation.variableReference(correlation, replacement);
                            if (replacement.getLocation() == HarPredefinedCorrelation.RequestLocation.REQUEST_HEADER
                                    && replacement.getLocationName().equals(headerName)
                                    && reference.startsWith("${__urldecode(")) {
                                addMatches(fieldSteps, correlation, replacement, field, descriptors, text,
                                        replacement.getMatchedLiteral(), reference);
                            }
                        }
                    }
                    fieldSteps.sort(java.util.Comparator.comparingInt(step -> step.start));
                    steps.addAll(fieldSteps);
                }
            }
        }
        return steps;
    }

    private static void addMatches(List<CorrelationReviewStep> steps, HarPredefinedCorrelation correlation,
            HarPredefinedCorrelation.Replacement replacement, Field field, List<ReplaceableField> descriptors,
            String text, String value, String reference) {
        for (var match : correlation.getRule().getReplacementFilter().samplerMatches(
                (Replaceable) field.node().getTestElement(), descriptors, field.name(), field.row(), text, value, reference, field.decoded())) {
            if (steps.stream().noneMatch(step -> step.start < match.end() && step.end > match.start())) {
                var step = new CorrelationReviewStep(correlation, replacement, field, text, match);
                if (field.name().equals("Parameter value") && field.node().getTestElement() instanceof HTTPSamplerBase http
                        && match.start() == 0 && match.end() == text.length()) {
                    HTTPArgument argument = (HTTPArgument) http.getArguments().getArgument(field.row());
                    if (RecordedValueReplacer.encodeWholeArgument(argument, value, http.getSendParameterValuesAsPostBody())) {
                        step = new CorrelationReviewStep(correlation, replacement, field, text,
                                new RecordedValueReplacer.Match(match.start(), match.end(), reference));
                        step.encodeArgument = true;
                    }
                }
                steps.add(step);
            }
        }
    }

    @Override
    public JMeterTreeNode node() {
        return field.node();
    }

    @Override
    public State state() {
        return decision;
    }

    @Override
    public void reject() {
        decision = State.REJECTED;
    }

    @Override
    public void markStale() {
        decision = State.STALE;
    }

    @Override
    public String description() {
        return field.node().getName() + " — " + field.name() + "\n" + literal + " → " + reference;
    }

    @Override
    public Runnable highlight(java.awt.Component editor) {
        return org.apache.jmeter.gui.util.EditorMatchHighlighter.field(
                editor, field.name(), field.row(), expected, start, end);
    }

    static List<CorrelationReviewStep> pending(List<CorrelationReviewStep> steps) {
        return steps.stream().filter(step -> step.decision == State.PENDING).toList();
    }

    @Override
    public boolean current() {
        ReplaceableField resolved = field.resolve();
        return resolved != null && expected.equals(resolved.value());
    }

    boolean accept(List<CorrelationReviewStep> steps) {
        if (decision != State.PENDING || !current()) {
            return false;
        }
        String updated = expected.substring(0, start) + reference + expected.substring(end);
        field.resolve().setValue(updated);
        if (encodeArgument) {
            HTTPSamplerBase http = (HTTPSamplerBase) field.node().getTestElement();
            ((HTTPArgument) http.getArguments().getArgument(field.row())).setAlwaysEncoded(true);
        }
        int delta = reference.length() - (end - start);
        for (CorrelationReviewStep other : steps) {
            if (other != this && other.field.equals(field) && other.expected.equals(expected)) {
                other.expected = updated;
                if (other.start >= end) {
                    other.start += delta;
                    other.end += delta;
                } else if (other.end > start) {
                    other.decision = State.STALE;
                }
            }
        }
        expected = updated;
        end = start + reference.length();
        decision = State.ACCEPTED;
        return true;
    }
}
