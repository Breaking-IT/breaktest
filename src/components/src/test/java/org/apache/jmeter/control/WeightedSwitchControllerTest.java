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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.junit.stubs.TestSampler;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
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
    void invalidWeightLoadedFromAPlanFailsInsteadOfSilentlySelectingAnotherChild() {
        WeightedSwitchController controller = new WeightedSwitchController();
        TestSampler invalid = new TestSampler("invalid");
        invalid.setProperty(WeightedSwitchController.CHILD_WEIGHT, "NaN");
        controller.addTestElement(invalid);
        controller.addTestElement(new TestSampler("valid"));
        controller.initialize();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, controller::next);
        assertTrue(error.getMessage().contains("child 'invalid'"));
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
        WeightedSwitchController.setWeight(module, "5");
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
        assertEquals("5", WeightedSwitchController.getWeight(loadedModule));
        TransactionController transaction = new TransactionController();
        JMeterTreeNode target = new JMeterTreeNode(transaction, null);
        DebugSampler sample = new DebugSampler();
        target.add(new JMeterTreeNode(sample, null));
        loadedModule.setSelectedNode(target);
        HashTree executable = JMeter.convertSubTree(loaded, true);
        WeightedSwitchController runtime = (WeightedSwitchController) executable.getArray()[0];
        assertEquals(1, executable.getTree(runtime).size());
        TestElement runtimeModule = (TestElement) executable.getTree(runtime).getArray()[0];
        assertEquals("5", WeightedSwitchController.getWeight(runtimeModule));
        executable.traverse(new TestCompiler(executable));
        runtime.initialize();
        for (int i = 0; i < 5; i++) {
            assertTrue(runtime.next() instanceof DebugSampler);
            assertNull(runtime.next());
        }
    }
}
