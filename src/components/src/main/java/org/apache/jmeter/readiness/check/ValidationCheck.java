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
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.EvidenceSession;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.readiness.evidence.RunStatus;
import org.apache.jmeter.readiness.evidence.SampleObservation;

/**
 * Check 1: the in-scope plan completed a bounded validation run against the current fingerprint, without request
 * errors or assertion failures. A cancelled, stopped, timed-out or otherwise incomplete run is never successful; a
 * run completed through Resume validation counts only when the resumed runs cover the scope against one unchanged
 * fingerprint.
 */
public final class ValidationCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.VALIDATION;
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        RuntimeEvidence evidence = context.evidence();
        if (evidence == null) {
            return CheckOutcome.notVerified("Not verified — requires runtime validation");
        }
        PlanModel model = context.model();
        EvidenceSession session = evidence.session();
        RunSegment main = session.main();
        EvidenceSession.Completion completion = session.completion();
        List<Finding> findings = new ArrayList<>();
        List<String> run = new ArrayList<>();
        run.add("Validation settings: " + main.settings());
        run.add("Started " + main.start() + (main.end() == null ? "" : ", ended " + main.end()));
        run.add("Status: " + completion.status().label());
        run.addAll(completion.details());
        run.add("Fingerprint: " + main.fingerprint().substring(0, Math.min(12, main.fingerprint().length())));
        if (completion.status() != RunStatus.COMPLETED) {
            findings.add(new Finding(id(), model.root().ref(), "run", Severity.BLOCKER,
                    "The validation run did not complete: " + completion.status().label(), run, null));
        } else {
            findings.add(new Finding(id(), model.root().ref(), "run", Severity.INFO, "The validation run completed",
                    run, null));
        }
        // Only a later segment can supersede an outcome. Repeated executions within one run all count.
        Set<String> reported = new HashSet<>();
        List<RunSegment> segments = new ArrayList<>(session.segments());
        java.util.Collections.reverse(segments);
        Set<String> later = new HashSet<>();
        int failed = 0;
        for (RunSegment segment : segments) {
            Set<String> seen = new HashSet<>();
            for (SampleObservation sample : segment.samples()) {
                String key = sample.executionId() + "#" + sample.thread() + "#" + sample.iteration();
                seen.add(key);
                if (later.contains(key) || sample.successful()) {
                    continue;
                }
                failed++;
                List<PlanNode> nodes = model.nodesWithId(sample.samplerId());
                if (nodes.isEmpty() || !reported.add(sample.samplerId())) {
                    continue;
                }
                List<String> details = new ArrayList<>(sample.failures());
                details.add("Thread: " + sample.thread() + ", iteration " + sample.iteration());
                findings.add(CheckContext.finding(id(), nodes.get(0), "failed", Severity.BLOCKER,
                        "'" + nodes.get(0).name() + "' failed during validation", details));
            }
            later.addAll(seen);
        }
        if (session.isTruncated()) {
            findings.add(new Finding(id(), model.root().ref(), "truncated", Severity.NOT_VERIFIED,
                    "The run produced more evidence than is kept; part of it was not recorded", List.of(), null));
        }
        return CheckOutcome.of(findings, true, session.allSamples().size() + " samples, " + failed + " failed");
    }
}
