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

package org.apache.jmeter.protocol.http.har;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.util.JMeterUtils;

/** Lets users manage correlation rules and enable individual rules or whole groups. */
final class HarCorrelationRulesPanel extends JPanel {

    interface RuleUpdater {
        Rule update(Rule rule);
    }

    interface RuleTransfer {
        List<Rule> importRules();

        void exportRules(List<Rule> rules);
    }

    private static final long serialVersionUID = 1L;

    private final List<Rule> rules;
    private final Set<String> customRuleIds;
    private final RuleUpdater ruleUpdater;
    private final RuleTransfer ruleTransfer;
    private final Set<String> disabledRuleIds = new LinkedHashSet<>();
    private Predicate<List<Rule>> ruleDeleter;
    private Predicate<List<Rule>> deletionConfirmation;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("rules");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree ruleTree = new JTree(treeModel);
    private final DefaultTableModel detailsModel = new DefaultTableModel(
            new Object[] {"Setting", "Value"}, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable detailsTable = new JTable(detailsModel);
    private final JButton editButton = new JButton(
            JMeterUtils.getResString("try_predefined_correlations_edit_custom"));
    private final JButton exportButton = new JButton(
            JMeterUtils.getResString("try_predefined_correlations_export_custom"));
    private final JButton exportGroupButton = new JButton(
            JMeterUtils.getResString("try_predefined_correlations_export_group"));
    private final JCheckBox customOnly = new JCheckBox(
            JMeterUtils.getResString("try_predefined_correlations_custom_only"));
    private final JButton deleteButton = new JButton(JMeterUtils.getResString("correlation_rules_delete"));
    private final JButton moveButton = new JButton(JMeterUtils.getResString("correlation_rules_move"));
    private Predicate<List<Rule>> ruleMover;
    private Function<List<String>, String> groupChooser;
    private Rule selectedRule;

    void configureMoving(Predicate<List<Rule>> mover) {
        configureMoving(mover, groups -> {
            JComboBox<String> destination = new JComboBox<>(groups.toArray(String[]::new));
            destination.setEditable(true);
            destination.setSelectedItem("");
            JPanel prompt = new JPanel(new BorderLayout(0, 8));
            prompt.add(new JLabel(JMeterUtils.getResString("correlation_rules_move_prompt")), BorderLayout.NORTH);
            prompt.add(destination, BorderLayout.CENTER);
            while (JOptionPane.showConfirmDialog(this, prompt,
                    JMeterUtils.getResString("correlation_rules_move"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                String group = String.valueOf(destination.getEditor().getItem()).trim();
                if (!group.isEmpty()) {
                    return group;
                }
            }
            return null;
        });
    }

    void configureMoving(Predicate<List<Rule>> mover, Function<List<String>, String> chooser) {
        ruleMover = mover;
        groupChooser = chooser;
        refreshSelectedGroupDetails();
    }


    void configureManagement(Set<String> disabled, Predicate<List<Rule>> deleter) {
        configureManagement(disabled, deleter, selected -> JOptionPane.showConfirmDialog(this,
                MessageFormat.format(JMeterUtils.getResString("correlation_rules_confirm_delete"), selected.size()),
                JMeterUtils.getResString("correlation_rules_delete_selected"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION);
    }

    void configureManagement(Set<String> disabled, Predicate<List<Rule>> deleter,
            Predicate<List<Rule>> confirmation) {
        deletionConfirmation = confirmation;
        disabledRuleIds.clear();
        disabledRuleIds.addAll(disabled);
        ruleDeleter = deleter;
        ruleTree.repaint();
        showNode((CatalogNode) ruleTree.getLastSelectedPathComponent());
    }

    Set<String> getDisabledRuleIds() {
        return Set.copyOf(disabledRuleIds);
    }

    void setRuleEnabled(String id, boolean enabled) {
        if (enabled) {
            disabledRuleIds.remove(id);
        } else {
            disabledRuleIds.add(id);
        }
        ruleTree.repaint();
    }

    private boolean isGroupEnabled(String group) {
        return rules.stream().filter(rule -> group.equals(rule.getGroup()))
                .allMatch(rule -> !disabledRuleIds.contains(rule.getId()));
    }

    HarCorrelationRulesPanel(List<Rule> rules, Set<String> customRuleIds, RuleUpdater ruleUpdater,
            RuleTransfer ruleTransfer) {
        super(new BorderLayout(10, 10));
        this.rules = new ArrayList<>(rules);
        this.customRuleIds = new LinkedHashSet<>(customRuleIds);
        this.ruleUpdater = ruleUpdater;
        this.ruleTransfer = ruleTransfer;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(new JLabel(JMeterUtils.getResString("try_predefined_correlations_rules_prompt")),
                BorderLayout.NORTH);
        add(createBrowser(), BorderLayout.CENTER);
    }

    List<Rule> getSelectedRules() {
        List<Rule> selected = new ArrayList<>();
        for (Rule rule : rules) {
            if (!disabledRuleIds.contains(rule.getId())) {
                selected.add(rule);
            }
        }
        return List.copyOf(selected);
    }

    void setGroupSelected(String group, boolean selected) {
        rules.stream().filter(rule -> group.equals(rule.getGroup()))
                .forEach(rule -> setRuleEnabled(rule.getId(), selected));
        refreshSelectedGroupDetails();
    }

    List<String> getRulePaths() {
        return displayedRules().stream().map(rule -> rule.getGroup() + " > " + rule.getName()).toList();
    }

    void setCustomOnly(boolean selected) {
        customOnly.setSelected(selected);
        rebuildTree();
    }

    boolean areAllGroupsCollapsed() {
        for (int groupIndex = 0; groupIndex < treeRoot.getChildCount(); groupIndex++) {
            TreePath groupPath = new TreePath(((CatalogNode) treeRoot.getChildAt(groupIndex)).getPath());
            if (ruleTree.isExpanded(groupPath)) {
                return false;
            }
        }
        return true;
    }

    private Component createBrowser() {
        JPanel treePanel = new JPanel(new BorderLayout(0, 6));
        JPanel controls = new JPanel(new GridLayout(0, 1, 0, 2));
        JPanel groupButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        JButton selectAll = new JButton(
                JMeterUtils.getResString("try_predefined_correlations_select_all"));
        selectAll.addActionListener(event -> setAllGroupsSelected(true));
        JButton selectNone = new JButton(
                JMeterUtils.getResString("try_predefined_correlations_select_none"));
        selectNone.addActionListener(event -> setAllGroupsSelected(false));
        customOnly.addActionListener(event -> rebuildTree());
        JButton importButton = new JButton(
                JMeterUtils.getResString("try_predefined_correlations_import_custom"));
        importButton.setEnabled(ruleTransfer != null);
        importButton.addActionListener(event -> importCustomRules());
        exportButton.setEnabled(ruleTransfer != null && !getCustomRules().isEmpty());
        exportButton.addActionListener(event -> ruleTransfer.exportRules(getCustomRules()));
        exportGroupButton.setEnabled(false);
        exportGroupButton.addActionListener(event -> ruleTransfer.exportRules(getSelectedGroupCustomRules()));
        groupButtons.add(selectAll);
        groupButtons.add(selectNone);
        JPanel filter = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        filter.add(customOnly);
        JPanel transferButtons = new JPanel(new GridLayout(1, 0, 6, 0));
        transferButtons.add(importButton);
        transferButtons.add(exportGroupButton);
        transferButtons.add(exportButton);
        controls.add(groupButtons);
        controls.add(filter);
        treePanel.add(controls, BorderLayout.NORTH);
        configureTree();
        treePanel.add(new JScrollPane(ruleTree), BorderLayout.CENTER);

        JPanel detailPanel = new JPanel(new BorderLayout(0, 6));
        JPanel detailHeader = new JPanel(new BorderLayout());
        detailHeader.add(new JLabel(JMeterUtils.getResString("try_predefined_correlations_rule_details")),
                BorderLayout.WEST);
        JButton infoButton = new JButton(JMeterUtils.getResString("try_predefined_correlations_info"));
        infoButton.addActionListener(this::showCustomRuleInfo);
        detailHeader.add(infoButton, BorderLayout.EAST);
        detailPanel.add(detailHeader, BorderLayout.NORTH);
        detailsTable.setFillsViewportHeight(true);
        detailsTable.setRowSelectionAllowed(false);
        detailsTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        detailsTable.getColumnModel().getColumn(1).setPreferredWidth(420);
        detailPanel.add(new JScrollPane(detailsTable), BorderLayout.CENTER);
        editButton.addActionListener(this::editSelectedRule);
        deleteButton.addActionListener(event -> deleteCustomRules(getSelectedCustomRules()));
        JPanel ruleButtons = new JPanel(new FlowLayout(FlowLayout.LEADING));
        ruleButtons.add(editButton);
        ruleButtons.add(deleteButton);
        moveButton.addActionListener(event -> moveSelectedRules());
        moveButton.setEnabled(false);
        ruleButtons.add(moveButton);
        detailPanel.add(ruleButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, detailPanel);
        split.setBorder(null);
        split.setResizeWeight(0.38);
        split.setDividerLocation(320);
        JPanel browser = new JPanel(new BorderLayout(0, 6));
        browser.add(transferButtons, BorderLayout.NORTH);
        browser.add(split, BorderLayout.CENTER);
        return browser;
    }

    private void setAllGroupsSelected(boolean selected) {
        rules.forEach(rule -> setRuleEnabled(rule.getId(), selected));
        ruleTree.repaint();
        refreshSelectedGroupDetails();
    }

    private void configureTree() {
        ruleTree.setRootVisible(false);
        ruleTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        ruleTree.setShowsRootHandles(true);
        ruleTree.setCellRenderer(new CatalogTreeRenderer());
        ruleTree.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "toggleRule");
        ruleTree.getActionMap().put("toggleRule", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                toggleSelectedRules();
            }
        });
        ruleTree.addTreeSelectionListener(event -> showNode(
                (CatalogNode) ruleTree.getLastSelectedPathComponent()));
        ruleTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!showPopup(event)) {
                    toggleGroupAt(event);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showPopup(event);
            }
        });
        rebuildTree();
    }

    void selectRule(String id) {
        TreePath path = findRulePath(id);
        if (path != null) {
            ruleTree.setSelectionPath(path);
            ruleTree.scrollPathToVisible(path);
        }
    }

    private void rebuildTree() {
        String selectedRuleId = selectedRule == null ? null : selectedRule.getId();
        treeRoot.removeAllChildren();
        Map<String, List<Rule>> rulesByGroup = new LinkedHashMap<>();
        for (Rule rule : displayedRules()) {
            rulesByGroup.computeIfAbsent(rule.getGroup(), ignored -> new ArrayList<>()).add(rule);
        }
        for (Map.Entry<String, List<Rule>> group : rulesByGroup.entrySet()) {
            CatalogNode groupNode = CatalogNode.group(group.getKey());
            treeRoot.add(groupNode);
            for (Rule rule : group.getValue()) {
                groupNode.add(CatalogNode.rule(rule));
            }
        }
        treeModel.reload();
        TreePath selectedPath = findRulePath(selectedRuleId);
        if (selectedPath == null && treeRoot.getChildCount() > 0) {
            selectedPath = new TreePath(((CatalogNode) treeRoot.getChildAt(0)).getPath());
        }
        if (selectedPath != null) {
            ruleTree.setSelectionPath(selectedPath);
            if (selectedRuleId != null) {
                ruleTree.scrollPathToVisible(selectedPath);
            }
        } else {
            showNode(null);
        }
    }

    private List<Rule> getSelectedGroupCustomRules() {
        if (!(ruleTree.getLastSelectedPathComponent() instanceof CatalogNode node)) {
            return List.of();
        }
        String group = node.isGroup() ? node.group() : node.rule().getGroup();
        return getCustomRules().stream().filter(rule -> group.equals(rule.getGroup())).toList();
    }

    private List<Rule> displayedRules() {
        if (!customOnly.isSelected()) {
            return List.copyOf(rules);
        }
        return getCustomRules();
    }

    List<Rule> getCustomRules() {
        return rules.stream().filter(rule -> customRuleIds.contains(rule.getId())).toList();
    }

    private void importCustomRules() {
        if (ruleTransfer == null) {
            return;
        }
        List<Rule> imported = ruleTransfer.importRules();
        if (imported == null || imported.isEmpty()) {
            return;
        }
        Map<String, Rule> merged = new LinkedHashMap<>();
        for (Rule rule : rules) {
            merged.put(rule.getId(), rule);
        }
        for (Rule rule : imported) {
            merged.put(rule.getId(), rule);
            customRuleIds.add(rule.getId());
        }
        rules.clear();
        rules.addAll(merged.values());
        exportButton.setEnabled(true);
        rebuildTree();
    }

    private TreePath findRulePath(String ruleId) {
        if (ruleId == null) {
            return null;
        }
        for (int groupIndex = 0; groupIndex < treeRoot.getChildCount(); groupIndex++) {
            CatalogNode group = (CatalogNode) treeRoot.getChildAt(groupIndex);
            for (int ruleIndex = 0; ruleIndex < group.getChildCount(); ruleIndex++) {
                CatalogNode ruleNode = (CatalogNode) group.getChildAt(ruleIndex);
                if (ruleId.equals(ruleNode.rule().getId())) {
                    return new TreePath(ruleNode.getPath());
                }
            }
        }
        return null;
    }

    private boolean showPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return false;
        }
        TreePath path = ruleTree.getPathForLocation(event.getX(), event.getY());
        JPopupMenu popup = createPopup(path);
        if (popup.getComponentCount() > 0) {
            popup.show(ruleTree, event.getX(), event.getY());
        }
        return true;
    }

    JPopupMenu createPopup(TreePath path) {
        if (path == null || !(path.getLastPathComponent() instanceof CatalogNode node)) {
            return new JPopupMenu();
        }
        if (!ruleTree.isPathSelected(path)) {
            ruleTree.setSelectionPath(path);
        }
        if (node.isGroup()) {
            return createGroupPopup(node.group());
        }
        JPopupMenu popup = new JPopupMenu();
        List<Rule> selected = getSelectedCustomRules();
        if (!selected.isEmpty() && ruleDeleter != null) {
            JMenuItem deleteRules = new JMenuItem(JMeterUtils.getResString(selected.size() == 1
                    ? "correlation_rules_delete" : "correlation_rules_delete_selected"));
            deleteRules.addActionListener(event -> deleteCustomRules(selected));
            popup.add(deleteRules);
        }
        if (!selected.isEmpty() && ruleMover != null) {
            JMenuItem moveRules = new JMenuItem(JMeterUtils.getResString("correlation_rules_move"));
            moveRules.addActionListener(event -> moveSelectedRules());
            popup.add(moveRules);
        }
        return popup;
    }

    private void moveSelectedRules() {
        List<Rule> selected = getSelectedCustomRules();
        if (selected.isEmpty() || ruleMover == null) {
            return;
        }
        List<String> groups = rules.stream().map(Rule::getGroup).distinct().sorted().toList();
        String destination = groupChooser.apply(groups);
        if (destination == null || destination.isBlank()) {
            return;
        }
        String group = destination.trim();
        List<Rule> updated = selected.stream().filter(rule -> !group.equals(rule.getGroup()))
                .map(rule -> rule.withGroup(group)).toList();
        if (updated.isEmpty() || !ruleMover.test(updated)) {
            return;
        }
        Map<String, Rule> replacements = new LinkedHashMap<>();
        updated.forEach(rule -> replacements.put(rule.getId(), rule));
        rules.replaceAll(rule -> replacements.getOrDefault(rule.getId(), rule));
        selectedRule = updated.get(0);
        rebuildTree();
        TreePath[] paths = selected.stream().map(rule -> findRulePath(rule.getId())).toArray(TreePath[]::new);
        ruleTree.setSelectionPaths(paths);
        ruleTree.scrollPathToVisible(paths[0]);
    }

    private List<Rule> getSelectedCustomRules() {
        List<Rule> selected = new ArrayList<>();
        TreePath[] paths = ruleTree.getSelectionPaths();
        if (paths != null) {
            for (TreePath path : paths) {
                if (path.getLastPathComponent() instanceof CatalogNode node && !node.isGroup()
                        && customRuleIds.contains(node.rule().getId())) {
                    selected.add(node.rule());
                }
            }
        }
        return selected;
    }

    JPopupMenu createGroupPopup(String group) {
        JPopupMenu popup = new JPopupMenu();
        List<Rule> groupRules = rules.stream().filter(rule -> group.equals(rule.getGroup())).toList();
        List<Rule> customRules = groupRules.stream().filter(rule -> customRuleIds.contains(rule.getId())).toList();
        if (customRules.isEmpty() || ruleDeleter == null) {
            return popup;
        }
        JMenuItem deleteGroup = new JMenuItem(JMeterUtils.getResString(
                customRules.size() == groupRules.size()
                        ? "correlation_rules_delete_group" : "correlation_rules_delete_group_custom"));
        deleteGroup.addActionListener(event -> deleteCustomRules(customRules, true));
        popup.add(deleteGroup);
        return popup;
    }

    private void deleteCustomRules(List<Rule> selected) {
        deleteCustomRules(selected, selected.size() > 1);
    }

    private void deleteCustomRules(List<Rule> selected, boolean confirm) {
        List<Rule> custom = selected.stream().filter(rule -> customRuleIds.contains(rule.getId())).toList();
        if (ruleDeleter == null || custom.isEmpty()
                || confirm && !deletionConfirmation.test(custom) || !ruleDeleter.test(custom)) {
            return;
        }
        rules.removeAll(custom);
        for (Rule rule : custom) {
            customRuleIds.remove(rule.getId());
            disabledRuleIds.remove(rule.getId());
        }
        selectedRule = null;
        exportButton.setEnabled(ruleTransfer != null && !getCustomRules().isEmpty());
        rebuildTree();
    }

    private List<Rule> selectedRulesIncludingGroups() {
        Set<Rule> selected = new LinkedHashSet<>();
        TreePath[] paths = ruleTree.getSelectionPaths();
        if (paths != null) {
            for (TreePath path : paths) {
                if (path.getLastPathComponent() instanceof CatalogNode node) {
                    if (node.isGroup()) {
                        rules.stream().filter(rule -> node.group().equals(rule.getGroup())).forEach(selected::add);
                    } else {
                        selected.add(node.rule());
                    }
                }
            }
        }
        return List.copyOf(selected);
    }

    private void toggleSelectedRules() {
        List<Rule> selected = selectedRulesIncludingGroups();
        boolean enable = selected.stream().anyMatch(rule -> disabledRuleIds.contains(rule.getId()));
        selected.forEach(rule -> setRuleEnabled(rule.getId(), enable));
        refreshSelectedGroupDetails();
    }

    private void toggleGroupAt(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event) || event.isPopupTrigger() || event.getClickCount() != 1) {
            return;
        }
        TreePath path = ruleTree.getPathForLocation(event.getX(), event.getY());
        if (path == null || !(path.getLastPathComponent() instanceof CatalogNode node)) {
            return;
        }
        java.awt.Rectangle bounds = ruleTree.getPathBounds(path);
        if (bounds != null && event.getX() >= bounds.x && event.getX() < bounds.x + 24) {
            toggleNode(node);
        }
    }

    private void toggleNode(CatalogNode node) {
        if (node.isGroup()) {
            setGroupSelected(node.group(), !isGroupEnabled(node.group()));
        } else {
            setRuleEnabled(node.rule().getId(), disabledRuleIds.contains(node.rule().getId()));
        }
        ruleTree.repaint();
        showNode(node);
    }

    private void refreshSelectedGroupDetails() {
        Object selectedNode = ruleTree.getLastSelectedPathComponent();
        if (selectedNode instanceof CatalogNode node) {
            showNode(node);
        }
    }

    private void showNode(CatalogNode node) {
        exportGroupButton.setEnabled(ruleTransfer != null && !getSelectedGroupCustomRules().isEmpty());
        if (ruleTree.getSelectionCount() > 1) {
            selectedRule = null;
            List<Rule> selected = selectedRulesIncludingGroups();
            setDetails(List.of(new Detail("Selected rules", selected.size()),
                    new Detail("Enabled rules", selected.stream()
                            .filter(rule -> !disabledRuleIds.contains(rule.getId())).count()),
                    new Detail("Custom rules", selected.stream()
                            .filter(rule -> customRuleIds.contains(rule.getId())).count())));
            editButton.setEnabled(false);
        } else if (node == null) {
            showRule(null);
        } else if (node.isGroup()) {
            showGroup(node.group());
        } else {
            showRule(node.rule());
        }
        int selectedCount = getSelectedCustomRules().size();
        deleteButton.setText(JMeterUtils.getResString(selectedCount > 1
                ? "correlation_rules_delete_selected" : "correlation_rules_delete"));
        deleteButton.setEnabled(ruleDeleter != null && selectedCount > 0);
        moveButton.setEnabled(ruleMover != null && selectedCount > 0);
    }

    private void showGroup(String group) {
        selectedRule = null;
        long customCount = rules.stream()
                .filter(rule -> group.equals(rule.getGroup()) && customRuleIds.contains(rule.getId()))
                .count();
        long ruleCount = rules.stream().filter(rule -> group.equals(rule.getGroup())).count();
        setDetails(List.of(
                new Detail("Group", group),
                new Detail("All rules enabled", isGroupEnabled(group)),
                new Detail("Rules", ruleCount),
                new Detail("Enabled rules", rules.stream().filter(rule -> group.equals(rule.getGroup())
                        && !disabledRuleIds.contains(rule.getId())).count()),
                new Detail("Built-in rules", ruleCount - customCount),
                new Detail("Custom rules", customCount)));
        editButton.setEnabled(false);
    }

    private void showRule(Rule rule) {
        selectedRule = rule;
        if (rule == null) {
            setDetails(List.of());
            editButton.setEnabled(false);
            return;
        }
        boolean custom = customRuleIds.contains(rule.getId());
        String origin = custom
                ? JMeterUtils.getResString("try_predefined_correlations_custom_editable")
                : JMeterUtils.getResString("try_predefined_correlations_built_in_fixed");
        setDetails(List.of(
                new Detail("Origin", origin),
                new Detail("Enabled", !disabledRuleIds.contains(rule.getId())),
                new Detail("Group", rule.getGroup()),
                new Detail("ID", rule.getId()),
                new Detail("Name", rule.getName()),
                new Detail("Variable", rule.getVariableName()),
                new Detail("Extractor", rule.getExtractorType()),
                new Detail("Response field", rule.getResponseField()),
                new Detail("Match number", "Detected from later request use"),
                new Detail(JMeterUtils.getResString("correlation_rule_max_matches"), rule.getMaxMatches()),
                new Detail(JMeterUtils.getResString("correlation_rule_min_length"), rule.getMinValueLength()),
                new Detail("Expression", rule.getExpression()),
                new Detail("Template", rule.getTemplate()),
                new Detail("Replacement scope", rule.getReplacementFilter().scopes()),
                new Detail("Match whole value only", rule.getReplacementFilter().wholeValue()),
                new Detail("Replacement field pattern", rule.getReplacementFilter().fieldPattern()),
                new Detail("Replacement filter diagnostic", rule.getReplacementFilter().diagnostic()),
                new Detail("Default value", rule.getDefaultValue()),
                new Detail("Use empty default", rule.isEmptyDefaultValue()),
                new Detail("Fail when no match", rule.isFailOnNoMatch())));
        editButton.setEnabled(custom && ruleUpdater != null);
    }

    private void setDetails(List<Detail> details) {
        detailsModel.setRowCount(0);
        for (Detail detail : details) {
            detailsModel.addRow(new Object[] {detail.name(), detail.value()});
        }
    }

    private void showCustomRuleInfo(ActionEvent event) {
        JOptionPane.showMessageDialog(
                this,
                JMeterUtils.getResString("try_predefined_correlations_info_message"),
                JMeterUtils.getResString("try_predefined_correlations_info_title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void editSelectedRule(ActionEvent event) {
        if (selectedRule == null || ruleUpdater == null || !customRuleIds.contains(selectedRule.getId())) {
            return;
        }
        Rule original = selectedRule;
        Rule updated = ruleUpdater.update(original);
        if (updated == null) {
            return;
        }
        int index = rules.indexOf(original);
        rules.set(index, updated);
        selectedRule = updated;
        rebuildTree();
    }

    private record Detail(String name, Object value) {
    }

    private record CatalogValue(String group, Rule rule) {
    }

    private static final class CatalogNode extends DefaultMutableTreeNode {

        private static final long serialVersionUID = 1L;

        private CatalogNode(CatalogValue value) {
            super(value);
        }

        static CatalogNode group(String group) {
            return new CatalogNode(new CatalogValue(group, null));
        }

        static CatalogNode rule(Rule rule) {
            return new CatalogNode(new CatalogValue(rule.getGroup(), rule));
        }

        String group() {
            return ((CatalogValue) getUserObject()).group();
        }

        Rule rule() {
            return ((CatalogValue) getUserObject()).rule();
        }

        boolean isGroup() {
            return rule() == null;
        }
    }

    private final class CatalogTreeRenderer extends DefaultTreeCellRenderer {

        private static final long serialVersionUID = 1L;
        private final JCheckBox groupRenderer = new JCheckBox();

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (!(value instanceof CatalogNode node)) {
                return super.getTreeCellRendererComponent(
                        tree, value, selected, expanded, leaf, row, hasFocus);
            }
            groupRenderer.setText(node.isGroup() ? node.group() : node.rule().getName());
            groupRenderer.setSelected(node.isGroup() ? isGroupEnabled(node.group())
                    : !disabledRuleIds.contains(node.rule().getId()));
            groupRenderer.setOpaque(true);
            groupRenderer.setForeground(selected
                    ? UIManager.getColor("Tree.selectionForeground")
                    : UIManager.getColor("Tree.textForeground"));
            groupRenderer.setBackground(selected
                    ? UIManager.getColor("Tree.selectionBackground")
                    : UIManager.getColor("Tree.textBackground"));
            groupRenderer.setBorder(hasFocus
                    ? UIManager.getBorder("Tree.focusCellHighlightBorder")
                    : BorderFactory.createEmptyBorder(1, 1, 1, 1));
            return groupRenderer;
        }
    }

    /** Multi-selection with All exclusive of specific request scopes. */
    static final class ScopeSelector extends JPanel {
        private static final long serialVersionUID = 1L;
        private final Map<CorrelationReplacementFilter.Scope, JCheckBox> choices = new LinkedHashMap<>();

        ScopeSelector() {
            super(new GridLayout(0, 3));
            for (var scope : CorrelationReplacementFilter.Scope.values()) {
                JCheckBox choice = new JCheckBox(scope.toString());
                choices.put(scope, choice);
                add(choice);
                choice.addActionListener(event -> {
                    if (choice.isSelected()) {
                        if (scope == CorrelationReplacementFilter.Scope.ALL) {
                            setScopes(Set.of(scope));
                        } else {
                            choices.get(CorrelationReplacementFilter.Scope.ALL).setSelected(false);
                        }
                    }
                    if (selectedScopes().isEmpty()) {
                        setScopes(Set.of(CorrelationReplacementFilter.Scope.ALL));
                    }
                });
            }
            setScopes(Set.of(CorrelationReplacementFilter.Scope.ALL));
        }

        void setScopes(Set<CorrelationReplacementFilter.Scope> scopes) {
            choices.forEach((scope, choice) -> choice.setSelected(scopes.contains(scope)));
        }

        Set<CorrelationReplacementFilter.Scope> selectedScopes() {
            Set<CorrelationReplacementFilter.Scope> scopes = new LinkedHashSet<>();
            choices.forEach((scope, choice) -> {
                if (choice.isSelected()) {
                    scopes.add(scope);
                }
            });
            return scopes;
        }
    }

    /** Editor used by the action for custom (never built-in) correlation rules. */
    static final class RuleEditorPanel extends JPanel {

        private static final long serialVersionUID = 1L;

        private final JTextField group = new JTextField(30);
        private final JTextField name = new JTextField(30);
        private final JTextField variableName = new JTextField(30);
        private final JComboBox<ExtractorType> extractorType = new JComboBox<>(ExtractorType.values());
        private final JComboBox<ResponseField> responseField = new JComboBox<>(ResponseField.values());
        private final JTextField expression = new JTextField(40);
        private final JTextField template = new JTextField(20);
        private final JSpinner maxMatches = new JSpinner(new SpinnerNumberModel(-1, -1, 100_000, 1) {
            @Override
            public Object getNextValue() {
                return getNumber().intValue() == -1 ? 1 : super.getNextValue();
            }

            @Override
            public Object getPreviousValue() {
                return getNumber().intValue() == 1 ? -1 : super.getPreviousValue();
            }
        });
        private final JSpinner minValueLength = new JSpinner(new SpinnerNumberModel(6, 1, Integer.MAX_VALUE, 1));
        private final ScopeSelector replacementScope = new ScopeSelector();
        private final JCheckBox replacementWholeValue = new JCheckBox();
        private final JTextField replacementFieldPattern = new JTextField(40);
        private final JTextField defaultValue = new JTextField(20);
        private final JCheckBox emptyDefaultValue = new JCheckBox();
        private final JCheckBox failOnNoMatch = new JCheckBox();

        RuleEditorPanel(Rule rule) {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            group.setText(rule.getGroup());
            name.setText(rule.getName());
            variableName.setText(rule.getVariableName());
            extractorType.setSelectedItem(rule.getExtractorType());
            responseField.setSelectedItem(rule.getResponseField());
            expression.setText(rule.getExpression());
            template.setText(rule.getTemplate());
            maxMatches.setValue(rule.getMaxMatches());
            minValueLength.setValue(rule.getMinValueLength());
            replacementScope.setScopes(rule.getReplacementFilter().scopes());
            replacementWholeValue.setSelected(rule.getReplacementFilter().wholeValue());
            replacementFieldPattern.setText(rule.getReplacementFilter().fieldPattern());
            defaultValue.setText(rule.getDefaultValue());
            emptyDefaultValue.setSelected(rule.isEmptyDefaultValue());
            failOnNoMatch.setSelected(rule.isFailOnNoMatch());

            int row = 0;
            addRow("Group", group, row++);
            addRow("Name", name, row++);
            addRow("Variable name", variableName, row++);
            addRow("Extractor type", extractorType, row++);
            addRow("Response field", responseField, row++);
            addRow(rule.getExtractorType() == ExtractorType.BOUNDARY
                    ? JMeterUtils.getResString("correlation_rule_left_boundary") : "Expression", expression, row++);
            addRow(switch (rule.getExtractorType()) {
            case BOUNDARY -> JMeterUtils.getResString("correlation_rule_right_boundary");
            case CSS -> "Attribute (empty for text)";
            default -> "Template";
            }, template, row++);
            maxMatches.setToolTipText(JMeterUtils.getResString("correlation_rule_max_matches_tooltip"));
            addRow(JMeterUtils.getResString("correlation_rule_max_matches_label"), maxMatches, row++);
            minValueLength.setToolTipText(JMeterUtils.getResString("correlation_rule_min_length_tooltip"));
            addRow(JMeterUtils.getResString("correlation_rule_min_length"), minValueLength, row++);
            addRow("Replacement scope", replacementScope, row++);
            replacementWholeValue.setToolTipText(
                    "Matches the full parameter or header value, a path segment, or a token within the body.");
            addRow("Match whole value only", replacementWholeValue, row++);
            addRow("", new JLabel("<html>Matches the full parameter or header value,<br>"
                    + "a path segment, or a token within the body.</html>"), row++);
            replacementFieldPattern.setToolTipText(
                    "Optional Java regex: replace only inside matching regions. Parameters use name=value; headers use name: value.");
            addRow("Replacement field pattern", replacementFieldPattern, row++);
            addRow("Default value", defaultValue, row++);
            addRow("Use empty default", emptyDefaultValue, row++);
            addRow("Fail when no match", failOnNoMatch, row);
        }

        Rule updatedRule(Rule original) {
            return new Rule(
                    original.getId(), group.getText().trim(), name.getText().trim(),
                    variableName.getText().trim(), (ExtractorType) extractorType.getSelectedItem(),
                    (ResponseField) responseField.getSelectedItem(), expression.getText(), template.getText(),
                    (Integer) maxMatches.getValue(), (Integer) minValueLength.getValue(), defaultValue.getText(),
                    emptyDefaultValue.isSelected(), original.isComputeConcatenation(), failOnNoMatch.isSelected(),
                    original.getExtractorSettings()).withReplacementFilter(new CorrelationReplacementFilter(
                            replacementScope.selectedScopes(),
                            replacementWholeValue.isSelected(), replacementFieldPattern.getText()));
        }

        private void addRow(String label, Component component, int row) {
            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = row;
            labelConstraints.anchor = GridBagConstraints.LINE_START;
            labelConstraints.insets = new Insets(3, 3, 3, 8);
            add(new JLabel(label), labelConstraints);

            GridBagConstraints fieldConstraints = new GridBagConstraints();
            fieldConstraints.gridx = 1;
            fieldConstraints.gridy = row;
            fieldConstraints.weightx = 1;
            fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
            fieldConstraints.insets = new Insets(3, 3, 3, 3);
            add(component, fieldConstraints);
        }
    }
}
