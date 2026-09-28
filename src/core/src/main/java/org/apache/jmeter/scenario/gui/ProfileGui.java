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


package org.apache.jmeter.scenario.gui;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.scenario.Profile;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

import net.miginfocom.swing.MigLayout;

/**
 * Editor of a named {@link Profile}. One profile can be the default, used when a thread group is validated.
 */
public class ProfileGui extends AbstractProfileGui {
    private static final long serialVersionUID = 1L;

    private final JLabel status = new JLabel();

    private final JButton makeDefault =
            new JButton(JMeterUtils.getResString("profile_make_default")); // $NON-NLS-1$

    private boolean isDefault;

    public ProfileGui() {
        JPanel defaultPanel = new JPanel(new MigLayout("insets 0", "[][10][]"));
        makeDefault.setName("makeDefaultProfile"); // $NON-NLS-1$
        makeDefault.addActionListener(e -> setCurrentProfileDefault(true));
        defaultPanel.add(makeDefault);
        defaultPanel.add(status);
        box.add(defaultPanel);
    }

    @Override
    public String getLabelResource() {
        return "profile_title"; // $NON-NLS-1$
    }

    @Override
    public TestElement makeTestElement() {
        return new Profile();
    }

    @Override
    protected boolean isRemovable() {
        return true;
    }

    @Override
    public JPopupMenu createPopupMenu() {
        JPopupMenu pop = super.createPopupMenu();
        // There is always one default profile: another profile takes the role by becoming the default
        JMenuItem makeDefaultItem = new JMenuItem(JMeterUtils.getResString("profile_make_default")); // $NON-NLS-1$
        makeDefaultItem.setEnabled(!isDefault);
        makeDefaultItem.addActionListener(e -> setCurrentProfileDefault(true));
        pop.insert(makeDefaultItem, 0);
        pop.insert(new JPopupMenu.Separator(), 1);
        return pop;
    }

    /** The first profile of a test plan becomes its default profile. */
    @Override
    public void assignDefaultValues(TestElement element) {
        super.assignDefaultValues(element);
        GuiPackage guiPackage = GuiPackage.getInstance();
        boolean hasDefault = guiPackage != null && guiPackage.getTreeModel().getNodesOfType(Profile.class).stream()
                .anyMatch(node -> ((Profile) node.getTestElement()).isDefault());
        ((Profile) element).setDefault(!hasDefault);
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        isDefault = ((Profile) element).isDefault();
        makeDefault.setEnabled(!isDefault);
        status.setText(JMeterUtils.getResString(
                isDefault ? "profile_is_default" : "profile_is_not_default")); // $NON-NLS-1$ $NON-NLS-2$
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.modifyTestElement(element);
        ((Profile) element).setDefault(isDefault);
    }

    @Override
    public void clearGui() {
        super.clearGui();
        isDefault = false;
    }

    /** Only one profile can be the default, used when a thread group is validated on its own. */
    private static void setCurrentProfileDefault(boolean makeDefault) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
        JMeterTreeNode current = guiPackage.getCurrentNode();
        if (current == null || !(current.getTestElement() instanceof Profile profile)) {
            return;
        }
        if (makeDefault && current.getParent() instanceof JMeterTreeNode section) {
            for (int i = 0; i < section.getChildCount(); i++) {
                if (section.getChildAt(i) instanceof JMeterTreeNode sibling
                        && sibling.getTestElement() instanceof Profile other && other.isDefault()) {
                    other.setDefault(false);
                    guiPackage.getTreeModel().nodeChanged(sibling);
                }
            }
        }
        profile.setDefault(makeDefault);
        guiPackage.getTreeModel().nodeChanged(current);
        guiPackage.refreshCurrentGui();
        guiPackage.getMainFrame().repaint();
    }
}
