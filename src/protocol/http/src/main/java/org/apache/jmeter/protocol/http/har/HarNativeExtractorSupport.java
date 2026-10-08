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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.apache.jmeter.extractor.BoundaryExtractor;
import org.apache.jmeter.extractor.HtmlExtractor;
import org.apache.jmeter.extractor.XPath2Extractor;
import org.apache.jmeter.extractor.XPathExtractor;
import org.apache.jmeter.extractor.gui.BoundaryExtractorGui;
import org.apache.jmeter.extractor.gui.HtmlExtractorGui;
import org.apache.jmeter.extractor.gui.XPath2ExtractorGui;
import org.apache.jmeter.extractor.gui.XPathExtractorGui;
import org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor;
import org.apache.jmeter.extractor.json.jmespath.gui.JMESPathExtractorGui;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractScopedTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;

/** Uses native built-in extractors for both discovery and replay. */
final class HarNativeExtractorSupport {
    private HarNativeExtractorSupport() {
    }

    static boolean supports(ExtractorType type) {
        return type != ExtractorType.REGEX && type != ExtractorType.JSON_PATH;
    }

    static Rule ruleFromExtractor(TestElement element) {
        if (!(element instanceof AbstractScopedTestElement scoped) || !scoped.isScopeParent(scoped.fetchScope())) {
            throw new IllegalArgumentException("scope must be the parent response");
        }
        ExtractorType type;
        String variable;
        String expression;
        String template = "";
        String defaultValue;
        boolean empty = false;
        boolean fail;
        ResponseField field = ResponseField.BODY;
        if (element instanceof BoundaryExtractor extractor) {
            type = ExtractorType.BOUNDARY;
            variable = extractor.getRefName();
            expression = extractor.getLeftBoundary();
            template = extractor.getRightBoundary();
            if (expression.isEmpty() && template.isEmpty()) {
                throw new IllegalArgumentException("at least one boundary is required");
            }
            if (extractor.useHeaders()) {
                field = ResponseField.HEADERS;
            } else if (!extractor.useBody()) {
                throw new IllegalArgumentException("the extraction source must be response body or response headers");
            }
            defaultValue = extractor.getDefaultValue();
            empty = extractor.isEmptyDefaultValue();
            fail = extractor.isFailOnNoMatch();
        } else if (element instanceof HtmlExtractor extractor) {
            type = ExtractorType.CSS;
            variable = extractor.getRefName();
            expression = extractor.getExpression();
            template = extractor.getAttribute();
            defaultValue = extractor.getDefaultValue();
            empty = extractor.isEmptyDefaultValue();
            fail = extractor.isFailOnNoMatch();
        } else if (element instanceof XPathExtractor extractor) {
            type = ExtractorType.XPATH;
            variable = extractor.getRefName();
            expression = extractor.getXPathQuery();
            defaultValue = extractor.getDefaultValue();
            fail = extractor.isFailOnNoMatch();
        } else if (element instanceof XPath2Extractor extractor) {
            type = ExtractorType.XPATH2;
            variable = extractor.getRefName();
            expression = extractor.getXPathQuery();
            defaultValue = extractor.getDefaultValue();
            fail = extractor.isFailOnNoMatch();
        } else if (element instanceof JMESPathExtractor extractor) {
            type = ExtractorType.JSON_JMESPATH;
            variable = extractor.getRefName();
            expression = extractor.getJmesPathExpression();
            defaultValue = extractor.getDefaultValue();
            fail = extractor.isFailOnNoMatch();
        } else {
            throw new IllegalArgumentException("this extractor type is not supported");
        }
        if (variable.isBlank() || type != ExtractorType.BOUNDARY && expression.isBlank()) {
            throw new IllegalArgumentException("variable name and expression are required");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        Set<String> settingNames = extractorSettingNames(type);
        var properties = element.propertyIterator();
        while (properties.hasNext()) {
            var property = properties.next();
            if (settingNames.contains(property.getName())) {
                settings.put(property.getName(), property.getStringValue());
            }
        }
        String name = element.getName().trim();
        if (name.startsWith("Extract ")) {
            name = name.substring(8).trim();
        }
        return new Rule("custom-" + variable.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-"),
                "Custom", name.isEmpty() ? variable : name, variable, type, field, expression, template,
                defaultValue, empty, false, fail).withExtractorSettings(settings);
    }

    private static Set<String> extractorSettingNames(ExtractorType type) {
        return switch (type) {
        case CSS -> Set.of("HtmlExtractor.extractor_impl");
        case XPATH -> Set.of("XPathExtractor.tolerant", "XPathExtractor.namespace", "XPathExtractor.quiet",
                "XPathExtractor.report_errors", "XPathExtractor.show_warnings", "XPathExtractor.download_dtds",
                "XPathExtractor.whitespace", "XPathExtractor.validate", "XPathExtractor.fragment");
        case XPATH2 -> Set.of("XPathExtractor2.fragment", "XPathExtractor2.namespaces");
        default -> Set.of();
        };
    }

    static TestElement build(Rule rule, int matchNumber, String variable) {
        TestElement element;
        String gui;
        switch (rule.getExtractorType()) {
        case BOUNDARY -> {
            element = new BoundaryExtractor();
            gui = BoundaryExtractorGui.class.getName();
        }
        case CSS -> {
            element = new HtmlExtractor();
            gui = HtmlExtractorGui.class.getName();
        }
        case XPATH -> {
            element = new XPathExtractor();
            gui = XPathExtractorGui.class.getName();
        }
        case XPATH2 -> {
            element = new XPath2Extractor();
            gui = XPath2ExtractorGui.class.getName();
        }
        case JSON_JMESPATH -> {
            element = new JMESPathExtractor();
            gui = JMESPathExtractorGui.class.getName();
        }
        default -> throw new IllegalArgumentException("Unsupported native extractor");
        }
        rule.getExtractorSettings().forEach(element::setProperty);
        ((AbstractScopedTestElement) element).setScopeParent();
        element.setName("Extract " + rule.getName());
        element.setProperty(TestElement.GUI_CLASS, gui);
        if (element instanceof BoundaryExtractor extractor) {
            extractor.setRefName(variable);
            extractor.setMatchNumber(matchNumber);
            extractor.setLeftBoundary(rule.getExpression());
            extractor.setRightBoundary(rule.getTemplate());
            extractor.setUseField(rule.getResponseField() == ResponseField.HEADERS ? "true" : "false");
            extractor.setDefaultValue(rule.getDefaultValue());
            extractor.setDefaultEmptyValue(rule.isEmptyDefaultValue());
            extractor.setFailOnNoMatch(rule.isFailOnNoMatch());
        } else if (element instanceof HtmlExtractor extractor) {
            extractor.setRefName(variable);
            extractor.setMatchNumber(matchNumber);
            extractor.setExpression(rule.getExpression());
            extractor.setAttribute(rule.getTemplate());
            extractor.setDefaultValue(rule.getDefaultValue());
            extractor.setDefaultEmptyValue(rule.isEmptyDefaultValue());
            extractor.setFailOnNoMatch(rule.isFailOnNoMatch());
        } else if (element instanceof XPathExtractor extractor) {
            extractor.setRefName(variable);
            extractor.setMatchNumber(matchNumber);
            extractor.setXPathQuery(rule.getExpression());
            extractor.setDefaultValue(rule.getDefaultValue());
            extractor.setFailOnNoMatch(rule.isFailOnNoMatch());
        } else if (element instanceof XPath2Extractor extractor) {
            extractor.setRefName(variable);
            extractor.setMatchNumber(matchNumber);
            extractor.setXPathQuery(rule.getExpression());
            extractor.setDefaultValue(rule.getDefaultValue());
            extractor.setFailOnNoMatch(rule.isFailOnNoMatch());
        } else if (element instanceof JMESPathExtractor extractor) {
            extractor.setRefName(variable);
            extractor.setMatchNumber(Integer.toString(matchNumber));
            extractor.setJmesPathExpression(rule.getExpression());
            extractor.setDefaultValue(rule.getDefaultValue());
            extractor.setFailOnNoMatch(rule.isFailOnNoMatch());
        }
        return element;
    }

    static List<String> extract(Rule rule, String body, String headers) {
        TestElement extractor = build(rule, -1, "correlationCandidate");
        var context = JMeterContextService.getContext();
        var previousVariables = context.getVariables();
        var previousResult = context.getPreviousResult();
        JMeterVariables variables = new JMeterVariables();
        SampleResult sample = new SampleResult();
        sample.setResponseData(body, "UTF-8");
        sample.setResponseHeaders(headers);
        try {
            context.setVariables(variables);
            context.setPreviousResult(sample);
            ((PostProcessor) extractor).process();
            int count = Integer.parseInt(variables.get("correlationCandidate_matchNr"));
            List<String> values = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                HarPredefinedCorrelation.checkCancelled();
                String value = variables.get("correlationCandidate_" + i);
                values.add(value == null ? "" : value);
            }
            return values;
        } catch (CancellationException ex) {
            throw ex;
        } catch (RuntimeException | StackOverflowError ex) {
            return List.of();
        } finally {
            context.setVariables(previousVariables);
            context.setPreviousResult(previousResult);
        }
    }
}
