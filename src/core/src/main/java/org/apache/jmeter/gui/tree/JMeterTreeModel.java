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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;

import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;

import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.control.gui.TestFragmentControllerGui;
import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.scenario.ListenersSection;
import org.apache.jmeter.scenario.NonTestElementsSection;
import org.apache.jmeter.scenario.Profile;
import org.apache.jmeter.scenario.ProfilesSection;
import org.apache.jmeter.scenario.Scenario;
import org.apache.jmeter.scenario.ScenarioPlanMigration;
import org.apache.jmeter.scenario.ScenariosSection;
import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.scenario.TestFragmentsSection;
import org.apache.jmeter.scenario.TestPlanSection;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.scenario.gui.ScenarioGui;
import org.apache.jmeter.scenario.gui.UniqueNames;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

public class JMeterTreeModel extends DefaultTreeModel {

    private static final long serialVersionUID = 240L;

    private int bulkUpdateDepth;

    /**
     * Deprecated after remove WorkBench
     * @param tp - Test Plan
     * @param wb - WorkBench
     * @deprecated since 4.0
     */
    @Deprecated
    public JMeterTreeModel(TestElement tp, TestElement wb) {
        this(tp);
    }

    public JMeterTreeModel(TestElement tp) {
        super(new JMeterTreeNode(tp, null));
        initTree(tp);
    }

    public JMeterTreeModel() {
        this(new TestPlanGui().createTestElement());
        addDefaultSections();
    }

    /**
     * Hack to allow TreeModel to be used in non-GUI and headless mode.
     *
     * @deprecated - only for use by JMeter class!
     * @param o - dummy
     */
    @Deprecated
    public JMeterTreeModel(Object o) {
        this(new TestPlan());
    }

    /**
     * Returns a list of tree nodes that hold objects of the given class type.
     * If none are found, an empty list is returned.
     * @param type The type of nodes, which are to be collected
     * @return a list of tree nodes of the given <code>type</code>, or an empty list
     */
    public List<JMeterTreeNode> getNodesOfType(Class<?> type) {
        List<JMeterTreeNode> nodeList = new ArrayList<>();
        traverseAndFind(type, (JMeterTreeNode) this.getRoot(), nodeList);
        return nodeList;
    }

    /**
     * Get the node for a given TestElement object.
     * @param userObject The object to be found in this tree
     * @return the node corresponding to the <code>userObject</code>
     */
    public JMeterTreeNode getNodeOf(TestElement userObject) {
        return traverseAndFind(userObject, (JMeterTreeNode) getRoot());
    }

    /**
     * Adds the sub tree at the given node. Returns a boolean indicating whether
     * the added sub tree was a full test plan.
     *
     * @param subTree
     *            The {@link HashTree} which is to be inserted into
     *            <code>current</code>
     * @param current
     *            The node in which the <code>subTree</code> is to be inserted.
     *            Will be overridden, when an instance of {@link TestPlan}
     * @return newly created sub tree now found at <code>current</code>
     * @throws IllegalUserActionException
     *             when <code>current</code> is not an instance of
     *             {@link AbstractConfigGui} and no instance of {@link TestPlan}
     *             <code>subTree</code>
     */
    public HashTree addSubTree(HashTree subTree, JMeterTreeNode current) throws IllegalUserActionException {
        return addSubTree(subTree, current, true);
    }

    /**
     * Adds the sub tree at the given node.
     *
     * @param subTree The {@link HashTree} which is to be inserted into
     *            <code>current</code>
     * @param current The node in which the <code>subTree</code> is to be inserted.
     * @param configureGui if true, run loaded elements through their GUI components
     * @return newly created sub tree now found at <code>current</code>
     * @throws IllegalUserActionException
     *             when <code>current</code> is not an instance of
     *             {@link AbstractConfigGui} and no instance of {@link TestPlan}
     *             <code>subTree</code>
     */
    public HashTree addSubTree(HashTree subTree, JMeterTreeNode current, boolean configureGui)
            throws IllegalUserActionException {
        beginBulkUpdate();
        JMeterTreeNode resultRoot;
        try {
            resultRoot = addSubTreeNodes(subTree, current, configureGui);
        } finally {
            endBulkUpdate();
        }
        return getCurrentSubTree(resultRoot);
    }

