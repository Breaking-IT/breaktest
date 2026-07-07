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

package org.apache.jmeter.gui.tree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.tree.TreePath;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.AbstractJMeterGuiComponent;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.EnableComponent;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JMeterTreeListenerTest {

    private GuiPackage previousGuiPackage;

    @BeforeEach
    void rememberGuiPackage() {
        previousGuiPackage = GuiPackage.getInstance();
    }

    @AfterEach
    void clearGuiPackage() throws ReflectiveOperationException {
        Field guiPack = GuiPackage.class.getDeclaredField("guiPack");
        guiPack.setAccessible(true);
        guiPack.set(null, previousGuiPackage);
    }

    @Test
    void selectedNodesPrefersCurrentPathWhenTreeSelectionIsStale() throws IllegalUserActionException {
        TreeFixture fixture = new TreeFixture();
        fixture.listener.setSelectionPathWithoutEdit(fixture.threadGroupPath);
        fixture.tree.setSelectionPath(fixture.configPath);

        JMeterTreeNode[] nodes = fixture.listener.getSelectedNodes();

        assertArrayEquals(new JMeterTreeNode[] { fixture.threadGroupNode }, nodes);
    }

    @Test
    void selectedNodesKeepsMultiSelectionWhenCurrentPathIsSelected() throws IllegalUserActionException {
        TreeFixture fixture = new TreeFixture();
        fixture.listener.setSelectionPathWithoutEdit(fixture.threadGroupPath);
        fixture.tree.setSelectionPaths(new TreePath[] { fixture.threadGroupPath, fixture.configPath });

        JMeterTreeNode[] nodes = fixture.listener.getSelectedNodes();

        assertSame(fixture.threadGroupNode, nodes[0]);
        assertSame(fixture.configNode, nodes[1]);
    }

    @Test
    void toggleCommandRemainsUndoableAfterRefreshingCurrentGui() throws Exception {
        TreeFixture fixture = new TreeFixture();
        GuiPackage.initInstance(fixture.listener, fixture.model);
        fixture.listener.setSelectionPathWithoutEdit(fixture.configPath);

        GuiPackage guiPackage = GuiPackage.getInstance();
        assertFalse(guiPackage.canUndo());

        guiPackage.beginUndoTransaction();
        try {
            new EnableComponent().doAction(
                    new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.TOGGLE));
        } finally {
            guiPackage.endUndoTransaction();
        }

        assertFalse(fixture.configNode.isEnabled());
        assertTrue(guiPackage.canUndo());
        assertFalse(guiPackage.canRedo());
    }

    private static final class TreeFixture {
        private final JMeterTreeModel model;
        private final JMeterTreeListener listener;
        private final JTree tree;
        private final JMeterTreeNode threadGroupNode;
        private final TreePath threadGroupPath;
        private final JMeterTreeNode configNode;
        private final TreePath configPath;

        private TreeFixture() throws IllegalUserActionException {
            model = new JMeterTreeModel(new TestPlan("Root"));
            JMeterTreeNode testPlanNode = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
            ThreadGroup threadGroup = new ThreadGroup();
            threadGroup.setName("Thread Group");
            threadGroupNode = new JMeterTreeNode(threadGroup, model);
            model.insertNodeInto(threadGroupNode, testPlanNode, testPlanNode.getChildCount());
            ConfigTestElement config = new ConfigTestElement();
            config.setName("Config");
            config.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
            config.setProperty(TestElement.GUI_CLASS, TestGui.class.getName());
            configNode = new JMeterTreeNode(config, model);
            model.insertNodeInto(configNode, threadGroupNode, threadGroupNode.getChildCount());
            threadGroupPath = new TreePath(threadGroupNode.getPath());
            configPath = new TreePath(configNode.getPath());

            tree = new JTree(model);
            listener = new JMeterTreeListener(model);
            listener.setJTree(tree);
        }
    }

    public static class TestGui extends AbstractJMeterGuiComponent {
        private static final long serialVersionUID = 1L;

        @Override
        public String getStaticLabel() {
            return "Test GUI";
        }

        @Override
        public String getLabelResource() {
            return "test_gui";
        }

        @Override
        public TestElement createTestElement() {
            ConfigTestElement element = new ConfigTestElement();
            modifyTestElement(element);
            return element;
        }

        @Override
        public JPopupMenu createPopupMenu() {
            return null;
        }

        @Override
        public Collection<String> getMenuCategories() {
            return List.of();
        }
    }
}
