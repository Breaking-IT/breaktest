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

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.scenario.Profile;
import org.apache.jmeter.scenario.ScenarioWorkload;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Editor of one {@link ScenarioWorkload}, shown inside the {@link ScenarioGui}: which thread group runs, and how.
 * <p>
 * The workload is edited with the thread group workload editor: a temporary {@link ThreadGroup} carries the values
 * between this editor and the {@link ScenarioWorkload}.
 */
public class ScenarioWorkloadGui extends ThreadGroupGui {
    private static final long serialVersionUID = 1L;

    private final JComboBox<ThreadGroupChoice> threadGroups = new JComboBox<>();

    private final JComboBox<String> profile = new JComboBox<>();

    private final JLabel ownSettings =
            new JLabel(JMeterUtils.getResString("scenario_workload_own_settings")); // $NON-NLS-1$

    private boolean updatingThreadGroups;

    private String selectedThreadGroupName = "";

    private Runnable changeListener = () -> { };

    public ScenarioWorkloadGui() {
        super(true);
        JPanel header = getHeaderPanel();
        header.add(new JLabel(JMeterUtils.getResString("scenario_workload_thread_group"))); // $NON-NLS-1$
        threadGroups.setName("workloadThreadGroup"); // $NON-NLS-1$
        threadGroups.addActionListener(e -> threadGroupSelected());
        header.add(threadGroups, "growx, wrap");
        header.add(new JLabel(JMeterUtils.getResString("scenario_workload_profile"))); // $NON-NLS-1$
        profile.setName("workloadProfile"); // $NON-NLS-1$
        profile.setEditable(true);
        profile.setToolTipText(JMeterUtils.getResString("scenario_workload_profile_tooltip")); // $NON-NLS-1$
        profile.addActionListener(e -> {
            if (!updatingThreadGroups) {
                workloadChanged();
            }
        });
        header.add(profile, "growx, wrap");
        ownSettings.setVisible(false);
        header.add(ownSettings, "span 2");
        reportEditsOf(this);
    }

