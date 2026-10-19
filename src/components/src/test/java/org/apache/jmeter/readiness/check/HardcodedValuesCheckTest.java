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

import static org.apache.jmeter.readiness.check.TestPlanBuilder.group;
import static org.apache.jmeter.readiness.check.TestPlanBuilder.sampler;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HardcodedValuesCheckTest extends JMeterTestCase {
    private final TestPlanBuilder plan = new TestPlanBuilder();
    private final ThreadGroup group = group("tg");

    @AfterEach
    void cleanup() {
        ValidationCheckpoints.clear();
    }

    private CheckOutcome run(HardcodedValues.Scan scan) {
        return new HardcodedValuesCheck().run(new CheckContext(plan.model(group), null, ReadinessOptions.defaults(),
                Map.of(), scan, Map.of()));
    }

    @Test
    void cachedScanBecomesUnverifiedAfterRequestEditsButNotExceptionEdits() {
        var order = sampler("order", "/order?csrf=recorded");
        plan.root.add(group).add(order);
        List<String> scope = List.of(group.getThreadGroupId());
        var scan = new HardcodedValues.Scan(List.of(new HardcodedValues.Candidate(
                ValidationCheckpoints.sourceIdOfSource(order), "reco…", "CSRF", "query", "home")), true, "");
        var snapshot = new HardcodedValues.Snapshot(scan,
                PlanFingerprint.compute(plan.model(group).tree(), null, scope));
        ReadinessExceptions.put(order, new AcceptedException(CheckId.THINK_TIME, "*", "API", "", ""));
        assertSame(scan, snapshot.forPlan(plan.model(group).tree(), scope));
        order.setProperty("HTTPSampler.path", "/order?csrf=${csrf}");
        CheckOutcome stale = run(snapshot.forPlan(plan.model(group).tree(), scope));
        assertTrue(stale.notVerifiedReason().contains("plan changed"));
        assertTrue(stale.findings().isEmpty());
        var clean = new HardcodedValues.Snapshot(new HardcodedValues.Scan(List.of(), true, ""),
                PlanFingerprint.compute(plan.model(group).tree(), null, scope));
        order.setProperty("HTTPSampler.path", "/order?csrf=recorded");
        assertTrue(run(clean.forPlan(plan.model(group).tree(), scope)).notVerifiedReason().contains("plan changed"));
    }

    @Test
    void candidatesAreForReviewOnly() {
        var order = sampler("order", "/order?csrf=a1b2c3d4");
        plan.root.add(group).add(order);
        String id = ValidationCheckpoints.sourceIdOfSource(order);
        CheckOutcome outcome = run(new HardcodedValues.Scan(List.of(new HardcodedValues.Candidate(id,
                "a1b2… (8 characters)", "CSRF token", "query parameter 'csrf'", "home")), true, ""));
        assertEquals(1, outcome.findings().size());
        Finding finding = outcome.findings().get(0);
        assertEquals(Severity.REVIEW, finding.severity());
        assertEquals("order", finding.element().name());
        assertTrue(finding.evidence().contains("Returned by: home"), finding.evidence().toString());
    }

    @Test
    void withoutRecordedResponsesTheCheckIsNotVerified() {
        plan.root.add(group).add(sampler("order", "/order"));
        CheckOutcome outcome = run(HardcodedValues.Scan.unavailable("no thread group in scope has linked recorded"
                + " responses"));
        assertTrue(outcome.notVerifiedReason().contains("linked recorded responses"), outcome.notVerifiedReason());
        assertTrue(run(null).notVerifiedReason().startsWith("Not verified"));
    }
}
