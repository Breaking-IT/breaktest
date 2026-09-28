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


package org.apache.jmeter.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.NonTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

/**
 * Organises a test plan saved before scenarios existed into sections:
 * <ul>
 *     <li>one enabled scenario that runs every thread group with the settings it had,</li>
 *     <li>the thread groups, keeping only their script,</li>
 *     <li>the test-level listeners,</li>
 *     <li>the other test-level elements (configuration, timers, processors, assertions) in the shared profile,</li>
 *     <li>the test fragments, unchanged,</li>
 *     <li>the non-test elements such as the recorder, only when there are any.</li>
 * </ul>
 */
public final class ScenarioPlanMigration {

    private static final String SECTION_GUI_PACKAGE = "org.apache.jmeter.scenario.gui."; // $NON-NLS-1$

    private static final String MODULE_CONTROLLER_NODE_PATH = "ModuleController.node_path"; // $NON-NLS-1$

    /**
     * The order of the sections under the test plan: listeners are always at hand at the top, and the thread groups,
     * which grow the largest, come last.
     */
    public static final List<Class<? extends TestPlanSection>> SECTION_ORDER = List.of(
            ListenersSection.class,
            ScenariosSection.class,
            ProfilesSection.class,
            TestFragmentsSection.class,
            ThreadGroupsSection.class,
            NonTestElementsSection.class);

    private ScenarioPlanMigration() {
    }

    /**
     * Tells in which section an element belongs when it is placed at the test plan level.
     * @param element an element being added to the test plan
     * @return the section class, or {@code null} for elements that cannot be placed at the test plan level
     */
    public static Class<? extends TestPlanSection> sectionFor(Object element) {
        if (element instanceof Scenario) {
            return ScenariosSection.class;
        }
        if (element instanceof Profile) {
            return ProfilesSection.class;
        }
        if (element instanceof SharedProfile) {
            return null;
        }
        if (element instanceof AbstractThreadGroup) {
            return ThreadGroupsSection.class;
        }
        if (element instanceof TestFragmentController) {
            return TestFragmentsSection.class;
        }
        if (element instanceof NonTestElement) {
            return NonTestElementsSection.class;
        }
        if (!(element instanceof TestElement) || element instanceof TestPlan || element instanceof TestPlanSection
                || element instanceof ScenarioWorkload || element instanceof Sampler || element instanceof Controller) {
            return null;
        }
        return element instanceof SampleListener ? ListenersSection.class : ProfilesSection.class;
    }

    /**
     * @param tree a loaded test plan organised in sections
     * @return whether its sections are out of {@link #SECTION_ORDER} or its fixed nodes have outdated names
     */
    public static boolean needsNormalizing(HashTree tree) {
        Object[] roots = tree.getArray();
        if (roots.length == 0 || !(roots[0] instanceof TestPlan)) {
            return false;
        }
        HashTree planTree = tree.getTree(roots[0]);
        return !orderedSections(planTree).equals(new ArrayList<>(planTree.list()))
                || sharedProfiles(planTree).stream().anyMatch(shared -> !shared.getName().equals(sharedProfileName()));
    }

    /**
     * Puts the sections of a test plan in {@link #SECTION_ORDER}, other elements under the test plan keeping their
     * order after them, and gives the fixed Shared Profile its current name. Their names cannot be edited.
     * @param tree a loaded test plan organised in sections
     * @return the normalized plan
     */
    public static HashTree normalize(HashTree tree) {
        Object[] roots = tree.getArray();
        if (roots.length == 0 || !(roots[0] instanceof TestPlan)) {
            return tree;
        }
        HashTree planTree = tree.getTree(roots[0]);
        sharedProfiles(planTree).forEach(shared -> shared.setName(sharedProfileName()));
        HashTree result = new ListedHashTree();
        HashTree newPlanTree = result.add(roots[0]);
        for (Object child : orderedSections(planTree)) {
            newPlanTree.add(child, planTree.getTree(child));
        }
        keepOtherRoots(tree, result);
        return result;
    }

    private static List<SharedProfile> sharedProfiles(HashTree planTree) {
        List<SharedProfile> shared = new ArrayList<>();
        for (Object child : planTree.list()) {
            if (child instanceof ProfilesSection) {
                for (Object profile : planTree.getTree(child).list()) {
                    if (profile instanceof SharedProfile sharedProfile) {
                        shared.add(sharedProfile);
                    }
                }
            }
        }
        return shared;
    }

    private static String sharedProfileName() {
        return JMeterUtils.getResString("shared_profile"); // $NON-NLS-1$
    }

