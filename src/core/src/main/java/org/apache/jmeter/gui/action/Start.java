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

package org.apache.jmeter.gui.action;

import java.awt.event.ActionEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import javax.swing.JOptionPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.engine.JMeterEngineException;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.validation.TreeClonerForValidation;
import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.JMeterToolBar;
import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
import org.apache.jmeter.readiness.evidence.ReadinessRecorder;
import org.apache.jmeter.readiness.evidence.ReadinessRunListener;
import org.apache.jmeter.readiness.evidence.RunKind;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.readiness.evidence.RunStatus;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.scenario.Scenario;
import org.apache.jmeter.scenario.ScenarioException;
import org.apache.jmeter.scenario.ScenarioResolver;
import org.apache.jmeter.scenario.TestFragmentsSection;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/**
 * Set of Actions to:
 * <ul>
 *      <li>Start a Test Plan, or one of its scenarios</li>
 *      <li>Stop a Test Plan</li>
 *      <li>Shutdown a Test plan</li>
 *      <li>Validate a set of Thread Groups with/without sleeping on the timers depending on jmeter properties</li>
 * </ul>
 */
@AutoService(Command.class)
public class Start extends AbstractAction {

    private static final Logger log = LoggerFactory.getLogger(Start.class);

    private enum RunMode {
        AS_IS,
        VALIDATION
    }
    private static final Set<String> commands = new HashSet<>();

    private static final String VALIDATION_CLONER_CLASS_PROPERTY_NAME =
            "testplan_validation.tree_cloner_class"; //$NON-NLS-1$
    /**
     * Implementation of {@link TreeCloner} used to clone the tree before running validation
     */
    private static final String CLONER_FOR_VALIDATION_CLASS_NAME =
            JMeterUtils.getPropDefault(VALIDATION_CLONER_CLASS_PROPERTY_NAME, //$NON-NLS-1$
                    "org.apache.jmeter.validation.ComponentTreeClonerForValidation");

    static {
        commands.add(ActionNames.ACTION_START);
        commands.add(ActionNames.ACTION_PAUSE);
        commands.add(ActionNames.ACTION_STOP);
        commands.add(ActionNames.ACTION_SHUTDOWN);
        commands.add(ActionNames.VALIDATE_TG);
        commands.add(ActionNames.VALIDATE_ALL);
        commands.add(ActionNames.RESUME_ALL_FAILED);
        commands.add(ActionNames.VALIDATE_ALL_SEQUENTIALLY);
        commands.add(ActionNames.RESUME_VALIDATION);
        commands.add(ActionNames.RUN_SCENARIO);
        commands.add(ActionNames.VALIDATE_READINESS);
    }

    /** Commands that start a run, refused while a readiness validation collects evidence */
    private static final Set<String> STARTING_COMMANDS = Set.of(ActionNames.ACTION_START, ActionNames.VALIDATE_TG,
            ActionNames.VALIDATE_ALL, ActionNames.RESUME_ALL_FAILED, ActionNames.VALIDATE_ALL_SEQUENTIALLY,
            ActionNames.RESUME_VALIDATION, ActionNames.RUN_SCENARIO, ActionNames.VALIDATE_READINESS);

    /**
     * A validation run that collects readiness evidence.
     *
     * @param kind purpose of the run
     * @param settings validation settings of the run
     * @param listener notified when the run starts and ends, may be {@code null}
     */
    private record ReadinessRun(RunKind kind, ValidationSettings settings, ReadinessRunListener listener) {
    }

    private StandardJMeterEngine engine;
    private static AbstractThreadGroup[] lastValidationThreadGroups = new AbstractThreadGroup[0];
    private static volatile AbstractThreadGroup[] activeValidationThreadGroups;

    private static final String RESULTS_TREE_GUI = "org.apache.jmeter.visualizers.ViewResultsFullVisualizer";

    /**
     * Constructor for the Start object.
     */
    public Start() {
    }

    /**
     * Create a validation event for a known set of thread groups.
     *
     * @param source action source
     * @param id event id
     * @param threadGroupsToRun thread groups to validate
     * @return action event carrying an explicit validation target
     */
    public static ActionEvent validateThreadGroupsEvent(
            Object source, int id, AbstractThreadGroup[] threadGroupsToRun) {
        return new ThreadGroupsActionEvent(source, id, ActionNames.VALIDATE_TG, threadGroupsToRun);
    }

