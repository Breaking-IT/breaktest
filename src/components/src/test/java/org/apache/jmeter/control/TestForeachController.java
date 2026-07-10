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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        controller.addTestElement(new RecordingSampler("child", new CountDownLatch(0)));
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
        controller.addTestElement(new RecordingSampler("child", new CountDownLatch(0)));
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
        child.addTestElement(new RecordingSampler("child", new CountDownLatch(0)));
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
        CountDownLatch completed = new CountDownLatch(3);
        RecordingSampler sampler = new RecordingSampler("child", completed);

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForeachController foreach = parallelForEachController();
        foreach.addTestElement(sampler);
        testTree.add(loop);
        testTree.add(loop, foreach);
        testTree.add(foreach, sampler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        variables.putObject("input_3", "three");

        JMeterThread thread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        thread.setThreadName("parallel-foreach-thread");
        thread.setThreadGroup(threadGroup);
        thread.putVariables(variables);
        thread.run();

        assertTrue(completed.await(5, TimeUnit.SECONDS), "All ForEach items should run");
        assertEquals(Set.of("one", "two", "three"), sampler.values);
        assertEquals(2, sampler.maxActive.get(), "Max parallel executions should be honored");
        assertNull(variables.getObject("item"), "ForEach output value should stay branch-local");
    }

    @Test
    void parallelForEachRunsParentTransactionControllerChildren() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(3);
        RecordingSampler sampler = new RecordingSampler("transaction-child", completed);
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        transaction.setGenerateParentSample(true);
        transaction.setEnabled(true);
        transaction.addTestElement(sampler);

        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForeachController foreach = parallelForEachController();
        foreach.addTestElement(transaction);
        testTree.add(loop);
        testTree.add(loop, foreach);
        testTree.add(foreach, transaction);
        testTree.add(transaction, sampler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        variables.putObject("input_3", "three");

        JMeterThread thread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        thread.setThreadName("parallel-foreach-transaction-thread");
        thread.setThreadGroup(threadGroup);
        thread.putVariables(variables);
        thread.run();

        assertTrue(completed.await(5, TimeUnit.SECONDS), "Every transaction child should run");
        assertEquals(Set.of("one", "two", "three"), sampler.values);
    }

    @Test
    void parallelForEachKeepsTransactionMappingsBranchScoped() {
        ForeachController controller = parallelForEachController();
        TransactionController transaction = new TransactionController();
        transaction.setName("transaction");
        transaction.addTestElement(new RecordingSampler("child", new CountDownLatch(0)));
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

    private static void setVariables(ForeachController controller, JMeterVariables variables) {
        JMeterContext context = JMeterContextService.getContext();
        context.setVariables(variables);
        controller.setThreadContext(context);
    }

    private static final class RecordingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch completed;
        private final Set<String> values = ConcurrentHashMap.newKeySet();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        private RecordingSampler(String name, CountDownLatch completed) {
            setName(name);
            this.completed = completed;
        }

        @Override
        public SampleResult sample(Entry e) {
            int running = active.incrementAndGet();
            maxActive.accumulateAndGet(running, Math::max);
            try {
                JMeterContext context = JMeterContextService.getContext();
                values.add(context.getVariables().get("item"));
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
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
