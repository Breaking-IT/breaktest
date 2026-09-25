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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.ForkController.FinalStopAction;
import org.apache.jmeter.control.ForkController.IterationEndAction;
import org.apache.jmeter.control.ForkController.RunningAction;
import org.apache.jmeter.control.ForkControllerSampler;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.ParallelControllerSampler;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.engine.util.ReplaceStringWithFunctions;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.samplers.StoppableSampler;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.HashTreeTraverser;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TestJMeterThread {

    private static ForkController newConfiguredForkController() {
        ForkController controller = new ForkController();
        controller.setIterationEndAction(IterationEndAction.GRACEFUL);
        controller.setRunningAction(RunningAction.SKIP);
        return controller;
    }

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

    private static final class CountingListedHashTree extends ListedHashTree {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger parentPathTraversals = new AtomicInteger();

        @Override
        public void traverse(HashTreeTraverser visitor) {
            if (visitor instanceof FindTestElementsUpToRootTraverser) {
                parentPathTraversals.incrementAndGet();
            }
            super.traverse(visitor);
        }

        private int parentPathTraversals() {
            return parentPathTraversals.get();
        }
    }

    private static class StoppableDelayTimer extends AbstractTestElement implements Timer {
        private static final long serialVersionUID = 1L;

        private final long delay;
        private final CountDownLatch started;
        private final CountDownLatch stopped;

        private StoppableDelayTimer(long delay, CountDownLatch started, CountDownLatch stopped) {
            this.delay = delay;
            this.started = started;
            this.stopped = stopped;
        }

        @Override
        public long delay() {
            started.countDown();
            return delay;
        }

        @Override
        public void stop() {
            stopped.countDown();
        }
    }


    private static final class TrackingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger activeSamplers;
        private final AtomicInteger maxActiveSamplers;
        private final CountDownLatch completedSamplers;

        private TrackingSampler(String name,
                AtomicInteger activeSamplers,
                AtomicInteger maxActiveSamplers,
                CountDownLatch completedSamplers) {
            setName(name);
            this.activeSamplers = activeSamplers;
            this.maxActiveSamplers = maxActiveSamplers;
            this.completedSamplers = completedSamplers;
        }

        @Override
        public SampleResult sample(Entry e) {
            int active = activeSamplers.incrementAndGet();
            maxActiveSamplers.accumulateAndGet(active, Math::max);
            JMeterContextService.getContext().getVariables().put("written-by-" + getName(), "yes");
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

    private static final class AwaitingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;
        private final BooleanSupplier ready;

        private AwaitingSampler(BooleanSupplier ready) {
            this.ready = ready;
            setName("wait-for-fork");
        }

        @Override
        public SampleResult sample(Entry entry) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            assertTrue(ready.getAsBoolean(), "Fork must start before the main flow finishes");
            return null;
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

    private static final class ResultStatusSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final boolean successful;
        private final AtomicInteger calls;

        private ResultStatusSampler(String name, boolean successful, AtomicInteger calls) {
            setName(name);
            this.successful = successful;
            this.calls = calls;
        }

        @Override
        public SampleResult sample(Entry e) {
            calls.incrementAndGet();
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(successful);
            result.sampleEnd();
            return result;
        }
    }

    private static final class StopAfterFailuresSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final int stopThreadAtCall;
        private int calls;

        private StopAfterFailuresSampler(String name, int stopThreadAtCall) {
            setName(name);
            this.stopThreadAtCall = stopThreadAtCall;
        }

        @Override
        public SampleResult sample(Entry e) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(false);
            result.setStopThread(++calls >= stopThreadAtCall);
            result.sampleEnd();
            return result;
        }
    }

    private static final class LoopIndexRecordingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final String loopName;
        private final List<Integer> indexes;

        private LoopIndexRecordingSampler(String name, String loopName, List<Integer> indexes) {
            setName(name);
            this.loopName = loopName;
            this.indexes = indexes;
        }

        @Override
        public SampleResult sample(Entry e) {
            Object index = JMeterContextService.getContext().getVariables()
                    .getObject(GenericController.getIndexVariableName(loopName));
            indexes.add((Integer) index);
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static final class MetadataNeedingVisualizer implements Visualizer {
        private final List<SampleResult> results = Collections.synchronizedList(new ArrayList<>());
        private final List<SampleResult> startedTransactions = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void add(SampleResult sample) {
            results.add(sample);
        }

        @Override
        public void addStartedTransaction(SampleEvent event) {
            startedTransactions.add(event.getResult());
        }

        @Override
        public boolean needsStartedResults() {
            return true;
        }

        List<SampleResult> results() {
            synchronized (results) {
                return List.copyOf(results);
            }
        }

        List<SampleResult> startedTransactions() {
            synchronized (startedTransactions) {
                return List.copyOf(startedTransactions);
            }
        }

        @Override
        public boolean needsSampleResultMetadata() {
            return true;
        }

        @Override
        public boolean isStats() {
            return false;
        }
    }

    private static final class StopTrackingSampler extends AbstractSampler implements StoppableSampler {
        private static final long serialVersionUID = 1L;

        private final AtomicBoolean stopped = new AtomicBoolean(false);

        @Override
        public SampleResult sample(Entry e) {
            return null;
        }

        @Override
        public void stop() {
            stopped.set(true);
        }
    }

    private static final class StopReleasableSampler extends AbstractSampler implements StoppableSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch started;
        private final CountDownLatch stopped;

        private StopReleasableSampler(String name, CountDownLatch started, CountDownLatch stopped) {
            setName(name);
            this.started = started;
            this.stopped = stopped;
        }

        @Override
        public SampleResult sample(Entry e) {
            started.countDown();
            try {
                stopped.await(5, TimeUnit.SECONDS);
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

        @Override
        public void stop() {
            stopped.countDown();
        }
    }

    private static class InterruptibleFailureSampler extends AbstractSampler implements Interruptible {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);

        private InterruptibleFailureSampler() {
            setName("interrupted-request");
        }

        @Override
        public SampleResult sample(Entry entry) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            started.countDown();
            try {
                interrupted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            result.setSuccessful(false);
            result.sampleEnd();
            return result;
        }

        @Override
        public boolean interrupt() {
            interrupted.countDown();
            return true;
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
            JMeterVariables variables = JMeterContextService.getContext().getVariables();
            observedVariables.compareAndSet(null, variables);
            variables.put("written-by-" + getName(), "yes");
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

    private static final class CountingBlockingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger calls;
        private final CountDownLatch firstStarted;
        private final CountDownLatch release;

        private CountingBlockingSampler(String name, AtomicInteger calls, CountDownLatch firstStarted,
                CountDownLatch release) {
            setName(name);
            this.calls = calls;
            this.firstStarted = firstStarted;
            this.release = release;
        }

        @Override
        public SampleResult sample(Entry e) {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
            }
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

        private VariableRecordingSampler(String name, CountDownLatch completedSamplers,
                AtomicReference<JMeterVariables> observedVariables) {
            setName(name);
            this.completedSamplers = completedSamplers;
            this.observedVariables = observedVariables;
        }

        @Override
        public SampleResult sample(Entry e) {
            observedVariables.compareAndSet(null, JMeterContextService.getContext().getVariables());
            completedSamplers.countDown();
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static final class IterationVariableSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> secondIterationRuntimeValue = new AtomicReference<>();
        private final AtomicReference<String> secondIterationInitialValue = new AtomicReference<>();

        @Override
        public SampleResult sample(Entry e) {
            JMeterVariables variables = JMeterContextService.getContext().getVariables();
            if (calls.incrementAndGet() == 1) {
                variables.put("runtime", "dirty");
            } else {
                secondIterationRuntimeValue.set(variables.get("runtime"));
                secondIterationInitialValue.set(variables.get("initial"));
            }
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static final class SleepStatusSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final long sleepMillis;
        private final boolean successful;
        private final AtomicReference<String> lastSampleOkAtSampleEnd;

        private SleepStatusSampler(String name, long sleepMillis, boolean successful,
                AtomicReference<String> lastSampleOkAtSampleEnd) {
            setName(name);
            this.sleepMillis = sleepMillis;
            this.successful = successful;
            this.lastSampleOkAtSampleEnd = lastSampleOkAtSampleEnd;
        }

        @Override
        public SampleResult sample(Entry e) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (lastSampleOkAtSampleEnd != null) {
                lastSampleOkAtSampleEnd.set(
                        JMeterContextService.getContext().getVariables().get(JMeterThread.LAST_SAMPLE_OK));
            }
            result.setSuccessful(successful);
            result.sampleEnd();
            return result;
        }
    }

    private static final class RecordingSampleListener extends AbstractTestElement implements SampleListener {
        private static final long serialVersionUID = 1L;

        private final List<SampleEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final List<SampleEvent> startedEvents = Collections.synchronizedList(new ArrayList<>());
        private final List<String> startEvents = Collections.synchronizedList(new ArrayList<>());
        private boolean startEventsNeeded = true;
        private final boolean jMeterVariablesNeeded;
        private final boolean sourceTestElementPathNeeded;

        private RecordingSampleListener(String name) {
            this(name, false, false);
        }

        private RecordingSampleListener(
                String name, boolean jMeterVariablesNeeded, boolean sourceTestElementPathNeeded) {
            setName(name);
            this.jMeterVariablesNeeded = jMeterVariablesNeeded;
            this.sourceTestElementPathNeeded = sourceTestElementPathNeeded;
        }

        List<SampleEvent> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }

        List<SampleEvent> transactionEvents() {
            synchronized (events) {
                return events.stream().filter(SampleEvent::isTransactionSampleEvent).toList();
            }
        }

        List<SampleEvent> startedEvents() {
            synchronized (startedEvents) {
                return List.copyOf(startedEvents);
            }
        }

        /** Sampler start, finish and stop events in order, e.g. {@code started:first} */
        List<String> startEvents() {
            synchronized (startEvents) {
                return List.copyOf(startEvents);
            }
        }

        RecordingSampleListener withoutStartEvents() {
            startEventsNeeded = false;
            return this;
        }

        @Override
        public void sampleOccurred(SampleEvent e) {
            events.add(e);
            if (startEventsNeeded && e.getStartedSample() != null) {
                startEvents.add("occurred:" + e.getResult().getSampleLabel()
                        + (e.getStartedSample().getSampleLabel().equals(e.getResult().getSampleLabel()) ? "" : "!"));
            }
        }

        @Override
        public void transactionStarted(SampleEvent e) {
            startedEvents.add(e);
        }

        @Override
        public boolean needsStartEvents() {
            return startEventsNeeded;
        }

        @Override
        public boolean needsJMeterVariables() {
            return jMeterVariablesNeeded;
        }

        @Override
        public boolean needsSourceTestElementPath() {
            return sourceTestElementPathNeeded;
        }

        @Override
        public void sampleStarted(SampleEvent e) {
            startEvents.add("started:" + e.getResult().getSampleLabel());
        }

        @Override
        public void sampleStopped(SampleEvent e) {
            startEvents.add("stopped:" + e.getResult().getSampleLabel());
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

    private static final class RecordingThreadListener extends AbstractTestElement implements ThreadListener {
        private static final long serialVersionUID = 1L;
        private final AtomicBoolean finished;

        private RecordingThreadListener(AtomicBoolean finished) {
            this.finished = finished;
        }

        @Override
        public void threadStarted() {
        }

        @Override
        public void threadFinished() {
            finished.set(true);
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
    void testDifferentUserOnNextIterationClearsRuntimeVariables() {
        IterationVariableSampler sampler = runTwoIterationsWithSameUserSetting(false);

        assertEquals(2, sampler.calls.get(), "Sampler should run in both iterations");
        assertNull(sampler.secondIterationRuntimeValue.get(),
                "Runtime variables from the previous user should be cleared");
        assertEquals("seed", sampler.secondIterationInitialValue.get(),
                "Initial thread variables should be restored like a fresh thread");
    }

    @Test
    void testSameUserOnNextIterationKeepsRuntimeVariables() {
        IterationVariableSampler sampler = runTwoIterationsWithSameUserSetting(true);

        assertEquals(2, sampler.calls.get(), "Sampler should run in both iterations");
        assertEquals("dirty", sampler.secondIterationRuntimeValue.get(),
                "Runtime variables should be preserved for the same user");
        assertEquals("seed", sampler.secondIterationInitialValue.get(),
                "Initial thread variables should remain available");
    }

    @Test
    void testThreadGroupPacingFirstIterationDoesNotDelay() {
        ThreadGroupPacingFixture fixture = createThreadGroupPacingFixture();
        fixture.threadGroup.setPacingMode(AbstractThreadGroup.PACING_FIXED);
        fixture.threadGroup.setFixedPacing("100");

        assertEquals(0, fixture.jMeterThread.computeThreadGroupPacingDelay(1000));
    }

    @Test
    void testThreadGroupPacingComputesNextStartDelay() {
        ThreadGroupPacingFixture fixture = createThreadGroupPacingFixture();
        fixture.threadGroup.setPacingMode(AbstractThreadGroup.PACING_FIXED);
        fixture.threadGroup.setFixedPacing("100");

        fixture.jMeterThread.recordThreadGroupIterationStart(1000);

        assertEquals(60, fixture.jMeterThread.computeThreadGroupPacingDelay(1040));
    }

    @Test
    void testThreadGroupPacingDoesNotDelayWhenCurrentStartExceededTarget() {
        ThreadGroupPacingFixture fixture = createThreadGroupPacingFixture();
        fixture.threadGroup.setPacingMode(AbstractThreadGroup.PACING_FIXED);
        fixture.threadGroup.setFixedPacing("100");

        fixture.jMeterThread.recordThreadGroupIterationStart(1000);

        assertEquals(0, fixture.jMeterThread.computeThreadGroupPacingDelay(1120));
    }

    @Test
    void testThreadGroupRandomPacingSupportsSameMinAndMax() {
        ThreadGroupPacingFixture fixture = createThreadGroupPacingFixture();
        fixture.threadGroup.setPacingMode(AbstractThreadGroup.PACING_RANDOM);
        fixture.threadGroup.setPacingMin("70");
        fixture.threadGroup.setPacingMax("70");

        fixture.jMeterThread.recordThreadGroupIterationStart(1000);

        assertEquals(40, fixture.jMeterThread.computeThreadGroupPacingDelay(1030));
    }

    @Test
    void testThreadGroupPacingValuesSupportVariables() throws Exception {
        JMeterVariables variables = new JMeterVariables();
        variables.put("pacingMs", "75");
        JMeterContextService.getContext().setVariables(variables);
        ThreadGroupPacingFixture fixture = createThreadGroupPacingFixture();
        fixture.threadGroup.setPacingMode(AbstractThreadGroup.PACING_FIXED);
        fixture.threadGroup.setProperty(functionProperty(
                AbstractThreadGroupSchema.INSTANCE.getFixedPacing().getName(),
                "${pacingMs}"));

        fixture.jMeterThread.recordThreadGroupIterationStart(1000);

        assertEquals(50, fixture.jMeterThread.computeThreadGroupPacingDelay(1025));
    }

    @Test
    void testThreadGroupPacingCompensatesForDrift() {
        ThreadGroupPacingFixture fixture = createThreadGroupPacingFixture();
        fixture.threadGroup.setPacingMode(AbstractThreadGroup.PACING_FIXED);
        fixture.threadGroup.setFixedPacing("100");

        fixture.jMeterThread.recordThreadGroupIterationStart(1000);
        fixture.jMeterThread.recordThreadGroupIterationStart(1103);

        assertEquals(97, fixture.jMeterThread.computeThreadGroupPacingDelay(1103));
    }

    @Test
    void testValidationRunSkipsThreadGroupPacing() {
        ThreadGroupPacingFixture fixture = createThreadGroupPacingFixture();
        fixture.threadGroup.setPacingMode(AbstractThreadGroup.PACING_FIXED);
        fixture.threadGroup.setFixedPacing("1000");
        fixture.jMeterThread.recordThreadGroupIterationStart(System.currentTimeMillis());

        JMeterContextService.setValidationRun(true);
        try {
            long start = System.currentTimeMillis();
            fixture.jMeterThread.applyThreadGroupPacing();

            assertTrue(System.currentTimeMillis() - start < 500,
                    "Thread Group pacing should not sleep during validation");
        } finally {
            JMeterContextService.setValidationRun(false);
        }
    }

    @Test
    void testParallelControllerHonorsMaxParallelAndSharesVariables() throws Exception {
        AtomicInteger activeSamplers = new AtomicInteger();
        AtomicInteger maxActiveSamplers = new AtomicInteger();
        CountDownLatch completedSamplers = new CountDownLatch(3);

        HashTree testTree = new ListedHashTree();
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
                "one", activeSamplers, maxActiveSamplers, completedSamplers));
        testTree.add(parallelController, new TrackingSampler(
                "two", activeSamplers, maxActiveSamplers, completedSamplers));
        testTree.add(parallelController, new TrackingSampler(
                "three", activeSamplers, maxActiveSamplers, completedSamplers));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("parallel-thread");
        jMeterThread.setThreadGroup(threadGroup);
        JMeterContext context = processParallelSamplerDirect(testTree, parallelController, jMeterThread, threadGroup);

        assertTrue(completedSamplers.await(5, TimeUnit.SECONDS), "All parallel samplers should complete");
        assertEquals(2, maxActiveSamplers.get(), "Only two samplers should run at the same time");
        assertEquals("yes", context.getVariables().get("written-by-one"),
                "Parallel workers should share virtual user variables");
        assertEquals("yes", context.getVariables().get("written-by-two"),
                "Parallel workers should share virtual user variables");
        assertEquals("yes", context.getVariables().get("written-by-three"),
                "Parallel workers should share virtual user variables");
    }

    @Test
    void testSourcePathOnlyListenerReceivesParallelAncestryWithoutVariableSnapshot() {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ParallelController parallelController = new ParallelController();
        parallelController.setName("parallel");
        parallelController.setMaxParallel(1);
        parallelController.setEnabled(true);
        AtomicInteger calls = new AtomicInteger();
        ResultStatusSampler sampler = new ResultStatusSampler("request", true, calls);
        RecordingSampleListener listener = new RecordingSampleListener("source-path-listener", false, true);

        HashTree loopTree = testTree.add(loop);
        HashTree parallelTree = loopTree.add(parallelController);
        loopTree.add(listener);
        parallelTree.add(sampler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("source-path-thread");
        jMeterThread.setThreadGroup(threadGroup);
        JMeterVariables variables = new JMeterVariables();
        variables.put("should-not-be-copied", "value");
        jMeterThread.putVariables(variables);
        jMeterThread.run();

        assertEquals(1, calls.get());
        List<SampleEvent> events = listener.events();
        assertEquals(1, events.size());
        SampleResult result = events.get(0).getResult();
        assertFalse(result.hasJMeterVariables(), "Requesting a source path must not snapshot variables");
        assertTrue(result.getSourceTestElementPath().stream()
                .anyMatch(entry -> entry.className().equals(ParallelController.class.getName())),
                "The source path should expose Parallel Controller ancestry");
        SampleResult.TestElementPathEntry source = result.getSourceTestElementPath()
                .get(result.getSourceTestElementPath().size() - 1);
        assertEquals(sampler.getClass().getName(), source.className());
        assertEquals(sampler.getName(), source.name());
    }

    @Test
    void testParallelControllerRunsNestedLoopBranchesWithOwnIndexes() throws Exception {
        List<Integer> firstLoopIndexes = Collections.synchronizedList(new ArrayList<>());
        List<Integer> secondLoopIndexes = Collections.synchronizedList(new ArrayList<>());

        HashTree testTree = new ListedHashTree();
        LoopController rootLoop = new LoopController();
        rootLoop.setLoops(1);
        rootLoop.setContinueForever(false);
        rootLoop.setEnabled(true);
        ParallelController parallelController = new ParallelController();
        parallelController.setName("parallel");
        parallelController.setMaxParallel(2);
        parallelController.setEnabled(true);
        LoopController firstLoop = new LoopController();
        firstLoop.setName("LoopAudio");
        firstLoop.setLoops(2);
        firstLoop.setContinueForever(false);
        firstLoop.setEnabled(true);
        LoopController secondLoop = new LoopController();
        secondLoop.setName("LoopAudio2");
        secondLoop.setLoops(2);
        secondLoop.setContinueForever(false);
        secondLoop.setEnabled(true);

        testTree.add(rootLoop);
        testTree.add(rootLoop, parallelController);
        testTree.add(parallelController, firstLoop);
        testTree.add(firstLoop, new LoopIndexRecordingSampler("audioDashLive", "LoopAudio", firstLoopIndexes));
        testTree.add(parallelController, secondLoop);
        testTree.add(secondLoop, new LoopIndexRecordingSampler("audioDashLive", "LoopAudio2", secondLoopIndexes));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("parallel-thread");
        jMeterThread.setThreadGroup(threadGroup);
        processParallelSamplerDirect(testTree, parallelController, jMeterThread, threadGroup);

        assertEquals(List.of(0, 1), firstLoopIndexes,
                "The first branch should advance its own loop index while it runs");
        assertEquals(List.of(0, 1), secondLoopIndexes,
                "The second branch should advance its own loop index while it runs");
    }

    @Test
    void testParallelControllerContinuesAfterChildSamplerException() throws Exception {
        CountDownLatch completedSamplers = new CountDownLatch(1);

        HashTree testTree = new ListedHashTree();
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
        processParallelSamplerDirect(testTree, parallelController, jMeterThread, threadGroup);

        assertTrue(completedSamplers.await(5, TimeUnit.SECONDS),
                "A later parallel child should still run after an earlier child throws");
    }

    @Test
    void testParallelControllerStartNextLoopOnErrorSkipsUnstartedChildren() throws Exception {
        AtomicInteger failingCalls = new AtomicInteger();
        AtomicInteger sameParallelAfterFailureCalls = new AtomicInteger();

        HashTree testTree = new ListedHashTree();
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
        testTree.add(parallelController, new ResultStatusSampler("failing", false, failingCalls));
        testTree.add(parallelController, new ResultStatusSampler(
                "same-parallel-after-failure", true, sameParallelAfterFailureCalls));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("parallel-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.setOnErrorStartNextLoop(true);
        JMeterContext context = processParallelSamplerDirect(testTree, parallelController, jMeterThread, threadGroup);

        assertEquals(1, failingCalls.get(), "The failing sampler should run");
        assertEquals(0, sameParallelAfterFailureCalls.get(),
                "Start Next Thread Loop should not launch later parallel children after a failure");
        assertEquals("false", context.getVariables().get(JMeterThread.LAST_SAMPLE_OK),
                "Later parallel successes must not overwrite the failed state");
    }

    @Test
    void testParallelControllerSamplerLogicalActionUsesSourceController() throws Exception {
        HashTree testTree = new ListedHashTree();
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
        testTree.add(parallelController, createSampler());

        JMeterThread jMeterThread = new JMeterThread(testTree, null, new ListenerNotifier());
        JMeterContext context = JMeterContextService.getContext();
        context.setVariables(new JMeterVariables());
        context.setThread(jMeterThread);

        TestCompiler.initialize();
        Field compilerField = JMeterThread.class.getDeclaredField("compiler");
        compilerField.setAccessible(true);
        testTree.traverse((TestCompiler) compilerField.get(jMeterThread));
        parallelController.initialize();
        ParallelControllerSampler parallelSampler = (ParallelControllerSampler) parallelController.next();
        AtomicBoolean sawParallelController = new AtomicBoolean();
        Method triggerMethod = JMeterThread.class.getDeclaredMethod(
                "triggerLoopLogicalActionOnParentControllers",
                Sampler.class,
                Consumer.class);
        triggerMethod.setAccessible(true);

        triggerMethod.invoke(
                jMeterThread,
                parallelSampler,
                (Consumer<FindTestElementsUpToRootTraverser>) traverser -> {
                    List<Controller> controllers = traverser.getControllersToRoot();
                    sawParallelController.set(controllers.contains(parallelController));
                });

        assertTrue(sawParallelController.get(),
                "Synthetic parallel sampler should resolve to the real ParallelController in the test tree");
    }

    @Test
    void testTransactionWrappedParallelControllerSamplerLogicalActionUsesSourceController() throws Exception {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController transactionController = new TransactionController();
        transactionController.setName("transaction");
        ParallelController parallelController = new ParallelController();
        parallelController.setName("parallel");
        parallelController.setMaxParallel(1);
        parallelController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, transactionController);
        testTree.add(transactionController, parallelController);
        testTree.add(parallelController, createSampler());

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("transaction-parallel-thread");
        jMeterThread.setThreadGroup(threadGroup);
        JMeterContext context = JMeterContextService.getContext();
        context.setVariables(new JMeterVariables());
        context.setThread(jMeterThread);
        context.setThreadGroup(threadGroup);

        TestCompiler.initialize();
        Field compilerField = JMeterThread.class.getDeclaredField("compiler");
        compilerField.setAccessible(true);
        testTree.traverse((TestCompiler) compilerField.get(jMeterThread));
        parallelController.initialize();
        ParallelControllerSampler parallelSampler = (ParallelControllerSampler) parallelController.next();
        AtomicBoolean sawParallelController = new AtomicBoolean();
        AtomicBoolean sawTransactionController = new AtomicBoolean();
        Method triggerMethod = JMeterThread.class.getDeclaredMethod(
                "triggerLoopLogicalActionOnParentControllers",
                Sampler.class,
                Consumer.class);
        triggerMethod.setAccessible(true);

        triggerMethod.invoke(
                jMeterThread,
                parallelSampler,
                (Consumer<FindTestElementsUpToRootTraverser>) traverser -> {
                    List<Controller> controllers = traverser.getControllersToRoot();
                    sawParallelController.set(controllers.contains(parallelController));
                    sawTransactionController.set(controllers.contains(transactionController));
                });

        assertTrue(sawParallelController.get(),
                "Parallel sampler inside a transaction should resolve to the real ParallelController");
        assertTrue(sawTransactionController.get(),
                "Start Next Thread Loop should unwind the enclosing TransactionController");
    }

    @Test
    void testLoopLogicalActionCachesParentControllerPathByResolvedNode() throws Exception {
        CountingListedHashTree testTree = new CountingListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        GenericController controller = new GenericController();
        controller.setName("parent");
        DummySampler sampler = createSampler();

        testTree.add(loop);
        testTree.add(loop, controller);
        testTree.add(controller, sampler);

        JMeterThread jMeterThread = new JMeterThread(testTree, null, new ListenerNotifier());
        JMeterContext context = JMeterContextService.getContext();
        context.setVariables(new JMeterVariables());
        context.setThread(jMeterThread);
        Method triggerMethod = JMeterThread.class.getDeclaredMethod(
                "triggerLoopLogicalActionOnParentControllers",
                Sampler.class,
                Consumer.class);
        triggerMethod.setAccessible(true);

        Consumer<FindTestElementsUpToRootTraverser> assertPathContainsParent = traverser -> {
            List<Controller> controllers = traverser.getControllersToRoot();
            assertTrue(controllers.stream().anyMatch(parent -> parent == controller));
        };

        triggerMethod.invoke(jMeterThread, sampler, assertPathContainsParent);
        triggerMethod.invoke(jMeterThread, sampler, assertPathContainsParent);

        assertEquals(1, testTree.parentPathTraversals(),
                "Parent controller path should be cached after the first lookup");
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void cancellationBeforeWorkerStartupCleansAllForkState(boolean stopThread) throws Exception {
        CountDownLatch holdExecutor = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                holdExecutor.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        HashTree tree = new ListedHashTree();
        HashTree loopTree = tree.add(loop);
        ForkController fork = newConfiguredForkController();
        AtomicInteger forkCalls = new AtomicInteger();
        loopTree.add(fork).add(new ResultStatusSampler("must-not-start", true, forkCalls));
        CountDownLatch mainStarted = new CountDownLatch(1);
        CountDownLatch releaseMain = new CountDownLatch(1);
        loopTree.add(new StopReleasableSampler("main", mainStarted, releaseMain));
        ThreadGroup group = new ThreadGroup();
        group.setName("cancel-before-start");
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), true) {
            @Override
            ExecutorService createForkExecutor(ForkControllerSampler sampler) {
                return executor;
            }
        };
        thread.setThreadGroup(group);
        thread.setThreadName("cancel-before-start");
        Thread runner = new Thread(thread);
        runner.setDaemon(true);
        try {
            runner.start();
            assertTrue(mainStarted.await(5, TimeUnit.SECONDS));
            if (stopThread) {
                thread.stop();
            } else {
                thread.stopForksNow();
            }
            releaseMain.countDown();
            runner.join(5000);
            assertFalse(runner.isAlive(), "A queued cancelled fork must not spin in final cleanup");
            assertEquals(0, forkCalls.get());
            assertForkBookkeepingEventuallyEmpty(thread);
            assertEquals(0, privateCollectionSize(thread, "forksRequestedToStop"));
        } finally {
            releaseMain.countDown();
            holdExecutor.countDown();
            executor.shutdownNow();
            thread.stop();
            runner.join(5000);
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void legacyForkSurvivesIterationBoundaryAndWaitsForFullCompletion(boolean sameUser) throws Exception {
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setContinueForever(false);
        HashTree tree = new ListedHashTree();
        HashTree loopTree = tree.add(loop);
        AtomicInteger iterations = new AtomicInteger();
        loopTree.add(new ResultStatusSampler("iteration-start", true, iterations));
        ForkController legacy = new ForkController();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        HashTree forkTree = loopTree.add(legacy);
        forkTree.add(new BlockingSampler("request", started, release, new AtomicReference<>()));
        forkTree.add(new CompletingSampler("rest-of-fork", finished));
        ThreadGroup group = new ThreadGroup();
        group.setName("legacy-fork");
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
        thread.setThreadGroup(group);
        thread.setThreadName("legacy-fork");
        Thread runner = new Thread(thread);
        try {
            runner.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (iterations.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(2, iterations.get(), "Old plans carry their fork across the iteration boundary");
            assertEquals(0, privateCollectionSize(thread, "forksRequestedToStop"));
            release.countDown();
            runner.join(5000);
            assertFalse(runner.isAlive());
            assertEquals(0, finished.getCount(), "Legacy re-entry and thread end both wait for full completion");
            assertForkBookkeepingEventuallyEmpty(thread);
        } finally {
            release.countDown();
            thread.stop();
            runner.join(5000);
        }
    }

    @ParameterizedTest
    @CsvSource({"WAIT", "IMMEDIATE"})
    void timeoutCleansStopFlagsAndWaitIgnoresHiddenFinalStop(IterationEndAction action) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InterruptibleFailureSampler request = new InterruptibleFailureSampler() {
            private static final long serialVersionUID = 1L;

            @Override
            public SampleResult sample(Entry entry) {
                started.countDown();
                while (release.getCount() != 0) {
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        // Simulate a request that needs time to release its resources after abort.
                    }
                }
                SampleResult result = new SampleResult();
                result.setSampleLabel("request");
                result.setSuccessful(false);
                return result;
            }

            @Override
            public boolean interrupt() {
                interrupted.countDown();
                return true;
            }
        };
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setContinueForever(false);
        HashTree tree = new ListedHashTree();
        HashTree loopTree = tree.add(loop);
        ForkController fork = newConfiguredForkController();
        fork.setIterationEndAction(action);
        fork.setFinalStopAction(FinalStopAction.IMMEDIATE);
        loopTree.add(fork).add(request);
        loopTree.add(new AwaitingSampler(() -> started.getCount() == 0));
        RecordingSampleListener listener = new RecordingSampleListener("results");
        loopTree.add(listener);
        ThreadGroup group = new ThreadGroup();
        group.setName("timeout-cleanup");
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), true);
        thread.setThreadGroup(group);
        thread.setThreadName("timeout-cleanup");
        thread.setScheduled(true);
        thread.setStartTime(System.currentTimeMillis());
        thread.setEndTime(System.currentTimeMillis() + 300);
        Thread runner = new Thread(thread);
        try {
            runner.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Field running = JMeterThread.class.getDeclaredField("running");
            running.setAccessible(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (running.getBoolean(thread) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(running.getBoolean(thread), "The scheduled deadline must end the main flow");
            assertEquals(action == IterationEndAction.IMMEDIATE ? 0 : 1, interrupted.getCount(),
                    "WAIT must not apply a hidden immediate-stop setting");
            release.countDown();
            runner.join(5000);
            assertFalse(runner.isAlive());
            assertEquals(action == IterationEndAction.IMMEDIATE ? 0 : 1, listener.events().size());
            assertForkBookkeepingEventuallyEmpty(thread);
            assertEquals(0, privateCollectionSize(thread, "forksRequestedToStop"));
        } finally {
            release.countDown();
            thread.stop();
            runner.join(5000);
        }
    }

    @Test
    void interruptedForkWaitDoesNotCancelUnrelatedFork() throws Exception {
        InterruptibleFailureSampler unrelated = new InterruptibleFailureSampler();
        CountDownLatch actorFinished = new CountDownLatch(1);
        AtomicReference<JMeterThread> owner = new AtomicReference<>();
        AbstractSampler actor = new AbstractSampler() {
            private static final long serialVersionUID = 1L;

            @Override
            public SampleResult sample(Entry entry) {
                try {
                    assertTrue(unrelated.started.await(5, TimeUnit.SECONDS));
                    Method waitMethod = JMeterThread.class.getDeclaredMethod("waitForForkTask", java.util.concurrent.Future.class);
                    waitMethod.setAccessible(true);
                    Thread.currentThread().interrupt();
                    assertEquals(false, waitMethod.invoke(owner.get(), new FutureTask<Void>(() -> null)));
                    actorFinished.countDown();
                    return null;
                } catch (ReflectiveOperationException | InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        HashTree tree = new ListedHashTree();
        HashTree loopTree = tree.add(loop);
        ForkController background = newConfiguredForkController();
        background.setName("background");
        background.setIterationEndAction(IterationEndAction.WAIT);
        loopTree.add(background).add(unrelated);
        ForkController waiting = newConfiguredForkController();
        waiting.setName("interrupted-wait");
        loopTree.add(waiting).add(actor);
        loopTree.add(new AwaitingSampler(() -> actorFinished.getCount() == 0));
        ThreadGroup group = new ThreadGroup();
        group.setName("local-interruption");
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), true);
        owner.set(thread);
        thread.setThreadGroup(group);
        thread.setThreadName("local-interruption");
        Thread runner = new Thread(thread);
        try {
            runner.start();
            assertTrue(actorFinished.await(5, TimeUnit.SECONDS));
            assertEquals(1, unrelated.interrupted.getCount(), "Interrupting a fork wait must not stop other forks");
            assertTrue(runner.isAlive());
            unrelated.interrupt();
            runner.join(5000);
            assertFalse(runner.isAlive());
            assertForkBookkeepingEventuallyEmpty(thread);
        } finally {
            unrelated.interrupt();
            thread.stop();
            runner.join(5000);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, false, false, false, true", "3, false, false, false, true",
            "1, true, false, false, true", "3, true, false, false, true",
            "1, false, true, false, true", "1, true, true, false, true",
            "3, false, true, false, true", "3, true, true, false, true",
            "3, false, false, true, true", "3, true, false, true, true",
            "1, false, true, true, true", "1, true, true, true, true",
            "3, true, false, false, false", "1, true, true, false, false",
            "3, true, false, true, false", "1, true, true, true, false"})
    void abortForkRequestsWithoutReportingErrors(int iterations, boolean waitingInTimer,
            boolean sameUser, boolean parallel, boolean hardStop) throws Exception {
        Semaphore forkStarted = new Semaphore(0);
        AtomicInteger interruptions = new AtomicInteger();
        AtomicInteger timerStops = new AtomicInteger();
        AtomicInteger mainCalls = new AtomicInteger();
        InterruptibleFailureSampler request = new InterruptibleFailureSampler() {
            private static final long serialVersionUID = 1L;
            private volatile CountDownLatch release;

            @Override
            public SampleResult sample(Entry entry) {
                release = new CountDownLatch(1);
                forkStarted.release();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                SampleResult result = new SampleResult();
                result.setSampleLabel("cancelled-fork-request");
                result.setSuccessful(false);
                return result;
            }

            @Override
            public boolean interrupt() {
                interruptions.incrementAndGet();
                release.countDown();
                return true;
            }
        };
        AbstractSampler main = new AbstractSampler() {
            private static final long serialVersionUID = 1L;

            @Override
            public SampleResult sample(Entry entry) {
                try {
                    assertTrue(forkStarted.tryAcquire(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                mainCalls.incrementAndGet();
                SampleResult result = new SampleResult();
                result.setSampleLabel("main");
                result.setSuccessful(true);
                return result;
            }
        };
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(iterations);
        loop.setContinueForever(false);
        ForkController fork = newConfiguredForkController();
        fork.setName("keep-alive");
        fork.setIterationEndAction(hardStop ? IterationEndAction.IMMEDIATE : IterationEndAction.GRACEFUL);
        RecordingSampleListener listener = new RecordingSampleListener("results");
        HashTree loopTree = testTree.add(loop);
        HashTree forkTree = loopTree.add(fork);
        if (parallel) {
            forkTree = forkTree.add(new ParallelController());
        }
        forkTree.add(request);
        if (waitingInTimer) {
            forkTree.add(new DummyTimer() {
                private static final long serialVersionUID = 1L;

                @Override
                public long delay() {
                    forkStarted.release();
                    return 60_000;
                }

                @Override
                public void stop() {
                    timerStops.incrementAndGet();
                }
            });
        }
        loopTree.add(main);
        loopTree.add(listener);
        ThreadGroup group = new ThreadGroup();
        group.setName("different users");
        group.setNumThreads(1);
        JMeterThread thread = new JMeterThread(testTree, group, new ListenerNotifier(), sameUser);
        thread.setThreadGroup(group);
        thread.setThreadName("different-users-fork");
        thread.setOnErrorStopThread(true);
        Thread runner = new Thread(thread);
        try {
            runner.start();
            runner.join(5000);
            assertFalse(runner.isAlive(), "Main flow must finish without waiting for the keep-alive");
            assertEquals(iterations, mainCalls.get());
            assertEquals(waitingInTimer ? 0 : iterations, interruptions.get());
            assertEquals(waitingInTimer ? iterations : 0, timerStops.get());
            assertEquals(Collections.nCopies(iterations, "main"), listener.events().stream()
                    .map(event -> event.getResult().getSampleLabel()).toList());
            assertForkBookkeepingEventuallyEmpty(thread);
        } finally {
            thread.stop();
            runner.join(5000);
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void waitAtIterationEndCompletesEntireForkBeforeNextIteration(boolean sameUser) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger mainCalls = new AtomicInteger();
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setContinueForever(false);
        HashTree tree = new ListedHashTree();
        HashTree loopTree = tree.add(loop);
        ForkController fork = newConfiguredForkController();
        fork.setIterationEndAction(IterationEndAction.WAIT);
        HashTree forkTree = loopTree.add(fork);
        forkTree.add(new BlockingSampler("active-request", started, release, new AtomicReference<>()));
        forkTree.add(new CompletingSampler("finish-entire-fork", completed));
        loopTree.add(new AwaitingSampler(() -> started.getCount() == 0));
        loopTree.add(new ResultStatusSampler("main", true, mainCalls));
        ThreadGroup group = new ThreadGroup();
        group.setName("wait-for-fork");
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
        thread.setThreadGroup(group);
        thread.setThreadName("wait-for-fork");
        Thread runner = new Thread(thread);
        try {
            runner.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (mainCalls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, mainCalls.get(), "Next iteration must wait for the entire fork");
            assertEquals(0, privateCollectionSize(thread, "forksRequestedToStop"));
            release.countDown();
            runner.join(5000);
            assertFalse(runner.isAlive());
            assertEquals(0, completed.getCount(), "WAIT must run the rest of the fork in both iterations");
            assertEquals(2, mainCalls.get());
            assertForkBookkeepingEventuallyEmpty(thread);
        } finally {
            release.countDown();
            thread.stop();
            runner.join(5000);
        }
    }

    @ParameterizedTest
    @CsvSource({"SKIP, false", "RESTART, false", "WAIT, true"})
    void keepRunningUsesReentryPolicyAndStopsAtFinalBoundary(RunningAction action, boolean duration) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger interruptions = new AtomicInteger();
        AtomicInteger mainCalls = new AtomicInteger();
        InterruptibleFailureSampler request = new InterruptibleFailureSampler() {
            private static final long serialVersionUID = 1L;
            private volatile CountDownLatch release;

            @Override
            public SampleResult sample(Entry entry) {
                release = new CountDownLatch(1);
                calls.incrementAndGet();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                SampleResult result = new SampleResult();
                result.setSampleLabel("cancelled-request");
                result.setSuccessful(false);
                return result;
            }

            @Override
            public boolean interrupt() {
                interruptions.incrementAndGet();
                release.countDown();
                return true;
            }
        };
        LoopController loop = new LoopController();
        loop.setLoops(3);
        loop.setContinueForever(false);
        HashTree tree = new ListedHashTree();
        HashTree loopTree = tree.add(loop);
        ForkController fork = newConfiguredForkController();
        fork.setIterationEndAction(IterationEndAction.KEEP_RUNNING);
        fork.setRunningAction(action);
        fork.setFinalStopAction(FinalStopAction.IMMEDIATE);
        loopTree.add(fork).add(request);
        loopTree.add(new AwaitingSampler(() -> calls.get() >= (action == RunningAction.RESTART ? mainCalls.get() + 1 : 1)));
        loopTree.add(new ResultStatusSampler("main", true, mainCalls));
        RecordingSampleListener listener = new RecordingSampleListener("results");
        loopTree.add(listener);
        ThreadGroup group = new ThreadGroup();
        group.setName("keep-running");
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), true);
        thread.setThreadGroup(group);
        thread.setThreadName("keep-running");
        thread.setOnErrorStopThread(true);
        if (duration) {
            thread.setScheduled(true);
            thread.setStartTime(System.currentTimeMillis());
            thread.setEndTime(System.currentTimeMillis() + 500);
        }
        Thread runner = new Thread(thread);
        try {
            runner.start();
            runner.join(5000);
            assertFalse(runner.isAlive());
            assertEquals(action == RunningAction.RESTART ? 3 : 1, calls.get());
            assertEquals(calls.get(), interruptions.get(), "Each old execution must be stopped before replacement");
            assertEquals(duration ? 1 : 3, mainCalls.get());
            assertEquals(Collections.nCopies(mainCalls.get(), "main"), listener.events().stream()
                    .map(event -> event.getResult().getSampleLabel()).toList());
            assertForkBookkeepingEventuallyEmpty(thread);
        } finally {
            thread.stop();
            runner.join(5000);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, true, GRACEFUL", "1, false, GRACEFUL", "3, false, GRACEFUL",
            "1, true, KEEP_RUNNING", "3, false, KEEP_RUNNING"})
    void gracefulStopFinishesActiveRequestBeforeNextUser(int iterations, boolean sameUser, IterationEndAction action) throws Exception {
        CountDownLatch[] started = new CountDownLatch[iterations];
        CountDownLatch[] release = new CountDownLatch[iterations];
        for (int i = 0; i < iterations; i++) {
            started[i] = new CountDownLatch(1);
            release[i] = new CountDownLatch(1);
        }
        AtomicInteger requestCalls = new AtomicInteger();
        AtomicInteger mainCalls = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        AtomicInteger skippedCalls = new AtomicInteger();
        InterruptibleFailureSampler request = new InterruptibleFailureSampler() {
            private static final long serialVersionUID = 1L;

            @Override
            public SampleResult sample(Entry entry) {
                int iteration = requestCalls.getAndIncrement();
                started[iteration].countDown();
                try {
                    assertTrue(release[iteration].await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
                SampleResult result = new SampleResult();
                result.setSampleLabel("completed-request");
                result.setSuccessful(false);
                return result;
            }

            @Override
            public boolean interrupt() {
                interrupts.incrementAndGet();
                return true;
            }
        };
        AbstractSampler main = new AbstractSampler() {
            private static final long serialVersionUID = 1L;

            @Override
            public SampleResult sample(Entry entry) {
                int iteration = mainCalls.getAndIncrement();
                try {
                    assertTrue(started[iteration].await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return null;
            }
        };
        LoopController loop = new LoopController();
        loop.setLoops(iterations);
        loop.setContinueForever(false);
        HashTree tree = new ListedHashTree();
        HashTree loopTree = tree.add(loop);
        ForkController fork = newConfiguredForkController();
        fork.setRunningAction(RunningAction.SKIP);
        fork.setIterationEndAction(action);
        HashTree forkTree = loopTree.add(fork);
        forkTree.add(request);
        forkTree.add(new ResultStatusSampler("must-not-start", true, skippedCalls));
        loopTree.add(main);
        RecordingSampleListener listener = new RecordingSampleListener("results");
        loopTree.add(listener);
        ThreadGroup group = new ThreadGroup();
        group.setName("graceful users");
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
        thread.setThreadGroup(group);
        thread.setThreadName("graceful-users");
        Thread runner = new Thread(thread);
        try {
            runner.start();
            for (int i = 0; i < iterations; i++) {
                assertTrue(started[i].await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (privateCollectionSize(thread, "forksRequestedToStop") == 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, privateCollectionSize(thread, "forksRequestedToStop"));
                assertEquals(i + 1, mainCalls.get(), "Next user must wait for graceful completion");
                assertEquals(0, interrupts.get());
                release[i].countDown();
            }
            runner.join(5000);
            assertFalse(runner.isAlive());
            assertEquals(0, skippedCalls.get());
            assertEquals(iterations, listener.events().size());
            assertTrue(listener.events().stream().allMatch(event -> !event.getResult().isSuccessful()),
                    "Graceful completion must retain real request failures");
            assertForkBookkeepingEventuallyEmpty(thread);
        } finally {
            for (CountDownLatch latch : release) {
                latch.countDown();
            }
            thread.stop();
            runner.join(5000);
        }
    }

    @Test
    void mainFlowEndUsesEachForksStopMode() throws Exception {
        InterruptibleFailureSampler cancelledRequest = new InterruptibleFailureSampler();
        CountDownLatch waitingForkStarted = new CountDownLatch(1);
        CountDownLatch releaseWaitingFork = new CountDownLatch(1);
        CountDownLatch mainStarted = new CountDownLatch(1);
        CountDownLatch releaseMain = new CountDownLatch(1);
        HashTree tree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        ForkController stopFork = newConfiguredForkController();
        stopFork.setName("stop-fork");
        stopFork.setIterationEndAction(IterationEndAction.IMMEDIATE);
        ForkController waitFork = newConfiguredForkController();
        waitFork.setName("wait-fork");
        HashTree loopTree = tree.add(loop);
        loopTree.add(stopFork).add(cancelledRequest);
        loopTree.add(waitFork, new BlockingSampler("wait-request", waitingForkStarted,
                releaseWaitingFork, new AtomicReference<>()));
        AtomicInteger skippedCalls = new AtomicInteger();
        loopTree.add(waitFork, new ResultStatusSampler("after-graceful-stop", true, skippedCalls));
        loopTree.add(new BlockingSampler("main", mainStarted, releaseMain, new AtomicReference<>()));
        RecordingSampleListener listener = new RecordingSampleListener("results");
        loopTree.add(listener);
        ThreadGroup group = new ThreadGroup();
        group.setName("mixed fork policies");
        group.setNumThreads(1);
        JMeterThread thread = new JMeterThread(tree, group, new ListenerNotifier(), true);
        thread.setThreadGroup(group);
        thread.setThreadName("mixed-fork-policies");
        thread.setOnErrorStopThread(true);
        Thread runner = new Thread(thread);
        try {
            runner.start();
            assertTrue(cancelledRequest.started.await(5, TimeUnit.SECONDS));
            assertTrue(waitingForkStarted.await(5, TimeUnit.SECONDS));
            assertTrue(mainStarted.await(5, TimeUnit.SECONDS));
            releaseMain.countDown();
            assertTrue(cancelledRequest.interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(runner.isAlive(), "Graceful stop must let the active request finish");
            releaseWaitingFork.countDown();
            runner.join(5000);
            assertFalse(runner.isAlive());
            assertEquals(0, skippedCalls.get(), "Graceful stop must not start another request");
            assertEquals(List.of("main", "wait-request"), listener.events().stream()
                    .map(event -> event.getResult().getSampleLabel()).toList());
            assertForkBookkeepingEventuallyEmpty(thread);
        } finally {
            releaseMain.countDown();
            releaseWaitingFork.countDown();
            thread.stop();
            runner.join(5000);
        }
    }

    @Test
    void testForkControllerContinuesMainFlowAndSharesVariables() throws Exception {
        CountDownLatch forkStarted = new CountDownLatch(1);
        CountDownLatch releaseFork = new CountDownLatch(1);
        CountDownLatch forkFinished = new CountDownLatch(1);
        CountDownLatch mainFlowContinued = new CountDownLatch(1);
        AtomicReference<JMeterVariables> forkVariables = new AtomicReference<>();
        AtomicReference<JMeterVariables> mainVariables = new AtomicReference<>();

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new BlockingSampler("fork-child", forkStarted, releaseFork, forkVariables));
        testTree.add(forkController, new CompletingSampler("fork-after-main-finished", forkFinished));
        testTree.add(loop, new AwaitingSampler(() -> forkStarted.getCount() == 0));
        testTree.add(loop, new VariableRecordingSampler("main-after-fork", mainFlowContinued, mainVariables));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-controller-test");
        runner.start();

        assertTrue(forkStarted.await(5, TimeUnit.SECONDS), "Fork child should start");
        assertTrue(mainFlowContinued.await(5, TimeUnit.SECONDS),
                "Main flow should continue while the fork child is still running");
        assertTrue(runner.isAlive(), "Virtual user should wait for the active fork before finishing");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (privateCollectionSize(jMeterThread, "forksRequestedToStop") == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, privateCollectionSize(jMeterThread, "forksRequestedToStop"),
                "Main flow must request graceful stop before the active request is released");
        releaseFork.countDown();
        runner.join(5000);

        assertEquals(1, forkFinished.getCount(),
                "Graceful stop should not execute the next fork sampler after the main flow finishes");
        assertFalse(runner.isAlive(), "Virtual user should finish after the fork child completes");
        assertEquals("yes", mainVariables.get().get("written-by-fork-child"),
                "Fork worker should share virtual user variables");
    }

    @Test
    void testForkWorkerDoesNotClobberMainThreadTransactionState() throws InterruptedException {
        // Fork flow: a slow success, then a fast failure. The failure lands while the main
        // flow is still inside its second transaction child.
        AtomicReference<String> mainLastSampleOkDuringSecondChild = new AtomicReference<>();

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);
        TransactionController transactionController = new TransactionController();
        transactionController.setName("transaction");
        transactionController.setEnabled(true);
        RecordingSampleListener listener = new RecordingSampleListener("transaction-listener");

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new SleepStatusSampler("fork-slow-success", 150, true, null));
        testTree.add(forkController, new SleepStatusSampler("fork-fast-failure", 0, false, null));
        testTree.add(loop, transactionController);
        testTree.add(transactionController, new SleepStatusSampler("tx-first", 0, true, null));
        testTree.add(transactionController, new SleepStatusSampler(
                "tx-second", 300, true, mainLastSampleOkDuringSecondChild));
        testTree.add(transactionController, listener);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        // A dedicated thread keeps the engine thread-locals of this virtual user isolated from
        // thread-locals left behind by other tests on the JUnit worker thread.
        Thread runner = new Thread(jMeterThread, "fork-transaction-state-test");
        runner.start();
        runner.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(runner.isAlive(), "Test plan should complete");

        assertEquals("true", mainLastSampleOkDuringSecondChild.get(),
                "A failure on the fork worker must not leak into the main thread's last_sample_ok");
        List<SampleEvent> transactionEvents = listener.transactionEvents();
        assertEquals(1, transactionEvents.size(),
                "The transaction event must be delivered to listeners scoped to the transaction,"
                        + " even when a fork worker sampled concurrently");
        assertTrue(transactionEvents.get(0).getResult().isSuccessful(),
                "Fork worker failures must not fail the main thread's transaction");
    }

    @Test
    void testForkControllerRunsTransactionControllerChild() throws InterruptedException {
        CountDownLatch forkSampleStarted = new CountDownLatch(1);
        CountDownLatch releaseForkSample = new CountDownLatch(1);
        CountDownLatch mainSample = new CountDownLatch(1);
        AtomicReference<JMeterVariables> observedVariables = new AtomicReference<>();

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);
        TransactionController transactionController = new TransactionController();
        transactionController.setName("fork-transaction");
        transactionController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, transactionController);
        testTree.add(transactionController, new BlockingSampler(
                "fork-transaction-child", forkSampleStarted, releaseForkSample, observedVariables));
        testTree.add(loop, new AwaitingSampler(() -> forkSampleStarted.getCount() == 0));
        testTree.add(loop, new CompletingSampler("main-after-fork-transaction", mainSample));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-transaction-test");
        runner.start();

        boolean forkStarted = forkSampleStarted.await(5, TimeUnit.SECONDS);
        boolean mainFlowContinued = mainSample.await(10, TimeUnit.SECONDS);
        releaseForkSample.countDown();
        runner.join(5000);

        assertTrue(forkStarted, "Fork worker should execute transaction-controller children");
        assertTrue(mainFlowContinued, "Main flow should continue after starting the fork transaction");
        assertFalse(runner.isAlive(), "Virtual user should finish after the fork transaction completes");
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void testForkControllerDoesNotAccumulateWorkersAcrossLoopIterations(boolean skipIfRunning) throws InterruptedException {
        AtomicInteger forkCalls = new AtomicInteger();
        CountDownLatch firstForkStarted = new CountDownLatch(1);
        CountDownLatch releaseFork = new CountDownLatch(1);
        CountDownLatch firstMainSample = new CountDownLatch(skipIfRunning ? 3 : 1);

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(3);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);
        forkController.setRunningAction(skipIfRunning ? RunningAction.SKIP : RunningAction.WAIT);
        forkController.setIterationEndAction(IterationEndAction.KEEP_RUNNING);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new CountingBlockingSampler(
                "fork-child", forkCalls, firstForkStarted, releaseFork));
        AtomicInteger mainIterations = new AtomicInteger();
        testTree.add(loop, new AbstractSampler() {
            private static final long serialVersionUID = 1L;

            @Override
            public SampleResult sample(Entry entry) {
                int expectedStarts = skipIfRunning ? 1 : mainIterations.incrementAndGet();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (forkCalls.get() < expectedStarts && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
                assertEquals(expectedStarts, forkCalls.get());
                firstMainSample.countDown();
                return null;
            }
        });

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-backpressure-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-backpressure-test");
        runner.start();

        assertTrue(firstForkStarted.await(5, TimeUnit.SECONDS), "First fork should start");
        assertTrue(firstMainSample.await(5, TimeUnit.SECONDS),
                "Main flow should continue after starting the first fork");
        Thread.sleep(200);
        assertEquals(1, forkCalls.get(),
                "Later iterations must not overlap the active fork");

        releaseFork.countDown();
        runner.join(5000);

        assertEquals(skipIfRunning ? 1 : 3, forkCalls.get(),
                "Skip keeps the active fork; wait starts a new fork after the previous finishes");
        assertFalse(runner.isAlive(), "Virtual user should finish after queued fork passes complete");
    }

    @Test
    void testCompletedForkControllerIsCleanedWhileThreadKeepsRunning() throws Exception {
        CountDownLatch forkFinished = new CountDownLatch(1);
        CountDownLatch mainSamplerStarted = new CountDownLatch(1);
        CountDownLatch releaseMainSampler = new CountDownLatch(1);
        AtomicReference<JMeterVariables> observedVariables = new AtomicReference<>();

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new CompletingSampler("fork-child", forkFinished));
        testTree.add(loop, new BlockingSampler(
                "main-keeps-thread-running",
                mainSamplerStarted,
                releaseMainSampler,
                observedVariables));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-cleanup-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-cleanup-test");
        runner.start();

        assertTrue(forkFinished.await(5, TimeUnit.SECONDS), "Fork child should finish");
        assertTrue(mainSamplerStarted.await(5, TimeUnit.SECONDS),
                "Main flow should keep the virtual user running after the fork finishes");
        assertForkBookkeepingEventuallyEmpty(jMeterThread);

        releaseMainSampler.countDown();
        runner.join(5000);

        assertFalse(runner.isAlive(), "Virtual user should finish after the main sampler is released");
    }

    @Test
    void testStopForksGracefullyEndsForkTimerWithoutStoppingMainThread() throws Exception {
        CountDownLatch timerStarted = new CountDownLatch(1);
        CountDownLatch timerStopped = new CountDownLatch(1);
        CountDownLatch mainSamplerStarted = new CountDownLatch(1);
        CountDownLatch releaseMainSampler = new CountDownLatch(1);
        AtomicInteger forkSamplerCalls = new AtomicInteger();
        AtomicReference<JMeterVariables> observedVariables = new AtomicReference<>();

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new StoppableDelayTimer(
                TimeUnit.MINUTES.toMillis(5), timerStarted, timerStopped));
        testTree.add(forkController, new ResultStatusSampler("fork-after-timer", true, forkSamplerCalls));
        testTree.add(loop, new BlockingSampler(
                "main-keeps-running",
                mainSamplerStarted,
                releaseMainSampler,
                observedVariables));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-stop-graceful-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-stop-graceful-test");
        runner.start();

        assertTrue(timerStarted.await(5, TimeUnit.SECONDS), "Fork timer should start");
        assertTrue(mainSamplerStarted.await(5, TimeUnit.SECONDS),
                "Main flow should continue while the fork waits in its timer");

        jMeterThread.stopForks();

        assertTrue(timerStopped.await(5, TimeUnit.SECONDS), "Graceful fork stop should notify fork timers");
        assertForkBookkeepingEventuallyEmpty(jMeterThread);
        assertEquals(0, forkSamplerCalls.get(),
                "Graceful fork stop should not start the next fork sampler after waking the timer");
        assertTrue(runner.isAlive(), "Stopping forks should not stop the main virtual-user flow");

        releaseMainSampler.countDown();
        runner.join(5000);

        assertFalse(runner.isAlive(), "Virtual user should finish after the main sampler is released");
    }

    @Test
    void testStopForksNowStopsForkSamplerWithoutStoppingMainThread() throws Exception {
        CountDownLatch forkStarted = new CountDownLatch(1);
        CountDownLatch releaseFork = new CountDownLatch(1);
        CountDownLatch mainSamplerStarted = new CountDownLatch(1);
        CountDownLatch releaseMainSampler = new CountDownLatch(1);
        AtomicReference<JMeterVariables> observedVariables = new AtomicReference<>();

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new BlockingSampler(
                "fork-child", forkStarted, releaseFork, observedVariables));
        testTree.add(loop, new BlockingSampler(
                "main-keeps-running",
                mainSamplerStarted,
                releaseMainSampler,
                observedVariables));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-stop-now-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-stop-now-test");
        runner.start();

        assertTrue(forkStarted.await(5, TimeUnit.SECONDS), "Fork sampler should start");
        assertTrue(mainSamplerStarted.await(5, TimeUnit.SECONDS),
                "Main flow should continue while the fork sampler is blocked");

        jMeterThread.stopForksNow();

        assertForkBookkeepingEventuallyEmpty(jMeterThread);
        assertTrue(runner.isAlive(), "Force-stopping forks should not stop the main virtual-user flow");

        releaseMainSampler.countDown();
        runner.join(5000);

        assertFalse(runner.isAlive(), "Virtual user should finish after the main sampler is released");
    }

    @Test
    void testForkControllerStopsBranchOnErrorStartNextLoop() throws InterruptedException {
        CountDownLatch skippedForkSampler = new CountDownLatch(1);
        CountDownLatch mainSampler = new CountDownLatch(1);
        AtomicInteger forkFailureCalls = new AtomicInteger();

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new ResultStatusSampler("fork-failure", false, forkFailureCalls));
        testTree.add(forkController, new CompletingSampler("should-not-run-after-fork-failure", skippedForkSampler));
        testTree.add(loop, new AwaitingSampler(() -> forkFailureCalls.get() > 0));
        testTree.add(loop, new CompletingSampler("main-after-fork", mainSampler));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-on-error-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.setOnErrorStartNextLoop(true);
        Thread runner = new Thread(jMeterThread, "fork-on-error-test");
        runner.start();
        runner.join(5000);

        assertFalse(runner.isAlive(), "Virtual user should finish after the fork branch stops");
        assertEquals(1, forkFailureCalls.get(), "Fork failure sampler should run once");
        assertEquals(1, skippedForkSampler.getCount(),
                "Fork branch should not continue after a failed sampler when Start Next Thread Loop is enabled");
        assertEquals(0, mainSampler.getCount(), "Main flow should still continue after starting the fork");
    }

    @Test
    void testStopStopsRunningForkSampler() throws InterruptedException {
        CountDownLatch forkStarted = new CountDownLatch(1);
        CountDownLatch forkStopped = new CountDownLatch(1);

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController forkController = newConfiguredForkController();
        forkController.setName("fork");
        forkController.setEnabled(true);

        testTree.add(loop);
        testTree.add(loop, forkController);
        testTree.add(forkController, new StopReleasableSampler("fork-child", forkStarted, forkStopped));
        testTree.add(loop, new StopReleasableSampler("main", new CountDownLatch(1), forkStopped));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier(), true);
        jMeterThread.setThreadName("fork-stop-thread");
        jMeterThread.setThreadGroup(threadGroup);
        Thread runner = new Thread(jMeterThread, "fork-stop-test");
        runner.start();

        assertTrue(forkStarted.await(5, TimeUnit.SECONDS), "Fork child should start");

        jMeterThread.stop();
        runner.join(5000);

        assertEquals(0, forkStopped.getCount(), "Clean stop should notify the running fork sampler");
        assertFalse(runner.isAlive(), "Virtual user should not remain stuck waiting for a stopped fork");
    }

    @Test
    void testStopNotifiesStoppableSampler() throws Exception {
        HashTree testTree = new HashTree();
        LoopController samplerController = createLoopController();
        testTree.add(samplerController);
        StopTrackingSampler sampler = new StopTrackingSampler();

        JMeterThread jMeterThread = new JMeterThread(testTree, null, null);
        Field currentSamplerField = JMeterThread.class.getDeclaredField("currentSamplerForInterruption");
        currentSamplerField.setAccessible(true);
        currentSamplerField.set(jMeterThread, sampler);

        jMeterThread.stop();

        assertTrue(sampler.stopped.get(), "Clean shutdown should notify samplers that opt into stop handling");
    }

    @ParameterizedTest
    @CsvSource({ "false, false", "true, false", "false, true", "true, true" })
    void testStopReportsInterruptedTransactionOnce(boolean startNextLoopOnError, boolean nested)
            throws InterruptedException {
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        InterruptibleFailureSampler sampler = new InterruptibleFailureSampler();
        RecordingSampleListener listener = new RecordingSampleListener("results");
        AtomicInteger subsequentCalls = new AtomicInteger();

        HashTree testTree = new ListedHashTree();
        HashTree loopTree = testTree.add(loop);
        HashTree transactionTree = loopTree.add(transaction);
        if (nested) {
            TransactionController inner = new TransactionController();
            inner.setName("inner-transaction");
            transactionTree = transactionTree.add(inner);
        }
        transactionTree.add(sampler);
        loopTree.add(new ResultStatusSampler("subsequent-request", true, subsequentCalls));
        loopTree.add(listener);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("interrupted-transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.setOnErrorStartNextLoop(startNextLoopOnError);
        Thread runner = new Thread(jMeterThread, "interrupted-transaction-test");
        runner.start();
        try {
            assertTrue(sampler.started.await(5, TimeUnit.SECONDS), "Request should start before stopping");
            jMeterThread.stop();
            assertTrue(jMeterThread.interrupt(), "Stop should interrupt the active request");
            runner.join(5000);
            assertFalse(runner.isAlive(), "Stopped virtual user should finish");
        } finally {
            jMeterThread.stop();
            sampler.interrupt();
            runner.join(5000);
        }

        assertEquals(0, subsequentCalls.get(), "Stop must not start another request");
        List<SampleEvent> events = listener.events();
        assertEquals(nested
                ? List.of("interrupted-request", "inner-transaction", "transaction")
                : List.of("interrupted-request", "transaction"),
                events.stream().map(event -> event.getResult().getSampleLabel()).toList(),
                "The request and each transaction must be reported exactly once");
        for (SampleEvent event : events) {
            assertFalse(event.getResult().isSuccessful());
        }
        for (SampleEvent event : listener.transactionEvents()) {
            assertEquals("Number of samples in transaction : 1, number of failing samples : 1",
                    event.getResult().getResponseMessage());
        }
        assertEquals(nested ? 2 : 1, listener.transactionEvents().size());
        SampleResult request = events.get(0).getResult();
        SampleResult innermost = events.get(1).getResult();
        assertEquals(innermost.getTransaction(), request.getParentTransaction(),
                "The request must point to the innermost transaction");
        if (nested) {
            assertEquals(events.get(2).getResult().getTransaction(), innermost.getParentTransaction(),
                    "The inner transaction must point to the outer transaction");
        }
    }

    @Test
    void testTransactionStreamsSamplesAndLinksThemToTheTransaction() {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController outer = new TransactionController();
        outer.setName("outer");
        TransactionController inner = new TransactionController();
        inner.setName("inner");
        RecordingSampleListener listener = new RecordingSampleListener("results");
        AtomicInteger calls = new AtomicInteger();

        HashTree loopTree = testTree.add(loop);
        HashTree outerTree = loopTree.add(outer);
        outerTree.add(new ResultStatusSampler("first", true, calls));
        outerTree.add(inner).add(new ResultStatusSampler("second", false, calls));
        loopTree.add(new ResultStatusSampler("outside", true, calls));
        loopTree.add(listener);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("streaming-transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.run();

        assertEquals(List.of("outer", "inner"),
                listener.startedEvents().stream().map(event -> event.getResult().getSampleLabel()).toList(),
                "Listeners must be told when each transaction starts");
        List<SampleEvent> events = listener.events();
        assertEquals(List.of("first", "second", "inner", "outer", "outside"),
                events.stream().map(event -> event.getResult().getSampleLabel()).toList(),
                "Samples must be sent as they complete, each transaction after its samples");
        SampleResult first = events.get(0).getResult();
        SampleResult second = events.get(1).getResult();
        SampleResult innerResult = events.get(2).getResult();
        SampleResult outerResult = events.get(3).getResult();
        SampleResult outside = events.get(4).getResult();

        SampleResult outerStarted = listener.startedEvents().get(0).getResult();
        SampleResult innerStarted = listener.startedEvents().get(1).getResult();
        assertEquals(outerResult.getTransaction(), outerStarted.getTransaction());
        assertEquals(innerResult.getTransaction(), innerStarted.getTransaction());
        assertTrue(listener.startedEvents().get(0).isTransactionSampleEvent());

        assertTrue(outerResult.isTransaction());
        assertTrue(innerResult.isTransaction());
        assertFalse(first.isTransaction());
        assertEquals(outerResult.getTransaction(), first.getParentTransaction());
        assertEquals(innerResult.getTransaction(), second.getParentTransaction());
        assertEquals(outerResult.getTransaction(), innerResult.getParentTransaction());
        assertNull(outerResult.getParentTransaction());
        assertNull(outside.getParentTransaction());
        assertEquals(List.of("outer", "inner"), second.getParentTransaction().getPath());

        assertEquals(0, first.getSubResults().length);
        assertEquals(0, outerResult.getSubResults().length, "Transactions must not keep their samples");
        assertEquals("Number of samples in transaction : 2, number of failing samples : 1",
                outerResult.getResponseMessage());
        assertFalse(outerResult.isSuccessful());
        assertEquals("Number of samples in transaction : 1, number of failing samples : 1",
                innerResult.getResponseMessage());
        assertTrue(events.get(3).isTransactionSampleEvent());
        assertFalse(events.get(0).isTransactionSampleEvent());
    }

    @Test
    void testStartNextLoopOnErrorReportsFailedTransactionEachIteration() {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        RecordingSampleListener listener = new RecordingSampleListener("results");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger skippedCalls = new AtomicInteger();

        HashTree loopTree = testTree.add(loop);
        HashTree transactionTree = loopTree.add(transaction);
        transactionTree.add(new ResultStatusSampler("first", true, calls));
        // The test loop restarts on every failure, so stop the thread on the second one
        transactionTree.add(new StopAfterFailuresSampler("failing", 2));
        transactionTree.add(new ResultStatusSampler("skipped", true, skippedCalls));
        loopTree.add(listener);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("start-next-loop-transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.setOnErrorStartNextLoop(true);
        jMeterThread.run();

        assertEquals(0, skippedCalls.get(), "The failing sample must end the iteration");
        assertEquals(List.of("first", "failing", "transaction", "first", "failing", "transaction"),
                listener.events().stream().map(event -> event.getResult().getSampleLabel()).toList());
        List<SampleEvent> transactions = listener.transactionEvents();
        assertEquals(2, transactions.size());
        for (SampleEvent event : transactions) {
            assertFalse(event.getResult().isSuccessful());
            assertEquals("Number of samples in transaction : 2, number of failing samples : 1",
                    event.getResult().getResponseMessage());
        }
        assertFalse(transactions.get(0).getResult().getTransaction()
                .equals(transactions.get(1).getResult().getTransaction()),
                "Each iteration must be a new transaction execution");
        assertEquals(2, listener.startedEvents().size());
    }

    @Test
    void testSamplerStartEventsOnlyReachListenersThatAskForThem() {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        RecordingSampleListener live = new RecordingSampleListener("live");
        RecordingSampleListener writer = new RecordingSampleListener("writer").withoutStartEvents();
        AtomicInteger calls = new AtomicInteger();

        HashTree loopTree = testTree.add(loop);
        loopTree.add(transaction).add(new ResultStatusSampler("first", true, calls));
        loopTree.add(new ResultStatusSampler("second", false, calls));
        loopTree.add(live);
        loopTree.add(writer);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("sampler-start-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.run();

        assertEquals(List.of("started:first", "occurred:first", "stopped:first",
                        "started:second", "occurred:second", "stopped:second"),
                live.startEvents(),
                "A started sampler must be followed by its sample, linked to the placeholder, then its stop");
        assertEquals(List.of(), writer.startEvents(), "Listeners that do not ask for start events get none");
        assertEquals(List.of(), writer.startedEvents());
        assertEquals(1, live.startedEvents().size(), "The transaction start still reaches the live listener");
        assertEquals(3, writer.events().size(), "Samples are delivered as before");
        SampleEvent first = live.events().get(0);
        assertEquals(first.getResult().getParentTransaction(), first.getStartedSample().getParentTransaction(),
                "The placeholder must point to the transaction the sampler runs in");
    }

    @Test
    void testParallelChildrenAccumulateIntoEnclosingTransaction() {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        ParallelController parallelController = new ParallelController();
        parallelController.setName("parallel");
        parallelController.setMaxParallel(3);
        parallelController.setEnabled(true);
        TransactionController branchTransaction = new TransactionController();
        branchTransaction.setName("branch-transaction");
        RecordingSampleListener listener = new RecordingSampleListener("results");
        AtomicInteger calls = new AtomicInteger();

        HashTree loopTree = testTree.add(loop);
        HashTree parallelTree = loopTree.add(transaction).add(parallelController);
        parallelTree.add(new ResultStatusSampler("branch-1", true, calls));
        parallelTree.add(new ResultStatusSampler("branch-2", true, calls));
        parallelTree.add(branchTransaction).add(new ResultStatusSampler("branch-3", true, calls));
        loopTree.add(listener);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("parallel-transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.run();

        List<SampleEvent> events = listener.events();
        SampleResult transactionResult = events.get(events.size() - 1).getResult();
        assertEquals("transaction", transactionResult.getSampleLabel());
        assertEquals("Number of samples in transaction : 3, number of failing samples : 0",
                transactionResult.getResponseMessage());
        SampleResult branchTransactionResult = events.stream()
                .map(SampleEvent::getResult)
                .filter(result -> "branch-transaction".equals(result.getSampleLabel()))
                .findFirst()
                .orElseThrow();
        assertEquals(transactionResult.getTransaction(), branchTransactionResult.getParentTransaction());
        for (SampleEvent event : events) {
            SampleResult result = event.getResult();
            if ("branch-3".equals(result.getSampleLabel())) {
                assertEquals(branchTransactionResult.getTransaction(), result.getParentTransaction());
            } else if (result.getSampleLabel().startsWith("branch-")) {
                assertEquals(transactionResult.getTransaction(), result.getParentTransaction(),
                        "Parallel children must belong to the transaction around the ParallelController");
            }
        }
    }

    @Test
    void testTransactionChildKeepsSamplerSourcePathForMetadataListener() {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        TransactionController transactionController = new TransactionController();
        transactionController.setName("transaction");
        AtomicInteger childCalls = new AtomicInteger();
        ResultStatusSampler childSampler = new ResultStatusSampler("har-linked-child", true, childCalls);
        ResultCollector resultCollector = new ResultCollector();
        MetadataNeedingVisualizer visualizer = new MetadataNeedingVisualizer();
        resultCollector.setListener(visualizer);

        testTree.add(loop);
        testTree.add(loop, transactionController);
        testTree.add(transactionController, childSampler);
        testTree.add(transactionController, resultCollector);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("transaction-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.run();

        assertEquals(1, childCalls.get(), "Child sampler should run once");
        List<SampleResult> results = visualizer.results();
        assertEquals(List.of("har-linked-child", "transaction"),
                results.stream().map(SampleResult::getSampleLabel).toList(),
                "A listener in scope of the transaction must receive its samples too");
        SampleResult childResult = results.get(0);
        SampleResult transactionResult = results.get(1);
        assertEquals(transactionResult.getTransaction(), childResult.getParentTransaction());
        assertTrue(transactionResult.hasJMeterVariables(),
                "The legacy metadata capability should continue requesting variable snapshots");
        List<SampleResult.TestElementPathEntry> childPath = childResult.getSourceTestElementPath();
        assertFalse(childPath.isEmpty(), "Transaction child should keep source metadata for visual tree lookup");
        SampleResult.TestElementPathEntry source = childPath.get(childPath.size() - 1);
        assertEquals(childSampler.getClass().getName(), source.className());
        assertEquals(childSampler.getName(), source.name());
        List<SampleResult.TestElementPathEntry> transactionPath = transactionResult.getSourceTestElementPath();
        assertEquals(TransactionController.class.getName(),
                transactionPath.get(transactionPath.size() - 1).className());
        assertEquals(List.of("transaction"),
                visualizer.startedTransactions().stream().map(SampleResult::getSampleLabel).toList(),
                "The visualizer must be told when the transaction starts");
    }

    @Test
    void threadCleanupActionsAreDeduplicatedAndRunAfterThreadListeners() {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        AtomicBoolean listenerFinished = new AtomicBoolean();
        testTree.add(loop);
        testTree.add(loop, new RecordingThreadListener(listenerFinished));

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("cleanup-action-thread");
        jMeterThread.setThreadGroup(threadGroup);

        Object cleanupKey = new Object();
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicBoolean cleanupRanAfterListener = new AtomicBoolean();
        jMeterThread.registerThreadCleanup(cleanupKey, () -> {
            cleanupCalls.incrementAndGet();
            cleanupRanAfterListener.set(listenerFinished.get());
        });
        jMeterThread.registerThreadCleanup(cleanupKey, () -> cleanupCalls.addAndGet(100));

        jMeterThread.run();

        assertEquals(1, cleanupCalls.get());
        assertTrue(cleanupRanAfterListener.get());
    }

    private static LoopController createLoopController() {
        LoopController result = new LoopController();
        result.setLoops(LoopController.INFINITE_LOOP_COUNT);
        result.setEnabled(true);
        return result;
    }

    private static ThreadGroupPacingFixture createThreadGroupPacingFixture() {
        HashTree testTree = new HashTree();
        LoopController loop = createLoopController();
        testTree.add(loop);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("thread-group-pacing-thread");
        jMeterThread.setThreadGroup(threadGroup);
        return new ThreadGroupPacingFixture(threadGroup, jMeterThread);
    }

    private static IterationVariableSampler runTwoIterationsWithSameUserSetting(boolean sameUserOnNextIteration) {
        HashTree testTree = new HashTree();
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        IterationVariableSampler sampler = new IterationVariableSampler();
        sampler.setName("iteration variable sampler");
        testTree.add(loop);
        testTree.add(loop, sampler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterVariables initialVariables = new JMeterVariables();
        initialVariables.put("initial", "seed");

        JMeterThread jMeterThread = new JMeterThread(
                testTree, threadGroup, new ListenerNotifier(), sameUserOnNextIteration);
        jMeterThread.setThreadName("iteration-variable-thread");
        jMeterThread.setThreadGroup(threadGroup);
        jMeterThread.putVariables(initialVariables);
        jMeterThread.run();
        return sampler;
    }

    private record ThreadGroupPacingFixture(ThreadGroup threadGroup, JMeterThread jMeterThread) {
    }

    private static DummySampler createSampler() {
        DummySampler result = new DummySampler();
        result.setName("Call me");
        return result;
    }

    private static JMeterContext processParallelSamplerDirect(
            HashTree testTree,
            ParallelController parallelController,
            JMeterThread jMeterThread,
            ThreadGroup threadGroup) throws Exception {
        JMeterContext context = JMeterContextService.getContext();
        JMeterVariables variables = new JMeterVariables();
        context.setVariables(variables);
        context.setThread(jMeterThread);
        context.setThreadGroup(threadGroup);

        TestCompiler.initialize();
        Field compilerField = JMeterThread.class.getDeclaredField("compiler");
        compilerField.setAccessible(true);
        testTree.traverse((TestCompiler) compilerField.get(jMeterThread));

        parallelController.initialize();
        ParallelControllerSampler parallelSampler = (ParallelControllerSampler) parallelController.next();
        Method processParallelSampler = JMeterThread.class.getDeclaredMethod(
                "processParallelSampler",
                ParallelControllerSampler.class,
                JMeterContext.class,
                Function.class);
        processParallelSampler.setAccessible(true);
        processParallelSampler.invoke(jMeterThread, parallelSampler, context, Function.identity());
        return context;
    }

    private static void assertForkBookkeepingEventuallyEmpty(JMeterThread jMeterThread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (privateCollectionSize(jMeterThread, "forkTasks") == 0
                    && privateCollectionSize(jMeterThread, "forkExecutors") == 0
                    && privateMapSize(jMeterThread, "activeForkTasksByController") == 0
                    && privateMapSize(jMeterThread, "forkWorkers") == 0
                    && privateCollectionSize(jMeterThread, "forkExecutions") == 0
                    && privateCollectionSize(jMeterThread, "forksCancelledWithoutResult") == 0) {
                return;
            }
            Thread.sleep(20);
        }

        assertEquals(0, privateCollectionSize(jMeterThread, "forkTasks"),
                "Completed fork futures should be removed while the thread is still running");
        assertEquals(0, privateCollectionSize(jMeterThread, "forkExecutors"),
                "Completed fork executors should be removed while the thread is still running");
        assertEquals(0, privateMapSize(jMeterThread, "activeForkTasksByController"),
                "Completed fork controller mappings should be removed while the thread is still running");
        assertEquals(0, privateMapSize(jMeterThread, "forkWorkers"));
        assertEquals(0, privateCollectionSize(jMeterThread, "forkExecutions"));
        assertEquals(0, privateCollectionSize(jMeterThread, "forksCancelledWithoutResult"));
    }

    private static int privateCollectionSize(JMeterThread target, String fieldName) throws Exception {
        Field field = JMeterThread.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Collection<?>) field.get(target)).size();
    }

    private static int privateMapSize(JMeterThread target, String fieldName) throws Exception {
        Field field = JMeterThread.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(target)).size();
    }

    private static JMeterProperty functionProperty(String propertyName, String value) throws Exception {
        ReplaceStringWithFunctions transformer =
                new ReplaceStringWithFunctions(new CompoundVariable(), new HashMap<>());
        JMeterProperty property = transformer.transformValue(new StringProperty(propertyName, value));
        property.setRunningVersion(true);
        return property;
    }

    private static Timer createConstantTimer(long delay) {
        DummyTimer timer = new DummyTimer();
        timer.setEnabled(true);
        timer.setDelay(delay);
        timer.setName("Long delay");
        return timer;
    }
}
