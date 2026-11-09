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

package org.apache.jmeter.readiness.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessAggregator;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.check.ReadinessReport;
import org.junit.jupiter.api.Test;

/**
 * Main wizard interactions, headless: the panel works without a window.
 */
class ReadinessWizardPanelTest extends JMeterTestCase {
    private static final ElementRef LOGIN = new ElementRef("1:1", "login", "HTTPSamplerProxy", List.of(0, 1, 0));
    private static final ElementRef CHECKOUT = new ElementRef("1:2", "checkout", "TransactionController",
            List.of(0, 1));

    private static final class FakeController implements ReadinessController {
        Listener listener;
        RunRequest started;
        final List<ElementRef> navigated = new ArrayList<>();
        final List<AcceptedException> removed = new ArrayList<>();
        boolean cancelled;

        @Override
        public List<GroupChoice> threadGroups() {
            return List.of(new GroupChoice("group:a", "Users", true), new GroupChoice("group:b", "Admins", false));
        }

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void start(RunRequest request) {
            started = request;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public void refresh() {
        }

        @Override
        public ReadinessReport latestReport() {
            return null;
        }

        @Override
        public void navigate(ElementRef element) {
            navigated.add(element);
        }

        @Override
        public void accept(ElementRef element, AcceptedException exception) {
        }

        @Override
        public void remove(ElementRef element, AcceptedException exception) {
            removed.add(exception);
        }

        @Override
        public String defaultAuthor() {
            return "";
        }
    }

    private static ReadinessReport report() {
        Finding blocker = new Finding(CheckId.UNRESOLVED_VARIABLES, LOGIN, "missing", Severity.BLOCKER,
                "'${missing}' is never defined", List.of("Cause: never defined"), CHECKOUT);
        AcceptedException accepted = new AcceptedException(CheckId.THINK_TIME, "missing-delay", "API step", "jo",
                "2026-10-17");
        Finding acceptedFinding = new Finding(CheckId.THINK_TIME, LOGIN, "missing-delay", Severity.REVIEW,
                "No think time", List.of(), CHECKOUT).withException(accepted);
        List<CheckResult> results = List.of(
                CheckResult.of(CheckId.UNRESOLVED_VARIABLES, List.of(blocker), true, "1 reference"),
                CheckResult.of(CheckId.THINK_TIME, List.of(acceptedFinding), true, "0 of 1"));
        ReadinessReport.UnusedException unused = new ReadinessReport.UnusedException(LOGIN,
                new AcceptedException(CheckId.UNUSED_EXTRACTIONS, "old", "obsolete", "", ""));
        return new ReadinessReport(Instant.now(), "f", List.of("Users"), ReadinessAggregator.aggregate(results),
                "Runtime evidence is current (Completed)", List.of(unused));
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    @Test
    void setupRunFindingsAndSummary() throws Exception {
        FakeController controller = new FakeController();
        ReadinessWizardPanel[] panel = new ReadinessWizardPanel[1];
        onEdt(() -> panel[0] = new ReadinessWizardPanel(controller));
        onEdt(() -> {
            ReadinessWizardPanel wizard = panel[0];
            assertEquals(ReadinessWizardPanel.SETUP, wizard.currentStep());
            // The tree selection preselects its thread group
            assertTrue(wizard.scopeSelected.isSelected());
            ReadinessController.RunRequest request = wizard.request();
            assertEquals(List.of("group:a"), request.groupIds());
            assertEquals(CheckId.values().length, request.checks().size());
            assertTrue(request.runValidation());
            assertEquals(3_000_000L, request.options().extractionBudgetNanos());
            assertEquals(20, request.options().offlineReruns());
            assertFalse(request.options().isRepeatabilityConfigured());

            wizard.next.doClick();
            assertNotNull(controller.started);
            assertEquals(ReadinessWizardPanel.RUN, wizard.currentStep());
            wizard.cancel.doClick();
            assertTrue(controller.cancelled);

            controller.listener.progress("Running");
            controller.listener.finished(report());
            assertEquals(ReadinessWizardPanel.FINDINGS, wizard.currentStep());
            assertEquals(2, wizard.shownFindings());

            // Enter on a finding navigates to its element
            assertTrue(wizard.select(o -> o instanceof Finding f && f.severity() == Severity.BLOCKER));
            wizard.findingsTree.getActionMap().get("goTo").actionPerformed(
                    new ActionEvent(wizard.findingsTree, ActionEvent.ACTION_PERFORMED, "goTo"));
            assertEquals(List.of(LOGIN), controller.navigated);
            assertTrue(wizard.acceptButton.isEnabled());
            assertFalse(wizard.removeButton.isEnabled());

            // Accepted findings can have their exception removed
            assertTrue(wizard.select(o -> o instanceof Finding f && f.isAccepted()));
            assertFalse(wizard.acceptButton.isEnabled());
            assertTrue(wizard.markButton.isEnabled(), "Think-time findings can be marked background activity");
            wizard.removeButton.doClick();
            assertEquals("missing-delay", controller.removed.get(0).findingKey());

            // Unused exceptions are listed so they do not linger
            assertTrue(wizard.select(o -> o instanceof ReadinessWizardPanel.UnusedItem));
            assertTrue(wizard.removeButton.isEnabled());
            wizard.goTo.doClick();
            assertEquals(2, controller.navigated.size());

            // Every row shows a status icon; a group shows its worst item
            DefaultMutableTreeNode byCheck = (DefaultMutableTreeNode) wizard.findingsTree.getModel().getRoot();
            // Checks are listed in check order: think time (accepted) before unresolved variables (blocker)
            assertEquals(StatusIcon.Kind.ACCEPTED, ReadinessWizardPanel.kindOf(
                    (DefaultMutableTreeNode) byCheck.getChildAt(0)));
            assertEquals(StatusIcon.Kind.FAILED, ReadinessWizardPanel.kindOf(
                    (DefaultMutableTreeNode) byCheck.getChildAt(1)));
            // Grouped by transaction
            wizard.setGroupByTransaction(true);
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) wizard.findingsTree.getModel().getRoot();
            assertEquals("Transaction: checkout", String.valueOf(
                    ((DefaultMutableTreeNode) root.getChildAt(0)).getUserObject()));
            assertEquals(StatusIcon.Kind.FAILED, ReadinessWizardPanel.kindOf(
                    (DefaultMutableTreeNode) root.getChildAt(0)));
            assertEquals(2, wizard.shownFindings());

            wizard.next.doClick();
            assertEquals(ReadinessWizardPanel.SUMMARY, wizard.currentStep());
            assertEquals("Needs attention", wizard.verdictText());
            assertEquals(StatusIcon.Kind.FAILED, wizard.verdictIcon().kind());
            assertTrue(wizard.tileTexts().contains("1 blocker"), wizard.tileTexts().toString());
            assertTrue(wizard.tileTexts().contains("1 accepted"), wizard.tileTexts().toString());
            wizard.back.doClick();
            assertEquals(ReadinessWizardPanel.FINDINGS, wizard.currentStep());
        });
    }

