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
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessExceptions;
import org.apache.jmeter.readiness.ReadinessVerdict;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.check.ReadinessOptions;
import org.apache.jmeter.readiness.check.ReadinessReport;

/**
 * The script readiness wizard: (1) scope, checks and validation settings, (2) run with progress and cancellation,
 * (3) findings grouped by check or by transaction, (4) readiness summary. Works without a window, so it can be
 * tested headless.
 */
public final class ReadinessWizardPanel extends JPanel implements ReadinessController.Listener {
    private static final long serialVersionUID = 1L;

    static final String SETUP = "setup"; // $NON-NLS-1$
    static final String RUN = "run"; // $NON-NLS-1$
    static final String FINDINGS = "findings"; // $NON-NLS-1$
    static final String SUMMARY = "summary"; // $NON-NLS-1$
    private static final List<String> STEPS = List.of(SETUP, RUN, FINDINGS, SUMMARY);
    private static final List<String> STEP_TITLES = List.of("Scope and checks", "Run", "Findings", "Summary");

    private final transient ReadinessController controller;
    private final CardLayout cards = new CardLayout();
    private final JPanel steps = new JPanel(cards);
    private final JLabel stepTitle = new JLabel();
    final JButton back = new JButton("Back");
    final JButton next = new JButton("Next");
    private String current = SETUP;

    // Setup
    final JRadioButton scopeSelected = new JRadioButton("Selected thread groups");
    private final JRadioButton scopeAll = new JRadioButton("All thread groups", true);
    private final DefaultListModel<ReadinessController.GroupChoice> groupsModel = new DefaultListModel<>();
    private final JList<ReadinessController.GroupChoice> groupsList = new JList<>(groupsModel);
    private final Map<CheckId, JCheckBox> checkBoxes = new EnumMap<>(CheckId.class);
    private final JCheckBox runValidation = new JCheckBox("Run a readiness validation for the runtime checks", true);
    private final JSpinner iterations = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JCheckBox ignoreTimers = new JCheckBox("Ignore timers during validation", true);
    private final JSpinner timeLimit = new JSpinner(new SpinnerNumberModel(300, 10, 86_400, 10));
    private final JSpinner budget = new JSpinner(new SpinnerNumberModel(3.0, 0.1, 10_000.0, 0.5));
    private final JSpinner offlineRuns = new JSpinner(new SpinnerNumberModel(20, 0, 1000, 5));
    private final JCheckBox repeatability = new JCheckBox("Check repeatability in a fresh session", false);
    private final JSpinner repeatIterations = new JSpinner(new SpinnerNumberModel(2, 2, 20, 1));
    private final JSpinner repeatTimeLimit = new JSpinner(new SpinnerNumberModel(120, 10, 86_400, 10));

    // Run
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea runLog = new JTextArea(12, 60);
    final JButton cancel = new JButton("Cancel");

    // Findings
    private final JRadioButton byCheck = new JRadioButton("By check", true);
    final JRadioButton byTransaction = new JRadioButton("By transaction");
    private final DefaultTreeModel findingsModel = new DefaultTreeModel(new DefaultMutableTreeNode("Findings"));
    final JTree findingsTree = new JTree(findingsModel);
    private final JTextArea details = new JTextArea(8, 60);
    private final JLabel evidenceStatus = new JLabel();
    final JButton goTo = new JButton("Go to element");
    final JButton acceptButton = new JButton("Accept…");
    final JButton markButton = new JButton("Mark…");
    final JButton removeButton = new JButton("Remove exception");
    final JButton rerun = new JButton("Re-run checks");

