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
import java.util.function.Function;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.apache.jmeter.control.ForkController;
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

    private JComboBox<RunningAction> whenRunning;
    private JComboBox<IterationEndAction> onMainFlowEnd;
    private JComboBox<FinalStopAction> finalStop;
    private JLabel finalStopLabel;

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
        controller.setRunningAction((RunningAction) whenRunning.getSelectedItem());
        controller.setIterationEndAction((IterationEndAction) onMainFlowEnd.getSelectedItem());
        controller.setFinalStopAction((FinalStopAction) finalStop.getSelectedItem());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        ForkController controller = (ForkController) element;
        boolean sameUser = isSameUserEnabled(element);
        updateIterationOptions(sameUser);
        whenRunning.setSelectedItem(controller.getRunningAction());
        IterationEndAction action = controller.getIterationEndAction();
        onMainFlowEnd.setSelectedItem(action == IterationEndAction.KEEP_RUNNING && !sameUser
                ? IterationEndAction.GRACEFUL : action);
        finalStop.setSelectedItem(controller.getFinalStopAction());
        updateFinalStopVisibility();
    }

    @Override
    public void clearGui() {
        super.clearGui();
        updateIterationOptions(isSameUserEnabled(null));
        whenRunning.setSelectedItem(RunningAction.SKIP);
        onMainFlowEnd.setSelectedItem(IterationEndAction.GRACEFUL);
        finalStop.setSelectedItem(FinalStopAction.GRACEFUL);
        updateFinalStopVisibility();
    }

    @Override
    public String getLabelResource() {
        return "fork_controller_title"; // $NON-NLS-1$
    }

    protected boolean isSameUserEnabled(TestElement element) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return false;
        }
        JMeterTreeNode node = element == null ? gui.getCurrentNode() : gui.getNodeOf(element);
        while (node != null) {
            if (node.getTestElement() instanceof AbstractThreadGroup group) {
                return group.isSameUserOnNextIteration();
            }
            node = (JMeterTreeNode) node.getParent();
        }
        return false;
    }

    private void updateIterationOptions(boolean sameUser) {
        onMainFlowEnd.removeAllItems();
        onMainFlowEnd.addItem(IterationEndAction.IMMEDIATE);
        onMainFlowEnd.addItem(IterationEndAction.GRACEFUL);
        onMainFlowEnd.addItem(IterationEndAction.WAIT);
        if (sameUser) {
            onMainFlowEnd.addItem(IterationEndAction.KEEP_RUNNING);
        }
    }

    private void updateFinalStopVisibility() {
        boolean keep = onMainFlowEnd.getSelectedItem() == IterationEndAction.KEEP_RUNNING;
        finalStop.setVisible(keep);
        finalStopLabel.setVisible(keep);
        revalidate();
    }

    private static <T> JComboBox<T> options(T[] values, Function<T, String> resourceKey) {
        JComboBox<T> combo = new JComboBox<>(values);
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        combo.setRenderer((list, value, index, selected, focused) -> {
            JLabel label = (JLabel) renderer.getListCellRendererComponent(list, value, index, selected, focused);
            label.setText(value == null ? "" : JMeterUtils.getResString(resourceKey.apply(value)));
            return label;
        });
        return combo;
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);
        JPanel panel = new JPanel(new MigLayout("wrap 2, hidemode 3", "[][left]"));
        whenRunning = options(RunningAction.values(), action -> switch (action) {
            case SKIP -> "fork_controller_running_skip";
            case RESTART -> "fork_controller_running_restart";
            case WAIT -> "fork_controller_running_wait";
        });
        onMainFlowEnd = options(IterationEndAction.values(), action -> switch (action) {
            case IMMEDIATE -> "fork_controller_end_immediate";
            case GRACEFUL -> "fork_controller_end_graceful";
            case WAIT -> "fork_controller_end_wait";
            case KEEP_RUNNING -> "fork_controller_end_keep";
        });
        finalStop = options(FinalStopAction.values(), action -> switch (action) {
            case GRACEFUL -> "fork_controller_end_graceful";
            case IMMEDIATE -> "fork_controller_end_immediate";
        });
        finalStopLabel = JMeterUtils.labelFor(finalStop, "fork_controller_final_stop");
        updateIterationOptions(false);
        onMainFlowEnd.setSelectedItem(IterationEndAction.GRACEFUL);
        onMainFlowEnd.addActionListener(event -> updateFinalStopVisibility());
        panel.add(JMeterUtils.labelFor(onMainFlowEnd, "fork_controller_on_main_flow_end"));
        panel.add(onMainFlowEnd);
        panel.add(finalStopLabel);
        panel.add(finalStop);
        panel.add(JMeterUtils.labelFor(whenRunning, "fork_controller_when_running"));
        panel.add(whenRunning);
        panel.add(new JLabel(JMeterUtils.getResString("fork_controller_different_users")), "span 2");
        add(panel, BorderLayout.CENTER);
        updateFinalStopVisibility();
    }
}
