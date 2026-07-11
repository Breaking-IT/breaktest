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

package org.apache.jmeter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class TestForeachController extends JMeterTestCase {

    @Test
    void parallelForEachReturnsOneParallelSamplerForAllItems() {
        ForeachController controller = parallelForEachController();
        controller.addTestElement(new RecordingSampler("child"));
        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        variables.putObject("input_3", "three");
        setVariables(controller, variables);
        controller.initialize();

        Sampler sampler = controller.next();

        ParallelControllerSampler parallelSampler = assertInstanceOf(ParallelControllerSampler.class, sampler);
        assertEquals(controller, parallelSampler.getController());
        assertEquals(2, parallelSampler.getMaxParallel());
        assertEquals(3, parallelSampler.getBranchCount());
        assertEquals(3, parallelSampler.getBranches().size());
        assertNull(controller.next());
    }

    @Test
    void parallelForEachCountsOnlyAvailableItemsWithinEndIndex() {
        ForeachController controller = parallelForEachController();
        controller.setEndIndex("3");
        controller.addTestElement(new RecordingSampler("child"));
        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        variables.putObject("input_4", "four");
        setVariables(controller, variables);
        controller.initialize();

        ParallelControllerSampler parallelSampler =
                assertInstanceOf(ParallelControllerSampler.class, controller.next());

        assertEquals(2, parallelSampler.getBranchCount());
    }

    @Test
    void parallelForEachCreatesBranchesLazily() {
        ForeachController controller = parallelForEachController();
        CountingController.cloneCalls.set(0);
        CountingController child = new CountingController();
        child.addTestElement(new RecordingSampler("child"));
        controller.addTestElement(child);
        JMeterVariables variables = new JMeterVariables();
        for (int i = 1; i <= 10_000; i++) {
            variables.putObject("input_" + i, "value-" + i);
        }
        setVariables(controller, variables);
        controller.initialize();

        ParallelControllerSampler parallelSampler =
                assertInstanceOf(ParallelControllerSampler.class, controller.next());

        assertEquals(10_000, parallelSampler.getBranchCount());
        assertEquals(0, CountingController.cloneCalls.get(),
                "Creating the ForEach marker must not clone every branch");
        parallelSampler.getParallelBranch(0);
        assertEquals(1, CountingController.cloneCalls.get(),
                "Only requested branches should be materialized");
    }

    @Test
    void parallelForEachRunsItemsConcurrentlyWithBranchLocalOutputValue() throws InterruptedException {
        RecordingSampler.reset(3);
        RecordingSampler sampler = new RecordingSampler("child");

        HashTree testTree = new ListedHashTree();
        LoopController loop = singleIterationLoop();
        ForeachController foreach = parallelForEachController();
        foreach.addTestElement(sampler);
        testTree.add(loop);
        testTree.add(loop, foreach);
        testTree.add(foreach, sampler);

        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        variables.putObject("input_3", "three");

        runTestPlan(testTree, variables, "parallel-foreach-thread");

        assertTrue(RecordingSampler.completed.await(5, TimeUnit.SECONDS), "All ForEach items should run");
        assertEquals(Set.of("one", "two", "three"), RecordingSampler.values);
        assertEquals(2, RecordingSampler.maxActive.get(), "Max parallel executions should be honored");
        assertNull(variables.getObject("item"), "ForEach output value should stay branch-local");
    }

    @Test
    void parallelForEachRunsParentTransactionControllerChildren() throws InterruptedException {
        RecordingSampler.reset(3);
        RecordingSampler sampler = new RecordingSampler("transaction-child");
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        transaction.setGenerateParentSample(true);
        transaction.setEnabled(true);
        transaction.addTestElement(sampler);

        HashTree testTree = new ListedHashTree();
        LoopController loop = singleIterationLoop();
        ForeachController foreach = parallelForEachController();
        foreach.addTestElement(transaction);
        testTree.add(loop);
        testTree.add(loop, foreach);
        testTree.add(foreach, transaction);
        testTree.add(transaction, sampler);

        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        variables.putObject("input_3", "three");

        runTestPlan(testTree, variables, "parallel-foreach-transaction-thread");

        assertTrue(RecordingSampler.completed.await(5, TimeUnit.SECONDS), "Every transaction child should run");
        assertEquals(Set.of("one", "two", "three"), RecordingSampler.values);
    }

    @Test
    void parallelForEachDoesNotShareSamplerConfigStateAcrossBranches() throws InterruptedException {
        ConfigProbeSampler.reset(2);
        ConfigProbeSampler sampler = new ConfigProbeSampler("probe");
        ConfigTestElement config = new ConfigTestElement();
        config.setName("defaults");
        config.setProperty(ConfigProbeSampler.CONFIG_PROP, "configured");

        HashTree testTree = new ListedHashTree();
        LoopController loop = singleIterationLoop();
        ForeachController foreach = parallelForEachController();
        foreach.addTestElement(sampler);
        testTree.add(loop);
        testTree.add(loop, foreach);
        testTree.add(foreach, config);
        testTree.add(foreach, sampler);

        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");

        runTestPlan(testTree, variables, "parallel-foreach-config-thread");

        assertTrue(ConfigProbeSampler.completed.await(5, TimeUnit.SECONDS), "Both ForEach items should run");
        assertEquals(List.of("configured", "configured"), ConfigProbeSampler.reads.get("one"),
                "The fast branch should see the merged config for its whole sample");
        assertEquals(List.of("configured", "configured"), ConfigProbeSampler.reads.get("two"),
                "Another branch finishing must not strip the merged config from a branch that is mid-sample");
    }

    @Test
    void parallelForEachKeepsLastSampleOkBranchLocal() throws InterruptedException {
        LastSampleOkProbeSampler.reset(2);
        LastSampleOkProbeSampler sampler = new LastSampleOkProbeSampler("probe");
        AtomicReference<String> lastSampleOkAfterForeach = new AtomicReference<>();

        HashTree testTree = new ListedHashTree();
        LoopController loop = singleIterationLoop();
        ForeachController foreach = parallelForEachController();
        foreach.addTestElement(sampler);
        testTree.add(loop);
        testTree.add(loop, foreach);
        testTree.add(foreach, sampler);
        testTree.add(loop, new VarCaptureSampler("after-foreach",
                JMeterThread.LAST_SAMPLE_OK, lastSampleOkAfterForeach));

        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");

        runTestPlan(testTree, variables, "parallel-foreach-last-sample-ok-thread");

        assertTrue(LastSampleOkProbeSampler.completed.await(5, TimeUnit.SECONDS), "Both ForEach items should run");
        assertEquals("true", LastSampleOkProbeSampler.lastSampleOkAtStart.get(),
                "A branch should inherit the last_sample_ok state from before the parallel section");
        assertEquals("true", LastSampleOkProbeSampler.lastSampleOkAtEnd.get(),
                "A failure in a sibling branch must not leak into this branch's last_sample_ok");
        assertEquals("false", lastSampleOkAfterForeach.get(),
                "After the parallel section the parent's last_sample_ok should reflect the failed branch");
    }

    @Test
    void parallelForEachKeepsTransactionMappingsBranchScoped() {
        ForeachController controller = parallelForEachController();
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        transaction.addTestElement(new RecordingSampler("child"));
        controller.addTestElement(transaction);
        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        setVariables(controller, variables);
        controller.initialize();

        ParallelControllerSampler parallelSampler =
                assertInstanceOf(ParallelControllerSampler.class, controller.next());
        ParallelControllerSampler.ParallelBranch firstBranch = parallelSampler.getParallelBranch(0);
        ParallelControllerSampler.ParallelBranch secondBranch = parallelSampler.getParallelBranch(1);
        TransactionController firstClone = firstTransactionController(firstBranch.getController());

        assertNotSame(transaction, firstClone);
        assertSame(transaction, firstBranch.getSourceTransactionController(firstClone));
        assertSame(firstClone, secondBranch.getSourceTransactionController(firstClone),
                "A branch must not retain another branch's transaction mappings");
    }

    private static TransactionController firstTransactionController(Controller branch) {
        for (org.apache.jmeter.testelement.TestElement child
                : ((GenericController) branch).getSubControllers()) {
            if (child instanceof TransactionController transactionController) {
                return transactionController;
            }
        }
        throw new AssertionError("Branch does not contain a TransactionController");
    }

    private static ForeachController parallelForEachController() {
        ForeachController controller = new ForeachController();
        controller.setName("foreach");
        controller.setInputVal("input");
        controller.setReturnVal("item");
        controller.setUseSeparator(true);
        controller.setParallel(true);
        controller.setMaxParallel(2);
        controller.setEnabled(true);
        return controller;
    }

    private static LoopController singleIterationLoop() {
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        return loop;
    }

    private static void runTestPlan(HashTree testTree, JMeterVariables variables, String threadName)
            throws InterruptedException {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);

        JMeterThread thread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        thread.setThreadName(threadName);
        thread.setThreadGroup(threadGroup);
        thread.putVariables(variables);
        // A dedicated thread keeps the engine thread-locals of this virtual user isolated from
        // thread-locals left behind by other tests on the JUnit worker thread.
        Thread runner = new Thread(thread, threadName);
        runner.start();
        runner.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(runner.isAlive(), "Test plan should complete");
    }

    private static void setVariables(ForeachController controller, JMeterVariables variables) {
        JMeterContext context = JMeterContextService.getContext();
        context.setVariables(variables);
        controller.setThreadContext(context);
    }

    private static SampleResult newSuccessResult(String label) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.sampleStart();
        result.setSuccessful(true);
        result.sampleEnd();
        return result;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Parallel branches clone their child samplers, so the recorded state is static:
     * every clone reports into the same sink.
     */
    public static final class RecordingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        static volatile CountDownLatch completed = new CountDownLatch(0);
        static final Set<String> values = ConcurrentHashMap.newKeySet();
        static final AtomicInteger active = new AtomicInteger();
        static final AtomicInteger maxActive = new AtomicInteger();

        static void reset(int expectedSamples) {
            completed = new CountDownLatch(expectedSamples);
            values.clear();
            active.set(0);
            maxActive.set(0);
        }

        public RecordingSampler() {
        }

        RecordingSampler(String name) {
            setName(name);
        }

        @Override
        public SampleResult sample(Entry e) {
            int running = active.incrementAndGet();
            maxActive.accumulateAndGet(running, Math::max);
            try {
                JMeterContext context = JMeterContextService.getContext();
                values.add(context.getVariables().get("item"));
                sleepQuietly(50);
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
            return newSuccessResult(getName());
        }
    }

    /**
     * Records the value of a merged config property at the start and the end of the sample.
     * The branch for item {@code one} finishes immediately, the other branches keep sampling,
     * which exposes state shared between branches.
     */
    public static final class ConfigProbeSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        static final String CONFIG_PROP = "test.mergedConfig";
        static volatile CountDownLatch completed = new CountDownLatch(0);
        static final Map<String, List<String>> reads = new ConcurrentHashMap<>();

        static void reset(int expectedSamples) {
            completed = new CountDownLatch(expectedSamples);
            reads.clear();
        }

        public ConfigProbeSampler() {
        }

        ConfigProbeSampler(String name) {
            setName(name);
        }

        @Override
        public SampleResult sample(Entry e) {
            try {
                String item = JMeterContextService.getContext().getVariables().get("item");
                String firstRead = getPropertyAsString(CONFIG_PROP);
                sleepQuietly("one".equals(item) ? 0 : 300);
                String secondRead = getPropertyAsString(CONFIG_PROP);
                reads.put(item, List.of(firstRead, secondRead));
            } finally {
                completed.countDown();
            }
            return newSuccessResult(getName());
        }
    }

    /**
     * The branch for item {@code one} fails quickly; the branch for item {@code two} watches
     * {@code JMeterThread.last_sample_ok} while the failure lands.
     */
    public static final class LastSampleOkProbeSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        static volatile CountDownLatch completed = new CountDownLatch(0);
        static final AtomicReference<String> lastSampleOkAtStart = new AtomicReference<>();
        static final AtomicReference<String> lastSampleOkAtEnd = new AtomicReference<>();

        static void reset(int expectedSamples) {
            completed = new CountDownLatch(expectedSamples);
            lastSampleOkAtStart.set(null);
            lastSampleOkAtEnd.set(null);
        }

        public LastSampleOkProbeSampler() {
        }

        LastSampleOkProbeSampler(String name) {
            setName(name);
        }

        @Override
        public SampleResult sample(Entry e) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            try {
                JMeterVariables variables = JMeterContextService.getContext().getVariables();
                String item = variables.get("item");
                if ("one".equals(item)) {
                    sleepQuietly(60);
                    result.setSuccessful(false);
                } else {
                    lastSampleOkAtStart.set(variables.get(JMeterThread.LAST_SAMPLE_OK));
                    sleepQuietly(150);
                    lastSampleOkAtEnd.set(variables.get(JMeterThread.LAST_SAMPLE_OK));
                    result.setSuccessful(true);
                }
            } finally {
                result.sampleEnd();
                completed.countDown();
            }
            return result;
        }
    }

    /** Captures a thread variable when the main flow reaches this sampler. */
    private static final class VarCaptureSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final String variableName;
        private final AtomicReference<String> capturedValue;

        private VarCaptureSampler(String name, String variableName, AtomicReference<String> capturedValue) {
            setName(name);
            this.variableName = variableName;
            this.capturedValue = capturedValue;
        }

        @Override
        public SampleResult sample(Entry e) {
            capturedValue.set(JMeterContextService.getContext().getVariables().get(variableName));
            return newSuccessResult(getName());
        }
    }

    public static final class CountingController extends GenericController {
        private static final long serialVersionUID = 1L;
        private static final AtomicInteger cloneCalls = new AtomicInteger();

        public CountingController() {
        }

        @Override
        public Object clone() {
            cloneCalls.incrementAndGet();
            return super.clone();
        }
    }
}
