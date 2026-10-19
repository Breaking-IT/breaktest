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

package org.apache.jmeter.readiness.gui;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.tree.TreePath;

import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.check.HardcodedValues;
import org.apache.jmeter.readiness.check.OfflineExtractorTimer;
import org.apache.jmeter.readiness.check.PlanModel;
import org.apache.jmeter.readiness.check.ReadinessAnalyzer;
import org.apache.jmeter.readiness.check.ReadinessReport;
import org.apache.jmeter.readiness.evidence.EvidenceSession;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
import org.apache.jmeter.readiness.evidence.ReadinessRecorder;
import org.apache.jmeter.readiness.evidence.ReadinessRunListener;
import org.apache.jmeter.readiness.evidence.RunKind;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.readiness.evidence.RunStatus;
import org.apache.jmeter.scenario.Scenario;
import org.apache.jmeter.scenario.ScenarioWorkload;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives readiness runs in the GUI: commits pending editor changes, runs the readiness validation through
 * {@link Start} (the same engine path as Validate), bounds it with a time limit, measures extractors offline, and
 * builds the report. Keeps the latest report for the GUI session; it is dropped when the plan is cleared or replaced.
 */
final class GuiReadinessController implements ReadinessController {
    private static final Logger log = LoggerFactory.getLogger(GuiReadinessController.class);

    private Listener listener;
    private RunRequest lastRequest;
    private ReadinessReport latest;
    private PlanModel lastModel;
    private Map<String, List<Long>> offlineTimings = Map.of();
    private HardcodedValues.Snapshot hardcoded;
    private RunSegment running;
    private Timer timeout;
    private SwingWorker<?, ?> worker;
    private boolean busy;
    private boolean cancelled;

    GuiReadinessController() {
        ReadinessEvidenceStore.addClearListener(() -> SwingUtilities.invokeLater(this::forget));
        // Resume validation runs that continue a readiness validation update the report when they end
        ReadinessEvidenceStore.addListener(() -> SwingUtilities.invokeLater(() -> {
            if (!busy && latest != null && !ReadinessRecorder.isActive()) {
                refresh();
            }
        }));
    }

    private void forget() {
        latest = null;
        lastModel = null;
        lastRequest = null;
        offlineTimings = Map.of();
        hardcoded = null;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public List<GroupChoice> threadGroups() {
        GuiPackage gui = GuiPackage.getInstance();
        List<GroupChoice> result = new ArrayList<>();
        if (gui == null) {
            return result;
        }
        java.util.Set<AbstractThreadGroup> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (JMeterTreeNode node : gui.getTreeListener().getSelectedNodes()) {
            for (JMeterTreeNode n = node; n != null; n = (JMeterTreeNode) n.getParent()) {
                if (n.getTestElement() instanceof AbstractThreadGroup group) {
                    selected.add(group);
                    break;
                }
            }
        }
        for (AbstractThreadGroup group : allGroups(gui.getTreeModel())) {
            result.add(new GroupChoice(groupKey(group), group.getName(),
                    selected.contains(group)));
        }
        return result;
    }

    /**
     * Enabled thread groups that validation can run: top-level ones and those of enabled thread group sections.
     */
    static List<AbstractThreadGroup> allGroups(JMeterTreeModel model) {
        List<AbstractThreadGroup> groups = new ArrayList<>();
        JMeterTreeNode plan = (JMeterTreeNode) model.getTestPlan().getArray()[0];
        if (!plan.isEnabled()) {
            return groups;
        }
        for (int i = 0; i < plan.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) plan.getChildAt(i);
            if (!child.isEnabled()) {
                continue;
            }
            if (child.getTestElement() instanceof AbstractThreadGroup group) {
                groups.add(group);
            } else if (child.getTestElement() instanceof ThreadGroupsSection) {
                for (int j = 0; j < child.getChildCount(); j++) {
                    JMeterTreeNode node = (JMeterTreeNode) child.getChildAt(j);
                    if (node.isEnabled() && node.getTestElement() instanceof AbstractThreadGroup group) {
                        groups.add(group);
                    }
                }
            }
        }
        return groups;
    }

