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

package org.apache.jmeter.gui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JTree;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.threads.AbstractThreadGroup;

public interface TreeState {

    /** Reveal thread groups, expanding a group only when it is the only one in the plan. */
    static void expandThreadGroups(JTree tree) {
        Object root = tree.getModel().getRoot();
        if (!(root instanceof JMeterTreeNode treeRoot)) {
            return;
        }
        List<TreePath> threadGroups = new ArrayList<>();
        for (var nodes = treeRoot.preorderEnumeration(); nodes.hasMoreElements();) {
            JMeterTreeNode treeNode = (JMeterTreeNode) nodes.nextElement();
            if (treeNode.getTestElement() instanceof AbstractThreadGroup) {
                threadGroups.add(new TreePath(treeNode.getPath()));
            } else if (treeNode.getTestElement() instanceof ThreadGroupsSection) {
                tree.expandPath(new TreePath(treeNode.getPath()));
            }
        }
        if (threadGroups.size() == 1) {
            tree.expandPath(threadGroups.get(0));
        } else {
            threadGroups.forEach(tree::collapsePath);
        }
    }

    /**
     * Restore tree expanded and selected state
     *
     * @param guiInstance GuiPackage to be used
     */
    void restore(GuiPackage guiInstance);

    static final TreeState NOTHING = (GuiPackage guiInstance) -> {};

    /**
     * Save tree expanded and selected state
     *
     * @param guiPackage {@link GuiPackage} to be used
     * @return {@link TreeState}
     */
    public static TreeState from(GuiPackage guiPackage) {
        if (guiPackage == null) {
            return NOTHING;
        }

        MainFrame mainframe = guiPackage.getMainFrame();
        if (mainframe != null) {
            final JTree tree = mainframe.getTree();
            int savedSelected = tree.getMinSelectionRow();
            ArrayList<Integer> savedExpanded = new ArrayList<>();

            for (int rowN = 0; rowN < tree.getRowCount(); rowN++) {
                if (tree.isExpanded(rowN)) {
                    savedExpanded.add(rowN);
                }
            }

            return new TreeStateImpl(savedSelected, savedExpanded);
        }

        return NOTHING;
    }

    static final class TreeStateImpl implements TreeState {

        // GUI tree expansion state
        private final List<Integer> savedExpanded;

        // GUI tree selected row
        private final int savedSelected;

        public TreeStateImpl(int savedSelected, List<Integer> savedExpanded) {
            this.savedSelected = savedSelected;
            this.savedExpanded = savedExpanded;
        }

        @Override
        public void restore(GuiPackage guiInstance) {
            MainFrame mainframe = guiInstance.getMainFrame();
            if (mainframe == null) {
                //log?
                return;
            }

            final JTree tree = mainframe.getTree();

            if (!savedExpanded.isEmpty()) {
                savedExpanded.forEach(tree::expandRow);
            } else {
                tree.expandRow(0);
            }
            tree.setSelectionRow(savedSelected);
        }
    }
}
