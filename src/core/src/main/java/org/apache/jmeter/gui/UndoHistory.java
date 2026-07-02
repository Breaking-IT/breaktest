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

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import javax.swing.JTree;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.action.UndoCommand;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class serves storing Test Tree state and navigating through it
 * to give the undo/redo ability for test plan changes
 *
 * @since 2.12
 */
public class UndoHistory implements TreeModelListener, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Interface to be implemented by components interested in UndoHistory
     */
    public interface HistoryListener {
        void notifyChangeInHistory(UndoHistory history);
    }

    private static final Logger log = LoggerFactory.getLogger(UndoHistory.class);

    private static final int HISTORY_SIZE = JMeterUtils.getPropDefault("undo.history.size", 25);

    /** flag to prevent recursive actions */
    private boolean working = false;

    /** nesting depth for model events that should not enter undo history */
    private int suspended = 0;

    /** History listeners */
    private final List<HistoryListener> listeners = new ArrayList<>();

    private final Deque<UndoableChange> undoStack = new ArrayDeque<>();

    private final Deque<UndoableChange> redoStack = new ArrayDeque<>();

    private final Deque<UndoTransaction> transactions = new ArrayDeque<>();

    private UndoHistoryItem lastKnownState = null;

    /**
     * Clears the undo history
     */
    public void clear() {
        if (working) {
            return;
        }
        log.debug("Clearing undo history");
        undoStack.clear();
        redoStack.clear();
        if (isTransaction()) {
            if(log.isWarnEnabled()) {
                log.warn("Clearing undo history with {} unfinished transactions", transactions.size());
            }
            transactions.clear();
        }
        lastKnownState = null;
        notifyListeners();
    }

    /**
     * Add tree model copy to the history
     * <p>
     * This method relies on the rule that the record in history made AFTER
     * change has been made to test plan
     *
     * @param treeModel JMeterTreeModel
     * @param comment   String
     */
    public void add(JMeterTreeModel treeModel, String comment) {
        if(!isEnabled()) {
            log.debug("undo.history.size is set to 0, undo/redo feature is disabled");
            return;
        }

        // don't add element if we are in the middle of undo/redo or a big loading
        if (working || suspended > 0) {
            log.debug("Not adding history because of noop");
            return;
        }

        working = true;
        try {
            UndoHistoryItem item = createHistoryItem(treeModel, comment);
            if (item != null) {
                addEdit(item);
            }
        } finally {
            working = false;
        }
    }

    private static UndoHistoryItem createHistoryItem(JMeterTreeModel treeModel, String comment) {
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        if (root.getChildCount() < 1) {
            log.debug("Not adding history because of no children");
            return null;
        }

        String name = root.getName();

        log.debug("Adding history element {}: {}", name, comment);

        HashTree tree = treeModel.getCurrentSubTree((JMeterTreeNode) treeModel.getRoot());
        // first clone to not convert original tree
        tree = (HashTree) tree.getTree(tree.getArray()[0]).clone();

        // cloning is required because we need to immute stored data
        HashTree copy = UndoCommand.convertAndCloneSubTree(tree);

        GuiPackage guiPackage = GuiPackage.getInstance();
        //or maybe a Boolean?
        boolean dirty = guiPackage != null ? guiPackage.isDirty() : false;
        return new UndoHistoryItem(copy, comment, TreeState.from(guiPackage), dirty);
    }

    public void undo() {
        if (!canUndo()) {
            log.warn("Can't undo, we're already on the last record");
            return;
        }
        UndoableChange change = undoStack.pop();
        redoStack.push(change);
        reload(change.before);
    }

    public void redo() {
        if (!canRedo()) {
            log.warn("Can't redo, we're already on the first record");
            return;
        }
        UndoableChange change = redoStack.pop();
        undoStack.push(change);
        reload(change.after);
    }

    private void reload(UndoHistoryItem z) {
        final GuiPackage guiInstance = GuiPackage.getInstance();
        JMeterTreeModel acceptorModel = guiInstance.getTreeModel();
        // Capture the user's current view (selection and expanded nodes) by node
        // identity so undo/redo restores the test plan content without disturbing
        // it. The snapshot's own tree state is row based and only reflects the
        // expansion at the last model change, so manual expand/collapse done since
        // then would be lost (e.g. a thread group collapsing on undo of a delete).
        List<NodeKey> selectedPath = currentSelectedPath(guiInstance);
        List<List<NodeKey>> expandedPaths = currentExpandedPaths(guiInstance);

        working = true;
        guiInstance.getTreeListener().beginSuppressEditAction();
        try {
            // load the tree
            loadHistoricalTree(acceptorModel, guiInstance, z.getTree());

            // restore the user's view onto the reloaded tree
            restoreExpandedPaths(guiInstance, expandedPaths);
            restoreSelectedPath(guiInstance, selectedPath);
            guiInstance.setDirty(z.isDirty());
            setLastKnownState(z);

            // refresh the UI from the restored tree without writing GUI fields
            // back into the just-restored model.
            guiInstance.refreshCurrentGui();
            guiInstance.getMainFrame().repaint();
            notifyListeners();
        } finally {
            guiInstance.getTreeListener().endSuppressEditAction();
            working = false;
        }
    }

    private static List<NodeKey> currentSelectedPath(GuiPackage guiInstance) {
        JMeterTreeNode currentNode = guiInstance.getTreeListener().getCurrentNode();
        if (currentNode == null) {
            return List.of();
        }
        TreeNode[] path = currentNode.getPath();
        List<NodeKey> result = new ArrayList<>(path.length);
        for (TreeNode treeNode : path) {
            JMeterTreeNode node = (JMeterTreeNode) treeNode;
            result.add(NodeKey.from(node));
        }
        return result;
    }

    private static void restoreSelectedPath(GuiPackage guiInstance, List<NodeKey> selectedPath) {
        JMeterTreeNode current = resolvePath(guiInstance, selectedPath);
        if (current == null) {
            return;
        }
        guiInstance.getTreeListener().setSelectionPathWithoutEdit(new TreePath(current.getPath()));
    }

    private static List<List<NodeKey>> currentExpandedPaths(GuiPackage guiInstance) {
        JTree tree = guiInstance.getMainFrame().getTree();
        List<List<NodeKey>> result = new ArrayList<>();
        for (int row = 0; row < tree.getRowCount(); row++) {
            if (tree.isExpanded(row)) {
                result.add(toNodeKeys(tree.getPathForRow(row)));
            }
        }
        return result;
    }

    private static List<NodeKey> toNodeKeys(TreePath path) {
        Object[] components = path.getPath();
        List<NodeKey> keys = new ArrayList<>(components.length);
        for (Object component : components) {
            keys.add(NodeKey.from((JMeterTreeNode) component));
        }
        return keys;
    }

    private static void restoreExpandedPaths(GuiPackage guiInstance, List<List<NodeKey>> expandedPaths) {
        JTree tree = guiInstance.getMainFrame().getTree();
        // rows are captured top-down, so expanding in order keeps ancestors
        // expanded before their descendants
        for (List<NodeKey> keys : expandedPaths) {
            JMeterTreeNode node = resolvePath(guiInstance, keys);
            if (node != null) {
                tree.expandPath(new TreePath(node.getPath()));
            }
        }
        // ensure the root is always expanded even if nothing was captured
        tree.expandRow(0);
    }

    private static JMeterTreeNode resolvePath(GuiPackage guiInstance, List<NodeKey> path) {
        if (path.size() < 2) {
            return null;
        }
        JMeterTreeNode current = (JMeterTreeNode) guiInstance.getTreeModel().getRoot();
        for (int i = 1; i < path.size(); i++) {
            current = findChild(current, path.get(i));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static JMeterTreeNode findChild(JMeterTreeNode parent, NodeKey key) {
        int occurrence = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
            if (key.matches(child)) {
                if (occurrence == key.occurrence) {
                    return child;
                }
                occurrence++;
            }
        }
        return null;
    }

    /**
     * Load the undo item into acceptorModel tree
     *
     * @param acceptorModel tree to accept the data
     * @param guiInstance {@link GuiPackage} to be used
     */
    private void loadHistoricalTree(JMeterTreeModel acceptorModel, GuiPackage guiInstance, HashTree newModel) {
        acceptorModel.removeTreeModelListener(this);
        try {
            guiInstance.getTreeModel().clearTestPlan();
            guiInstance.addSubTree(newModel);
        } catch (Exception ex) {
            log.error("Failed to load from history", ex);
        } finally {
            acceptorModel.addTreeModelListener(this);
        }
    }

    /**
     * @return true if remaining items
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * @return true if not at first element
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * Record the changes in the node as the undo step
     *
     * @param tme {@link TreeModelEvent} with event details
     */
    @Override
    public void treeNodesChanged(TreeModelEvent tme) {
        String name = ((JMeterTreeNode) tme.getTreePath().getLastPathComponent()).getName();
        log.debug("Nodes changed {}", name);
        final JMeterTreeModel sender = (JMeterTreeModel) tme.getSource();
        add(sender, "Node changed " + name);
    }

    /**
     * Record adding nodes as the undo step
     *
     * @param tme {@link TreeModelEvent} with event details
     */
    @Override
    public void treeNodesInserted(TreeModelEvent tme) {
        String name = ((JMeterTreeNode) tme.getTreePath().getLastPathComponent()).getName();
        log.debug("Nodes inserted {}", name);
        final JMeterTreeModel sender = (JMeterTreeModel) tme.getSource();
        add(sender, "Add " + name);
    }

    /**
     * Record deleting nodes as the undo step
     *
     * @param tme {@link TreeModelEvent} with event details
     */
    @Override
    public void treeNodesRemoved(TreeModelEvent tme) {
        String name = ((JMeterTreeNode) tme.getTreePath().getLastPathComponent()).getName();
        log.debug("Nodes removed: {}", name);
        add((JMeterTreeModel) tme.getSource(), "Remove " + name);
    }

    /**
     * Record some other change
     *
     * @param tme {@link TreeModelEvent} with event details
     */
    @Override
    public void treeStructureChanged(TreeModelEvent tme) {
        log.debug("Nodes struct changed");
        add((JMeterTreeModel) tme.getSource(), "Complex Change");
    }

    /**
     * @return true if history is enabled
     */
    public static boolean isEnabled() {
        return HISTORY_SIZE > 0;
    }

    /**
     * Register HistoryListener
     * @param listener to add to our listeners
     */
    public void registerHistoryListener(HistoryListener listener) {
        listeners.add(listener);
    }

    /**
     * Notify listener
     */
    private void notifyListeners() {
        for (HistoryListener listener : listeners) {
            listener.notifyChangeInHistory(this);
        }
    }

    private void addEdit(UndoHistoryItem item) {
        if (isTransaction()) {
            UndoTransaction transaction = transactions.peek();
            transaction.after = item;
            transaction.size++;
            lastKnownState = item;
            return;
        }
        boolean changed = addGlobalEdit(item, lastKnownState);
        lastKnownState = item;
        if (changed) {
            notifyListeners();
        }
    }

    private boolean addGlobalEdit(UndoHistoryItem item, UndoHistoryItem previous) {
        if (previous == null) {
            log.debug("Skipping undo since there is no previous known state");
            return false;
        }
        if (item.hasSameTree(previous)) {
            log.debug("Skipping undo since only tree UI state changed");
            return false;
        }
        undoStack.push(new UndoableChange(previous, item));
        redoStack.clear();
        trimUndoHistory();
        return true;
    }

    void endUndoTransaction() {
        if(!isEnabled()) {
            return;
        }
        if (!isTransaction()) {
            log.error("Undo transaction ended without beginning", new Exception());
            return;
        }
        UndoTransaction transaction = transactions.pop();
        if (!transaction.isEmpty()) {
            if (isTransaction()) {
                UndoTransaction parent = transactions.peek();
                parent.after = transaction.after;
                parent.size += transaction.size;
            } else {
                GuiPackage guiPackage = GuiPackage.getInstance();
                UndoHistoryItem after = guiPackage != null
                        ? createHistoryItem(guiPackage.getTreeModel(), transaction.getComment())
                        : null;
                if (after == null) {
                    after = transaction.after;
                }
                boolean changed = addGlobalEdit(after, transaction.before);
                lastKnownState = after;
                if (changed) {
                    notifyListeners();
                }
            }
        }
    }

    void beginUndoTransaction() {
        if (isEnabled()) {
            transactions.push(new UndoTransaction(lastKnownState));
        }
    }

    /**
     * Record the current tree state after a model mutation that does not emit a
     * Swing tree event, for example direct changes to a TestElement property.
     *
     * @param comment description of the change
     */
    void addCurrentState(String comment) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage != null) {
            add(guiPackage.getTreeModel(), comment);
        }
    }

    void withoutUndo(Runnable action) {
        beginWithoutUndo();
        try {
            action.run();
        } finally {
            endWithoutUndo();
        }
    }

    void beginWithoutUndo() {
        suspended++;
    }

    void endWithoutUndo() {
        if (suspended == 0) {
            log.warn("Undo suspension ended without beginning");
            return;
        }
        suspended--;
    }

    boolean isTransaction() {
        return !transactions.isEmpty();
    }

    private void setLastKnownState(UndoHistoryItem previous) {
        this.lastKnownState = previous;
    }

    private void trimUndoHistory() {
        while (undoStack.size() > HISTORY_SIZE) {
            undoStack.removeLast();
        }
    }

    private static final class UndoableChange {
        private final UndoHistoryItem before;
        private final UndoHistoryItem after;

        private UndoableChange(UndoHistoryItem before, UndoHistoryItem after) {
            this.before = before;
            this.after = after;
        }
    }

    private static final class UndoTransaction {
        private final UndoHistoryItem before;
        private UndoHistoryItem after;
        private int size;

        private UndoTransaction(UndoHistoryItem before) {
            this.before = before;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private String getComment() {
            return after == null ? "Complex Change" : after.getComment();
        }
    }

    private static final class NodeKey {
        private final String className;
        private final String name;
        private final int occurrence;

        private NodeKey(String className, String name, int occurrence) {
            this.className = className;
            this.name = name;
            this.occurrence = occurrence;
        }

        private static NodeKey from(JMeterTreeNode node) {
            int occurrence = 0;
            TreeNode parentNode = node.getParent();
            if (parentNode instanceof JMeterTreeNode parent) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
                    if (child == node) {
                        break;
                    }
                    if (sameNodeIdentity(child, node)) {
                        occurrence++;
                    }
                }
            }
            return new NodeKey(node.getTestElement().getClass().getName(), node.getName(), occurrence);
        }

        private boolean matches(JMeterTreeNode node) {
            return className.equals(node.getTestElement().getClass().getName())
                    && Objects.equals(name, node.getName());
        }

        private static boolean sameNodeIdentity(JMeterTreeNode left, JMeterTreeNode right) {
            return left.getTestElement().getClass() == right.getTestElement().getClass()
                    && Objects.equals(left.getName(), right.getName());
        }
    }

}
