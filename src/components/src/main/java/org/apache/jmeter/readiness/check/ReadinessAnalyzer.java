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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.readiness.ReadinessAggregator;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.evidence.EvidenceSession;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.testelement.TestElement;

/**
 * Runs the selected checks against the in-scope plan and the latest evidence, applies accepted exceptions, decides
 * whether runtime evidence is current from the content fingerprint, and aggregates the verdict. Swing free.
 */
public final class ReadinessAnalyzer {
    private static final Map<CheckId, ReadinessCheck> CHECKS = new EnumMap<>(CheckId.class);

    static {
        for (ReadinessCheck check : List.of(new ValidationCheck(), new VerificationCoverageCheck(),
                new UnusedExtractionsCheck(), new UnmatchedExtractionsCheck(), new ExtractionTimeCheck(),
                new ThinkTimeCheck(), new UnresolvedVariablesCheck(), new FilesCheck(), new ExerciseCoverageCheck(),
                new HardcodedValuesCheck(), new RepeatabilityCheck())) {
            CHECKS.put(check.id(), check);
        }
    }

    /**
     * What to analyse.
     *
     * @param model in-scope plan
     * @param scope thread group ids in scope, as {@link org.apache.jmeter.gui.action.Start#readinessScope} returns
     * @param scopeNames thread group names, for presentation
     * @param selected checks to run
     * @param session latest runtime evidence, or {@code null}
     * @param checkpointGeneration current validation checkpoint generation
     * @param options options
     * @param offlineTimings offline extractor timings
     * @param hardcoded hardcoded value scan, or {@code null}
     * @param pacing scenario pacing per thread group id
     */
    public record Input(PlanModel model, List<String> scope, List<String> scopeNames, Set<CheckId> selected,
            EvidenceSession session, long checkpointGeneration, ReadinessOptions options,
            Map<String, List<Long>> offlineTimings, HardcodedValues.Scan hardcoded, Map<String, String> pacing) {
    }

    private ReadinessAnalyzer() {
    }

    /**
     * @param session evidence, may be {@code null}
     * @param model in-scope plan
     * @param scope scope ids
     * @param generation current checkpoint generation
     * @return why the evidence is not current, or {@code null} when it is
     */
    public static String staleReason(EvidenceSession session, PlanModel model, List<String> scope, long generation) {
        if (session == null) {
            return "No readiness validation has run";
        }
        RunSegment main = session.main();
        if (main.checkpointGeneration() != generation) {
            return "Another validation run replaced the readiness validation history";
        }
        String now = PlanFingerprint.compute(model.tree(), main.settings(), scope);
        if (!now.equals(main.fingerprint())) {
            return "The plan or the scope changed after the readiness validation";
        }
        return null;
    }

    /**
     * @param input what to analyse
     * @return the report
     */
    public static ReadinessReport analyze(Input input) {
        PlanModel model = input.model();
        EvidenceSession session = input.session();
        String stale = staleReason(session, model, input.scope(), input.checkpointGeneration());
        boolean current = stale == null;
        RuntimeEvidence evidence = session == null ? null : new RuntimeEvidence(session, current, stale);
        Set<String> used = new HashSet<>();
        List<CheckResult> results = new ArrayList<>();
        Set<CheckId> evaluated = new HashSet<>();
        for (CheckId id : CheckId.values()) {
            if (!input.selected().contains(id)) {
                continue;
            }
            RuntimeEvidence checkEvidence = evidence;
            String checkStale = stale;
            if (id == CheckId.REPEATABILITY && evidence != null && session.repeatability() != null) {
                checkStale = repeatabilityStale(session, model, input.scope(), stale);
            }
            boolean staleEvidence = checkEvidence != null && checkStale != null;
            if (id.evidence() == CheckId.Evidence.BOTH && staleEvidence) {
                // Configuration findings stay valid; only the runtime part becomes Not verified
                checkEvidence = null;
            }
            CheckContext context = new CheckContext(model, checkEvidence, input.options(), input.offlineTimings(),
                    input.hardcoded(), input.pacing());
            CheckOutcome outcome = CHECKS.get(id).run(context);
            CheckResult result;
            if (outcome.notVerifiedReason() != null) {
                result = CheckResult.notVerified(id, outcome.notVerifiedReason());
            } else {
                List<Finding> findings = applyExceptions(model, outcome.findings(), used);
                String summary = outcome.summary();
                if (id.evidence() == CheckId.Evidence.BOTH && staleEvidence) {
                    summary += " · runtime evidence stale";
                }
                result = CheckResult.of(id, findings, outcome.applicable(), summary);
                evaluated.add(id);
            }
            if (id.evidence() == CheckId.Evidence.RUNTIME && staleEvidence
                    && result.state() != CheckState.NOT_VERIFIED) {
                result = result.asStale("Stale — " + checkStale);
                evaluated.remove(id);
            }
            results.add(result);
        }
        List<ReadinessReport.UnusedException> unused = unusedExceptions(model, evaluated, used);
        String evidenceStatus = session == null ? "No runtime evidence"
                : current ? "Runtime evidence is current (" + session.completion().status().label() + ")"
                : "Stale — " + stale;
        return new ReadinessReport(Instant.now(),
                PlanFingerprint.compute(model.tree(), session == null ? null : session.main().settings(),
                        input.scope()),
                input.scopeNames(), ReadinessAggregator.aggregate(results), evidenceStatus, unused);
    }

