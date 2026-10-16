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

package org.apache.jmeter.gui.action.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.time.Duration;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.test.samplers.CollectSamplesListener;
import org.apache.jmeter.test.samplers.ThreadSleep;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TreeClonerForValidationTest extends JMeterTestCase {
    private static final String CLOSED_MODEL_SCHEDULE = """
            threadsPhase(10, 30)
            threadsPhase(50, 60)
            """;

    public static class ValidationSampler extends org.apache.jmeter.samplers.AbstractSampler {
        @Override
        public org.apache.jmeter.samplers.SampleResult sample(org.apache.jmeter.samplers.Entry entry) {
            var result = new org.apache.jmeter.samplers.SampleResult();
            result.setSampleLabel(getName());
            result.setSuccessful(!getName().equals("failure"));
            return result;
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "true, stop", "false, stop", "true, continue", "false, continue",
            "true, stoptest", "false, stoptest", "true, stoptestnow", "false, stoptestnow",
            "true, stopthread", "false, stopthread", "true, startnextloop", "false, startnextloop"
    })
    @Timeout(10)
    void validationErrorsDoNotStopOtherGroups(boolean sequential, String policy) throws Exception {
        TestPlan plan = new TestPlan();
        plan.setSerialized(sequential);
        ThreadGroup first = closedThreadGroup();
        first.setName("first");
        if (policy.equals("stop") || policy.equals("continue")) {
            first.setValidationStopOnError(policy.equals("stop"));
        } else {
            first.setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, policy);
        }
        ThreadGroup second = closedThreadGroup();
        second.setName("second");
        ListedHashTree tree = new ListedHashTree();
        var planTree = tree.add(plan);
        var firstTree = planTree.add(first);
        firstTree.add(validationSampler("failure"));
        firstTree.add(validationSampler("first-tail"));
        planTree.add(second).add(validationSampler("second"));
        TreeClonerForValidation cloner = new TreeClonerForValidation();
        tree.traverse(cloner);
        var validation = cloner.getClonedTree();
        CollectSamplesListener listener = new CollectSamplesListener();
        validation.add(validation.getArray()[0], listener);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(validation);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(5));
        var labels = listener.getEvents().stream().map(event -> event.getResult().getSampleLabel()).toList();
        assertEquals(1, java.util.Collections.frequency(labels, "failure"));
        assertEquals(1, java.util.Collections.frequency(labels, "second"));
        assertEquals(policy.equals("continue") ? 1 : 0, java.util.Collections.frequency(labels, "first-tail"));
        if (sequential) {
            assertEquals("second", labels.get(labels.size() - 1));
        }
        if (!policy.equals("stop") && !policy.equals("continue")) {
            assertEquals(policy, first.getPropertyAsString(AbstractThreadGroup.ON_SAMPLE_ERROR));
        }
    }

    private static ValidationSampler validationSampler(String name) {
        ValidationSampler sampler = new ValidationSampler();
        sampler.setName(name);
        return sampler;
    }

    @Test
    void customClosedModelValidationCloneUsesStandardValidationSettings() {
        ThreadGroup original = closedThreadGroup();
        original.setClosedModelMode(ThreadGroup.CLOSED_MODEL_MODE_CUSTOM);
        original.setClosedModelSchedule(CLOSED_MODEL_SCHEDULE);

        ThreadGroup clone = cloneForValidation(original);

        assertNotSame(original, clone);
        assertEquals(ThreadGroup.CLOSED_MODEL_MODE_STANDARD, clone.getClosedModelMode());
        assertEquals("", clone.getClosedModelSchedule());
        assertEquals(TreeClonerForValidation.VALIDATION_NUMBER_OF_THREADS, clone.getNumThreads());
        assertFalse(clone.getScheduler());
        assertEquals(0, clone.getDelay());
        assertEquals(TreeClonerForValidation.VALIDATION_ITERATIONS,
                ((LoopController) clone.getSamplerController()).getLoops());

        assertEquals(ThreadGroup.CLOSED_MODEL_MODE_CUSTOM, original.getClosedModelMode());
        assertEquals(CLOSED_MODEL_SCHEDULE, original.getClosedModelSchedule());
        assertEquals(50, original.getNumThreads());
        assertEquals(7, ((LoopController) original.getSamplerController()).getLoops());
    }

    @Test
    void legacyClosedModelPhasesAreDisabledInValidationClone() {
        ThreadGroup original = closedThreadGroup();
        original.setClosedModelSchedule(CLOSED_MODEL_SCHEDULE);

        ThreadGroup clone = cloneForValidation(original);

        assertEquals(ThreadGroup.CLOSED_MODEL_MODE_STANDARD, clone.getClosedModelMode());
        assertEquals("", clone.getClosedModelSchedule());
        assertEquals(TreeClonerForValidation.VALIDATION_NUMBER_OF_THREADS, clone.getNumThreads());

        assertEquals(ThreadGroup.CLOSED_MODEL_MODE_CUSTOM, original.getClosedModelMode());
        assertEquals(CLOSED_MODEL_SCHEDULE, original.getClosedModelSchedule());
    }

    @Test
    void standardClosedModelStillUsesStandardValidationSettings() {
        ThreadGroup original = closedThreadGroup();

        ThreadGroup clone = cloneForValidation(original);

        assertEquals(ThreadGroup.CLOSED_MODEL_MODE_STANDARD, clone.getClosedModelMode());
        assertEquals(TreeClonerForValidation.VALIDATION_NUMBER_OF_THREADS, clone.getNumThreads());
        assertFalse(clone.getScheduler());
        assertEquals(0, clone.getDelay());
        assertEquals(TreeClonerForValidation.VALIDATION_ITERATIONS,
                ((LoopController) clone.getSamplerController()).getLoops());

        assertEquals(12, original.getNumThreads());
        assertEquals(7, ((LoopController) original.getSamplerController()).getLoops());
    }

    @Test
    void openModelValidationCloneUsesStandardValidationSettings() {
        ThreadGroup original = closedThreadGroup();
        original.setThreadGroupModel(ThreadGroup.MODEL_OPEN);
        original.setOpenModelSchedule("rate(100 / sec) even_arrivals(5 min) pause(1 hour)");

        ThreadGroup clone = cloneForValidation(original);

        assertEquals(ThreadGroup.MODEL_CLOSED, clone.getThreadGroupModel());
        assertEquals(ThreadGroup.CLOSED_MODEL_MODE_STANDARD, clone.getClosedModelMode());
        assertEquals("", clone.getOpenModelSchedule());
        assertEquals(TreeClonerForValidation.VALIDATION_NUMBER_OF_THREADS, clone.getNumThreads());
        assertEquals(TreeClonerForValidation.VALIDATION_ITERATIONS,
                ((LoopController) clone.getSamplerController()).getLoops());

        assertEquals(ThreadGroup.MODEL_OPEN, original.getThreadGroupModel());
        assertEquals("rate(100 / sec) even_arrivals(5 min) pause(1 hour)", original.getOpenModelSchedule());
    }

    @Test
    @Timeout(5)
    void openModelValidationRunsOnceAndTerminates() throws Exception {
        ThreadGroup original = closedThreadGroup();
        original.setThreadGroupModel(ThreadGroup.MODEL_OPEN);
        original.setOpenModelSchedule("rate(100 / sec) even_arrivals(5 min) pause(1 hour)");

        assertEquals(1, runValidationAndCountSamples(original));
    }

    @Test
    void legacyOpenModelValidationCloneUsesStandardValidationSettings() {
        OpenModelThreadGroup original = legacyOpenModelThreadGroup();

        AbstractThreadGroup clonedElement = cloneElementForValidation(original);
        ThreadGroup clone = assertInstanceOf(ThreadGroup.class, clonedElement);

        assertEquals(original.getName(), clone.getName());
        assertEquals(ThreadGroup.MODEL_CLOSED, clone.getThreadGroupModel());
        assertEquals(TreeClonerForValidation.VALIDATION_NUMBER_OF_THREADS, clone.getNumThreads());
        assertEquals(TreeClonerForValidation.VALIDATION_ITERATIONS,
                ((LoopController) clone.getSamplerController()).getLoops());

        assertEquals("rate(100 / sec) even_arrivals(5 min) pause(1 hour)", original.getScheduleString());
    }

    @Test
    @Timeout(5)
    void legacyOpenModelValidationRunsOnceAndTerminates() throws Exception {
        assertEquals(1, runValidationAndCountSamples(legacyOpenModelThreadGroup()));
    }

    private static int runValidationAndCountSamples(AbstractThreadGroup original) throws Exception {
        TestPlan testPlan = new TestPlan();
        ThreadSleep sampler = new ThreadSleep();
        sampler.setDurationMillis(10);

        ListedHashTree tree = new ListedHashTree();
        HashTree testPlanTree = tree.add(testPlan);
        HashTree threadGroupTree = testPlanTree.add(original);
        threadGroupTree.add(sampler);

        TreeClonerForValidation cloner = new TreeClonerForValidation();
        tree.traverse(cloner);
        ListedHashTree validationTree = cloner.getClonedTree();
        CollectSamplesListener listener = new CollectSamplesListener();
        validationTree.add(validationTree.getArray()[0], listener);

        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(validationTree);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(3));

        return listener.getEvents().size();
    }

    private static ThreadGroup closedThreadGroup() {
        LoopController controller = new LoopController();
        controller.setLoops(7);
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setThreadGroupModel(ThreadGroup.MODEL_CLOSED);
        threadGroup.setNumThreads(12);
        threadGroup.setScheduler(true);
        threadGroup.setDelay(15);
        threadGroup.setSamplerController(controller);
        return threadGroup;
    }

    private static ThreadGroup cloneForValidation(ThreadGroup original) {
        return (ThreadGroup) cloneElementForValidation(original);
    }

    private static AbstractThreadGroup cloneElementForValidation(AbstractThreadGroup original) {
        ListedHashTree tree = new ListedHashTree();
        tree.add(original);
        TreeClonerForValidation cloner = new TreeClonerForValidation();
        tree.traverse(cloner);
        return (AbstractThreadGroup) cloner.getClonedTree().getArray()[0];
    }

    private static OpenModelThreadGroup legacyOpenModelThreadGroup() {
        OpenModelThreadGroup threadGroup = new OpenModelThreadGroup();
        threadGroup.setName("Legacy Open Model Thread Group");
        threadGroup.setScheduleString("rate(100 / sec) even_arrivals(5 min) pause(1 hour)");
        return threadGroup;
    }
}
