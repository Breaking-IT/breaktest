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

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import org.apache.jmeter.gui.AbstractJMeterGuiComponent;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.scenario.Scenario;
import org.apache.jmeter.scenario.ScenarioWorkload;
import org.apache.jmeter.scenario.WorkloadSummary;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;

import net.miginfocom.swing.MigLayout;

/**
 * Editor of a {@link Scenario}: the thread groups it runs, a summary of the expected load, and the settings of
 * the selected thread group. The enabled scenario is the one that runs, so enabling one disables the others.
 */
public class ScenarioGui extends AbstractJMeterGuiComponent {
    private static final long serialVersionUID = 1L;

    private static final String EDITOR_CARD = "editor"; // $NON-NLS-1$

    private static final String EMPTY_CARD = "empty"; // $NON-NLS-1$

    private static final int ENABLED_COLUMN = 0;

    private final JLabel status = new JLabel();

    private final JButton activate = new JButton(JMeterUtils.getResString("scenario_make_active")); // $NON-NLS-1$

    private final JCheckBox runConsecutively =
            new JCheckBox(JMeterUtils.getResString("scenario_run_consecutively")); // $NON-NLS-1$

    private final List<ScenarioWorkload> workloads = new ArrayList<>();

    private final WorkloadTableModel tableModel = new WorkloadTableModel();

    private final JTable table = new JTable(tableModel);

    private final JLabel totals = new JLabel();

    private final JButton add = new JButton(JMeterUtils.getResString("scenario_add_thread_group")); // $NON-NLS-1$

    private final JButton duplicate = new JButton(JMeterUtils.getResString("scenario_duplicate")); // $NON-NLS-1$

    private final JButton remove = new JButton(JMeterUtils.getResString("scenario_remove")); // $NON-NLS-1$

    private final JButton up = new JButton(JMeterUtils.getResString("up")); // $NON-NLS-1$

    private final JButton down = new JButton(JMeterUtils.getResString("down")); // $NON-NLS-1$

    private final ScenarioWorkloadGui editor = new ScenarioWorkloadGui();

    private final JPanel editorCards = new JPanel(new CardLayout());

    private int editedRow = -1;

    private boolean updating;

