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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.TransactionSampler;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.timers.Timer;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;

class TestJMeterThread {

    private static final class DummySampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;
        private boolean called = false;

        public boolean isCalled() {
            return called;
        }

        @Override
        public SampleResult sample(Entry e) {
            called = true;
            return null;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + (called ? 1231 : 1237);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!super.equals(obj)) {
                return false;
            }
            if (!getClass().equals(obj.getClass())) {
                return false;
            }
            DummySampler other = (DummySampler) obj;
            return called == other.called;
        }

    }

    private static class DummyTimer extends AbstractTestElement implements Timer {
        private static final long serialVersionUID = 5641410390783919241L;
        private long delay;

        void setDelay(long delay) {
            this.delay = delay;
        }

        @Override
        public long delay() {
            return delay;
        }
    }

    private static final class TrackingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger activeSamplers;
        private final AtomicInteger maxActiveSamplers;
        private final CountDownLatch completedSamplers;
        private final AtomicReference<JMeterVariables> observedVariables;
        private final AtomicBoolean sameVariables;

        private TrackingSampler(String name,
                AtomicInteger activeSamplers,
                AtomicInteger maxActiveSamplers,
                CountDownLatch completedSamplers,
                AtomicReference<JMeterVariables> observedVariables,
                AtomicBoolean sameVariables) {
            setName(name);
            this.activeSamplers = activeSamplers;
            this.maxActiveSamplers = maxActiveSamplers;
            this.completedSamplers = completedSamplers;
            this.observedVariables = observedVariables;
            this.sameVariables = sameVariables;
        }

        @Override
        public SampleResult sample(Entry e) {
            int active = activeSamplers.incrementAndGet();
            maxActiveSamplers.accumulateAndGet(active, Math::max);
            JMeterVariables variables = JMeterContextService.getContext().getVariables();
            if (!observedVariables.compareAndSet(null, variables) && observedVariables.get() != variables) {
                sameVariables.set(false);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                activeSamplers.decrementAndGet();
                completedSamplers.countDown();
            }
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static final class FailingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private FailingSampler(String name) {
            setName(name);
        }

        @Override
        public SampleResult sample(Entry e) {
            throw new IllegalStateException("Expected test failure");
        }
    }

    private static final class CompletingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch completedSamplers;

        private CompletingSampler(String name, CountDownLatch completedSamplers) {
            setName(name);
            this.completedSamplers = completedSamplers;
        }

        @Override
        public SampleResult sample(Entry e) {
            completedSamplers.countDown();
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static final class BlockingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicReference<JMeterVariables> observedVariables;

        private BlockingSampler(String name, CountDownLatch started, CountDownLatch release,
                AtomicReference<JMeterVariables> observedVariables) {
            setName(name);
            this.started = started;
            this.release = release;
            this.observedVariables = observedVariables;
        }

        @Override
        public SampleResult sample(Entry e) {
            observedVariables.compareAndSet(null, JMeterContextService.getContext().getVariables());
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static final class VariableRecordingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch completedSamplers;
        private final AtomicReference<JMeterVariables> observedVariables;
        private final AtomicBoolean sameVariables;

        private VariableRecordingSampler(String name, CountDownLatch completedSamplers,
                AtomicReference<JMeterVariables> observedVariables, AtomicBoolean sameVariables) {
            setName(name);
            this.completedSamplers = completedSamplers;
            this.observedVariables = observedVariables;
            this.sameVariables = sameVariables;
        }

        @Override
        public SampleResult sample(Entry e) {
            JMeterVariables variables = JMeterContextService.getContext().getVariables();
            if (!observedVariables.compareAndSet(null, variables) && observedVariables.get() != variables) {
                sameVariables.set(false);
            }
            completedSamplers.countDown();
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static class ThrowingThreadListener implements ThreadListener {
        private boolean throwError;

        public ThrowingThreadListener(boolean throwError) {
            this.throwError = throwError;
        }

        @Override
        public void threadStarted() {
            if (throwError) {
                throw new NoClassDefFoundError("Throw for Bug TestJMeterThread");
            } else {
                throw new RuntimeException("Throw for Bug TestJMeterThread");
            }
        }

        @Override
        public void threadFinished() {
            if (throwError) {
                throw new NoClassDefFoundError("Throw for Bug TestJMeterThread");
            } else {
                throw new RuntimeException("Throw for Bug TestJMeterThread");
            }
        }
    }

    @Test
    void testBug61661OnError() {
        HashTree hashTree = new HashTree();
        hashTree.add("Test", new ThrowingThreadListener(true));
        JMeterThread.ThreadListenerTraverser traverser =
                new JMeterThread.ThreadListenerTraverser(true);
        assertThrows(
                NoClassDefFoundError.class,
                () -> hashTree.traverse(traverser));
    }

    @Test
    void testBug61661OnException() {
        HashTree hashTree = new HashTree();
        hashTree.add("Test", new ThrowingThreadListener(false));
        JMeterThread.ThreadListenerTraverser traverser =
                new JMeterThread.ThreadListenerTraverser(true);
        hashTree.traverse(traverser);
    }

    @Test
    void testBug63490EndTestWhenDelayIsTooLongForScheduler() {
        JMeterContextService.getContext().setVariables(new JMeterVariables());

        HashTree testTree = new HashTree();
        LoopController samplerController = createLoopController();
        testTree.add(samplerController);
        testTree.add(samplerController, createConstantTimer(3000));
        DummySampler dummySampler = createSampler();
        testTree.add(samplerController, dummySampler);

        TestCompiler compiler = new TestCompiler(testTree);
        testTree.traverse(compiler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(1);
        long maxDuration = 2000L;
        threadGroup.setDuration(maxDuration);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, null);
        jMeterThread.setScheduled(true);
        jMeterThread.setEndTime(System.currentTimeMillis() + maxDuration);
        jMeterThread.setThreadGroup(threadGroup);
        Instant startTime = Instant.now();
        jMeterThread.run();
        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();

        assertFalse(dummySampler.isCalled(), "Sampler should not be called");

        // the duration of this test plan should currently be around zero seconds,
        // but it is allowed to take up to maxDuration amount of time
        assertTrue(duration <= maxDuration, "Test plan should not run for longer than duration");
    }

    @Test
    void testParallelControllerHonorsMaxParallelAndSharesVariables() throws InterruptedException {
        AtomicInteger activeSamplers = new AtomicInteger();
        AtomicInteger maxActiveSamplers = new AtomicInteger();
        CountDownLatch completedSamplers = new CountDownLatch(3);
        AtomicReference<JMeterVariables> observedVariables = new AtomicReference<>();
        AtomicBoolean sameVariables = new AtomicBoolean(true);

        HashTree testTree = new HashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ParallelController parallelController = new ParallelController();
        parallelController.setName("parallel");
        parallelController.setMaxParallel(2);
        parallelController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, parallelController);
        testTree.add(parallelController, new TrackingSampler(
                "one", activeSamplers, maxActiveSamplers, completedSamplers, observedVariables, sameVariables));
        testTree.add(parallelController, new TrackingSampler(
                "two", activeSamplers, maxActiveSamplers, completedSamplers, observedVariables, sameVariables));
        testTree.add(parallelController, new TrackingSampler(
                "three", activeSamplers, maxActiveSamplers, completedSamplers, observedVariables, sameVariables));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("parallel-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.run();

        assertTrue(completedSamplers.await(5, TimeUnit.SECONDS), "All parallel samplers should complete");
        assertEquals(2, maxActiveSamplers.get(), "Only two samplers should run at the same time");
        assertTrue(sameVariables.get(), "Parallel workers should share virtual user variables");
    }

    @Test
    void testParallelControllerContinuesAfterChildSamplerException() throws InterruptedException {
        CountDownLatch completedSamplers = new CountDownLatch(1);

        HashTree testTree = new HashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ParallelController parallelController = new ParallelController();
        parallelController.setName("parallel");
        parallelController.setMaxParallel(1);
        parallelController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, parallelController);
        testTree.add(parallelController, new FailingSampler("failing"));
        testTree.add(parallelController, new CompletingSampler("after-failure", completedSamplers));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("parallel-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.run();

        assertTrue(completedSamplers.await(5, TimeUnit.SECONDS),
                "A later parallel child should still run after an earlier child throws");
    }

    @Test
    void testForkControllerContinuesMainFlowAndSharesVariables() throws InterruptedException {
        CountDownLatch forkStarted = new CountDownLatch(1);
        CountDownLatch releaseFork = new CountDownLatch(1);
        CountDownLatch forkFinished = new CountDownLatch(1);
        CountDownLatch mainFlowContinued = new CountDownLatch(1);
        AtomicReference<JMeterVariables> observedVariables = new AtomicReference<>();
        AtomicBoolean sameVariables = new AtomicBoolean(true);

        HashTree testTree = new HashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = new ForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new BlockingSampler("fork-child", forkStarted, releaseFork, observedVariables));
        testTree.add(forkController, new CompletingSampler("fork-after-main-finished", forkFinished));
        testTree.add(loop, new VariableRecordingSampler(
                "main-after-fork", mainFlowContinued, observedVariables, sameVariables));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("fork-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-controller-test");
        runner.start();

        assertTrue(forkStarted.await(5, TimeUnit.SECONDS), "Fork child should start");
        assertTrue(mainFlowContinued.await(5, TimeUnit.SECONDS),
                "Main flow should continue while the fork child is still running");
        assertTrue(runner.isAlive(), "Virtual user should wait for the active fork before finishing");

        releaseFork.countDown();
        runner.join(5000);

        assertTrue(forkFinished.await(5, TimeUnit.SECONDS),
                "Fork flow should continue after the main flow has completed");
        assertFalse(runner.isAlive(), "Virtual user should finish after the fork child completes");
        assertTrue(sameVariables.get(), "Fork worker should share virtual user variables");
    }

    @Test
    void testLogicalActionCanUnwindCompletedTransactionSampler() throws Exception {
        HashTree testTree = new HashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController transactionController = new TransactionController();
        transactionController.setName("transaction");
        transactionController.setGenerateParentSample(true);
        DummySampler childSampler = createSampler();

        testTree.add(loop);
        testTree.add(loop, transactionController);
        testTree.add(transactionController, childSampler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        JMeterContext context = JMeterContextService.getContext();
        context.setVariables(new JMeterVariables());
        context.setThread(jMeterThread);
        context.setThreadGroup(threadGroup);

        Field compilerField = JMeterThread.class.getDeclaredField("compiler");
        compilerField.setAccessible(true);
        testTree.traverse((TestCompiler) compilerField.get(jMeterThread));

        TransactionSampler transactionSampler = new TransactionSampler(transactionController, transactionController.getName());
        Method triggerMethod = JMeterThread.class.getDeclaredMethod(
                "triggerLoopLogicalActionOnParentControllers",
                Sampler.class,
                JMeterContext.class,
                Consumer.class);
        triggerMethod.setAccessible(true);

        assertDoesNotThrow(() -> triggerMethod.invoke(
                jMeterThread,
                transactionSampler,
                context,
                (Consumer<FindTestElementsUpToRootTraverser>) traverser -> { }));
    }

    private static LoopController createLoopController() {
        LoopController result = new LoopController();
        result.setLoops(LoopController.INFINITE_LOOP_COUNT);
        result.setEnabled(true);
        return result;
    }

    private static DummySampler createSampler() {
        DummySampler result = new DummySampler();
        result.setName("Call me");
        return result;
    }

    private static Timer createConstantTimer(long delay) {
        DummyTimer timer = new DummyTimer();
        timer.setEnabled(true);
        timer.setDelay(delay);
        timer.setName("Long delay");
        return timer;
    }
}
