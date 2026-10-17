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
import java.util.Set;

import org.apache.jmeter.control.ForeachController;
import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.InterleaveControl;
import org.apache.jmeter.control.OnceOnlyController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.RandomController;
import org.apache.jmeter.control.SwitchController;
import org.apache.jmeter.control.ThroughputController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.WhileController;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;

/**
 * Validation exercise coverage: transactions and branches (If, Switch, While, Throughput, Fork, ...) in scope that
 * never executed during validation, and children of Switch / Random / Interleave / Fork / Parallel controllers that
 * were never chosen.
 */
public final class ExerciseCoverageCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.EXERCISE_COVERAGE;
    }

    static boolean isBranch(PlanNode node) {
        Object e = node.element();
        return e instanceof IfController || e instanceof SwitchController || e instanceof WhileController
                || e instanceof ThroughputController || e instanceof ForkController || e instanceof RandomController
                || e instanceof InterleaveControl || e instanceof OnceOnlyController || e instanceof ForeachController
                || e instanceof ParallelController;
    }

    private static boolean choosesChild(PlanNode node) {
        Object e = node.element();
        return e instanceof SwitchController || e instanceof InterleaveControl || e instanceof ForkController
                || e instanceof ParallelController;
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        RuntimeEvidence evidence = context.evidence();
        if (evidence == null) {
            return CheckOutcome.notVerified("Not verified — requires runtime validation");
        }
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int transactions = 0;
        int transactionsRun = 0;
        int branches = 0;
        int branchesRun = 0;
        for (PlanNode node : context.model().nodes()) {
            if (!seen.add(node.sourceId()) || node.requestSamplers().isEmpty() && !node.isSampler()) {
                continue;
            }
            if (node.element() instanceof TransactionController) {
                transactions++;
                if (evidence.executed(node.sourceId())) {
                    transactionsRun++;
                } else {
                    findings.add(CheckContext.finding(id(), node, "not-executed", Severity.WARNING,
                            "Transaction '" + node.name() + "' never executed during validation", List.of()));
                }
            } else if (isBranch(node)) {
                branches++;
                if (!evidence.executed(node.sourceId())) {
                    findings.add(CheckContext.finding(id(), node, "not-executed", Severity.WARNING,
                            node.element().getClass().getSimpleName() + " '" + node.name()
                                    + "' never executed during validation", List.of()));
                    continue;
                }
                branchesRun++;
                if (node.element() instanceof SwitchController || node.element() instanceof RandomController
                        || choosesChild(node)) {
                    for (PlanNode child : node.children()) {
                        if ((child.isSampler() || child.isController()) && !child.requestSamplers().isEmpty()
                                && !evidence.executed(child.sourceId())) {
                            findings.add(CheckContext.finding(id(), child, "branch-not-executed", Severity.WARNING,
                                    "Branch '" + child.name() + "' of '" + node.name()
                                            + "' never executed during validation", List.of()));
                        }
                    }
                }
            }
        }
        return CheckOutcome.of(findings, transactions + branches > 0,
                transactionsRun + " of " + transactions + " transactions exercised · " + branchesRun + " of "
                        + branches + " branches exercised");
    }
}
