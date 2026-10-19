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

import java.util.List;
import java.util.Objects;

/**
 * Result of one readiness check.
 *
 * @param checkId check
 * @param state state, derived from the findings unless the check did not run or its evidence is stale
 * @param findings findings, including accepted and informational ones
 * @param summary one-line summary, for instance "12 of 14 transactions exercised"
 * @param stale whether the runtime evidence behind the result no longer matches the plan
 */
public record CheckResult(CheckId checkId, CheckState state, List<Finding> findings, String summary, boolean stale) {

    public CheckResult {
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(state, "state");
        findings = findings == null ? List.of() : List.copyOf(findings);
        summary = summary == null ? "" : summary;
    }

    /**
     * Derives the state from the findings: the worst open finding (Suggestion only when nothing else is open), else
     * Accepted when a finding was accepted, else Passed or Not applicable.
     *
     * @param checkId check
     * @param findings findings, with exceptions already applied
     * @param applicable whether anything in scope was subject to the check
     * @param summary summary
     * @return the result
     */
    public static CheckResult of(CheckId checkId, List<Finding> findings, boolean applicable, String summary) {
        return new CheckResult(checkId, stateOf(findings, applicable), findings, summary, false);
    }

    /**
     * @param checkId check not selected for this run
     * @return a result that keeps the check from counting as verified
     */
    public static CheckResult notRun(CheckId checkId) {
        return new CheckResult(checkId, CheckState.NOT_RUN, List.of(), "Not selected in this run", false);
    }

    /**
     * @param checkId check
     * @param reason why it could not be verified
     * @return a Not verified result without findings
     */
    public static CheckResult notVerified(CheckId checkId, String reason) {
        return new CheckResult(checkId, CheckState.NOT_VERIFIED, List.of(), reason, false);
    }

    /**
     * @param reason why the evidence is stale
     * @return this result marked stale; it counts as Not verified, its findings are kept for reference
     */
    public CheckResult asStale(String reason) {
        return new CheckResult(checkId, CheckState.NOT_VERIFIED, findings, reason, true);
    }

    /**
     * @return a copy with the state derived again from the findings, for instance after exceptions changed
     */
    public CheckResult rederive(boolean applicable) {
        if (stale || state == CheckState.NOT_RUN) {
            return this;
        }
        return new CheckResult(checkId, stateOf(findings, applicable), findings, summary, false);
    }

    static CheckState stateOf(List<Finding> findings, boolean applicable) {
        CheckState worst = null;
        boolean accepted = false;
        for (Finding finding : findings) {
            if (finding.severity() == Severity.INFO) {
                continue;
            }
            if (!finding.isOpen()) {
                accepted = true;
                continue;
            }
            CheckState state = switch (finding.severity()) {
                case BLOCKER -> CheckState.BLOCKER;
                case WARNING -> CheckState.WARNING;
                case REVIEW -> CheckState.REVIEW;
                case SUGGESTION -> CheckState.SUGGESTION;
                default -> CheckState.NOT_VERIFIED;
            };
            if (worst == null || rank(state) < rank(worst)) {
                worst = state;
            }
        }
        if (worst != null) {
            return worst;
        }
        if (accepted) {
            return CheckState.ACCEPTED;
        }
        return applicable ? CheckState.PASSED : CheckState.NOT_APPLICABLE;
    }

    private static int rank(CheckState state) {
        return switch (state) {
            case BLOCKER -> 0;
            case WARNING -> 1;
            case REVIEW -> 2;
            case SUGGESTION -> 4;
            default -> 3;
        };
    }

    /**
     * @return whether the check ran against the current plan and established all of its items
     */
    public boolean isVerified() {
        if (stale || state == CheckState.NOT_RUN || state == CheckState.NOT_VERIFIED) {
            return false;
        }
        return findings.stream().noneMatch(f -> f.isOpen() && f.severity() == Severity.NOT_VERIFIED);
    }

    /**
     * @param severity severity
     * @return number of open findings with that severity, zero for a stale result
     */
    public long openCount(Severity severity) {
        if (stale) {
            return 0;
        }
        return findings.stream().filter(f -> f.isOpen() && f.severity() == severity).count();
    }

    /**
     * @return number of accepted findings
     */
    public long acceptedCount() {
        return findings.stream().filter(Finding::isAccepted).count();
    }
}
