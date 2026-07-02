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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreePath;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.AbstractJMeterGuiComponent;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LoadTest {

    @AfterEach
    void resetGuiPackage() throws Exception {
        Field guiPack = GuiPackage.class.getDeclaredField("guiPack");
        guiPack.setAccessible(true);
        guiPack.set(null, null);
        Properties properties = JMeterUtils.getJMeterProperties();
        if (properties != null) {
            properties.remove(Load.FAST_JMX_LOAD_PROPERTY);
        }
    }

    @Test
    void collectBreakTestHarReferencesReadsGuiTreeNodes() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, "recording.har");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, "0123456789abcdef0123456789abcdef");

        JMeterTreeNode testPlanNode = new JMeterTreeNode(new TestPlan("Test Plan"), null);
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, null);
        ListedHashTree tree = new ListedHashTree();
        tree.add(testPlanNode);
        tree.add(testPlanNode, threadGroupNode);

        List<Load.BreakTestHarReference> references = Load.collectBreakTestHarReferences(tree);

        assertEquals(1, references.size());
        assertEquals("Thread Group", references.get(0).sourceName());
        assertEquals("recording.har", references.get(0).filename());
        assertEquals("0123456789abcdef0123456789abcdef", references.get(0).expectedMd5());
    }

    @Test
    void addSubTreeBatchesTreeModelEvents() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Root"));
        AtomicInteger insertedEvents = new AtomicInteger();
        AtomicInteger structureEvents = new AtomicInteger();
        model.addTreeModelListener(new TreeModelListener() {
            @Override
            public void treeNodesChanged(TreeModelEvent e) {
            }

            @Override
            public void treeNodesInserted(TreeModelEvent e) {
                insertedEvents.incrementAndGet();
            }

            @Override
            public void treeNodesRemoved(TreeModelEvent e) {
            }

            @Override
            public void treeStructureChanged(TreeModelEvent e) {
                structureEvents.incrementAndGet();
            }
        });

        TestPlan testPlan = new TestPlan("Test Plan");
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        ConfigTestElement config = new ConfigTestElement();
        config.setName("Config");

        ListedHashTree tree = new ListedHashTree();
        tree.add(testPlan);
        tree.add(testPlan, threadGroup);
        tree.add(threadGroup, config);

        model.addSubTree(tree, (JMeterTreeNode) model.getRoot(), false);

        assertEquals(0, insertedEvents.get());
        assertEquals(1, structureEvents.get());
    }

    @Test
    void fastLoadedResultCollectorIsBoundToVisualizerBeforeSelection() throws Exception {
        TestVisualizer.samplesReceived.set(0);
        JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Root"));
        GuiPackage.initInstance(new JMeterTreeListener(model), model);

        TestPlan testPlan = new TestPlan("Test Plan");
        ResultCollector resultCollector = new ResultCollector();
        resultCollector.setName("View Results Tree");
        resultCollector.setProperty(TestElement.TEST_CLASS, ResultCollector.class.getName());
        resultCollector.setProperty(TestElement.GUI_CLASS, TestVisualizer.class.getName());

        ListedHashTree tree = new ListedHashTree();
        tree.add(testPlan);
        tree.add(testPlan, resultCollector);

        model.addSubTree(tree, (JMeterTreeNode) model.getRoot(), false);
        resultCollector.sampleOccurred(new SampleEvent(new SampleResult(), "Thread Group"));

        assertEquals(1, TestVisualizer.samplesReceived.get());
    }

    @Test
    void fastLoadedSubTreeFlushesCurrentGuiOnceBeforeInsertion() throws Exception {
        TestEditableGui.modifyCalls.set(0);
        JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Root"));
        JMeterTreeListener listener = new JMeterTreeListener(model);
        listener.setJTree(new JTree(model));
        GuiPackage.initInstance(listener, model);
        GuiPackage guiPackage = GuiPackage.getInstance();

        JMeterTreeNode testPlanNode = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        EditableElement selectedElement = new EditableElement();
        selectedElement.setName("edited");
        selectedElement.setProperty(TestElement.TEST_CLASS, EditableElement.class.getName());
        selectedElement.setProperty(TestElement.GUI_CLASS, TestEditableGui.class.getName());
        JMeterTreeNode selectedNode = model.addComponent(selectedElement, testPlanNode);
        listener.setSelectionPathWithoutEdit(new TreePath(selectedNode.getPath()));
        markCurrentNodeDirty(guiPackage, selectedNode);
        TestEditableGui.modifyCalls.set(0);

        ListedHashTree loadedTree = new ListedHashTree();
        ConfigTestElement loadedElement = new ConfigTestElement();
        loadedElement.setName("loaded");
        loadedTree.add(loadedElement);

        guiPackage.addLoadedSubTree(loadedTree);

        assertEquals(1, TestEditableGui.modifyCalls.get());
        assertEquals("true", selectedElement.getPropertyAsString("flushed"));
    }

    @Test
    void fastJmxLoadRequiresExplicitOptIn() {
        assertFalse(Load.useFastJmxLoad());

        jMeterProperties().setProperty(Load.FAST_JMX_LOAD_PROPERTY, "true");

        assertTrue(Load.useFastJmxLoad());
    }

    private static Properties jMeterProperties() {
        Properties properties = JMeterUtils.getJMeterProperties();
        if (properties != null) {
            return properties;
        }
        try {
            Properties testProperties = new Properties();
            Field appProperties = JMeterUtils.class.getDeclaredField("appProperties");
            appProperties.setAccessible(true);
            appProperties.set(null, testProperties);
            return testProperties;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void markCurrentNodeDirty(GuiPackage guiPackage, JMeterTreeNode selectedNode) throws Exception {
        Field currentNode = GuiPackage.class.getDeclaredField("currentNode");
        currentNode.setAccessible(true);
        currentNode.set(guiPackage, selectedNode);
        Field currentNodeUpdated = GuiPackage.class.getDeclaredField("currentNodeUpdated");
        currentNodeUpdated.setAccessible(true);
        currentNodeUpdated.set(guiPackage, false);
    }

    public static class TestVisualizer extends AbstractVisualizer {
        private static final long serialVersionUID = 1L;

        static final AtomicInteger samplesReceived = new AtomicInteger();

        @Override
        public void add(SampleResult sample) {
            samplesReceived.incrementAndGet();
        }

        @Override
        public void clearData() {
            samplesReceived.set(0);
        }

        @Override
        public String getLabelResource() {
            return "view_results_tree_title";
        }
    }

    public static class EditableElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;
    }

    public static class TestEditableGui extends AbstractJMeterGuiComponent {
        private static final long serialVersionUID = 1L;

        static final AtomicInteger modifyCalls = new AtomicInteger();

        @Override
        public TestElement createTestElement() {
            EditableElement element = new EditableElement();
            modifyTestElement(element);
            return element;
        }

        @Override
        public void modifyTestElement(TestElement element) {
            modifyCalls.incrementAndGet();
            configureTestElement(element);
            element.setProperty("flushed", "true");
        }

        @Override
        public String getLabelResource() {
            return "test";
        }

        @Override
        public JPopupMenu createPopupMenu() {
            return null;
        }

        @Override
        public Collection<String> getMenuCategories() {
            return Collections.emptyList();
        }
    }
}
