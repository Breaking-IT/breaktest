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

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.JOptionPane;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractActionWithNoRunningTest;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.testelement.AbstractScopedTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.StringUtilities;

import com.google.auto.service.AutoService;

/** Saves a supported extractor as an installation-level and plan-local predefined correlation rule. */
@AutoService(Command.class)
public final class SaveExtractorAsPredefinedCorrelationAction extends AbstractActionWithNoRunningTest {

    private static final Set<String> COMMANDS = Set.of(ActionNames.ADD_CUSTOM_PREDEFINED_CORRELATION);

    @Override
    protected void doActionAfterCheck(ActionEvent event) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getCurrentNode() == null) {
            return;
        }
        TestElement extractor = gui.getCurrentNode().getTestElement();
        List<Rule> newRules;
        try {
            newRules = rulesFromExtractor(extractor);
        } catch (IllegalArgumentException ex) {
            JMeterUtils.reportErrorToUser(MessageFormat.format(
                    JMeterUtils.getResString("add_custom_predefined_correlation_invalid"), ex.getMessage()),
                    JMeterUtils.getResString("add_custom_predefined_correlation"));
            return;
        }
        String group = JOptionPane.showInputDialog(
                gui.getMainFrame(),
                JMeterUtils.getResString("add_custom_predefined_correlation_group_prompt"),
                JMeterUtils.getResString("add_custom_predefined_correlation"),
                JOptionPane.PLAIN_MESSAGE);
        if (group == null) {
            return;
        }
        group = group.trim();
        if (group.isEmpty()) {
            group = "Custom";
        }
        newRules = withGroup(newRules, group);

        List<JMeterTreeNode> testPlans = gui.getTreeModel().getNodesOfType(TestPlan.class);
        if (testPlans.isEmpty()) {
            JMeterUtils.reportErrorToUser(
                    JMeterUtils.getResString("add_custom_predefined_correlation_no_plan"),
                    JMeterUtils.getResString("add_custom_predefined_correlation"));
            return;
        }
        JMeterTreeNode testPlanNode = testPlans.get(0);
        TestElement testPlan = testPlanNode.getTestElement();
        try {
            HarCorrelationRuleCatalog.storeCustomRulesEverywhere(testPlan, newRules);
        } catch (IOException ex) {
            JMeterUtils.reportErrorToUser(ex.getMessage(),
                    JMeterUtils.getResString("add_custom_predefined_correlation"));
            return;
        }
        gui.getTreeModel().nodeChanged(testPlanNode);
        gui.setDirty(true);
        JMeterUtils.reportInfoToUser(MessageFormat.format(
                JMeterUtils.getResString("add_custom_predefined_correlation_saved"), newRules.size()),
                JMeterUtils.getResString("add_custom_predefined_correlation"));
    }

    static List<Rule> rulesFromExtractor(TestElement extractor) {
        if (extractor instanceof RegexExtractor regexExtractor) {
            return List.of(ruleFromRegex(regexExtractor));
        }
        if (extractor instanceof JSONPostProcessor jsonExtractor) {
            return rulesFromJsonPath(jsonExtractor);
        }
        throw new IllegalArgumentException("only Regex and JSONPath extractors are supported");
    }

    private static Rule ruleFromRegex(RegexExtractor extractor) {
        requireParentScope(extractor);
        String variableName = required(extractor.getRefName(), "variable name");
        String expression = required(extractor.getRegex(), "regular expression");
        ResponseField responseField;
        if (extractor.useHeaders()) {
            responseField = ResponseField.HEADERS;
        } else if (extractor.useBody()) {
            responseField = ResponseField.BODY;
        } else {
            throw new IllegalArgumentException("the extraction source must be response body or response headers");
        }
        return new Rule(
                customId(variableName), "Custom", displayName(extractor, variableName), variableName,
                ExtractorType.REGEX, responseField, expression, required(extractor.getTemplate(), "template"),
                extractor.getDefaultValue(), extractor.isEmptyDefaultValue(), false,
                extractor.isFailOnNoMatch());
    }

    private static List<Rule> rulesFromJsonPath(JSONPostProcessor extractor) {
        requireParentScope(extractor);
        String[] variableNames = required(extractor.getRefNames(), "variable name").split(";");
        String[] expressions = required(extractor.getJsonPathExpressions(), "JSONPath expression").split(";");
        String[] defaultValues = extractor.getDefaultValues().split(";", -1);
        if (variableNames.length != expressions.length || variableNames.length != defaultValues.length) {
            throw new IllegalArgumentException(
                    "JSONPath variable, expression, and default-value counts must match");
        }
        List<Rule> rules = new ArrayList<>(variableNames.length);
        Set<String> ruleIds = new LinkedHashSet<>();
        for (int i = 0; i < variableNames.length; i++) {
            String variableName = required(variableNames[i], "variable name");
            String expression = required(expressions[i], "JSONPath expression");
            String id = customId(variableName);
            if (!ruleIds.add(id)) {
                throw new IllegalArgumentException("JSONPath variables must be unique");
            }
            String name = displayName(extractor, variableName);
            if (variableNames.length > 1) {
                name += " (" + variableName + ')';
            }
            rules.add(new Rule(
                    id, "Custom", name, variableName, ExtractorType.JSON_PATH, ResponseField.BODY,
                    expression, "", defaultValues[i], false,
                    extractor.getComputeConcatenation(), extractor.isFailOnNoMatch()));
        }
        return List.copyOf(rules);
    }

    static List<Rule> withGroup(List<Rule> rules, String group) {
        return rules.stream()
                .map(rule -> new Rule(
                        rule.getId(), group, rule.getName(), rule.getVariableName(), rule.getExtractorType(),
                        rule.getResponseField(), rule.getExpression(), rule.getTemplate(), rule.getMaxMatches(),
                        rule.getDefaultValue(), rule.isEmptyDefaultValue(),
                        rule.isComputeConcatenation(),
                        rule.isFailOnNoMatch()))
                .toList();
    }

    private static void requireParentScope(AbstractScopedTestElement extractor) {
        if (!extractor.isScopeParent(extractor.fetchScope())) {
            throw new IllegalArgumentException("scope must be the parent response");
        }
    }

    private static String displayName(TestElement extractor, String variableName) {
        String name = extractor.getName().trim();
        if (name.regionMatches(true, 0, "Extract ", 0, 8)) {
            name = name.substring(8).trim();
        }
        return name.isEmpty() ? variableName : name;
    }

    private static String customId(String variableName) {
        return "custom-" + variableName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }

    private static String required(String value, String field) {
        if (StringUtilities.isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    @Override
    public Set<String> getActionNames() {
        return COMMANDS;
    }
}
