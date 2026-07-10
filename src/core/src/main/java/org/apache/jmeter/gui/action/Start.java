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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JOptionPane;
import javax.swing.JToolBar;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.engine.JMeterEngineException;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.engine.TreeClonerNoTimer;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.validation.TreeClonerForValidation;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.JMeterToolBar;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
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
 *      <li>Start a Test Plan</li>
 *      <li>Start a Test Plan without sleeping on the timers</li>
 *      <li>Stop a Test Plan</li>
 *      <li>Shutdown a Test plan</li>
 *      <li>Run a set of Thread Groups</li>
 *      <li>Run a set of Thread Groups without sleeping on the timers</li>
 *      <li>Validate a set of Thread Groups with/without sleeping on the timers depending on jmeter properties</li>
 * </ul>
 */
@AutoService(Command.class)
public class Start extends AbstractAction {

    private static final Logger log = LoggerFactory.getLogger(Start.class);

    private enum RunMode {
        AS_IS,
        IGNORING_TIMERS,
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
        commands.add(ActionNames.ACTION_START_NO_TIMERS);
        commands.add(ActionNames.ACTION_PAUSE);
        commands.add(ActionNames.ACTION_STOP);
        commands.add(ActionNames.ACTION_SHUTDOWN);
        commands.add(ActionNames.RUN_TG);
        commands.add(ActionNames.RUN_TG_NO_TIMERS);
        commands.add(ActionNames.VALIDATE_TG);
    }