    /** Every edit, not only those that change the load, is shown in the scenario table right away. */
    private void reportEditsOf(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTextComponent text) {
                text.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        workloadChanged();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        workloadChanged();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        workloadChanged();
                    }
                });
            } else if (component instanceof JToggleButton toggle) {
                toggle.addItemListener(e -> workloadChanged());
            } else if (component instanceof JComboBox<?> comboBox) {
                comboBox.addActionListener(e -> workloadChanged());
            }
            if (component instanceof Container child) {
                reportEditsOf(child);
            }
        }
    }

    /**
     * @param changeListener called when a setting that changes the expected load has been edited
     */
    void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    @Override
    protected void workloadChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    /**
     * Thread group editors fill the whole editor area and report their minimum size as preferred size.
     * Inside the scenario editor the settings sit below the summary, so they need their real height,
     * or the schedule tables collapse to their header.
     */
    @Override
    public Dimension getPreferredSize() {
        return getLayout().preferredLayoutSize(this);
    }

    @Override
    public String getLabelResource() {
        return "scenario_workload_title"; // $NON-NLS-1$
    }

    /** Workloads are edited inside the scenario editor, not as tree nodes. */
    @Override
    public Collection<String> getMenuCategories() {
        return null;
    }

    @Override
    public JPopupMenu createPopupMenu() {
        return new JPopupMenu();
    }

    @Override
    protected boolean isInThreadGroupsSection(TestElement element) {
        return false;
    }

    @Override
    public TestElement makeTestElement() {
        return new ScenarioWorkload();
    }

    @Override
    public void assignDefaultValues(TestElement element) {
        ThreadGroup carrier = new ThreadGroup();
        super.assignDefaultValues(carrier);
        ScenarioWorkload workload = (ScenarioWorkload) element;
        workload.copyWorkloadFrom(carrier);
        workload.setName(getStaticLabel());
        workload.setProperty(TestElement.GUI_CLASS, getClass().getName());
        workload.setProperty(TestElement.TEST_CLASS, ScenarioWorkload.class.getName());
        ThreadGroupChoice first = firstThreadGroup();
        if (first != null) {
            workload.setThreadGroupId(first.threadGroup().getOrCreateThreadGroupId());
            workload.setName(first.threadGroup().getName());
        }
    }

    @Override
    public void configure(TestElement element) {
        ScenarioWorkload workload = (ScenarioWorkload) element;
        ThreadGroup carrier = new ThreadGroup();
        PropertyIterator properties = workload.propertyIterator();
        while (properties.hasNext()) {
            carrier.setProperty(properties.next().clone());
        }
        ScenarioWorkload.ensureMainController(carrier);
        super.configure(carrier);
        loadThreadGroups(workload.getThreadGroupId());
        loadProfiles(workload.getProfile());
        updateOwnSettings();
    }

    /** Thread groups that are not BreakTest's own keep their settings: there is nothing to set here. */
    private void updateOwnSettings() {
        boolean own = threadGroups.getSelectedItem() instanceof ThreadGroupChoice choice && choice.threadGroup() != null
                && ScenarioWorkload.usesOwnSettings(choice.threadGroup());
        ownSettings.setVisible(own);
        if (own) {
            setOwnSettingsMode(true);
        } else {
            setWorkloadSettingsVisible(true);
        }
    }

    @Override
    public void modifyTestElement(TestElement element) {
        ScenarioWorkload workload = (ScenarioWorkload) element;
        ThreadGroup carrier = new ThreadGroup();
        super.modifyTestElement(carrier);
        workload.copyWorkloadFrom(carrier);
        for (String name : new String[]{TestElement.NAME, TestElement.COMMENTS, TestElement.ENABLED}) {
            JMeterProperty property = carrier.getProperty(name);
            if (property.getObjectValue() == null) {
                workload.removeProperty(name);
            } else {
                workload.setProperty(property.clone());
            }
        }
        workload.setProperty(TestElement.GUI_CLASS, getClass().getName());
        workload.setProperty(TestElement.TEST_CLASS, ScenarioWorkload.class.getName());
        if (threadGroups.getSelectedItem() instanceof ThreadGroupChoice choice && choice.threadGroup() != null) {
            workload.setThreadGroupId(choice.threadGroup().getOrCreateThreadGroupId());
        }
        Object selectedProfile = profile.isEditable() ? profile.getEditor().getItem() : profile.getSelectedItem();
        workload.setProfile(selectedProfile == null ? "" : selectedProfile.toString().trim());
    }

    @Override
    public void clearGui() {
        super.clearGui();
        loadThreadGroups("");
        loadProfiles("");
    }

    /** Offers the profiles of the test plan; any other text, such as an expression, can be typed too. */
    private void loadProfiles(String selected) {
        updatingThreadGroups = true;
        try {
            profile.removeAllItems();
            profile.addItem("");
            for (String name : availableProfiles()) {
                profile.addItem(name);
            }
            profile.setSelectedItem(selected);
            profile.getEditor().setItem(selected);
        } finally {
            updatingThreadGroups = false;
        }
    }

    private static List<String> availableProfiles() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return List.of();
        }
        return guiPackage.getTreeModel().getNodesOfType(Profile.class).stream()
                .map(node -> node.getTestElement().getName())
                .toList();
    }

    private void loadThreadGroups(String selectedId) {
        updatingThreadGroups = true;
        try {
            threadGroups.removeAllItems();
            ThreadGroupChoice selected = null;
            for (AbstractThreadGroup threadGroup : availableThreadGroups()) {
                ThreadGroupChoice choice = new ThreadGroupChoice(threadGroup, null);
                threadGroups.addItem(choice);
                if (!selectedId.isEmpty() && selectedId.equals(threadGroup.getThreadGroupId())) {
                    selected = choice;
                }
            }
            if (selected == null && !selectedId.isEmpty()) {
                selected = new ThreadGroupChoice(null,
                        JMeterUtils.getResString("scenario_workload_missing_thread_group")); // $NON-NLS-1$
                threadGroups.addItem(selected);
            }
            if (selected != null) {
                threadGroups.setSelectedItem(selected);
            }
            selectedThreadGroupName = selected != null && selected.threadGroup() != null
                    ? selected.threadGroup().getName()
                    : "";
        } finally {
            updatingThreadGroups = false;
        }
    }

    /** Keeps the workload named after its thread group until the user gives it a name of its own. */
    private void threadGroupSelected() {
        if (updatingThreadGroups || !(threadGroups.getSelectedItem() instanceof ThreadGroupChoice choice)
                || choice.threadGroup() == null) {
            return;
        }
        String name = getName();
        if (name.isEmpty() || name.equals(getStaticLabel()) || name.equals(selectedThreadGroupName)) {
            setName(choice.threadGroup().getName());
        }
        selectedThreadGroupName = choice.threadGroup().getName();
        updateOwnSettings();
        workloadChanged();
    }

    private static ThreadGroupChoice firstThreadGroup() {
        return availableThreadGroups().stream().findFirst().map(tg -> new ThreadGroupChoice(tg, null)).orElse(null);
    }

    static List<AbstractThreadGroup> availableThreadGroups() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return List.of();
        }
        return guiPackage.getTreeModel().getNodesOfType(ThreadGroupsSection.class).stream()
                .flatMap(section -> Collections.list(section.children()).stream())
                .map(child -> ((JMeterTreeNode) child).getTestElement())
                .filter(AbstractThreadGroup.class::isInstance)
                .map(AbstractThreadGroup.class::cast)
                .toList();
    }

    private record ThreadGroupChoice(AbstractThreadGroup threadGroup, String label) {
        @Override
        public String toString() {
            if (threadGroup == null) {
                return label;
            }
            return threadGroup.isEnabled()
                    ? threadGroup.getName()
                    : threadGroup.getName() + ' ' + JMeterUtils.getResString("scenario_workload_disabled_suffix"); // $NON-NLS-1$
        }
    }
}