    private static List<Object> orderedSections(HashTree planTree) {
        List<Object> ordered = new ArrayList<>();
        for (Class<? extends TestPlanSection> sectionClass : SECTION_ORDER) {
            for (Object child : planTree.list()) {
                if (child.getClass() == sectionClass) {
                    ordered.add(child);
                }
            }
        }
        for (Object child : planTree.list()) {
            if (!ordered.contains(child)) {
                ordered.add(child);
            }
        }
        return ordered;
    }

    /**
     * @param tree a loaded test plan
     * @return whether the plan has no sections yet and should be migrated
     */
    public static boolean needsMigration(HashTree tree) {
        Object[] roots = tree.getArray();
        return roots.length > 0 && roots[0] instanceof TestPlan
                && tree.getTree(roots[0]).list().stream().noneMatch(TestPlanSection.class::isInstance);
    }

    /**
     * @param tree a loaded test plan
     * @return the plan organised in sections, or {@code tree} itself when it does not need a migration
     */
    public static HashTree migrate(HashTree tree) {
        return migrate(tree, new ArrayList<>());
    }

    /**
     * @param tree a loaded test plan
     * @param changedVariables receives the names of variables whose value at the start of a thread may differ from
     *     the old plan, because the plan relied on both orders of User Defined Variables
     * @return the plan organised in sections, or {@code tree} itself when it does not need a migration
     */
    public static HashTree migrate(HashTree tree, List<String> changedVariables) {
        if (!needsMigration(tree)) {
            return tree;
        }
        Object plan = tree.getArray()[0];
        HashTree planTree = tree.getTree(plan);
        boolean sharedOverrides = sharedVariablesMustOverride(planTree, changedVariables);
        // Resolved before thread groups are renamed, while the saved paths still find their targets
        Map<TestElement, Object> moduleTargets = new LinkedHashMap<>();
        findModuleTargets(tree, planTree, moduleTargets);

        Scenario scenario = new Scenario(JMeterUtils.getResString("scenario_title")); // $NON-NLS-1$
        setGuiClass(scenario, "ScenarioGui"); // $NON-NLS-1$

        Map<Class<? extends TestPlanSection>, HashTree> sectionTrees = new LinkedHashMap<>();
        for (Class<? extends TestPlanSection> sectionClass : SECTION_ORDER) {
            sectionTrees.put(sectionClass, new ListedHashTree());
        }
        sectionTrees.get(ScenariosSection.class).add(scenario);
        HashTree otherTree = new ListedHashTree();
        List<ScenarioWorkload> workloads = new ArrayList<>();
        Map<Object, Object> replaced = new HashMap<>();
        Set<String> threadGroupNames = new HashSet<>();
        Set<String> threadGroupIds = new HashSet<>();

        for (Object child : planTree.list()) {
            HashTree childTree = planTree.getTree(child);
            Object element = child;
            if (child instanceof AbstractThreadGroup original) {
                AbstractThreadGroup threadGroup = original instanceof OpenModelThreadGroup openModel
                        ? toThreadGroup(openModel)
                        : original;
                // Scenario rows, other formats and the command line refer to thread groups by name and id
                replaced.put(original, threadGroup);
                threadGroup.setName(uniqueName(threadGroup.getName(), threadGroupNames));
                threadGroup.setThreadGroupId(AbstractThreadGroup.uniqueReadableId(threadGroup.getName(), threadGroupIds));
                threadGroupIds.add(threadGroup.getThreadGroupId());
                workloads.add(workloadOf(threadGroup));
                keepOnlyScript(threadGroup);
                element = threadGroup;
            }
            Class<? extends TestPlanSection> section = sectionFor(element);
            if (section == null) {
                otherTree.add(element, childTree);
            } else {
                // Test Fragments stay wrapped: Include Controllers use the first fragment of a file, and Module
                // Controllers may run a fragment as a whole
                sectionTrees.get(section).add(element, childTree);
            }
        }
        scenario.setWorkloads(workloads);
        if (plan instanceof TestPlan testPlan) {
            // Whether thread groups run one after another is now a setting of the scenario
            scenario.setRunConsecutively(testPlan.isSerialized());
            testPlan.setSerialized(false);
        }

        HashTree result = new ListedHashTree();
        result.add(plan);
        HashTree newPlanTree = result.getTree(plan);
        Map<Class<? extends TestPlanSection>, String> sectionNames = new HashMap<>();
        for (Map.Entry<Class<? extends TestPlanSection>, HashTree> entry : sectionTrees.entrySet()) {
            if (entry.getKey() == NonTestElementsSection.class && entry.getValue().isEmpty()) {
                continue; // Only created when the plan has such elements
            }
            TestPlanSection section = newSection(entry.getKey());
            sectionNames.put(entry.getKey(), section.getName());
            if (section instanceof ProfilesSection) {
                // The test-level configuration applies to every thread group: it becomes the shared profile
                SharedProfile shared = newSharedProfile();
                shared.setOverridingThreadGroupVariables(sharedOverrides);
                newPlanTree.add(section).add(shared, entry.getValue());
            } else {
                newPlanTree.add(section, entry.getValue());
            }
        }
        newPlanTree.add(otherTree);
        fixModuleControllerPaths(moduleTargets, replaced, sectionNames);
        keepOtherRoots(tree, result);
        return result;
    }

