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

package org.apache.jmeter.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.scenario.Profile;
import org.apache.jmeter.scenario.ProfilesSection;
import org.apache.jmeter.scenario.Scenario;
import org.apache.jmeter.scenario.ScenarioException;
import org.apache.jmeter.scenario.ScenarioPlanMigration;
import org.apache.jmeter.scenario.ScenarioResolver;
import org.apache.jmeter.scenario.ScenarioWorkload;
import org.apache.jmeter.scenario.ScenariosSection;
import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Functions in a test plan organised in scenarios. */
class ScenarioFunctionsTest extends JMeterTestCase {
    @TempDir
    Path dir;

    /** An old plan: a thread group defines {@code seed}, a test plan level variable after it uses it. */
    private static HashTree legacyPlan(String result) {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        ThreadGroup group = new ThreadGroup();
        group.setName("Workers");
        group.setNumThreads(1);
        LoopController loops = new LoopController();
        loops.setLoops(1);
        group.setSamplerController(loops);
        Arguments local = new Arguments();
        local.addArgument("seed", "2");
        plan.add(group).add(local);
        Arguments shared = new Arguments();
        shared.addArgument("result", result);
        plan.add(shared);
        return tree;
    }

    private static String compiledResult(HashTree tree) {
        ScenarioResolver.resolve(JMeter.convertSubTree(tree, false)).traverse(new PreCompiler());
        return JMeterContextService.getContext().getVariables().get("result");
    }

    @Test
    void sharedVariablesCanStillUseVariablesOfThreadGroups() {
        String result = "${__intSum(${seed},1)}";
        assertEquals("3", compiledResult(legacyPlan(result)), "The old plan");
        assertEquals("3", compiledResult(ScenarioPlanMigration.migrate(legacyPlan(result))));
    }

    /**
     * An old plan with {@code seed=1} in the test plan and {@code seed=2} in a thread group, and {@code result} using
     * {@code seed} before or after that thread group, optionally with {@code next} using {@code result}.
     */
    private static HashTree legacyPlan(boolean resultBeforeThreadGroup, boolean chained) {
        HashTree tree = new ListedHashTree();
        TestPlan testPlan = new TestPlan();
        Arguments planVariables = new Arguments();
        planVariables.addArgument("seed", "1");
        testPlan.setUserDefinedVariables(planVariables);
        HashTree plan = tree.add(testPlan);
        Arguments result = new Arguments();
        result.addArgument("result", "${__intSum(${seed},1)}");
        if (resultBeforeThreadGroup) {
            plan.add(result);
        }
        ThreadGroup group = new ThreadGroup();
        group.setName("Workers");
        Arguments local = new Arguments();
        local.addArgument("seed", "2");
        plan.add(group).add(local);
        if (!resultBeforeThreadGroup) {
            plan.add(result);
        }
        if (chained) {
            Arguments next = new Arguments();
            next.addArgument("next", "${__intSum(${result},1)}");
            plan.add(next);
        }
        return tree;
    }

    private static String compiled(HashTree tree, String name) {
        ScenarioResolver.resolve(JMeter.convertSubTree(tree, false)).traverse(new PreCompiler());
        return JMeterContextService.getContext().getVariables().get(name);
    }

    @Test
    void variablesBeforeAThreadGroupKeepUsingTheValuesBeforeIt() {
        assertEquals("2", compiled(legacyPlan(true, false), "result"), "The old plan");
        assertEquals("2", compiled(ScenarioPlanMigration.migrate(legacyPlan(true, false)), "result"));
    }

    @Test
    void variablesAfterAThreadGroupCanDependOnEachOther() {
        assertEquals("4", compiled(legacyPlan(false, true), "next"), "The old plan");
        assertEquals("4", compiled(ScenarioPlanMigration.migrate(legacyPlan(false, true)), "next"));
    }

    @Test
    void failedResolutionOfAChosenScenarioEndsTheFunctionsItStarted() throws Exception {
        Path file = dir.resolve("values.txt");
        Files.writeString(file, "first\nsecond\n");
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        ThreadGroup group = new ThreadGroup();
        group.setThreadGroupId("workers");
        plan.add(new ThreadGroupsSection()).add(group);
        ScenarioWorkload workload = new ScenarioWorkload();
        workload.setThreadGroupId("workers");
        workload.setProfile("Missing environment");
        Scenario scenario = new Scenario("Explicit run");
        scenario.setWorkloads(List.of(workload));
        plan.add(new ScenariosSection()).add(scenario);
        Arguments shared = new Arguments();
        shared.addArgument("value", "${__StringFromFile(" + file + ")}");
        plan.add(new ProfilesSection()).add(new SharedProfile()).add(shared);
        HashTree converted = JMeter.convertSubTree(tree, false);
        int registered = StandardJMeterEngine.registeredListenerCount();

        assertThrows(ScenarioException.class, () -> ScenarioResolver.resolve(converted, scenario));

        assertEquals(registered, StandardJMeterEngine.registeredListenerCount(),
                "The functions of a rejected scenario are ended rather than left for a later run");
    }

