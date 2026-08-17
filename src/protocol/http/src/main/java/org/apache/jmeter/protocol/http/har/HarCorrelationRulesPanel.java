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
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.util.JMeterUtils;

/** Lets users browse correlation rules and choose which groups should be tried. */
final class HarCorrelationRulesPanel extends JPanel {

    interface RuleUpdater {
        Rule update(Rule rule);
    }

    private static final long serialVersionUID = 1L;

    private final List<Rule> rules;
    private final Set<String> customRuleIds;
    private final RuleUpdater ruleUpdater;
    private final Map<String, Boolean> selectedGroups = new LinkedHashMap<>();
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
    private Rule selectedRule;

    HarCorrelationRulesPanel(List<Rule> rules, Set<String> customRuleIds, RuleUpdater ruleUpdater) {
        super(new BorderLayout(10, 10));
        this.rules = new ArrayList<>(rules);
        this.customRuleIds = customRuleIds;
        this.ruleUpdater = ruleUpdater;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(new JLabel(JMeterUtils.getResString("try_predefined_correlations_rules_prompt")),
                BorderLayout.NORTH);
        add(createBrowser(), BorderLayout.CENTER);
    }

    List<Rule> getSelectedRules() {
        List<Rule> selected = new ArrayList<>();
        for (Rule rule : rules) {
            if (selectedGroups.getOrDefault(rule.getGroup(), false)) {
                selected.add(rule);
            }
        }
        return List.copyOf(selected);
    }

    void setGroupSelected(String group, boolean selected) {
        if (selectedGroups.containsKey(group)) {
            selectedGroups.put(group, selected);
            ruleTree.repaint();
            refreshSelectedGroupDetails();
        }
    }

    List<String> getRulePaths() {
        return rules.stream().map(rule -> rule.getGroup() + " > " + rule.getName()).toList();
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
        JPanel groupButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        JButton selectAll = new JButton(
                JMeterUtils.getResString("try_predefined_correlations_select_all"));
        selectAll.addActionListener(event -> setAllGroupsSelected(true));
        JButton selectNone = new JButton(
                JMeterUtils.getResString("try_predefined_correlations_select_none"));
        selectNone.addActionListener(event -> setAllGroupsSelected(false));
        groupButtons.add(selectAll);
        groupButtons.add(selectNone);
        treePanel.add(groupButtons, BorderLayout.NORTH);
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
        detailPanel.add(editButton, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, detailPanel);
        split.setBorder(null);
        split.setResizeWeight(0.38);
        split.setDividerLocation(320);
        return split;
    }

    private void setAllGroupsSelected(boolean selected) {
        selectedGroups.replaceAll((group, previous) -> selected);
        ruleTree.repaint();
        refreshSelectedGroupDetails();
    }

