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
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

/** Workload settings using thread group and profile variables, as the engine starts the threads. */
class ScenarioWorkloadExpressionsTest extends JMeterTestCase {
    private static final Set<String> THREADS = ConcurrentHashMap.newKeySet();

    /** Records which threads sample. */
    public static class ThreadRecordingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        @Override
        public SampleResult sample(Entry entry) {
            THREADS.add(Thread.currentThread().getName());
            SampleResult result = new SampleResult();
            result.sampleStart();
            result.sampleEnd();
            result.setSuccessful(true);
            return result;
        }
    }

    /**
     * Runs a plan whose thread group defines {@code users=3} and whose test plan defines {@code extra=1}; the default
     * profile, when present, defines {@code extra=2} and lets the thread group's own variables win.
     * @return the number of threads that sampled
     */
    private static int threadsStarted(String threads, boolean profile) throws Exception {
        HashTree tree = new ListedHashTree();
        TestPlan testPlan = new TestPlan();
        Arguments planVariables = new Arguments();
        planVariables.addArgument("extra", "1");
        testPlan.setUserDefinedVariables(planVariables);
        HashTree plan = tree.add(testPlan);
        ThreadGroup group = new ThreadGroup();
        group.setName("Workers");
        group.setThreadGroupId("workers");
        HashTree script = plan.add(new ThreadGroupsSection()).add(group);
        Arguments users = new Arguments();
        users.addArgument("users", "3");
        script.add(users);
        script.add(new ThreadRecordingSampler());
        ScenarioWorkload workload = new ScenarioWorkload();
        workload.setThreadGroupId("workers");
        workload.setThreads(threads);
        workload.setLoops("1");
        workload.setRampUp("0");
        Scenario scenario = new Scenario("Load");
        scenario.setWorkloads(List.of(workload));
        plan.add(new ScenariosSection()).add(scenario);
        if (profile) {
            Profile acceptance = new Profile("Acceptance");
            acceptance.setDefault(true);
            acceptance.setOverridingThreadGroupVariables(false);
            Arguments extra = new Arguments();
            extra.addArgument("extra", "2");
            plan.add(new ProfilesSection()).add(acceptance).add(extra);
        }
        THREADS.clear();
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(JMeter.convertSubTree(tree, false));
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(10));
        return THREADS.size();
    }

    @Test
    void settingsCanCombineThreadGroupAndProfileVariables() throws Exception {
        assertEquals(4, threadsStarted("${__intSum(${users},${extra})}", false), "Without a profile");
        assertEquals(2, threadsStarted("${extra}", true), "The profile's extra");
        assertEquals(5, threadsStarted("${__intSum(${users},${extra})}", true), "3 users and the profile's extra 2");
    }

    @Test
    void functionsReadThreadGroupVariablesWhereTheEngineSetsThem() throws Exception {
        assertEquals(3, threadsStarted("${__V(users)}", false), "Without a profile");
        assertEquals(3, threadsStarted("${__V(users)}", true), "An unrelated profile changes nothing");
    }
}
