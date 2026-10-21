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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JMenuItem;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.validation.TreeClonerForValidation;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModuleValidationResumeTest extends JMeterTestCase {
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final List<SampleResult> RESULTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger FRAGMENT_CALLS = new AtomicInteger();

    private String testStartProperty;

    @BeforeEach
    void rememberGlobalState() {
        testStartProperty = JMeterUtils.getProperty("TESTSTART.MS");
    }

    @AfterEach
    void cleanup() {
        ValidationCheckpoints.clear();
        JMeterContextService.setValidationRun(false);
        // Engine runs publish TESTSTART.MS as a JMeter property; later tests must not see it.
        if (testStartProperty == null) {
            JMeterUtils.getJMeterProperties().remove("TESTSTART.MS");
        } else {
            JMeterUtils.setProperty("TESTSTART.MS", testStartProperty);
        }
        JMeterContextService.getContext().clear();
    }

    public static class Step extends AbstractSampler {
        @Override
        public SampleResult sample(Entry entry) {
            CALLS.add(getName());
            SampleResult result = new SampleResult();
            boolean successful = getName().equals("login") || getPropertyAsBoolean("fixed")
                    || FRAGMENT_CALLS.incrementAndGet() > 1;
            result.setSuccessful(successful);
            RESULTS.add(result);
            return result;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(15)
    void resumesFailedFragmentInvocationFromThreadGroupAfterEditing(boolean enabled) throws Exception {
        ValidationCheckpoints.clear();
        JMeterContextService.setValidationRun(true);
        CALLS.clear();
        RESULTS.clear();
        FRAGMENT_CALLS.set(0);
        ThreadGroup group = new ThreadGroup();
        group.setThreadGroupId("original-group");
        group.setSamplerController(new LoopController());
        TestFragmentController fragment = new TestFragmentController();
        fragment.setName("shared fragment");
        fragment.setEnabled(enabled);
        JMeterTreeNode fragmentNode = new JMeterTreeNode(fragment, null);
        Step step = new Step();
        step.setName("original");
        fragmentNode.add(new JMeterTreeNode(step, null));
        ModuleController first = new ModuleController();
        first.setName("first call");
        first.setSelectedNode(fragmentNode);
        ModuleController second = new ModuleController();
        second.setName("second call");
        second.setSelectedNode(fragmentNode);
        Step login = new Step();
        login.setName("login");
        TestPlan plan = new TestPlan();
        ListedHashTree initial = prepare(plan, group, login, first, second);
        ValidationCheckpoints.rememberStructure(initial);
        run(initial);
        assertEquals(List.of("login", "original", "original"), CALLS);
        var checkpoint = ValidationCheckpoints.getReplayTarget(RESULTS.get(1)).checkpoint();
        assertEquals(ValidationCheckpoints.get(group), checkpoint);
        var secondInvocation = ValidationCheckpoints.getReplayTarget(RESULTS.get(2));
        org.junit.jupiter.api.Assertions.assertNotEquals(checkpoint.samplerId(),
                secondInvocation.checkpoint().samplerId());
        step.setName("edited");
        step.setProperty("fixed", true);
        ListedHashTree resumed = prepare(plan, group, login, first, second);
        ValidationCheckpoints.prepareResume(resumed, checkpoint);
        CALLS.clear();
        run(resumed);
        // The first invocation failed, so both fragment invocations must run, but login must not.
        assertEquals(List.of("edited", "edited"), CALLS);
        assertThrows(IllegalStateException.class, () -> ValidationCheckpoints.get(group));
        ListedHashTree replaySecond = prepare(plan, group, login, first, second);
        ValidationCheckpoints.prepareResume(replaySecond, secondInvocation.checkpoint());
        CALLS.clear();
        run(replaySecond);
        assertEquals(List.of("edited"), CALLS);
    }

    @Test
    void threadGroupMenuIncludesResumeOnlyWithAFailedCheckpoint() throws Exception {
        var previous = org.apache.jmeter.gui.GuiPackage.getInstance();
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                @SuppressWarnings("deprecation")
                var model = new org.apache.jmeter.gui.tree.JMeterTreeModel(new Object());
                var listener = new org.apache.jmeter.gui.tree.JMeterTreeListener(model);
                ThreadGroup group = new ThreadGroup();
                JMeterTreeNode groupNode = new JMeterTreeNode(group, model);
                ((JMeterTreeNode) model.getRoot()).add(groupNode);
                var tree = new javax.swing.JTree(model);
                listener.setJTree(tree);
                listener.setSelectionPathWithoutEdit(new javax.swing.tree.TreePath(groupNode.getPath()));
                org.apache.jmeter.gui.GuiPackage.initInstance(listener, model);
                assertFalse(hasResumeMenuItem());
                JMeterContextService.setValidationRun(true);
                Step sampler = new Step();
                ValidationCheckpoints.register(group, group);
                ValidationCheckpoints.register(sampler, sampler);
                var executionTree = new ListedHashTree();
                executionTree.add(sampler);
                var context = JMeterContextService.getContext();
                context.setVariables(new org.apache.jmeter.threads.JMeterVariables());
                ValidationCheckpoints.beforeSampler(executionTree, sampler, context);
                SampleResult result = new SampleResult();
                result.setSuccessful(true);
                ValidationCheckpoints.afterSampler(sampler, group, result);
                assertFalse(hasResumeMenuItem());
                result.setSuccessful(false);
                ValidationCheckpoints.afterSampler(sampler, group, result);
                assertTrue(hasResumeMenuItem());
                ValidationCheckpoints.clear();
                assertFalse(hasResumeMenuItem());
            });
        } finally {
            var field = org.apache.jmeter.gui.GuiPackage.class.getDeclaredField("guiPack");
            field.setAccessible(true);
            field.set(null, previous);
        }
    }

    private static boolean hasResumeMenuItem() {
        return Arrays.stream(new ThreadGroupGui().createPopupMenu().getComponents())
                .filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                .anyMatch(item -> ActionNames.RESUME_VALIDATION.equals(item.getActionCommand()));
    }

    private static ListedHashTree prepare(TestPlan plan, ThreadGroup group, Step login,
            ModuleController first, ModuleController second) {
        ListedHashTree tree = new ListedHashTree();
        var flow = tree.add(plan).add(group);
        flow.add(login);
        flow.add(first);
        flow.add(second);
        TreeClonerForValidation cloner = new TreeClonerForValidation();
        JMeter.convertSubTree(tree, false).traverse(cloner);
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
