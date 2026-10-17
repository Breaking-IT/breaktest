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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessAggregator;

/**
 * An immutable readiness report, structured so another front end (CLI, MCP) could present it.
 *
 * @param createdAt when the report was built
 * @param fingerprint fingerprint of the scope when the report was built
 * @param scope names of the thread groups in scope
 * @param summary verdict and counts
 * @param evidenceStatus whether runtime evidence exists and is current
 * @param unusedExceptions exceptions that no longer match any finding
 */
public record ReadinessReport(Instant createdAt, String fingerprint, List<String> scope,
        ReadinessAggregator.Summary summary, String evidenceStatus, List<UnusedException> unusedExceptions) {

    /**
     * @param element element holding the exception
     * @param exception the exception
     */
    public record UnusedException(ElementRef element, AcceptedException exception) {
    }

    public ReadinessReport {
        scope = List.copyOf(scope);
        unusedExceptions = List.copyOf(unusedExceptions);
    }

    public List<CheckResult> results() {
        return summary.results();
    }

    public Optional<CheckResult> result(CheckId id) {
        return summary.results().stream().filter(r -> r.checkId() == id).findFirst();
    }

    /**
     * @return all findings of all checks
     */
    public List<Finding> findings() {
        return summary.results().stream().flatMap(r -> r.findings().stream()).toList();
    }
}
