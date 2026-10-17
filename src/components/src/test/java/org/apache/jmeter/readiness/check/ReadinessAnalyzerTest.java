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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.ReadinessVerdict;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
import org.apache.jmeter.readiness.evidence.RunKind;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class ReadinessAnalyzerTest extends JMeterTestCase {
    private final TestPlanBuilder plan = new TestPlanBuilder();
    private final ThreadGroup group = group("tg");
    private TestPlanBuilder.EchoSampler next;
    private String testStart;

    @BeforeEach
    void buildReadyPlan() {
        testStart = JMeterUtils.getProperty("TESTSTART.MS");
        var flow = plan.root.add(group);
        TransactionController home = new TransactionController();
        home.setName("home");
        var homeNode = flow.add(home);
        var homeRequest = sampler("home", "/home");
        homeRequest.setProperty("response", "ok token=abc");
        var homeSampler = homeNode.add(homeRequest);
        RegexExtractor token = new RegexExtractor();
        token.setName("token");
        token.setRefName("token");
        token.setRegex("token=(\\w+)");
        token.setTemplate("$1$");
        token.setMatchNumber(1);
        homeSampler.add(token);
        homeNode.add(assertion());
        TransactionController search = new TransactionController();
        search.setName("search");
        var searchNode = flow.add(search);
        next = sampler("search", "/search?t=${token}");
        next.setProperty("response", "ok");
        var nextSampler = searchNode.add(next);
        ConstantTimer think = new ConstantTimer();
        think.setName("think");
        think.setDelay("3000");
        nextSampler.add(think);
        searchNode.add(assertion());
    }

    @AfterEach
    void cleanup() {
        ReadinessEvidenceStore.clear();
        ValidationCheckpoints.clear();
        JMeterContextService.setValidationRun(false);
        if (testStart == null) {
            JMeterUtils.getJMeterProperties().remove("TESTSTART.MS");
        } else {
            JMeterUtils.setProperty("TESTSTART.MS", testStart);
        }
        JMeterContextService.getContext().clear();
    }

    private static ResponseAssertion assertion() {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setName("ok");
        assertion.setTestFieldResponseData();
        assertion.setToContainsType();
        assertion.addTestString("ok");
        return assertion;
    }

    private ReadinessReport analyze(Set<CheckId> checks) throws Exception {
        RunSegment segment = ReadinessEvidenceStore.latest().main();
        Map<String, List<Long>> offline = OfflineExtractorTimer.measure(segment, plan.model(group), 20, () -> false);
        return plan.analyze(checks, ReadinessOptions.defaults(), offline, group);
    }

    @Test
    void readyPlanIsReadyAndShowsCounts() throws Exception {
        plan.run(RunKind.MAIN, new ValidationSettings(1, true, 1, true), group);
        ReadinessReport report = analyze(TestPlanBuilder.all());
        assertEquals(ReadinessVerdict.READY, report.summary().verdict(), report.summary().reasons() + " "
                + report.results());
        assertEquals(CheckState.NOT_VERIFIED, report.result(CheckId.REPEATABILITY).orElseThrow().state());
        assertTrue(report.summary().countsText().contains("of 11 checks passed"), report.summary().countsText());
        assertEquals("2 of 2 transactions exercised · 0 of 0 branches exercised",
                report.result(CheckId.EXERCISE_COVERAGE).orElseThrow().summary());
    }

    @Test
    void subsetOfChecksIsNeverReady() throws Exception {
        plan.run(RunKind.MAIN, new ValidationSettings(1, true, 1, true), group);
        Set<CheckId> subset = EnumSet.allOf(CheckId.class);
        subset.remove(CheckId.THINK_TIME);
        assertEquals(ReadinessVerdict.NOT_VERIFIED, analyze(subset).summary().verdict());
    }

    @Test
    void withoutValidationRuntimeChecksAreNotVerifiedNotHidden() {
        ReadinessReport report = plan.analyze(TestPlanBuilder.all(), ReadinessOptions.defaults(), group);
        assertEquals(ReadinessVerdict.NOT_VERIFIED, report.summary().verdict());
        for (CheckId id : List.of(CheckId.VALIDATION, CheckId.UNMATCHED_EXTRACTIONS, CheckId.EXTRACTION_TIME,
                CheckId.EXERCISE_COVERAGE)) {
            var result = report.result(id).orElseThrow();
            assertEquals(CheckState.NOT_VERIFIED, result.state(), id.toString());
            assertTrue(result.summary().contains("requires runtime validation"), result.summary());
        }
        assertEquals("No runtime evidence", report.evidenceStatus());
    }

    @Test
    void unusedExceptionsAreListedAndAcceptedFindingsAreNotPasses() throws Exception {
        ReadinessExceptions.put(next, new AcceptedException(CheckId.UNRESOLVED_VARIABLES, "gone",
                "Was a literal once", "", "2026-01-01"));
        plan.run(RunKind.MAIN, new ValidationSettings(1, true, 1, true), group);
        ReadinessReport report = analyze(TestPlanBuilder.all());
        assertEquals(1, report.unusedExceptions().size());
        assertEquals("gone", report.unusedExceptions().get(0).exception().findingKey());
        assertEquals("search", report.unusedExceptions().get(0).element().name());

        // A finding covered by an exception is shown as Accepted, never as Passed
        next.setProperty("HTTPSampler.path", "/search?t=${token}&x=${undefined}");
        ReadinessExceptions.put(next, new AcceptedException(CheckId.UNRESOLVED_VARIABLES, "undefined",
                "Placeholder the server keeps", "", "2026-01-01"));
        var unresolved = plan.analyze(Set.of(CheckId.UNRESOLVED_VARIABLES), ReadinessOptions.defaults(), group)
                .result(CheckId.UNRESOLVED_VARIABLES).orElseThrow();
        assertEquals(1, unresolved.acceptedCount());
    }
}