    private StandardJMeterEngine engine;
    private static volatile AbstractThreadGroup[] activeValidationThreadGroups;

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
     * @return original thread groups targeted by the latest validation run
     */
    public static AbstractThreadGroup[] getActiveValidationThreadGroups() {
        return activeValidationThreadGroups == null ? null : activeValidationThreadGroups.clone();
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
        if (e.getActionCommand().equals(ActionNames.ACTION_START)) {
            popupShouldSave(e);
            startEngine(null, RunMode.AS_IS);
        } else if (e.getActionCommand().equals(ActionNames.ACTION_START_NO_TIMERS)) {
            popupShouldSave(e);
            startEngine(null, RunMode.IGNORING_TIMERS);
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
                GuiPackage.getInstance().getMainFrame().showStoppingMessage("");
                engine.stopTest();
            }
        } else if (e.getActionCommand().equals(ActionNames.ACTION_SHUTDOWN)) {
            if (engine != null) {
                log.info("Shutting test down");
                GuiPackage.getInstance().getMainFrame().showStoppingMessage("");
                engine.askThreadsToStop();
            }
        } else if (e.getActionCommand().equals(ActionNames.RUN_TG)
                || e.getActionCommand().equals(ActionNames.RUN_TG_NO_TIMERS)
                || e.getActionCommand().equals(ActionNames.VALIDATE_TG)) {
            boolean noTimers = e.getActionCommand().equals(ActionNames.RUN_TG_NO_TIMERS);
            boolean isValidation = e.getActionCommand().equals(ActionNames.VALIDATE_TG);
            // Validation runs from the in-memory tree. Rewriting an existing JMX
            // archive here only blocks the EDT, particularly when recordings are
            // embedded. Keep the prompt for a new plan so relative paths still
            // get a base directory.
            if (!isValidation || GuiPackage.getInstance().getTestPlanFile() == null) {
                popupShouldSave(e);
            }
            RunMode runMode = null;
            if(isValidation) {
                runMode = RunMode.VALIDATION;
            } else if (noTimers) {
                runMode = RunMode.IGNORING_TIMERS;
            } else {
                runMode = RunMode.AS_IS;
            }
            AbstractThreadGroup[] tg;
            boolean hasExplicitThreadGroups = e instanceof ThreadGroupsActionEvent;
            JMeterTreeNode[] nodes = new JMeterTreeNode[0];
            if (hasExplicitThreadGroups) {
                tg = ((ThreadGroupsActionEvent) e).getThreadGroupsToRun();
            } else {
                JMeterTreeListener treeListener = GuiPackage.getInstance().getTreeListener();
                nodes = treeListener.getSelectedNodes();
                nodes = Copy.keepOnlyAncestors(nodes);
                tg = keepOnlyThreadGroups(nodes);
            }
            if((hasExplicitThreadGroups && tg != null && tg.length > 0) || (!hasExplicitThreadGroups && nodes.length > 0)) {
                startEngine(tg, runMode);
            }
            else {
                log.warn("No thread group selected the test will not be started");
            }
        }
    }

    /**
     * filter the nodes to keep only the thread group
     * @param currentNodes jmeter tree nodes
     * @return the thread groups
     */
    private static AbstractThreadGroup[] keepOnlyThreadGroups(JMeterTreeNode[] currentNodes) {
        List<AbstractThreadGroup> nodes = new ArrayList<>();
        for (JMeterTreeNode jMeterTreeNode : currentNodes) {
            if(jMeterTreeNode.getTestElement() instanceof AbstractThreadGroup) {
                nodes.add((AbstractThreadGroup) jMeterTreeNode.getTestElement());
            }
        }
        return nodes.toArray(new AbstractThreadGroup[nodes.size()]);
    }

    /**
     * Start JMeter engine
     * @param threadGroupsToRun Array of AbstractThreadGroup to run
     * @param runMode {@link RunMode} How to run engine
     */
    private void startEngine(AbstractThreadGroup[] threadGroupsToRun, RunMode runMode) {
        // Let samplers know this is a validation run so they keep response bodies they would
        // otherwise discard (set for every run, so a later normal run resets it).
        JMeterContextService.setValidationRun(runMode == RunMode.VALIDATION);
        activeValidationThreadGroups = runMode == RunMode.VALIDATION && threadGroupsToRun != null
                ? threadGroupsToRun.clone()
                : null;
        GuiPackage gui = GuiPackage.getInstance();
        HashTree testTree = gui.getTreeModel().getTestPlan();

        // We need to make this conversion before removing any Thread Group as 1 thread Group running may
        // reference another one (not running) using ModuleController
        // We don't clone as we'll be doing it later AND we cannot clone before we have removed the unselected ThreadGroups
        HashTree treeToUse = JMeter.convertSubTree(testTree, false);
        if(threadGroupsToRun != null && threadGroupsToRun.length>0) {
            keepOnlySelectedThreadGroupsInHashTree(treeToUse, threadGroupsToRun);
        }
        treeToUse.add(treeToUse.getArray()[0], gui.getMainFrame());
        if (log.isDebugEnabled()) {
            log.debug("test plan before cloning is running version: {}",
                    ((TestPlan) treeToUse.getArray()[0]).isRunningVersion());
        }

        ListedHashTree clonedTree = cloneTree(treeToUse, runMode);
        if ( popupCheckExistingFileListener(clonedTree) ) {
            engine = new StandardJMeterEngine();
            engine.configure(clonedTree);
            try {
                engine.runTest();
            } catch (JMeterEngineException e) {
                JOptionPane.showMessageDialog(gui.getMainFrame(), e.getMessage(),
                        JMeterUtils.getResString("error_occurred"), JOptionPane.ERROR_MESSAGE); //$NON-NLS-1$
            }
            if (log.isDebugEnabled()) {
                log.debug("test plan after cloning and running test is running version: {}",
                        ((TestPlan) treeToUse.getArray()[0]).isRunningVersion());
            }
        }
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
    private static void keepOnlySelectedThreadGroupsInHashTree(HashTree testTree, AbstractThreadGroup[] threadGroupsToKeep) {
        for (Object o : new ArrayList<>(testTree.list())) {
            TestElement item = (TestElement) o;
            if (o instanceof AbstractThreadGroup) {
                if (!isInThreadGroups(item, threadGroupsToKeep)) {
                    // hack hack hack
                    // due to the bug of equals / hashcode on AbstractTestElement
                    // where 2 AbstractTestElement can be equals but have different hashcode
                    try {
                        item.setEnabled(false);
                        testTree.remove(item);
                    } finally {
                        item.setEnabled(true);
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
        for (AbstractThreadGroup abstractThreadGroup : threadGroups) {
            if(item == abstractThreadGroup) {
                return true;
            }
        }
        return false;
    }


    /**
     * Create a Cloner that ignores {@link Timer} if removeTimers is true
     * @param testTree {@link HashTree}
     * @param runMode {@link RunMode} how plan will be run
     * @return {@link TreeCloner}
     */
    private static ListedHashTree cloneTree(HashTree testTree, RunMode runMode) {
        TreeCloner cloner = switch (runMode) {
            case VALIDATION -> createTreeClonerForValidation(false);
            case IGNORING_TIMERS -> new TreeClonerNoTimer(false);
            case AS_IS -> new TreeCloner(false);
        };
        testTree.traverse(cloner);
        return cloner.getClonedTree();
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
