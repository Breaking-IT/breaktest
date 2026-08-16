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

import java.util.List;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.junit.jupiter.api.Test;

class SaveExtractorAsPredefinedCorrelationActionTest {

    @Test
    void capturesRegexExtractorSettings() {
        RegexExtractor extractor = new RegexExtractor();
        extractor.setName("Extract Tenant session");
        extractor.setRefName("tenant_session");
        extractor.setRegex("X-Session: ([^\\s]+)");
        extractor.setTemplate("session-$1$");
        extractor.setMatchNumber(2);
        extractor.setDefaultValue("fallback");
        extractor.setDefaultEmptyValue(true);
        extractor.setFailOnNoMatch(false);
        extractor.setUseField(RegexExtractor.USE_HDRS);

        Rule rule = SaveExtractorAsPredefinedCorrelationAction.rulesFromExtractor(extractor).get(0);

        assertEquals("custom-tenant_session", rule.getId());
        assertEquals("Custom", rule.getGroup());
        assertEquals("Tenant session", rule.getName());
        assertEquals(ResponseField.HEADERS, rule.getResponseField());
        assertEquals("session-$1$", rule.getTemplate());
        assertEquals(1, rule.getMaxMatches());
        assertEquals("fallback", rule.getDefaultValue());
        assertTrue(rule.isEmptyDefaultValue());
        assertFalse(rule.isFailOnNoMatch());
    }

    @Test
    void expandsMultiValueJsonPathExtractorIntoSeparateRules() {
        JSONPostProcessor extractor = new JSONPostProcessor();
        extractor.setName("Extract API identifiers");
        extractor.setRefNames("customer_id;order_id");
        extractor.setJsonPathExpressions("$.customer.id;$.order.id");
        extractor.setMatchNumbers("1;2");
        extractor.setDefaultValues(";missing");
        extractor.setComputeConcatenation(true);
        extractor.setFailOnNoMatch(true);

        List<Rule> rules = SaveExtractorAsPredefinedCorrelationAction.rulesFromExtractor(extractor);

        assertEquals(List.of("custom-customer_id", "custom-order_id"),
                rules.stream().map(Rule::getId).toList());
        assertTrue(rules.stream().allMatch(rule -> rule.getMaxMatches() == 1));
        assertEquals("missing", rules.get(1).getDefaultValue());
        assertTrue(rules.get(1).isComputeConcatenation());
    }

    @Test
    void determinesMatchNumberDuringDiscoveryInsteadOfCopyingExtractorMatchMode() {
        RegexExtractor extractor = new RegexExtractor();
        extractor.setRefName("all_values");
        extractor.setRegex("id=(.+)");
        extractor.setTemplate("$1$");
        extractor.setMatchNumber(-1);

        Rule rule = SaveExtractorAsPredefinedCorrelationAction.rulesFromExtractor(extractor).get(0);

        assertEquals(1, rule.getMaxMatches());
    }

    @Test
    void appliesAChosenGroupToEveryGeneratedRule() {
        JSONPostProcessor extractor = new JSONPostProcessor();
        extractor.setName("Extract API identifiers");
        extractor.setRefNames("customer_id;order_id");
        extractor.setJsonPathExpressions("$.customer.id;$.order.id");
        extractor.setMatchNumbers("1;1");
        extractor.setDefaultValues(";");

        List<Rule> grouped = SaveExtractorAsPredefinedCorrelationAction.withGroup(
                SaveExtractorAsPredefinedCorrelationAction.rulesFromExtractor(extractor), "Commerce");

        assertEquals(List.of("Commerce", "Commerce"), grouped.stream().map(Rule::getGroup).toList());
    }
}
