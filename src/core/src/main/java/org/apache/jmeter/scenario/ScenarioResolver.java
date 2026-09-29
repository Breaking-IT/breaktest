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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

/**
 * Turns a test plan organised in {@link TestPlanSection}s into the flat tree the engine runs:
 * the test plan with the shared configuration, listeners and one thread group per workload of the enabled
 * {@link Scenario} as direct children. Each thread group gets the {@link Profile} its workload names.
 * <p>
 * Test elements are scoped by their ancestors, so section contents must become children of the test plan
 * for them to apply to every thread group. The input must already be converted with
 * {@link org.apache.jmeter.JMeter#convertSubTree(HashTree, boolean)}: disabled elements removed and
 * tree nodes replaced by test elements. Plans without sections are returned unchanged.
 */
public final class ScenarioResolver {

    /** A reference to a variable, such as {@code ${host}}, also inside a function call */
    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\$\\{([^${}()]+)\\}"); // $NON-NLS-1$

    private ScenarioResolver() {
    }

    /**
     * @param tree a converted test tree
     * @return {@code true} when the test plan is organised in sections
     */
    public static boolean hasSections(HashTree tree) {
        Object root = root(tree);
        return root != null && tree.getTree(root).list().stream().anyMatch(TestPlanSection.class::isInstance);
    }

    /**
     * Builds the tree to run for the enabled scenario.
     * @param tree a converted test tree
     * @return the flat tree to run, or {@code tree} itself when it has no sections
     * @throws ScenarioException when no single scenario is enabled or a workload cannot be resolved
     */
    public static HashTree resolve(HashTree tree) {
        return resolve(tree, null);
    }

    /**
     * Builds the tree to run for the given scenario, whether it is enabled or not.
     * @param tree a converted test tree
     * @param scenario the scenario to run, or {@code null} for the enabled scenario
     * @return the flat tree to run, or {@code tree} itself when it has no sections
     * @throws ScenarioException when the scenario cannot be determined or a workload cannot be resolved
     */
    public static HashTree resolve(HashTree tree, Scenario scenario) {
        return resolve(tree, scenario, null);
    }

    /**
     * Builds the tree to run for the given scenario and default profile, as chosen on the command line.
     * @param tree a converted test tree
     * @param scenario the scenario to run, or {@code null} for the enabled scenario
     * @param defaultProfileName the profile that thread groups set to "Use default" run with, or {@code null} for
     *     the profile marked as default
     * @return the flat tree to run, or {@code tree} itself when it has no sections
     * @throws ScenarioException when the scenario or a profile cannot be determined, or a workload cannot be resolved
     */
    public static HashTree resolve(HashTree tree, Scenario scenario, String defaultProfileName) {
        return endingFunctionsOnFailure(() -> resolveSections(tree, scenario, defaultProfileName));
    }

    /**
     * Resolving evaluates functions, which may open files and register to be ended with the test. When resolving
     * fails no test starts, so they are ended here, whoever asked for the resolution.
     */
    private static HashTree endingFunctionsOnFailure(Supplier<HashTree> resolution) {
        int registered = StandardJMeterEngine.registeredListenerCount();
        try {
            return resolution.get();
        } catch (RuntimeException e) {
            StandardJMeterEngine.endListenersRegisteredSince(registered);
            throw e;
        }
    }