    /**
     * @param sectionClass the kind of section to create
     * @return a new, named section ready to be added to a test plan
     */
    public static TestPlanSection newSection(Class<? extends TestPlanSection> sectionClass) {
        TestPlanSection section;
        String labelResource;
        if (sectionClass == ScenariosSection.class) {
            section = new ScenariosSection();
            labelResource = "scenarios_section"; // $NON-NLS-1$
        } else if (sectionClass == ThreadGroupsSection.class) {
            section = new ThreadGroupsSection();
            labelResource = "thread_groups_section"; // $NON-NLS-1$
        } else if (sectionClass == ListenersSection.class) {
            section = new ListenersSection();
            labelResource = "listeners_section"; // $NON-NLS-1$
        } else if (sectionClass == ProfilesSection.class) {
            section = new ProfilesSection();
            labelResource = "profiles_section"; // $NON-NLS-1$
        } else if (sectionClass == TestFragmentsSection.class) {
            section = new TestFragmentsSection();
            labelResource = "test_fragments_section"; // $NON-NLS-1$
        } else {
            section = new NonTestElementsSection();
            labelResource = "non_test_elements_section"; // $NON-NLS-1$
        }
        section.setName(JMeterUtils.getResString(labelResource));
        setGuiClass(section, section.getClass().getSimpleName() + "Gui"); // $NON-NLS-1$
        return section;
    }

    /**
     * @return a new shared profile, the part of the Profiles section that applies to every thread group
     */
    public static SharedProfile newSharedProfile() {
        SharedProfile shared = new SharedProfile();
        shared.setName(JMeterUtils.getResString("shared_profile")); // $NON-NLS-1$
        setGuiClass(shared, "SharedProfileGui"); // $NON-NLS-1$
        return shared;
    }

    private static void setGuiClass(TestElement element, String guiSimpleName) {
        element.setProperty(TestElement.GUI_CLASS, SECTION_GUI_PACKAGE + guiSimpleName);
        element.setProperty(TestElement.TEST_CLASS, element.getClass().getName());
    }

    private static ScenarioWorkload workloadOf(AbstractThreadGroup threadGroup) {
        ScenarioWorkload workload = new ScenarioWorkload();
        workload.setName(threadGroup.getName());
        workload.setThreadGroupId(threadGroup.getOrCreateThreadGroupId());
        if (!ScenarioWorkload.usesOwnSettings(threadGroup)) {
            workload.copyWorkloadFrom(threadGroup);
        }
        // A disabled thread group stays in the plan; its workload is disabled so the scenario can still run
        workload.setEnabled(threadGroup.isEnabled());
        setGuiClass(workload, "ScenarioWorkloadGui"); // $NON-NLS-1$
        return workload;
    }

    private static void keepOnlyScript(AbstractThreadGroup threadGroup) {
        if (ScenarioWorkload.usesOwnSettings(threadGroup)) {
            return;
        }
        boolean stopOnError = threadGroup.getOnErrorStopTest() || threadGroup.getOnErrorStopTestNow()
                || threadGroup.getOnErrorStopThread();
        ScenarioWorkload.removeWorkload(threadGroup);
        threadGroup.setValidationStopOnError(stopOnError);
    }

    /** Open Model Thread Groups became the open model of the regular thread group. */
    private static ThreadGroup toThreadGroup(OpenModelThreadGroup source) {
        ThreadGroup target = new ThreadGroup();
        PropertyIterator properties = source.propertyIterator();
        while (properties.hasNext()) {
            target.setProperty(properties.next().clone());
        }
        target.setThreadGroupModel(ThreadGroup.MODEL_OPEN);
        target.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui"); // $NON-NLS-1$
        target.setProperty(TestElement.TEST_CLASS, ThreadGroup.class.getName());
        return target;
    }