    /**
     * Identity of a thread group that survives validation history resets, unlike its source id.
     */
    static String groupKey(AbstractThreadGroup group) {
        return group.getThreadGroupId().isEmpty()
                ? "identity:" + System.identityHashCode(group) : "group:" + group.getThreadGroupId();
    }

    private static AbstractThreadGroup[] resolveGroups(List<String> ids) {
        GuiPackage gui = GuiPackage.getInstance();
        List<AbstractThreadGroup> groups = new ArrayList<>();
        for (AbstractThreadGroup group : allGroups(gui.getTreeModel())) {
            if (ids.contains(groupKey(group))) {
                groups.add(group);
            }
        }
        return groups.toArray(new AbstractThreadGroup[0]);
    }

    @Override
    public boolean isRunning() {
        return busy;
    }

    @Override
    public void start(RunRequest request) {
        if (busy) {
            return;
        }
        if (JMeterUtils.isTestRunning() || ReadinessRecorder.isActive()) {
            listener.failed("A test or validation is running. Stop it before checking readiness.");
            return;
        }
        GuiPackage.getInstance().updateCurrentNode();
        lastRequest = request;
        offlineTimings = Map.of();
        hardcoded = null;
        cancelled = false;
        busy = true;
        boolean needsValidation = request.runValidation()
                && request.checks().stream().anyMatch(CheckId::needsValidationRun);
        if (needsValidation) {
            listener.progress("Running the readiness validation (" + request.settings() + ")…");
            validate(RunKind.MAIN, request.settings(), request.timeLimitSeconds(), this::afterMainRun);
        } else {
            listener.progress(request.runValidation() ? "No selected check needs a validation run."
                    : "Using the latest readiness validation, if it still matches the plan.");
            analyze();
        }
    }

    private void validate(RunKind kind, ValidationSettings settings, int limitSeconds, Runnable then) {
        AbstractThreadGroup[] groups = resolveGroups(lastRequest.groupIds());
        ActionEvent event = Start.readinessValidationEvent(this, groups, settings, kind, new ReadinessRunListener() {
            @Override
            public void runStarted(RunSegment segment) {
                SwingUtilities.invokeLater(() -> startTimeout(segment, limitSeconds));
                running = segment;
            }

            @Override
            public void runFinished(RunSegment segment) {
                SwingUtilities.invokeLater(() -> {
                    stopTimeout();
                    running = null;
                    listener.progress("Validation run ended: " + segment.status().label() + ", "
                            + segment.samples().size() + " samples.");
                    segment.statusDetails().forEach(detail -> listener.progress("  " + detail));
                    then.run();
                });
            }

            @Override
            public void runNotStarted(String message) {
                SwingUtilities.invokeLater(() -> {
                    stopTimeout();
                    running = null;
                    busy = false;
                    listener.failed("The readiness validation did not start: " + message);
                });
            }
        });
        whenIdle(() -> ActionRouter.getInstance().doActionNow(event), 300);
    }

    /**
     * The engine reports the end of a run to its listeners before it clears its running state, so a run that
     * follows another one (the repeatability run) waits briefly until the engine is idle.
     */
    private static void whenIdle(Runnable action, int attempts) {
        if (!JMeterUtils.isTestRunning() || attempts <= 0) {
            action.run();
            return;
        }
        Timer retry = new Timer(100, e -> whenIdle(action, attempts - 1));
        retry.setRepeats(false);
        retry.start();
    }