    // Summary
    private final JLabel verdict = new JLabel();
    private final JLabel counts = new JLabel();
    private final JPanel tiles = new JPanel();
    private final JTextArea reasons = new JTextArea(4, 60);
    private final DefaultTableModel checksTable = new DefaultTableModel(new Object[]{"Check", "State", "Summary"}, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    private transient ReadinessReport report;
    private transient ReadinessController.RunRequest lastRequest;

    /** A unused exception shown in the findings tree. */
    record UnusedItem(ReadinessReport.UnusedException unused) {
        @Override
        public String toString() {
            return "Unused exception: " + unused.exception().checkId().title() + " / "
                    + unused.exception().findingKey() + " on '" + unused.element() + "' — "
                    + unused.exception().reason();
        }
    }

    public ReadinessWizardPanel(ReadinessController controller) {
        super(new BorderLayout(0, 8));
        this.controller = controller;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        stepTitle.setFont(stepTitle.getFont().deriveFont(Font.BOLD, stepTitle.getFont().getSize2D() + 2f));
        add(stepTitle, BorderLayout.NORTH);
        steps.add(createSetup(), SETUP);
        steps.add(createRun(), RUN);
        steps.add(createFindings(), FINDINGS);
        steps.add(createSummary(), SUMMARY);
        add(steps, BorderLayout.CENTER);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        back.setMnemonic(KeyEvent.VK_B);
        next.setMnemonic(KeyEvent.VK_N);
        back.addActionListener(e -> goBack());
        next.addActionListener(e -> goNext());
        navigation.add(back);
        navigation.add(next);
        add(navigation, BorderLayout.SOUTH);
        controller.setListener(this);
        reloadGroups();
        ReadinessReport latest = controller.latestReport();
        if (latest != null) {
            showReport(latest);
            show(SUMMARY);
        } else {
            show(SETUP);
        }
    }

    // ---------------------------------------------------------------- setup

    private JComponent createSetup() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(2, 2, 2, 2);
        c.weightx = 1;

        JPanel scope = titled("Scope");
        scope.setLayout(new BorderLayout(4, 4));
        ButtonGroup scopeGroup = new ButtonGroup();
        scopeGroup.add(scopeAll);
        scopeGroup.add(scopeSelected);
        scopeAll.setMnemonic(KeyEvent.VK_A);
        scopeSelected.setMnemonic(KeyEvent.VK_S);
        JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT));
        radios.add(scopeAll);
        radios.add(scopeSelected);
        scope.add(radios, BorderLayout.NORTH);
        groupsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        groupsList.setVisibleRowCount(4);
        groupsList.setCellRenderer((list, value, index, selected, focus) -> new javax.swing.DefaultListCellRenderer()
                .getListCellRendererComponent(list, value.name(), index, selected, focus));
        scopeAll.addActionListener(e -> groupsList.setEnabled(false));
        scopeSelected.addActionListener(e -> groupsList.setEnabled(true));
        scope.add(new JScrollPane(groupsList), BorderLayout.CENTER);
        panel.add(scope, c);

        JPanel checks = titled("Checks (⏱ needs a validation run)");
        checks.setLayout(new GridBagLayout());
        GridBagConstraints cc = new GridBagConstraints();
        cc.anchor = GridBagConstraints.WEST;
        int i = 0;
        for (CheckId id : CheckId.values()) {
            JCheckBox box = new JCheckBox(id.title() + (id.needsValidationRun() ? "  ⏱" : "")
                    + (id.isMandatory() ? "  (mandatory for Ready)" : ""), true);
            box.setName(id.key());
            box.setToolTipText(id.needsValidationRun() ? "Uses evidence from a validation run" : "Configuration only");
            checkBoxes.put(id, box);
            cc.gridx = i % 2;
            cc.gridy = i / 2;
            checks.add(box, cc);
            i++;
        }
        JButton all = new JButton("Select all");
        all.addActionListener(e -> checkBoxes.values().forEach(b -> b.setSelected(true)));
        cc.gridx = 0;
        cc.gridy = (i + 1) / 2 + 1;
        checks.add(all, cc);
        c.gridy++;
        panel.add(checks, c);

        JPanel validation = titled("Validation settings (this run only)");
        validation.setLayout(new GridBagLayout());
        ValidationSettings defaults = ValidationSettings.defaults();
        ignoreTimers.setSelected(defaults.ignoreTimers());
        iterations.setValue(Math.max(1, defaults.iterations()));
        int row = 0;
        addRow(validation, row++, runValidation, null);
        addRow(validation, row++, new JLabel("Iterations:"), iterations);
        addRow(validation, row++, ignoreTimers, null);
        addRow(validation, row++, new JLabel("Time limit (s):"), timeLimit);
        addRow(validation, row++, new JLabel("Extraction budget per invocation (ms):"), budget);
        addRow(validation, row++, new JLabel("Offline extractor runs per response:"), offlineRuns);
        addRow(validation, row++, repeatability, null);
        addRow(validation, row++, new JLabel("Repeatability iterations:"), repeatIterations);
        addRow(validation, row, new JLabel("Repeatability time limit (s):"), repeatTimeLimit);
        c.gridy++;
        c.weighty = 1;
        panel.add(validation, c);
        return new JScrollPane(panel);
    }

    private static void addRow(JPanel panel, int row, JComponent label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(1, 2, 1, 6);
        panel.add(label, c);
        if (field != null) {
            c.gridx = 1;
            panel.add(field, c);
        }
    }

    private static JPanel titled(String title) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    void reloadGroups() {
        groupsModel.clear();
        List<Integer> selected = new ArrayList<>();
        int index = 0;
        for (ReadinessController.GroupChoice choice : controller.threadGroups()) {
            groupsModel.addElement(choice);
            if (choice.selected()) {
                selected.add(index);
            }
            index++;
        }
        groupsList.setSelectedIndices(selected.stream().mapToInt(Integer::intValue).toArray());
        if (!selected.isEmpty()) {
            scopeSelected.setSelected(true);
        }
        groupsList.setEnabled(scopeSelected.isSelected());
    }

    /**
     * Opens the setup step with a preset scope, as the thread group popups do.
     *
     * @param all every thread group, or the thread groups selected in the tree
     */
    void useScope(boolean all) {
        reloadGroups();
        (all ? scopeAll : scopeSelected).setSelected(true);
        groupsList.setEnabled(!all);
        show(SETUP);
    }

    /**
     * @return the run the setup step describes
     */
    ReadinessController.RunRequest request() {
        List<String> ids = new ArrayList<>();
        if (scopeAll.isSelected()) {
            for (int i = 0; i < groupsModel.size(); i++) {
                ids.add(groupsModel.get(i).id());
            }
        } else {
            groupsList.getSelectedValuesList().forEach(choice -> ids.add(choice.id()));
        }
        Set<CheckId> checks = EnumSet.noneOf(CheckId.class);
        checkBoxes.forEach((id, box) -> {
            if (box.isSelected()) {
                checks.add(id);
            }
        });
        ValidationSettings defaults = ValidationSettings.defaults();
        ValidationSettings settings = new ValidationSettings(defaults.threadsPerThreadGroup(),
                ignoreTimers.isSelected(), (Integer) iterations.getValue(), defaults.ignoreBackends());
        long budgetNanos = Math.round(((Number) budget.getValue()).doubleValue() * 1_000_000);
        ReadinessOptions options = new ReadinessOptions(budgetNanos, (Integer) offlineRuns.getValue(),
                repeatability.isSelected() && checks.contains(CheckId.REPEATABILITY)
                        ? (Integer) repeatIterations.getValue() : 0,
                (Integer) repeatTimeLimit.getValue());
        return new ReadinessController.RunRequest(ids, checks, runValidation.isSelected(), settings,
                (Integer) timeLimit.getValue(), options);
    }

    // ---------------------------------------------------------------- run

    private JComponent createRun() {
        JPanel panel = new JPanel(new BorderLayout(4, 8));
        progress.setIndeterminate(true);
        panel.add(progress, BorderLayout.NORTH);
        runLog.setEditable(false);
        runLog.setLineWrap(true);
        runLog.setWrapStyleWord(true);
        panel.add(new JScrollPane(runLog), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cancel.setMnemonic(KeyEvent.VK_C);
        cancel.addActionListener(e -> controller.cancel());
        buttons.add(cancel);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    void startRun() {
        ReadinessController.RunRequest request = request();
        if (request.groupIds().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select at least one thread group.", "Script readiness",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (request.checks().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select at least one check.", "Script readiness",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        lastRequest = request;
        runLog.setText("");
        progress.setIndeterminate(true);
        cancel.setEnabled(true);
        controller.start(request);
        show(RUN);
    }

    @Override
    public void progress(String message) {
        runLog.append(message + "\n");
    }

    @Override
    public void finished(ReadinessReport newReport) {
        progress.setIndeterminate(false);
        progress.setValue(progress.getMaximum());
        cancel.setEnabled(false);
        showReport(newReport);
        show(FINDINGS);
    }

    @Override
    public void updated(ReadinessReport newReport) {
        Object selected = selection();
        showReport(newReport);
        if (selected instanceof Finding finding) {
            select(o -> o instanceof Finding f && f.checkId() == finding.checkId()
                    && f.findingKey().equals(finding.findingKey())
                    && java.util.Objects.equals(f.element(), finding.element()));
        }
    }

    @Override
    public void failed(String message) {
        progress.setIndeterminate(false);
        cancel.setEnabled(false);
        runLog.append(message + "\n");
        // Recomputes the navigation buttons now that nothing runs
        show(RUN);
    }

    // ---------------------------------------------------------------- findings

    private JComponent createFindings() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ButtonGroup grouping = new ButtonGroup();
        grouping.add(byCheck);
        grouping.add(byTransaction);
        byCheck.setMnemonic(KeyEvent.VK_K);
        byTransaction.setMnemonic(KeyEvent.VK_T);
        byCheck.addActionListener(e -> rebuildFindings());
        byTransaction.addActionListener(e -> rebuildFindings());
        top.add(new JLabel("Group:"));
        top.add(byCheck);
        top.add(byTransaction);
        top.add(Box.createHorizontalStrut(16));
        top.add(evidenceStatus);
        panel.add(top, BorderLayout.NORTH);

        findingsTree.setRootVisible(false);
        findingsTree.setShowsRootHandles(true);
        findingsTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        findingsTree.setCellRenderer(new FindingRenderer());
        findingsTree.addTreeSelectionListener(e -> updateDetails());
        findingsTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSelection();
                }
            }
        });
        findingsTree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "goTo");
        findingsTree.getActionMap().put("goTo", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                navigateToSelection();
            }
        });
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(findingsTree),
                new JScrollPane(details));
        split.setResizeWeight(0.7);
        panel.add(split, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        goTo.setMnemonic(KeyEvent.VK_G);
        acceptButton.setMnemonic(KeyEvent.VK_E);
        markButton.setMnemonic(KeyEvent.VK_M);
        removeButton.setMnemonic(KeyEvent.VK_R);
        rerun.setMnemonic(KeyEvent.VK_U);
        goTo.addActionListener(e -> navigateToSelection());
        acceptButton.addActionListener(e -> acceptSelection());
        markButton.addActionListener(e -> markSelection());
        removeButton.addActionListener(e -> removeSelection());
        rerun.addActionListener(e -> {
            if (lastRequest != null) {
                startRun();
            } else {
                show(SETUP);
            }
        });
        buttons.add(goTo);
        buttons.add(acceptButton);
        buttons.add(markButton);
        buttons.add(removeButton);
        buttons.add(rerun);
        panel.add(buttons, BorderLayout.SOUTH);
        updateDetails();
        return panel;
    }

    void showReport(ReadinessReport newReport) {
        report = newReport;
        String status = newReport.evidenceStatus();
        evidenceStatus.setText(status);
        evidenceStatus.setForeground(status.startsWith("Stale") ? color("Actions.Red", Color.RED) : null);
        rebuildFindings();
        rebuildSummary();
    }

    void setGroupByTransaction(boolean transaction) {
        (transaction ? byTransaction : byCheck).setSelected(true);
        rebuildFindings();
    }

    private void rebuildFindings() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Findings");
        if (report != null) {
            if (byCheck.isSelected()) {
                for (CheckResult result : report.results()) {
                    if (result.state() == CheckState.NOT_RUN) {
                        continue;
                    }
                    DefaultMutableTreeNode group = new DefaultMutableTreeNode(result);
                    result.findings().forEach(f -> group.add(new DefaultMutableTreeNode(f)));
                    root.add(group);
                }
            } else {
                Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
                for (CheckResult result : report.results()) {
                    for (Finding finding : result.findings()) {
                        String unit = finding.transaction() == null ? "Outside transactions / plan level"
                                : "Transaction: " + finding.transaction();
                        groups.computeIfAbsent(unit, label -> new DefaultMutableTreeNode(label))
                                .add(new DefaultMutableTreeNode(finding));
                    }
                }
                groups.values().forEach(root::add);
            }
            if (!report.unusedExceptions().isEmpty()) {
                DefaultMutableTreeNode unused = new DefaultMutableTreeNode("Unused exceptions");
                report.unusedExceptions().forEach(u -> unused.add(new DefaultMutableTreeNode(new UnusedItem(u))));
                root.add(unused);
            }
        }
        findingsModel.setRoot(root);
        for (int i = 0; i < findingsTree.getRowCount(); i++) {
            Object node = findingsTree.getPathForRow(i).getLastPathComponent();
            Object value = ((DefaultMutableTreeNode) node).getUserObject();
            if (!(value instanceof CheckResult result) || result.state() != CheckState.PASSED) {
                findingsTree.expandRow(i);
            }
        }
        updateDetails();
    }

    /**
     * @return the user object of the selected tree node, or {@code null}
     */
    Object selection() {
        TreePath path = findingsTree.getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    /**
     * @param predicate selects the first matching tree node
     * @return whether one was found
     */
    boolean select(java.util.function.Predicate<Object> predicate) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) findingsModel.getRoot();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node != root && predicate.test(node.getUserObject())) {
                TreePath path = new TreePath(node.getPath());
                findingsTree.setSelectionPath(path);
                findingsTree.scrollPathToVisible(path);
                return true;
            }
        }
        return false;
    }

    /**
     * @return number of findings shown in the tree
     */
    int shownFindings() {
        int count = 0;
        var nodes = ((DefaultMutableTreeNode) findingsModel.getRoot()).depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            if (((DefaultMutableTreeNode) nodes.nextElement()).getUserObject() instanceof Finding) {
                count++;
            }
        }
        return count;
    }

    private void updateDetails() {
        Object selected = selection();
        StringBuilder text = new StringBuilder();
        ElementRef element = null;
        if (selected instanceof Finding finding) {
            element = finding.element();
            text.append(label(finding)).append(": ").append(finding.message()).append('\n');
            if (finding.element() != null) {
                text.append("Element: ").append(finding.element()).append(" (").append(finding.element().type())
                        .append(")\n");
            }
            if (finding.transaction() != null) {
                text.append("Transaction: ").append(finding.transaction()).append('\n');
            }
            finding.evidence().forEach(line -> text.append(line).append('\n'));
            if (finding.isAccepted()) {
                AcceptedException exception = finding.exception();
                text.append("\nAccepted").append(exception.author().isEmpty() ? "" : " by " + exception.author())
                        .append(exception.date().isEmpty() ? "" : " on " + exception.date()).append(": ")
                        .append(exception.reason()).append('\n');
            }
        } else if (selected instanceof CheckResult result) {
            text.append(result.checkId().title()).append(": ").append(result.state().label())
                    .append(result.stale() ? " (stale evidence)" : "").append('\n').append(result.summary());
        } else if (selected instanceof UnusedItem item) {
            element = item.unused().element();
            text.append(item).append("\nThis exception no longer matches any finding; remove it if it is obsolete.");
        }
        details.setText(text.toString());
        details.setCaretPosition(0);
        boolean finding = selected instanceof Finding f && f.severity() != Severity.INFO;
        goTo.setEnabled(element != null);
        acceptButton.setEnabled(finding && !((Finding) selected).isAccepted());
        markButton.setEnabled(selected instanceof Finding f2 && markingKey(f2) != null);
        removeButton.setEnabled(selected instanceof UnusedItem || selected instanceof Finding f3 && f3.isAccepted());
    }

    private void navigateToSelection() {
        Object selected = selection();
        if (selected instanceof Finding finding && finding.element() != null) {
            controller.navigate(finding.element());
        } else if (selected instanceof UnusedItem item) {
            controller.navigate(item.unused().element());
        }
    }

    /**
     * @param finding a finding
     * @return the marking the finding supports (optional extractor, background activity), or {@code null}
     */
    static String markingKey(Finding finding) {
        if (finding.checkId() == CheckId.UNMATCHED_EXTRACTIONS && !"unsupported".equals(finding.findingKey())) {
            return ReadinessExceptions.OPTIONAL_EXTRACTOR_KEY;
        }
        if (finding.checkId() == CheckId.THINK_TIME) {
            return ReadinessExceptions.BACKGROUND_ACTIVITY_KEY;
        }
        return null;
    }

    private void acceptSelection() {
        if (selection() instanceof Finding finding) {
            recordException(finding, finding.findingKey(), "Accept this finding");
        }
    }

    private void markSelection() {
        if (selection() instanceof Finding finding && markingKey(finding) != null) {
            String key = markingKey(finding);
            recordException(finding, key, ReadinessExceptions.OPTIONAL_EXTRACTOR_KEY.equals(key)
                    ? "Mark this extractor as optional" : "Mark as background/API activity (no think time needed)");
        }
    }

    private void recordException(Finding finding, String key, String title) {
        JTextArea reason = new JTextArea(4, 40);
        reason.setLineWrap(true);
        JTextField author = new JTextField(controller.defaultAuthor(), 20);
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel(title + " on '" + finding.element() + "'. Reason (required):"));
        form.add(new JScrollPane(reason));
        form.add(new JLabel("Author (optional, stored in the test plan):"));
        form.add(author);
        int answer = JOptionPane.showConfirmDialog(this, form, "Readiness exception", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        if (reason.getText().isBlank()) {
            JOptionPane.showMessageDialog(this, "A reason is required.", "Readiness exception",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        controller.accept(finding.element(), new AcceptedException(finding.checkId(), key, reason.getText().trim(),
                author.getText().trim(), LocalDate.now(ZoneId.systemDefault()).toString()));
    }

    private void removeSelection() {
        Object selected = selection();
        if (selected instanceof Finding finding && finding.isAccepted()) {
            controller.remove(finding.element(), finding.exception());
        } else if (selected instanceof UnusedItem item) {
            controller.remove(item.unused().element(), item.unused().exception());
        }
    }

    // ---------------------------------------------------------------- summary

    private JComponent createSummary() {
        JPanel panel = new JPanel(new BorderLayout(4, 8));
        // Everything left-aligned under the verdict, like a report heading
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = GridBagConstraints.RELATIVE;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        tiles.setLayout(new BoxLayout(tiles, BoxLayout.X_AXIS));
        verdict.setFont(verdict.getFont().deriveFont(Font.BOLD, verdict.getFont().getSize2D() + 6f));
        verdict.setIconTextGap(10);
        verdict.setHorizontalAlignment(JLabel.LEADING);
        c.insets = new Insets(0, 0, 6, 0);
        top.add(verdict, c);
        c.insets = new Insets(0, 0, 4, 0);
        top.add(tiles, c);
        counts.setHorizontalAlignment(JLabel.LEADING);
        top.add(counts, c);
        reasons.setEditable(false);
        reasons.setFocusable(false);
        reasons.setOpaque(false);
        reasons.setLineWrap(true);
        reasons.setWrapStyleWord(true);
        reasons.setBorder(BorderFactory.createEmptyBorder());
        reasons.setFont(counts.getFont());
        reasons.setRows(0);
        c.insets = new Insets(4, 0, 0, 0);
        top.add(reasons, c);
        panel.add(top, BorderLayout.NORTH);
        JTable table = new JTable(checksTable);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), StatusIcon.SMALL + 6));
        table.getColumnModel().getColumn(1).setCellRenderer(new StateRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(2).setPreferredWidth(420);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private void rebuildSummary() {
        checksTable.setRowCount(0);
        tiles.removeAll();
        if (report == null) {
            verdict.setText("");
            verdict.setIcon(null);
            counts.setText("");
            reasons.setText("");
            return;
        }
        ReadinessVerdict v = report.summary().verdict();
        verdict.setText(v.label());
        verdict.setIcon(new StatusIcon(StatusIcon.kindOf(v), StatusIcon.LARGE));
        verdict.setForeground(StatusIcon.color(StatusIcon.kindOf(v)));
        counts.setText(report.summary().countsText());
        var summary = report.summary();
        tile(StatusIcon.Kind.PASSED, summary.passed(), "passed");
        tile(StatusIcon.Kind.ACCEPTED, summary.accepted(), "accepted");
        tile(StatusIcon.Kind.FAILED, summary.blockers(), summary.blockers() == 1 ? "blocker" : "blockers");
        tile(StatusIcon.Kind.WARNING, summary.warnings(), summary.warnings() == 1 ? "warning" : "warnings");
        tile(StatusIcon.Kind.REVIEW, summary.reviews(), "to review");
        tile(StatusIcon.Kind.NOT_VERIFIED, summary.notVerified(), "not verified");
        if (summary.suggestions() > 0) {
            tile(StatusIcon.Kind.SUGGESTION, summary.suggestions(),
                    summary.suggestions() == 1 ? "suggestion" : "suggestions");
        }
        tiles.revalidate();
        tiles.repaint();
        StringBuilder text = new StringBuilder();
        report.summary().reasons().forEach(r -> text.append("• ").append(r).append('\n'));
        text.append(report.evidenceStatus());
        if (!report.scope().isEmpty()) {
            text.append("\nScope: ").append(String.join(", ", report.scope()));
        }
        reasons.setText(text.toString());
        for (CheckResult result : report.results()) {
            String state = result.state().label() + (result.stale() ? " (stale)" : "")
                    + (result.acceptedCount() > 0 && result.state() != CheckState.ACCEPTED
                            ? " · " + result.acceptedCount() + " accepted" : "");
            checksTable.addRow(new Object[]{result.checkId().title() + (result.checkId().isMandatory() ? " *" : ""),
                    new StateCell(StatusIcon.kindOf(result), state), result.summary()});
        }
    }

    private void tile(StatusIcon.Kind kind, long count, String what) {
        if (tiles.getComponentCount() > 0) {
            tiles.add(Box.createHorizontalStrut(16));
        }
        JLabel label = new JLabel(count + " " + what, StatusIcon.of(kind), JLabel.LEADING);
        label.setName(kind.name());
        label.setEnabled(count > 0 || kind == StatusIcon.Kind.PASSED);
        tiles.add(label);
    }

    /**
     * @return the status icon of the verdict, or {@code null}
     */
    StatusIcon verdictIcon() {
        return (StatusIcon) verdict.getIcon();
    }

    /**
     * @return the count tiles of the summary, such as "1 blocker"
     */
    List<String> tileTexts() {
        List<String> texts = new ArrayList<>();
        for (Component component : tiles.getComponents()) {
            if (component instanceof JLabel label) {
                texts.add(label.getText());
            }
        }
        return texts;
    }

    /**
     * @return the verdict shown in the summary
     */
    String verdictText() {
        return verdict.getText();
    }

    // ---------------------------------------------------------------- navigation

    void show(String step) {
        current = step;
        cards.show(steps, step);
        int index = STEPS.indexOf(step);
        stepTitle.setText("Step " + (index + 1) + " of 4: " + STEP_TITLES.get(index));
        back.setEnabled(index > 0 && !controller.isRunning());
        next.setText(SETUP.equals(step) ? "Run checks" : "Next");
        next.setEnabled(SETUP.equals(step) || (index < STEPS.size() - 1 && report != null && !controller.isRunning()));
    }

    String currentStep() {
        return current;
    }

    private void goBack() {
        int index = STEPS.indexOf(current);
        if (RUN.equals(STEPS.get(Math.max(0, index - 1))) && report != null) {
            show(SETUP);
        } else if (index > 0) {
            show(STEPS.get(index - 1));
        }
    }

    private void goNext() {
        if (SETUP.equals(current)) {
            startRun();
        } else if (RUN.equals(current) && report != null) {
            show(FINDINGS);
        } else if (FINDINGS.equals(current)) {
            show(SUMMARY);
        }
    }

    private static Color color(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    static String label(Finding finding) {
        if (finding.isAccepted()) {
            return "Accepted";
        }
        return switch (finding.severity()) {
            case BLOCKER -> "Blocker";
            case WARNING -> "Warning";
            case REVIEW -> "Review required";
            case NOT_VERIFIED -> "Not verified";
            case SUGGESTION -> "Suggestion";
            case INFO -> "Info";
        };
    }

    /**
     * @param node a node of the findings tree
     * @return the status icon kind it shows: its own state, or the worst state below a group
     */
    static StatusIcon.Kind kindOf(DefaultMutableTreeNode node) {
        Object item = node.getUserObject();
        if (item instanceof CheckResult result) {
            return StatusIcon.kindOf(result);
        } else if (item instanceof Finding finding) {
            return StatusIcon.kindOf(finding);
        } else if (item instanceof UnusedItem) {
            return StatusIcon.Kind.INFO;
        }
        List<StatusIcon.Kind> kinds = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            kinds.add(kindOf((DefaultMutableTreeNode) node.getChildAt(i)));
        }
        return StatusIcon.worst(kinds);
    }

    private static final class FindingRenderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object item = node.getUserObject();
            String text;
            if (item instanceof CheckResult result) {
                text = result.checkId().title() + "  \u2014  " + result.state().label()
                        + (result.stale() ? " (stale)" : "") + "  \u00b7  " + result.summary();
            } else if (item instanceof Finding finding) {
                text = finding.message() + (finding.severity() == Severity.INFO ? "" : "  \u2014  " + label(finding))
                        + (finding.isAccepted() ? ": " + finding.exception().reason() : "");
            } else {
                text = String.valueOf(item);
            }
            super.getTreeCellRendererComponent(tree, text, selected, expanded, leaf, row, hasFocus);
            StatusIcon.Kind kind = kindOf(node);
            setIcon(StatusIcon.of(kind));
            setToolTipText(kind.description());
            return this;
        }
    }

    /** A check state in the summary table. */
    record StateCell(StatusIcon.Kind kind, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private static final class StateRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setIcon(value instanceof StateCell cell ? StatusIcon.of(cell.kind()) : null);
            setIconTextGap(6);
            return this;
        }
    }
}
