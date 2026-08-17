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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.oro.text.regex.PatternMatcherInput;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in expressions run on every response of every sample, so a badly shaped one costs the load
 * generator real CPU. These bounds are far above the measured cost (tenths of a millisecond on a
 * 100 KB page) and only fail for an expression that scans the body more than once per position.
 */
class HarCorrelationRulePerformanceTest {

    private static final Logger LOG = LoggerFactory.getLogger(HarCorrelationRulePerformanceTest.class);

    private static final int BODY_SIZE = 100 * 1024;
    private static final long REALISTIC_BUDGET_MS = 100;
    private static final long HOSTILE_BUDGET_MS = 500;

    @Test
    void bodyExpressionsStayCheapOnALargePage() {
        String page = html(BODY_SIZE);
        for (Rule rule : bodyRules()) {
            assertWithinBudget(rule, page, REALISTIC_BUDGET_MS, "100 KB page");
        }
    }

    /**
     * Markup that never closes a tag makes an unbounded {@code [^>]*} attribute span rescan the
     * rest of the response at every candidate position, which turns a 100 KB body into seconds of
     * CPU. Every tag span in the catalog is bounded to keep that quadratic case out of reach.
     */
    @Test
    void bodyExpressionsSurviveMarkupThatNeverClosesATag() {
        List<String> hostileBodies = List.of(
                "<input ".repeat(BODY_SIZE / 7),
                "<input name=x ".repeat(BODY_SIZE / 14),
                "<meta name=x content=y ".repeat(BODY_SIZE / 23));
        for (Rule rule : bodyRules()) {
            for (String body : hostileBodies) {
                assertWithinBudget(rule, body, HOSTILE_BUDGET_MS, "unterminated markup");
            }
        }
    }

    private static void assertWithinBudget(Rule rule, String body, long budgetMs, String description) {
        long start = System.nanoTime();
        scan(rule, body);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < budgetMs,
                () -> rule.getId() + " took " + elapsedMs + "ms on " + description
                        + ", budget is " + budgetMs + "ms: " + rule.getExpression());
    }

    private static List<Rule> bodyRules() {
        return HarPredefinedCorrelation.rules().stream()
                .filter(rule -> rule.getExtractorType() == ExtractorType.REGEX
                        && rule.getResponseField() == ResponseField.BODY)
                .toList();
    }

    /** The worst case the Regex Extractor runs at test time: no match, so the whole body is read. */
    private static void scan(Rule rule, String body) {
        Perl5Matcher matcher = JMeterUtils.getMatcher();
        org.apache.oro.text.regex.Pattern pattern = JMeterUtils.getPatternCache()
                .getPattern(rule.getExpression(), Perl5Compiler.READ_ONLY_MASK);
        PatternMatcherInput input = new PatternMatcherInput(body);
        // Read every match, like a Regex Extractor asked for a match number it never finds.
        int matches = 0;
        while (matcher.contains(input, pattern)) {
            matches++;
        }
        LOG.debug("{} matched {} times", rule.getId(), matches);
        JMeterUtils.clearMatcherMemory(matcher, pattern);
    }

    private static String html(int targetBytes) {
        StringBuilder sb = new StringBuilder(targetBytes + 1024);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"></head><body>");
        int block = 0;
        while (sb.length() < targetBytes) {
            block++;
            sb.append("<div class=\"card card-").append(block).append("\" data-id=\"item-").append(block)
                    .append("\"><a href=\"/products/item-").append(block)
                    .append("?utm_source=news&amp;utm_campaign=summer\">Product ").append(block)
                    .append("</a><p>Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do "
                            + "eiusmod tempor incididunt ut labore et dolore magna aliqua.</p>")
                    .append("<input type=\"checkbox\" name=\"select-").append(block)
                    .append("\" value=\"on\"><img src=\"/img/item-").append(block)
                    .append(".webp\" alt=\"Product image\"></div>");
        }
        return sb.append("</body></html>").toString();
    }
}
