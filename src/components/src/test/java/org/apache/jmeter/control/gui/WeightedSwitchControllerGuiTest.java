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

package org.apache.jmeter.control.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.WeightedSwitchController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class WeightedSwitchControllerGuiTest implements JMeterSerialTest {
    @Test
    void tableTracksChildrenAndPreservesWeightsAcrossTreeEdits() throws Exception {
        GuiPackage previous = GuiPackage.getInstance();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JMeterTreeModel tree = new JMeterTreeModel();
                GuiPackage.initInstance(new JMeterTreeListener(tree), tree);
                JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) tree.getRoot()).getChildAt(0);
                WeightedSwitchController controller = new WeightedSwitchController();
                JMeterTreeNode node = new JMeterTreeNode(controller, tree);
                plan.add(node);
                DebugSampler sampler = new DebugSampler();
                sampler.setName("duplicate");
                TransactionController transaction = new TransactionController();
                transaction.setName("duplicate");
                JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, tree);
                JMeterTreeNode transactionNode = new JMeterTreeNode(transaction, tree);
                node.add(samplerNode);
                node.add(transactionNode);
                node.add(new JMeterTreeNode(new ConfigTestElement(), tree));
                transactionNode.add(new JMeterTreeNode(new DebugSampler(), tree));

                WeightedSwitchControllerGui gui = new WeightedSwitchControllerGui();
                gui.configure(controller);
                JTable table = findTable(gui);
                assertEquals(2, table.getRowCount());
                assertEquals("1", table.getValueAt(0, 1));
                assertFalse(table.isCellEditable(0, 0));
                GuiPackage.getInstance().setDirty(false);
                AtomicBoolean dirtyWhenHistoryCapturesChange = new AtomicBoolean();
                tree.addTreeModelListener(new TreeModelListener() {
                    @Override
                    public void treeNodesChanged(TreeModelEvent event) {
                        dirtyWhenHistoryCapturesChange.set(GuiPackage.getInstance().isDirty());
                    }

                    @Override
                    public void treeNodesInserted(TreeModelEvent event) {
                    }

                    @Override
                    public void treeNodesRemoved(TreeModelEvent event) {
                    }

                    @Override
                    public void treeStructureChanged(TreeModelEvent event) {
                    }
                });
                table.setValueAt("3", 0, 1);
                table.setValueAt("0.5", 1, 1);
                gui.modifyTestElement(controller);
                assertTrue(GuiPackage.getInstance().isDirty());
                assertTrue(dirtyWhenHistoryCapturesChange.get(), "Redo must retain the unsaved state");
                assertEquals("3", WeightedSwitchController.getWeight(sampler));
                assertEquals("0.5", WeightedSwitchController.getWeight(transaction));

                assertTrue(table.editCellAt(0, 1));
                ((JTextField) table.getEditorComponent()).setText("-");
                JMeterTreeNode unrelated = new JMeterTreeNode(new DebugSampler(), tree);
                tree.insertNodeInto(unrelated, plan, plan.getChildCount());
                tree.nodeChanged(unrelated);
                tree.removeNodeFromParent(unrelated);
                sampler.setName("renaming while editing");
                tree.nodeChanged(samplerNode);
                tree.nodeStructureChanged(node);
                assertTrue(table.isEditing(), "Unrelated changes and same-row refreshes must preserve the editor");
                assertEquals("-", ((JTextField) table.getEditorComponent()).getText());
                table.getCellEditor().cancelCellEditing();

                WeightedSwitchController.setWeight(sampler, "11");
                tree.nodeChanged(samplerNode);
                assertEquals("11", table.getValueAt(0, 1));
                gui.modifyTestElement(controller);
                assertEquals("11", WeightedSwitchController.getWeight(sampler), "Do not overwrite external edits");
                WeightedSwitchController.setWeight(sampler, "3");
                tree.nodeChanged(samplerNode);

                tree.removeNodeFromParent(samplerNode);
                tree.insertNodeInto(samplerNode, node, 1);
                assertEquals("0.5", table.getValueAt(0, 1));
                assertEquals("3", table.getValueAt(1, 1));
                sampler.setName("renamed");
                sampler.setEnabled(false);
                tree.nodeChanged(samplerNode);
                assertEquals("renamed", table.getValueAt(1, 0));
                assertEquals(false, table.getValueAt(1, 2));
                tree.insertNodeInto(new JMeterTreeNode(new ModuleController(), tree), node, 2);
                assertEquals(3, table.getRowCount());
                assertEquals("1", table.getValueAt(2, 1));

                assertTrue(table.editCellAt(2, 1));
                ((JTextField) table.getEditorComponent()).setText("8");
                JMeterTreeNode added = new JMeterTreeNode(new DebugSampler(), tree);
                tree.insertNodeInto(added, node, node.getChildCount());
                assertFalse(table.isEditing(), "Changing rows should commit the edit against the original child");
                assertEquals("8", table.getValueAt(2, 1));
                tree.removeNodeFromParent(added);
                gui.modifyTestElement(controller);
                gui.configure(controller);
                assertEquals("8", table.getValueAt(2, 1));
                assertTrue(table.editCellAt(2, 1));
                ((JTextField) table.getEditorComponent()).setText("-1");
                assertFalse(table.getCellEditor().stopCellEditing());
                table.getCellEditor().cancelCellEditing();
                assertEquals("8", table.getValueAt(2, 1));

                tree.removeNodeFromParent(transactionNode);
                assertEquals(2, table.getRowCount());
                gui.clearGui();
                assertEquals(0, table.getRowCount());
                gui.configure(new WeightedSwitchController());
                assertEquals(0, table.getRowCount());
                gui.clearGui();
            });
        } finally {
            var field = GuiPackage.class.getDeclaredField("guiPack");
            field.setAccessible(true);
            field.set(null, previous);
        }
    }

    @Test
    void canCreateAndClearWithoutAnOpenPlan() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WeightedSwitchControllerGui gui = new WeightedSwitchControllerGui();
            TestElement element = gui.createTestElement();
            assertTrue(element instanceof WeightedSwitchController);
            gui.configure(element);
            gui.modifyTestElement(element);
            gui.clearGui();
            assertEquals(0, findTable(gui).getRowCount());
        });
    }

    private static JTable findTable(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTable) {
                return (JTable) child;
            }
            if (child instanceof Container) {
                JTable table = findTable((Container) child);
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }
}
