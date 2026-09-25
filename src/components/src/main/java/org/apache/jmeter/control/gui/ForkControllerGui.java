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

import java.awt.BorderLayout;
import java.awt.event.ActionListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.ForkController.ErrorAction;
import org.apache.jmeter.control.ForkController.FinalStopAction;
import org.apache.jmeter.control.ForkController.IterationEndAction;
import org.apache.jmeter.control.ForkController.RunningAction;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.MenuInfo;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;

import net.miginfocom.swing.MigLayout;

@GUIMenuSortOrder(MenuInfo.SORT_ORDER_DEFAULT + 2)
@TestElementMetadata(labelResource = "fork_controller_title")
public class ForkControllerGui extends AbstractControllerGui {
    private static final long serialVersionUID = 240L;

    private OptionGroup<RunningAction> whenRunning;
    private OptionGroup<IterationEndAction> onMainFlowEnd;
    private OptionGroup<FinalStopAction> finalStop;
    private OptionGroup<ErrorAction> onError;
    private JLabel differentUsersHint;
    private boolean preserveKeepRunning;

    public ForkControllerGui() {
        init();
    }

    @Override
    public TestElement createTestElement() {
        ForkController controller = new ForkController();
        modifyTestElement(controller);
        return controller;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        configureTestElement(element);
        ForkController controller = (ForkController) element;
        controller.setRunningAction(whenRunning.getSelectedItem());
        controller.setIterationEndAction(preserveKeepRunning ? IterationEndAction.KEEP_RUNNING : onMainFlowEnd.getSelectedItem());
        controller.setFinalStopAction(finalStop.getSelectedItem());
        controller.setErrorAction(onError.getSelectedItem());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        ForkController controller = (ForkController) element;
        whenRunning.setSelectedItem(controller.getRunningAction());
        finalStop.setSelectedItem(controller.getFinalStopAction());
        boolean keepAvailable = isKeepRunningAvailable();
        onMainFlowEnd.setOptionVisible(IterationEndAction.KEEP_RUNNING, keepAvailable);
        preserveKeepRunning = !keepAvailable && controller.getIterationEndAction() == IterationEndAction.KEEP_RUNNING;
        onMainFlowEnd.setSelectedItem(preserveKeepRunning ? effectiveFinalStop() : controller.getIterationEndAction());
        onError.setSelectedItem(controller.getErrorAction());
        updateFinalStopVisibility();
    }

    @Override
    public void clearGui() {
        super.clearGui();
        preserveKeepRunning = false;
        onMainFlowEnd.setOptionVisible(IterationEndAction.KEEP_RUNNING, isKeepRunningAvailable());
        whenRunning.setSelectedItem(RunningAction.SKIP);
        onMainFlowEnd.setSelectedItem(IterationEndAction.GRACEFUL);
        finalStop.setSelectedItem(FinalStopAction.GRACEFUL);
        onError.setSelectedItem(ErrorAction.CONTINUE);
        updateFinalStopVisibility();
    }

    @Override
    public String getLabelResource() {
        return "fork_controller_title"; // $NON-NLS-1$
    }

    private void updateFinalStopVisibility() {
        boolean keep = preserveKeepRunning || onMainFlowEnd.getSelectedItem() == IterationEndAction.KEEP_RUNNING;
        differentUsersHint.setText(JMeterUtils.getResString(preserveKeepRunning
                ? "fork_controller_saved_keep_disabled" : "fork_controller_different_users"));
        finalStop.setVisible(keep);
        revalidate();
    }

    private IterationEndAction effectiveFinalStop() {
        return finalStop.getSelectedItem() == FinalStopAction.IMMEDIATE
                ? IterationEndAction.IMMEDIATE : IterationEndAction.GRACEFUL;
    }

