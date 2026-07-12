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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class HarImportActionTest {

    @Test
    void expandsOnlyImportedThreadGroupAndCollapsesItsTransactions() {
        JMeterTreeNode root = new JMeterTreeNode(new TestPlan("Root"), null);
        JMeterTreeNode plan = new JMeterTreeNode(new TestPlan("Test Plan"), null);
        root.add(plan);
        JMeterTreeNode existingGroup = threadGroup("Existing", plan);
        JMeterTreeNode existingTransaction = transaction("Existing transaction", existingGroup);
        JMeterTreeNode importedGroup = threadGroup("Imported", plan);
        JMeterTreeNode importedTransaction = transaction("Imported transaction", importedGroup);

        JTree tree = new JTree(new DefaultTreeModel(root));
        TreePath existingTransactionPath = new TreePath(existingTransaction.getPath());
        TreePath importedGroupPath = new TreePath(importedGroup.getPath());
        TreePath importedTransactionPath = new TreePath(importedTransaction.getPath());
        tree.expandPath(existingTransactionPath);
        tree.expandPath(importedTransactionPath);

        HarImportAction.expandImportedThreadGroup(tree, importedGroup);

        assertTrue(tree.isExpanded(importedGroupPath));
        assertFalse(tree.isExpanded(importedTransactionPath));
        assertTrue(tree.isExpanded(existingTransactionPath), "existing tree expansion is unchanged");
    }

    private static JMeterTreeNode threadGroup(String name, JMeterTreeNode parent) {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName(name);
        JMeterTreeNode node = new JMeterTreeNode(threadGroup, null);
        parent.add(node);
        return node;
    }

    private static JMeterTreeNode transaction(String name, JMeterTreeNode parent) {
        TransactionController transaction = new TransactionController();
        transaction.setName(name);
        JMeterTreeNode node = new JMeterTreeNode(transaction, null);
        node.add(new JMeterTreeNode(new DummyElement(), null));
        parent.add(node);
        return node;
    }

    private static final class DummyElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;
    }
}
