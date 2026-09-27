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

import java.awt.event.ActionEvent;
import java.util.HashSet;
import java.util.Set;

import javax.swing.tree.TreePath;

import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractAction;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.scenario.TestFragmentsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

import com.google.auto.service.AutoService;

/**
 * Makes a transaction reusable: moves it to the Test Fragments section and puts a Module Controller with the same
 * name in its place, so the thread group still runs it.
 */
@AutoService(Command.class)
public class MoveToTestFragments extends AbstractAction {

    private static final Set<String> COMMANDS = Set.of(ActionNames.MOVE_TO_TEST_FRAGMENTS);

    @Override
    public Set<String> getActionNames() {
        return COMMANDS;
    }

    @Override
    public void doAction(ActionEvent e) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
        JMeterTreeNode transaction = guiPackage.getTreeListener().getCurrentNode();
        if (transaction == null || !(transaction.getTestElement() instanceof TransactionController)) {
            return;
        }
        JMeterTreeModel treeModel = guiPackage.getTreeModel();
        JMeterTreeNode fragments = treeModel.getNodesOfType(TestFragmentsSection.class).stream().findFirst().orElse(null);
        if (fragments == null) {
            JMeterUtils.reportErrorToUser(JMeterUtils.getResString("move_to_test_fragments_no_section")); // $NON-NLS-1$
            return;
        }
        guiPackage.beginUndoTransaction();
        try {
            JMeterTreeNode module = moveToTestFragments(treeModel, transaction, fragments);
            guiPackage.getTreeListener().setSelectionPathWithoutEdit(new TreePath(module.getPath()));
            ActionRouter.getInstance().doActionNow(new ActionEvent(e.getSource(), e.getID(), ActionNames.EDIT));
        } finally {
            guiPackage.endUndoTransaction();
        }
    }

    /**
     * @param treeModel the test plan tree
     * @param transaction the transaction to move
     * @param fragments the Test Fragments section
     * @return the node of the Module Controller that now runs the transaction
     */
    static JMeterTreeNode moveToTestFragments(JMeterTreeModel treeModel, JMeterTreeNode transaction,
            JMeterTreeNode fragments) {
        JMeterTreeNode parent = (JMeterTreeNode) transaction.getParent();
        int index = parent.getIndex(transaction);
        String name = transaction.getName();
        // Module Controllers find their target by name, so the moved transaction must be unique in the section
        transaction.getTestElement().setName(uniqueName(name, fragments));

        treeModel.removeNodeFromParent(transaction);
        treeModel.insertNodeInto(transaction, fragments, fragments.getChildCount());

        ModuleController module = new ModuleController();
        module.setName(name);
        module.setProperty(TestElement.GUI_CLASS, ModuleControllerGui.class.getName());
        module.setProperty(TestElement.TEST_CLASS, ModuleController.class.getName());
        module.setSelectedNode(transaction);
        JMeterTreeNode moduleNode = new JMeterTreeNode(module, treeModel);
        treeModel.insertNodeInto(moduleNode, parent, index);
        return moduleNode;
    }

    private static String uniqueName(String name, JMeterTreeNode fragments) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < fragments.getChildCount(); i++) {
            used.add(((JMeterTreeNode) fragments.getChildAt(i)).getName());
        }
        String candidate = name;
        for (int i = 2; used.contains(candidate); i++) {
            candidate = name + " (" + i + ")";
        }
        return candidate;
    }
}