    boolean isKeepRunningAvailable() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            for (JMeterTreeNode node = gui.getCurrentNode(); node != null;
                    node = (JMeterTreeNode) node.getParent()) {
                if (node.getTestElement() instanceof AbstractThreadGroup group) {
                    return group.isSameUserOnNextIteration();
                }
            }
        }
        // Test fragments have no enclosing thread group until execution.
        return true;
    }

    private static final class OptionGroup<T> extends JPanel {
        private static final long serialVersionUID = 1L;
        private final Map<T, JRadioButton> buttons = new LinkedHashMap<>();

        private OptionGroup(String titleKey, T[] values, Function<T, String> resourceKey) {
            super(new MigLayout("wrap 1, hidemode 3, insets 6 10 8 10, gapy 2", "[left]"));
            setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString(titleKey)));
            ButtonGroup group = new ButtonGroup();
            for (T value : values) {
                JRadioButton button = new JRadioButton(JMeterUtils.getResString(resourceKey.apply(value)));
                button.putClientProperty("fork.option", value);
                group.add(button);
                buttons.put(value, button);
                add(button);
            }
            setSelectedItem(values[0]);
        }

        private T getSelectedItem() {
            return buttons.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
        }

        private void setSelectedItem(T value) {
            buttons.get(value).setSelected(true);
        }

        private void setOptionVisible(T value, boolean visible) {
            buttons.get(value).setVisible(visible);
        }

        private void addActionListener(ActionListener listener) {
            buttons.values().forEach(button -> button.addActionListener(listener));
        }
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);
        JPanel panel = new JPanel(new MigLayout("wrap 1, hidemode 3, insets 16 0 0 0, gapy 10", "[left]"));
        whenRunning = new OptionGroup<>("fork_controller_when_running", RunningAction.values(), action -> switch (action) {
            case SKIP -> "fork_controller_running_skip";
            case RESTART -> "fork_controller_running_restart";
            case WAIT -> "fork_controller_running_wait";
        });
        onMainFlowEnd = new OptionGroup<>("fork_controller_on_main_flow_end", IterationEndAction.values(), action -> switch (action) {
            case IMMEDIATE -> "fork_controller_end_immediate";
            case GRACEFUL -> "fork_controller_end_graceful";
            case WAIT -> "fork_controller_end_wait";
            case KEEP_RUNNING -> "fork_controller_end_keep";
        });
        finalStop = new OptionGroup<>("fork_controller_final_stop", FinalStopAction.values(), action -> switch (action) {
            case GRACEFUL -> "fork_controller_end_graceful";
            case IMMEDIATE -> "fork_controller_end_immediate";
        });
        onError = new OptionGroup<>("fork_controller_on_error", ErrorAction.values(), action -> switch (action) {
            case CONTINUE -> "fork_controller_error_continue";
            case STOP_FORK -> "fork_controller_error_stop_fork";
            case END_ITERATION_GRACEFUL -> "fork_controller_error_end_iteration_graceful";
            case END_ITERATION_IMMEDIATE -> "fork_controller_error_end_iteration_immediate";
        });
        onMainFlowEnd.setSelectedItem(IterationEndAction.GRACEFUL);
        onMainFlowEnd.addActionListener(event -> {
            preserveKeepRunning = false;
            updateFinalStopVisibility();
        });
        finalStop.addActionListener(event -> {
            if (preserveKeepRunning) {
                onMainFlowEnd.setSelectedItem(effectiveFinalStop());
            }
        });
        onMainFlowEnd.setOptionVisible(IterationEndAction.KEEP_RUNNING, isKeepRunningAvailable());
        panel.add(onMainFlowEnd, "sgx fork-options");
        panel.add(finalStop, "sgx fork-options");
        panel.add(whenRunning, "sgx fork-options");
        panel.add(onError, "sgx fork-options");
        differentUsersHint = new JLabel();
        panel.add(differentUsersHint);
        add(panel, BorderLayout.CENTER);
        updateFinalStopVisibility();
    }
}