    private void startTimeout(RunSegment segment, int limitSeconds) {
        stopTimeout();
        if (limitSeconds <= 0) {
            return;
        }
        timeout = new Timer(limitSeconds * 1000, e -> {
            if (running == segment) {
                segment.markStatus(RunStatus.TIMED_OUT, "Exceeded the time limit of " + limitSeconds + " s");
                listener.progress("Time limit reached; stopping the validation run.");
                stopEngine();
            }
        });
        timeout.setRepeats(false);
        timeout.start();
    }

    private void stopTimeout() {
        if (timeout != null) {
            timeout.stop();
            timeout = null;
        }
    }

    private void stopEngine() {
        ActionRouter.getInstance().doActionNow(new ActionEvent(this, ActionEvent.ACTION_PERFORMED,
                ActionNames.ACTION_STOP));
    }

    private void afterMainRun() {
        EvidenceSession session = ReadinessEvidenceStore.latest();
        if (!cancelled && lastRequest.options().isRepeatabilityConfigured()
                && lastRequest.checks().contains(CheckId.REPEATABILITY)
                && session != null && session.main().status().isComplete()) {
            int iterations = lastRequest.options().repeatabilityIterations();
            listener.progress("Running the repeatability check: a fresh session, " + iterations
                    + " iterations…");
            validate(RunKind.REPEATABILITY, lastRequest.settings().withIterations(iterations),
                    lastRequest.options().repeatabilityTimeLimitSeconds(), this::analyze);
        } else {
            analyze();
        }
    }

