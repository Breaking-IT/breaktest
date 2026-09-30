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

import java.time.Duration;
import java.util.List;
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
import org.apache.jmeter.scenario.ScenarioWorkload;
import org.apache.jmeter.scenario.ScenariosSection;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.PostThreadGroup;
import org.apache.jmeter.threads.SetupThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Settings the threads of a thread group get when they are made, taken from the thread group's profile. */
class ScenarioThreadSettingsTest extends JMeterTestCase {
    private static final Set<Boolean> SAME_USER = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger AFTER_FAILURE = new AtomicInteger();

    /** Fails when its {@code fail} property is set; otherwise counts that it ran. Records the same user flag. */
    public static class Probe extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        @Override
        public SampleResult sample(Entry entry) {
            SAME_USER.add(JMeterContextService.getContext().getVariables().isSameUserOnNextIteration());
            boolean fail = getPropertyAsBoolean("fail");
            if (!fail) {
                AFTER_FAILURE.incrementAndGet();
            }
            SampleResult result = new SampleResult();
            result.sampleStart();
            result.sampleEnd();
            result.setSuccessful(!fail);
            return result;
        }
    }

    /**
     * Runs a thread group whose first sampler fails, with a default profile defining {@code action=stopthread} and
     * {@code same=true}.
     * @return how many samplers ran after a failure
     */
    private static int run(String mode, String onError, String sameUser) throws Exception {
        AFTER_FAILURE.set(0);
        SAME_USER.clear();
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        AbstractThreadGroup group;
        if ("legacy".equals(mode)) {
            OpenModelThreadGroup legacy = new OpenModelThreadGroup();
            legacy.setScheduleString("rate(4/sec) even_arrivals(1 sec)");
            legacy.setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, onError);
            group = legacy;
        } else if ("setup-open".equals(mode)) {
            group = new SetupThreadGroup();
        } else if ("teardown-open".equals(mode)) {
            group = new PostThreadGroup();
        } else {
            group = new ThreadGroup();
        }
        group.setProperty(AbstractThreadGroup.IS_SAME_USER_ON_NEXT_ITERATION, sameUser);
        group.setName("Script");
        group.setThreadGroupId("script");
        HashTree script = plan.add(new ThreadGroupsSection()).add(group);
        Probe failure = new Probe();
        failure.setName("Failure");
        failure.setProperty("fail", true);
        script.add(failure);
        Probe after = new Probe();
        after.setName("After the failure");
        script.add(after);

        Profile profile = new Profile("P");
        profile.setDefault(true);
        Arguments variables = new Arguments();
        variables.addArgument("action", "stopthread");
        variables.addArgument("same", "true");
        plan.add(new ProfilesSection()).add(profile).add(variables);

        ScenarioWorkload workload = new ScenarioWorkload();
        workload.setThreadGroupId("script");
        workload.setThreads("1");
        workload.setRampUp("0");
        workload.setLoops("1");
        workload.setOnSampleError(onError);
        workload.setProperty(AbstractThreadGroup.IS_SAME_USER_ON_NEXT_ITERATION, sameUser);
        if (mode.endsWith("open")) {
            workload.setModel(ThreadGroup.MODEL_OPEN);
            workload.setOpenSchedule("rate(4/sec) even_arrivals(1 sec)");
        } else if ("delayed".equals(mode)) {
            workload.setDelayedStart(true);
        } else if ("custom".equals(mode)) {
            workload.setClosedSchedule("threadsPhase(1,0) threadsPhase(1,1)");
        }
        Scenario scenario = new Scenario("Run");
        scenario.setWorkloads(List.of(workload));
        plan.add(new ScenariosSection()).add(scenario);

        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(JMeter.convertSubTree(tree, false));
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(10));
        return AFTER_FAILURE.get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"standard", "delayed", "custom", "open", "setup-open", "teardown-open", "legacy"})
    void theErrorActionCanComeFromTheProfile(String mode) throws Exception {
        assertEquals(0, run(mode, "stopthread", "true"), "A literal action");
        assertEquals(0, run(mode, "${action}", "true"), "The profile's action");
    }

    @ParameterizedTest
    @ValueSource(strings = {"standard", "delayed", "custom", "open", "setup-open", "teardown-open", "legacy"})
    void theSameUserSettingCanComeFromTheProfile(String mode) throws Exception {
        run(mode, "continue", "true");
        assertEquals(Set.of(true), SAME_USER, "A literal setting");
        run(mode, "continue", "${same}");
        assertEquals(Set.of(true), SAME_USER, "The profile's setting");
    }
}
