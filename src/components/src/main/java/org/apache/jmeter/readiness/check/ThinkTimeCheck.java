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
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.sampler.TestAction;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.timers.Timer;

/**
 * Check 6: lists the configured delays between user-facing steps for review, independently of whether validation
 * slept. Steps are top-level transactions, or requests outside any transaction. Delays are timers in effective scope,
 * Flow Control Action pauses and transaction delay settings; iteration and transaction pacing are reported apart, as
 * they are not think time between steps. Never claims that a duration is realistic.
 */
public final class ThinkTimeCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.THINK_TIME;
    }

    /**
     * @param timer a timer node
     * @return whether the timer models throughput (pacing) or synchronization rather than think time
     */
    static boolean isPacingTimer(PlanNode timer) {
        String name = timer.element().getClass().getSimpleName();
        return name.contains("Throughput") || name.contains("Sync"); // $NON-NLS-1$
    }

    static List<PlanNode> delayTimers(PlanNode sampler) {
        return sampler.applicable(Timer.class).stream().filter(t -> !isPacingTimer(t)).toList();
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        PlanModel model = context.model();
        List<Finding> findings = new ArrayList<>();
        int transitions = 0;
        int delayed = 0;
        Set<String> reported = new HashSet<>();
        Map<String, String> pacing = PacingInfo.describe(context);
        for (PlanNode group : model.threadGroups()) {
            String groupPacing = pacing.get(group.sourceId());
            if (groupPacing != null) {
                findings.add(CheckContext.finding(id(), group, "pacing", Severity.INFO,
                        "Iteration pacing of '" + group.name() + "': " + groupPacing
                                + " (pacing, not think time between steps)", List.of()));
            }
            List<PlanNode> steps = steps(group);
            for (int i = 1; i < steps.size(); i++) {
                transitions++;
                PlanNode previous = steps.get(i - 1);
                PlanNode next = steps.get(i);
                List<String> delays = delaysBetween(group, previous, next);
                if (delays.isEmpty()) {
                    findings.add(CheckContext.finding(id(), next, "missing-delay", Severity.REVIEW,
                            "No think time between '" + previous.name() + "' and '" + next.name() + "'",
                            List.of("Add a timer or a Flow Control Action pause, or mark the step as background/API"
                                    + " activity")));
                } else {
                    delayed++;
                    findings.add(CheckContext.finding(id(), next, "delay", Severity.INFO,
                            "Delay before '" + next.name() + "': " + String.join(", ", delays), List.of()));
                }
            }
            for (PlanNode node : group.subtree()) {
                if (node.element() instanceof TransactionController tc
                        && !TransactionController.DELAY_DISABLED.equals(tc.getPacingMode())
                        && tc.getPacingMode() != null && !tc.getPacingMode().isEmpty()) {
                    findings.add(CheckContext.finding(id(), node, "transaction-pacing", Severity.INFO,
                            "Transaction pacing of '" + node.name() + "': " + tc.getPacingMode()
                                    + " (pacing, not think time)", List.of()));
                }
                if (node.isRequestSampler() && reported.add("dup:" + node.sourceId())) {
                    List<PlanNode> timers = delayTimers(node);
                    if (timers.size() > 1) {
                        findings.add(CheckContext.finding(id(), node, "duplicate-delay", Severity.REVIEW,
                                timers.size() + " timers delay '" + node.name() + "'; delays add up",
                                List.of("Timers: " + String.join(", ", timers.stream().map(PlanNode::name).toList()))));
                    }
                }
                if (node.element() instanceof Timer && !isPacingTimer(node) && reported.add("scope:" + node.sourceId())) {
                    checkPlacement(node, steps, findings);
                }
            }
        }
        boolean applicable = transitions > 0 || !model.nodesOf(Timer.class).isEmpty();
        return CheckOutcome.of(findings, applicable,
                delayed + " of " + transitions + " step transitions have a configured delay (configuration only)");
    }

    private void checkPlacement(PlanNode timer, List<PlanNode> steps, List<Finding> findings) {
        List<PlanNode> samplers = timer.scopeSamplers().stream().filter(PlanNode::isRequestSampler).toList();
        if (samplers.size() < 2) {
            return;
        }
        for (PlanNode step : steps) {
            long inStep = samplers.stream().filter(s -> s.isWithin(step)).count();
            if (inStep > 1) {
                findings.add(CheckContext.finding(id(), timer, "timer-scope", Severity.REVIEW,
                        "Timer '" + timer.name() + "' applies to " + samplers.size()
                                + " requests, so it delays every request (embedded resources included), not once"
                                + " per step", List.of("For example " + inStep + " requests of '" + step.name() + "'")));
                return;
            }
        }
    }

    /**
     * @param group a thread group node
     * @return its user-facing steps: top-level transactions and requests outside any transaction
     */
    static List<PlanNode> steps(PlanNode group) {
        List<PlanNode> steps = new ArrayList<>();
        for (PlanNode node : group.subtree()) {
            if (node.element() instanceof TransactionController && node.enclosingTransaction() == null
                    && !node.requestSamplers().isEmpty()) {
                steps.add(node);
            } else if (node.isRequestSampler() && node.enclosingTransaction() == null) {
                steps.add(node);
            }
        }
        return steps;
    }

    private static List<String> delaysBetween(PlanNode group, PlanNode previous, PlanNode next) {
        List<String> delays = new ArrayList<>();
        List<PlanNode> samplers = next.requestSamplers();
        if (!samplers.isEmpty()) {
            for (PlanNode timer : delayTimers(samplers.get(0))) {
                delays.add("timer '" + timer.name() + "'");
            }
        }
        int after = previous.subtree().get(previous.subtree().size() - 1).order();
        for (PlanNode node : group.subtree()) {
            if (node.order() > after && node.order() < next.order() && node.element() instanceof TestAction action
                    && action.getAction() == TestAction.PAUSE) {
                delays.add("Flow Control Action '" + node.name() + "' (" + action.getDurationAsString() + " ms)");
            }
        }
        for (PlanNode step : List.of(previous, next)) {
            if (step.element() instanceof TransactionController tc && tc.getDelayMode() != null
                    && !tc.getDelayMode().isEmpty() && !TransactionController.DELAY_DISABLED.equals(tc.getDelayMode())) {
                delays.add("transaction delay of '" + step.name() + "' (" + tc.getDelayMode() + ")");
            }
        }
        return delays;
    }

    /** Iteration pacing per thread group. */
    static final class PacingInfo {
        private PacingInfo() {
        }

        static Map<String, String> describe(CheckContext context) {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (PlanNode node : context.model().threadGroups()) {
                AbstractThreadGroup group = (AbstractThreadGroup) node.element();
                String mode = group.getPacingMode();
                String description = context.pacing().get(group.getThreadGroupId());
                if (description == null && mode != null && !mode.isEmpty()
                        && !AbstractThreadGroup.PACING_DISABLED.equals(mode)) {
                    description = mode + (AbstractThreadGroup.PACING_FIXED.equals(mode)
                            ? " " + group.getFixedPacing() + " ms"
                            : " " + group.getPacingMin() + "-" + group.getPacingMax() + " ms");
                }
                if (description != null) {
                    result.put(node.sourceId(), description);
                }
            }
            return result;
        }
    }
}
