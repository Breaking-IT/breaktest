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

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.assertions.JSR223Assertion;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.extractor.BoundaryExtractor;
import org.apache.jmeter.extractor.HtmlExtractor;
import org.apache.jmeter.extractor.JSR223PostProcessor;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.XPath2Extractor;
import org.apache.jmeter.extractor.XPathExtractor;
import org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.modifiers.JSR223PreProcessor;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class ParameterCompletionCatalogTest extends JMeterTestCase {
    @Test
    void combinesPlanCsvAndThreadExtractorsWithoutLeakingAcrossThreadGroups() {
        JMeterTreeNode plan = node(new TestPlan());
        add(plan, csv("username, password, userid, username"));
        JMeterTreeNode first = add(plan, new ThreadGroup());
        JMeterTreeNode second = add(plan, new ThreadGroup());
        add(first, csv("local"));
        add(second, csv("otherCsv"));
        add(first, regex("productIds", -1));
        add(second, regex("otherExtractor", -1));
        JMeterTreeNode selected = add(first, new ConfigTestElement());
        List<String> names = names(selected);
        assertTrue(names.containsAll(List.of("username", "password", "userid", "local", "productIds",
                "productIds_n", "productIds_1", "productIds_matchNr", "productIds_rand", "productIds_1_gn")));
        assertFalse(names.contains("otherCsv"));
        assertFalse(names.contains("otherExtractor"));
        assertFalse(names(plan).contains("local"));
        assertFalse(names(second).contains("productIds"));
    }

    @Test
    void refreshesRenamesRemovalAndDisabledBranchesWithoutReadingValues() {
        JMeterTreeNode plan = node(new TestPlan());
        JMeterTreeNode group = add(plan, new ThreadGroup());
        RegexExtractor extractor = regex("old", 1);
        JMeterTreeNode extraction = add(group, extractor);
        assertTrue(names(group).contains("old"));
        extractor.setRefName("new");
        assertFalse(names(group).contains("old"));
        assertTrue(names(group).contains("new"));
        extraction.setEnabled(false);
        assertFalse(names(group).contains("new"));
        extraction.setEnabled(true);
        extraction.removeFromParent();
        assertFalse(names(group).contains("new"));
        GenericController controller = new GenericController();
        JMeterTreeNode disabled = add(group, controller);
        add(disabled, regex("hidden", 1));
        disabled.setEnabled(false);
        assertFalse(names(group).contains("hidden"));
    }

    @Test
    void includesPlanAndUserDefinedVariableNames() {
        TestPlan plan = new TestPlan();
        Arguments globals = new Arguments();
        globals.addArgument("host", "secret-value-must-not-be-suggested");
        plan.setUserDefinedVariables(globals);
        JMeterTreeNode root = node(plan);
        JMeterTreeNode group = add(root, new ThreadGroup());
        Arguments local = new Arguments();
        local.addArgument("token", "another-secret");
        add(group, local);
        assertTrue(names(group).containsAll(List.of("host", "token")));
        assertFalse(names(group).contains("secret-value-must-not-be-suggested"));
    }

    @Test
    void supportsEveryBuiltInExtractorAndOnlyTheirActualSuffixes() {
        JMeterTreeNode group = node(new ThreadGroup());
        BoundaryExtractor boundary = new BoundaryExtractor();
        boundary.setRefName("boundary");
        boundary.setMatchNumber(-1);
        add(group, boundary);
        HtmlExtractor html = new HtmlExtractor();
        html.setRefName("html");
        html.setMatchNumber(-1);
        add(group, html);
        XPathExtractor xpath = new XPathExtractor();
        xpath.setRefName("xpath");
        xpath.setMatchNumber(-1);
        add(group, xpath);
        XPath2Extractor xpath2 = new XPath2Extractor();
        xpath2.setRefName("xpath2");
        xpath2.setMatchNumber(-1);
        add(group, xpath2);
        JMESPathExtractor jmes = new JMESPathExtractor();
        jmes.setRefName("jmes");
        jmes.setMatchNumber("-1");
        add(group, jmes);
        for (String name : List.of("boundary", "html", "xpath", "xpath2", "jmes")) {
            assertTrue(names(group).containsAll(List.of(name, name + "_n", name + "_matchNr")), name);
            assertFalse(names(group).contains(name + "_rand"), name);
        }
        jmes.setMatchNumber("1");
        assertTrue(names(group).contains("jmes_matchNr"));
        assertFalse(names(group).contains("jmes_n"));
        html.setMatchNumber(1);
        assertFalse(names(group).contains("html_n"));
        assertFalse(names(group).contains("html_matchNr"));
    }

    @Test
    void handlesMultipleJsonReferencesAndConcatenationPerMatchMode() {
        JMeterTreeNode group = node(new ThreadGroup());
        JSONPostProcessor json = new JSONPostProcessor();
        json.setRefNames("products;user;random");
        json.setMatchNumbers("-1;1;0");
        json.setComputeConcatenation(true);
        add(group, json);
        List<String> names = names(group);
        assertTrue(names.containsAll(List.of("products_n", "products_matchNr", "products_ALL", "user",
                "user_matchNr", "random")));
        assertFalse(names.contains("user_n"));
        assertFalse(names.contains("user_ALL"));
        assertFalse(names.contains("random_matchNr"));
        assertFalse(names.contains("products_rand"));
    }

    private static List<String> names(JMeterTreeNode node) {
        return ParameterCompletionCatalog.variables(node).stream()
                .map(ParameterCompletionCatalog.Suggestion::name).toList();
    }

    @Test
    void discoversInlineScriptsInTheCurrentScopeAndRefreshesEdits() {
        JMeterTreeNode plan = node(new TestPlan());
        JMeterTreeNode first = add(plan, new ThreadGroup());
        JMeterTreeNode second = add(plan, new ThreadGroup());
        JSR223PreProcessor pre = new JSR223PreProcessor();
        pre.setProperty("script", "vars.put('credential', value)");
        JMeterTreeNode preNode = add(first, pre);
        JSR223PostProcessor post = new JSR223PostProcessor();
        post.setProperty("script", "vars.put(\"userid\", value)");
        add(first, post);
        JSR223Assertion assertion = new JSR223Assertion();
        assertion.setProperty("script", "vars.put('otherGroup', value)");
        add(second, assertion);
        assertEquals(List.of("credential", "userid"), names(first));
        assertEquals(List.of("otherGroup"), names(second));
        assertTrue(names(plan).isEmpty());

        pre.setProperty("script", "vars.put('renamed', value)");
        assertEquals(List.of("renamed", "userid"), names(first));
        preNode.setEnabled(false);
        assertEquals(List.of("userid"), names(first));
        preNode.setEnabled(true);
        preNode.removeFromParent();
        assertEquals(List.of("userid"), names(first));
    }

    @Test
    void ignoresInactiveInlineScriptsAndDoesNotScanUnrelatedScriptProperties() {
        JMeterTreeNode group = node(new ThreadGroup());
        JSR223PreProcessor pre = new JSR223PreProcessor();
        pre.setProperty("filename", "external.groovy");
        pre.setProperty("script", "vars.put('inactiveInline', value)");
        add(group, pre);
        ConfigTestElement unrelated = new ConfigTestElement();
        unrelated.setProperty("script", "vars.put('notJsr223', value)");
        add(group, unrelated);
        assertTrue(names(group).isEmpty());
    }

    private static JMeterTreeNode node(TestElement element) {
        element.setEnabled(true);
        return new JMeterTreeNode(element, null);
    }

    private static JMeterTreeNode add(JMeterTreeNode parent, TestElement element) {
        JMeterTreeNode child = node(element);
        parent.add(child);
        return child;
    }

    private static RegexExtractor regex(String name, int match) {
        RegexExtractor extractor = new RegexExtractor();
        extractor.setRefName(name);
        extractor.setMatchNumber(match);
        return extractor;
    }

    private static CSVDataSet csv(String names) {
        CSVDataSet csv = new CSVDataSet();
        // TestBean editors persist these properties before leaving the editor.
        csv.setProperty("variableNames", names);
        return csv;
    }
}
