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

import java.util.List;
import java.util.Map;

import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;

/**
 * What a check works from.
 *
 * @param model in-scope plan
 * @param evidence runtime evidence, or {@code null} without a readiness validation
 * @param options options
 * @param offlineTimings extractor source id to offline processing times in nanoseconds
 * @param hardcoded candidate hardcoded recorded values, or {@code null} when the correlation tooling is unavailable
 * @param pacing thread group id to a description of the iteration pacing a scenario workload configures for it
 */
public record CheckContext(PlanModel model, RuntimeEvidence evidence, ReadinessOptions options,
        Map<String, List<Long>> offlineTimings, HardcodedValues.Scan hardcoded, Map<String, String> pacing) {

    public CheckContext {
        offlineTimings = offlineTimings == null ? Map.of() : Map.copyOf(offlineTimings);
        pacing = pacing == null ? Map.of() : Map.copyOf(pacing);
    }

    /**
     * @return variable sources of the model
     */
    public VariableSources sources() {
        return model.sources();
    }

    /**
     * @return whether runtime evidence is available, current or stale
     */
    public boolean hasEvidence() {
        return evidence != null;
    }

    /**
     * @param id check
     * @param node element the finding is about
     * @param key finding key
     * @param severity severity
     * @param message message
     * @param evidence evidence lines
     * @return a finding grouped under the element's transaction
     */
    public static Finding finding(CheckId id, PlanNode node, String key, Severity severity, String message,
            List<String> evidence) {
        return new Finding(id, node.ref(), key, severity, message, evidence, unitOf(node));
    }

    /**
     * @param node a node
     * @return the transaction it belongs to, or {@code null}
     */
    static ElementRef unitOf(PlanNode node) {
        if (node.element() instanceof org.apache.jmeter.control.TransactionController) {
            return node.ref();
        }
        PlanNode transaction = node.enclosingTransaction();
        return transaction == null ? null : transaction.ref();
    }
}
