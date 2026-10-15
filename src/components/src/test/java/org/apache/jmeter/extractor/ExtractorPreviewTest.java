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

package org.apache.jmeter.extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractScopedTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExtractorPreviewTest {
    private static final String XML = "<root><item id='1'>one</item><item id='2'>two</item></root>";

    static Stream<Arguments> configurations() {
        return Stream.of("boundary", "jsoup", "jodd", "xpath", "xpath2", "jmespath")
                .flatMap(type -> Stream.of(-1, 1, 2, 9).flatMap(match -> Stream.of(false, true)
                        .map(noMatch -> Arguments.of(type, match, noMatch))));
    }

    private static AbstractScopedTestElement extractor(String type, int match, boolean noMatch) {
        switch (type) {
        case "boundary":
            BoundaryExtractor boundary = new BoundaryExtractor();
            boundary.setRefName("value");
            boundary.setLeftBoundary(noMatch ? "absent" : "id='");
            boundary.setRightBoundary("'");
            boundary.setMatchNumber(match);
            boundary.setDefaultValue("missing");
            return boundary;
        case "jsoup":
        case "jodd":
            HtmlExtractor html = new HtmlExtractor();
            html.setRefName("value");
            html.setExtractor("jodd".equals(type) ? "JODD" : "JSOUP");
            html.setExpression(noMatch ? "absent" : "item");
            html.setAttribute("id");
            html.setMatchNumber(match);
            html.setDefaultValue("missing");
            return html;
        case "xpath":
            XPathExtractor xpath = new XPathExtractor();
            xpath.setRefName("value");
            xpath.setXPathQuery(noMatch ? "/root/absent" : "/root/item/@id");
            xpath.setMatchNumber(match);
            xpath.setDefaultValue("missing");
            return xpath;
        case "xpath2":
            XPath2Extractor xpath2 = new XPath2Extractor();
            xpath2.setRefName("value");
            xpath2.setXPathQuery(noMatch ? "/root/absent" : "/root/item/@id");
            xpath2.setMatchNumber(match);
            xpath2.setDefaultValue("missing");
            return xpath2;
        case "jmespath":
            JMESPathExtractor jmes = new JMESPathExtractor();
            jmes.setRefName("value");
            jmes.setJmesPathExpression(noMatch ? "absent" : "items[*].id");
            jmes.setMatchNumber(Integer.toString(match));
            jmes.setDefaultValue("missing");
            return jmes;
        default:
            throw new IllegalArgumentException(type);
        }
    }

    private static SampleResult sample(String type) {
        SampleResult sample = new SampleResult();
        sample.setResponseData("jmespath".equals(type) ? "{\"items\":[{\"id\":1},{\"id\":2}]}" : XML, "UTF-8");
        sample.setSuccessful(true);
        return sample;
    }

    private static ExtractorTestResult preview(AbstractScopedTestElement extractor, SampleResult sample) throws Exception {
        if (extractor instanceof BoundaryExtractor boundary) {
            return boundary.extractForTesting(sample);
        }
        if (extractor instanceof HtmlExtractor html) {
            return html.extractForTesting(sample);
        }
        if (extractor instanceof XPathExtractor xpath) {
            return xpath.extractForTesting(sample);
        }
        if (extractor instanceof XPath2Extractor xpath2) {
            return xpath2.extractForTesting(sample);
        }
        return ((JMESPathExtractor) extractor).extractForTesting(sample);
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void previewMatchesRuntimeWithoutChangingSamplesOrVariables(String type, int match, boolean noMatch) throws Exception {
        AbstractScopedTestElement extractor = extractor(type, match, noMatch);
        SampleResult sample = sample(type);
        var context = JMeterContextService.getContext();
        try {
            JMeterVariables vars = new JMeterVariables();
            vars.clear();
            vars.put("sentinel", "unchanged");
            context.setVariables(vars);
            context.setPreviousResult(sample);
            ExtractorTestResult preview = preview(extractor, sample);
            assertEquals("unchanged", vars.get("sentinel"));
            var entries = vars.getIterator();
            assertEquals("sentinel", entries.next().getKey());
            assertTrue(!entries.hasNext());
            assertTrue(sample.isSuccessful());
            assertEquals(0, sample.getAssertionResults().length);
            vars.clear();
            extractor.setThreadContext(context);
            ((PostProcessor) extractor).process();
            Map<String, String> runtime = new TreeMap<>();
            vars.getIterator().forEachRemaining(entry -> runtime.put(entry.getKey(), (String) entry.getValue()));
            assertEquals(runtime, preview.variables());
        } finally {
            context.clear();
        }
    }

    @Test
    void honorsScopeAndRejectsRuntimeVariableSources() throws Exception {
        for (String type : new String[] {"boundary", "jsoup", "jodd", "xpath", "xpath2", "jmespath"}) {
            AbstractScopedTestElement extractor = extractor(type, -1, false);
            SampleResult parent = sample(type);
            SampleResult child = new SampleResult();
            child.setResponseData("jmespath".equals(type) ? "{\"items\":[{\"id\":3}]}"
                    : "<root><item id='3'>child</item></root>", "UTF-8");
            parent.addSubResult(child);
            extractor.setScopeChildren();
            assertEquals("3", preview(extractor, parent).variables().get("value_1"), type);
            extractor.setScopeAll();
            assertEquals(3, preview(extractor, parent).matchCount(), type);
            extractor.setScopeVariable("source");
            assertThrows(IllegalArgumentException.class, () -> preview(extractor, parent), type);
        }
    }

    @Test
    void xpath2RuntimeAndPreviewReadTheSelectedChildResponse() throws Exception {
        XPath2Extractor extractor = (XPath2Extractor) extractor("xpath2", -1, false);
        SampleResult parent = sample("xpath2");
        SampleResult child = new SampleResult();
        child.setResponseData("<root><item id='child'/></root>", "UTF-8");
        parent.addSubResult(child);
        extractor.setScopeChildren();
        var context = JMeterContextService.getContext();
        try {
            JMeterVariables vars = new JMeterVariables();
            vars.clear();
            context.setVariables(vars);
            context.setPreviousResult(parent);
            extractor.setThreadContext(context);
            extractor.process();
            assertEquals("child", vars.get("value"));
            assertEquals("1", vars.get("value_matchNr"));
            assertEquals(vars.get("value"), preview(extractor, parent).variables().get("value"));
        } finally {
            context.clear();
        }
    }

    @Test
    void supportsRandomSelectionAndDoesNotReuseCssDocuments() throws Exception {
        for (String type : new String[] {"boundary", "jsoup", "jodd", "xpath", "xpath2", "jmespath"}) {
            AbstractScopedTestElement extractor = extractor(type, 0, false);
            assertTrue(java.util.List.of("1", "2").contains(preview(extractor, sample(type)).variables().get("value")), type);
        }
        for (String type : new String[] {"jsoup", "jodd"}) {
            HtmlExtractor extractor = (HtmlExtractor) extractor(type, 1, false);
            SampleResult sample = sample(type);
            assertEquals("1", preview(extractor, sample).variables().get("value"));
            sample.setResponseData("<item id='changed'>text</item>", "UTF-8");
            assertEquals("changed", preview(extractor, sample).variables().get("value"));
        }
    }

    @Test
    void reportsInvalidExpressionsAndDocuments() {
        XPathExtractor xpath = (XPathExtractor) extractor("xpath", -1, false);
        xpath.setXPathQuery("[");
        assertThrows(Exception.class, () -> preview(xpath, sample("xpath")));
        XPath2Extractor xpath2 = (XPath2Extractor) extractor("xpath2", -1, false);
        xpath2.setXPathQuery("[");
        assertThrows(Exception.class, () -> preview(xpath2, sample("xpath2")));
        JMESPathExtractor jmes = (JMESPathExtractor) extractor("jmespath", -1, false);
        jmes.setJmesPathExpression("[");
        assertThrows(Exception.class, () -> preview(jmes, sample("jmespath")));
        HtmlExtractor html = (HtmlExtractor) extractor("jsoup", -1, false);
        html.setExpression("[");
        assertThrows(Exception.class, () -> preview(html, sample("jsoup")));
        SampleResult invalid = new SampleResult();
        invalid.setResponseData("{", "UTF-8");
        assertThrows(Exception.class, () -> preview(extractor("jmespath", 1, false), invalid));
        assertThrows(Exception.class, () -> preview(extractor("xpath", 1, false), invalid));
        assertThrows(Exception.class, () -> preview(extractor("xpath2", 1, false), invalid));
    }

    @Test
    void honorsBoundarySourceAndXpathNamespacesAndFragments() throws Exception {
        BoundaryExtractor boundary = (BoundaryExtractor) extractor("boundary", -1, false);
        boundary.setUseField("true");
        SampleResult sample = sample("boundary");
        sample.setResponseHeaders("X-Id: id='header'");
        assertEquals("header", preview(boundary, sample).variables().get("value_1"));
        XPath2Extractor xpath = (XPath2Extractor) extractor("xpath2", -1, false);
        xpath.setNamespaces("n=urn:test");
        xpath.setXPathQuery("/n:root/n:item");
        xpath.setFragment(true);
        sample.setResponseData("<root xmlns='urn:test'><item>value</item></root>", "UTF-8");
        assertTrue(preview(xpath, sample).variables().get("value").contains("<item"));
    }
}
