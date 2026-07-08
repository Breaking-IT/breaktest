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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.junit.stubs.TestSampler;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.Test;

class TestForkController {

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

    @Test
    void nextReturnsOneForkSamplerForAllChildren() {
        ForkController controller = new ForkController();
        controller.setName("fork");
        controller.addTestElement(new TestSampler("one"));
        controller.addTestElement(new TestSampler("two"));
        controller.addTestElement(new TestSampler("three"));
        controller.initialize();

        Sampler sampler = controller.next();

        ForkControllerSampler forkSampler = assertInstanceOf(ForkControllerSampler.class, sampler);
        assertEquals("fork", forkSampler.getName());
        assertEquals("one", forkSampler.getController().next().getName());
        assertEquals("two", forkSampler.getController().next().getName());
        assertEquals("three", forkSampler.getController().next().getName());
        assertNull(forkSampler.getController().next());
        assertNull(controller.next());
    }

    @Test
    void controllerIsReusableAcrossParentIterations() {
        ForkController controller = new ForkController();
        controller.setName("fork");
        controller.addTestElement(new TestSampler("one"));
        controller.addTestElement(new TestSampler("two"));
        controller.initialize();

        for (int iteration = 0; iteration < 3; iteration++) {
            Sampler sampler = controller.next();
            ForkControllerSampler forkSampler = assertInstanceOf(ForkControllerSampler.class, sampler);
            assertEquals("one", forkSampler.getController().next().getName());
            assertEquals("two", forkSampler.getController().next().getName());
            assertNull(forkSampler.getController().next());
            assertNull(controller.next());
            assertFalse(controller.isDone());
        }
    }

    @Test
    void nextDoesNotDrainInfiniteChildLoop() {
        ForkController controller = new ForkController();
        controller.setName("fork");
        LoopController childLoop = new LoopController();
        childLoop.setName("child-loop");
        childLoop.setLoops(LoopController.INFINITE_LOOP_COUNT);
        childLoop.setEnabled(true);
        childLoop.addTestElement(new TestSampler("child"));
        controller.addTestElement(childLoop);
        controller.initialize();

        Sampler sampler = assertTimeoutPreemptively(Duration.ofSeconds(1), controller::next);

        ForkControllerSampler forkSampler = assertInstanceOf(ForkControllerSampler.class, sampler);
        assertEquals("child", forkSampler.getController().next().getName());
    }

    @Test
    void forkExecutionControllerCompletesTransactionChild() {
        ForkController controller = new ForkController();
        controller.setName("fork");
        TransactionController transactionController = new TransactionController();
        transactionController.setName("transaction");
        transactionController.setGenerateParentSample(true);
        transactionController.addTestElement(new TestSampler("child"));
        controller.addTestElement(transactionController);
        controller.initialize();

        ForkControllerSampler forkSampler = assertInstanceOf(ForkControllerSampler.class, controller.next());
        TransactionSampler first = assertInstanceOf(TransactionSampler.class, forkSampler.getController().next());
        assertEquals("child", first.getSubSampler().getName());
        TransactionSampler last = assertInstanceOf(TransactionSampler.class, forkSampler.getController().next());
        assertNull(last.getSubSampler());
        assertNull(forkSampler.getController().next());
    }

    @Test
    void forkExecutionControllerUpdatesNestedLoopIndex() {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        List<Integer> indexes = new ArrayList<>();
        ForkController controller = new ForkController();
        controller.setName("fork");
        LoopController forkLoop = new LoopController();
        forkLoop.setName("fork-loop");
        forkLoop.setLoops(3);
        forkLoop.addTestElement(new LoopIndexRecordingSampler("child", forkLoop.getName(), indexes));
        controller.addTestElement(forkLoop);
        controller.initialize();

        ForkControllerSampler forkSampler = assertInstanceOf(ForkControllerSampler.class, controller.next());
        Sampler sampler;
        while ((sampler = forkSampler.getController().next()) != null) {
            sampler.sample(null);
        }

        assertEquals(List.of(0, 1, 2), indexes);
    }

    @Test
    void parentControllerContinuesAfterForkSampler() {
        LoopController parent = new LoopController();
        parent.setLoops(1);
        parent.setContinueForever(false);
        ForkController fork = new ForkController();
        fork.setName("fork");
        fork.addTestElement(new TestSampler("fork-child"));
        parent.addTestElement(fork);
        parent.addTestElement(new TestSampler("after-fork"));
        parent.initialize();

        assertInstanceOf(ForkControllerSampler.class, parent.next());
        assertEquals("after-fork", parent.next().getName());
        assertNull(parent.next());
    }

    @Test
    void parentControllerCompletesWhenForkIsOnlyChild() {
        LoopController parent = new LoopController();
        parent.setLoops(1);
        parent.setContinueForever(false);
        ForkController fork = new ForkController();
        fork.setName("fork");
        fork.addTestElement(new TestSampler("fork-child"));
        parent.addTestElement(fork);
        parent.initialize();

        assertInstanceOf(ForkControllerSampler.class, parent.next());
        assertNull(parent.next());
        assertTrue(parent.isDone());
    }
}
