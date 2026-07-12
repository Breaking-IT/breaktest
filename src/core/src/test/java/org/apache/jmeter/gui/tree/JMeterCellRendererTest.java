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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JTable;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.Test;

class JMeterCellRendererTest {

    @Test
    void postProcessorWithoutPostProcessorInClassNameUsesPostProcessorIcon() {
        JMeterTreeNode node = new JMeterTreeNode(new DummyExtractor(), null);

        assertEquals(
                JMeterCellRenderer.ModernTreeIcon.Kind.POST_PROCESSOR,
                JMeterCellRenderer.ModernTreeIcon.kindFor(node));
    }

    @Test
    void regexExtractorGuiClassUsesPostProcessorIcon() {
        DummyTestElement element = new DummyTestElement();
        element.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.extractor.gui.RegexExtractorGui");
        JMeterTreeNode node = new JMeterTreeNode(element, null);

        assertEquals(
                JMeterCellRenderer.ModernTreeIcon.Kind.POST_PROCESSOR,
                JMeterCellRenderer.ModernTreeIcon.kindFor(node));
    }

    @Test
    void randomDelaySummaryResolvesTestPlanVariables() {
        TestPlan plan = new TestPlan("Test Plan");
        plan.getArguments().addArgument("ThinkTimeMin", "1000");
        plan.getArguments().addArgument("ThinkTimeMax", "3000");
        JMeterTreeNode transactionNode = transactionNode(plan, TransactionController.DELAY_RANDOM,
                "0", "${ThinkTimeMin}", "${ThinkTimeMax}");

        assertEquals("(2s)", JMeterCellRenderer.delaySummary(transactionNode));
    }

    @Test
    void fixedDelaySummaryResolvesTopLevelUserDefinedVariable() {
        TestPlan plan = new TestPlan("Test Plan");
        JMeterTreeNode planNode = new JMeterTreeNode(plan, null);
        Arguments variables = new Arguments();
        variables.addArgument("ThinkTime", "1500");
        planNode.add(new JMeterTreeNode(variables, null));
        JMeterTreeNode transactionNode = transactionNode(planNode, TransactionController.DELAY_FIXED,
                "${ThinkTime}", "0", "0");

        assertEquals("(1.5s)", JMeterCellRenderer.delaySummary(transactionNode));
    }

    @Test
    void unresolvedDelayVariableDoesNotShowSummary() {
        JMeterTreeNode transactionNode = transactionNode(new TestPlan("Test Plan"),
                TransactionController.DELAY_FIXED, "${Missing}", "0", "0");

        assertNull(JMeterCellRenderer.delaySummary(transactionNode));
    }

    @Test
    void delaySummarySurvivesEditingVariableThroughTestPlanGui() {
        TestPlan plan = new TestPlan("Test Plan");
        plan.getArguments().addArgument("ThinkTimeMin", "1000");
        plan.getArguments().addArgument("ThinkTimeMax", "3000");
        JMeterTreeNode transactionNode = transactionNode(plan, TransactionController.DELAY_RANDOM,
                "0", "${ThinkTimeMin}", "${ThinkTimeMax}");
        TestPlanGui gui = new TestPlanGui();
        gui.configure(plan);
        JTable variablesTable = findTable(gui);
        assertNotNull(variablesTable);
        variablesTable.setValueAt("5000", 1, 1);

        gui.modifyTestElement(plan);

        assertEquals("(3s)", JMeterCellRenderer.delaySummary(transactionNode));
    }

    private static JTable findTable(Component component) {
        if (component instanceof JTable table) {
            return table;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTable table = findTable(child);
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }

    private static JMeterTreeNode transactionNode(TestPlan plan, String mode,
            String fixed, String min, String max) {
        return transactionNode(new JMeterTreeNode(plan, null), mode, fixed, min, max);
    }

    private static JMeterTreeNode transactionNode(JMeterTreeNode planNode, String mode,
            String fixed, String min, String max) {
        TransactionController transaction = new TransactionController();
        transaction.setDelayMode(mode);
        transaction.setFixedDelay(fixed);
        transaction.setDelayMin(min);
        transaction.setDelayMax(max);
        JMeterTreeNode transactionNode = new JMeterTreeNode(transaction, null);
        planNode.add(transactionNode);
        return transactionNode;
    }

    private static class DummyExtractor extends AbstractTestElement implements PostProcessor {
        private static final long serialVersionUID = 1L;

        @Override
        public void process() {
            // NOOP
        }
    }

    private static class DummyTestElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;
    }
}
