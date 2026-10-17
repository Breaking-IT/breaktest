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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.tree.TreePath;

import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.check.PlanModel;
import org.apache.jmeter.readiness.check.ReadinessAnalyzer;
import org.apache.jmeter.readiness.check.ReadinessReport;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
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
 * Drives readiness runs in the GUI: commits pending editor changes and builds the report from the configuration.
 * Runtime checks stay Not verified until readiness validation runs exist.
 * Keeps the latest report for the GUI session; it is dropped when the plan is cleared or replaced.
 */
final class GuiReadinessController implements ReadinessController {
    private static final Logger log = LoggerFactory.getLogger(GuiReadinessController.class);

    private Listener listener;
    private RunRequest lastRequest;
    private ReadinessReport latest;
    private PlanModel lastModel;
    private Map<String, List<Long>> offlineTimings = Map.of();
    private SwingWorker<?, ?> worker;
    private boolean busy;

    GuiReadinessController() {
        ReadinessEvidenceStore.addClearListener(() -> SwingUtilities.invokeLater(this::forget));
    }

    private void forget() {
        latest = null;
        lastModel = null;
        lastRequest = null;
        offlineTimings = Map.of();
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
        if (JMeterUtils.isTestRunning()) {
            listener.failed("A test or validation is running. Stop it before checking readiness.");
            return;
        }
        GuiPackage.getInstance().updateCurrentNode();
        lastRequest = request;
        offlineTimings = Map.of();
        busy = true;
        listener.progress("Checking the configuration. Runtime checks need a readiness validation and are"
                + " shown as Not verified.");
        analyze();
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
        listener.progress("Analysing…");
        SwingWorker<Void, Void> background = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
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
        PlanModel model = PlanModel.build(tree, element -> ref(element, nodes));
        lastModel = model;
        List<String> names = new ArrayList<>();
        for (AbstractThreadGroup group : groups) {
            names.add(group.getName());
        }
        return new PlanBuild(model, Start.readinessScope(groups), names, scenarioPacing(treeModel));
    }

    private ReadinessReport report(PlanBuild build) {
        return ReadinessAnalyzer.analyze(new ReadinessAnalyzer.Input(build.model(), build.scope(), build.names(),
                lastRequest.checks(), ReadinessEvidenceStore.latest(), ValidationCheckpoints.generation(),
                lastRequest.options(), offlineTimings, build.pacing()));
    }

    private static void index(JMeterTreeNode node, IdentityHashMap<TestElement, JMeterTreeNode> nodes) {
        nodes.put(node.getTestElement(), node);
        for (int i = 0; i < node.getChildCount(); i++) {
            index((JMeterTreeNode) node.getChildAt(i), nodes);
        }
    }

    private static ElementRef ref(TestElement element, IdentityHashMap<TestElement, JMeterTreeNode> nodes) {
        JMeterTreeNode node = nodes.get(element);
        if (node == null && element instanceof AbstractThreadGroup group && !group.getThreadGroupId().isEmpty()) {
            // Sections are flattened with copies of thread groups that carry their id
            node = nodes.entrySet().stream()
                    .filter(e -> e.getKey() instanceof AbstractThreadGroup g
                            && g.getThreadGroupId().equals(group.getThreadGroupId()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        List<Integer> path = new ArrayList<>();
        for (JMeterTreeNode n = node; n != null && n.getParent() != null; n = (JMeterTreeNode) n.getParent()) {
            path.add(n.getParent().getIndex(n));
        }
        Collections.reverse(path);
        return new ElementRef(ValidationCheckpoints.sourceIdOfSource(element), element.getName(),
                element.getClass().getSimpleName(), path);
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
