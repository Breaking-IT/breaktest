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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Combines check results into the overall readiness verdict. Never produces a score:
 * <ul>
 *     <li><b>Ready</b>: every check ran against the current plan, every mandatory check is verified and Passed, Not
 *     applicable or Accepted, and nothing is open</li>
 *     <li><b>Needs attention</b>: an open Blocker, or an open Warning / Review finding</li>
 *     <li><b>Not verified</b>: otherwise, for instance a mandatory check that was not run, is stale or Not verified, or
 *     a run of only some of the checks</li>
 * </ul>
 * Suggestions are optional improvements: a check with only suggestions counts as passed and they never change the
 * verdict.
 * Stale results are never counted as findings, only as not verified.
 */
public final class ReadinessAggregator {

    private ReadinessAggregator() {
    }

    /**
     * Summary of a report.
     *
     * @param verdict overall verdict
     * @param results one result per check, in {@link CheckId} order; checks without a result are Not run
     * @param selected number of checks that ran
     * @param passed checks Passed or Not applicable
     * @param accepted checks whose failing findings are all accepted
     * @param blockers open blocker findings
     * @param warnings open warning findings
     * @param reviews open review findings
     * @param suggestions open suggestions, which never affect the verdict
     * @param notVerified checks that ran but are not verified (including stale ones)
     * @param reasons why the verdict is not Ready
     */
    public record Summary(ReadinessVerdict verdict, List<CheckResult> results, int selected, int passed,
            int accepted, long blockers, long warnings, long reviews, long suggestions, int notVerified,
            List<String> reasons) {

        public Summary {
            results = List.copyOf(results);
            reasons = List.copyOf(reasons);
        }

        /**
         * @return counts such as "6 of 8 checks passed · 1 blocker · 1 review pending"
         */
        public String countsText() {
            StringBuilder sb = new StringBuilder();
            sb.append(passed).append(" of ").append(selected).append(selected == 1 ? " check" : " checks")
                    .append(" passed");
            appendCount(sb, accepted, "accepted", "accepted");
            appendCount(sb, blockers, "blocker", "blockers");
            appendCount(sb, warnings, "warning", "warnings");
            appendCount(sb, reviews, "review pending", "reviews pending");
            appendCount(sb, notVerified, "not verified", "not verified");
            appendCount(sb, suggestions, "suggestion", "suggestions");
            return sb.toString();
        }

        private static void appendCount(StringBuilder sb, long count, String one, String many) {
            if (count > 0) {
                sb.append(" · ").append(count).append(' ').append(count == 1 ? one : many);
            }
        }
    }

    /**
     * @param checkResults results of the checks that ran; others are treated as not selected
     * @return the summary
     */
    public static Summary aggregate(List<CheckResult> checkResults) {
        Map<CheckId, CheckResult> byId = new EnumMap<>(CheckId.class);
        for (CheckResult result : checkResults) {
            byId.put(result.checkId(), result);
        }
        List<CheckResult> results = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int selected = 0;
        int passed = 0;
        int accepted = 0;
        int notVerified = 0;
        long blockers = 0;
        long warnings = 0;
        long reviews = 0;
        long suggestions = 0;
        boolean mandatoryUnverified = false;
        boolean subset = false;
        for (CheckId id : CheckId.values()) {
            CheckResult result = byId.getOrDefault(id, CheckResult.notRun(id));
            results.add(result);
            if (result.state() == CheckState.NOT_RUN) {
                subset = true;
                if (id.isMandatory()) {
                    mandatoryUnverified = true;
                    reasons.add(id.title() + " was not run");
                }
                continue;
            }
            selected++;
            switch (result.state()) {
                case PASSED, NOT_APPLICABLE, SUGGESTION -> passed++;
                case ACCEPTED -> accepted++;
                default -> {
                }
            }
            if (!result.isVerified()) {
                notVerified++;
                if (id.isMandatory()) {
                    mandatoryUnverified = true;
                    reasons.add(id.title() + (result.stale() ? " is stale" : " is not verified"));
                }
            }
            blockers += result.openCount(Severity.BLOCKER);
            warnings += result.openCount(Severity.WARNING);
            reviews += result.openCount(Severity.REVIEW);
            suggestions += result.openCount(Severity.SUGGESTION);
        }
        ReadinessVerdict verdict;
        if (blockers > 0 || warnings > 0 || reviews > 0) {
            verdict = ReadinessVerdict.NEEDS_ATTENTION;
            if (blockers > 0) {
                reasons.add(0, blockers + (blockers == 1 ? " blocker" : " blockers") + " open");
            }
            if (warnings + reviews > 0) {
                reasons.add(warnings + reviews + " warning or review finding(s) not covered by an exception");
            }
        } else if (mandatoryUnverified || subset) {
            verdict = ReadinessVerdict.NOT_VERIFIED;
            if (subset && !mandatoryUnverified) {
                reasons.add("Only some of the checks were run");
            }
        } else {
            verdict = ReadinessVerdict.READY;
        }
        return new Summary(verdict, results, selected, passed, accepted, blockers, warnings, reviews, suggestions,
                notVerified, reasons);
    }
}