    /** Populates controller-resolution context without copying it back into a HashTree. */
    public void addSubTreeForExecution(HashTree subTree, JMeterTreeNode current) throws IllegalUserActionException {
        beginBulkUpdate();
        try {
            addSubTreeNodes(subTree, current, false);
        } finally {
            endBulkUpdate();
        }
    }

    private JMeterTreeNode addSubTreeNodes(HashTree subTree, JMeterTreeNode current, boolean configureGui)
            throws IllegalUserActionException {
        for (Object o : subTree.list()) {
            TestElement item = (TestElement) o;
            if (item instanceof TestPlan tp) {
                current = (JMeterTreeNode) ((JMeterTreeNode) getRoot()).getChildAt(0);
                final TestPlan userObject = (TestPlan) current.getUserObject();
                userObject.addTestElement(item);
                userObject.setName(item.getName());
                userObject.setFunctionalMode(tp.isFunctionalMode());
                userObject.setSerialized(tp.isSerialized());
                addSubTreeNodes(subTree.getTree(item), current, configureGui);
            } else if (isWorkbench(item)) {
                //Move item from WorkBench to TestPlan
                HashTree workbenchTree = subTree.getTree(item);
                if (!workbenchTree.isEmpty()) {
                    moveWorkBenchToTestPlan(current, workbenchTree, configureGui);
                }
            } else {
                addSubTreeNodes(subTree.getTree(item), addComponent(item, current, configureGui), configureGui);
            }
        }
        return current;
    }

    @SuppressWarnings("deprecation")
    private static boolean isWorkbench(TestElement item) {
        return item instanceof org.apache.jmeter.testelement.WorkBench;
    }

    /**
     * Add a {@link TestElement} to a {@link JMeterTreeNode}
     * @param component The {@link TestElement} to be used as data for the newly created node
     * @param node The {@link JMeterTreeNode} into which the newly created node is to be inserted
     * @return new {@link JMeterTreeNode} for the given <code>component</code>
     * @throws IllegalUserActionException
     *             when the user object for the <code>node</code> is not an instance
     *             of {@link AbstractConfigGui}
     */
    public JMeterTreeNode addComponent(TestElement component, JMeterTreeNode node) throws IllegalUserActionException {
        return addComponent(component, node, true);
    }

