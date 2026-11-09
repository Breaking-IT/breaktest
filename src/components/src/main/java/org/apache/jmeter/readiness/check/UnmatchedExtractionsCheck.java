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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.ExtractorObservation;

/**
 * Check 4: supported extractors that did not match during validation, from direct instrumentation of each
 * invocation (never from the resulting variable, which may hold a default or a stale value). An extractor is
 * required unless an exception marks it optional; one that never executed is Not verified. Other post-processors
 * are Unsupported — not verified.
 */
public final class UnmatchedExtractionsCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.UNMATCHED_EXTRACTIONS;
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        RuntimeEvidence evidence = context.evidence();
        if (evidence == null) {
            return CheckOutcome.notVerified("Not verified — requires runtime validation");
        }
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int extractors = 0;
        int matched = 0;
        for (PlanNode node : context.model().nodes()) {
            if (!seen.add(node.sourceId())) {
                continue;
            }
            var info = ExtractorSupport.describe(node.element()).orElse(null);
            if (info == null) {
                if (ExtractorSupport.isUnsupportedPostProcessor(node.element())) {
                    findings.add(CheckContext.finding(id(), node, "unsupported", Severity.NOT_VERIFIED,
                            "Unsupported — not verified: '" + node.name() + "' ("
                                    + node.element().getClass().getSimpleName() + ") does not report its outcome",
                            List.of()));
                }
                continue;
            }
            extractors++;
            List<ExtractorObservation> observations = evidence.extractionsOf(node.sourceId());
            if (observations.isEmpty()) {
                findings.add(CheckContext.finding(id(), node, info.key(), Severity.NOT_VERIFIED,
                        "'" + node.name() + "' never executed during validation", List.of()));
                continue;
            }
            List<ExtractorObservation> failures = observations.stream()
                    .filter(o -> Boolean.FALSE.equals(o.matched())).toList();
            if (failures.isEmpty()) {
                if (observations.stream().allMatch(o -> o.matched() == null)) {
                    findings.add(CheckContext.finding(id(), node, info.key(), Severity.NOT_VERIFIED,
                            "'" + node.name() + "' ran but did not report whether it matched", List.of()));
                } else {
                    matched++;
                }
                continue;
            }
            boolean optional = ReadinessExceptions.get(node.element(), id(),
                    ReadinessExceptions.OPTIONAL_EXTRACTOR_KEY) != null;
            ExtractorObservation first = failures.get(0);
            List<String> details = new ArrayList<>();
            details.add("Expression: " + (first.expression().isEmpty() ? info.expression() : first.expression()));
            List<PlanNode> sampler = context.model().nodesWithId(first.samplerId());
            details.add("Source sampler: " + (sampler.isEmpty() ? first.samplerId() : sampler.get(0).name()));
            details.add("Thread: " + first.thread() + ", iteration " + first.iteration());
            details.add("Unmatched in " + failures.size() + " of " + observations.size() + " invocations");
            if (!first.excerpt().isEmpty()) {
                details.add("Response excerpt: " + first.excerpt());
            }
            findings.add(CheckContext.finding(id(), node, info.key(),
                    optional ? Severity.WARNING : Severity.BLOCKER,
                    "'" + node.name() + "' did not match" + (optional ? " (marked optional)" : ""), details));
        }
        return CheckOutcome.of(findings, extractors > 0 || !findings.isEmpty(),
                matched + " of " + extractors + " extractors matched every time they ran");
    }
}
