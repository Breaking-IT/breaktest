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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.extractor.BoundaryExtractor;
import org.apache.jmeter.extractor.HtmlExtractor;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.XPath2Extractor;
import org.apache.jmeter.extractor.XPathExtractor;
import org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.Test;

class HarNativeExtractorSupportTest extends JMeterTestCase {
    private record Fixture(TestElement extractor, String body) { }

    private static List<Fixture> fixtures() {
        BoundaryExtractor boundary = new BoundaryExtractor();
        boundary.setRefName("value");
        boundary.setLeftBoundary("[");
        boundary.setRightBoundary("]");
        HtmlExtractor css = new HtmlExtractor();
        css.setRefName("value");
        css.setExpression("input[name=id]");
        css.setAttribute("value");
        XPathExtractor xpath = new XPathExtractor();
        xpath.setRefName("value");
        xpath.setXPathQuery("/root/id/text()");
        XPath2Extractor xpath2 = new XPath2Extractor();
        xpath2.setRefName("value");
        xpath2.setXPathQuery("/n:root/n:id/text()");
        xpath2.setNamespaces("n=urn:test");
        JMESPathExtractor jmes = new JMESPathExtractor();
        jmes.setRefName("value");
        jmes.setJmesPathExpression("id");
        return List.of(new Fixture(boundary, "[abc]"),
                new Fixture(css, "<input name='id' value='abc'>"),
                new Fixture(xpath, "<root><id>abc</id></root>"),
                new Fixture(xpath2, "<root xmlns='urn:test'><id>abc</id></root>"),
                new Fixture(jmes, "{\"id\":\"abc\"}"));
    }

    @Test
    void nativeRulesRoundTripDiscoverAndReplayShortValues() throws Exception {
        for (Fixture fixture : fixtures()) {
            var rules = SaveExtractorAsPredefinedCorrelationAction.rulesFromExtractor(fixture.extractor());
            rules = SaveExtractorAsPredefinedCorrelationAction.withObservedMinimums(rules, fixture.body(), "");
            var rule = HarCorrelationRuleCatalog.parse(HarCorrelationRuleCatalog.serialize(rules)).get(0);
            assertEquals(3, rule.getMinValueLength(), fixture.extractor().getClass().getName());
            assertEquals(rules.get(0).getExtractorSettings(), rule.getExtractorSettings());
            assertEquals(rule.getExtractorSettings(), SaveExtractorAsPredefinedCorrelationAction
                    .withGroup(List.of(rule), "Other").get(0).getExtractorSettings());
            HarEntry source = entry(0, "https://example.test/source");
            source.setResponseContentText(fixture.body());
            HarEntry target = entry(1, "https://example.test/abc");
            var correlations = HarPredefinedCorrelation.find(List.of(source, target), List.of(rule));
            assertEquals(1, correlations.size(), fixture.extractor().getClass().getName());
            var extractor = HarPredefinedCorrelation.buildExtractor(correlations.get(0));
            assertEquals(fixture.extractor().getClass(), extractor.getClass());
            var context = JMeterContextService.getContext();
            var savedVars = context.getVariables();
            var savedSample = context.getPreviousResult();
            try {
                JMeterVariables variables = new JMeterVariables();
                SampleResult sample = new SampleResult();
                sample.setResponseData(fixture.body(), "UTF-8");
                context.setVariables(variables);
                context.setPreviousResult(sample);
                ((PostProcessor) extractor).process();
                assertEquals("abc", variables.get(correlations.get(0).getVariableName()));
                assertEquals(List.of("abc"), HarNativeExtractorSupport.extract(rule, fixture.body(), ""));
                assertSame(variables, context.getVariables());
                assertSame(sample, context.getPreviousResult());
            } finally {
                context.setVariables(savedVars);
                context.setPreviousResult(savedSample);
            }
        }
    }

    @Test
    void regexAndJsonMinimumsUseNonEmptyCapturedValuesNotDefaults() {
        RegexExtractor regex = new RegexExtractor();
        regex.setRefName("value");
        regex.setRegex("id=([^;]*);");
        regex.setTemplate("$1$");
        regex.setDefaultValue("x");
        var rules = SaveExtractorAsPredefinedCorrelationAction.rulesFromExtractor(regex);
        assertEquals(6, rules.get(0).getMinValueLength());
        for (String value : List.of("a", "abc", "abcd", "abcdefghi")) {
            var inferred = SaveExtractorAsPredefinedCorrelationAction.withObservedMinimums(rules, "id=" + value + ";", "");
            assertEquals(Math.min(6, value.length()), inferred.get(0).getMinValueLength());
        }
        for (String body : List.of("no match", "id=;", "id=   ;")) {
            assertEquals(6, SaveExtractorAsPredefinedCorrelationAction.withObservedMinimums(rules, body, "")
                    .get(0).getMinValueLength());
        }
        JSONPostProcessor json = new JSONPostProcessor();
        json.setRefNames("first;second");
        json.setJsonPathExpressions("$.first;$.second");
        json.setDefaultValues("x;x");
        var jsonRules = SaveExtractorAsPredefinedCorrelationAction.withObservedMinimums(
                SaveExtractorAsPredefinedCorrelationAction.rulesFromExtractor(json),
                "{\"first\":\"abc\",\"second\":\"long-value\"}", "");
        assertEquals(List.of(3, 6), jsonRules.stream().map(HarPredefinedCorrelation.Rule::getMinValueLength).toList());
    }

    @Test
    void nativeExtractorsOfferSaveMenu() throws Exception {
        var previous = org.apache.jmeter.gui.GuiPackage.getInstance();
        try {
            var model = new org.apache.jmeter.gui.tree.JMeterTreeModel();
            var listener = new org.apache.jmeter.gui.tree.JMeterTreeListener(model);
            listener.setJTree(new javax.swing.JTree(model));
            org.apache.jmeter.gui.GuiPackage.initInstance(listener, model);
            for (var gui : List.of(new org.apache.jmeter.extractor.gui.BoundaryExtractorGui(),
                    new org.apache.jmeter.extractor.gui.HtmlExtractorGui(),
                    new org.apache.jmeter.extractor.gui.XPathExtractorGui(),
                    new org.apache.jmeter.extractor.gui.XPath2ExtractorGui(),
                    new org.apache.jmeter.extractor.json.jmespath.gui.JMESPathExtractorGui())) {
                var menu = gui.createPopupMenu();
                assertFalse(menu.getComponentCount() == 0);
                assertTrue(((javax.swing.JMenuItem) menu.getComponent(0)).getActionCommand()
                        .equals(org.apache.jmeter.gui.action.ActionNames.ADD_CUSTOM_PREDEFINED_CORRELATION));
            }
        } finally {
            var field = org.apache.jmeter.gui.GuiPackage.class.getDeclaredField("guiPack");
            field.setAccessible(true);
            field.set(null, previous);
        }
    }

    private static HarEntry entry(int index, String url) {
        HarEntry entry = new HarEntry();
        entry.setOriginalIndex(index);
        entry.setStartMs(index * 100);
        entry.setMethod("GET");
        entry.setUrl(url);
        entry.setResponseStatus(200);
        return entry;
    }
}
