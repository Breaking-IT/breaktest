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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.scenario.Profile;
import org.apache.jmeter.scenario.ProfilesSection;
import org.apache.jmeter.scenario.Scenario;
import org.apache.jmeter.scenario.ScenarioResolver;
import org.apache.jmeter.scenario.ScenarioWorkload;
import org.apache.jmeter.scenario.ScenariosSection;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.PostThreadGroup;
import org.apache.jmeter.threads.SetupThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Profiles of scenario rows along the ways the engine starts thread groups. */
class ScenarioStartPathsTest extends JMeterTestCase {
    private static final Map<String, Set<String>> THREADS = new ConcurrentHashMap<>();
    private static final Set<String> VALUES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger PEAK = new AtomicInteger();

    /** Records its thread, the {@code users} variable it sees and how many samplers run at the same time. */
    public static class Probe extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        @Override
        public SampleResult sample(Entry entry) {
            String group = getPropertyAsString("group");
            THREADS.computeIfAbsent(group, key -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread().getName());
            VALUES.add(group + "=" + JMeterContextService.getContext().getVariables().get("users"));
            PEAK.accumulateAndGet(ACTIVE.incrementAndGet(), Math::max);
            SampleResult result = new SampleResult();
            result.sampleStart();
            try {
                Thread.sleep(getPropertyAsLong("sleep"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ACTIVE.decrementAndGet();
            }
            result.sampleEnd();
            result.setSuccessful(true);
            return result;
        }
    }

    /**
     * Thread groups G0, G1 (and G2), each run with profile Pi, which sets {@code users} to i + 2, {@code cap} to 1 and
     * an open model schedule; the test plan sets {@code users} to 1.
     */
    private static HashTree plan(String mode, String cap, boolean consecutive, boolean setUpAndTearDown) {
        HashTree tree = new ListedHashTree();
        TestPlan testPlan = new TestPlan();
        Arguments planVariables = new Arguments();
        planVariables.addArgument("users", "1");
        testPlan.setUserDefinedVariables(planVariables);
        HashTree plan = tree.add(testPlan);
        HashTree groups = plan.add(new ThreadGroupsSection());
        HashTree profiles = plan.add(new ProfilesSection());
        List<ScenarioWorkload> workloads = new ArrayList<>();
        int count = setUpAndTearDown ? 3 : 2;
        for (int i = 0; i < count; i++) {
            String name = "G" + i;
            ThreadGroup group;
            if (setUpAndTearDown && i == 0) {
                group = new SetupThreadGroup();
            } else if (setUpAndTearDown && i == 2) {
                group = new PostThreadGroup();
            } else {
                group = new ThreadGroup();
            }
            group.setName(name);
            group.setThreadGroupId(name);
            Probe probe = new Probe();
            probe.setProperty("group", name);
            probe.setProperty("sleep", "open".equals(mode) ? 250 : "closed".equals(mode) ? 1100 : 0);
            groups.add(group).add(probe);

            Profile profile = new Profile("P" + i);
            profile.setDefault(i == 0);
            Arguments variables = new Arguments();
            variables.addArgument("users", Integer.toString(i + 2));
            variables.addArgument("cap", "1");
            variables.addArgument("schedule", "rate(40/sec) even_arrivals(1 sec)");
            profiles.add(profile).add(variables);

            ScenarioWorkload workload = new ScenarioWorkload();
            workload.setName(name);
            workload.setThreadGroupId(name);
            workload.setProfile("P" + i);
            workload.setThreads("${users}");
            workload.setLoops("1");
            workload.setRampUp("0");
            if ("delayed".equals(mode)) {
                workload.setDelayedStart(true);
                workload.setDelay("1");
            } else if ("closed".equals(mode)) {
                workload.setClosedSchedule("threadsPhase(${users},0) threadsPhase(${users},1)");
            } else if ("open".equals(mode)) {
                workload.setModel(ThreadGroup.MODEL_OPEN);
                workload.setOpenSchedule("${schedule}");
                workload.setOpenMaxThreads(cap);
            }
            workloads.add(workload);
        }
        Scenario scenario = new Scenario("Run");
        scenario.setRunConsecutively(consecutive);
        scenario.setWorkloads(workloads);
        plan.add(new ScenariosSection()).add(scenario);
        return tree;
    }

    private static void run(HashTree tree, boolean validation) throws Exception {
        THREADS.clear();
        VALUES.clear();
        ACTIVE.set(0);
        PEAK.set(0);
        HashTree converted = JMeter.convertSubTree(tree, false);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(validation ? ScenarioResolver.flattenForValidation(converted) : converted);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(15));
    }

