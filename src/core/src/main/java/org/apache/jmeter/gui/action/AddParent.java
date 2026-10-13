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

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JTree;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/**
 * Implements the Add Parent menu command
 */
@AutoService(Command.class)
public class AddParent extends AbstractAction {
    private static final Logger log = LoggerFactory.getLogger(AddParent.class);

    private static final Set<String> commands = new HashSet<>();

    static {
        commands.add(ActionNames.ADD_PARENT);
    }

    public AddParent() {
    }

    @Override
    public void doAction(ActionEvent e) {
        String name = ((Component) e.getSource()).getName();
        GuiPackage guiPackage = GuiPackage.getInstance();
        try {
            guiPackage.updateCurrentNode();
            TestElement controller = guiPackage.createTestElement(name);
            addParentToTree(controller);
        } catch (Exception err) {
            log.error("Exception while adding a TestElement.", err);
        }

    }

    @Override
    public Set<String> getActionNames() {
        return commands;
    }

    protected static void addParentToTree(TestElement newParent) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeListener treeListener = guiPackage.getTreeListener();
        JTree tree = treeListener.getJTree();
        JMeterTreeNode newNode = new JMeterTreeNode(newParent, guiPackage.getTreeModel());
        JMeterTreeNode currentNode = treeListener.getCurrentNode();
        JMeterTreeNode[] nodes = treeListener.getSelectedNodes();
        Set<JMeterTreeNode> selected = new HashSet<>(Arrays.asList(nodes));
        if (!selected.contains(currentNode)) {
            currentNode = nodes[nodes.length - 1];
        }
        List<JMeterTreeNode> roots = new ArrayList<>();
        var enumeration = ((JMeterTreeNode) guiPackage.getTreeModel().getRoot()).preorderEnumeration();
        while (enumeration.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) enumeration.nextElement();
            if (selected.contains(node) && !hasSelectedAncestor(node, selected)) {
                roots.add(node);
            }
        }
        if (roots.isEmpty() || roots.get(0).getParent() == null) {
            return;
        }
        JMeterTreeNode firstNode = roots.get(0);
        JMeterTreeNode parentNode = (JMeterTreeNode) firstNode.getParent();
        int index = parentNode.getIndex(firstNode);
        treeListener.beginSuppressEditAction();
        try {
            guiPackage.getTreeModel().insertNodeInto(newNode, parentNode, index);
            for (JMeterTreeNode node : roots) {
                moveNode(guiPackage, node, newNode);
            }
            List<TreePath> paths = new ArrayList<>();
            for (JMeterTreeNode node : nodes) {
                if (node != currentNode) {
                    paths.add(new TreePath(node.getPath()));
                }
            }
            TreePath currentPath = new TreePath(currentNode.getPath());
            // Swing uses the last selected path as the lead selection.
            paths.add(currentPath);
            tree.setSelectionPaths(paths.toArray(new TreePath[0]));
            tree.scrollPathToVisible(currentPath);
        } finally {
            treeListener.endSuppressEditAction();
        }
        guiPackage.refreshCurrentGui();
    }

    private static boolean hasSelectedAncestor(JMeterTreeNode node, Set<JMeterTreeNode> selected) {
        for (var ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (selected.contains(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static void moveNode(GuiPackage guiPackage, JMeterTreeNode node, JMeterTreeNode newParentNode) {
        guiPackage.getTreeModel().removeNodeFromParent(node);
        guiPackage.getTreeModel().insertNodeInto(node, newParentNode, newParentNode.getChildCount());
    }
}
