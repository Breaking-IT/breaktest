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
import java.util.Locale;
import java.util.Set;

import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.ExtractorObservation;

/**
 * Check 5: processing time of each supported extractor, measured with {@link System#nanoTime()} apart from the request.
 * The response is decoded once before timing, so decoding is excluded. The first invocation is warm-up and reported
 * apart. Observations made during validation are complemented by offline runs of the extractor against a captured
 * response, with scratch variables, so no request is replayed.
 */
public final class ExtractionTimeCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.EXTRACTION_TIME;
    }

    /**
     * Timing statistics of one extractor.
     *
     * @param count observations, warm-up excluded
     * @param medianNanos median
     * @param p95Nanos 95th percentile, or -1 below {@link ReadinessOptions#MIN_P95_SAMPLES} observations
     * @param maxNanos maximum
     * @param exceedances observations strictly above the budget
     */
    public record Stats(int count, long medianNanos, long p95Nanos, long maxNanos, int exceedances) {
        /**
         * @param budgetNanos budget
         * @return whether the extraction is slow: its median, or its p95 when known, is above the budget
         */
        public boolean isSlow(long budgetNanos) {
            return medianNanos > budgetNanos || p95Nanos > budgetNanos;
        }
    }

    /**
     * @param nanos observations, warm-up excluded
     * @param budgetNanos budget
     * @return statistics
     */
    public static Stats stats(List<Long> nanos, long budgetNanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(null);
        int n = sorted.size();
        if (n == 0) {
            return new Stats(0, 0, -1, 0, 0);
        }
        long median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        long p95 = n >= ReadinessOptions.MIN_P95_SAMPLES
                ? sorted.get(Math.min(n - 1, (int) Math.ceil(0.95 * n) - 1)) : -1;
        int exceedances = (int) sorted.stream().filter(v -> v > budgetNanos).count();
        return new Stats(n, median, p95, sorted.get(n - 1), exceedances);
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        RuntimeEvidence evidence = context.evidence();
        if (evidence == null) {
            return CheckOutcome.notVerified("Not verified — requires runtime validation");
        }
        long budget = context.options().extractionBudgetNanos();
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int measured = 0;
        int extractors = 0;
        for (PlanNode node : context.model().nodes()) {
            if (!seen.add(node.sourceId()) || ExtractorSupport.describe(node.element()).isEmpty()) {
                continue;
            }
            extractors++;
            List<Long> values = new ArrayList<>();
            Long warmUp = null;
            for (ExtractorObservation observation : evidence.extractionsOf(node.sourceId())) {
                if (observation.warmUp() && warmUp == null) {
                    warmUp = observation.nanos();
                } else {
                    values.add(observation.nanos());
                }
            }
            int inRun = values.size();
            values.addAll(context.offlineTimings().getOrDefault(node.sourceId(), List.of()));
            Stats stats = stats(values, budget);
            List<String> details = new ArrayList<>();
            details.add(stats.count() + " observations (" + inRun + " during validation, "
                    + (stats.count() - inRun) + " offline)");
            if (warmUp != null) {
                details.add("Warm-up (first invocation, excluded): " + millis(warmUp));
            }
            if (stats.count() < ReadinessOptions.MIN_TIMING_SAMPLES) {
                findings.add(CheckContext.finding(id(), node, "time", Severity.NOT_VERIFIED,
                        "'" + node.name() + "': Not verified (insufficient samples)", details));
                continue;
            }
            measured++;
            details.add("Median " + millis(stats.medianNanos())
                    + (stats.p95Nanos() >= 0 ? ", p95 " + millis(stats.p95Nanos()) : "")
                    + ", max " + millis(stats.maxNanos()) + ", " + stats.exceedances() + " above "
                    + millis(budget));
            boolean slow = stats.isSlow(budget);
            findings.add(CheckContext.finding(id(), node, "time", slow ? Severity.WARNING : Severity.INFO,
                    "'" + node.name() + "'" + (slow ? " exceeds the " : " is within the ") + millis(budget)
                            + " budget", details));
        }
        return CheckOutcome.of(findings, extractors > 0, measured + " of " + extractors
                + " extractors measured · budget " + millis(budget) + " per invocation, decoding excluded");
    }

    static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
    }
}
