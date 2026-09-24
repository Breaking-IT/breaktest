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

import java.awt.Color;
import java.awt.Component;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JMeterUIDefaults;

/**
 * Renders a View Results Tree node with its status icon, the elapsed time of a sampler or
 * transaction that is still running, and search matches.
 */
final class ResultsNodeRenderer extends DefaultTreeCellRenderer {
    private static final long serialVersionUID = 4159626601097711566L;

    private static final Border RED_BORDER = BorderFactory.createLineBorder(Color.red);
    private static final Border BLUE_BORDER = BorderFactory.createLineBorder(Color.blue);

    private final transient Supplier<Set<SampleResult>> runningResults;

    /**
     * @param runningResults the samplers and transactions that have started but not finished
     */
    ResultsNodeRenderer(Supplier<Set<SampleResult>> runningResults) {
        this.runningResults = runningResults;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean sel, boolean expanded, boolean leaf, int row, boolean focus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, focus);
        boolean failure = true;
        SampleResult running = null;
        Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
        if (userObject instanceof SampleResult sampleResult) {
            failure = !sampleResult.isSuccessful();
            running = runningResults.get().contains(sampleResult) ? sampleResult : null;
        } else if (userObject instanceof AssertionResult assertion) {
            failure = assertion.isError() || assertion.isFailure();
        }

        // Set the status for the node
        if (running != null) {
            long elapsed = Math.max(0, System.currentTimeMillis() - running.getStartTime());
            this.setText(String.format(Locale.ROOT, "%s (%s %.1f s)", getText(),
                    JMeterUtils.getResString("view_results_transaction_running"), elapsed / 1000.0)); // $NON-NLS-1$
            this.setIcon(ResultStatusIcons.RUNNING);
        } else if (failure) {
            this.setForeground(UIManager.getColor(JMeterUIDefaults.LABEL_ERROR_FOREGROUND));
            this.setIcon(ResultStatusIcons.FAILURE);
        } else {
            this.setIcon(ResultStatusIcons.SUCCESS);
        }

        // Handle search related rendering
        SearchableTreeNode node = (SearchableTreeNode) value;
        if (node.isNodeHasMatched()) {
            setBorder(RED_BORDER);
        } else if (node.isChildrenNodesHaveMatched()) {
            setBorder(BLUE_BORDER);
        } else {
            setBorder(null);
        }
        return this;
    }
}