    private static HashTree resolveSections(HashTree tree, Scenario scenario, String defaultProfileName) {
        if (!hasSections(tree)) {
            return tree;
        }
        Flattener flattener = new Flattener(tree);
        if (scenario == null) {
            scenario = flattener.enabledScenario();
        }
        ProfileEntry defaultProfile = flattener.defaultProfile(defaultProfileName);
        Map<String, ThreadGroupEntry> threadGroups = flattener.threadGroupsById();
        Evaluator evaluator = flattener.evaluator();
        Set<String> usedNames = new HashSet<>();
        int workloads = 0;
        for (ScenarioWorkload workload : scenario.getWorkloads()) {
            if (!workload.isEnabled()) {
                continue;
            }
            ThreadGroupEntry entry = threadGroups.get(workload.getThreadGroupId());
            if (entry == null) {
                throw new ScenarioException("'" + workload.getName() + "' in scenario '"
                        + scenario.getName() + "' uses a thread group that does not exist or is disabled.");
            }
            AbstractThreadGroup instance = (AbstractThreadGroup) entry.threadGroup().clone();
            if (!ScenarioWorkload.usesOwnSettings(instance)) {
                workload.applyTo(instance);
            }
            String name = workload.getName().isBlank() ? entry.threadGroup().getName() : workload.getName();
            instance.setName(uniqueName(name, usedNames));
            // An empty profile means "Use default": the default profile of this run
            ProfileEntry profile = workload.getProfile().isBlank()
                    ? defaultProfile
                    : flattener.profileNamed(evaluator.evaluate(workload.getProfile()).trim(),
                            "'" + workload.getName() + "' in scenario '" + scenario.getName() + "'");
            flattener.addThreadGroup(entry.threadGroup().getThreadGroupId(), instance,
                    withProfile(instance, profile, deepClone(entry.subTree()), evaluator));
            workloads++;
        }
        if (workloads == 0) {
            throw new ScenarioException("Scenario '" + scenario.getName() + "' has no enabled thread groups.");
        }
        if (flattener.plan instanceof TestPlan testPlan) {
            testPlan.setSerialized(scenario.isRunConsecutively());
        }
        flattener.addThreadGroupsAndLaterSharedVariables();
        return flattener.result;
    }

    /**
     * Builds a tree that runs the thread groups directly, ignoring scenarios, with the default profile.
     * Used to validate thread groups: the caller is expected to override the workload of each thread group.
     * @param tree a converted test tree
     * @return the flat tree, or {@code tree} itself when it has no sections
     */
    public static HashTree flattenIgnoringScenarios(HashTree tree) {
        return endingFunctionsOnFailure(() -> flattenSectionsIgnoringScenarios(tree));
    }

    private static HashTree flattenSectionsIgnoringScenarios(HashTree tree) {
        if (!hasSections(tree)) {
            return tree;
        }
        Flattener flattener = new Flattener(tree);
        ProfileEntry defaultProfile = flattener.defaultProfile(null);
        Evaluator evaluator = flattener.evaluator();
        for (ThreadGroupEntry entry : flattener.threadGroups) {
            AbstractThreadGroup threadGroup = entry.threadGroup();
            ScenarioWorkload.ensureMainController(threadGroup);
            if (defaultProfile == null) {
                flattener.addThreadGroup(threadGroup.getThreadGroupId(), threadGroup, entry.subTree());
            } else {
                // A copy, so the profile variables do not end up in the edited test plan
                AbstractThreadGroup copy = (AbstractThreadGroup) threadGroup.clone();
                flattener.addThreadGroup(threadGroup.getThreadGroupId(), copy,
                        withProfile(copy, defaultProfile, entry.subTree(), evaluator));
            }
        }
        flattener.addThreadGroupsAndLaterSharedVariables();
        return flattener.result;
    }

    /**
     * Builds a tree that validates the thread groups of a sectioned plan: each runs once, with a single thread and
     * the default profile, whatever the scenarios say. Used by callers that do not use the validation tree cloner.
     * @param tree a converted test tree
     * @return the flat validation tree, or {@code tree} itself when it has no sections
     */
    public static HashTree flattenForValidation(HashTree tree) {
        if (!hasSections(tree)) {
            return tree;
        }
        HashTree flat = flattenIgnoringScenarios(tree);
        for (Object element : flat.getTree(root(flat)).list()) {
            if (element instanceof AbstractThreadGroup threadGroup && !ScenarioWorkload.usesOwnSettings(threadGroup)) {
                runOnce(threadGroup);
            }
        }
        return flat;
    }

    private static void runOnce(AbstractThreadGroup threadGroup) {
        ScenarioWorkload.removeWorkload(threadGroup);
        threadGroup.setProperty(AbstractThreadGroup.NUM_THREADS, 1);
        threadGroup.setProperty(ThreadGroup.RAMP_TIME, 0);
    }