    /**
     * Create an event that validates thread groups while collecting script readiness evidence.
     *
     * @param source action source
     * @param threadGroupsToRun thread groups in scope
     * @param settings validation settings for this run only
     * @param kind {@link RunKind#MAIN} for a fresh readiness validation, {@link RunKind#REPEATABILITY} for the
     *     repeatability run that follows it
     * @param listener notified when the run starts and ends
     * @return the event
     */
    public static ActionEvent readinessValidationEvent(Object source, AbstractThreadGroup[] threadGroupsToRun,
            ValidationSettings settings, RunKind kind, ReadinessRunListener listener) {
        return new ReadinessActionEvent(source, threadGroupsToRun, new ReadinessRun(kind, settings, listener));
    }

    /**
     * Builds the tree a validation of the given thread groups runs, before it is cloned: enabled elements only,
     * replaceable controllers resolved, sections flattened and other thread groups removed. Script readiness
     * analyses and fingerprints this same tree.
     *
     * @param testPlan the GUI test plan, as {@link JMeterTreeModel#getTestPlan()} returns it
     * @param threadGroups thread groups in scope
     * @return the tree
     */
    public static HashTree validationTree(HashTree testPlan, AbstractThreadGroup[] threadGroups) {
        HashTree treeToUse = JMeter.convertSubTree(testPlan, false);
        if (threadGroups != null && threadGroups.length > 0) {
            treeToUse = ScenarioResolver.flattenIgnoringScenarios(treeToUse);
            keepOnlySelectedThreadGroupsInHashTree(treeToUse, threadGroups);
        }
        return treeToUse;
    }

    /**
     * @param threadGroups thread groups in scope
     * @return the scope identifiers the readiness fingerprint includes
     */
    public static List<String> readinessScope(AbstractThreadGroup[] threadGroups) {
        List<String> scope = new ArrayList<>();
        for (AbstractThreadGroup group : threadGroups) {
            scope.add(group.getThreadGroupId());
        }
        return scope;
    }

    /**
     * @return original thread groups targeted by the latest validation run
     */
    public static AbstractThreadGroup[] getActiveValidationThreadGroups() {
        return activeValidationThreadGroups == null ? null : activeValidationThreadGroups.clone();
    }

    /**
     * Release remembered validation targets when the GUI clears or replaces its test plan.
     */
    public static void clearValidationThreadGroups() {
        ValidationCheckpoints.clear();
        ReadinessEvidenceStore.clear();
        lastValidationThreadGroups = new AbstractThreadGroup[0];
        activeValidationThreadGroups = null;
    }

    /**
     * Gets the ActionNames attribute of the Start object.
     *
     * @return the ActionNames value
     */
    @Override
    public Set<String> getActionNames() {
        return commands;
    }

