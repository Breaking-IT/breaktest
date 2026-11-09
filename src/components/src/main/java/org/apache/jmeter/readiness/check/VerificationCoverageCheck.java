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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.SampleObservation;

/**
 * Check 2: every relevant transaction has at least one verification (an assertion, or an extractor with "fail on no
 * match") applying to one of its request samplers, following JMeter's scoping rules. Request samplers outside any
 * transaction are checked as their own unit. A runtime pass confirms the verification actually ran.
 */
public final class VerificationCoverageCheck implements ReadinessCheck {
    private static final Pattern STATIC_RESOURCE = Pattern.compile(
            "(?i).*\\.(css|js|mjs|png|jpe?g|gif|svg|ico|webp|bmp|woff2?|ttf|otf|eot|map)$"); // $NON-NLS-1$

    @Override
    public CheckId id() {
        return CheckId.VERIFICATION_COVERAGE;
    }

    /**
     * @param sampler a sampler node
     * @return the nodes verifying it
     */
    static List<PlanNode> verifiers(PlanNode sampler) {
        List<PlanNode> result = new ArrayList<>(sampler.applicable(Assertion.class));
        for (PlanNode node : sampler.applicable(PostProcessor.class)) {
            ExtractorSupport.describe(node.element()).filter(ExtractorSupport.ExtractorInfo::failOnNoMatch)
                    .ifPresent(info -> result.add(node));
        }
        return result;
    }

    /**
     * @param sampler a sampler node
     * @return whether it requests a static resource (stylesheet, script, image, font)
     */
    static boolean isStaticResource(PlanNode sampler) {
        String path = sampler.element().getPropertyAsString("HTTPSampler.path"); // $NON-NLS-1$
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return !path.isEmpty() && STATIC_RESOURCE.matcher(path.toLowerCase(Locale.ROOT)).matches();
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        PlanModel model = context.model();
        List<Finding> findings = new ArrayList<>();
        RuntimeEvidence evidence = context.evidence();
        int units = 0;
        int covered = 0;
        int exercised = 0;
        Set<String> seen = new java.util.HashSet<>();
        Map<PlanNode, List<PlanNode>> unitsToSamplers = new LinkedHashMap<>();
        for (PlanNode transaction : model.nodesOf(TransactionController.class)) {
            List<PlanNode> samplers = transaction.requestSamplers();
            if (!samplers.isEmpty() && seen.add(transaction.sourceId())) {
                unitsToSamplers.put(transaction, samplers);
            }
        }
        for (PlanNode sampler : model.nodes()) {
            if (sampler.isRequestSampler() && sampler.enclosingTransaction() == null
                    && seen.add(sampler.sourceId())) {
                unitsToSamplers.put(sampler, List.of(sampler));
            }
        }
        for (Map.Entry<PlanNode, List<PlanNode>> unit : unitsToSamplers.entrySet()) {
            units++;
            PlanNode node = unit.getKey();
            boolean transaction = node.element() instanceof TransactionController;
            String what = transaction ? "Transaction" : "Request outside any transaction";
            List<String> coveredNames = new ArrayList<>();
            List<String> uncoveredNames = new ArrayList<>();
            List<PlanNode> verified = new ArrayList<>();
            for (PlanNode sampler : unit.getValue()) {
                if (verifiers(sampler).isEmpty()) {
                    uncoveredNames.add(sampler.name());
                } else {
                    coveredNames.add(sampler.name());
                    verified.add(sampler);
                }
            }
            List<String> details = new ArrayList<>();
            if (transaction) {
                details.add("Covered requests: " + (coveredNames.isEmpty() ? "none" : String.join(", ", coveredNames)));
                if (!uncoveredNames.isEmpty()) {
                    details.add("Requests without verification: " + String.join(", ", uncoveredNames));
                }
            }
            if (verified.isEmpty()) {
                findings.add(CheckContext.finding(id(), node, "unverified", Severity.BLOCKER,
                        what + " '" + node.name() + "' has no assertion or failing extractor on any request",
                        details));
                continue;
            }
            covered++;
            if (verified.stream().allMatch(VerificationCoverageCheck::isStaticResource)) {
                findings.add(CheckContext.finding(id(), node, "weak", Severity.WARNING,
                        what + " '" + node.name() + "' is only verified on static resources", details));
            } else if (transaction) {
                findings.add(CheckContext.finding(id(), node, "covered", Severity.INFO,
                        "Transaction '" + node.name() + "' is verified", details));
            }
            if (evidence != null) {
                exercised += exercised(context, node, verified, what, findings) ? 1 : 0;
            }
        }
        String summary = covered + " of " + units + " units have configured verification";
        if (evidence == null) {
            if (units > 0) {
                findings.add(new Finding(id(), model.root().ref(), "exercised", Severity.NOT_VERIFIED,
                        "Exercised coverage requires a validation run", List.of(), null));
            }
        } else {
            summary += " · " + exercised + " of " + covered + " exercised during validation";
        }
        return CheckOutcome.of(findings, units > 0, summary);
    }

    private boolean exercised(CheckContext context, PlanNode unit, List<PlanNode> verified, String what,
            List<Finding> findings) {
        RuntimeEvidence evidence = context.evidence();
        boolean ran = false;
        boolean onlyRedirects = true;
        for (PlanNode sampler : verified) {
            Set<String> verifierIds = new java.util.HashSet<>();
            verifiers(sampler).forEach(v -> verifierIds.add(v.sourceId()));
            for (SampleObservation sample : evidence.samplesOf(sampler.sourceId())) {
                if (sample.verifierIds().stream().anyMatch(verifierIds::contains)) {
                    ran = true;
                    onlyRedirects &= sample.redirect();
                }
            }
        }
        if (!ran) {
            findings.add(CheckContext.finding(id(), unit, "not-exercised", Severity.NOT_VERIFIED,
                    what + " '" + unit.name() + "': its verification did not run during validation", List.of()));
            return false;
        }
        if (onlyRedirects) {
            findings.add(CheckContext.finding(id(), unit, "weak-redirect", Severity.WARNING,
                    what + " '" + unit.name() + "' was only verified on redirect responses", List.of()));
        }
        return true;
    }
}