    /**
     * A thread group defines {@code seed=3}, then {@code users} from an expression; the test plan defines
     * {@code seed=2}. The workload uses {@code ${users}} for the number of threads. A default profile, optionally
     * present, defines {@code users=50} but lets the thread group's own variables win.
     */
    private static HashTree planWithUsersFromTheScript(boolean profile, String users) {
        HashTree tree = new ListedHashTree();
        TestPlan testPlan = new TestPlan();
        Arguments planVariables = new Arguments();
        planVariables.addArgument("seed", "2");
        testPlan.setUserDefinedVariables(planVariables);
        HashTree plan = tree.add(testPlan);
        ThreadGroup group = new ThreadGroup();
        group.setThreadGroupId("workers");
        HashTree script = plan.add(new ThreadGroupsSection()).add(group);
        Arguments seed = new Arguments();
        seed.addArgument("seed", "3");
        script.add(seed);
        Arguments userCount = new Arguments();
        userCount.addArgument("users", users);
        script.add(userCount);
        ScenarioWorkload workload = new ScenarioWorkload();
        workload.setThreadGroupId("workers");
        workload.setThreads("${users}");
        workload.setLoops("1");
        Scenario scenario = new Scenario("Load");
        scenario.setWorkloads(List.of(workload));
        plan.add(new ScenariosSection()).add(scenario);
        if (profile) {
            Profile acceptance = new Profile("Acceptance");
            acceptance.setDefault(true);
            acceptance.setOverridingThreadGroupVariables(false);
            Arguments defaults = new Arguments();
            defaults.addArgument("users", "50");
            plan.add(new ProfilesSection()).add(acceptance).add(defaults);
        }
        return tree;
    }

    /** Resolves and compiles the plan as the engine does, and returns the number of threads of its thread group */
    private static int threadsWhenStarted(boolean profile, String users) {
        HashTree run = ScenarioResolver.resolve(JMeter.convertSubTree(planWithUsersFromTheScript(profile, users), false));
        run.traverse(new PreCompiler());
        ThreadGroup group = (ThreadGroup) run.getTree(run.getArray()[0]).list().stream()
                .filter(ThreadGroup.class::isInstance).findFirst().orElseThrow();
        group.setRunningVersion(true);
        return group.getNumThreads();
    }

    @Test
    void aProfileThatDoesNotOverrideLeavesWorkloadsToTheThreadGroupsVariables() {
        String users = "${__intSum(${seed},1)}";
        assertEquals(4, threadsWhenStarted(false, users), "Without a profile");
        assertEquals(4, threadsWhenStarted(true, users), "The thread group's users, from its own seed");
    }

    @Test
    void aProfileThatDoesNotOverrideDoesNotEvaluateThreadGroupFunctionsAgain() {
        String key = "scenario.functions.test.calls";
        String users = "${__groovy(props.setProperty('" + key + "'\\,(Integer.parseInt(props.getProperty('" + key
                + "'))+1).toString()); 4)}";
        try {
            JMeterUtils.setProperty(key, "0");
            threadsWhenStarted(false, users);
            assertEquals("1", JMeterUtils.getProperty(key), "Without a profile");
            JMeterUtils.setProperty(key, "0");
            threadsWhenStarted(true, users);
            assertEquals("1", JMeterUtils.getProperty(key), "The profile adds no evaluation");
        } finally {
            JMeterUtils.getJMeterProperties().remove(key);
        }
    }

    @Test
    void functionsEvaluatedWhileResolvingTheScenarioAreEndedWithTheTest() throws Exception {
        Path input = dir.resolve("values.txt");
        Files.writeString(input, "first\nsecond\n");
        HashTree tree = ScenarioPlanMigration.migrate(legacyPlan("${__StringFromFile(" + input + ")}"));
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(JMeter.convertSubTree(tree, false));
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(10));

        var registrationsField = StandardJMeterEngine.class.getDeclaredField("testList");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<List<TestStateListener>> registrations =
                (ThreadLocal<List<TestStateListener>>) registrationsField.get(null);
        try {
            for (TestStateListener listener : registrations.get()) {
                if (listener instanceof StringFromFile) {
                    var readerField = StringFromFile.class.getDeclaredField("myBread");
                    readerField.setAccessible(true);
                    BufferedReader reader = (BufferedReader) readerField.get(listener);
                    assertThrows(IOException.class, reader::ready, "The file must be closed when the test ends");
                }
            }
        } finally {
            registrations.get().forEach(TestStateListener::testEnded);
            registrations.remove();
        }
    }
}