    private static String repeatabilityStale(EvidenceSession session, PlanModel model, List<String> scope,
            String mainStale) {
        if (mainStale != null) {
            return mainStale;
        }
        RunSegment run = session.repeatability();
        ValidationSettings settings = run.settings();
        if (!PlanFingerprint.compute(model.tree(), settings, scope).equals(run.fingerprint())) {
            return "The plan changed after the repeatability run";
        }
        return null;
    }

    private static String usedKey(String sourceId, CheckId id, String key) {
        return sourceId + "|" + id.key() + "|" + key;
    }

    static List<Finding> applyExceptions(PlanModel model, List<Finding> findings, Set<String> used) {
        List<Finding> result = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.severity() == Severity.INFO || finding.element() == null) {
                result.add(finding);
                continue;
            }
            AcceptedException exception = null;
            String holder = finding.element().sourceId();
            TestElement element = model.element(holder);
            if (element != null) {
                exception = ReadinessExceptions.find(element, finding);
            }
            if (exception == null && finding.checkId() == CheckId.THINK_TIME) {
                // Background/API activity markings cover the subtree below them
                for (PlanNode occurrence : model.nodesWithId(holder)) {
                    for (PlanNode ancestor : occurrence.ancestors()) {
                        AcceptedException marking = subtreeMarking(ancestor.element());
                        if (marking != null) {
                            exception = marking;
                            holder = ancestor.sourceId();
                            break;
                        }
                    }
                    if (exception != null) {
                        break;
                    }
                }
                if (exception == null && element != null) {
                    exception = ReadinessExceptions.get(element, CheckId.THINK_TIME,
                            ReadinessExceptions.BACKGROUND_ACTIVITY_KEY);
                }
            }
            if (exception != null) {
                used.add(usedKey(holder, exception.checkId(), exception.findingKey()));
                result.add(finding.withException(exception));
            } else {
                result.add(finding);
            }
        }
        return result;
    }

    private static AcceptedException subtreeMarking(TestElement element) {
        AcceptedException background = ReadinessExceptions.get(element, CheckId.THINK_TIME,
                ReadinessExceptions.BACKGROUND_ACTIVITY_KEY);
        return background != null ? background
                : ReadinessExceptions.get(element, CheckId.THINK_TIME, ReadinessExceptions.ANY_KEY);
    }

    private static List<ReadinessReport.UnusedException> unusedExceptions(PlanModel model, Set<CheckId> evaluated,
            Set<String> used) {
        Map<String, ReadinessReport.UnusedException> unused = new LinkedHashMap<>();
        for (PlanNode node : model.distinctNodes()) {
            for (AcceptedException exception : ReadinessExceptions.read(node.element())) {
                if (!evaluated.contains(exception.checkId())
                        || used.contains(usedKey(node.sourceId(), exception.checkId(), exception.findingKey()))) {
                    continue;
                }
                // An optional-extractor marking is in use as long as it marks a supported extractor
                if (exception.checkId() == CheckId.UNMATCHED_EXTRACTIONS
                        && Objects.equals(exception.findingKey(), ReadinessExceptions.OPTIONAL_EXTRACTOR_KEY)
                        && ExtractorSupport.describe(node.element()).isPresent()) {
                    continue;
                }
                unused.putIfAbsent(usedKey(node.sourceId(), exception.checkId(), exception.findingKey()),
                        new ReadinessReport.UnusedException(node.ref(), exception));
            }
        }
        return new ArrayList<>(unused.values());
    }
}
