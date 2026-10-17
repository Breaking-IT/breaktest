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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.control.ForeachController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.extractor.JSR223PostProcessor;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.sampler.TestAction;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.timers.ConstantTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticChecksTest extends JMeterTestCase {
    private final TestPlanBuilder plan = new TestPlanBuilder();
    private final ThreadGroup group = group("tg");
    private final TestPlanBuilder.Node flow = plan.root.add(group);

    @AfterEach
    void cleanup() {
        ValidationCheckpoints.clear();
    }

    private CheckOutcome run(ReadinessCheck check) {
        return check.run(plan.context(group));
    }

    private static List<Finding> open(CheckOutcome outcome, Severity severity) {
        return outcome.findings().stream().filter(f -> f.severity() == severity).toList();
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

    // ------------------------------------------------------------- unresolved variables

    @Test
    void undefinedVariableIsBlockerButEmptyUdvIsDefined() {
        Arguments udv = new Arguments();
        udv.addArgument("empty", "");
        plan.root.add(udv);
        var s = sampler("search", "/search?q=${empty}&t=${missing}");
        flow.add(s);
        CheckOutcome outcome = run(new UnresolvedVariablesCheck());
        List<Finding> blockers = open(outcome, Severity.BLOCKER);
        assertEquals(1, blockers.size(), outcome.findings().toString());
        assertEquals("missing", blockers.get(0).findingKey());
        assertTrue(blockers.get(0).evidence().contains("Cause: never defined in scope"));
    }

    @Test
    void functionsPropertiesAndExtractedFamiliesAreNotUndefined() {
        var login = sampler("login", "/login");
        flow.add(login).add(regex("token", "token=(\\w+)"));
        flow.add(sampler("next", "/next?a=${token}&b=${token_matchNr}&c=${__P(env,dev)}&d=${__time(,now)}${now}"));
        CheckOutcome outcome = run(new UnresolvedVariablesCheck());
        assertTrue(open(outcome, Severity.BLOCKER).isEmpty(), outcome.findings().toString());
    }

    @Test
    void dynamicAndScriptedReferencesAreUncertainNotUndefined() {
        JSR223PostProcessor script = new JSR223PostProcessor();
        script.setName("script");
        script.setProperty("script", "vars.put(name, 'x')");
        Arguments udv = new Arguments();
        udv.addArgument("i", "1");
        plan.root.add(udv);
        flow.add(sampler("first", "/first")).add(script);
        flow.add(sampler("second", "/x/${__V(id_${i})}/${maybe}"));
        CheckOutcome outcome = run(new UnresolvedVariablesCheck());
        assertTrue(open(outcome, Severity.BLOCKER).isEmpty(), outcome.findings().toString());
        assertTrue(open(outcome, Severity.NOT_VERIFIED).stream().anyMatch(f -> f.findingKey().equals("maybe")));
        assertTrue(open(outcome, Severity.NOT_VERIFIED).stream().anyMatch(f -> f.findingKey().equals("(dynamic)")));
    }

    @Test
    void intentionalLiteralCanBeAccepted() {
        var s = sampler("template", "/t?x=${literal}");
        flow.add(s);
        ReadinessExceptions.put(s, new AcceptedException(CheckId.UNRESOLVED_VARIABLES, "literal",
                "The server expects the literal text", "", "2026-10-17"));
        ReadinessReport report = plan.analyze(Set.of(CheckId.UNRESOLVED_VARIABLES), ReadinessOptions.defaults(),
                group);
        var result = report.result(CheckId.UNRESOLVED_VARIABLES).orElseThrow();
        assertTrue(result.findings().stream().anyMatch(f -> f.findingKey().equals("literal") && f.isAccepted()));
        assertEquals(0, result.openCount(Severity.BLOCKER));
    }

    // ------------------------------------------------------------- verification coverage

    @Test
    void transactionWithoutVerificationIsBlockerAndInheritedAssertionCovers() {
        TransactionController checkout = new TransactionController();
        checkout.setName("checkout");
        var checkoutNode = flow.add(checkout);
        checkoutNode.add(sampler("pay", "/pay"));
        TransactionController browse = new TransactionController();
        browse.setName("browse");
        var browseNode = flow.add(browse);
        browseNode.add(sampler("list", "/list"));
        browseNode.add(assertion());
        CheckOutcome outcome = run(new VerificationCoverageCheck());
        List<Finding> blockers = open(outcome, Severity.BLOCKER);
        assertEquals(1, blockers.size(), outcome.findings().toString());
        assertEquals("checkout", blockers.get(0).element().name());
        assertTrue(outcome.summary().startsWith("1 of 2"), outcome.summary());
    }

    @Test
    void disabledAssertionAndDisabledAncestorsDoNotCount() {
        TransactionController tx = new TransactionController();
        tx.setName("tx");
        var txNode = flow.add(tx);
        txNode.add(sampler("a", "/a"));
        ResponseAssertion disabled = assertion();
        disabled.setEnabled(false);
        txNode.add(disabled);
        TransactionController off = new TransactionController();
        off.setName("disabled tx");
        off.setEnabled(false);
        flow.add(off).add(sampler("b", "/b"));
        CheckOutcome outcome = run(new VerificationCoverageCheck());
        assertEquals(List.of("tx"), open(outcome, Severity.BLOCKER).stream().map(f -> f.element().name()).toList());
    }

    @Test
    void nestedTransactionCoversOuterOnlyThroughItsSamplers() {
        TransactionController outer = new TransactionController();
        outer.setName("outer");
        var outerNode = flow.add(outer);
        outerNode.add(sampler("outer request", "/o"));
        TransactionController inner = new TransactionController();
        inner.setName("inner");
        var innerNode = outerNode.add(inner);
        innerNode.add(sampler("inner request", "/i"));
        innerNode.add(assertion());
        CheckOutcome outcome = run(new VerificationCoverageCheck());
        assertTrue(open(outcome, Severity.BLOCKER).isEmpty(), outcome.findings().toString());
        Finding outerInfo = outcome.findings().stream()
                .filter(f -> f.element().name().equals("outer") && f.severity() == Severity.INFO).findFirst()
                .orElseThrow();
        assertTrue(outerInfo.evidence().contains("Covered requests: inner request"), outerInfo.evidence().toString());
        assertTrue(outerInfo.evidence().contains("Requests without verification: outer request"));
    }

    @Test
    void failOnNoMatchExtractorVerifiesAndStaticResourcesAreWeak() {
        TransactionController tx = new TransactionController();
        tx.setName("page");
        var txNode = flow.add(tx);
        txNode.add(sampler("html", "/home"));
        txNode.add(sampler("style", "/app.css")).add(assertion());
        TransactionController api = new TransactionController();
        api.setName("api");
        RegexExtractor failing = regex("id", "id=(\\d+)");
        failing.setFailOnNoMatch(true);
        flow.add(api).add(sampler("call", "/api")).add(failing);
        CheckOutcome outcome = run(new VerificationCoverageCheck());
        assertTrue(open(outcome, Severity.BLOCKER).isEmpty(), outcome.findings().toString());
        assertEquals(List.of("page"), open(outcome, Severity.WARNING).stream().map(f -> f.element().name()).toList());
        assertTrue(open(outcome, Severity.NOT_VERIFIED).stream().anyMatch(f -> f.findingKey().equals("exercised")),
                "Exercised coverage needs a validation run");
    }

    @Test
    void samplersOutsideTransactionsAreTheirOwnUnitAndNoRequestSamplersAreSkipped() {
        flow.add(sampler("bare", "/bare"));
        TestAction pause = new TestAction();
        pause.setName("pause");
        flow.add(pause);
        CheckOutcome outcome = run(new VerificationCoverageCheck());
        assertEquals(List.of("bare"), open(outcome, Severity.BLOCKER).stream().map(f -> f.element().name()).toList());
        assertEquals(null, open(outcome, Severity.BLOCKER).get(0).transaction());
    }

    // ------------------------------------------------------------- unused extractions

    @Test
    void unusedExtractionWarnsAndFamiliesCountAsUse() {
        var login = flow.add(sampler("login", "/login"));
        login.add(regex("unused", "u=(\\w+)"));
        login.add(regex("items", "item=(\\w+)"));
        login.add(regex("count", "c=(\\d+)"));
        ForeachController foreach = new ForeachController();
        foreach.setName("each item");
        foreach.setInputVal("items");
        foreach.setReturnVal("item");
        flow.add(foreach).add(sampler("item", "/item/${item}?n=${count_matchNr}"));
        CheckOutcome outcome = run(new UnusedExtractionsCheck());
        assertEquals(List.of("unused"), open(outcome, Severity.WARNING).stream().map(Finding::findingKey).toList());
    }

    @Test
    void scriptedUsageIsPossiblyUsedNotUnused() {
        flow.add(sampler("login", "/login")).add(regex("token", "t=(\\w+)"));
        JSR223PostProcessor script = new JSR223PostProcessor();
        script.setName("dynamic reader");
        script.setProperty("script", "def v = vars.get(prefix + 'en')");
        flow.add(sampler("next", "/next")).add(script);
        CheckOutcome outcome = run(new UnusedExtractionsCheck());
        assertTrue(open(outcome, Severity.WARNING).isEmpty(), outcome.findings().toString());
        assertTrue(outcome.findings().stream().anyMatch(f -> f.severity() == Severity.INFO
                && f.message().contains("possibly used (scripted / dynamic reference)")));
    }

    @Test
    void literalScriptReadCountsAsUse() {
        flow.add(sampler("login", "/login")).add(regex("token", "t=(\\w+)"));
        JSR223PostProcessor script = new JSR223PostProcessor();
        script.setProperty("script", "log.info(vars.get('token'))");
        flow.add(sampler("next", "/next")).add(script);
        assertTrue(open(run(new UnusedExtractionsCheck()), Severity.WARNING).isEmpty());
    }

    @Test
    void overwriteBeforeReadIsReported() {
        flow.add(sampler("first", "/a")).add(regex("id", "a=(\\d+)"));
        flow.add(sampler("second", "/b")).add(regex("id", "b=(\\d+)"));
        flow.add(sampler("third", "/c/${id}"));
        CheckOutcome outcome = run(new UnusedExtractionsCheck());
        List<Finding> warnings = open(outcome, Severity.WARNING);
        assertEquals(1, warnings.size(), outcome.findings().toString());
        assertEquals("extract id", warnings.get(0).element().name());
        assertTrue(warnings.get(0).message().contains("overwritten"));
    }

    // ------------------------------------------------------------- think time

    private static TransactionController tx(String name) {
        TransactionController tx = new TransactionController();
        tx.setName(name);
        return tx;
    }

    private static ConstantTimer timer(String name, String delay) {
        ConstantTimer timer = new ConstantTimer();
        timer.setName(name);
        timer.setDelay(delay);
        return timer;
    }

    @Test
    void missingDelayBetweenStepsAndDuplicatedTimers() {
        flow.add(tx("home")).add(sampler("home", "/"));
        var search = flow.add(tx("search"));
        var request = search.add(sampler("search", "/search"));
        request.add(timer("think", "2000"));
        request.add(timer("another", "1000"));
        flow.add(tx("logout")).add(sampler("logout", "/logout"));
        CheckOutcome outcome = run(new ThinkTimeCheck());
        List<Finding> reviews = open(outcome, Severity.REVIEW);
        assertTrue(reviews.stream().anyMatch(f -> f.findingKey().equals("missing-delay")
                && f.element().name().equals("logout")), reviews.toString());
        assertTrue(reviews.stream().anyMatch(f -> f.findingKey().equals("duplicate-delay")));
        assertFalse(reviews.stream().anyMatch(f -> f.findingKey().equals("missing-delay")
                && f.element().name().equals("search")));
        assertTrue(outcome.summary().startsWith("1 of 2 step transitions"), outcome.summary());
    }

    @Test
    void iterationPacingIsReportedApartFromThinkTime() {
        group.setPacingMode(org.apache.jmeter.threads.AbstractThreadGroup.PACING_FIXED);
        group.setFixedPacing("10000");
        flow.add(tx("a")).add(sampler("a", "/a"));
        flow.add(tx("b")).add(sampler("b", "/b"));
        CheckOutcome outcome = run(new ThinkTimeCheck());
        assertTrue(outcome.findings().stream().anyMatch(f -> f.findingKey().equals("pacing")
                && f.severity() == Severity.INFO && f.message().contains("not think time")));
        assertTrue(open(outcome, Severity.REVIEW).stream().anyMatch(f -> f.findingKey().equals("missing-delay")),
                "pacing must not count as think time between steps");
    }

    @Test
    void flowControlPauseCountsAndTimerOnEmbeddedResourcesIsSuspicious() {
        var page = flow.add(tx("page"));
        page.add(sampler("html", "/"));
        page.add(sampler("css", "/a.css"));
        page.add(timer("per request", "500"));
        TestAction pause = new TestAction();
        pause.setName("think");
        pause.setAction(TestAction.PAUSE);
        pause.setDuration("1000");
        flow.add(pause);
        flow.add(tx("next")).add(sampler("next", "/next"));
        CheckOutcome outcome = run(new ThinkTimeCheck());
        assertFalse(open(outcome, Severity.REVIEW).stream().anyMatch(f -> f.findingKey().equals("missing-delay")),
                outcome.findings().toString());
        assertTrue(open(outcome, Severity.REVIEW).stream().anyMatch(f -> f.findingKey().equals("timer-scope")));
    }

    @Test
    void backgroundMarkingCoversTheSubtree() {
        flow.add(tx("a")).add(sampler("a", "/a"));
        TransactionController api = tx("api");
        flow.add(api).add(sampler("poll", "/poll"));
        ReadinessExceptions.put(api, new AcceptedException(CheckId.THINK_TIME,
                ReadinessExceptions.BACKGROUND_ACTIVITY_KEY, "Polling API, no user", "", ""));
        ReadinessReport report = plan.analyze(Set.of(CheckId.THINK_TIME), ReadinessOptions.defaults(), group);
        var result = report.result(CheckId.THINK_TIME).orElseThrow();
        assertEquals(CheckState.ACCEPTED, result.state(), result.findings().toString());
        assertTrue(report.unusedExceptions().isEmpty());
    }

    // ------------------------------------------------------------- files

    @Test
    void missingAndPresentDataFiles(@TempDir Path dir) throws Exception {
        File present = dir.resolve("users.csv").toFile();
        Files.writeString(present.toPath(), "user,password\njo,x\n", StandardCharsets.UTF_8);
        CSVDataSet ok = new CSVDataSet();
        ok.setName("users");
        ok.setProperty("filename", present.getAbsolutePath());
        CSVDataSet missing = new CSVDataSet();
        missing.setName("missing");
        missing.setProperty("filename", dir.resolve("nope.csv").toString());
        flow.add(ok);
        flow.add(missing);
        flow.add(sampler("login", "/login?u=${user}"));
        CheckOutcome outcome = run(new FilesCheck());
        assertEquals(List.of("missing"), open(outcome, Severity.BLOCKER).stream().map(f -> f.element().name()).toList());
        // The header of a CSV file without variable names defines the variables
        assertTrue(open(run(new UnresolvedVariablesCheck()), Severity.BLOCKER).isEmpty());
    }

    // ------------------------------------------------------------- scope

    @Test
    void elementsOutsideTheScopeProduceNoFindings() {
        ThreadGroup other = group("other");
        plan.root.add(other).add(sampler("other", "/x?${undefined}"));
        flow.add(sampler("in scope", "/ok")).add(assertion());
        CheckOutcome outcome = run(new UnresolvedVariablesCheck());
        assertTrue(open(outcome, Severity.BLOCKER).isEmpty(), outcome.findings().toString());
    }
}
