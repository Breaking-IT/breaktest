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

package org.apache.jmeter.gui.util;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.EditCommand;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.util.JMeterUtils;

/** Small modal navigator: the sampler editor remains visible behind it. Decisions apply immediately. */
public final class StepByStepReviewDialog<S extends ReviewStep> extends JDialog {
    private final GuiPackage gui;
    private final List<S> steps;
    private final JLabel progress = new JLabel();
    private final JTextArea details = new JTextArea(3, 52);
    private final JButton previous = button("correlation_review_previous");
    private final JButton next = button("correlation_review_next");
    private final JButton accept = button("correlation_review_accept");
    private final JButton acceptAll = button("correlation_review_accept_all");
    private final JButton reject = button("correlation_review_reject");
    private Runnable clearHighlight;
    private int index;
    private javax.swing.Timer batchTimer;
    private JMeterTreeNode loadedNode;


    private StepByStepReviewDialog(GuiPackage gui, List<S> steps, Consumer<List<S>> apply) {
        super(gui.getMainFrame(), JMeterUtils.getResString("correlation_review_step_by_step"),
                Dialog.ModalityType.APPLICATION_MODAL);
        this.gui = gui;
        this.steps = steps;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(progress, BorderLayout.NORTH);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        panel.add(new JScrollPane(details), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(previous);
        buttons.add(next);
        buttons.add(accept);
        buttons.add(reject);
        buttons.add(acceptAll);
        JButton close = button("close");
        buttons.add(close);
        panel.add(buttons, BorderLayout.SOUTH);
        setContentPane(panel);
        previous.addActionListener(event -> move(-1));
        next.addActionListener(event -> move(1));
        reject.addActionListener(event -> {
            steps.get(index).reject();
            move(1);
        });
        accept.addActionListener(event -> {
            clear();
            apply.accept(List.of(steps.get(index)));
            if (steps.get(index).state() == ReviewStep.State.ACCEPTED) {
                move(1);
            } else {
                showStep();
                details.append("\n" + JMeterUtils.getResString("review_not_applied"));
            }
        });
        acceptAll.addActionListener(event -> startAcceptAll(apply));
        close.addActionListener(event -> dispose());
        pack();
        var owner = gui.getMainFrame();
        setLocation(owner.getX() + Math.max(0, owner.getWidth() - getWidth() - 20),
                owner.getY() + Math.max(0, owner.getHeight() - getHeight() - 40));
    }

    public static <S extends ReviewStep> void show(GuiPackage gui, List<S> steps, Consumer<List<S>> apply) {
        if (steps.isEmpty()) {
            JMeterUtils.reportInfoToUser(JMeterUtils.getResString("review_no_matches"),
                    JMeterUtils.getResString("correlation_review_step_by_step"));
            return;
        }
        StepByStepReviewDialog<S> dialog = new StepByStepReviewDialog<>(gui, steps, apply);
        dialog.showStep();
        dialog.setVisible(true);
    }

    private void startAcceptAll(Consumer<List<S>> apply) {
        clear();
        previous.setEnabled(false);
        next.setEnabled(false);
        accept.setEnabled(false);
        reject.setEnabled(false);
        acceptAll.setEnabled(false);
        var batches = reviewBatches(steps).iterator();
        List<S> visible = new java.util.ArrayList<>();
        java.util.Set<S> notVisible = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int[] checked = {0};
        loadedNode = null;
        batchTimer = new javax.swing.Timer(1, event -> {
            try {
                if (batches.hasNext()) {
                    List<S> batch = batches.next();
                    applyVisibleSteps(batch, step -> {
                        Runnable highlight = revealStep(step);
                        if (highlight == null && step.state() == ReviewStep.State.PENDING) {
                            notVisible.add(step);
                        }
                        return highlight;
                    }, visible::addAll);
                    checked[0] += batch.size();
                    progress.setText(java.text.MessageFormat.format(
                            JMeterUtils.getResString("review_checking_visibility"), checked[0]));
                    return;
                }
                batchTimer.stop();
                batchTimer = null;
                loadedNode = null;
                apply.accept(visible);
                index = 0;
                while (index < steps.size() && steps.get(index).state() != ReviewStep.State.PENDING) {
                    index++;
                }
                showStep();
                if (index < steps.size() && !notVisible.contains(steps.get(index))) {
                    details.append("\n" + JMeterUtils.getResString("review_not_applied"));
                }
            } catch (RuntimeException ex) {
                if (batchTimer != null) {
                    batchTimer.stop();
                    batchTimer = null;
                }
                loadedNode = null;
                showStep();
                JMeterUtils.reportErrorToUser(ex.getMessage());
            }
        });
        batchTimer.start();
    }

    /** One editor per batch, with bounded work before returning to the event queue. */
    static <S extends ReviewStep> List<List<S>> reviewBatches(List<S> steps) {
        java.util.Map<JMeterTreeNode, List<S>> nodes = new java.util.LinkedHashMap<>();
        for (S step : steps) {
            if (step.state() == ReviewStep.State.PENDING) {
                nodes.computeIfAbsent(step.node(), ignored -> new java.util.ArrayList<>()).add(step);
            }
        }
        List<List<S>> batches = new java.util.ArrayList<>();
        for (List<S> nodeSteps : nodes.values()) {
            for (int start = 0; start < nodeSteps.size(); start += 32) {
                batches.add(nodeSteps.subList(start, Math.min(start + 32, nodeSteps.size())));
            }
        }
        return batches;
    }

    static <S extends ReviewStep> void applyVisibleSteps(List<S> steps,
            java.util.function.Function<S, Runnable> reveal, Consumer<List<S>> apply) {
        List<S> visible = new java.util.ArrayList<>();
        for (S step : steps) {
            if (step.state() != ReviewStep.State.PENDING) {
                continue;
            }
            Runnable clear = reveal.apply(step);
            if (clear != null) {
                clear.run();
                visible.add(step);
            }
        }
        apply.accept(visible);
    }

    private static JButton button(String key) {
        return new JButton(JMeterUtils.getResString(key));
    }

    private void move(int delta) {
        clear();
        index = Math.max(0, Math.min(steps.size(), index + delta));
        showStep();
    }

    private void showStep() {
        clear();
        if (index < steps.size()) {
            clearHighlight = revealStep(steps.get(index));
        }
        previous.setEnabled(index > 0);
        next.setEnabled(index < steps.size());
        accept.setEnabled(false);
        reject.setEnabled(false);
        acceptAll.setEnabled(steps.stream().anyMatch(step -> step.state() == ReviewStep.State.PENDING));
        if (index == steps.size()) {
            progress.setText(JMeterUtils.getResString("correlation_review_end"));
            long accepted = steps.stream().filter(step -> step.state() == ReviewStep.State.ACCEPTED).count();
            long rejected = steps.stream().filter(step -> step.state() == ReviewStep.State.REJECTED).count();
            details.setText(java.text.MessageFormat.format(JMeterUtils.getResString("correlation_review_summary"),
                    accepted, rejected, steps.size() - accepted - rejected));
            return;
        }
        S step = steps.get(index);
        boolean visible = clearHighlight != null;
        progress.setText((index + 1) + " / " + steps.size() + " — "
                + JMeterUtils.getResString("correlation_review_" + step.state().name().toLowerCase(java.util.Locale.ROOT)));
        details.setText(step.description());
        details.setCaretPosition(0);
        boolean pending = step.state() == ReviewStep.State.PENDING;
        accept.setEnabled(pending && visible);
        reject.setEnabled(pending);
        if (!visible && pending) {
            details.append("\n" + JMeterUtils.getResString("correlation_review_not_visible"));
        }
    }

    private Runnable revealStep(S step) {
        JMeterTreeNode node = step.node();
        boolean reload = batchTimer == null || node != loadedNode;
        if (reload) {
            gui.updateCurrentNode();
        }
        boolean attached = node.getParent() != null && node.getRoot() == gui.getTreeModel().getRoot();
        if (attached && reload) {
            TreePath path = new TreePath(node.getPath());
            gui.getTreeListener().setSelectionPathWithoutEdit(path);
            new EditCommand().doAction(null);
            gui.getMainFrame().getTree().scrollPathToVisible(path);
            loadedNode = node;
        }
        boolean current = attached && step.current();
        if (!current && step.state() == ReviewStep.State.PENDING) {
            step.markStale();
        }
        if (current && step.state() != ReviewStep.State.STALE
                && gui.getGui(node.getTestElement()) instanceof Component component) {
            return step.highlight(component);
        }
        return null;
    }

    private void clear() {
        if (clearHighlight != null) {
            clearHighlight.run();
            clearHighlight = null;
        }
    }

    @Override
    public void dispose() {
        if (batchTimer != null) {
            batchTimer.stop();
            batchTimer = null;
        }
        loadedNode = null;
        clear();
        super.dispose();
    }
}
