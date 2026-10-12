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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddParentTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void keepsSelectionAndCurrentNodeWhenInsertingParent(int selectedCount) throws Exception {
        GuiPackage previousGuiPackage = GuiPackage.getInstance();
        AtomicInteger editActions = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Plan"));
                JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
                JMeterTreeNode[] selectedNodes = new JMeterTreeNode[selectedCount];
                TreePath[] selectedPaths = new TreePath[selectedCount];
                for (int i = 0; i < selectedCount; i++) {
                    selectedNodes[i] = new JMeterTreeNode(new ConfigTestElement(), model);
                    model.insertNodeInto(selectedNodes[i], plan, i);
                    selectedPaths[i] = new TreePath(selectedNodes[i].getPath());
                }
                JTree tree = new JTree(model);
                JMeterTreeListener listener = new JMeterTreeListener(model);
                listener.setJTree(tree);
                listener.setActionHandler(event -> editActions.incrementAndGet());
                tree.addTreeSelectionListener(listener);
                GuiPackage.initInstance(listener, model);
                listener.beginSuppressEditAction();
                try {
                    tree.setSelectionPaths(selectedPaths);
                } finally {
                    listener.endSuppressEditAction();
                }
                GenericController controller = new GenericController();

                AddParent.addParentToTree(controller);

                JMeterTreeNode parent = (JMeterTreeNode) plan.getChildAt(0);
                assertSame(controller, parent.getTestElement());
                assertEquals(selectedCount, parent.getChildCount());
                for (int i = 0; i < selectedCount; i++) {
                    assertSame(selectedNodes[i], parent.getChildAt(i));
                    assertTrue(tree.isPathSelected(new TreePath(selectedNodes[i].getPath())));
                }
                assertArrayEquals(selectedNodes, listener.getSelectedNodes());
                assertSame(selectedNodes[selectedCount - 1], listener.getCurrentNode());
                assertEquals(new TreePath(selectedNodes[selectedCount - 1].getPath()), tree.getLeadSelectionPath());
                assertTrue(tree.isVisible(tree.getLeadSelectionPath()));
            });
            // Flush queued edit actions: transient selection changes must not replace the editor.
            SwingUtilities.invokeAndWait(() -> assertEquals(0, editActions.get()));
        } finally {
            Field guiPack = GuiPackage.class.getDeclaredField("guiPack");
            guiPack.setAccessible(true);
            guiPack.set(null, previousGuiPackage);
        }
    }
}