    private void configureTree() {
        ruleTree.setRootVisible(false);
        ruleTree.setShowsRootHandles(true);
        ruleTree.setCellRenderer(new CatalogTreeRenderer());
        ruleTree.addTreeSelectionListener(event -> showNode(
                (CatalogNode) ruleTree.getLastSelectedPathComponent()));
        ruleTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                toggleGroupAt(event);
            }
        });
        rebuildTree();
    }

    private void rebuildTree() {
        String selectedRuleId = selectedRule == null ? null : selectedRule.getId();
        Map<String, Boolean> previousSelection = new LinkedHashMap<>(selectedGroups);
        selectedGroups.clear();
        treeRoot.removeAllChildren();
        Map<String, List<Rule>> rulesByGroup = new LinkedHashMap<>();
        for (Rule rule : rules) {
            rulesByGroup.computeIfAbsent(rule.getGroup(), ignored -> new ArrayList<>()).add(rule);
        }
        for (Map.Entry<String, List<Rule>> group : rulesByGroup.entrySet()) {
            selectedGroups.put(group.getKey(), previousSelection.getOrDefault(group.getKey(), true));
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
            showRule(null);
        }
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

    private void toggleGroupAt(MouseEvent event) {
        if (event.getClickCount() != 1) {
            return;
        }
        TreePath path = ruleTree.getPathForLocation(event.getX(), event.getY());
        if (path == null || !(path.getLastPathComponent() instanceof CatalogNode node) || !node.isGroup()) {
            return;
        }
        java.awt.Rectangle bounds = ruleTree.getPathBounds(path);
        if (bounds != null && event.getX() >= bounds.x) {
            selectedGroups.computeIfPresent(node.group(), (group, selected) -> !selected);
            ruleTree.repaint(bounds);
            showGroup(node.group());
        }
    }

    private void refreshSelectedGroupDetails() {
        Object selectedNode = ruleTree.getLastSelectedPathComponent();
        if (selectedNode instanceof CatalogNode node && node.isGroup()) {
            showGroup(node.group());
        }
    }

    private void showNode(CatalogNode node) {
        if (node == null) {
            showRule(null);
        } else if (node.isGroup()) {
            showGroup(node.group());
        } else {
            showRule(node.rule());
        }
    }

    private void showGroup(String group) {
        selectedRule = null;
        long customCount = rules.stream()
                .filter(rule -> group.equals(rule.getGroup()) && customRuleIds.contains(rule.getId()))
                .count();
        long ruleCount = rules.stream().filter(rule -> group.equals(rule.getGroup())).count();
        setDetails(List.of(
                new Detail("Group", group),
                new Detail("Selected for matching", selectedGroups.getOrDefault(group, false)),
                new Detail("Rules", ruleCount),
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
                new Detail("Group", rule.getGroup()),
                new Detail("ID", rule.getId()),
                new Detail("Name", rule.getName()),
                new Detail("Variable", rule.getVariableName()),
                new Detail("Extractor", rule.getExtractorType()),
                new Detail("Response field", rule.getResponseField()),
                new Detail("Match number", "Detected from later request use"),
                new Detail("Maximum matches", rule.getMaxMatches()),
                new Detail("Expression", rule.getExpression()),
                new Detail("Template", rule.getTemplate()),
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
            if (!node.isGroup()) {
                Component component = super.getTreeCellRendererComponent(
                        tree, value, selected, expanded, leaf, row, hasFocus);
                setText(node.rule().getName());
                return component;
            }
            groupRenderer.setText(node.group());
            groupRenderer.setSelected(selectedGroups.getOrDefault(node.group(), false));
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
        private final JSpinner maxMatches = new JSpinner(new SpinnerNumberModel(1, 1, 100_000, 1));
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
            defaultValue.setText(rule.getDefaultValue());
            emptyDefaultValue.setSelected(rule.isEmptyDefaultValue());
            failOnNoMatch.setSelected(rule.isFailOnNoMatch());

            int row = 0;
            addRow("Group", group, row++);
            addRow("Name", name, row++);
            addRow("Variable name", variableName, row++);
            addRow("Extractor type", extractorType, row++);
            addRow("Response field", responseField, row++);
            addRow("Expression", expression, row++);
            addRow("Template", template, row++);
            addRow("Maximum matches", maxMatches, row++);
            addRow("Default value", defaultValue, row++);
            addRow("Use empty default", emptyDefaultValue, row++);
            addRow("Fail when no match", failOnNoMatch, row);
        }

        Rule updatedRule(Rule original) {
            return new Rule(
                    original.getId(), group.getText().trim(), name.getText().trim(),
                    variableName.getText().trim(), (ExtractorType) extractorType.getSelectedItem(),
                    (ResponseField) responseField.getSelectedItem(), expression.getText(), template.getText(),
                    (Integer) maxMatches.getValue(), defaultValue.getText(),
                    emptyDefaultValue.isSelected(), original.isComputeConcatenation(), failOnNoMatch.isSelected());
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
