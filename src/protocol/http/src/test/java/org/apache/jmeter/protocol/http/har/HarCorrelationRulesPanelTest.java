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

import java.util.List;
import java.util.Set;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.junit.jupiter.api.Test;

class HarCorrelationRulesPanelTest extends JMeterTestCase {

    @Test
    void selectsAllGroupsByDefaultAndCanExcludeAGroup() {
        Rule oauth = rule("oauth", "OAuth", 1);
        Rule aspNet = rule("viewstate", "ASP.NET", 1);
        HarCorrelationRulesPanel panel = new HarCorrelationRulesPanel(
                List.of(oauth, aspNet), Set.of("viewstate"), null);

        assertEquals(List.of("OAuth > oauth", "ASP.NET > viewstate"), panel.getRulePaths());
        assertTrue(panel.areAllGroupsCollapsed());
        assertEquals(List.of(oauth, aspNet), panel.getSelectedRules());

        panel.setGroupSelected("OAuth", false);

        assertEquals(List.of(aspNet), panel.getSelectedRules());
    }

    @Test
    void customEditorPreservesMaximumMatchSetting() {
        Rule rule = rule("custom", "Custom", 100);

        Rule updated = new HarCorrelationRulesPanel.RuleEditorPanel(rule).updatedRule(rule);

        assertEquals(100, updated.getMaxMatches());
    }

    private static Rule rule(String id, String group, int maxMatches) {
        return new Rule(
                id, group, id, id + "_value", ExtractorType.REGEX, ResponseField.BODY,
                "value=([^&]+)", "$1$", maxMatches,
                "", false, false, true);
    }
}