    public ScenarioGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());

        VerticalPanel box = new VerticalPanel();
        box.add(makeTitlePanel());
        JTextArea info = new JTextArea(JMeterUtils.getResString("scenario_title_info")); // $NON-NLS-1$
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setOpaque(false);
        box.add(info);
        JPanel activePanel = new JPanel(new MigLayout("insets 0", "[][10][]"));
        activate.setName("makeActiveScenario"); // $NON-NLS-1$
        activate.addActionListener(e -> activateCurrentScenario());
        activePanel.add(activate);
        activePanel.add(status);
        box.add(activePanel);
        runConsecutively.setName("runConsecutively"); // $NON-NLS-1$
        box.add(runConsecutively);
        box.add(createSummaryPanel());
        add(box, BorderLayout.NORTH);

        editor.setChangeListener(this::editorChanged);
        JLabel empty = new JLabel(JMeterUtils.getResString("scenario_no_thread_groups")); // $NON-NLS-1$
        editorCards.add(editor, EDITOR_CARD);
        editorCards.add(empty, EMPTY_CARD);
        add(editorCards, BorderLayout.CENTER);
        showRow(-1);
    }

    private JPanel createSummaryPanel() {
        JPanel panel = new JPanel(new MigLayout("fillx, insets 0, wrap 1", "[fill,grow]"));
        table.setName("scenarioThreadGroups"); // $NON-NLS-1$
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updating) {
                commitEditor();
                showRow(table.getSelectedRow());
            }
        });
        table.getColumnModel().getColumn(ENABLED_COLUMN).setMaxWidth(40);
        table.setPreferredScrollableViewportSize(new Dimension(600, 120));
        panel.add(new JScrollPane(table), "h 80:140:");
        panel.add(totals);

        JPanel buttons = new JPanel(new MigLayout("insets 0"));
        add.setName("addThreadGroupToScenario"); // $NON-NLS-1$
        add.addActionListener(e -> showAddMenu());
        duplicate.addActionListener(e -> duplicateSelected());
        remove.addActionListener(e -> removeSelected());
        up.addActionListener(e -> moveSelected(-1));
        down.addActionListener(e -> moveSelected(1));
        buttons.add(add);
        buttons.add(duplicate);
        buttons.add(remove);
        buttons.add(up);
        buttons.add(down);
        panel.add(buttons);
        return panel;
    }

    @Override
    public String getLabelResource() {
        return "scenario_title"; // $NON-NLS-1$
    }

    @Override
    public TestElement makeTestElement() {
        return new Scenario();
    }

    /** Scenarios are only added from the Scenarios section. */
    @Override
    public Collection<String> getMenuCategories() {
        return null;
    }

    @Override
    public JPopupMenu createPopupMenu() {
        JPopupMenu pop = new JPopupMenu();
        JMenuItem makeActive = new JMenuItem(JMeterUtils.getResString("scenario_make_active")); // $NON-NLS-1$
        makeActive.setEnabled(!isEnabled());
        makeActive.addActionListener(e -> activateCurrentScenario());
        pop.add(makeActive);
        JMenuItem run = new JMenuItem(JMeterUtils.getResString("scenario_run")); // $NON-NLS-1$
        run.setName("runScenario"); // $NON-NLS-1$
        run.setActionCommand(ActionNames.RUN_SCENARIO);
        run.addActionListener(ActionRouter.getInstance());
        run.setEnabled(!JMeterUtils.isTestRunning());
        pop.add(run);
        pop.addSeparator();
        MenuFactory.addEditMenu(pop, true);
        MenuFactory.addFileMenu(pop, false);
        return pop;
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        boolean active = element.isEnabled();
        activate.setEnabled(!active);
        status.setText(JMeterUtils.getResString(active ? "scenario_is_active" : "scenario_is_inactive")); // $NON-NLS-1$
        runConsecutively.setSelected(((Scenario) element).isRunConsecutively());
        workloads.clear();
        for (ScenarioWorkload workload : ((Scenario) element).getWorkloads()) {
            workloads.add((ScenarioWorkload) workload.clone());
        }
        editedRow = -1;
        refreshTable(workloads.isEmpty() ? -1 : 0);
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.modifyTestElement(element);
        commitEditor();
        List<ScenarioWorkload> copies = new ArrayList<>();
        for (ScenarioWorkload workload : workloads) {
            copies.add((ScenarioWorkload) workload.clone());
        }
        ((Scenario) element).setWorkloads(copies);
        ((Scenario) element).setRunConsecutively(runConsecutively.isSelected());
    }

    @Override
    public void clearGui() {
        super.clearGui();
        runConsecutively.setSelected(false);
        workloads.clear();
        editedRow = -1;
        refreshTable(-1);
    }

    /** Stores the edits of the selected thread group and updates its summary. */
    private void commitEditor() {
        if (editedRow < 0 || editedRow >= workloads.size() || updating) {
            return;
        }
        // Storing the editor can update its own fields, which report edits again
        updating = true;
        try {
            editor.modifyTestElement(workloads.get(editedRow));
            tableModel.fireTableRowsUpdated(editedRow, editedRow);
        } finally {
            updating = false;
        }
        updateTotals();
    }

    private void editorChanged() {
        if (!updating) {
            commitEditor();
        }
    }

    private void showRow(int row) {
        editedRow = row;
        CardLayout cards = (CardLayout) editorCards.getLayout();
        if (row < 0 || row >= workloads.size()) {
            cards.show(editorCards, EMPTY_CARD);
        } else {
            updating = true;
            try {
                editor.configure(workloads.get(row));
            } finally {
                updating = false;
            }
            cards.show(editorCards, EDITOR_CARD);
        }
        duplicate.setEnabled(editedRow >= 0);
        remove.setEnabled(editedRow >= 0);
        up.setEnabled(editedRow > 0);
        down.setEnabled(editedRow >= 0 && editedRow < workloads.size() - 1);
    }

    private void refreshTable(int selectRow) {
        updating = true;
        try {
            tableModel.reloadPlanNames();
            tableModel.fireTableDataChanged();
            if (selectRow >= 0) {
                table.setRowSelectionInterval(selectRow, selectRow);
            } else {
                table.clearSelection();
            }
        } finally {
            updating = false;
        }
        showRow(selectRow);
        updateTotals();
    }

    private void showAddMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<AbstractThreadGroup> threadGroups = ScenarioWorkloadGui.availableThreadGroups();
        if (threadGroups.isEmpty()) {
            JMenuItem none = new JMenuItem(JMeterUtils.getResString("scenario_no_thread_groups_available")); // $NON-NLS-1$
            none.setEnabled(false);
            menu.add(none);
        }
        for (AbstractThreadGroup threadGroup : threadGroups) {
            JMenuItem item = new JMenuItem(threadGroup.getName());
            item.addActionListener(e -> addThreadGroup(threadGroup));
            menu.add(item);
        }
        menu.show(add, 0, add.getHeight());
    }

    private void addThreadGroup(AbstractThreadGroup threadGroup) {
        commitEditor();
        editedRow = -1;
        updating = true;
        ScenarioWorkload workload;
        try {
            editor.clearGui();
            workload = (ScenarioWorkload) editor.createTestElement();
        } finally {
            updating = false;
        }
        workload.setThreadGroupId(ScenarioWorkloadGui.threadGroupId(threadGroup));
        workload.setName(uniqueName(threadGroup.getName()));
        workloads.add(workload);
        refreshTable(workloads.size() - 1);
    }

    private void duplicateSelected() {
        int row = editedRow;
        if (row < 0) {
            return;
        }
        commitEditor();
        ScenarioWorkload copy = (ScenarioWorkload) workloads.get(row).clone();
        copy.setName(uniqueName(copy.getName()));
        workloads.add(row + 1, copy);
        editedRow = -1;
        refreshTable(row + 1);
    }

    private void removeSelected() {
        int row = editedRow;
        if (row < 0) {
            return;
        }
        workloads.remove(row);
        editedRow = -1;
        refreshTable(Math.min(row, workloads.size() - 1));
    }

    private void moveSelected(int offset) {
        int row = editedRow;
        int target = row + offset;
        if (row < 0 || target < 0 || target >= workloads.size()) {
            return;
        }
        commitEditor();
        workloads.add(target, workloads.remove(row));
        editedRow = -1;
        refreshTable(target);
    }

    private String uniqueName(String name) {
        Set<String> used = new HashSet<>();
        for (ScenarioWorkload workload : workloads) {
            used.add(workload.getName());
        }
        String candidate = name;
        for (int i = 2; used.contains(candidate); i++) {
            candidate = name + " (" + i + ")";
        }
        return candidate;
    }

    private void updateTotals() {
        long threads = 0;
        double rate = 0;
        int unlimitedThreads = 0;
        int unknownRate = 0;
        int ownSettings = 0;
        int runTimeThreads = 0;
        for (ScenarioWorkload workload : workloads) {
            if (!workload.isEnabled()) {
                continue;
            }
            if (usesOwnSettings(workload)) {
                ownSettings++;
                continue;
            }
            WorkloadSummary summary = WorkloadSummary.of(workload);
            if (summary.getThreadsExpression() != null) {
                runTimeThreads++;
            } else if (summary.getPeakThreads() == null) {
                unlimitedThreads++;
            } else {
                threads += summary.getPeakThreads();
            }
            if (summary.getPeakIterationsPerMinute() == null) {
                unknownRate++;
            } else {
                rate += summary.getPeakIterationsPerMinute();
            }
        }
        StringBuilder text = new StringBuilder(MessageFormat.format(
                JMeterUtils.getResString("scenario_totals"), threads, formatRate(rate))); // $NON-NLS-1$
        if (unlimitedThreads > 0) {
            text.append(' ').append(MessageFormat.format(
                    JMeterUtils.getResString("scenario_totals_unlimited_threads"), unlimitedThreads)); // $NON-NLS-1$
        }
        if (unknownRate > 0) {
            text.append(' ').append(MessageFormat.format(
                    JMeterUtils.getResString("scenario_totals_unknown_rate"), unknownRate)); // $NON-NLS-1$
        }
        if (runTimeThreads > 0) {
            text.append(' ').append(MessageFormat.format(
                    JMeterUtils.getResString("scenario_totals_run_time_threads"), runTimeThreads)); // $NON-NLS-1$
        }
        if (ownSettings > 0) {
            text.append(' ').append(MessageFormat.format(
                    JMeterUtils.getResString("scenario_totals_own_settings"), ownSettings)); // $NON-NLS-1$
        }
        totals.setText(text.toString());
    }

    private static boolean usesOwnSettings(ScenarioWorkload workload) {
        return ScenarioWorkloadGui.availableThreadGroups().stream()
                .anyMatch(threadGroup -> threadGroup.getThreadGroupId().equals(workload.getThreadGroupId())
                        && ScenarioWorkload.usesOwnSettings(threadGroup));
    }

    private static String formatRate(double perMinute) {
        return new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(perMinute);
    }

    private static String formatDuration(WorkloadSummary summary) {
        if (summary.getDurationExpression() != null) {
            return summary.getDurationExpression() + " s"; // $NON-NLS-1$
        }
        if (summary.getLoopsExpression() != null) {
            return MessageFormat.format(JMeterUtils.getResString("scenario_loops"), // $NON-NLS-1$
                    summary.getLoopsExpression());
        }
        Long seconds = summary.getDurationSeconds();
        if (seconds != null) {
            long hours = seconds / 3600;
            long minutes = seconds % 3600 / 60;
            long rest = seconds % 60;
            if (hours > 0) {
                return hours + "h " + minutes + "m"; // $NON-NLS-1$ $NON-NLS-2$
            }
            return minutes > 0 ? minutes + "m " + rest + "s" : rest + "s"; // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
        }
        Integer loops = summary.getLoops();
        if (loops != null && loops < 0) {
            return JMeterUtils.getResString("scenario_forever"); // $NON-NLS-1$
        }
        if (loops != null) {
            return MessageFormat.format(JMeterUtils.getResString("scenario_loops"), loops); // $NON-NLS-1$
        }
        return "–"; // $NON-NLS-1$
    }

    private static void activateCurrentScenario() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
        JMeterTreeNode node = guiPackage.getCurrentNode();
        if (node != null && node.getTestElement() instanceof Scenario) {
            node.setEnabled(true);
            guiPackage.getGui(node.getTestElement()).setEnabled(true);
            disableOtherScenarios(node);
            guiPackage.refreshCurrentGui();
            guiPackage.getMainFrame().repaint();
        }
    }

    /**
     * Only one scenario can run, so enabling a scenario disables the other scenarios of its section.
     * @param enabledScenario tree node of the scenario that has just been enabled
     */
    public static void disableOtherScenarios(JMeterTreeNode enabledScenario) {
        if (!(enabledScenario.getParent() instanceof JMeterTreeNode section)) {
            return;
        }
        for (int i = 0; i < section.getChildCount(); i++) {
            if (section.getChildAt(i) instanceof JMeterTreeNode sibling && sibling != enabledScenario
                    && sibling.getTestElement() instanceof Scenario && sibling.isEnabled()) {
                sibling.setEnabled(false);
            }
        }
    }

    /** One row per thread group run by the scenario, with its expected load. */
    private final class WorkloadTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        private final String[] columns = {
                "", // $NON-NLS-1$
                JMeterUtils.getResString("scenario_column_name"), // $NON-NLS-1$
                JMeterUtils.getResString("scenario_column_thread_group"), // $NON-NLS-1$
                JMeterUtils.getResString("scenario_workload_profile"), // $NON-NLS-1$
                JMeterUtils.getResString("scenario_column_model"), // $NON-NLS-1$
                JMeterUtils.getResString("scenario_column_peak_threads"), // $NON-NLS-1$
                JMeterUtils.getResString("scenario_column_peak_rate"), // $NON-NLS-1$
                JMeterUtils.getResString("scenario_column_duration"), // $NON-NLS-1$
        };

        /** Names from the test plan, read when the table is refreshed rather than for every cell it paints */
        private Map<String, String> threadGroupNames = Map.of();
        private String defaultProfile = ""; // $NON-NLS-1$

        void reloadPlanNames() {
            threadGroupNames = ScenarioWorkloadGui.availableThreadGroups().stream()
                    .filter(tg -> !tg.getThreadGroupId().isEmpty())
                    .collect(Collectors.toMap(AbstractThreadGroup::getThreadGroupId, AbstractThreadGroup::getName,
                            (a, b) -> a));
            defaultProfile = ScenarioWorkloadGui.defaultProfileName();
        }

        @Override
        public int getRowCount() {
            return workloads.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == ENABLED_COLUMN ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == ENABLED_COLUMN;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != ENABLED_COLUMN) {
                return;
            }
            boolean enabled = Boolean.TRUE.equals(value);
            workloads.get(row).setEnabled(enabled);
            if (row == editedRow) {
                editor.setEnabled(enabled);
            }
            fireTableRowsUpdated(row, row);
            updateTotals();
        }

        @Override
        public Object getValueAt(int row, int column) {
            ScenarioWorkload workload = workloads.get(row);
            if (column == ENABLED_COLUMN) {
                return workload.isEnabled();
            }
            if (column == 1) {
                return workload.getName();
            }
            if (column == 2) {
                return threadGroupNames.getOrDefault(workload.getThreadGroupId(),
                        JMeterUtils.getResString("scenario_workload_missing_thread_group")); // $NON-NLS-1$
            }
            if (column == 3) {
                if (!workload.getProfile().isEmpty()) {
                    return workload.getProfile();
                }
                return defaultProfile.isEmpty()
                        ? ScenarioWorkloadGui.useDefaultLabel()
                        : ScenarioWorkloadGui.useDefaultLabel() + " (" + defaultProfile + ")";
            }
            if (usesOwnSettings(workload)) {
                return column == 4 ? JMeterUtils.getResString("scenario_own_settings") : "–"; // $NON-NLS-1$ $NON-NLS-2$
            }
            WorkloadSummary summary = WorkloadSummary.of(workload);
            return switch (column) {
                case 4 -> JMeterUtils.getResString(summary.getOpen()
                        ? "thread_group_model_open" : "thread_group_model_closed"); // $NON-NLS-1$ $NON-NLS-2$
                case 5 -> summary.getThreadsExpression() != null
                        ? summary.getThreadsExpression()
                        : summary.getPeakThreads() == null
                        ? JMeterUtils.getResString("scenario_unlimited") // $NON-NLS-1$
                        : String.valueOf(summary.getPeakThreads());
                case 6 -> summary.getPeakIterationsPerMinute() == null
                        ? "–" // $NON-NLS-1$
                        : formatRate(summary.getPeakIterationsPerMinute());
                default -> formatDuration(summary);
            };
        }
    }
}
