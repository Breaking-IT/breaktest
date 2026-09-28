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

package org.apache.jmeter.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class StandardJMeterEngineTest extends JMeterTestCase {
    @Test
    void failedStartupEndsOnlyListenersWhoseStartWasInvokedAndAllowsAnotherRun() {
        StandardJMeterEngine engine = new StandardJMeterEngine();
        FailingListener listener = new FailingListener();
        ListedHashTree tree = new ListedHashTree();
        FailingListener before = new FailingListener();
        before.fail = false;
        FailingListener after = new FailingListener();
        after.fail = false;
        var children = tree.add(new TestPlan());
        children.add(before);
        children.add(listener);
        children.add(after);
        engine.configure(tree);

        assertThrows(IllegalStateException.class, engine::run);
        assertFalse(engine.isActive());
        assertEquals(1, before.started);
        assertEquals(1, before.ended);
        assertEquals(1, listener.started);
        assertEquals(1, listener.ended);
        assertEquals(0, after.started);
        assertEquals(0, after.ended);

        listener.fail = false;
        engine.configure(tree);
        engine.run();
        assertFalse(engine.isActive());
        assertEquals(2, listener.ended);
        assertEquals(2, before.ended);
        assertEquals(1, after.started);
        assertEquals(1, after.ended);
    }

    @Test
    void compileFailureDoesNotEndUnstartedListenersOrLeakRegistrations() {
        StandardJMeterEngine engine = new StandardJMeterEngine();
        FailingListener listener = new FailingListener();
        listener.fail = false;
        FailingListener registered = new FailingListener();
        registered.fail = false;
        TestPlan broken = new TestPlan() {
            @Override
            public void prepareForPreCompile() {
                StandardJMeterEngine.register(registered);
                throw new IllegalStateException("Simulated compile failure");
            }
        };
        ListedHashTree tree = new ListedHashTree();
        tree.add(broken).add(listener);
        engine.configure(tree);
        engine.run();
        assertFalse(engine.isActive());
        assertEquals(0, listener.started);
        assertEquals(0, listener.ended);
        assertEquals(0, registered.ended);

        ListedHashTree valid = new ListedHashTree();
        valid.add(new TestPlan()).add(listener);
        engine.configure(valid);
        engine.run();
        assertEquals(1, listener.started);
        assertEquals(1, listener.ended);
        assertEquals(0, registered.started);
        assertEquals(0, registered.ended);
    }

    @Test
    void explicitlyRegisteredCleanupRunsEvenBeforeStartupAndIsNotDuplicated() {
        StandardJMeterEngine engine = new StandardJMeterEngine();
        FailingListener cleanup = new FailingListener();
        cleanup.fail = false;
        engine.addAlwaysEndListener(cleanup);
        TestPlan broken = new TestPlan() {
            @Override
            public void prepareForPreCompile() {
                throw new IllegalStateException("Compile failure");
            }
        };
        ListedHashTree tree = new ListedHashTree();
        tree.add(broken).add(cleanup);
        engine.configure(tree);
        engine.run();
        assertEquals(0, cleanup.started);
        assertEquals(1, cleanup.ended);

        ListedHashTree valid = new ListedHashTree();
        valid.add(new TestPlan()).add(cleanup);
        engine.configure(valid);
        engine.run();
        assertEquals(1, cleanup.started);
        assertEquals(2, cleanup.ended);
    }

    @Test
    void registrationOnAnotherThreadSurvivesFailedCompilation() throws Exception {
        FailingListener registered = new FailingListener();
        registered.fail = false;
        StandardJMeterEngine.register(registered);
        FutureTask<Void> compilation = new FutureTask<>(() -> {
            compileFailureDoesNotEndUnstartedListenersOrLeakRegistrations();
            return null;
        });
        new Thread(compilation).start();
        compilation.get(5, TimeUnit.SECONDS);

        StandardJMeterEngine engine = new StandardJMeterEngine();
        ListedHashTree tree = new ListedHashTree();
        tree.add(new TestPlan());
        engine.configure(tree);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(5));
        assertEquals(1, registered.started);
        assertEquals(1, registered.ended);
    }

    @Test
    void scheduledEndNotifiesStoppingObservers() throws Exception {
        StandardJMeterEngine engine = new StandardJMeterEngine();
        List<Boolean> stopping = new CopyOnWriteArrayList<>();
        engine.addStoppingListener(stopping::add);
        ThreadGroup group = new ThreadGroup();
        group.setNumThreads(1);
        group.setScheduler(true);
        group.setDuration(1);
        LoopController loop = new LoopController();
        loop.setLoops(-1);
        group.setSamplerController(loop);
        TransactionController transaction = new TransactionController();
        transaction.setDelayMode(TransactionController.DELAY_FIXED);
        transaction.setFixedDelay("60000");
        ListedHashTree tree = new ListedHashTree();
        tree.add(new TestPlan()).add(group).add(transaction);
        engine.configure(tree);
        engine.runTest();
        try {
            engine.awaitTermination(Duration.ofSeconds(5));
            assertEquals(List.of(false), stopping);
        } finally {
            if (engine.isActive()) {
                engine.stopTest(true);
                engine.awaitTermination(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void gracefulStopCanEscalateWhileRequestIsBlocked() throws Exception {
        StandardJMeterEngine engine = new StandardJMeterEngine();
        BlockingSampler sampler = new BlockingSampler();
        List<Boolean> stopping = new CopyOnWriteArrayList<>();
        engine.addStoppingListener(stopping::add);
        ThreadGroup group = new ThreadGroup();
        group.setNumThreads(1);
        LoopController loop = new LoopController();
        loop.setLoops(-1);
        group.setSamplerController(loop);
        ListedHashTree tree = new ListedHashTree();
        tree.add(new TestPlan()).add(group).add(sampler);
        engine.configure(tree);
        engine.runTest();
        try {
            assertTrue(sampler.started.await(10, TimeUnit.SECONDS));
            engine.stopTest(false);
            assertEquals(List.of(false), stopping);
            assertFalse(sampler.released.await(200, TimeUnit.MILLISECONDS),
                    "Graceful stop must let the active sampler finish");
            assertTrue(engine.isActive(), "A blocked worker must still prevent a new run");
            engine.stopTest(true);
            assertEquals(List.of(false, true), stopping);
            assertTrue(sampler.released.await(10, TimeUnit.SECONDS));
            engine.awaitTermination(Duration.ofSeconds(10));
            assertFalse(engine.isActive());
        } finally {
            sampler.released.countDown();
            engine.stopTest(true);
            engine.awaitTermination(Duration.ofSeconds(10));
        }
    }

    private static class BlockingSampler extends AbstractSampler implements Interruptible {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        // This fixture has one worker; retain the latches observed by the test.
        @Override
        public Object clone() {
            return this;
        }

        @Override
        public Object lightweightClone() {
            return this;
        }

        @Override
        public SampleResult sample(Entry entry) {
            started.countDown();
            try {
                released.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new SampleResult();
        }

        @Override
        public boolean interrupt() {
            released.countDown();
            return true;
        }
    }

    private static class FailingListener extends AbstractTestElement implements TestStateListener {
        private boolean fail = true;
        private int started;
        private int ended;

        @Override
        public void testStarted() {
            started++;
            if (fail) {
                throw new IllegalStateException("Simulated startup failure");
            }
        }

        @Override
        public void testEnded() {
            ended++;
        }

        @Override
        public void testStarted(String host) {
            testStarted();
        }

        @Override
        public void testEnded(String host) {
            testEnded();
        }
    }
}