    /**
     * Elements added to a test plan organised in sections go into their section rather than directly under the
     * test plan.
     * @param parent the node the element is being added to
     * @param element the element being added
     * @return the section node of the element, or {@code parent} when the element is not added to a sectioned
     *     test plan or has no section
     */
    public static JMeterTreeNode sectionNodeFor(JMeterTreeNode parent, TestElement element) {
        if (parent == null || !(parent.getUserObject() instanceof TestPlan)) {
            return parent;
        }
        Class<? extends TestPlanSection> section = ScenarioPlanMigration.sectionFor(element);
        if (section == null) {
            return parent;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof JMeterTreeNode child && section.isInstance(child.getUserObject())) {
                // Configuration goes into the shared profile, which applies to every thread group
                return child.getUserObject() instanceof ProfilesSection && !(element instanceof Profile)
                        ? sharedProfileNode(child)
                        : child;
            }
        }
        return parent;
    }

    private static JMeterTreeNode sharedProfileNode(JMeterTreeNode profilesSection) {
        for (int i = 0; i < profilesSection.getChildCount(); i++) {
            if (profilesSection.getChildAt(i) instanceof JMeterTreeNode child
                    && child.getUserObject() instanceof SharedProfile) {
                return child;
            }
        }
        return profilesSection;
    }

    /**
     * Like {@link #sectionNodeFor(JMeterTreeNode, TestElement)}, but creates the Non-Test Elements section the first
     * time such an element is added to a test plan organised in sections.
     * @param parent the node the element is being added to
     * @param element the element being added
     * @return the node to add the element to
     */
    public JMeterTreeNode addTargetFor(JMeterTreeNode parent, TestElement element) {
        JMeterTreeNode target = sectionNodeFor(parent, element);
        if (target == parent && parent != null && parent.getUserObject() instanceof TestPlan
                && ScenarioPlanMigration.sectionFor(element) == NonTestElementsSection.class
                && hasSections(parent)) {
            target = addDefaultNode(ScenarioPlanMigration.newSection(NonTestElementsSection.class), parent);
        }
        return target;
    }

    private static boolean hasSections(JMeterTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof JMeterTreeNode child && child.getUserObject() instanceof TestPlanSection) {
                return true;
            }
        }
        return false;
    }

    /**
     * A copied element must not take over the role of its original: a copied active scenario becomes inactive and
     * a copied default profile is no longer the default. Elements that were cut and pasted have no original left
     * and keep their role. Copied thread groups get their own id when they are inserted, see {@link UniqueNames}.
     * @param element an element about to be added to this tree
     */
    public void resolveCopyConflicts(TestElement element) {
        if (element instanceof Scenario && element.isEnabled()
                && anyOther(Scenario.class, element, TestElement::isEnabled)) {
            element.setEnabled(false);
        } else if (element instanceof Profile profile && profile.isDefault()
                && anyOther(Profile.class, element, other -> ((Profile) other).isDefault())) {
            profile.setDefault(false);
        }
        // A copied thread group gets its own id when it is inserted, see UniqueNames
    }

    private boolean anyOther(Class<?> type, TestElement element, Predicate<TestElement> condition) {
        return getNodesOfType(type).stream()
                .map(JMeterTreeNode::getTestElement)
                .anyMatch(other -> other != element && condition.test(other));
    }

    private JMeterTreeNode addComponent(TestElement component, JMeterTreeNode parent, boolean configureGui)
            throws IllegalUserActionException {
        resolveCopyConflicts(component);
        JMeterTreeNode node = addTargetFor(parent, component);
        if (node.getUserObject() instanceof AbstractConfigGui) {
            throw new IllegalUserActionException("This node cannot hold sub-elements");
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage != null && configureGui) {
            // The node can be added in non GUI mode at startup
            guiPackage.updateCurrentNode();
            JMeterGUIComponent guicomp = guiPackage.getGui(component);
            if (guicomp == null) {
                throw new IllegalUserActionException("Could not create GUI component "
                        + component.getPropertyAsString(TestElement.GUI_CLASS)
                        + " for " + component.getPropertyAsString(TestElement.TEST_CLASS));
            }
            guicomp.clearGui();
            guicomp.configure(component);
            guicomp.modifyTestElement(component);
            guiPackage.getCurrentGui(); // put the gui object back
                                        // to the way it was.
        } else if (guiPackage != null && component instanceof ResultCollector) {
            initializeLoadedResultCollectorGui(component, guiPackage);
        }
        JMeterTreeNode newNode = new JMeterTreeNode(component, this);

        // This check the state of the TestElement and if returns false it
        // disable the loaded node
        try {
            newNode.setEnabled(component.isEnabled());
        } catch (Exception e) { // TODO - can this ever happen?
            newNode.setEnabled(true);
        }

        this.insertNodeInto(newNode, node, node.getChildCount());
        return newNode;
    }

    private static void initializeLoadedResultCollectorGui(TestElement component, GuiPackage guiPackage)
            throws IllegalUserActionException {
        JMeterGUIComponent guicomp = guiPackage.getGui(component);
        if (guicomp == null) {
            throw new IllegalUserActionException("Could not create GUI component "
                    + component.getPropertyAsString(TestElement.GUI_CLASS)
                    + " for " + component.getPropertyAsString(TestElement.TEST_CLASS));
        }
        guicomp.clearGui();
        guicomp.configure(component);
        guicomp.modifyTestElement(component);
    }

    @Override
    public void insertNodeInto(MutableTreeNode newChild, MutableTreeNode parent, int index) {
        parent.insert(newChild, index);
        if (newChild instanceof JMeterTreeNode node && node.getUserObject() instanceof TestElement) {
            // Every way of adding an element ends here: add, paste, duplicate, drag and drop, loading, the AI agent
            UniqueNames.apply(this, node);
        }
        if (bulkUpdateDepth == 0) {
            nodesWereInserted(parent, new int[] { index });
        }
    }

    private void beginBulkUpdate() {
        bulkUpdateDepth++;
    }

    private void endBulkUpdate() {
        bulkUpdateDepth--;
        if (bulkUpdateDepth == 0) {
            nodeStructureChanged((JMeterTreeNode) getRoot());
        }
    }

    public void removeNodeFromParent(JMeterTreeNode node) {
        if (!(node.getUserObject() instanceof TestPlan)) {
            super.removeNodeFromParent(node);
        }
    }

    @SuppressWarnings("JdkObsolete")
    private static void traverseAndFind(Class<?> type, JMeterTreeNode node, List<? super JMeterTreeNode> nodeList) {
        if (type.isInstance(node.getUserObject())) {
            nodeList.add(node);
        }
        Enumeration<?> enumNode = node.children();
        while (enumNode.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode)enumNode.nextElement();
            traverseAndFind(type, child, nodeList);
        }
    }

    @SuppressWarnings("JdkObsolete")
    private static JMeterTreeNode traverseAndFind(TestElement userObject, JMeterTreeNode node) {
        if (userObject == node.getUserObject()) {
            return node;
        }
        Enumeration<?> enumNode = node.children();
        while (enumNode.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode)enumNode.nextElement();
            JMeterTreeNode result = traverseAndFind(userObject, child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Get the current sub tree for a {@link JMeterTreeNode}
     * @param node The {@link JMeterTreeNode} from which the sub tree is to be taken
     * @return newly copied sub tree
     */
    @SuppressWarnings("JdkObsolete")
    public HashTree getCurrentSubTree(JMeterTreeNode node) {
        ListedHashTree hashTree = new ListedHashTree(node);
        Enumeration<?> enumNode = node.children();
        while (enumNode.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode)enumNode.nextElement();
            hashTree.add(node, getCurrentSubTree(child));
        }
        return hashTree;
    }

    /**
     * Get the {@link TestPlan} from the root of this tree
     * @return The {@link TestPlan} found at the root of this tree
     */
    public HashTree getTestPlan() {
        return getCurrentSubTree((JMeterTreeNode) ((JMeterTreeNode) this.getRoot()).getChildAt(0));
    }


    /**
     * Clear the test plan, and use default node for test plan.
     *
     * N.B. Should only be called by {@link GuiPackage#clearTestPlan()}
     */
    public void clearTestPlan() {
        TestElement tp = new TestPlanGui().createTestElement();
        clearTestPlan(tp);
    }

    /**
     * Organises a new test plan in sections, with one scenario ready to receive workloads.
     * Only for new test plans: a restored or loaded plan brings its own sections.
     */
    public void addDefaultSections() {
        JMeterTreeNode planNode = (JMeterTreeNode) getChild(getRoot(), 0);
        // In ScenarioPlanMigration.SECTION_ORDER; Non-Test Elements is only added when needed
        addDefaultNode(ScenarioPlanMigration.newSection(ListenersSection.class), planNode);
        JMeterTreeNode scenarios = addDefaultNode(ScenarioPlanMigration.newSection(ScenariosSection.class), planNode);
        addDefaultNode(new ScenarioGui().createTestElement(), scenarios);
        JMeterTreeNode profiles = addDefaultNode(ScenarioPlanMigration.newSection(ProfilesSection.class), planNode);
        addDefaultNode(ScenarioPlanMigration.newSharedProfile(), profiles);
        addDefaultNode(ScenarioPlanMigration.newSection(TestFragmentsSection.class), planNode);
        addDefaultNode(ScenarioPlanMigration.newSection(ThreadGroupsSection.class), planNode);
    }

    private JMeterTreeNode addDefaultNode(TestElement element, JMeterTreeNode parent) {
        JMeterTreeNode node = new JMeterTreeNode(element, this);
        insertNodeInto(node, parent, parent.getChildCount());
        return node;
    }

    /**
     * Clear the test plan, and use specified node for test plan
     *
     * N.B. Should only be called by {@link GuiPackage#clearTestPlan(TestElement)}
     *
     * @param testPlan the node to use as the testplan top node
     */
    public void clearTestPlan(TestElement testPlan) {
        // Remove testplan nodes
        int children = getChildCount(getRoot());
        while (children > 0) {
            JMeterTreeNode child = (JMeterTreeNode)getChild(getRoot(), 0);
            super.removeNodeFromParent(child);
            children = getChildCount(getRoot());
        }
        // Init the tree
        initTree(testPlan); // Assumes this is only called from GUI mode
    }

    /**
     * Initialize the model with nodes for testplan.
     *
     * @param tp the element to use as testplan
     */
    private void initTree(TestElement tp) {
        // Insert the test plan node
        insertNodeInto(new JMeterTreeNode(tp, this), (JMeterTreeNode) getRoot(), 0);
        // Let others know that the tree content has changed.
        // This should not be necessary, but without it, nodes are not shown when the user
        // uses the Close menu item
        nodeStructureChanged((JMeterTreeNode)getRoot());
    }


    /**
     * Move all Non-Test Elements from WorkBench to TestPlan root.
     * Other Test Elements will be move to WorkBench Test Fragment in TestPlan
     * @param current - TestPlan root
     * @param workbenchTree - WorkBench hash tree
     */
    private void moveWorkBenchToTestPlan(JMeterTreeNode current, HashTree workbenchTree, boolean configureGui)
            throws IllegalUserActionException {
        Object[] workbenchTreeArray = workbenchTree.getArray();
        if (GuiPackage.getInstance() != null) {
            for (Object node : workbenchTreeArray) {
                if (isNonTestElement(node)) {
                    HashTree subtree = workbenchTree.getTree(node);
                    workbenchTree.remove(node);
                    HashTree tree = new HashTree();
                    tree.add(node);
                    tree.add(node, subtree);
                    ((TestElement) node).setEnabled(false);
                    addSubTreeNodes(tree, current, configureGui);
                }
            }
        }

        if (!workbenchTree.isEmpty()) {
            HashTree testFragmentTree = new HashTree();
            TestFragmentController testFragmentController = new TestFragmentController();
            testFragmentController.setName("WorkBench Test Fragment");
            testFragmentController.setProperty(TestElement.GUI_CLASS, TestFragmentControllerGui.class.getName());
            testFragmentController.setEnabled(false);
            testFragmentTree.add(testFragmentController);
            testFragmentTree.add(testFragmentController, workbenchTree);
            addSubTreeNodes(testFragmentTree, current, configureGui);
        }
    }

    /**
     * Is element :
     * <ul>
     *  <li>HTTP(S) Test Script Recorder</li>
     *  <li>Mirror Server</li>
     *  <li>Property Display</li>
     * </ul>
     * @param node
     */
    private static boolean isNonTestElement(Object node) {
        JMeterTreeNode treeNode = new JMeterTreeNode((TestElement) node, null);
        Collection<String> categories = treeNode.getMenuCategories();
        if (categories != null) {
            for (String category : categories) {
                if (MenuFactory.NON_TEST_ELEMENTS.equals(category)) {
                    return true;
                }
            }
        }
        return false;
    }
}
