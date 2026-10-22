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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.engine.TurnElementsOn;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.junit.stubs.TestSampler;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeightedSwitchControllerTest extends JMeterTestCase {
    @Test
    void selectionUsesRelativeWeightsAndExactBoundaries() {
        WeightedSwitchController controller = new WeightedSwitchController();
        TestSampler first = new TestSampler("same name");
        TestSampler second = new TestSampler("same name");
        WeightedSwitchController.setWeight(first, "3");
        WeightedSwitchController.setWeight(second, "1");
        controller.addTestElement(first);
        controller.addTestElement(second);
        assertEquals(0, controller.selectChild(0));
        assertEquals(0, controller.selectChild(Math.nextDown(0.75)));
        assertEquals(1, controller.selectChild(0.75));
        assertEquals(1, controller.selectChild(Math.nextDown(1.0)));
        int firstCount = 0;
        for (int i = 0; i < 10000; i++) {
            if (controller.selectChild((i + 0.5) / 10000) == 0) {
                firstCount++;
            }
        }
        assertEquals(7500, firstCount);
        WeightedSwitchController.setWeight(first, "0.3");
        WeightedSwitchController.setWeight(second, "0.1");
        assertEquals(0, controller.selectChild(0.74));
        assertEquals(1, controller.selectChild(0.76));
    }

    @Test
    void variableWeightsAreResolvedForEverySelection() throws Exception {
        JMeterVariables previous = JMeterContextService.getContext().getVariables();
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        try {
            WeightedSwitchController controller = new WeightedSwitchController();
            controller.setName("weighted");
            TestSampler first = new TestSampler("variable child");
            TestSampler second = new TestSampler("literal child");
            WeightedSwitchController.setWeight(first, " ${weight} ");
            assertEquals("${weight}", WeightedSwitchController.getWeight(first));
            new ValueReplacer().replaceValues(first);
            first.setRunningVersion(true);
            controller.addTestElement(first);
            controller.addTestElement(second);
            variables.put("weight", "3");
            assertEquals(0, controller.selectChild(0.74));
            assertEquals(1, controller.selectChild(0.75));
            variables.put("weight", "0.5");
            assertEquals(1, controller.selectChild(0.5));
            variables.put("weight", "0");
            assertEquals(1, controller.selectChild(0));
            for (String invalid : new String[]{"", "abc", "-1", "NaN", "Infinity", "1e309"}) {
                variables.put("weight", invalid);
                assertEquals(1, controller.selectChild(0));
            }
            variables.remove("weight");
            assertEquals(1, controller.selectChild(0));
            variables.put("weight", "1");
            assertEquals(0, controller.selectChild(0));
        } finally {
            JMeterContextService.getContext().setVariables(previous);
        }
    }

    @Test
    void defaultsAndVeryLargeWeightsStaySelectable() {
        WeightedSwitchController controller = new WeightedSwitchController();
        TestSampler first = new TestSampler("first");
        TestSampler second = new TestSampler("second");
        controller.addTestElement(first);
        controller.addTestElement(second);
        assertEquals(0, controller.selectChild(0.49));
        assertEquals(1, controller.selectChild(0.5));
        WeightedSwitchController.setWeight(first, "1.7e308");
        WeightedSwitchController.setWeight(second, "1.7e308");
        assertEquals(0, controller.selectChild(0.49));
        assertEquals(1, controller.selectChild(0.5));
    }

    @Test
    void skipsZeroAndDisabledChildrenAndHandlesNoCandidates() {
        WeightedSwitchController controller = new WeightedSwitchController();
        controller.initialize();
        assertNull(controller.next());
        TestSampler disabled = new TestSampler("disabled");
        disabled.setEnabled(false);
        TestSampler zero = new TestSampler("zero");
        WeightedSwitchController.setWeight(zero, "0");
        controller.addTestElement(disabled);
        controller.addTestElement(zero);
        controller.initialize();
        assertNull(controller.next());
        assertNull(controller.next());
        TestSampler active = new TestSampler("active");
        controller.addTestElement(active);
        for (int i = 0; i < 20; i++) {
            assertSame(active, controller.next());
            assertNull(controller.next());
        }
    }

    @Test
    void selectedBranchRunsFullyOncePerIteration() {
        WeightedSwitchController controller = new WeightedSwitchController();
        GenericController branch = new GenericController();
        TestSampler first = new TestSampler("first");
        TestSampler second = new TestSampler("second");
        branch.addTestElement(first);
        branch.addTestElement(second);
        TestSampler skipped = new TestSampler("skipped");
        WeightedSwitchController.setWeight(skipped, "0");
        controller.addTestElement(branch);
        controller.addTestElement(skipped);
        controller.initialize();
        for (int i = 0; i < 10; i++) {
            assertSame(first, controller.next());
            assertSame(second, controller.next());
            assertNull(controller.next());
        }
        assertSame(first, controller.next());
        branch.triggerEndOfLoop();
        controller.triggerEndOfLoop();
        assertSame(first, controller.next());
    }

    @Test
    void finishedChildNeverFallsThroughToSibling() {
        WeightedSwitchController controller = new WeightedSwitchController();
        controller.addTestElement(new GenericController());
        TestSampler sibling = new TestSampler("must not run");
        WeightedSwitchController.setWeight(sibling, "0");
        controller.addTestElement(sibling);
        controller.initialize();
        assertNull(controller.next());
        assertNull(controller.next());
    }

    @Test
    void randomRuntimeChoosesExactlyOneChildOnEachIteration() {
        WeightedSwitchController controller = new WeightedSwitchController();
        controller.addTestElement(new TestSampler("a"));
        controller.addTestElement(new TestSampler("b"));
        controller.initialize();
        int a = 0;
        for (int i = 0; i < 20000; i++) {
            if ("a".equals(controller.next().getName())) {
                a++;
            }
            assertNull(controller.next());
        }
        assertTrue(a > 9000 && a < 11000, "Equal weights should produce roughly equal selections: " + a);
    }

    @Test
    void rejectsInvalidWeightsAndPreservesPreviousValue() {
        TestSampler child = new TestSampler();
        WeightedSwitchController.setWeight(child, "2.5");
        for (String invalid : new String[]{"", "abc", "-1", "NaN", "Infinity", "1e309"}) {
            assertThrows(IllegalArgumentException.class, () -> WeightedSwitchController.setWeight(child, invalid));
            assertEquals("2.5", WeightedSwitchController.getWeight(child));
        }
    }

    @Test
    void invalidLiteralWeightLoadedFromAPlanFailsAtInitialize() {
        for (String literal : new String[]{"NaN", "-1", "abc", "1e309"}) {
            WeightedSwitchController controller = new WeightedSwitchController();
            controller.setName("weighted");
            TestSampler invalid = new TestSampler("invalid");
            invalid.setProperty(WeightedSwitchController.CHILD_WEIGHT, literal);
            controller.addTestElement(invalid);
            controller.addTestElement(new TestSampler("valid"));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, controller::initialize);
            assertTrue(error.getMessage().contains("'" + literal + "'"), error.getMessage());
            assertTrue(error.getMessage().contains("child 'invalid'"), error.getMessage());
            assertTrue(error.getMessage().contains("Controller 'weighted'"), error.getMessage());
        }
    }

    @Test
    void initializeAcceptsExpressionsAndIgnoresDisabledChildren() {
        WeightedSwitchController controller = new WeightedSwitchController();
        TestSampler expression = new TestSampler("expression");
        WeightedSwitchController.setWeight(expression, "${weight}");
        TestSampler disabled = new TestSampler("disabled");
        disabled.setProperty(WeightedSwitchController.CHILD_WEIGHT, "NaN");
        disabled.setEnabled(false);
        TestSampler valid = new TestSampler("valid");
        controller.addTestElement(expression);
        controller.addTestElement(disabled);
        controller.addTestElement(valid);
        controller.initialize();
    }

    @Test
    void invalidRuntimeWeightIsWarnedOncePerChildAcrossThreadsAndTests() throws Exception {
        JMeterVariables previous = JMeterContextService.getContext().getVariables();
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender(
                "weighted-switch-controller-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(WeightedSwitchController.class);
        Level previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            WeightedSwitchController controller = new WeightedSwitchController();
            controller.setName("weighted");
            TestSampler child = new TestSampler("variable child");
            WeightedSwitchController.setWeight(child, "${weight}");
            new ValueReplacer().replaceValues(child);
            child.setRunningVersion(true);
            controller.addTestElement(child);
            controller.addTestElement(new TestSampler("literal child"));
            controller.testStarted();
            WeightedSwitchController otherThread = (WeightedSwitchController) controller.clone();
            otherThread.addTestElement(child);
            otherThread.addTestElement(new TestSampler("literal child"));
            variables.put("weight", "abc");

            controller.selectChild(0);
            controller.selectChild(0);
            otherThread.selectChild(0);
            assertEquals(List.of(Level.WARN, Level.DEBUG, Level.DEBUG),
                    events.stream().map(LogEvent::getLevel).toList());
            String warning = events.get(0).getMessage().getFormattedMessage();
            assertTrue(warning.contains("Invalid weight 'abc'"), warning);
            assertTrue(warning.contains("child 'variable child'"), warning);
            assertTrue(warning.contains("Controller 'weighted'"), warning);

            controller.testStarted();
            controller.selectChild(0);
            assertEquals(Level.WARN, events.get(3).getLevel(), "A new test reports the invalid weight again");
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
            JMeterContextService.getContext().setVariables(previous);
        }
    }

    @Test
    void warningIdentitySurvivesChildRemovalAcrossThreadClones() throws Exception {
        JMeterVariables previous = JMeterContextService.getContext().getVariables();
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender(
                "weighted-switch-controller-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(WeightedSwitchController.class);
        Level previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            WeightedSwitchController controller = new WeightedSwitchController();
            GenericController finished = new GenericController();
            finished.setName("duplicate name");
            TestSampler remaining = new TestSampler("duplicate name");
            WeightedSwitchController.setWeight(finished, "${firstWeight}");
            WeightedSwitchController.setWeight(remaining, "${secondWeight}");
            new ValueReplacer().replaceValues(finished);
            new ValueReplacer().replaceValues(remaining);
            finished.setRunningVersion(true);
            remaining.setRunningVersion(true);
            controller.addTestElement(finished);
            controller.addTestElement(remaining);
            controller.testStarted();
            WeightedSwitchController otherThread = (WeightedSwitchController) controller.clone();
            otherThread.addTestElement((TestElement) finished.clone());
            otherThread.addTestElement((TestElement) remaining.clone());
            controller.initialize();
            otherThread.initialize();

            variables.put("firstWeight", "abc");
            variables.put("secondWeight", "0");
            assertNull(controller.next());
            variables.put("firstWeight", "1");
            assertNull(controller.next());
            assertEquals(1, controller.getSubControllers().size(), "Finished child must be removed");
            assertEquals(2, otherThread.getSubControllers().size(), "Other thread still has both children");
            variables.put("firstWeight", "0");
            variables.put("secondWeight", "abc");
            assertNull(controller.next());
            assertEquals(List.of(Level.WARN, Level.WARN),
                    events.stream().map(LogEvent::getLevel).toList(),
                    "The remaining child's first invalid weight must still warn");
            assertNull(otherThread.next());
            assertNull(controller.next());
            assertEquals(List.of(Level.WARN, Level.WARN, Level.DEBUG, Level.DEBUG),
                    events.stream().map(LogEvent::getLevel).toList(),
                    "The same child must not warn again even at a different index in another thread");
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
            JMeterContextService.getContext().setVariables(previous);
        }
    }

    @Test
    void invalidVariableWeightDoesNotStopVirtualUserAndCanRecover() throws Exception {
        for (String initialWeight : new String[]{null, "abc"}) {
            LoopController loop = new LoopController();
            loop.setLoops(2);
            loop.setContinueForever(false);
            WeightedSwitchController controller = new WeightedSwitchController();
            controller.setName("weighted");
            TestSampler child = new TestSampler("variable child");
            WeightedSwitchController.setWeight(child, "${weight}");
            TestSampler after = new TestSampler("after weighted controller") {
                @Override
                public SampleResult sample(Entry entry) {
                    JMeterContextService.getContext().getVariables().put("weight", "1");
                    return super.sample(entry);
                }
            };
            HashTree tree = new ListedHashTree();
            HashTree iterations = tree.add(loop);
            iterations.add(controller).add(child);
            iterations.add(after);
            tree.traverse(new PreCompiler());
            tree.traverse(new TurnElementsOn());
            ThreadGroup group = new ThreadGroup();
            group.setNumThreads(1);
            JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), true);
            user.setThreadGroup(group);
            JMeterVariables variables = new JMeterVariables();
            if (initialWeight != null) {
                variables.put("weight", initialWeight);
            }
            user.putVariables(variables);
            Thread runner = new Thread(user, "weighted-controller-test");
            runner.start();
            runner.join(10000);
            if (runner.isAlive()) {
                user.stop();
                runner.interrupt();
                runner.join(1000);
            }
            assertFalse(runner.isAlive(), "Virtual user should finish");
            assertEquals(2, after.getSamples(), "Invalid weight must not stop the virtual user");
            assertEquals(1, child.getSamples(), "Child should become selectable when its weight becomes valid");
        }
    }

    @Test
    void childMetadataSurvivesCloneRenameAndGuiClear() {
        TestSampler child = new TestSampler("before");
        WeightedSwitchController.setWeight(child, "7");
        TestElement clone = (TestElement) child.clone();
        assertNotSame(child, clone);
        clone.clear();
        clone.setName("after");
        assertEquals("7", WeightedSwitchController.getWeight(clone));
        WeightedSwitchController.setWeight(clone, "2");
        assertEquals("7", WeightedSwitchController.getWeight(child));
    }

    @Test
    void weightsSurviveSavingLoadingAndModuleExpansion(@TempDir Path directory) throws Exception {
        WeightedSwitchController controller = new WeightedSwitchController();
        controller.setName("weighted");
        controller.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.WeightedSwitchControllerGui");
        ModuleController module = new ModuleController();
        module.setName("module");
        module.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.ModuleControllerGui");
        WeightedSwitchController.setWeight(module, "${weight}");
        DebugSampler disabled = new DebugSampler();
        disabled.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        disabled.setEnabled(false);
        WeightedSwitchController.setWeight(disabled, "999");
        HashTree tree = new ListedHashTree();
        HashTree children = tree.add(controller);
        children.add(disabled);
        children.add(module);
        Path file = directory.resolve("weighted.jmx");
        try (OutputStream output = Files.newOutputStream(file)) {
            SaveService.saveTree(tree, output);
        }
        HashTree loaded = SaveService.loadTree(file.toFile());
        WeightedSwitchController loadedController = (WeightedSwitchController) loaded.getArray()[0];
        ModuleController loadedModule = (ModuleController) loaded.getTree(loadedController).getArray()[1];
        assertEquals("${weight}", WeightedSwitchController.getWeight(loadedModule));
        TransactionController transaction = new TransactionController();
        JMeterTreeNode target = new JMeterTreeNode(transaction, null);
        DebugSampler sample = new DebugSampler();
        target.add(new JMeterTreeNode(sample, null));
        loadedModule.setSelectedNode(target);
        HashTree executable = JMeter.convertSubTree(loaded, true);
        WeightedSwitchController runtime = (WeightedSwitchController) executable.getArray()[0];
        assertEquals(1, executable.getTree(runtime).size());
        TestElement runtimeModule = (TestElement) executable.getTree(runtime).getArray()[0];
        assertEquals("${weight}", WeightedSwitchController.getWeight(runtimeModule));
        JMeterVariables previous = JMeterContextService.getContext().getVariables();
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        try {
            executable.traverse(new PreCompiler());
            executable.traverse(new TurnElementsOn());
            executable.traverse(new TestCompiler(executable));
            runtime.initialize();
            variables.put("weight", "0");
            assertNull(runtime.next());
            variables.put("weight", "5");
            for (int i = 0; i < 5; i++) {
                assertTrue(runtime.next() instanceof DebugSampler);
                assertNull(runtime.next());
            }
        } finally {
            JMeterContextService.getContext().setVariables(previous);
        }
    }
}
