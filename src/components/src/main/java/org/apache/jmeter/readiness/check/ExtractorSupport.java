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

package org.apache.jmeter.readiness.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.jmeter.extractor.BoundaryExtractor;
import org.apache.jmeter.extractor.HtmlExtractor;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.XPath2Extractor;
import org.apache.jmeter.extractor.XPathExtractor;
import org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.testelement.TestElement;

/**
 * Describes the extractors whose outcome readiness records: Regular Expression, XPath, XPath2, Boundary, CSS/JQuery
 * (HTML), JSON (JSONPath) and JSON JMESPath extractors. Other post-processors are reported as unsupported.
 */
public final class ExtractorSupport {
    /** Post-processors that never extract a value */
    private static final Set<String> NON_EXTRACTING = Set.of(
            "org.apache.jmeter.reporters.ResultAction", // $NON-NLS-1$
            "org.apache.jmeter.extractor.DebugPostProcessor"); // $NON-NLS-1$

    /**
     * @param element the extractor
     * @param type display type
     * @param refNames variables it sets
     * @param failOnNoMatch whether a failed match fails the sample (a verification)
     * @param expression its expression
     */
    public record ExtractorInfo(TestElement element, String type, List<String> refNames, boolean failOnNoMatch,
            String expression) {
        public ExtractorInfo {
            refNames = List.copyOf(refNames);
        }

        /**
         * @return finding key of the extractor: its reference names
         */
        public String key() {
            return String.join(";", refNames);
        }
    }

    private ExtractorSupport() {
    }

    /**
     * @param element a test element
     * @return its description when it is a supported extractor
     */
    public static Optional<ExtractorInfo> describe(TestElement element) {
        if (element instanceof RegexExtractor e) {
            return info(e, "Regular Expression Extractor", e.getRefName(), e.isFailOnNoMatch(), e.getRegex());
        } else if (element instanceof BoundaryExtractor e) {
            return info(e, "Boundary Extractor", e.getRefName(), e.isFailOnNoMatch(),
                    e.getLeftBoundary() + "…" + e.getRightBoundary());
        } else if (element instanceof XPath2Extractor e) {
            return info(e, "XPath2 Extractor", e.getRefName(), e.isFailOnNoMatch(), e.getXPathQuery());
        } else if (element instanceof XPathExtractor e) {
            return info(e, "XPath Extractor", e.getRefName(), e.isFailOnNoMatch(), e.getXPathQuery());
        } else if (element instanceof HtmlExtractor e) {
            return info(e, "CSS Selector Extractor", e.getRefName(), e.isFailOnNoMatch(), e.getExpression());
        } else if (element instanceof JSONPostProcessor e) {
            return info(e, "JSON Extractor", e.getRefNames(), e.isFailOnNoMatch(), e.getJsonPathExpressions());
        } else if (element instanceof JMESPathExtractor e) {
            return info(e, "JSON JMESPath Extractor", e.getRefName(), e.isFailOnNoMatch(),
                    e.getJmesPathExpression());
        }
        return Optional.empty();
    }

    private static Optional<ExtractorInfo> info(TestElement element, String type, String refNames,
            boolean failOnNoMatch, String expression) {
        List<String> names = new ArrayList<>();
        for (String name : (refNames == null ? "" : refNames).split(";")) {
            if (!name.trim().isEmpty()) {
                names.add(name.trim());
            }
        }
        return Optional.of(new ExtractorInfo(element, type, names, failOnNoMatch,
                expression == null ? "" : expression));
    }

    /**
     * @param element a test element
     * @return whether it is a post-processor that may extract values but does not report its outcome
     */
    public static boolean isUnsupportedPostProcessor(TestElement element) {
        return element instanceof PostProcessor && describe(element).isEmpty()
                && !NON_EXTRACTING.contains(element.getClass().getName());
    }
}
