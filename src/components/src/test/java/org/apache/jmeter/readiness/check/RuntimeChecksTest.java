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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.ReadinessVerdict;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.ExtractorObservation;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
import org.apache.jmeter.readiness.evidence.ReadinessRecorder;
import org.apache.jmeter.readiness.evidence.RunKind;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.readiness.evidence.RunStatus;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class RuntimeChecksTest extends JMeterTestCase {
    private static final ValidationSettings ONCE = new ValidationSettings(1, true, 1, true);

    private final TestPlanBuilder plan = new TestPlanBuilder();
    private final ThreadGroup group = group("tg");
    private final TestPlanBuilder.Node flow = plan.root.add(group);
    private String testStart;

    @BeforeEach
    void remember() {
        testStart = JMeterUtils.getProperty("TESTSTART.MS");
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

    private static RegexExtractor regex(String refName, String regex) {
        RegexExtractor extractor = new RegexExtractor();
        extractor.setName("extract " + refName);
        extractor.setRefName(refName);
        extractor.setRegex(regex);
        extractor.setTemplate("$1$");
        extractor.setMatchNumber(1);
        return extractor;
    }

    private static ResponseAssertion assertion() {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setName("contains ok");
        assertion.setTestFieldResponseData();
        assertion.setToContainsType();
        assertion.addTestString("ok");
        return assertion;
    }

    private CheckResult result(ReadinessReport report, CheckId id) {
        return report.result(id).orElseThrow();
    }

    private static List<Finding> open(CheckResult result, Severity severity) {
        return result.findings().stream().filter(f -> f.isOpen() && f.severity() == severity).toList();
    }

    private ReadinessReport analyze(Set<CheckId> checks, AbstractThreadGroup... groups) {
        return plan.analyze(checks, ReadinessOptions.defaults(), groups);
    }

    @Test
    void failedExtractionIsSeenDirectlyEvenWhenTheVariableKeepsAStaleValue() throws Exception {
        var login = sampler("login", "/login");
        login.setProperty("response.1", "token=abc ok");
        login.setProperty("response.2", "no token here ok");
        flow.add(login).add(regex("token", "token=(\\w+)"));
        var next = sampler("next", "/next?t=${token}");
        next.setProperty("response", "ok");
        flow.add(next);
        RunSegment segment = plan.run(RunKind.MAIN, ONCE.withIterations(2), group);
        assertEquals(RunStatus.COMPLETED, segment.status());

        ReadinessReport report = analyze(Set.of(CheckId.UNMATCHED_EXTRACTIONS, CheckId.UNRESOLVED_VARIABLES), group);
        List<Finding> blockers = open(result(report, CheckId.UNMATCHED_EXTRACTIONS), Severity.BLOCKER);
        assertEquals(1, blockers.size(), blockers.toString());
        Finding unmatched = blockers.get(0);
        assertTrue(unmatched.evidence().stream().anyMatch(line -> line.contains("iteration 2")), unmatched.evidence()
                .toString());
        assertTrue(unmatched.evidence().contains("Unmatched in 1 of 2 invocations"), unmatched.evidence().toString());
        assertTrue(unmatched.evidence().contains("Source sampler: login"));
        // The stale value of iteration 1 was sent, so nothing literal was: only instrumentation can tell
        assertTrue(open(result(report, CheckId.UNRESOLVED_VARIABLES), Severity.BLOCKER).isEmpty());
    }

    @Test
    void unresolvedValuesInUrlHeadersBodyAndEncodedFormWithoutDecodingFalsePositives() throws Exception {
        var request = sampler("send", "/a");
        request.setProperty("url", "http://localhost/a?q=${missing}");
        request.setProperty("headers", "X-Id: ${hdr}\nAuthorization: Bearer secret");
        request.setProperty("body", "{\"v\":\"%24%7Benc%7D\",\"price\":\"$5\",\"double\":\"%2524%257Bnope%257D\"}");
        request.setProperty("response", "ok");
        flow.add(request);
        plan.run(RunKind.MAIN, ONCE, group);
        CheckResult result = result(analyze(Set.of(CheckId.UNRESOLVED_VARIABLES), group),
                CheckId.UNRESOLVED_VARIABLES);
        List<Finding> sent = result.findings().stream().filter(f -> f.message().startsWith("Sent")).toList();
        assertEquals(Set.of("missing", "hdr", "enc"), Set.copyOf(sent.stream().map(Finding::findingKey).toList()),
                sent.toString());
        Finding header = sent.stream().filter(f -> f.findingKey().equals("hdr")).findFirst().orElseThrow();
        assertTrue(header.evidence().contains("Location: request headers"), header.evidence().toString());
        assertTrue(header.evidence().contains("Cause: never defined"));
        assertFalse(String.join(" ", header.evidence()).contains("secret"), "Evidence must be redacted");
        Finding encoded = sent.stream().filter(f -> f.findingKey().equals("enc")).findFirst().orElseThrow();
        assertTrue(encoded.evidence().contains("Location: request data (URL-encoded)"), encoded.evidence().toString());
        assertTrue(sent.stream().allMatch(f -> f.severity() == Severity.BLOCKER));
    }

    @Test
    void unmatchedExtractorIsReportedAsTheRootCauseOfAnUnresolvedValue() throws Exception {
        var login = sampler("login", "/login");
        login.setProperty("response", "nothing ok");
        flow.add(login).add(regex("sessionId", "sid=(\\w+)"));
        var next = sampler("next", "/next");
        next.setProperty("url", "http://localhost/next?sid=${sessionId}");
        flow.add(next);
        plan.run(RunKind.MAIN, ONCE, group);
        ReadinessReport report = analyze(TestPlanBuilder.all(), group);
        Finding sent = result(report, CheckId.UNRESOLVED_VARIABLES).findings().stream()
                .filter(f -> f.message().startsWith("Sent")).findFirst().orElseThrow();
        assertTrue(sent.evidence().contains("Cause: its extractor 'extract sessionId' ran but did not match"),
                sent.evidence().toString());
        assertEquals(ReadinessVerdict.NEEDS_ATTENTION, report.summary().verdict());
    }

    @Test
    void optionalExtractorIsAWarning() throws Exception {
        var login = sampler("login", "/login");
        login.setProperty("response", "ok");
        RegexExtractor optional = regex("banner", "banner=(\\w+)");
        ReadinessExceptions.put(optional, new AcceptedException(CheckId.UNMATCHED_EXTRACTIONS,
                ReadinessExceptions.OPTIONAL_EXTRACTOR_KEY, "Only shown on Mondays", "", ""));
        flow.add(login).add(optional);
        plan.run(RunKind.MAIN, ONCE, group);
        CheckResult result = result(analyze(Set.of(CheckId.UNMATCHED_EXTRACTIONS), group),
                CheckId.UNMATCHED_EXTRACTIONS);
        assertEquals(CheckState.WARNING, result.state(), result.findings().toString());
    }

    @Test
    void stopOnErrorRunIsNeverSuccessful() throws Exception {
        group.setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTHREAD);
        var broken = sampler("broken", "/broken");
        broken.setProperty("code", "500");
        flow.add(broken);
        flow.add(sampler("never", "/never")).add(regex("x", "x=(\\d)"));
        RunSegment segment = plan.run(RunKind.MAIN, ONCE, group);
        assertEquals(RunStatus.STOPPED_ON_ERROR, segment.status());
        ReadinessReport report = analyze(TestPlanBuilder.all(), group);
        CheckResult validation = result(report, CheckId.VALIDATION);
        assertEquals(CheckState.BLOCKER, validation.state());
        assertTrue(validation.findings().stream().anyMatch(f -> f.message().contains("Stopped on error")));
        assertTrue(validation.findings().stream().anyMatch(f -> f.findingKey().equals("failed")
                && f.element().name().equals("broken")));
        assertTrue(open(result(report, CheckId.UNMATCHED_EXTRACTIONS), Severity.NOT_VERIFIED).stream()
                .anyMatch(f -> f.message().contains("never executed")));
    }

    @Test
    void cancelledRunIsNeverSuccessful() throws Exception {
        var s = sampler("first", "/first");
        s.setProperty("cancel", true);
        flow.add(s);
        RunSegment segment = plan.run(RunKind.MAIN, ONCE, group);
        assertEquals(RunStatus.CANCELLED, segment.status());
        CheckResult validation = result(analyze(Set.of(CheckId.VALIDATION), group), CheckId.VALIDATION);
        assertEquals(CheckState.BLOCKER, validation.state());
    }

    @Test
    void unexecutedBranchesAndTransactionsAreReportedAndVerificationMustRun() throws Exception {
        TransactionController main = new TransactionController();
        main.setName("main");
        var mainNode = flow.add(main);
        var ok = sampler("ok", "/ok");
        ok.setProperty("response", "ok");
        mainNode.add(ok);
        mainNode.add(assertion());
        IfController never = new IfController("false");
        never.setUseExpression(true);
        never.setName("never");
        TransactionController hidden = new TransactionController();
        hidden.setName("hidden");
        var hiddenNode = flow.add(never).add(hidden);
        hiddenNode.add(sampler("hidden request", "/h"));
        hiddenNode.add(assertion());
        plan.run(RunKind.MAIN, ONCE, group);
        ReadinessReport report = analyze(Set.of(CheckId.EXERCISE_COVERAGE, CheckId.VERIFICATION_COVERAGE), group);
        CheckResult exercise = result(report, CheckId.EXERCISE_COVERAGE);
        assertEquals(Set.of("never", "hidden"), Set.copyOf(open(exercise, Severity.WARNING).stream()
                .map(f -> f.element().name()).toList()), exercise.findings().toString());
        assertEquals("1 of 2 transactions exercised · 0 of 1 branches exercised", exercise.summary());
        CheckResult coverage = result(report, CheckId.VERIFICATION_COVERAGE);
        assertEquals(List.of("hidden"), open(coverage, Severity.NOT_VERIFIED).stream()
                .map(f -> f.element().name()).toList(), coverage.findings().toString());
    }

    @Test
    void editingThePlanMakesRuntimeEvidenceStale() throws Exception {
        var s = sampler("login", "/login");
        s.setProperty("response", "ok");
        flow.add(s).add(assertion());
        plan.run(RunKind.MAIN, ONCE, group);
        ReadinessReport fresh = analyze(Set.of(CheckId.VALIDATION, CheckId.UNRESOLVED_VARIABLES), group);
        assertEquals(CheckState.PASSED, result(fresh, CheckId.VALIDATION).state());
        assertFalse(result(fresh, CheckId.VALIDATION).stale());
        // A field edited in the editor panel and committed: no tree event, only the content changes
        s.setProperty("HTTPSampler.path", "/login2");
        ReadinessReport stale = analyze(Set.of(CheckId.VALIDATION, CheckId.UNRESOLVED_VARIABLES), group);
        assertTrue(result(stale, CheckId.VALIDATION).stale());
        assertEquals(CheckState.NOT_VERIFIED, result(stale, CheckId.VALIDATION).state());
        assertTrue(stale.evidenceStatus().startsWith("Stale"), stale.evidenceStatus());
        assertTrue(result(stale, CheckId.UNRESOLVED_VARIABLES).summary().contains("runtime evidence stale"));
        // Accepting an exception is not a content change
        s.setProperty("HTTPSampler.path", "/login");
        ReadinessExceptions.put(s, new AcceptedException(CheckId.THINK_TIME, "*", "API", "", ""));
        ReadinessReport restored = analyze(Set.of(CheckId.VALIDATION), group);
        assertFalse(result(restored, CheckId.VALIDATION).stale(), restored.evidenceStatus());
    }

    @Test
    void evidenceOfASmallerScopeIsStaleForAWiderOne() throws Exception {
        ThreadGroup other = group("other");
        plan.root.add(other).add(sampler("other", "/o"));
        flow.add(sampler("login", "/login"));
        plan.run(RunKind.MAIN, ONCE, group);
        assertFalse(result(analyze(Set.of(CheckId.VALIDATION), group), CheckId.VALIDATION).stale());
        assertTrue(result(analyze(Set.of(CheckId.VALIDATION), group, other), CheckId.VALIDATION).stale());
    }

    @Test
    void anotherValidationResettingHistoryMakesEvidenceStale() throws Exception {
        flow.add(sampler("login", "/login"));
        plan.run(RunKind.MAIN, ONCE, group);
        ValidationCheckpoints.clear();
        assertTrue(result(analyze(Set.of(CheckId.VALIDATION), group), CheckId.VALIDATION).stale());
    }

    @Test
    void extractionTimingWarmUpOfflineRunsAndInsufficientSamples() throws Exception {
        var login = sampler("login", "/login");
        login.setProperty("response", "token=abc ok");
        flow.add(login).add(regex("token", "token=(\\w+)"));
        RunSegment segment = plan.run(RunKind.MAIN, ONCE, group);
        List<ExtractorObservation> observations = segment.extractions();
        assertEquals(1, observations.size());
        assertTrue(observations.get(0).warmUp());
        assertEquals(Boolean.TRUE, observations.get(0).matched());

        // Only the warm-up invocation: not enough observations
        CheckResult insufficient = result(analyze(Set.of(CheckId.EXTRACTION_TIME), group), CheckId.EXTRACTION_TIME);
        assertTrue(open(insufficient, Severity.NOT_VERIFIED).get(0).message().contains("insufficient samples"));

        JMeterVariables variables = new JMeterVariables();
        variables.put("token", "kept");
        JMeterContextService.getContext().setVariables(variables);
        PlanModel model = plan.model(group);
        Map<String, List<Long>> offline = OfflineExtractorTimer.measure(segment, model, 20, () -> false);
        String id = observations.get(0).extractorId();
        assertEquals(20, offline.get(id).size());
        assertEquals("kept", JMeterContextService.getContext().getVariables().get("token"),
                "Offline runs use scratch variables");
        CheckResult measured = result(plan.analyze(Set.of(CheckId.EXTRACTION_TIME), ReadinessOptions.defaults(),
                offline, group), CheckId.EXTRACTION_TIME);
        Finding timing = measured.findings().get(0);
        assertTrue(timing.evidence().get(0).startsWith("20 observations (0 during validation, 20 offline)"),
                timing.evidence().toString());
        assertTrue(timing.evidence().stream().anyMatch(line -> line.startsWith("Warm-up")));
        assertTrue(timing.evidence().stream().anyMatch(line -> line.contains("p95")));
    }

    @Test
    void timingBudgetBoundaries() {
        long budget = 3_000_000L;
        List<Long> exactly = new ArrayList<>(List.of(budget, budget, budget, budget, budget));
        ExtractionTimeCheck.Stats atBudget = ExtractionTimeCheck.stats(exactly, budget);
        assertFalse(atBudget.isSlow(budget));
        assertEquals(0, atBudget.exceedances());
        assertEquals(-1, atBudget.p95Nanos(), "p95 needs 20 observations");
        List<Long> above = List.of(budget + 1, budget + 1, budget + 1, budget, budget);
        ExtractionTimeCheck.Stats aboveBudget = ExtractionTimeCheck.stats(above, budget);
        assertTrue(aboveBudget.isSlow(budget));
        assertEquals(3, aboveBudget.exceedances());
        List<Long> many = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            many.add((long) i);
        }
        ExtractionTimeCheck.Stats twenty = ExtractionTimeCheck.stats(many, budget);
        assertEquals(19, twenty.p95Nanos());
        assertEquals(10, twenty.medianNanos());
        assertEquals(20, twenty.maxNanos());
    }

    @Test
    void repeatabilityRunsInAFreshSessionAndFailsOnASecondIterationProblem() throws Exception {
        var create = sampler("create user", "/users");
        create.setProperty("response", "created ok");
        create.setProperty("code.2", "409");
        flow.add(create).add(assertion());
        plan.run(RunKind.MAIN, ONCE, group);
        CheckResult notConfigured = result(analyze(Set.of(CheckId.REPEATABILITY), group), CheckId.REPEATABILITY);
        assertEquals(CheckState.NOT_VERIFIED, notConfigured.state());
        assertTrue(notConfigured.summary().contains("not configured"));

        RunSegment repeat = plan.run(RunKind.REPEATABILITY, ONCE.withIterations(2), group);
        assertEquals(RunKind.REPEATABILITY, repeat.kind());
        ReadinessReport report = plan.analyze(Set.of(CheckId.REPEATABILITY, CheckId.VALIDATION),
                new ReadinessOptions(ReadinessOptions.DEFAULT_BUDGET_NANOS, 0, 2, 60), group);
        CheckResult repeatability = result(report, CheckId.REPEATABILITY);
        assertEquals(CheckState.BLOCKER, repeatability.state(), repeatability.findings().toString());
        assertTrue(repeatability.findings().get(0).evidence().stream().anyMatch(l -> l.contains("iteration 2")));
        assertEquals(CheckState.PASSED, result(report, CheckId.VALIDATION).state(),
                "The main validation stays separate from the repeatability run");
    }

    @Test
    void instrumentationIsOffOutsideReadinessRuns() {
        assertFalse(ReadinessRecorder.isActive());
    }
}