    private void analyze() {
        PlanBuild build;
        try {
            build = build(lastRequest.groupIds());
        } catch (RuntimeException e) {
            busy = false;
            log.debug("Unable to prepare the readiness analysis", e);
            listener.failed("The test plan could not be prepared for analysis: " + e.getMessage());
            return;
        }
        EvidenceSession session = ReadinessEvidenceStore.latest();
        boolean timing = lastRequest.checks().contains(CheckId.EXTRACTION_TIME) && session != null
                && ReadinessAnalyzer.staleReason(session, build.model(), build.scope(),
                        ValidationCheckpoints.generation()) == null;
        boolean scanHardcoded = lastRequest.checks().contains(CheckId.HARDCODED_VALUES);
        String scanFingerprint = PlanFingerprint.compute(build.model().tree(), null, build.scope());
        List<TestElement> groups = new ArrayList<>(List.of(resolveGroups(lastRequest.groupIds())));
        int runs = lastRequest.options().offlineReruns();
        listener.progress((timing ? "Measuring extractors offline (" + runs + " runs per response)… " : "")
                + (scanHardcoded ? "Looking for hardcoded recorded values…" : "Analysing…"));
        SwingWorker<Void, Void> background = new SwingWorker<>() {
            private Map<String, List<Long>> timings = Map.of();
            private HardcodedValues.Scan scan;

            @Override
            protected Void doInBackground() {
                if (timing) {
                    timings = OfflineExtractorTimer.measure(session.main(), build.model(), runs, this::isCancelled);
                }
                if (scanHardcoded && !isCancelled()) {
                    scan = HardcodedValues.scan(groups);
                }
                return null;
            }

            @Override
            protected void done() {
                worker = null;
                try {
                    if (!isCancelled()) {
                        get();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.debug("Readiness background analysis failed", e.getCause());
                    listener.progress("Part of the analysis failed: " + e.getCause().getMessage());
                }
                offlineTimings = timings;
                hardcoded = scan == null ? null : new HardcodedValues.Snapshot(scan, scanFingerprint);
                try {
                    latest = report(build);
                    busy = false;
                    listener.finished(latest);
                } catch (RuntimeException e) {
                    busy = false;
                    log.debug("Unable to build the readiness report", e);
                    listener.failed("The readiness report could not be built: " + e.getMessage());
                }
            }
        };
        worker = background;
        background.execute();
    }

    @Override
    public void cancel() {
        cancelled = true;
        RunSegment segment = running;
        if (segment != null) {
            segment.markStatus(RunStatus.CANCELLED, "Cancelled by the user");
            listener.progress("Cancelling…");
            stopEngine();
        }
        if (worker != null) {
            worker.cancel(true);
        }
    }

    @Override
    public void refresh() {
        if (lastRequest == null || busy) {
            return;
        }
        try {
            latest = report(build(lastRequest.groupIds()));
            listener.updated(latest);
        } catch (RuntimeException e) {
            log.debug("Unable to refresh the readiness report", e);
            listener.failed("The readiness report could not be refreshed: " + e.getMessage());
        }
    }

    @Override
    public ReadinessReport latestReport() {
        return latest;
    }

    private record PlanBuild(PlanModel model, List<String> scope, List<String> names, Map<String, String> pacing) {
    }

    private PlanBuild build(List<String> groupIds) {
        GuiPackage gui = GuiPackage.getInstance();
        gui.updateCurrentNode();
        AbstractThreadGroup[] groups = resolveGroups(groupIds);
        if (groups.length == 0) {
            throw new IllegalStateException("None of the thread groups in scope is enabled any more.");
        }
        JMeterTreeModel treeModel = gui.getTreeModel();
        // Copies made while flattening sections keep the identity of the element they copy until the model is built
        PlanModel model = ValidationCheckpoints.withSourceIdentity(() -> buildModel(treeModel, groups));
        lastModel = model;
        List<String> names = new ArrayList<>();
        for (AbstractThreadGroup group : groups) {
            names.add(group.getName());
        }
        return new PlanBuild(model, Start.readinessScope(groups), names, scenarioPacing(treeModel));
    }

    private static PlanModel buildModel(JMeterTreeModel treeModel, AbstractThreadGroup[] groups) {
        HashTree tree;
        int registered = StandardJMeterEngine.registeredListenerCount();
        try {
            tree = Start.validationTree(treeModel.getTestPlan(), groups);
        } finally {
            // Functions evaluated while flattening sections register to be ended with a test that never starts
            StandardJMeterEngine.endListenersRegisteredSince(registered);
        }
        IdentityHashMap<TestElement, JMeterTreeNode> nodes = new IdentityHashMap<>();
        index((JMeterTreeNode) treeModel.getRoot(), nodes);
        Map<String, JMeterTreeNode> bySourceId = new HashMap<>();
        nodes.forEach((element, node) -> bySourceId.putIfAbsent(ValidationCheckpoints.sourceIdOfSource(element), node));
        return PlanModel.build(tree, element -> ref(element, nodes, bySourceId));
    }

    private ReadinessReport report(PlanBuild build) {
        return ReadinessAnalyzer.analyze(new ReadinessAnalyzer.Input(build.model(), build.scope(), build.names(),
                lastRequest.checks(), ReadinessEvidenceStore.latest(), ValidationCheckpoints.generation(),
                lastRequest.options(), offlineTimings,
                hardcoded == null ? null : hardcoded.forPlan(build.model().tree(), build.scope()), build.pacing()));
    }

    private static void index(JMeterTreeNode node, IdentityHashMap<TestElement, JMeterTreeNode> nodes) {
        nodes.put(node.getTestElement(), node);
        for (int i = 0; i < node.getChildCount(); i++) {
            index((JMeterTreeNode) node.getChildAt(i), nodes);
        }
    }

    private static ElementRef ref(TestElement element, IdentityHashMap<TestElement, JMeterTreeNode> nodes,
            Map<String, JMeterTreeNode> bySourceId) {
        String sourceId = ValidationCheckpoints.sourceIdOfSource(element);
        JMeterTreeNode node = nodes.get(element);
        if (node == null) {
            // A copy made while flattening sections (profile configuration, the test plan, thread groups): it has
            // the identity of the element it copies, which is the one to show, navigate to and record exceptions on
            node = bySourceId.get(sourceId);
        }
        List<Integer> path = new ArrayList<>();
        for (JMeterTreeNode n = node; n != null && n.getParent() != null; n = (JMeterTreeNode) n.getParent()) {
            path.add(n.getParent().getIndex(n));
        }
        Collections.reverse(path);
        return new ElementRef(sourceId, element.getName(), element.getClass().getSimpleName(), path);
    }

    private static Map<String, String> scenarioPacing(JMeterTreeModel model) {
        Map<String, String> pacing = new LinkedHashMap<>();
        for (JMeterTreeNode node : model.getNodesOfType(Scenario.class)) {
            if (!node.isEnabled()) {
                continue;
            }
            for (ScenarioWorkload workload : ((Scenario) node.getTestElement()).getWorkloads()) {
                String mode = workload.getPacingMode();
                if (!workload.isEnabled() || mode == null || mode.isEmpty()
                        || AbstractThreadGroup.PACING_DISABLED.equals(mode)) {
                    continue;
                }
                pacing.putIfAbsent(workload.getThreadGroupId(), mode
                        + (AbstractThreadGroup.PACING_FIXED.equals(mode) ? " " + workload.getFixedPacing() + " ms"
                                : " " + workload.getPacingMin() + "-" + workload.getPacingMax() + " ms")
                        + " (scenario '" + node.getName() + "')");
            }
        }
        return pacing;
    }

    /**
     * @param ref element reference
     * @return the tree node of the element, following its recorded path when the element was replaced
     */
    private static JMeterTreeNode locate(ElementRef ref, PlanModel model) {
        GuiPackage gui = GuiPackage.getInstance();
        JMeterTreeModel treeModel = gui.getTreeModel();
        TestElement element = model == null ? null : model.element(ref.sourceId());
        JMeterTreeNode node = element == null ? null : treeModel.getNodeOf(element);
        if (node != null) {
            return node;
        }
        JMeterTreeNode current = (JMeterTreeNode) treeModel.getRoot();
        for (int index : ref.treePath()) {
            if (index < 0 || index >= current.getChildCount()) {
                return null;
            }
            current = (JMeterTreeNode) current.getChildAt(index);
        }
        return !ref.treePath().isEmpty()
                && current.getTestElement().getClass().getSimpleName().equals(ref.type()) ? current : null;
    }

    @Override
    public void navigate(ElementRef element) {
        JMeterTreeNode node = locate(element, lastModel);
        if (node == null) {
            JMeterUtils.reportErrorToUser("The element '" + element + "' is no longer in the test plan.");
            return;
        }
        GuiPackage gui = GuiPackage.getInstance();
        TreePath path = new TreePath(node.getPath());
        gui.getMainFrame().getTree().setSelectionPath(path);
        gui.getMainFrame().getTree().scrollPathToVisible(path);
        gui.getMainFrame().toFront();
    }

    @Override
    public void accept(ElementRef element, AcceptedException exception) {
        edit(element, node -> ReadinessExceptions.put(node.getTestElement(), exception));
    }

    @Override
    public void remove(ElementRef element, AcceptedException exception) {
        edit(element, node -> ReadinessExceptions.remove(node.getTestElement(), exception.checkId(),
                exception.findingKey()));
    }

    private void edit(ElementRef element, java.util.function.Consumer<JMeterTreeNode> change) {
        GuiPackage gui = GuiPackage.getInstance();
        gui.updateCurrentNode();
        JMeterTreeNode node = locate(element, lastModel);
        if (node == null) {
            JMeterUtils.reportErrorToUser("The element '" + element + "' is no longer in the test plan.");
            return;
        }
        change.accept(node);
        // A node change is recorded in undo history; exceptions are plan edits saved with the JMX
        gui.getTreeModel().nodeChanged(node);
        gui.setDirty(true);
        refresh();
    }

    @Override
    public String defaultAuthor() {
        return JMeterUtils.getPropDefault("breaktest.readiness.author", System.getProperty("user.name", "")); // $NON-NLS-1$
    }
}
