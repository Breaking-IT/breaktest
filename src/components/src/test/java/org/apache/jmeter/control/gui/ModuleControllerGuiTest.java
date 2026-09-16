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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JMenuItem;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class ModuleControllerGuiTest implements JMeterSerialTest {
    @Test
    void jumpsToClickedFragmentWithoutChangingConfiguredTarget() throws Exception {
        GuiPackage previousGui = GuiPackage.getInstance();
        try {
            SwingUtilities.invokeAndWait(() -> {
                @SuppressWarnings("deprecation")
                JMeterTreeModel model = new JMeterTreeModel(new Object());
                JMeterTreeListener listener = new JMeterTreeListener(model);
                JTree tree = new JTree(model);
                listener.setJTree(tree);
                GuiPackage.initInstance(listener, model);
                JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
                JMeterTreeNode linked = new JMeterTreeNode(new TestFragmentController(), model);
                JMeterTreeNode clicked = new JMeterTreeNode(new TestFragmentController(), model);
                plan.add(linked);
                plan.add(clicked);
                ModuleController module = new ModuleController();
                module.setSelectedNode(linked);
                JMeterTreeNode moduleNode = new JMeterTreeNode(module, model);
                plan.add(moduleNode);
                model.reload();
                tree.setSelectionPath(new TreePath(moduleNode.getPath()));
                tree.collapsePath(new TreePath(plan.getPath()));

                JMenuItem jumpTo = ModuleControllerGui.createJumpToMenuItem(
                        new TreePath(new DefaultMutableTreeNode(clicked)));
                assertEquals("Jump to", jumpTo.getText());
                assertTrue(jumpTo.isEnabled());
                jumpTo.doClick();

                assertSame(clicked, tree.getLastSelectedPathComponent());
                assertTrue(tree.isVisible(new TreePath(clicked.getPath())));
                assertSame(linked, module.getSelectedNode());
            });
        } finally {
            var field = GuiPackage.class.getDeclaredField("guiPack");
            field.setAccessible(true);
            field.set(null, previousGui);
        }
    }

    @Test
    void jumpIsDisabledWithoutAValidModuleTarget() {
        assertFalse(ModuleControllerGui.createJumpToMenuItem(null).isEnabled());
        assertFalse(ModuleControllerGui.createJumpToMenuItem(
                new TreePath(new DefaultMutableTreeNode())).isEnabled());
        assertFalse(ModuleControllerGui.createJumpToMenuItem(
                new TreePath(new DefaultMutableTreeNode(new JMeterTreeNode(new TestPlan(), null)))).isEnabled());
    }
}
