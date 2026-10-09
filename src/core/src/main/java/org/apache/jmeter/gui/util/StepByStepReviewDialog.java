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
        acceptAll.addActionListener(event -> {
            clear();
            apply.accept(steps.stream().filter(step -> step.state() == ReviewStep.State.PENDING).toList());
            index = 0;
            while (index < steps.size() && steps.get(index).state() != ReviewStep.State.PENDING) {
                index++;
            }
            showStep();
            if (index < steps.size()) {
                details.append("\n" + JMeterUtils.getResString("review_not_applied"));
            }
        });
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
        gui.updateCurrentNode();
        JMeterTreeNode node = step.node();
        boolean attached = node.getParent() != null && node.getRoot() == gui.getTreeModel().getRoot();
        if (attached) {
            TreePath path = new TreePath(node.getPath());
            gui.getTreeListener().setSelectionPathWithoutEdit(path);
            new EditCommand().doAction(null);
            gui.getMainFrame().getTree().scrollPathToVisible(path);
        }
        if ((!attached || !step.current()) && step.state() == ReviewStep.State.PENDING) {
            step.markStale();
        }
        if (attached && step.current() && step.state() != ReviewStep.State.STALE
                && gui.getGui(node.getTestElement()) instanceof Component component) {
            clearHighlight = step.highlight(component);
        }
        acceptAll.setEnabled(steps.stream().anyMatch(item -> item.state() == ReviewStep.State.PENDING));
        progress.setText((index + 1) + " / " + steps.size() + " — "
                + JMeterUtils.getResString("correlation_review_" + step.state().name().toLowerCase(java.util.Locale.ROOT)));
        details.setText(step.description());
        details.setCaretPosition(0);
        boolean pending = step.state() == ReviewStep.State.PENDING;
        accept.setEnabled(pending && clearHighlight != null);
        reject.setEnabled(pending);
        if (clearHighlight == null && pending) {
            details.append("\n" + JMeterUtils.getResString("correlation_review_not_visible"));
        }
    }

    private void clear() {
        if (clearHighlight != null) {
            clearHighlight.run();
            clearHighlight = null;
        }
    }

    @Override
    public void dispose() {
        clear();
        super.dispose();
    }
}