    /**
     * Puts the profile configuration at the top of the thread group, so it applies to that thread group only.
     * User Defined Variables of the profile become variables of each thread of the thread group, as User Defined
     * Variables elements would otherwise apply to the whole test.
     */
    private static HashTree withProfile(AbstractThreadGroup threadGroup, ProfileEntry profile, HashTree script,
            Evaluator evaluator) {
        if (profile == null) {
            return script;
        }
        HashTree profileTree = deepClone(profile.subTree());
        Evaluator profileEvaluator = evaluator.copy();
        Map<String, String> variables = new LinkedHashMap<>();
        HashTree result = new ListedHashTree();
        for (Object element : profileTree.list()) {
            if (element.getClass() == Arguments.class) {
                ((Arguments) element).getArgumentsAsMap().forEach((name, value) -> {
                    String evaluated = profileEvaluator.evaluate(value);
                    profileEvaluator.put(name, evaluated);
                    variables.put(name, evaluated);
                });
            } else {
                result.add(element, profileTree.getTree(element));
            }
        }
        result.add(script);
        Set<String> scriptVariables = userDefinedVariableNames(script);
        if (!profile.profile().isOverridingThreadGroupVariables()) {
            // The thread group's own User Defined Variables win: threads start with their values
            variables.keySet().removeAll(scriptVariables);
        }
        if (!variables.isEmpty()) {
            threadGroup.setProfileVariables(variables);
            // The engine evaluates the thread group's own variables once, in order: settings that use them are
            // left to it, as they are without a profile
            Evaluator workloadEvaluator = evaluator.copy();
            variables.forEach(workloadEvaluator::put);
            Set<String> leftToEngine = new HashSet<>(scriptVariables);
            leftToEngine.removeAll(variables.keySet());
            evaluateWorkload(threadGroup, workloadEvaluator, leftToEngine);
        }
        return result;
    }

    /**
     * @param tree a part of a test plan
     * @return the names of the variables its User Defined Variables elements define
     */
    static Set<String> userDefinedVariableNames(HashTree tree) {
        return userDefinedVariables(tree).keySet();
    }

