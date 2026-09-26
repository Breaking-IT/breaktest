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
 *     <li>the content of the test fragments, which the Test Fragments section holds directly,</li>
 *     <li>the non-test elements such as the recorder, only when there are any.</li>
 * </ul>
 */
public final class ScenarioPlanMigration {

    private static final String SECTION_GUI_PACKAGE = "org.apache.jmeter.scenario.gui."; // $NON-NLS-1$

    private static final String MODULE_CONTROLLER_NODE_PATH = "ModuleController.node_path"; // $NON-NLS-1$

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
        if (!needsMigration(tree)) {
            return tree;
        }
        Object plan = tree.getArray()[0];
        HashTree planTree = tree.getTree(plan);

        Scenario scenario = new Scenario(JMeterUtils.getResString("scenario_title")); // $NON-NLS-1$
        setGuiClass(scenario, "ScenarioGui"); // $NON-NLS-1$

        Map<Class<? extends TestPlanSection>, HashTree> sectionTrees = new LinkedHashMap<>();
        for (Class<? extends TestPlanSection> sectionClass : List.of(ScenariosSection.class,
                ThreadGroupsSection.class, ListenersSection.class, ProfilesSection.class, TestFragmentsSection.class,
                NonTestElementsSection.class)) {
            sectionTrees.put(sectionClass, new ListedHashTree());
        }
        sectionTrees.get(ScenariosSection.class).add(scenario);
        HashTree otherTree = new ListedHashTree();
        List<ScenarioWorkload> workloads = new ArrayList<>();
        Map<String, PathFix> movedTargets = new HashMap<>();

        for (Object child : planTree.list()) {
            HashTree childTree = planTree.getTree(child);
            Object element = child;
            if (child instanceof AbstractThreadGroup original) {
                AbstractThreadGroup threadGroup = original instanceof OpenModelThreadGroup openModel
                        ? toThreadGroup(openModel)
                        : original;
                workloads.add(workloadOf(threadGroup));
                keepOnlyScript(threadGroup);
                element = threadGroup;
            }
            Class<? extends TestPlanSection> section = sectionFor(element);
            if (section == null) {
                otherTree.add(element, childTree);
            } else if (element instanceof TestFragmentController fragment
                    && unwrapFragment(childTree, sectionTrees.get(TestFragmentsSection.class))) {
                // Its content now sits directly in the Test Fragments section
                movedTargets.putIfAbsent(fragment.getName(), new PathFix(section, true));
            } else {
                sectionTrees.get(section).add(element, childTree);
                if (element instanceof TestElement testElement) {
                    movedTargets.putIfAbsent(testElement.getName(), new PathFix(section, false));
                }
            }
        }
        scenario.setWorkloads(workloads);

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
                newPlanTree.add(section).add(newSharedProfile(), entry.getValue());
            } else {
                newPlanTree.add(section, entry.getValue());
            }
        }
        newPlanTree.add(otherTree);
        fixModuleControllerPaths(result, movedTargets, sectionNames);
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
        workload.copyWorkloadFrom(threadGroup);
        // A disabled thread group stays in the plan; its workload is disabled so the scenario can still run
        workload.setEnabled(threadGroup.isEnabled());
        setGuiClass(workload, "ScenarioWorkloadGui"); // $NON-NLS-1$
        return workload;
    }

    private static void keepOnlyScript(AbstractThreadGroup threadGroup) {
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
     * The Test Fragments section holds reusable controllers directly, so a Test Fragment's content moves into it.
     * A fragment whose content has names already used in the section stays as it is, so every Module Controller
     * keeps finding its own target.
     * @return whether the fragment was unwrapped
     */
    private static boolean unwrapFragment(HashTree fragmentTree, HashTree fragmentsSectionTree) {
        Set<String> names = new HashSet<>();
        for (Object existing : fragmentsSectionTree.list()) {
            if (existing instanceof TestElement element) {
                names.add(element.getName());
            }
        }
        for (Object child : fragmentTree.list()) {
            if (!(child instanceof TestElement element) || !names.add(element.getName())) {
                return false;
            }
        }
        for (Object child : fragmentTree.list()) {
            fragmentsSectionTree.add(child, fragmentTree.getTree(child));
        }
        return true;
    }

    /** How the path of a Module Controller target changes: its section is inserted, or replaces an unwrapped fragment */
    private record PathFix(Class<? extends TestPlanSection> section, boolean replacesFragment) {
    }

    /**
     * Module Controllers find their target by the names on its tree path. Thread groups and test fragments now sit
     * one level deeper, in their section; the content of unwrapped fragments sits in the section itself.
     */
    private static void fixModuleControllerPaths(HashTree tree, Map<String, PathFix> moved,
            Map<Class<? extends TestPlanSection>, String> sectionNames) {
        for (Object element : tree.list()) {
            if (element instanceof TestElement testElement
                    && testElement.getProperty(MODULE_CONTROLLER_NODE_PATH) instanceof CollectionProperty path
                    && path.size() > 2 && moved.containsKey(path.get(2).getStringValue())) {
                List<String> names = new ArrayList<>();
                for (JMeterProperty name : path) {
                    names.add(name.getStringValue());
                }
                PathFix fix = moved.get(names.get(2));
                String sectionName = sectionNames.get(fix.section());
                if (fix.replacesFragment()) {
                    names.set(2, sectionName);
                } else {
                    names.add(2, sectionName);
                }
                testElement.setProperty(new CollectionProperty(MODULE_CONTROLLER_NODE_PATH, names));
            }
            fixModuleControllerPaths(tree.getTree(element), moved, sectionNames);
        }
    }
}