    @Override
    public void doAction(ActionEvent e) {
        if (ReadinessRecorder.isActive() && STARTING_COMMANDS.contains(e.getActionCommand())) {
            if (e instanceof ReadinessActionEvent readinessEvent && readinessEvent.run.listener() != null) {
                readinessEvent.run.listener().runNotStarted("A script readiness run is already in progress.");
            } else {
                JMeterUtils.reportErrorToUser("Wait for the script readiness run to finish, or cancel it first.");
            }
            return;
        }
        if (e instanceof ReadinessActionEvent readinessEvent) {
            startReadinessValidation(readinessEvent);
            return;
        }
        if (e.getActionCommand().equals(ActionNames.ACTION_START)) {
            popupShouldSave(e);
            startEngine(null, RunMode.AS_IS);
        } else if (e.getActionCommand().equals(ActionNames.RUN_SCENARIO)) {
            GuiPackage guiPackage = GuiPackage.getInstance();
            guiPackage.updateCurrentNode();
            JMeterTreeNode node = guiPackage.getTreeListener().getCurrentNode();
            if (node != null && node.getTestElement() instanceof Scenario scenario) {
                popupShouldSave(e);
                startEngine(null, RunMode.AS_IS, scenario);
            }
        } else if (e.getActionCommand().equals(ActionNames.ACTION_PAUSE)) {
            if (engine != null) {
                boolean paused = StandardJMeterEngine.togglePauseEngine();
                JToolBar mainToolbar = GuiPackage.getInstance().getMainToolbar();
                if (mainToolbar instanceof JMeterToolBar toolbar) {
                    toolbar.setLocalTestPaused(paused);
                }
            }
        } else if (e.getActionCommand().equals(ActionNames.ACTION_STOP)) {
            if (engine != null) {
                log.info("Stopping test");
                GuiPackage.getInstance().getMainFrame().showLocalTestStopping(true);
                engine.stopTest();
            }
        } else if (e.getActionCommand().equals(ActionNames.ACTION_SHUTDOWN)) {
            if (engine != null) {
                log.info("Shutting test down");
                GuiPackage.getInstance().getMainFrame().showLocalTestStopping(false);
                engine.askThreadsToStop();
            }
        } else if (e.getActionCommand().equals(ActionNames.RESUME_ALL_FAILED)) {
            if (JMeterUtils.isTestRunning()) {
                return;
            }
            GuiPackage gui = GuiPackage.getInstance();
            gui.updateCurrentNode();
            AbstractThreadGroup[] groups = Arrays.stream(findAllValidationThreadGroups(gui.getTreeModel()))
                    .filter(ValidationCheckpoints::canResumeFailedSampler).toArray(AbstractThreadGroup[]::new);
            if (groups.length == 0) {
                return;
            }
            try {
                List<ValidationCheckpoints.Checkpoint> checkpoints = Arrays.stream(groups)
                        .map(ValidationCheckpoints::get).toList();
                startEngine(groups, RunMode.VALIDATION, null, checkpoints, true, readinessContinuation());
            } catch (IllegalStateException ex) {
                JMeterUtils.reportErrorToUser(ex.getMessage());
            }
        } else if (e.getActionCommand().equals(ActionNames.RESUME_VALIDATION)) {
            GuiPackage gui = GuiPackage.getInstance();
            if (engine != null && engine.isActive()) {
                JMeterUtils.reportErrorToUser("Stop the running test before resuming validation.");
                return;
            }
            gui.updateCurrentNode();
            try {
                ValidationCheckpoints.Checkpoint checkpoint;
                AbstractThreadGroup[] groups;
                // Replays from the results tree are never readiness evidence
                ReadinessRun readiness = e.getSource() instanceof SampleResult ? null : readinessContinuation();
                if (e.getSource() instanceof SampleResult result) {
                    var target = ValidationCheckpoints.getReplayTarget(result);
                    checkpoint = target.checkpoint();
                    groups = new AbstractThreadGroup[]{target.group()};
                } else {
                    groups = findValidationThreadGroups(new JMeterTreeNode[]{gui.getCurrentNode()});
                    checkpoint = ValidationCheckpoints.get(gui.getCurrentElement(), groups.length == 0 ? null : groups[0]);
                    if (groups.length == 0) {
                        // A test fragment sampler: run the group that reached it, never the whole plan.
                        groups = new AbstractThreadGroup[]{ValidationCheckpoints.groupOf(checkpoint)};
                    }
                }
                startEngine(groups, RunMode.VALIDATION, null, List.of(checkpoint), null, readiness);
            } catch (IllegalStateException ex) {
                JMeterUtils.reportErrorToUser(ex.getMessage());
            }
        } else if (e.getActionCommand().equals(ActionNames.VALIDATE_TG)
                || e.getActionCommand().equals(ActionNames.VALIDATE_ALL)
                || e.getActionCommand().equals(ActionNames.VALIDATE_ALL_SEQUENTIALLY)) {
            boolean validateAll = !e.getActionCommand().equals(ActionNames.VALIDATE_TG);
            if (validateAll && JMeterUtils.isTestRunning()) {
                return;
            }
            AbstractThreadGroup[] tg;
            boolean hasExplicitThreadGroups = e instanceof ThreadGroupsActionEvent;
            if (validateAll) {
                tg = findAllValidationThreadGroups(GuiPackage.getInstance().getTreeModel());
            } else if (hasExplicitThreadGroups) {
                tg = ((ThreadGroupsActionEvent) e).getThreadGroupsToRun();
            } else {
                tg = findValidationThreadGroups(GuiPackage.getInstance().getTreeListener().getSelectedNodes());
            }
            tg = resolveValidationThreadGroups(tg,
                    hasExplicitThreadGroups || validateAll ? new AbstractThreadGroup[0] : lastValidationThreadGroups,
                    GuiPackage.getInstance().getTreeModel());
            if (tg.length == 0) {
                JMeterUtils.reportErrorToUser(validateAll ? "There are no enabled thread groups to validate."
                        : "Select a thread group to validate first.");
                return;
            }
            // Resolve the target before prompting to save a new plan for relative paths.
            // Existing plans can be validated directly from the in-memory tree.
            if (GuiPackage.getInstance().getTestPlanFile() == null) {
                popupShouldSave(e);
            }
            startEngine(tg, RunMode.VALIDATION, null, null, validateAll
                    ? e.getActionCommand().equals(ActionNames.VALIDATE_ALL_SEQUENTIALLY) : null);
        }
    }

