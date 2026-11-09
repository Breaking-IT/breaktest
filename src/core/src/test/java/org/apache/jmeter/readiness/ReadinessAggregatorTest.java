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

package org.apache.jmeter.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReadinessAggregatorTest {
    private static final ElementRef ELEMENT = new ElementRef("1:1", "login", "HTTPSamplerProxy", List.of());

    private static Finding finding(CheckId id, Severity severity) {
        return new Finding(id, ELEMENT, "key", severity, "message", List.of(), null);
    }

    private static List<CheckResult> allPassed() {
        List<CheckResult> results = new ArrayList<>();
        for (CheckId id : CheckId.values()) {
            results.add(CheckResult.of(id, List.of(), true, ""));
        }
        return results;
    }

    private static List<CheckResult> replace(List<CheckResult> results, CheckResult result) {
        List<CheckResult> copy = new ArrayList<>(results);
        copy.replaceAll(r -> r.checkId() == result.checkId() ? result : r);
        return copy;
    }

    @Test
    void everyCheckPassedIsReady() {
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(allPassed());
        assertEquals(ReadinessVerdict.READY, summary.verdict());
        assertEquals(CheckId.values().length + " of " + CheckId.values().length + " checks passed",
                summary.countsText());
    }

    @Test
    void notApplicableCountsAsPassedButNeverAsScore() {
        var results = replace(allPassed(), CheckResult.of(CheckId.FILES, List.of(), false, ""));
        assertEquals(CheckState.NOT_APPLICABLE, results.get(CheckId.FILES.ordinal()).state());
        assertEquals(ReadinessVerdict.READY, ReadinessAggregator.aggregate(results).verdict());
    }

    @Test
    void blockerNeedsAttention() {
        var results = replace(allPassed(), CheckResult.of(CheckId.UNRESOLVED_VARIABLES,
                List.of(finding(CheckId.UNRESOLVED_VARIABLES, Severity.BLOCKER)), true, ""));
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(results);
        assertEquals(ReadinessVerdict.NEEDS_ATTENTION, summary.verdict());
        assertEquals(1, summary.blockers());
        assertTrue(summary.countsText().contains("1 blocker"), summary.countsText());
    }

    @Test
    void warningOnOptionalCheckNeedsAttentionUnlessAccepted() {
        Finding warning = finding(CheckId.UNUSED_EXTRACTIONS, Severity.WARNING);
        var open = replace(allPassed(), CheckResult.of(CheckId.UNUSED_EXTRACTIONS, List.of(warning), true, ""));
        assertEquals(ReadinessVerdict.NEEDS_ATTENTION, ReadinessAggregator.aggregate(open).verdict());

        AcceptedException exception = new AcceptedException(CheckId.UNUSED_EXTRACTIONS, "key", "kept for debug",
                "", "2026-10-17");
        CheckResult accepted = CheckResult.of(CheckId.UNUSED_EXTRACTIONS, List.of(warning.withException(exception)),
                true, "");
        assertEquals(CheckState.ACCEPTED, accepted.state());
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(replace(allPassed(), accepted));
        assertEquals(ReadinessVerdict.READY, summary.verdict());
        assertEquals(1, summary.accepted());
        assertTrue(summary.countsText().contains("1 accepted"), summary.countsText());
    }

    @Test
    void reviewFindingNeedsAttention() {
        var results = replace(allPassed(), CheckResult.of(CheckId.THINK_TIME,
                List.of(finding(CheckId.THINK_TIME, Severity.REVIEW)), true, ""));
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(results);
        assertEquals(ReadinessVerdict.NEEDS_ATTENTION, summary.verdict());
        assertTrue(summary.countsText().contains("1 review pending"), summary.countsText());
    }

    @Test
    void subsetRunIsNeverReady() {
        List<CheckResult> subset = new ArrayList<>(allPassed());
        subset.removeIf(r -> r.checkId() == CheckId.HARDCODED_VALUES);
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(subset);
        assertEquals(ReadinessVerdict.NOT_VERIFIED, summary.verdict());
        assertEquals(CheckState.NOT_RUN, summary.results().get(CheckId.HARDCODED_VALUES.ordinal()).state());
    }

    @Test
    void mandatoryCheckNotRunIsNotVerified() {
        List<CheckResult> subset = new ArrayList<>(allPassed());
        subset.removeIf(r -> r.checkId() == CheckId.VALIDATION);
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(subset);
        assertEquals(ReadinessVerdict.NOT_VERIFIED, summary.verdict());
        assertTrue(summary.reasons().contains("Successful validation was not run"), summary.reasons().toString());
    }

    @Test
    void staleMandatoryEvidenceIsNotVerifiedAndItsBlockersDoNotCount() {
        CheckResult stale = CheckResult.of(CheckId.UNMATCHED_EXTRACTIONS,
                List.of(finding(CheckId.UNMATCHED_EXTRACTIONS, Severity.BLOCKER)), true, "").asStale("plan changed");
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(replace(allPassed(), stale));
        assertEquals(ReadinessVerdict.NOT_VERIFIED, summary.verdict());
        assertEquals(0, summary.blockers());
        assertTrue(stale.stale());
        assertFalse(stale.isVerified());
    }

    @Test
    void incompleteValidationIsNotVerified() {
        var results = replace(allPassed(), CheckResult.notVerified(CheckId.VALIDATION, "requires runtime validation"));
        assertEquals(ReadinessVerdict.NOT_VERIFIED, ReadinessAggregator.aggregate(results).verdict());
    }

    @Test
    void openNotVerifiedFindingOfMandatoryCheckIsNotVerified() {
        var results = replace(allPassed(), CheckResult.of(CheckId.UNMATCHED_EXTRACTIONS,
                List.of(finding(CheckId.UNMATCHED_EXTRACTIONS, Severity.NOT_VERIFIED)), true, ""));
        assertEquals(ReadinessVerdict.NOT_VERIFIED, ReadinessAggregator.aggregate(results).verdict());
    }

    @Test
    void notVerifiedOptionalCheckDoesNotPreventReady() {
        var results = replace(allPassed(), CheckResult.notVerified(CheckId.REPEATABILITY, "not configured"));
        assertEquals(ReadinessVerdict.READY, ReadinessAggregator.aggregate(results).verdict());
    }

    @Test
    void suggestionsNeverChangeTheVerdictOrCountAsPendingReviews() {
        CheckResult suggestion = CheckResult.of(CheckId.RESPONSE_BODY_STORAGE,
                List.of(finding(CheckId.RESPONSE_BODY_STORAGE, Severity.SUGGESTION)), true, "");
        assertEquals(CheckState.SUGGESTION, suggestion.state());
        assertTrue(suggestion.isVerified());
        ReadinessAggregator.Summary summary = ReadinessAggregator.aggregate(replace(allPassed(), suggestion));
        assertEquals(ReadinessVerdict.READY, summary.verdict());
        assertEquals(0, summary.reviews());
        assertEquals(1, summary.suggestions());
        assertEquals(CheckId.values().length, summary.passed());
        assertTrue(summary.countsText().endsWith("1 suggestion"), summary.countsText());
        // A suggestion never hides a real problem of the same check
        CheckResult mixed = CheckResult.of(CheckId.RESPONSE_BODY_STORAGE, List.of(
                finding(CheckId.RESPONSE_BODY_STORAGE, Severity.SUGGESTION),
                finding(CheckId.RESPONSE_BODY_STORAGE, Severity.REVIEW)), true, "");
        assertEquals(CheckState.REVIEW, mixed.state());
    }

    @Test
    void stateIsWorstOpenFindingAndInfoIsIgnored() {
        CheckResult result = CheckResult.of(CheckId.VERIFICATION_COVERAGE, List.of(
                finding(CheckId.VERIFICATION_COVERAGE, Severity.INFO),
                finding(CheckId.VERIFICATION_COVERAGE, Severity.WARNING),
                finding(CheckId.VERIFICATION_COVERAGE, Severity.BLOCKER)), true, "");
        assertEquals(CheckState.BLOCKER, result.state());
        CheckResult info = CheckResult.of(CheckId.VERIFICATION_COVERAGE,
                List.of(finding(CheckId.VERIFICATION_COVERAGE, Severity.INFO)), true, "");
        assertEquals(CheckState.PASSED, info.state());
    }
}
