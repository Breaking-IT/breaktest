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

/**
 * Options of a readiness run.
 *
 * @param extractionBudgetNanos processing time budget per extractor invocation (default 3 ms)
 * @param offlineReruns times each captured response is processed again offline to measure extractors (default 20)
 * @param repeatabilityIterations iterations of the repeatability run; 0 when repeatability is not configured
 * @param repeatabilityTimeLimitSeconds time limit of the repeatability run
 */
public record ReadinessOptions(long extractionBudgetNanos, int offlineReruns, int repeatabilityIterations,
        int repeatabilityTimeLimitSeconds) {
    public static final long DEFAULT_BUDGET_NANOS = 3_000_000L;
    public static final int DEFAULT_OFFLINE_RERUNS = 20;
    /** Fewer observations than this make extraction time Not verified */
    public static final int MIN_TIMING_SAMPLES = 5;
    /** p95 is only reported from this many observations on */
    public static final int MIN_P95_SAMPLES = 20;

    public ReadinessOptions {
        if (extractionBudgetNanos <= 0) {
            extractionBudgetNanos = DEFAULT_BUDGET_NANOS;
        }
        offlineReruns = Math.max(0, Math.min(offlineReruns, 1000));
        repeatabilityIterations = Math.max(0, repeatabilityIterations);
        repeatabilityTimeLimitSeconds = Math.max(0, repeatabilityTimeLimitSeconds);
    }

    public static ReadinessOptions defaults() {
        return new ReadinessOptions(DEFAULT_BUDGET_NANOS, DEFAULT_OFFLINE_RERUNS, 0, 0);
    }

    public boolean isRepeatabilityConfigured() {
        return repeatabilityIterations >= 2;
    }
}
