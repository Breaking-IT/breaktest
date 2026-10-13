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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JMenuItem;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.config.gui.SimpleConfigGui;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.gui.LogicControllerGui;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddParentTest {
    private GuiPackage previousGuiPackage;
    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        previousGuiPackage = GuiPackage.getInstance();
        SwingUtilities.invokeAndWait(() -> fixture = new Fixture());
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        Field guiPack = GuiPackage.class.getDeclaredField("guiPack");
        guiPack.setAccessible(true);
        guiPack.set(null, previousGuiPackage);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void keepsSelectionAndCurrentNodeWhenInsertingParent(int selectedCount) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterTreeNode[] nodes = new JMeterTreeNode[selectedCount];
            for (int i = 0; i < selectedCount; i++) {
                nodes[i] = fixture.add(fixture.plan, "Node " + i);
            }
            fixture.select(nodes);

            fixture.insertParent();

            JMeterTreeNode parent = (JMeterTreeNode) fixture.plan.getChildAt(0);
            assertEquals(selectedCount, parent.getChildCount());
            for (int i = 0; i < selectedCount; i++) {
                assertSame(nodes[i], parent.getChildAt(i));
            }
            fixture.assertSelection(nodes[nodes.length - 1], nodes);
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(0, fixture.editActions.get()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistsEditsMadeAfterInsertParent(boolean navigateAway) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterTreeNode node = fixture.add(fixture.plan, "Original");
            JMeterTreeNode other = fixture.add(fixture.plan, "Other");
            fixture.select(node);
            SimpleConfigGui editor = (SimpleConfigGui) fixture.gui.getCurrentGui();
            editor.setName("Before insertion");

            fixture.insertParent();

            assertEquals("Before insertion", node.getName());
            editor.setName("After insertion");
            if (navigateAway) {
                fixture.select(other);
                fixture.gui.getCurrentGui();
            } else {
                // Saving flushes the current editor through this same method.
                fixture.gui.updateCurrentNode();
            }
            assertEquals("After insertion", node.getName());
            assertEquals("Other", other.getName());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesTreeOrderRegardlessOfClickOrder(boolean reverse) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterTreeNode a = fixture.add(fixture.plan, "A");
            JMeterTreeNode b = fixture.add(fixture.plan, "B");
            JMeterTreeNode c = fixture.add(fixture.plan, "C");
            fixture.select(reverse ? new JMeterTreeNode[] {c, a} : new JMeterTreeNode[] {a, c});

            fixture.insertParent();

            JMeterTreeNode parent = (JMeterTreeNode) fixture.plan.getChildAt(0);
            assertSame(a, parent.getChildAt(0));
            assertSame(c, parent.getChildAt(1));
            assertSame(b, fixture.plan.getChildAt(1));
            fixture.assertSelection(reverse ? a : c, a, c);
        });
    }

    @Test
    void keepsSelectedDescendantInsideItsController() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GenericController controller = new GenericController();
            JMeterTreeNode parent = new JMeterTreeNode(controller, fixture.model);
            fixture.model.insertNodeInto(parent, fixture.plan, 0);
            JMeterTreeNode child = fixture.add(parent, "Child");
            fixture.select(parent, child);

            fixture.insertParent();

            JMeterTreeNode wrapper = (JMeterTreeNode) fixture.plan.getChildAt(0);
            assertSame(parent, wrapper.getChildAt(0));
            assertEquals(1, wrapper.getChildCount());
            assertSame(child, parent.getChildAt(0));
            fixture.assertSelection(child, parent, child);
        });
    }

    @Test
    void rejectsSelectionWithDifferentParentsAndKeepsEditorBound() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterTreeNode first = fixture.add(fixture.plan, "First");
            JMeterTreeNode controller = new JMeterTreeNode(new GenericController(), fixture.model);
            fixture.model.insertNodeInto(controller, fixture.plan, 1);
            JMeterTreeNode nested = fixture.add(controller, "Nested");
            fixture.select(nested, first);
            SimpleConfigGui editor = (SimpleConfigGui) fixture.gui.getCurrentGui();

            assertThrows(IllegalUserActionException.class, fixture::tryInsertParent);

            assertEquals(2, fixture.plan.getChildCount());
            assertSame(first, fixture.plan.getChildAt(0));
            assertSame(nested, controller.getChildAt(0));
            editor.setName("After rejection");
            fixture.gui.updateCurrentNode();
            assertEquals("After rejection", first.getName());
        });
    }

    @Test
    void usesSelectedNodeWhenCurrentPathIsMissing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterTreeNode node = fixture.add(fixture.plan, "Selected");
            // Simulate a tree selection that has not reached the listener yet.
            fixture.tree.removeTreeSelectionListener(fixture.listener);
            fixture.tree.setSelectionPath(new TreePath(node.getPath()));
            fixture.tree.addTreeSelectionListener(fixture.listener);

            fixture.insertParent(new GenericController());

            assertSame(node, ((JMeterTreeNode) fixture.plan.getChildAt(0)).getChildAt(0));
            fixture.assertSelection(node, node);
        });
    }

    private static class Fixture {
        private final JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Plan"));
        private final JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        private final JTree tree = new JTree(model);
        private final JMeterTreeListener listener = new JMeterTreeListener(model);
        private final AtomicInteger editActions = new AtomicInteger();
        private final GuiPackage gui;

        Fixture() {
            listener.setJTree(tree);
            listener.setActionHandler(event -> editActions.incrementAndGet());
            tree.addTreeSelectionListener(listener);
            GuiPackage.initInstance(listener, model);
            gui = GuiPackage.getInstance();
        }

        JMeterTreeNode add(JMeterTreeNode parent, String name) {
            ConfigTestElement element = new ConfigTestElement();
            element.setName(name);
            element.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
            element.setProperty(TestElement.GUI_CLASS, SimpleConfigGui.class.getName());
            JMeterTreeNode node = new JMeterTreeNode(element, model);
            model.insertNodeInto(node, parent, parent.getChildCount());
            return node;
        }

        void select(JMeterTreeNode... nodes) {
            listener.beginSuppressEditAction();
            try {
                tree.setSelectionPaths(Arrays.stream(nodes).map(node -> new TreePath(node.getPath()))
                        .toArray(TreePath[]::new));
            } finally {
                listener.endSuppressEditAction();
            }
        }

        void insertParent() {
            try {
                tryInsertParent();
            } catch (IllegalUserActionException e) {
                throw new AssertionError(e);
            }
        }

        void tryInsertParent() throws IllegalUserActionException {
            JMenuItem item = new JMenuItem();
            item.setName(LogicControllerGui.class.getName());
            new AddParent().doAction(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, ActionNames.ADD_PARENT));
        }

        void insertParent(TestElement controller) {
            try {
                AddParent.addParentToTree(controller);
            } catch (IllegalUserActionException e) {
                throw new AssertionError(e);
            }
        }

        void assertSelection(JMeterTreeNode current, JMeterTreeNode... selected) {
            assertSame(current, listener.getCurrentNode());
            assertEquals(new TreePath(current.getPath()), tree.getLeadSelectionPath());
            assertEquals(selected.length, tree.getSelectionCount());
            for (JMeterTreeNode node : selected) {
                assertTrue(tree.isPathSelected(new TreePath(node.getPath())));
            }
            assertTrue(tree.isVisible(tree.getLeadSelectionPath()));
        }
    }
}