    public static boolean canResumeAllFailed() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui != null && !JMeterUtils.isTestRunning()
                && Arrays.stream(findAllValidationThreadGroups(gui.getTreeModel()))
                        .anyMatch(ValidationCheckpoints::canResumeFailedSampler);
    }

    static AbstractThreadGroup[] findAllValidationThreadGroups(JMeterTreeModel model) {
        List<AbstractThreadGroup> groups = new ArrayList<>();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode plan = (JMeterTreeNode) root.getChildAt(i);
            if (!(plan.getTestElement() instanceof TestPlan) || !plan.isEnabled()) {
                continue;
            }
            for (int j = 0; j < plan.getChildCount(); j++) {
                JMeterTreeNode child = (JMeterTreeNode) plan.getChildAt(j);
                if (child.isEnabled() && child.getTestElement() instanceof AbstractThreadGroup group) {
                    groups.add(group);
                } else {
                    Collections.addAll(groups, findSectionValidationThreadGroups(child));
                }
            }
        }
        return groups.toArray(new AbstractThreadGroup[0]);
    }

    static AbstractThreadGroup[] findSectionValidationThreadGroups(JMeterTreeNode section) {
        if (!(section.getTestElement() instanceof ThreadGroupsSection)
                || !section.isEnabled()) {
            return new AbstractThreadGroup[0];
        }
        List<AbstractThreadGroup> groups = new ArrayList<>();
        for (int i = 0; i < section.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) section.getChildAt(i);
            if (child.isEnabled() && child.getTestElement() instanceof AbstractThreadGroup group) {
                groups.add(group);
            }
        }
        return groups.toArray(new AbstractThreadGroup[0]);
    }

    static AbstractThreadGroup[] findValidationThreadGroups(JMeterTreeNode[] selectedNodes) {
        List<AbstractThreadGroup> groups = new ArrayList<>();
        Set<AbstractThreadGroup> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (JMeterTreeNode selectedNode : selectedNodes) {
            for (JMeterTreeNode node = selectedNode; node != null; node = (JMeterTreeNode) node.getParent()) {
                if (node.getTestElement() instanceof AbstractThreadGroup group) {
                    // Test elements can compare equal even when they belong to different groups.
                    if (seen.add(group)) {
                        groups.add(group);
                    }
                    break;
                }
            }
        }
        return groups.toArray(new AbstractThreadGroup[0]);
    }

    static AbstractThreadGroup[] resolveValidationThreadGroups(AbstractThreadGroup[] selected,
            AbstractThreadGroup[] previous, JMeterTreeModel treeModel) {
        AbstractThreadGroup[] candidates = selected != null && selected.length > 0 ? selected : previous;
        List<AbstractThreadGroup> available = new ArrayList<>();
        for (AbstractThreadGroup threadGroup : candidates) {
            // A deleted group or a group from a previous plan must never turn into a full-plan run.
            if (treeModel.getNodeOf(threadGroup) != null) {
                available.add(threadGroup);
            }
        }
        return available.toArray(new AbstractThreadGroup[0]);
    }

    /**
     * Start JMeter engine
     * @param threadGroupsToRun Array of AbstractThreadGroup to run
     * @param runMode {@link RunMode} How to run engine
     */
    private void startEngine(AbstractThreadGroup[] threadGroupsToRun, RunMode runMode) {
        startEngine(threadGroupsToRun, runMode, null);
    }

    /**
     * Start JMeter engine
     * @param threadGroupsToRun Array of AbstractThreadGroup to run
     * @param runMode {@link RunMode} How to run engine
     * @param scenario scenario to run instead of the enabled one, or {@code null}
     */
    private void startEngine(AbstractThreadGroup[] threadGroupsToRun, RunMode runMode, Scenario scenario) {
        startEngine(threadGroupsToRun, runMode, scenario, null);
    }

    private void startEngine(AbstractThreadGroup[] threadGroupsToRun, RunMode runMode, Scenario scenario,
            ValidationCheckpoints.Checkpoint checkpoint) {
        startEngine(threadGroupsToRun, runMode, scenario, checkpoint == null ? null : List.of(checkpoint), null);
    }

    private void startEngine(AbstractThreadGroup[] threadGroupsToRun, RunMode runMode, Scenario scenario,
            List<ValidationCheckpoints.Checkpoint> checkpoints, Boolean sequential) {
        startEngine(threadGroupsToRun, runMode, scenario, checkpoints, sequential, null);
    }

    /**
     * @return the readiness run a Resume validation continues, or {@code null} when it does not continue one
     */
    private static ReadinessRun readinessContinuation() {
        if (!ReadinessEvidenceStore.canContinueWithResume()) {
            return null;
        }
        // Resume with the settings the readiness validation ran with, so the resumed run matches it
        return new ReadinessRun(RunKind.RESUME, ReadinessEvidenceStore.latest().main().settings(), null);
    }

    private void startReadinessValidation(ReadinessActionEvent event) {
        ReadinessRun readiness = event.run;
        GuiPackage gui = GuiPackage.getInstance();
        if (JMeterUtils.isTestRunning() || (engine != null && engine.isActive())) {
            readiness.listener().runNotStarted("Stop the running test or validation before checking readiness.");
            return;
        }
        gui.updateCurrentNode();
        AbstractThreadGroup[] tg = resolveValidationThreadGroups(event.threadGroupsToRun, new AbstractThreadGroup[0],
                gui.getTreeModel());
        if (tg.length == 0) {
            readiness.listener().runNotStarted("There are no enabled thread groups in scope.");
            return;
        }
        startEngine(tg, RunMode.VALIDATION, null, null, null, readiness);
    }

    private void startEngine(AbstractThreadGroup[] threadGroupsToRun, RunMode runMode, Scenario scenario,
            List<ValidationCheckpoints.Checkpoint> checkpoints, Boolean sequential, ReadinessRun readiness) {
        boolean[] readinessStarted = {false};
        try {
            startEngine(threadGroupsToRun, runMode, scenario, checkpoints, sequential, readiness, readinessStarted);
        } finally {
            if (readiness != null && !readinessStarted[0] && readiness.listener() != null) {
                readiness.listener().runNotStarted("The validation run did not start.");
            }
        }
    }

    private void startEngine(AbstractThreadGroup[] threadGroupsToRun, RunMode runMode, Scenario scenario,
            List<ValidationCheckpoints.Checkpoint> checkpoints, Boolean sequential, ReadinessRun readiness,
            boolean[] readinessStarted) {
        // Let samplers know this is a validation run so they keep response bodies they would
        // otherwise discard (set for every run, so a later normal run resets it).
        JMeterContextService.setValidationRun(runMode == RunMode.VALIDATION);
        activeValidationThreadGroups = runMode == RunMode.VALIDATION && threadGroupsToRun != null
                ? threadGroupsToRun.clone()
                : null;
        GuiPackage gui = GuiPackage.getInstance();
        JMeterTreeNode resultsTree = null;
        if (runMode == RunMode.VALIDATION
                && JMeterUtils.getPropDefault("testplan_validation.jump_to_visual_tree", true)) {
            resultsTree = findValidationResultsTree(gui.getTreeModel(), threadGroupsToRun);
        }
        // A repeatability run keeps the source ids of the readiness validation it repeats
        if (runMode == RunMode.VALIDATION && checkpoints == null
                && (readiness == null || readiness.kind() != RunKind.REPEATABILITY)) {
            ValidationCheckpoints.clear();
        }
        HashTree testTree = gui.getTreeModel().getTestPlan();

        // We need to make this conversion before removing any Thread Group as 1 thread Group running may
        // reference another one (not running) using ModuleController
        // We don't clone as we'll be doing it later AND we cannot clone before we have removed the unselected ThreadGroups
        HashTree treeToUse;
        if (runMode == RunMode.VALIDATION && scenario == null) {
            try {
                // Validation replaces the workload anyway, so scenarios are ignored
                treeToUse = validationTree(testTree, threadGroupsToRun);
            } catch (ScenarioException e) {
                JOptionPane.showMessageDialog(gui.getMainFrame(), e.getMessage(),
                        JMeterUtils.getResString("error_occurred"), JOptionPane.ERROR_MESSAGE); //$NON-NLS-1$
                return;
            }
        } else {
            treeToUse = JMeter.convertSubTree(testTree, false);
            if (scenario != null) {
                try {
                    treeToUse = ScenarioResolver.resolve(treeToUse, scenario);
                } catch (ScenarioException e) {
                    JOptionPane.showMessageDialog(gui.getMainFrame(), e.getMessage(),
                            JMeterUtils.getResString("error_occurred"), JOptionPane.ERROR_MESSAGE); //$NON-NLS-1$
                    return;
                }
            }
            if(threadGroupsToRun != null && threadGroupsToRun.length>0) {
                try {
                    // Validation replaces the workload anyway; other runs use the workloads of the enabled scenario
                    treeToUse = runMode == RunMode.VALIDATION
                            ? ScenarioResolver.flattenIgnoringScenarios(treeToUse)
                            : ScenarioResolver.resolve(treeToUse);
                } catch (ScenarioException e) {
                    JOptionPane.showMessageDialog(gui.getMainFrame(), e.getMessage(),
                            JMeterUtils.getResString("error_occurred"), JOptionPane.ERROR_MESSAGE); //$NON-NLS-1$
                    return;
                }
                keepOnlySelectedThreadGroupsInHashTree(treeToUse, threadGroupsToRun);
            }
        }
        ValidationSettings settings = readiness != null ? readiness.settings() : ValidationSettings.defaults();
        String fingerprint = readiness == null ? null
                : readiness.kind() == RunKind.RESUME ? resumeReadinessFingerprint(gui.getTreeModel(), settings)
                : PlanFingerprint.compute(treeToUse, settings, readinessScope(threadGroupsToRun));
        treeToUse.add(treeToUse.getArray()[0], gui.getMainFrame());
        if (log.isDebugEnabled()) {
            log.debug("test plan before cloning is running version: {}",
                    ((TestPlan) treeToUse.getArray()[0]).isRunningVersion());
        }

        ListedHashTree clonedTree = cloneTree(treeToUse, runMode, settings);
        if (sequential != null) {
            ((TestPlan) clonedTree.getArray()[0]).setSerialized(sequential);
        }
        if (checkpoints != null) {
            ValidationCheckpoints.prepareResume(clonedTree, checkpoints);
        } else if (runMode == RunMode.VALIDATION) {
            ValidationCheckpoints.rememberStructure(clonedTree);
        }
        if ( popupCheckExistingFileListener(clonedTree) ) {
            engine = new StandardJMeterEngine();
            engine.configure(clonedTree);
            StandardJMeterEngine startedEngine = engine;
            startedEngine.addAlwaysEndListener(gui.getMainFrame());
            RunSegment segment = readiness == null ? null
                    : beginReadinessSegment(startedEngine, readiness, threadGroupsToRun, checkpoints, fingerprint);
            startedEngine.addStoppingListener(immediately -> SwingUtilities.invokeLater(() -> {
                if (startedEngine.isActive()) {
                    gui.getMainFrame().showLocalTestStopping(immediately);
                }
            }));
            readinessStarted[0] = true;
            try {
                engine.runTest();
                if (runMode == RunMode.VALIDATION) {
                    lastValidationThreadGroups = threadGroupsToRun.clone();
                    if (resultsTree != null) {
                        TreePath path = new TreePath(resultsTree.getPath());
                        gui.getTreeListener().setSelectionPathWithoutEdit(path);
                        gui.getMainFrame().getTree().scrollPathToVisible(path);
                        ActionRouter.getInstance().doActionNow(new ActionEvent(this, 0, ActionNames.EDIT));
                    }
                }
            } catch (JMeterEngineException e) {
                if (segment != null) {
                    segment.markStatus(RunStatus.FAILED_TO_START, e.getMessage());
                    ReadinessEvidenceStore.end(segment);
                    if (readiness.listener() != null) {
                        readiness.listener().runFinished(segment);
                    }
                }
                JOptionPane.showMessageDialog(gui.getMainFrame(), e.getMessage(),
                        JMeterUtils.getResString("error_occurred"), JOptionPane.ERROR_MESSAGE); //$NON-NLS-1$
            }
            if (log.isDebugEnabled()) {
                log.debug("test plan after cloning and running test is running version: {}",
                        ((TestPlan) treeToUse.getArray()[0]).isRunningVersion());
            }
        }
    }

    /** Fingerprint the original readiness scope, even when only a subset is being resumed. */
    static String resumeReadinessFingerprint(JMeterTreeModel model, ValidationSettings settings) {
        List<String> originalScope = ReadinessEvidenceStore.latest().main().scope();
        AbstractThreadGroup[] groups = Arrays.stream(findAllValidationThreadGroups(model))
                .filter(group -> originalScope.contains(ValidationCheckpoints.sourceIdOfSource(group)))
                .toArray(AbstractThreadGroup[]::new);
        HashTree tree = validationTree(model.getTestPlan(), groups);
        return PlanFingerprint.compute(tree, settings, readinessScope(groups));
    }

    private static RunSegment beginReadinessSegment(StandardJMeterEngine startedEngine, ReadinessRun readiness,
            AbstractThreadGroup[] threadGroups, List<ValidationCheckpoints.Checkpoint> checkpoints,
            String fingerprint) {
        List<String> scope = new ArrayList<>();
        for (AbstractThreadGroup group : threadGroups) {
            scope.add(ValidationCheckpoints.sourceIdOfSource(group));
        }
        Set<String> resumedFrom = new HashSet<>();
        if (checkpoints != null) {
            checkpoints.forEach(checkpoint -> resumedFrom.add(checkpoint.samplerId()));
        }
        RunSegment segment = new RunSegment(readiness.kind(), fingerprint, readiness.settings(), scope,
                ValidationCheckpoints.generation(), resumedFrom);
        ReadinessEvidenceStore.begin(segment);
        startedEngine.addStoppingListener(immediately -> segment.markStatus(RunStatus.CANCELLED,
                "The run was stopped before it completed"));
        startedEngine.addAlwaysEndListener(new TestStateListener() {
            @Override
            public void testStarted() {
                // Evidence collection started with the segment
            }

            @Override
            public void testStarted(String host) {
                testStarted();
            }

            @Override
            public void testEnded() {
                ReadinessEvidenceStore.end(segment);
                if (readiness.listener() != null) {
                    readiness.listener().runFinished(segment);
                }
            }

            @Override
            public void testEnded(String host) {
                testEnded();
            }
        });
        if (readiness.listener() != null) {
            readiness.listener().runStarted(segment);
        }
        return segment;
    }

    static JMeterTreeNode findValidationResultsTree(JMeterTreeModel model,
            AbstractThreadGroup[] threadGroups) {
        JMeterTreeNode groupListener = null;
        for (JMeterTreeNode node : model.getNodesOfType(TestElement.class)) {
            if (!RESULTS_TREE_GUI.equals(node.getTestElement().getPropertyAsString(TestElement.GUI_CLASS))) {
                continue;
            }
            boolean enabled = true;
            AbstractThreadGroup group = null;
            for (JMeterTreeNode parent = node; parent != null; parent = (JMeterTreeNode) parent.getParent()) {
                // Fragment listeners are not plan-wide listeners, even without a Thread Group ancestor.
                if (!parent.isEnabled() || parent.getTestElement() instanceof TestFragmentController
                        || parent.getTestElement() instanceof TestFragmentsSection) {
                    enabled = false;
                    break;
                }
                if (parent.getTestElement() instanceof AbstractThreadGroup threadGroup) {
                    group = threadGroup;
                }
            }
            if (!enabled) {
                continue;
            }
            // Prefer a plan-wide listener so validation of several groups is visible together.
            if (group == null) {
                return node;
            }
            if (groupListener == null && isInThreadGroups(group, threadGroups)) {
                groupListener = node;
            }
        }
        return groupListener;
    }

    /**
     *
     * @return {@link TreeCloner}
     */
    private static TreeCloner createTreeClonerForValidation(boolean honorThreadClone) {
        Class<?> clazz;
        try {
            clazz = Class.forName(CLONER_FOR_VALIDATION_CLASS_NAME, true, Thread.currentThread().getContextClassLoader());
            return (TreeCloner) clazz.getConstructor(boolean.class).newInstance(honorThreadClone);
        } catch (InstantiationException | IllegalAccessException
                | ClassNotFoundException | NoSuchMethodException
                | InvocationTargetException ex) {
            log.error("Error instantiating class:'{}' defined in property:'{}'", CLONER_FOR_VALIDATION_CLASS_NAME,
                    VALIDATION_CLONER_CLASS_PROPERTY_NAME, ex);
            return new TreeClonerForValidation(honorThreadClone);
        }
    }

    /**
     * Keep only thread groups in testTree that are in threadGroupsToKeep
     * @param testTree {@link HashTree}
     * @param threadGroupsToKeep Array of {@link AbstractThreadGroup} to keep
     */
    static void keepOnlySelectedThreadGroupsInHashTree(HashTree testTree, AbstractThreadGroup[] threadGroupsToKeep) {
        for (Object o : new ArrayList<>(testTree.list())) {
            TestElement item = (TestElement) o;
            if (o instanceof AbstractThreadGroup) {
                if (!isInThreadGroups(item, threadGroupsToKeep)) {
                    // hack hack hack
                    // due to the bug of equals / hashcode on AbstractTestElement
                    // where 2 AbstractTestElement can be equals but have different hashcode
                    JMeterProperty enabled = item.getPropertyOrNull(TestElement.ENABLED);
                    enabled = enabled == null ? null : enabled.clone();
                    try {
                        item.setEnabled(false);
                        testTree.remove(item);
                    } finally {
                        // Preserve absent defaults too: preparing a subset must not change the plan fingerprint.
                        if (enabled == null) {
                            item.removeProperty(TestElement.ENABLED);
                        } else {
                            item.setProperty(enabled);
                        }
                    }
                }
                else {
                    keepOnlySelectedThreadGroupsInHashTree(testTree.getTree(item), threadGroupsToKeep);
                }
            }
            else {
                keepOnlySelectedThreadGroupsInHashTree(testTree.getTree(item), threadGroupsToKeep);
            }
        }
    }

    /**
     * @param item {@link TestElement}
     * @param threadGroups Array of {@link AbstractThreadGroup}
     * @return true if item is in threadGroups array
     */
    private static boolean isInThreadGroups(TestElement item, AbstractThreadGroup[] threadGroups) {
        String id = item instanceof AbstractThreadGroup threadGroup ? threadGroup.getThreadGroupId() : "";
        for (AbstractThreadGroup abstractThreadGroup : threadGroups) {
            // Scenario workloads run clones of the thread group, so they are matched by id
            if(item == abstractThreadGroup || (!id.isEmpty() && id.equals(abstractThreadGroup.getThreadGroupId()))) {
                return true;
            }
        }
        return false;
    }


    /**
     * Clone the test tree, ignoring {@link Timer}s when validating if the jmeter properties say so
     * @param testTree {@link HashTree}
     * @param runMode {@link RunMode} how plan will be run
     * @param settings validation settings, used when validating
     * @return {@link TreeCloner}
     */
    private static ListedHashTree cloneTree(HashTree testTree, RunMode runMode, ValidationSettings settings) {
        TreeCloner cloner = switch (runMode) {
            case VALIDATION -> createTreeClonerForValidation(false);
            case AS_IS -> new TreeCloner(false);
        };
        if (cloner instanceof TreeClonerForValidation validationCloner) {
            validationCloner.setSettings(settings);
        }
        testTree.traverse(cloner);
        return cloner.getClonedTree();
    }

    private static final class ReadinessActionEvent extends ActionEvent {
        private static final long serialVersionUID = 1L;

        private final transient AbstractThreadGroup[] threadGroupsToRun;
        private final transient ReadinessRun run;

        private ReadinessActionEvent(Object source, AbstractThreadGroup[] threadGroupsToRun, ReadinessRun run) {
            super(source, ActionEvent.ACTION_PERFORMED, ActionNames.VALIDATE_READINESS);
            this.threadGroupsToRun = threadGroupsToRun.clone();
            this.run = run;
        }
    }

    private static final class ThreadGroupsActionEvent extends ActionEvent {
        private static final long serialVersionUID = 1L;

        private final AbstractThreadGroup[] threadGroupsToRun;

        private ThreadGroupsActionEvent(
                Object source, int id, String command, AbstractThreadGroup[] threadGroupsToRun) {
            super(source, id, command);
            this.threadGroupsToRun = threadGroupsToRun;
        }

        private AbstractThreadGroup[] getThreadGroupsToRun() {
            return threadGroupsToRun;
        }
    }
}
