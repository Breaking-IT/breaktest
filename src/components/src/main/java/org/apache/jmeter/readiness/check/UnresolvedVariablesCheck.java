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

package org.apache.jmeter.readiness.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.ExtractorObservation;
import org.apache.jmeter.readiness.evidence.UnresolvedObservation;

/**
 * Check 7: <code>${name}</code> references sent in requests that nothing defines. Statically, references in request
 * samplers and the request configuration applying to them (defaults, headers, cookies, authorization) are looked up
 * in the variable sources in scope; functions are recognized and computed names are uncertain. At run time, literal
 * <code>${name}</code> or {@code %24%7Bname%7D} in the URL, request headers and request data of what was sent confirms a
 * problem. Large or binary bodies may be truncated or absent in request data and are then not verified.
 */
public final class UnresolvedVariablesCheck implements ReadinessCheck {
    private static final Set<String> REQUEST_CONFIG = Set.of(
            "org.apache.jmeter.config.ConfigTestElement", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.HeaderManager", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.CookieManager", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.AuthManager"); // $NON-NLS-1$

    @Override
    public CheckId id() {
        return CheckId.UNRESOLVED_VARIABLES;
    }

    private record Key(String samplerId, String name) {
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        PlanModel model = context.model();
        VariableSources sources = context.sources();
        RuntimeEvidence evidence = context.evidence();
        Map<Key, Finding> findings = new LinkedHashMap<>();
        Set<String> seenSamplers = new java.util.HashSet<>();
        int references = 0;
        for (PlanNode sampler : model.nodes()) {
            if (!sampler.isRequestSampler() || !seenSamplers.add(sampler.sourceId())) {
                continue;
            }
            for (Map.Entry<String, String> text : requestTexts(sampler).entrySet()) {
                VariableReferences.Result parsed = VariableReferences.parse(text.getValue());
                if (parsed.dynamic()) {
                    references++;
                    Key key = new Key(sampler.sourceId(), "(dynamic)");
                    findings.putIfAbsent(key, CheckContext.finding(id(), sampler, "(dynamic)",
                            uncertainSeverity(evidence, sampler), "Dynamic variable reference in "
                                    + text.getKey() + " of '" + sampler.name() + "'", List.of(
                                    "The variable name is computed at run time")));
                }
                for (String name : parsed.names()) {
                    references++;
                    VariableSources.Lookup lookup = sources.lookup(name);
                    Key key = new Key(sampler.sourceId(), name);
                    if (lookup.status() == VariableSources.Status.UNDEFINED) {
                        findings.putIfAbsent(key, CheckContext.finding(id(), sampler, name, Severity.BLOCKER,
                                "'${" + name + "}' in " + text.getKey() + " of '" + sampler.name()
                                        + "' is never defined", List.of("Cause: never defined in scope")));
                    } else if (lookup.status() == VariableSources.Status.UNCERTAIN) {
                        findings.putIfAbsent(key, CheckContext.finding(id(), sampler, name,
                                uncertainSeverity(evidence, sampler), "'${" + name + "}' in " + text.getKey()
                                        + " of '" + sampler.name() + "' is not defined by a recognized source",
                                List.of("May be set by: " + String.join(", ", lookup.sources().stream()
                                        .map(PlanNode::name).distinct().limit(5).toList()))));
                    }
                }
            }
        }
        if (evidence == null) {
            findings.put(new Key("", ""), new Finding(id(), model.root().ref(), "runtime", Severity.NOT_VERIFIED,
                    "Runtime confirmation of what requests sent requires a validation run", List.of(), null));
        } else {
            addRuntimeFindings(context, sources, findings);
        }
        String summary = references + " variable reference(s) in requests checked";
        if (evidence != null) {
            summary += " · sent requests scanned (large or binary bodies may be truncated)";
        }
        return CheckOutcome.of(new ArrayList<>(findings.values()), true, summary);
    }

    /**
     * Uncertain references are resolved by a validation run that executed the sampler without sending the literal.
     */
    private static Severity uncertainSeverity(RuntimeEvidence evidence, PlanNode sampler) {
        if (evidence != null && !evidence.samplesOf(sampler.sourceId()).isEmpty()) {
            return Severity.INFO;
        }
        return Severity.NOT_VERIFIED;
    }

    private void addRuntimeFindings(CheckContext context, VariableSources sources, Map<Key, Finding> findings) {
        RuntimeEvidence evidence = context.evidence();
        for (UnresolvedObservation observation : evidence.unresolved()) {
            List<PlanNode> nodes = context.model().nodesWithId(observation.samplerId());
            if (nodes.isEmpty()) {
                continue;
            }
            PlanNode sampler = nodes.get(0);
            Key key = new Key(sampler.sourceId(), observation.variable());
            if (findings.containsKey(key) && findings.get(key).message().startsWith("Sent")) {
                continue;
            }
            List<String> details = new ArrayList<>();
            details.add("Location: " + observation.location() + (observation.encoded() ? " (URL-encoded)" : ""));
            details.add("Thread: " + observation.thread() + ", iteration " + observation.iteration());
            details.add("Cause: " + cause(context, sources, sampler, observation.variable()));
            if (!observation.excerpt().isEmpty()) {
                details.add("Excerpt: " + observation.excerpt());
            }
            findings.put(key, CheckContext.finding(id(), sampler, observation.variable(), Severity.BLOCKER,
                    "Sent unresolved '${" + observation.variable() + "}' in " + observation.location() + " of '"
                            + sampler.name() + "'", details));
        }
    }

    private static String cause(CheckContext context, VariableSources sources, PlanNode sampler, String name) {
        VariableSources.Lookup lookup = sources.lookup(name);
        if (lookup.status() == VariableSources.Status.UNDEFINED) {
            return "never defined";
        }
        for (PlanNode extractor : sources.extractorsDefining(name)) {
            for (ExtractorObservation observation : context.evidence().extractionsOf(extractor.sourceId())) {
                if (Boolean.FALSE.equals(observation.matched())) {
                    return "its extractor '" + extractor.name() + "' ran but did not match";
                }
            }
        }
        if (!lookup.sources().isEmpty() && lookup.sources().stream().allMatch(source -> isPositional(source)
                && source.threadGroup() == sampler.threadGroup() && source.order() > sampler.order())) {
            return "consumed before assignment (only defined later in execution order)";
        }
        return "cause undetermined";
    }

    private static boolean isPositional(PlanNode source) {
        // Configuration elements apply to their whole scope; processors and controllers define at their position
        return !(source.element() instanceof ConfigElement) && source.parent() != null;
    }

    /**
     * @param sampler a request sampler
     * @return the texts it sends: its own properties and those of the request configuration applying to it
     */
    static Map<String, String> requestTexts(PlanNode sampler) {
        Map<String, String> texts = new LinkedHashMap<>(PropertyTexts.of(sampler.element()));
        for (PlanNode config : sampler.applicable(ConfigElement.class)) {
            if (REQUEST_CONFIG.contains(config.element().getClass().getName())) {
                PropertyTexts.of(config.element()).forEach((property, value) ->
                        texts.merge(config.name() + " / " + property, value, (a, b) -> a + "\n" + b));
            }
        }
        return texts;
    }
}
