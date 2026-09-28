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

package org.apache.jmeter.threads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomClosedModelTest extends JMeterTestCase {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void profileKeepsUserAndIterationCounterWhileSameUserControlsVariables(boolean sameUser) throws Exception {
        TrackingSampler sampler = new TrackingSampler();
        ThreadGroup group = runProfile(sameUser,
                "threadsPhase(1, 0) threadsPhase(1, 2) threadsPhase(0, 0)", sampler);

        assertTrue(sampler.iterations.size() > 3, "The user must keep iterating within the profile");
        assertEquals(1, sampler.started.get(), "Iterations must not create replacement users");
        assertEquals(1, sampler.finished.get());
        assertEquals(1, sampler.iterations.stream().map(Iteration::threadName).distinct().count());
        for (int i = 0; i < sampler.iterations.size(); i++) {
            Iteration iteration = sampler.iterations.get(i);
            assertEquals(i + 1, iteration.number());
            assertEquals(sameUser && i > 0 ? "saved" : null, iteration.previousValue());
            assertEquals(sameUser, iteration.sameUser());
        }
        assertEquals(1, ((LoopController) group.getSamplerController()).getLoops(),
                "The saved standard profile settings must remain unchanged");
    }

    @Test
    void reducingThenIncreasingConcurrencyCreatesANewUserOnlyForTheNewPhase() throws Exception {
        TrackingSampler sampler = new TrackingSampler();
        runProfile(true, "threadsPhase(1, 0) threadsPhase(1, 1) "
                + "threadsPhase(0, 0) threadsPhase(0, 1) "
                + "threadsPhase(1, 0) threadsPhase(1, 1)", sampler);

        assertEquals(2, sampler.started.get());
        assertEquals(2, sampler.finished.get());
        assertEquals(2, sampler.iterations.stream().map(Iteration::threadName).distinct().count());
    }

    private ThreadGroup runProfile(boolean sameUser, String schedule, TrackingSampler sampler) throws Exception {
        ThreadGroup group = new ThreadGroup();
        group.setName("Closed profile");
        group.setClosedModelMode(ThreadGroup.CLOSED_MODEL_MODE_CUSTOM);
        group.setClosedModelSchedule(schedule);
        group.setIsSameUserOnNextIteration(sameUser);
        LoopController loop = new LoopController();
        loop.setLoops(1);
        group.setSamplerController(loop);
        ListedHashTree tree = new ListedHashTree();
        tree.add(new TestPlan()).add(group).add(sampler);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(tree);
        engine.runTest();
        try {
            engine.awaitTermination(Duration.ofSeconds(10));
            assertFalse(engine.isActive());
        } finally {
            if (engine.isActive()) {
                engine.stopTest(true);
                engine.awaitTermination(Duration.ofSeconds(10));
            }
        }
        return group;
    }

    private record Iteration(String threadName, int number, String previousValue, boolean sameUser) {
    }

    private static class TrackingSampler extends AbstractSampler implements NoThreadClone, ThreadListener {
        private final List<Iteration> iterations = new CopyOnWriteArrayList<>();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

        @Override
        public SampleResult sample(Entry entry) {
            JMeterContext context = JMeterContextService.getContext();
            JMeterVariables variables = context.getVariables();
            iterations.add(new Iteration(context.getThread().getThreadName(), variables.getIteration(),
                    variables.get("profileState"), variables.isSameUserOnNextIteration()));
            variables.put("profileState", "saved");
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new SampleResult();
        }

        @Override
        public void threadStarted() {
            started.incrementAndGet();
        }

        @Override
        public void threadFinished() {
            finished.incrementAndGet();
        }
    }
}
