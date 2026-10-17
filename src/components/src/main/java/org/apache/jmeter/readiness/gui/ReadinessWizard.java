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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Set;

import javax.swing.JDialog;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractAction;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.util.JMeterUtils;

import com.google.auto.service.AutoService;

/**
 * Tools → Check script readiness…: opens the readiness wizard, or the latest report of the GUI session. The
 * thread group and Thread Groups popups open it with that thread group, or all of them, in scope.
 */
@AutoService({Command.class, MenuCreator.class})
public final class ReadinessWizard extends AbstractAction implements MenuCreator {
    private static final Set<String> COMMANDS = Set.of(ActionNames.CHECK_SCRIPT_READINESS,
            ActionNames.CHECK_READINESS_TG, ActionNames.CHECK_READINESS_ALL);

    private static GuiReadinessController controller;
    private static JDialog dialog;
    private static ReadinessWizardPanel panel;

    @Override
    public Set<String> getActionNames() {
        return COMMANDS;
    }

    @Override
    public void doAction(ActionEvent e) throws IllegalUserActionException {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return;
        }
        gui.updateCurrentNode();
        if (controller == null) {
            controller = new GuiReadinessController();
        }
        if (dialog == null) {
            dialog = new JDialog(gui.getMainFrame(), JMeterUtils.getResString(ActionNames.CHECK_SCRIPT_READINESS)
                    .replace("...", ""), false);
            panel = new ReadinessWizardPanel(controller);
            dialog.getContentPane().add(panel, BorderLayout.CENTER);
            dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            dialog.setPreferredSize(new Dimension(900, 680));
            dialog.pack();
            dialog.setLocationRelativeTo(gui.getMainFrame());
            // Coming back to the wizard after editing shows whether the evidence still matches the plan
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent event) {
                    if (!controller.isRunning()) {
                        controller.refresh();
                    }
                }
            });
        } else if (!controller.isRunning()) {
            panel.reloadGroups();
            if (controller.latestReport() == null
                    && !ReadinessWizardPanel.SETUP.equals(panel.currentStep())) {
                panel.show(ReadinessWizardPanel.SETUP);
            }
        }
        if (!controller.isRunning() && !ActionNames.CHECK_SCRIPT_READINESS.equals(e.getActionCommand())) {
            // From a popup: the right-clicked thread group, or every thread group, ready to run
            panel.useScope(ActionNames.CHECK_READINESS_ALL.equals(e.getActionCommand()));
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location != MENU_LOCATION.TOOLS) {
            return new JMenuItem[0];
        }
        JMenuItem item = new JMenuItem(JMeterUtils.getResString(ActionNames.CHECK_SCRIPT_READINESS).replace("...",
                "…"), KeyEvent.VK_R);
        item.setName(ActionNames.CHECK_SCRIPT_READINESS);
        item.setActionCommand(ActionNames.CHECK_SCRIPT_READINESS);
        item.addActionListener(ActionRouter.getInstance());
        return new JMenuItem[]{item};
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
        // NOOP
    }
}