    /**
     * @param tree a part of a test plan
     * @return the variables its User Defined Variables elements define, in tree order, the last definition winning.
     *     Disabled elements and everything below them are left out, as they are when the test runs.
     */
    private static Map<String, String> userDefinedVariables(HashTree tree) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (Object element : tree.list()) {
            if (element instanceof TestElement testElement && !testElement.isEnabled()) {
                continue;
            }
            if (element.getClass() == Arguments.class) {
                variables.putAll(((Arguments) element).getArgumentsAsMap());
            }
            variables.putAll(userDefinedVariables(tree.getTree(element)));
        }
        return variables;
    }

    /**
     * Settings such as the number of threads are read before the threads get their profile variables, so
     * expressions in the workload are evaluated here with the profile variables.
     */
    private static void evaluateWorkload(AbstractThreadGroup threadGroup, Evaluator evaluator, Set<String> leftToEngine) {
        for (String name : ScenarioWorkload.WORKLOAD_PROPERTIES) {
            JMeterProperty property = threadGroup.getProperty(name);
            if (property instanceof TestElementProperty elementProperty) {
                evaluateStrings(elementProperty.getElement(), evaluator, leftToEngine);
            } else if (isExpressionFor(property, leftToEngine)) {
                threadGroup.setProperty(name, evaluator.evaluate(property.getStringValue()));
            }
        }
    }

    private static void evaluateStrings(TestElement element, Evaluator evaluator, Set<String> leftToEngine) {
        List<JMeterProperty> expressions = new ArrayList<>();
        PropertyIterator properties = element.propertyIterator();
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            if (isExpressionFor(property, leftToEngine)) {
                expressions.add(property);
            }
        }
        for (JMeterProperty property : expressions) {
            element.setProperty(property.getName(), evaluator.evaluate(property.getStringValue()));
        }
    }

    /**
     * @return whether the property is an expression to evaluate here: one that uses none of the variables left to
     *     the engine
     */
    private static boolean isExpressionFor(JMeterProperty property, Set<String> leftToEngine) {
        if (!(property instanceof StringProperty) || !property.getStringValue().contains("${")) { // $NON-NLS-1$
            return false;
        }
        Matcher references = VARIABLE_REFERENCE.matcher(property.getStringValue());
        while (references.find()) {
            if (leftToEngine.contains(references.group(1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds a scenario by name, whether it is enabled or not, in a test plan as it was loaded.
     * @param tree a loaded test tree, before disabled elements are removed
     * @param name the scenario name
     * @return the scenario
     * @throws ScenarioException when the plan has no scenario with that name
     */
    public static Scenario findScenario(HashTree tree, String name) {
        Object root = root(tree);
        List<Scenario> scenarios = new ArrayList<>();
        if (root != null) {
            HashTree planTree = tree.getTree(root);
            for (Object section : planTree.list()) {
                if (section instanceof ScenariosSection) {
                    for (Object scenario : planTree.getTree(section).list()) {
                        if (scenario instanceof Scenario s) {
                            scenarios.add(s);
                        }
                    }
                }
            }
        }
        return scenarios.stream()
                .filter(scenario -> scenario.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new ScenarioException("There is no scenario '" + name + "'."
                        + (scenarios.isEmpty() ? "" : " Scenarios: " + scenarios.stream() // $NON-NLS-1$
                                .map(scenario -> "'" + scenario.getName() + "'")
                                .collect(Collectors.joining(", ")) + '.')));
    }

    private static Object root(HashTree tree) {
        Object[] roots = tree.getArray();
        return roots.length == 0 ? null : roots[0];
    }

    private static HashTree deepClone(HashTree subTree) {
        TreeCloner cloner = new TreeCloner(false);
        subTree.traverse(cloner);
        return cloner.getClonedTree();
    }

    private static String uniqueName(String name, Set<String> usedNames) {
        String candidate = name;
        for (int i = 2; !usedNames.add(candidate); i++) {
            candidate = name + " (" + i + ")";
        }
        return candidate;
    }

    private record ThreadGroupEntry(AbstractThreadGroup threadGroup, HashTree subTree) {
    }

    private record ThreadGroupRun(String threadGroupId, AbstractThreadGroup threadGroup, HashTree script) {
    }

    private record ProfileEntry(Profile profile, HashTree subTree) {
    }

    /**
     * Evaluates profile names and profile variables at test start, with the functions (such as {@code __P})
     * and the variables of the test plan and of the shared profile.
     */
    private static final class Evaluator {
        private final JMeterVariables variables = new JMeterVariables();

        String evaluate(String expression) {
            if (!expression.contains("${")) { // $NON-NLS-1$
                return expression;
            }
            JMeterContext context = JMeterContextService.getContext();
            JMeterVariables previous = context.getVariables();
            context.setVariables(variables);
            try {
                return new CompoundVariable(expression).execute();
            } finally {
                context.setVariables(previous);
            }
        }

        void put(String name, String value) {
            variables.put(name, value);
        }

        Evaluator copy() {
            Evaluator copy = new Evaluator();
            copy.variables.putAll(variables);
            return copy;
        }
    }

    /** Copies everything except scenarios and thread groups to the test plan level of a new tree. */
    private static final class Flattener {
        private final HashTree result = new ListedHashTree();
        private final HashTree planTree;
        private final List<Scenario> scenarios = new ArrayList<>();
        private final List<ThreadGroupEntry> threadGroups = new ArrayList<>();
        private final List<ProfileEntry> profiles = new ArrayList<>();
        private final Evaluator evaluator = new Evaluator();
        private final List<Object> overridingSharedVariables = new ArrayList<>();

        /** Shared variables of an old plan that came after a thread group, by the id of that thread group */
        private final List<Arguments> anchoredSharedVariables = new ArrayList<>();
        /** The thread groups of this run, in run order */
        private final List<ThreadGroupRun> runs = new ArrayList<>();

        void addThreadGroup(String threadGroupId, AbstractThreadGroup threadGroup, HashTree script) {
            runs.add(new ThreadGroupRun(threadGroupId, threadGroup, script));
        }

        /**
         * Adds the thread groups of the run. Shared variables that came after thread groups in an old plan go after
         * the last run of the nearest of those thread groups that runs, else before all thread groups. The engine
         * evaluates them there, in the same order as the old plan. The shared variables that override thread group
         * variables go last.
         */
        void addThreadGroupsAndLaterSharedVariables() {
            Map<Integer, List<Arguments>> afterRun = new HashMap<>();
            for (Arguments variables : anchoredSharedVariables) {
                int run = lastRunOf(SharedProfile.threadGroupsBefore(variables));
                afterRun.computeIfAbsent(run, key -> new ArrayList<>()).add(variables);
            }
            afterRun.getOrDefault(-1, List.of()).forEach(planTree::add);
            for (int i = 0; i < runs.size(); i++) {
                ThreadGroupRun run = runs.get(i);
                planTree.add(run.threadGroup(), run.script());
                afterRun.getOrDefault(i, List.of()).forEach(planTree::add);
            }
            overridingSharedVariables.forEach(planTree::add);
        }

        /**
         * @param threadGroupIds thread groups, in the order of an old plan; disabled ones are not part of the run
         * @return the index of the last run of the last of those thread groups that runs, or -1
         */
        private int lastRunOf(List<String> threadGroupIds) {
            for (int position = threadGroupIds.size() - 1; position >= 0; position--) {
                String candidate = threadGroupIds.get(position);
                for (int run = runs.size() - 1; run >= 0; run--) {
                    if (runs.get(run).threadGroupId().equals(candidate)) {
                        return run;
                    }
                }
            }
            return -1;
        }
        /** The test plan of the run tree: a copy, so run settings can be changed without touching the edited plan */
        private final Object plan;

        Flattener(HashTree tree) {
            Object root = root(tree);
            plan = evaluateVariables(root);
            result.add(plan);
            planTree = result.getTree(plan);
            HashTree sourcePlanTree = tree.getTree(root);
            for (Object child : sourcePlanTree.list()) {
                HashTree childTree = sourcePlanTree.getTree(child);
                if (child instanceof ScenariosSection) {
                    for (Object scenario : childTree.list()) {
                        if (scenario instanceof Scenario s) {
                            scenarios.add(s);
                        }
                    }
                } else if (child instanceof ThreadGroupsSection) {
                    for (Object element : childTree.list()) {
                        if (element instanceof AbstractThreadGroup threadGroup) {
                            threadGroups.add(new ThreadGroupEntry(threadGroup, childTree.getTree(threadGroup)));
                        } else {
                            planTree.add(element, childTree.getTree(element));
                        }
                    }
                } else if (child instanceof ProfilesSection) {
                    addProfiles(childTree);
                } else if (child instanceof TestPlanSection) {
                    // Fragments never run by themselves: Module Controllers copied what they use before this step
                    if (!(child instanceof TestFragmentsSection)) {
                        for (Object element : childTree.list()) {
                            planTree.add(element, childTree.getTree(element));
                        }
                    }
                } else {
                    planTree.add(child, childTree);
                }
            }
        }

        /**
         * Profile names may use shared variables that the engine evaluates later. Values without function calls can
         * safely be evaluated here too; functions could have side effects, such as reading the next line of a file,
         * so those are only evaluated once, by the engine.
         */
        private void learnValuesWithoutFunctions(Arguments variables) {
            variables.getArgumentsAsMap().forEach((name, value) -> {
                if (!value.contains("${__")) { // $NON-NLS-1$
                    evaluator.put(name, evaluator.evaluate(value));
                }
            });
        }

        private void addProfiles(HashTree profilesTree) {
            for (Object element : profilesTree.list()) {
                HashTree elementTree = profilesTree.getTree(element);
                if (element instanceof SharedProfile sharedProfile) {
                    // The shared configuration applies to every thread group
                    for (Object shared : elementTree.list()) {
                        if (shared.getClass() == Arguments.class
                                && !SharedProfile.threadGroupsBefore((Arguments) shared).isEmpty()) {
                            // Evaluated by the engine where they are placed, as they may use thread group variables:
                            // between the thread groups as in the old plan, or after all of them when the shared
                            // variables override those of thread groups
                            if (sharedProfile.isOverridingThreadGroupVariables()) {
                                overridingSharedVariables.add(shared);
                            } else {
                                anchoredSharedVariables.add((Arguments) shared);
                            }
                            learnValuesWithoutFunctions((Arguments) shared);
                            continue;
                        }
                        Object evaluated = evaluateVariables(shared);
                        if (evaluated instanceof Arguments && sharedProfile.isOverridingThreadGroupVariables()) {
                            // User Defined Variables apply in tree order, the last one winning: these go after
                            // the thread groups so their values win
                            overridingSharedVariables.add(evaluated);
                        } else {
                            planTree.add(evaluated, elementTree.getTree(shared));
                        }
                    }
                } else if (element instanceof Profile profile) {
                    profiles.add(new ProfileEntry(profile, elementTree));
                }
            }
        }

        /**
         * Evaluates the variables of the test plan and of shared User Defined Variables once, here, so profile
         * names and profile variables can use them. The copy in the run tree holds the results, so functions such
         * as {@code __UUID} are not evaluated again, with a different result, when the engine starts the test.
         * @param element the test plan or a shared element
         * @return the element to put in the run tree
         */
        private Object evaluateVariables(Object element) {
            if (element instanceof TestPlan testPlan) {
                Map<String, String> variables = testPlan.getUserDefinedVariables();
                TestPlan copy = (TestPlan) testPlan.clone();
                if (!variables.isEmpty()) {
                    copy.setUserDefinedVariables(evaluated(variables));
                }
                return copy;
            }
            if (element != null && element.getClass() == Arguments.class) {
                Arguments copy = (Arguments) ((Arguments) element).clone();
                Arguments values = evaluated(copy.getArgumentsAsMap());
                copy.removeAllArguments();
                values.getArgumentsAsMap().forEach(copy::addArgument);
                return copy;
            }
            return element;
        }

        private Arguments evaluated(Map<String, String> variables) {
            Arguments arguments = new Arguments();
            variables.forEach((name, value) -> {
                String result = evaluator.evaluate(value);
                evaluator.put(name, result);
                arguments.addArgument(name, result);
            });
            return arguments;
        }

        Evaluator evaluator() {
            return evaluator;
        }

        /**
         * There is always one default profile: the one marked as default, else the first one.
         * @param overrideName the default profile chosen for this run, or {@code null}
         * @return the default profile, or {@code null} when the plan has no profiles
         */
        ProfileEntry defaultProfile(String overrideName) {
            if (overrideName != null && !overrideName.isBlank()) {
                return profileNamed(overrideName.trim(), "The run");
            }
            return profiles.stream()
                    .filter(profile -> profile.profile().isDefault())
                    .findFirst()
                    .orElse(profiles.isEmpty() ? null : profiles.get(0));
        }

        /**
         * @param name the evaluated profile name; empty for no profile
         * @param usedBy what uses the profile, for error messages
         */
        ProfileEntry profileNamed(String name, String usedBy) {
            if (name.isEmpty()) {
                return null;
            }
            for (ProfileEntry profile : profiles) {
                if (profile.profile().getName().equals(name)) {
                    return profile;
                }
            }
            throw new ScenarioException(usedBy + " uses profile '" + name + "', which does not exist or is disabled."
                    + (profiles.isEmpty() ? "" : " Profiles: " + profiles.stream() // $NON-NLS-1$
                            .map(profile -> "'" + profile.profile().getName() + "'")
                            .collect(Collectors.joining(", ")) + '.'));
        }

        Scenario enabledScenario() {
            if (scenarios.isEmpty()) {
                throw new ScenarioException("No scenario is enabled. Enable the scenario you want to run.");
            }
            if (scenarios.size() > 1) {
                throw new ScenarioException("Only one scenario can be enabled, but these are: "
                        + scenarios.stream().map(s -> "'" + s.getName() + "'")
                                .collect(Collectors.joining(", ")) + '.');
            }
            return scenarios.get(0);
        }

        Map<String, ThreadGroupEntry> threadGroupsById() {
            Map<String, ThreadGroupEntry> byId = new HashMap<>();
            for (ThreadGroupEntry entry : threadGroups) {
                String id = entry.threadGroup().getThreadGroupId();
                if (id.isEmpty()) {
                    continue;
                }
                ThreadGroupEntry previous = byId.putIfAbsent(id, entry);
                if (previous != null) {
                    throw new ScenarioException("Thread groups '" + previous.threadGroup().getName() + "' and '"
                            + entry.threadGroup().getName() + "' have the same id. Recreate one of them.");
                }
            }
            return byId;
        }
    }
}
