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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ThreadGroupShutdownTest extends JMeterTestCase {
    @ParameterizedTest
    @CsvSource({"eager,false", "eager,true", "delayed,false", "delayed,true", "custom,false", "custom,true"})
    @Timeout(10)
    void workerBeingCreatedCannotMissShutdown(String mode, boolean immediate) throws Exception {
        BlockingThreadGroup group = new BlockingThreadGroup();
        group.setName("shutdown-race");
        group.setNumThreads(1);
        group.setRampUp(0);
        group.set(ThreadGroupSchema.INSTANCE.getDelayedStart(), "delayed".equals(mode));
        if ("custom".equals(mode)) {
            group.setClosedModelMode(ThreadGroup.CLOSED_MODEL_MODE_CUSTOM);
            group.setClosedModelSchedule("threadsPhase(1, 0) threadsPhase(1, 60)");
        }
        LoopController loop = new LoopController();
        loop.setLoops(1);
        group.setSamplerController(loop);
        TrackingSampler sampler = new TrackingSampler();
        ListedHashTree tree = new ListedHashTree();
        tree.add(group).add(sampler);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var start = executor.submit(() -> {
                JMeterContextService.getContext().setVariables(new JMeterVariables());
                group.start(1, new ListenerNotifier(), tree, new StandardJMeterEngine());
            });
            try {
                assertTrue(group.constructing.await(5, TimeUnit.SECONDS), "Worker must reach construction");
                if (immediate) {
                    group.tellThreadsToStop();
                } else {
                    group.stop();
                }
                group.releaseConstruction.countDown();
                start.get(5, TimeUnit.SECONDS);
                group.waitThreadsStopped();
                assertEquals(0, sampler.started.get(), "Shutdown must refuse workers still being constructed");
                assertEquals(0, group.numberOfActiveThreads());
            } finally {
                group.tellThreadsToStop();
                group.releaseConstruction.countDown();
            }
        }
    }

    public static class BlockingThreadGroup extends ThreadGroup {
        private final CountDownLatch constructing = new CountDownLatch(1);
        private final CountDownLatch releaseConstruction = new CountDownLatch(1);

        @Override
        protected JMeterThread makeThread(StandardJMeterEngine engine, JMeterThreadMonitor monitor,
                ListenerNotifier notifier, int groupNumber, int threadNumber,
                ListedHashTree tree, JMeterVariables variables) {
            JMeterThread worker = super.makeThread(engine, monitor, notifier,
                    groupNumber, threadNumber, tree, variables);
            constructing.countDown();
            // Model construction that completes despite the starter's shutdown interrupt.
            boolean interrupted = false;
            while (true) {
                try {
                    if (!releaseConstruction.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Construction was not released");
                    }
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return worker;
        }
    }

    private static class TrackingSampler extends AbstractSampler implements NoThreadClone, ThreadListener {
        private final AtomicInteger started = new AtomicInteger();

        @Override
        public SampleResult sample(Entry entry) {
            getThreadContext().getThread().stop();
            return new SampleResult();
        }

        @Override
        public void threadStarted() {
            started.incrementAndGet();
        }

        @Override
        public void threadFinished() {
        }
    }
}
