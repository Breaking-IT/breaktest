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

/**
 * Suspicious hardcoded recorded values, found by the HAR correlation tooling (the same rules as "Find predefined
 * correlations"), reported for review only. The plan is never changed.
 */
public final class HardcodedValuesCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.HARDCODED_VALUES;
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        HardcodedValues.Scan scan = context.hardcoded();
        if (scan == null || !scan.available()) {
            return CheckOutcome.notVerified("Not verified — "
                    + (scan == null || scan.note().isEmpty() ? "no recorded responses to compare with" : scan.note()));
        }
        List<Finding> findings = new ArrayList<>();
        for (HardcodedValues.Candidate candidate : scan.candidates()) {
            List<PlanNode> nodes = context.model().nodesWithId(candidate.samplerId());
            if (nodes.isEmpty()) {
                continue;
            }
            findings.add(CheckContext.finding(id(), nodes.get(0), candidate.rule() + ":" + candidate.location(),
                    Severity.REVIEW, "'" + nodes.get(0).name() + "' sends a recorded " + candidate.rule()
                            + " value literally in " + candidate.location(),
                    List.of("Value: " + candidate.value(), "Returned by: " + candidate.sourceRequest(),
                            "Review whether it must be extracted (Tools → Find predefined correlations)")));
        }
        String summary = findings.size() + " candidate(s) for review";
        if (!scan.note().isEmpty()) {
            summary += " · " + scan.note();
        }
        return CheckOutcome.of(findings, true, summary);
    }
}
