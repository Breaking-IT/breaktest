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

package org.apache.jmeter.protocol.http.proxy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

/** Side-effect-free previews grouped by literal and variable, applied only after review. */
public final class RecordingVariableReview {
    public record Pair(String name, String value) {
        public String replacement() { return "${" + name + "}"; }
    }

    public record Match(String request, String field, String before, String after) { }

    public record Candidate(Pair pair, List<Match> matches, List<RecordedSampler> samples) { }

    private RecordingVariableReview() { }

    static List<Candidate> find(List<RecordedSampler> samples,
            Function<RecordedSampler, Collection<Arguments>> variablesFor) {
        Map<Pair, List<Match>> matches = new LinkedHashMap<>();
        Map<Pair, List<RecordedSampler>> targets = new LinkedHashMap<>();
        for (RecordedSampler sample : samples) {
            Map<String, String> variables = new LinkedHashMap<>();
            for (Arguments arguments : variablesFor.apply(sample)) {
                variables.putAll(arguments.getArgumentsAsMap());
            }
            for (var variable : variables.entrySet()) {
                if (variable.getKey().isEmpty() || variable.getValue().isEmpty()) {
                    continue;
                }
                Pair pair = new Pair(variable.getKey(), variable.getValue());
                List<TestElement> originals = elements(sample);
                List<TestElement> copies = originals.stream().map(element -> (TestElement) element.clone()).toList();
                replace(copies, List.of(pair));
                List<Match> found = new ArrayList<>();
                for (int i = 0; i < originals.size(); i++) {
                    Map<String, String> before = fields(originals.get(i));
                    Map<String, String> after = fields(copies.get(i));
                    for (var field : before.entrySet()) {
                        String updated = after.get(field.getKey());
                        if (updated != null && !updated.equals(field.getValue())) {
                            found.add(new Match(sample.entry().getMethod() + " " + sample.entry().getUrl(),
                                    originals.get(i).getClass().getSimpleName() + "/" + field.getKey(), field.getValue(), updated));
                        }
                    }
                }
                if (!found.isEmpty()) {
                    matches.computeIfAbsent(pair, ignored -> new ArrayList<>()).addAll(found);
                    targets.computeIfAbsent(pair, ignored -> new ArrayList<>()).add(sample);
                }
            }
        }
        return matches.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Pair, List<Match>> entry) -> entry.getKey().value().length()).reversed())
                .map(entry -> new Candidate(entry.getKey(), List.copyOf(entry.getValue()), List.copyOf(targets.get(entry.getKey()))))
                .toList();
    }

    public static void apply(List<Candidate> accepted) {
        Map<RecordedSampler, List<Pair>> replacements = new LinkedHashMap<>();
        for (Candidate candidate : accepted) {
            for (RecordedSampler sample : candidate.samples()) {
                replacements.computeIfAbsent(sample, ignored -> new ArrayList<>()).add(candidate.pair());
            }
        }
        replacements.forEach((sample, pairs) -> replace(elements(sample), pairs));
    }

    private static List<TestElement> elements(RecordedSampler sample) {
        List<TestElement> elements = new ArrayList<>();
        elements.add(sample.sampler);
        for (TestElement element : sample.testElements) {
            if (element != null) {
                elements.add(element);
            }
        }
        if (sample.authorization != null) {
            elements.add(sample.authorization);
        }
        return elements;
    }

    private static void replace(List<TestElement> elements, List<Pair> pairs) {
        // Longest literals first, with a stable order for competing variables with identical values.
        pairs.stream().sorted(Comparator.comparingInt((Pair pair) -> pair.value().length()).reversed()).forEach(pair -> {
            Arguments variables = new Arguments();
            variables.addArgument(pair.name(), pair.value());
            RecordedVariableReplacer.replaceValues(elements.get(0), elements.subList(1, elements.size()).toArray(new TestElement[0]),
                    List.of(variables));
        });
    }

    private static Map<String, String> fields(TestElement element) {
        Map<String, String> result = new LinkedHashMap<>();
        fields(element.propertyIterator(), "", result);
        return result;
    }

    private static void fields(PropertyIterator properties, String prefix, Map<String, String> result) {
        int index = 0;
        while (properties.hasNext()) {
            var property = properties.next();
            String path = prefix + property.getName() + "[" + index++ + "]";
            if (property instanceof MultiProperty multi) {
                fields(multi.iterator(), path + "/", result);
            } else {
                result.put(path, property.getStringValue());
            }
        }
    }
}