    private static HashTree section(HashTree tree, Class<?> type) {
        HashTree plan = tree.getTree(tree.getArray()[0]);
        return plan.getTree(plan.list().stream().filter(type::isInstance).findFirst().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"standard", "delayed", "closed"})
    void eachThreadGroupStartsWithItsProfile(String mode) throws Exception {
        run(plan(mode, "1", false, false), false);
        assertEquals(2, THREADS.get("G0").size());
        assertEquals(3, THREADS.get("G1").size());
        assertEquals(Set.of("G0=2", "G1=3"), VALUES);
    }

    @Test
    void setUpAndTearDownThreadGroupsUseTheirProfiles() throws Exception {
        run(plan("delayed", "1", false, true), false);
        assertEquals(2, THREADS.get("G0").size());
        assertEquals(3, THREADS.get("G1").size());
        assertEquals(4, THREADS.get("G2").size());
        assertEquals(Set.of("G0=2", "G1=3", "G2=4"), VALUES);
    }

    @Test
    void consecutiveAndRepeatedRunsKeepProfilesApart() throws Exception {
        for (int i = 0; i < 2; i++) {
            run(plan("delayed", "1", true, false), false);
            assertEquals(2, THREADS.get("G0").size());
            assertEquals(3, THREADS.get("G1").size());
            assertEquals(Set.of("G0=2", "G1=3"), VALUES);
        }
    }

    @Test
    void validationRunsOneThreadWithTheDefaultProfile() throws Exception {
        run(plan("open", "${cap}", false, false), true);
        assertEquals(1, THREADS.get("G0").size());
        assertEquals(1, THREADS.get("G1").size());
        assertEquals(Set.of("G0=2", "G1=2"), VALUES);
    }

    @Test
    void openModelScheduleAndSamplersUseTheProfile() throws Exception {
        run(plan("open", "1", true, false), false);
        assertTrue(THREADS.get("G0").size() > 0);
        assertTrue(THREADS.get("G1").size() > 0);
        assertEquals(Set.of("G0=2", "G1=3"), VALUES);
        assertEquals(1, PEAK.get());
    }

    @Test
    void openModelMaximumFromTheProfileLimitsConcurrency() throws Exception {
        run(plan("open", "1", true, false), false);
        assertEquals(1, PEAK.get(), "A literal maximum");
        run(plan("open", "${cap}", true, false), false);
        assertEquals(1, PEAK.get(), "The profile's maximum, read before arrivals are admitted on another thread");
    }

    @Test
    void legacyOpenModelThreadGroupsUseTheProfileMaximum() throws Exception {
        HashTree tree = plan("open", "1", true, false);
        HashTree groups = section(tree, ThreadGroupsSection.class);
        for (Object element : new ArrayList<>(groups.list())) {
            ThreadGroup group = (ThreadGroup) element;
            OpenModelThreadGroup legacy = new OpenModelThreadGroup();
            legacy.setName(group.getName());
            legacy.setThreadGroupId(group.getThreadGroupId());
            legacy.setScheduleString("${schedule}");
            legacy.setMaxThreadsString("${cap}");
            HashTree script = groups.getTree(group);
            groups.remove(group);
            groups.add(legacy, script);
        }
        run(tree, false);
        assertEquals(Set.of("G0=2", "G1=3"), VALUES);
        assertEquals(1, PEAK.get());
    }

    @Test
    void aProfileDoesNotLeakIntoARowWithoutProfile() throws Exception {
        HashTree tree = plan("delayed", "1", true, false);
        Scenario scenario = (Scenario) section(tree, ScenariosSection.class).getArray()[0];
        List<ScenarioWorkload> workloads = scenario.getWorkloads();
        // An expression that evaluates to no profile
        workloads.get(1).setProfile("${__groovy('')}");
        scenario.setWorkloads(workloads);
        run(tree, false);
        assertEquals(2, THREADS.get("G0").size());
        assertEquals(1, THREADS.get("G1").size());
        assertEquals(Set.of("G0=2", "G1=1"), VALUES);
    }
}
