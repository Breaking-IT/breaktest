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
import java.util.List;

import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.ExtractorObservation;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.readiness.evidence.SampleObservation;
import org.apache.jmeter.readiness.evidence.UnresolvedObservation;

/**
 * Optional repeatability: after the readiness validation, a separate run in a fresh session (new thread, so fresh
 * cookies and variables) repeats the scope for a second (and further) iteration, bounded by explicit iteration and time
 * limits. Any failure, unmatched extraction or unresolved variable in it is a Blocker.
 */
public final class RepeatabilityCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.REPEATABILITY;
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        RuntimeEvidence evidence = context.evidence();
        RunSegment run = evidence == null ? null : evidence.session().repeatability();
        if (run == null) {
            return CheckOutcome.notVerified(context.options().isRepeatabilityConfigured()
                    ? "Not verified — the repeatability run did not run"
                    : "Not verified — not configured");
        }
        PlanModel model = context.model();
        List<Finding> findings = new ArrayList<>();
        if (!run.status().isComplete()) {
            List<String> details = new ArrayList<>(run.statusDetails());
            details.add("Settings: " + run.settings());
            findings.add(new Finding(id(), model.root().ref(), "run", Severity.BLOCKER,
                    "The repeatability run did not complete: " + run.status().label(), details, null));
        }
        if (run.isTruncated()) {
            findings.add(new Finding(id(), model.root().ref(), "truncated", Severity.NOT_VERIFIED,
                    "The repeatability run produced more evidence than is kept; part of it was not recorded",
                    List.of("Run fewer iterations or a smaller scope to verify repeatability"), null));
        }
        if (run.hasTruncatedFailures()) {
            findings.add(new Finding(id(), model.root().ref(), "truncated-failures", Severity.BLOCKER,
                    "The repeatability run had failures in evidence beyond the recording limit", List.of(), null));
        }
        int maxIteration = 0;
        for (SampleObservation sample : run.samples()) {
            maxIteration = Math.max(maxIteration, sample.iteration());
            if (!sample.successful()) {
                addFinding(model, findings, sample.samplerId(), "failed", "failed in the repeatability run",
                        sample.thread(), sample.iteration(), sample.failures());
            }
        }
        for (ExtractorObservation extraction : run.extractions()) {
            if (Boolean.FALSE.equals(extraction.matched())) {
                addFinding(model, findings, extraction.extractorId(), "unmatched",
                        "did not match in the repeatability run", extraction.thread(), extraction.iteration(),
                        List.of("Expression: " + extraction.expression()));
            }
        }
        for (UnresolvedObservation unresolved : run.unresolved()) {
            addFinding(model, findings, unresolved.samplerId(), "unresolved:" + unresolved.variable(),
                    "sent unresolved '${" + unresolved.variable() + "}' in the repeatability run",
                    unresolved.thread(), unresolved.iteration(), List.of("Location: " + unresolved.location()));
        }
        return CheckOutcome.of(findings, true, "Fresh session, " + run.settings().iterations() + " iteration(s) planned, "
                + maxIteration + " reached, " + run.samples().size() + " samples");
    }

    private void addFinding(PlanModel model, List<Finding> findings, String sourceId, String key, String message,
            String thread, int iteration, List<String> evidence) {
        List<PlanNode> nodes = model.nodesWithId(sourceId);
        if (nodes.isEmpty() || findings.stream().anyMatch(f -> f.element().sourceId().equals(sourceId)
                && f.findingKey().equals(key))) {
            return;
        }
        List<String> details = new ArrayList<>(evidence);
        details.add("Thread: " + thread + ", iteration " + iteration);
        findings.add(CheckContext.finding(id(), nodes.get(0), key, Severity.BLOCKER,
                "'" + nodes.get(0).name() + "' " + message, details));
    }
}
