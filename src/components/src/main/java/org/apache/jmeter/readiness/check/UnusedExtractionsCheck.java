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
import java.util.List;

import org.apache.jmeter.control.ForeachController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.RunTime;
import org.apache.jmeter.control.WhileController;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.ScriptingTestElement;

/**
 * Check 3: extracted variables nothing in scope reads. Reads are searched in every string property of every element
 * (requests, headers, bodies, controller conditions, assertions, timers, other extractors, configuration), including
 * generated families such as {@code name_1}, {@code name_matchNr}, {@code name_g1}, {@code name_ALL} and ForEach
 * input prefixes. Scripts and computed names make usage uncertain: such variables are reported as possibly used.
 */
public final class UnusedExtractionsCheck implements ReadinessCheck {

    @Override
    public CheckId id() {
        return CheckId.UNUSED_EXTRACTIONS;
    }

    private record Read(String name, PlanNode node) {
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        PlanModel model = context.model();
        List<Read> reads = new ArrayList<>();
        List<PlanNode> dynamicReaders = new ArrayList<>();
        for (PlanNode node : model.nodes()) {
            collectReads(node, reads, dynamicReaders);
        }
        List<Finding> findings = new ArrayList<>();
        int extractors = 0;
        int used = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PlanNode node : model.nodes()) {
            var info = ExtractorSupport.describe(node.element()).orElse(null);
            if (info == null || !seen.add(node.sourceId())) {
                continue;
            }
            for (String refName : info.refNames()) {
                extractors++;
                List<Read> readers = reads.stream()
                        .filter(r -> r.node().element() != node.element()
                                && VariableReferences.isInFamily(refName, r.name()))
                        .toList();
                if (readers.isEmpty()) {
                    if (!dynamicReaders.isEmpty()) {
                        findings.add(CheckContext.finding(id(), node, refName, Severity.INFO,
                                "'" + refName + "' is possibly used (scripted / dynamic reference)",
                                List.of("Computed or scripted variable access in: "
                                        + names(dynamicReaders))));
                    } else {
                        findings.add(CheckContext.finding(id(), node, refName, Severity.WARNING,
                                "'" + refName + "' is extracted but never used", List.of(info.type()
                                        + " expression: " + info.expression())));
                    }
                    continue;
                }
                PlanNode overwriter = overwrittenBeforeRead(model, node, refName, readers);
                if (overwriter != null) {
                    findings.add(CheckContext.finding(id(), node, refName, Severity.WARNING,
                            "'" + refName + "' is overwritten by '" + overwriter.name() + "' before it is read",
                            List.of()));
                    continue;
                }
                used++;
            }
        }
        return CheckOutcome.of(findings, extractors > 0,
                used + " of " + extractors + " extracted variables have a detected consumer");
    }

    private static void collectReads(PlanNode node, List<Read> reads, List<PlanNode> dynamicReaders) {
        boolean extractor = ExtractorSupport.describe(node.element()).isPresent();
        PropertyTexts.of(node.element()).forEach((property, text) -> {
            // An extractor's reference name is what it defines, not a read
            if (extractor && isReferenceNameProperty(property)) {
                return;
            }
            VariableReferences.Result parsed = VariableReferences.parse(text);
            parsed.names().forEach(name -> reads.add(new Read(name, node)));
            if (parsed.dynamic()) {
                dynamicReaders.add(node);
            }
        });
        if (node.element() instanceof ScriptingTestElement script) {
            ScriptingTestElement prepared = Beans.prepared(script);
            VariableReferences.ScriptAccess access = VariableReferences.scriptAccess(prepared.getScript());
            access.reads().forEach(name -> reads.add(new Read(name, node)));
            if (access.dynamicReads() || !VariableSources.isEmpty(prepared.getFilename())) {
                dynamicReaders.add(node);
            }
        }
        if (node.element() instanceof ForeachController foreach) {
            // ForEach reads <input>_1, <input>_2, ...
            reads.add(new Read(foreach.getInputValString() + "_1", node));
        }
    }

    private static boolean isReferenceNameProperty(String property) {
        String lower = property.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("refname") || lower.endsWith("referencename") // $NON-NLS-1$
                || lower.endsWith("referencenames"); // $NON-NLS-1$
    }

    /**
     * Detects an extraction overwritten by a later extractor of the same name before anything reads it. Only linear
     * flows are analysed: both extractors must apply to a single sampler and not run inside a loop.
     */
    private static PlanNode overwrittenBeforeRead(PlanModel model, PlanNode extractor, String refName,
            List<Read> readers) {
        if (extractor.parent() == null || !extractor.parent().isSampler() || inLoop(extractor)) {
            return null;
        }
        // A read by a scoped element (config, header, timer at controller level) happens at every sampler
        if (readers.stream().anyMatch(r -> !r.node().isSampler() && !r.node().isController()
                && (r.node().parent() == null || !r.node().parent().isSampler()))) {
            return null;
        }
        int defined = extractor.parent().order();
        PlanNode next = null;
        for (PlanNode other : model.nodes()) {
            if (other == extractor || other.parent() == null || !other.parent().isSampler()
                    || other.parent().order() <= defined || other.threadGroup() != extractor.threadGroup()
                    || inLoop(other)) {
                continue;
            }
            var info = ExtractorSupport.describe(other.element()).orElse(null);
            if (info != null && info.refNames().contains(refName)
                    && (next == null || other.parent().order() < next.parent().order())) {
                next = other;
            }
        }
        if (next == null) {
            return null;
        }
        int overwritten = next.parent().order();
        for (Read read : readers) {
            int at = read.node().isSampler() || read.node().isController() ? read.node().order()
                    : read.node().parent().order();
            // The overwriting sampler itself still reads the first value
            if (at > defined && at <= overwritten) {
                return null;
            }
        }
        return next;
    }

    private static boolean inLoop(PlanNode node) {
        for (PlanNode ancestor : node.ancestors()) {
            if (ancestor.element() instanceof AbstractThreadGroup) {
                return false;
            }
            if (ancestor.element() instanceof LoopController || ancestor.element() instanceof WhileController
                    || ancestor.element() instanceof ForeachController || ancestor.element() instanceof RunTime) {
                return true;
            }
        }
        return false;
    }

    private static String names(List<PlanNode> nodes) {
        return String.join(", ", nodes.stream().map(PlanNode::name).distinct().limit(5).toList());
    }
}
