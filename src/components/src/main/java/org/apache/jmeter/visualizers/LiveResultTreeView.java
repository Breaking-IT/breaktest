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

package org.apache.jmeter.visualizers;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.apache.jmeter.samplers.SampleResult;

/** Tracks automatic expansion separately from branches opened by the user. Used on the EDT. */
final class LiveResultTreeView implements TreeExpansionListener {
    private final JTree tree;
    private final Set<Object> automaticallyExpanded = new HashSet<>();
    private boolean updating;

    LiveResultTreeView(JTree tree) {
        this.tree = tree;
        tree.addTreeExpansionListener(this);
    }

    void setUpdating(boolean updating) {
        this.updating = updating;
    }

    void clear() {
        automaticallyExpanded.clear();
    }

    Set<Object> expandedObjects() {
        Set<Object> expanded = new HashSet<>();
        Enumeration<TreePath> paths = tree.getExpandedDescendants(new TreePath(tree.getModel().getRoot()));
        while (paths != null && paths.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) paths.nextElement().getLastPathComponent();
            expanded.add(node.getUserObject());
        }
        return expanded;
    }

    void prepareExpansion(Set<Object> expanded, TransactionResultTree transactions, boolean moving) {
        automaticallyExpanded.retainAll(expanded);
        if (moving) {
            expanded.removeAll(automaticallyExpanded);
            automaticallyExpanded.clear();
        } else {
            transactions.carryOverExpansion(automaticallyExpanded);
        }
    }

    TreePath followLatest(Set<SampleResult> running, Set<Object> manuallyExpanded) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        TreePath latest = null;
        Enumeration<?> nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node == root || !(node.getUserObject() instanceof SampleResult)) {
                continue;
            }
            latest = new TreePath(node.getPath());
            if (running.contains(node.getUserObject())) {
                expandAutomatically(latest, manuallyExpanded);
            }
        }
        if (latest != null) {
            expandAutomatically(latest.getParentPath(), manuallyExpanded);
        }
        return latest;
    }

    private void expandAutomatically(TreePath path, Set<Object> manuallyExpanded) {
        for (TreePath ancestor = path; ancestor != null; ancestor = ancestor.getParentPath()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) ancestor.getLastPathComponent();
            if (!manuallyExpanded.contains(node.getUserObject())) {
                automaticallyExpanded.add(node.getUserObject());
            }
        }
        tree.expandPath(path);
    }

    @Override
    public void treeExpanded(TreeExpansionEvent event) {
        if (!updating) {
            // Expanding a descendant also explicitly keeps its ancestors open.
            for (Object component : event.getPath().getPath()) {
                automaticallyExpanded.remove(((DefaultMutableTreeNode) component).getUserObject());
            }
        }
    }

    @Override
    public void treeCollapsed(TreeExpansionEvent event) {
        if (!updating) {
            automaticallyExpanded.remove(
                    ((DefaultMutableTreeNode) event.getPath().getLastPathComponent()).getUserObject());
        }
    }
}