    @Test
    void popupsPresetTheScope() throws Exception {
        FakeController controller = new FakeController();
        ReadinessWizardPanel[] panel = new ReadinessWizardPanel[1];
        onEdt(() -> panel[0] = new ReadinessWizardPanel(controller));
        onEdt(() -> {
            controller.listener.finished(report());
            panel[0].useScope(true);
            assertEquals(ReadinessWizardPanel.SETUP, panel[0].currentStep());
            assertEquals(List.of("group:a", "group:b"), panel[0].request().groupIds());
            panel[0].useScope(false);
            assertEquals(List.of("group:a"), panel[0].request().groupIds());
        });
    }

    @Test
    void threadGroupAndThreadGroupsPopupsOfferReadiness() throws Exception {
        var previous = org.apache.jmeter.gui.GuiPackage.getInstance();
        try {
            onEdt(() -> {
                @SuppressWarnings("deprecation")
                var model = new org.apache.jmeter.gui.tree.JMeterTreeModel(new Object());
                var listener = new org.apache.jmeter.gui.tree.JMeterTreeListener(model);
                var group = new org.apache.jmeter.threads.ThreadGroup();
                var node = new org.apache.jmeter.gui.tree.JMeterTreeNode(group, model);
                ((org.apache.jmeter.gui.tree.JMeterTreeNode) model.getRoot()).add(node);
                listener.setJTree(new javax.swing.JTree(model));
                listener.setSelectionPathWithoutEdit(new javax.swing.tree.TreePath(node.getPath()));
                org.apache.jmeter.gui.GuiPackage.initInstance(listener, model);
                assertTrue(hasItem(new org.apache.jmeter.threads.gui.ThreadGroupGui().createPopupMenu(),
                        org.apache.jmeter.gui.action.ActionNames.CHECK_READINESS_TG));
                assertTrue(hasItem(new org.apache.jmeter.scenario.gui.ThreadGroupsSectionGui().createPopupMenu(),
                        org.apache.jmeter.gui.action.ActionNames.CHECK_READINESS_ALL));
            });
        } finally {
            var field = org.apache.jmeter.gui.GuiPackage.class.getDeclaredField("guiPack");
            field.setAccessible(true);
            field.set(null, previous);
        }
    }

    private static boolean hasItem(javax.swing.JPopupMenu menu, String action) {
        return java.util.Arrays.stream(menu.getComponents()).filter(javax.swing.JMenuItem.class::isInstance)
                .map(javax.swing.JMenuItem.class::cast).anyMatch(item -> action.equals(item.getActionCommand()));
    }

    @Test
    void refreshedReportKeepsTheSelection() throws Exception {
        FakeController controller = new FakeController();
        ReadinessWizardPanel[] panel = new ReadinessWizardPanel[1];
        onEdt(() -> panel[0] = new ReadinessWizardPanel(controller));
        onEdt(() -> {
            controller.listener.finished(report());
            assertTrue(panel[0].select(o -> o instanceof Finding f && f.severity() == Severity.BLOCKER));
            controller.listener.updated(report());
            assertTrue(panel[0].selection() instanceof Finding f && f.findingKey().equals("missing"));
        });
    }
}
