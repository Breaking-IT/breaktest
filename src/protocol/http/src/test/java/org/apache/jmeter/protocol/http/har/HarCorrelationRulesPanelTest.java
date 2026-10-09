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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.JMenuItem;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.junit.jupiter.api.Test;

class HarCorrelationRulesPanelTest extends JMeterTestCase {

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
        panel.configureManagement(Set.of("second", "other"), deleted::add);

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
        panel.configureManagement(Set.of(), deleted::add);
        panel.setCustomOnly(true);

        ((JMenuItem) panel.createGroupPopup("Mixed").getComponent(0)).doClick();

        assertEquals(List.of(custom), deleted);
        panel.setCustomOnly(false);
        assertEquals(List.of("Mixed > built-in"), panel.getRulePaths());
        assertEquals(0, panel.createGroupPopup("Mixed").getComponentCount());
    }

    @Test
    void groupDeletionStopsOnPersistenceFailureAndKeepsRemainingRules() {
        Rule first = rule("first", "Custom", 1);
        Rule second = rule("second", "Custom", 1);
        Rule third = rule("third", "Custom", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(first, second, third), Set.of("first", "second", "third"), null, null);
        assertEquals(0, panel.createGroupPopup("Custom").getComponentCount());
        List<Rule> attempted = new ArrayList<>();
        panel.configureManagement(Set.of("second"), rule -> {
            attempted.add(rule);
            return rule == first;
        });

        ((JMenuItem) panel.createGroupPopup("Custom").getComponent(0)).doClick();

        assertEquals(List.of(first, second), attempted);
        assertEquals(List.of(second, third), panel.getCustomRules());
        assertEquals(Set.of("second"), panel.getDisabledRuleIds());
    }

    private static Rule rule(String id, String group, int maxMatches) {
        return new Rule(
                id, group, id, id + "_value", ExtractorType.REGEX, ResponseField.BODY,
                "value=([^&]+)", "$1$", maxMatches,
                "", false, false, true);
    }
}
