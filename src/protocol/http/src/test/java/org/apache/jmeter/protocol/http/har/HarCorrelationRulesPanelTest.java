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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HarCorrelationRulesPanelTest extends JMeterTestCase {

    @Test
    void scopeSelectorCombinesValuesAndAllOverridesThem() {
        var selector = new HarCorrelationRulesPanel.ScopeSelector();
        var boxes = descendants(selector).stream().filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).toList();
        JCheckBox all = boxes.stream().filter(box -> box.getText().equals("All")).findFirst().orElseThrow();
        JCheckBox headers = boxes.stream().filter(box -> box.getText().equals("Headers")).findFirst().orElseThrow();
        JCheckBox params = boxes.stream().filter(box -> box.getText().equals("Parameters")).findFirst().orElseThrow();
        assertTrue(all.isSelected());
        headers.doClick();
        params.doClick();
        assertEquals(Set.of(CorrelationReplacementFilter.Scope.HEADERS, CorrelationReplacementFilter.Scope.PARAMETERS),
                selector.selectedScopes());
        assertFalse(all.isSelected());
        all.doClick();
        assertEquals(Set.of(CorrelationReplacementFilter.Scope.ALL), selector.selectedScopes());
        assertFalse(headers.isSelected());
        assertFalse(params.isSelected());
        headers.doClick();
        headers.doClick();
        assertEquals(Set.of(CorrelationReplacementFilter.Scope.ALL), selector.selectedScopes());
    }

    @Test
    void enablesIndividualRulesAndPreservesStateWhenFiltering() {
        Rule first = rule("first", "OAuth", 1);
        Rule second = rule("second", "OAuth", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second), Set.of("second"), null, null);
        panel.configureManagement(Set.of("first", "unknown-rule"), null);
        assertEquals(List.of(second), panel.getSelectedRules());
        panel.setCustomOnly(true);
        assertEquals(Set.of("first", "unknown-rule"), panel.getDisabledRuleIds());
        panel.setGroupSelected("OAuth", true);
        assertEquals(List.of(first, second), panel.getSelectedRules());
        panel.setRuleEnabled("second", false);
        assertEquals(List.of(first), panel.getSelectedRules());
        assertEquals(Set.of("second", "unknown-rule"), panel.getDisabledRuleIds());
    }

    @Test
    void selectsAllGroupsByDefaultAndCanExcludeAGroup() {
        Rule oauth = rule("oauth", "OAuth", 1);
        Rule aspNet = rule("viewstate", "ASP.NET", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(oauth, aspNet), Set.of("viewstate"), null, null);

        assertEquals(List.of("OAuth > oauth", "ASP.NET > viewstate"), panel.getRulePaths());
        assertTrue(panel.areAllGroupsCollapsed());
        assertEquals(List.of(oauth, aspNet), panel.getSelectedRules());

        panel.setGroupSelected("OAuth", false);

        assertEquals(List.of(aspNet), panel.getSelectedRules());
    }

    @Test
    void customOnlyFiltersTheTreeWithoutChangingGroupSelections() {
        Rule oauth = rule("oauth", "OAuth", 1);
        Rule custom = rule("custom-oauth", "OAuth", 1);
        Rule aspNet = rule("viewstate", "ASP.NET", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(oauth, custom, aspNet), Set.of("custom-oauth"), null, null);

        panel.setCustomOnly(true);

        assertEquals(List.of("OAuth > custom-oauth"), panel.getRulePaths());
        assertEquals(List.of(custom), panel.getCustomRules());
        assertEquals(List.of(oauth, custom, aspNet), panel.getSelectedRules());
    }

    @Test
    void customEditorPreservesMaximumMatchSetting() {
        Rule rule = rule("custom", "Custom", 100);

        Rule updated = new HarCorrelationRulesPanel.RuleEditorPanel(rule).updatedRule(rule);

        assertEquals(100, updated.getMaxMatches());
    }

    @Test
    void customEditorAcceptsUnlimitedMatches() {
        Rule rule = rule("custom", "Custom", -1);
        Rule updated = new HarCorrelationRulesPanel.RuleEditorPanel(rule).updatedRule(rule);
        assertEquals(-1, updated.getMaxMatches());
    }

    @Test
    void customEditorPreservesMinimumValueLength() {
        Rule rule = new Rule("short", "Custom", "Short", "shortValue", ExtractorType.REGEX,
                ResponseField.BODY, "value=([^;]+)", "$1$", -1, 1, "", false, false, true);
        Rule updated = new HarCorrelationRulesPanel.RuleEditorPanel(rule).updatedRule(rule);
        assertEquals(1, updated.getMinValueLength());
    }

    @Test
    void groupPopupDeletesAllCustomRulesIncludingDisabledRules() {
        Rule first = rule("first", "Custom", 1);
        Rule second = rule("second", "Custom", 1);
        Rule other = rule("other", "Other", 1);
        List<Rule> deleted = new ArrayList<>();
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second, other), Set.of("first", "second", "other"), null, null);
        panel.configureManagement(Set.of("second", "other"), deleted::addAll, selected -> true);

        ((JMenuItem) panel.createGroupPopup("Custom").getComponent(0)).doClick();

        assertEquals(List.of(first, second), deleted);
        assertEquals(List.of("Other > other"), panel.getRulePaths());
        assertEquals(List.of(other), panel.getCustomRules());
        assertEquals(Set.of("other"), panel.getDisabledRuleIds());
    }

    @Test
    void groupPopupPreservesBuiltInRulesEvenWhenTheyAreHidden() {
        Rule builtIn = rule("built-in", "Mixed", 1);
        Rule custom = rule("custom", "Mixed", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(builtIn, custom), Set.of("custom"), null, null);
        List<Rule> deleted = new ArrayList<>();
        panel.configureManagement(Set.of(), deleted::addAll, selected -> true);
        panel.setCustomOnly(true);

        ((JMenuItem) panel.createGroupPopup("Mixed").getComponent(0)).doClick();

        assertEquals(List.of(custom), deleted);
        panel.setCustomOnly(false);
        assertEquals(List.of("Mixed > built-in"), panel.getRulePaths());
        assertEquals(0, panel.createGroupPopup("Mixed").getComponentCount());
    }

    @Test
    void groupDeletionKeepsAllRulesOnPersistenceFailure() {
        Rule first = rule("first", "Custom", 1);
        Rule second = rule("second", "Custom", 1);
        Rule third = rule("third", "Custom", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second, third), Set.of("first", "second", "third"), null, null);
        assertEquals(0, panel.createGroupPopup("Custom").getComponentCount());
        List<Rule> attempted = new ArrayList<>();
        panel.configureManagement(Set.of("second"), selected -> {
            attempted.addAll(selected);
            return false;
        }, selected -> true);

        ((JMenuItem) panel.createGroupPopup("Custom").getComponent(0)).doClick();

        assertEquals(List.of(first, second, third), attempted);
        assertEquals(List.of(first, second, third), panel.getCustomRules());
        assertEquals(Set.of("second"), panel.getDisabledRuleIds());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletesOnlySelectedCustomRulesFromPopupOrButton(boolean useButton) {
        Rule first = rule("first", "Custom", 1);
        Rule unselected = rule("unselected", "Custom", 1);
        Rule second = rule("second", "Custom", 1);
        Rule builtIn = rule("built-in", "Custom", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, unselected, second, builtIn), Set.of("first", "unselected", "second"), null, null);
        List<Rule> deleted = new ArrayList<>();
        panel.configureManagement(Set.of("second"), deleted::addAll, selected -> true);
        JTree tree = descendants(panel).stream().filter(JTree.class::isInstance).map(JTree.class::cast)
                .findFirst().orElseThrow();
        DefaultMutableTreeNode group = (DefaultMutableTreeNode) tree.getModel().getChild(tree.getModel().getRoot(), 0);
        TreePath firstPath = new TreePath(((DefaultMutableTreeNode) group.getChildAt(0)).getPath());
        TreePath secondPath = new TreePath(((DefaultMutableTreeNode) group.getChildAt(2)).getPath());
        TreePath builtInPath = new TreePath(((DefaultMutableTreeNode) group.getChildAt(3)).getPath());
        tree.setSelectionPaths(new TreePath[] {firstPath, secondPath, builtInPath, new TreePath(group.getPath())});

        if (useButton) {
            JButton delete = descendants(panel).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                    .filter(button -> JMeterUtils.getResString("correlation_rules_delete_selected").equals(button.getText()))
                    .findFirst().orElseThrow();
            assertTrue(delete.isEnabled());
            delete.doClick();
        } else {
            var popup = panel.createPopup(secondPath);
            assertEquals(4, tree.getSelectionCount(), "Right-click must preserve the selected rules");
            ((JMenuItem) popup.getComponent(0)).doClick();
        }

        assertEquals(List.of(first, second), deleted);
        assertEquals(List.of("Custom > unselected", "Custom > built-in"), panel.getRulePaths());
        assertEquals(Set.of(), panel.getDisabledRuleIds());
    }

    @Test
    void rightClickOnUnselectedRuleReplacesPreviousSelection() {
        Rule first = rule("first", "Custom", 1);
        Rule second = rule("second", "Custom", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second), Set.of("first", "second"), null, null);
        List<Rule> deleted = new ArrayList<>();
        panel.configureManagement(Set.of(), deleted::addAll, selected -> true);
        JTree tree = descendants(panel).stream().filter(JTree.class::isInstance).map(JTree.class::cast)
                .findFirst().orElseThrow();
        DefaultMutableTreeNode group = (DefaultMutableTreeNode) tree.getModel().getChild(tree.getModel().getRoot(), 0);
        TreePath firstPath = new TreePath(((DefaultMutableTreeNode) group.getChildAt(0)).getPath());
        TreePath secondPath = new TreePath(((DefaultMutableTreeNode) group.getChildAt(1)).getPath());
        tree.setSelectionPath(firstPath);

        var popup = panel.createPopup(secondPath);

        assertEquals(List.of(secondPath), List.of(tree.getSelectionPaths()));
        ((JMenuItem) popup.getComponent(0)).doClick();
        assertEquals(List.of(second), deleted);
        assertEquals(List.of(first), panel.getCustomRules());
    }

    @Test
    void cancelledGroupDeletionDoesNotWriteOrChangeSelection() {
        Rule first = rule("first", "Custom", 1);
        Rule second = rule("second", "Custom", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second), Set.of("first", "second"), null, null);
        List<List<Rule>> confirmations = new ArrayList<>();
        panel.configureManagement(Set.of("second"), selected -> {
            throw new AssertionError("Cancelled deletion must not persist anything");
        }, selected -> {
            confirmations.add(selected);
            return false;
        });
        ((JMenuItem) panel.createGroupPopup("Custom").getComponent(0)).doClick();
        assertEquals(List.of(List.of(first, second)), confirmations);
        assertEquals(List.of(first, second), panel.getCustomRules());
        assertEquals(Set.of("second"), panel.getDisabledRuleIds());
    }

    @Test
    void multipleSelectionSummarizesRulesDisablesEditAndTogglesTogether() {
        Rule first = rule("first", "Custom", 1);
        Rule second = rule("second", "Custom", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second), Set.of("first", "second"), rule -> rule, null);
        panel.configureManagement(Set.of("second"), null);
        JTree tree = descendants(panel).stream().filter(JTree.class::isInstance).map(JTree.class::cast)
                .findFirst().orElseThrow();
        DefaultMutableTreeNode group = (DefaultMutableTreeNode) tree.getModel().getChild(tree.getModel().getRoot(), 0);
        tree.setSelectionPaths(new TreePath[] {
                new TreePath(((DefaultMutableTreeNode) group.getChildAt(0)).getPath()),
                new TreePath(((DefaultMutableTreeNode) group.getChildAt(1)).getPath())});
        JButton edit = descendants(panel).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> JMeterUtils.getResString("try_predefined_correlations_edit_custom").equals(button.getText()))
                .findFirst().orElseThrow();
        assertFalse(edit.isEnabled());
        JTable details = descendants(panel).stream().filter(JTable.class::isInstance)
                .map(JTable.class::cast).findFirst().orElseThrow();
        assertEquals("Selected rules", details.getValueAt(0, 0));
        assertEquals(2, details.getValueAt(0, 1));
        tree.getActionMap().get("toggleRule").actionPerformed(null);
        assertEquals(Set.of(), panel.getDisabledRuleIds());
        tree.getActionMap().get("toggleRule").actionPerformed(null);
        assertEquals(Set.of("first", "second"), panel.getDisabledRuleIds());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void movesSelectedCustomRulesToAnotherGroupThroughMenuOrButton(boolean useButton) {
        Rule first = rule("first", "Source", 1);
        Rule second = rule("second", "Source", 2);
        Rule builtIn = rule("built-in", "Source", 1);
        Rule other = rule("other", "Destination", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second, builtIn, other), Set.of("first", "second", "other"), null, null);
        panel.configureManagement(Set.of("second"), null);
        List<List<Rule>> saved = new ArrayList<>();
        panel.configureMoving(saved::add, groups -> {
            assertEquals(List.of("Destination", "Source"), groups);
            return " Destination ";
        });
        JTree tree = descendants(panel).stream().filter(JTree.class::isInstance).map(JTree.class::cast)
                .findFirst().orElseThrow();
        DefaultMutableTreeNode source = (DefaultMutableTreeNode) tree.getModel().getChild(tree.getModel().getRoot(), 0);
        TreePath firstPath = new TreePath(((DefaultMutableTreeNode) source.getChildAt(0)).getPath());
        tree.setSelectionPaths(new TreePath[] {firstPath,
                new TreePath(((DefaultMutableTreeNode) source.getChildAt(1)).getPath()),
                new TreePath(((DefaultMutableTreeNode) source.getChildAt(2)).getPath())});
        if (useButton) {
            JButton move = descendants(panel).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                    .filter(button -> JMeterUtils.getResString("correlation_rules_move").equals(button.getText()))
                    .findFirst().orElseThrow();
            assertTrue(move.isEnabled());
            move.doClick();
        } else {
            ((JMenuItem) panel.createPopup(firstPath).getComponent(0)).doClick();
        }
        assertEquals(1, saved.size());
        assertEquals(List.of("first", "second"), saved.get(0).stream().map(Rule::getId).toList());
        assertEquals(List.of("Destination > first", "Destination > second", "Source > built-in", "Destination > other"),
                panel.getRulePaths());
        assertEquals(Set.of("second"), panel.getDisabledRuleIds());
        assertEquals(2, tree.getSelectionCount());
        assertEquals(2, panel.getCustomRules().get(1).getMaxMatches());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "blank", "same", "failure", "new"})
    void movingOneRuleHandlesCancelNoOpFailureAndNewGroup(String outcome) {
        Rule rule = rule("one", "Source", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(List.of(rule), Set.of("one"), null, null);
        List<List<Rule>> saved = new ArrayList<>();
        panel.configureMoving(updated -> {
            saved.add(updated);
            return !"failure".equals(outcome);
        }, groups -> switch (outcome) {
            case "cancel" -> null;
            case "blank" -> " ";
            case "same" -> "Source";
            default -> "New group";
        });
        JTree tree = descendants(panel).stream().filter(JTree.class::isInstance).map(JTree.class::cast)
                .findFirst().orElseThrow();
        DefaultMutableTreeNode source = (DefaultMutableTreeNode) tree.getModel().getChild(tree.getModel().getRoot(), 0);
        TreePath path = new TreePath(((DefaultMutableTreeNode) source.getChildAt(0)).getPath());
        ((JMenuItem) panel.createPopup(path).getComponent(0)).doClick();
        assertEquals("new".equals(outcome) || "failure".equals(outcome) ? 1 : 0, saved.size());
        assertEquals(List.of("new".equals(outcome) ? "New group > one" : "Source > one"), panel.getRulePaths());
    }

    private static List<Component> descendants(Container container) {
        List<Component> result = new ArrayList<>();
        for (Component component : container.getComponents()) {
            result.add(component);
            if (component instanceof Container child) {
                result.addAll(descendants(child));
            }
        }
        return result;
    }

    private static Rule rule(String id, String group, int maxMatches) {
        return new Rule(
                id, group, id, id + "_value", ExtractorType.REGEX, ResponseField.BODY,
                "value=([^&]+)", "$1$", maxMatches,
                "", false, false, true);
    }
}