    /**
     * User Defined Variables apply in tree order, the last one winning. In an old plan, test plan level variables
     * that come after a thread group defining the same variable win over it; those before it lose. In sections, the
     * Shared Profile setting decides for all of them at once.
     * @param planTree the children of the old test plan
     * @param changedVariables receives the variables whose starting value cannot be kept
     * @return whether the shared variables must override the variables of thread groups
     */
    private static boolean sharedVariablesMustOverride(HashTree planTree, List<String> changedVariables) {
        Set<String> definedShared = new HashSet<>();
        Set<String> definedInThreadGroup = new HashSet<>();
        // Whether the last definition of each variable is at the test plan level
        Map<String, Boolean> lastDefinedShared = new HashMap<>();
        for (Object child : planTree.list()) {
            if (child instanceof TestElement element && !element.isEnabled()) {
                continue;
            }
            if (child.getClass() == Arguments.class) {
                for (String name : ((Arguments) child).getArgumentsAsMap().keySet()) {
                    definedShared.add(name);
                    lastDefinedShared.put(name, Boolean.TRUE);
                }
            } else if (child instanceof AbstractThreadGroup) {
                for (String name : ScenarioResolver.userDefinedVariableNames(planTree.getTree(child))) {
                    definedInThreadGroup.add(name);
                    lastDefinedShared.put(name, Boolean.FALSE);
                }
            }
        }
        Set<String> sharedWins = new TreeSet<>();
        Set<String> threadGroupWins = new TreeSet<>();
        for (String name : definedShared) {
            if (definedInThreadGroup.contains(name)) {
                (lastDefinedShared.get(name) ? sharedWins : threadGroupWins).add(name);
            }
        }
        boolean override = sharedWins.size() > threadGroupWins.size();
        changedVariables.addAll(override ? threadGroupWins : sharedWins);
        return override;
    }

    /**
     * Plans saved by old versions can have a WorkBench next to the test plan. Loading moves its content into the
     * test plan, so it must be kept.
     */
    private static void keepOtherRoots(HashTree tree, HashTree result) {
        Object[] roots = tree.getArray();
        for (int i = 1; i < roots.length; i++) {
            result.add(roots[i], tree.getTree(roots[i]));
        }
    }

    private static String uniqueName(String name, Set<String> used) {
        String base = name == null ? "" : name;
        String candidate = base;
        for (int i = 2; !used.add(candidate); i++) {
            candidate = base + " (" + i + ")";
        }
        return candidate;
    }

    /**
     * Finds, for each Module Controller, the child of the test plan its saved path goes through, the way a Module
     * Controller resolves it: when several elements have the same names, the last one that has the whole path wins.
     */
    private static void findModuleTargets(HashTree tree, HashTree planTree, Map<TestElement, Object> targets) {
        for (Object element : tree.list()) {
            if (element instanceof TestElement testElement
                    && testElement.getProperty(MODULE_CONTROLLER_NODE_PATH) instanceof CollectionProperty path
                    && path.size() > 2) {
                List<String> names = new ArrayList<>();
                for (JMeterProperty name : path) {
                    names.add(name.getStringValue());
                }
                Object target = null;
                Object sameName = null;
                for (Object child : planTree.list()) {
                    if (hasPath(child, planTree.getTree(child), names, 2)) {
                        target = child;
                    }
                    if (sameName == null && hasPath(child, planTree.getTree(child), names.subList(0, 3), 2)) {
                        sameName = child;
                    }
                }
                // A path that does not resolve still moves along with the element it names
                target = target == null ? sameName : target;
                if (target != null) {
                    targets.put(testElement, target);
                }
            }
            findModuleTargets(tree.getTree(element), planTree, targets);
        }
    }

    private static boolean hasPath(Object element, HashTree elementTree, List<String> names, int level) {
        if (!(element instanceof TestElement testElement) || isModuleController(testElement)
                || !testElement.getName().equals(names.get(level))) {
            return false;
        }
        if (level == names.size() - 1) {
            return true;
        }
        for (Object child : elementTree.list()) {
            if (hasPath(child, elementTree.getTree(child), names, level + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isModuleController(TestElement element) {
        // A Module Controller never targets another one
        return element.getProperty(MODULE_CONTROLLER_NODE_PATH) instanceof CollectionProperty;
    }

    /**
     * Module Controllers find their target by the names on its tree path. Thread groups and test fragments now sit
     * one level deeper, in their section, and thread groups may have been renamed to make their names unique.
     */
    private static void fixModuleControllerPaths(Map<TestElement, Object> targets, Map<Object, Object> replaced,
            Map<Class<? extends TestPlanSection>, String> sectionNames) {
        targets.forEach((controller, target) -> {
            Object moved = replaced.getOrDefault(target, target);
            CollectionProperty path = (CollectionProperty) controller.getProperty(MODULE_CONTROLLER_NODE_PATH);
            List<String> names = new ArrayList<>();
            for (JMeterProperty name : path) {
                names.add(name.getStringValue());
            }
            names.set(2, ((TestElement) moved).getName());
            Class<? extends TestPlanSection> section = sectionFor(moved);
            if (section != null) {
                names.add(2, sectionNames.get(section));
            }
            controller.setProperty(new CollectionProperty(MODULE_CONTROLLER_NODE_PATH, names));
        });
    }
}
