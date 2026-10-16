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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.action.validation.TreeClonerForValidation;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ValidationCheckpointsTest extends JMeterTestCase {
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();

    private static final List<SampleResult> RESULTS = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        ValidationCheckpoints.clear();
        CALLS.clear();
        RESULTS.clear();
        JMeterContextService.setValidationRun(true);
    }

    @AfterEach
    void cleanup() {
        ValidationCheckpoints.clear();
        JMeterContextService.setValidationRun(false);
    }

    public static class RecordingSampler extends AbstractSampler {
        @Override
        public SampleResult sample(Entry entry) {
            JMeterContext context = JMeterContextService.getContext();
            JMeterVariables variables = context.getVariables();
            String mode = getPropertyAsString("mode");
            if (mode.equals("login")) {
                variables.put("token", getPropertyAsString("token", "saved-token"));
                variables.put("pre", "0");
            }
            CALLS.add(mode + ":" + variables.get("token") + ":" + variables.get("pre"));
            SampleResult result = new SampleResult();
            result.setSampleLabel(mode);
            result.setSuccessful(!getPropertyAsBoolean("fail"));
            RESULTS.add(result);
            if (mode.equals("original") || mode.equals("edited")) {
                variables.put("token", "changed-after-sampler");
            }
            return result;
        }
    }

    public static class IncrementPreProcessor extends AbstractTestElement implements PreProcessor {
        @Override
        public void process() {
            JMeterContext context = JMeterContextService.getContext();
            assertEquals("login", context.getPreviousResult().getSampleLabel());
            JMeterVariables vars = context.getVariables();
            vars.put("pre", Integer.toString(Integer.parseInt(vars.get("pre")) + 1));
        }
    }

    public static class EditedExtractor extends AbstractTestElement
            implements org.apache.jmeter.processor.PostProcessor {
        @Override
        public void process() {
            JMeterContextService.getContext().getVariables().put("token", "new-extracted-token");
        }
    }

    public static class BrokenRestore extends org.apache.jmeter.config.ConfigTestElement
            implements ValidationCheckpointParticipant {
        @Override
        public Object captureValidationState() {
            return true;
        }

        @Override
        public void restoreValidationState(Object state) {
            throw new IllegalStateException("CSV file changed since checkpoint");
        }
    }

    @Test
    @Timeout(15)
    void replayAfterFailurePreservesItUntilTheFailedRequestIsRetried() throws Exception {
        ListedHashTree source = new ListedHashTree();
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        group.setValidationStopOnError(false);
        var flow = source.add(new TestPlan()).add(group);
        RecordingSampler login = sampler("login");
        RecordingSampler failed = sampler("original");
        failed.setProperty("fail", true);
        RecordingSampler later = sampler("tail");
        flow.add(login);
        flow.add(failed);
        flow.add(later);
        var initial = cloneTree(source);
        ValidationCheckpoints.rememberStructure(initial);
        run(initial);
        assertTrue(ValidationCheckpoints.canResumeFailedSampler(group));
        var failedCheckpoint = ValidationCheckpoints.get(group);
        var replayLater = cloneTree(source);
        ValidationCheckpoints.prepareResume(replayLater, ValidationCheckpoints.get(later));
        run(replayLater);
        assertEquals(failedCheckpoint, ValidationCheckpoints.get(group));
        failed.setProperty("fail", false);
        var replayEarlier = cloneTree(source);
        ValidationCheckpoints.prepareResume(replayEarlier, ValidationCheckpoints.get(login));
        run(replayEarlier);
        org.junit.jupiter.api.Assertions.assertFalse(ValidationCheckpoints.canResumeFailedSampler(group));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Timeout(15)
    void failedRestoreReportsErrorAndStopsOnlyItsGroup(boolean stopOnError) throws Exception {
        ListedHashTree source = new ListedHashTree();
        TestPlan plan = new TestPlan();
        plan.setSerialized(true);
        var planTree = source.add(plan);
        ThreadGroup broken = new ThreadGroup();
        broken.setName("broken");
        broken.setSamplerController(new LoopController());
        broken.setValidationStopOnError(stopOnError);
        var flow = planTree.add(broken);
        flow.add(new BrokenRestore());
        RecordingSampler target = sampler("original");
        flow.add(target);
        flow.add(sampler("must-not-run"));
        ThreadGroup healthy = new ThreadGroup();
        healthy.setName("healthy");
        healthy.setSamplerController(new LoopController());
        RecordingSampler other = sampler("other");
        planTree.add(healthy).add(other);
        var initial = cloneTree(source);
        ValidationCheckpoints.rememberStructure(initial);
        run(initial);
        var resumed = cloneTree(source);
        ValidationCheckpoints.prepareResume(resumed,
                List.of(ValidationCheckpoints.get(target), ValidationCheckpoints.get(other)));
        var listener = new org.apache.jmeter.test.samplers.CollectSamplesListener();
        resumed.add(resumed.getArray()[0], listener);
        CALLS.clear();
        run(resumed);
        assertEquals(List.of("other:null:null"), CALLS);
        assertEquals(2, listener.getEvents().size());
        SampleResult failure = listener.getEvents().get(0).getResult();
        org.junit.jupiter.api.Assertions.assertFalse(failure.isSuccessful());
        assertEquals("VALIDATION_RESTORE_FAILED", failure.getResponseCode());
        assertTrue(failure.getResponseMessage().contains("CSV file changed since checkpoint"));
        assertTrue(ValidationCheckpoints.canResumeFailedSampler(broken));
        org.junit.jupiter.api.Assertions.assertFalse(ValidationCheckpoints.canResumeFailedSampler(healthy));
    }

    @Test
    @Timeout(15)
    void resumesMultipleFailedGroupsWithTheirOwnVariablesAndSkipsSuccessfulGroups() throws Exception {
        ListedHashTree source = new ListedHashTree();
        TestPlan plan = new TestPlan();
        plan.setSerialized(true);
        var planTree = source.add(plan);
        var groups = new java.util.ArrayList<ThreadGroup>();
        var targets = new java.util.ArrayList<RecordingSampler>();
        for (String name : List.of("one", "two", "passed")) {
            ThreadGroup group = new ThreadGroup();
            group.setName(name);
            group.setSamplerController(new LoopController());
            group.setValidationStopOnError(true);
            groups.add(group);
            var flow = planTree.add(group);
            RecordingSampler login = sampler("login");
            login.setProperty("token", name);
            flow.add(login);
            RecordingSampler target = sampler("original");
            target.setProperty("fail", !name.equals("passed"));
            targets.add(target);
            flow.add(target);
            flow.add(sampler("tail"));
        }
        var initial = cloneTree(source);
        ValidationCheckpoints.rememberStructure(initial);
        run(initial);
        var checkpoints = List.of(ValidationCheckpoints.get(groups.get(0)), ValidationCheckpoints.get(groups.get(1)));
        for (RecordingSampler target : targets) {
            target.setProperty("mode", "edited");
            target.setProperty("fail", false);
        }
        // A still-failing first group must not prevent the next group from resuming.
        targets.get(0).setProperty("fail", true);
        var resumed = cloneTree(source);
        ValidationCheckpoints.prepareResume(resumed, checkpoints);
        CALLS.clear();
        run(resumed);
        assertEquals(List.of("edited:one:0", "edited:two:0", "tail:changed-after-sampler:0"), CALLS);
        assertTrue(ValidationCheckpoints.canResumeFailedSampler(groups.get(0)));
        org.junit.jupiter.api.Assertions.assertFalse(ValidationCheckpoints.canResumeFailedSampler(groups.get(1)));
        org.junit.jupiter.api.Assertions.assertFalse(ValidationCheckpoints.canResumeFailedSampler(groups.get(2)));
    }

    @Test
    @Timeout(15)
    void resumesEditedSamplerWithPreSamplerVariablesAndContinuesWithoutLogin() throws Exception {
        ThreadGroup group = new ThreadGroup();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        group.setSamplerController(loop);
        group.setNumThreads(1);
        ListedHashTree original = new ListedHashTree();
        var groupTree = original.add(new TestPlan()).add(group);
        groupTree.add(sampler("login"));
        // A nested simple controller preserves scope when the prefix is removed.
        var scope = groupTree.add(new GenericController());
        RecordingSampler target = sampler("original");
        scope.add(target).add(new IncrementPreProcessor());
        groupTree.add(sampler("tail"));
        ListedHashTree first = cloneTree(original);
        ValidationCheckpoints.rememberStructure(first);
        run(first);
        assertEquals(List.of("login:saved-token:0", "original:saved-token:1",
                "tail:changed-after-sampler:1"), CALLS);
        SampleResult selectedResult = RESULTS.get(1);
        var replayTarget = ValidationCheckpoints.getReplayTarget(selectedResult);
        assertEquals(group, replayTarget.group());
        ValidationCheckpoints.Checkpoint checkpoint = replayTarget.checkpoint();
        assertEquals(ValidationCheckpoints.get(target), checkpoint);
        target.setProperty("mode", "edited");
        IncrementPreProcessor added = new IncrementPreProcessor();
        added.setName("added while editing");
        scope.getTree(target).add(added);
        scope.getTree(target).add(new EditedExtractor());
        ListedHashTree resumed = cloneTree(original);
        ValidationCheckpoints.prepareResume(resumed, checkpoint);
        CALLS.clear();
        run(resumed);
        assertEquals(List.of("edited:saved-token:2", "tail:new-extracted-token:2"), CALLS);
        // Repeated resume starts from the same pre-sampler state, not the previous run's end.
        ListedHashTree repeated = cloneTree(original);
        ValidationCheckpoints.prepareResume(repeated, ValidationCheckpoints.getReplayTarget(selectedResult).checkpoint());
        CALLS.clear();
        run(repeated);
        assertEquals(List.of("edited:saved-token:2", "tail:new-extracted-token:2"), CALLS);
        scope.add(sampler("new"));
        assertThrows(IllegalStateException.class,
                () -> ValidationCheckpoints.prepareResume(cloneTree(original), checkpoint));
        assertEquals("", target.getPropertyAsString("__breaktest_validation_id"));
        ValidationCheckpoints.clear();
        assertThrows(IllegalStateException.class, () -> ValidationCheckpoints.get(target));
        assertThrows(IllegalStateException.class, () -> ValidationCheckpoints.getReplayTarget(selectedResult));
    }

    @Test
    void unsupportedObjectVariablesAndControllersCannotBeResumed() {
        RecordingSampler sampler = sampler("sample");
        ListedHashTree tree = new ListedHashTree();
        tree.add(new GenericController()).add(sampler);
        ValidationCheckpoints.register(sampler, sampler);
        JMeterContext context = JMeterContextService.getContext();
        context.setVariables(new JMeterVariables());
        context.getVariables().putObject("connection", new Object());
        ValidationCheckpoints.beforeSampler(tree, sampler, context);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> ValidationCheckpoints.get(sampler)).getMessage().contains("connection"));
        context.getVariables().clear();
        tree.clear();
        tree.add(new LoopController()).add(sampler);
        ValidationCheckpoints.beforeSampler(tree, sampler, context);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> ValidationCheckpoints.get(sampler)).getMessage().contains("controller"));
    }

    private static RecordingSampler sampler(String mode) {
        RecordingSampler sampler = new RecordingSampler();
        sampler.setName(mode);
        sampler.setProperty("mode", mode);
        return sampler;
    }

    private static ListedHashTree cloneTree(ListedHashTree source) {
        TreeClonerForValidation cloner = new TreeClonerForValidation();
        source.traverse(cloner);
        return cloner.getClonedTree();
    }

    private static void run(ListedHashTree tree) throws Exception {
        JMeterContextService.setValidationRun(true);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(tree);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(4));
    }
}
