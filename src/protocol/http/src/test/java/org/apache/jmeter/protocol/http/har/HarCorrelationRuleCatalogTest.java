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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarCorrelationRuleCatalogTest extends JMeterTestCase {

    @TempDir
    private Path tempDir;

    @AfterEach
    void clearCustomFileProperty() {
        JMeterUtils.getJMeterProperties().remove(HarCorrelationRuleCatalog.CUSTOM_FILE_PROPERTY);
    }

    @Test
    void loadsBuiltInRulesFromVersionedResource() {
        List<Rule> rules = HarCorrelationRuleCatalog.builtInRules();

        assertEquals("oauth-access-token", rules.get(0).getId());
        assertEquals("OAuth", rules.get(0).getGroup());
        List<String> ids = rules.stream().map(Rule::getId).toList();
        assertEquals(Set.copyOf(ids).size(), ids.size(), "rule ids stay unique");
        assertTrue(ids.containsAll(List.of(
                "aspnet-request-verification", "saml-request", "keycloak-session-code",
                "sap-csrf-token", "siebel-session-cookie", "oracle-nca-icx-ticket")), ids.toString());
        assertEquals("ASP.NET", rules.stream()
                .filter(rule -> "aspnet-request-verification".equals(rule.getId()))
                .findFirst()
                .orElseThrow()
                .getGroup());
    }

    @Test
    void serializesAllExtractorSettingsAndStoresThemAsArchiveAttachment() throws Exception {
        Rule rule = regexRule("custom-session", "session_id", "session=([^;]+)", "$1$", 2, false);
        TestPlan testPlan = new TestPlan("Plan");

        HarCorrelationRuleCatalog.storeCustomRules(testPlan, List.of(rule));

        String entryName = testPlan.getPropertyAsString(
                JmxArchiveEntryStore.CORRELATION_RULES_FILENAME_PROPERTY);
        String checksum = testPlan.getPropertyAsString(
                JmxArchiveEntryStore.CORRELATION_RULES_CHECKSUM_PROPERTY);
        assertEquals(HarCorrelationRuleCatalog.CUSTOM_ARCHIVE_ENTRY, entryName);
        byte[] content = JmxArchiveEntryStore.find(entryName, checksum).orElseThrow();
        String serialized = new String(content, StandardCharsets.UTF_8);
        assertTrue(serialized.contains("\"groups\""));
        assertFalse(serialized.contains("\"group\" :"));
        assertFalse(serialized.contains("\"matchNumber\""));
        Rule restored = HarCorrelationRuleCatalog.parse(content).get(0);
        assertEquals(rule.getExpression(), restored.getExpression());
        assertEquals("Custom", restored.getGroup());
        assertEquals(rule.getTemplate(), restored.getTemplate());
        assertEquals(2, restored.getMaxMatches());
        assertFalse(restored.isFailOnNoMatch());
    }

    @Test
    void storesNewCustomRulesInSharedFileAndPlanArchive() throws Exception {
        Path customFile = tempDir.resolve("predefined-correlations.custom.json");
        JMeterUtils.setProperty(HarCorrelationRuleCatalog.CUSTOM_FILE_PROPERTY, customFile.toString());
        Rule rule = new Rule(
                "custom-session", "Custom", "Custom session", "session_id",
                ExtractorType.REGEX, ResponseField.BODY, "session=([^;]+)", "$1$", 100,
                "", false, false, true);
        TestPlan testPlan = new TestPlan("Plan");

        HarCorrelationRuleCatalog.storeCustomRulesEverywhere(testPlan, List.of(rule));

        Rule shared = HarCorrelationRuleCatalog.parse(Files.readAllBytes(customFile)).get(0);
        Rule archived = HarCorrelationRuleCatalog.loadCustomRules(testPlan).get(0);
        assertEquals("custom-session", shared.getId());
        assertEquals(100, shared.getMaxMatches());
        assertEquals("custom-session", archived.getId());
        assertEquals(100, archived.getMaxMatches());
    }

    @Test
    void treatsOlderFlatCustomRulesWithoutAGroupAsCustom() throws Exception {
        String content = """
                {"format":"breaktest-predefined-correlations-v1","rules":[{
                  "id":"legacy-rule","name":"Legacy rule","variableName":"legacy_value",
                  "extractorType":"REGEX","responseField":"BODY","expression":"value=([^&]+)",
                  "matchNumber":7
                }]}
                """;

        Rule rule = HarCorrelationRuleCatalog.parse(content.getBytes(StandardCharsets.UTF_8)).get(0);

        assertEquals("Custom", rule.getGroup());
        assertEquals(1, rule.getMaxMatches());
    }

    @Test
    void planArchiveRulesAreMergedAfterSharedRules() throws Exception {
        Rule override = new Rule(
                "oauth-access-token", "Company", "Plan access token", "plan_access_token",
                ExtractorType.JSON_PATH, ResponseField.BODY, "$.plan.access", "",
                "", false, false, true);
        TestPlan testPlan = new TestPlan("Plan");
        HarCorrelationRuleCatalog.storeCustomRules(testPlan, List.of(override));
        JMeterTreeNode planNode = new JMeterTreeNode(testPlan, null);
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(new ThreadGroup(), null);
        planNode.add(threadGroupNode);

        List<Rule> rules = HarCorrelationRuleCatalog.rulesFor(threadGroupNode);

        Rule merged = rules.stream()
                .filter(rule -> "oauth-access-token".equals(rule.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("Plan access token", merged.getName());
        assertEquals("plan_access_token", merged.getVariableName());
    }

    @Test
    void loadsConfiguredCustomFileAfterBuiltInsAndAllowsIdOverride() throws Exception {
        Rule override = new Rule(
                "oauth-access-token", "Company", "Company access token", "company_access_token",
                ExtractorType.JSON_PATH, ResponseField.BODY, "$.tokens.access", "",
                "", false, false, true);
        Path customFile = tempDir.resolve("shared-correlations.json");
        Files.write(customFile, HarCorrelationRuleCatalog.serialize(List.of(override)));
        JMeterUtils.setProperty(HarCorrelationRuleCatalog.CUSTOM_FILE_PROPERTY, customFile.toString());

        List<Rule> rules = HarCorrelationRuleCatalog.sharedRules();

        Rule merged = rules.stream()
                .filter(rule -> "oauth-access-token".equals(rule.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("Company access token", merged.getName());
        assertEquals("company_access_token", merged.getVariableName());
        assertEquals(HarCorrelationRuleCatalog.builtInRules().size(), rules.size(),
                "an override replaces the built-in rule instead of adding one");
    }

    @Test
    void discoversRegexMatchNumberUsingTheJMeterEngineAndTemplate() {
        Rule rule = regexRule("custom-session", "session_id", "session=([^;]+)", "id-$1$", 2, true);
        HarEntry source = entry(0, "GET", "https://example.test/start");
        source.setResponseContentText("session=first; session=second;");
        HarEntry target = entry(1, "POST", "https://example.test/next");
        target.setPostData(new PostData("application/json", "{\"session\":\"id-second\"}", List.of()));

        List<HarPredefinedCorrelation> matches =
                HarPredefinedCorrelation.find(List.of(source, target), List.of(rule));

        assertEquals(1, matches.size());
        assertEquals("id-second", matches.get(0).getExtractedValue());
        assertEquals(2, matches.get(0).getMatchNumber());
    }

    @Test
    void ignoresRegexWhenTheResponseHasMoreMatchesThanAllowed() {
        Rule rule = new Rule(
                "custom-session", "Custom", "Custom session", "session_id",
                ExtractorType.REGEX, ResponseField.BODY, "session=([^;]+)", "$1$", 3,
                "", false, false, true);
        HarEntry source = entry(0, "GET", "https://example.test/start");
        source.setResponseContentText(
                "session=first;session=second;session=third;session=fourth;session=fifth;");
        HarEntry target = entry(1, "POST", "https://example.test/next");
        target.setPostData(new PostData("application/json", "{\"session\":\"fourth\"}", List.of()));

        assertTrue(HarPredefinedCorrelation.find(List.of(source, target), List.of(rule)).isEmpty());
    }

    @Test
    void selectsNthRegexWhenTheTotalMatchCountIsAllowed() {
        Rule rule = new Rule(
                "custom-session", "Custom", "Custom session", "session_id",
                ExtractorType.REGEX, ResponseField.BODY, "session=([^;]+)", "$1$", 100,
                "", false, false, true);
        HarEntry source = entry(0, "GET", "https://example.test/start");
        source.setResponseContentText(
                "session=first;session=second;session=third;session=fourth;session=fifth;");
        HarEntry target = entry(1, "POST", "https://example.test/next");
        target.setPostData(new PostData("application/json", "{\"session\":\"fourth\"}", List.of()));

        List<HarPredefinedCorrelation> matches =
                HarPredefinedCorrelation.find(List.of(source, target), List.of(rule));

        assertEquals(1, matches.size());
        assertEquals("fourth", matches.get(0).getExtractedValue());
        assertEquals(4, matches.get(0).getMatchNumber());
    }

    @Test
    void ignoresJsonPathWhenTheResponseHasMoreMatchesThanAllowed() {
        Rule rule = new Rule(
                "custom-items", "Custom", "Custom items", "item_id",
                ExtractorType.JSON_PATH, ResponseField.BODY, "$.items[*].id", "", 1,
                "", false, false, true);
        HarEntry source = entry(0, "GET", "https://example.test/start");
        source.setResponseContentText("{\"items\":[{\"id\":\"first-item\"},{\"id\":\"second-item\"}]}");
        HarEntry target = entry(1, "POST", "https://example.test/next");
        target.setPostData(new PostData("application/json", "{\"item\":\"second-item\"}", List.of()));

        assertTrue(HarPredefinedCorrelation.find(List.of(source, target), List.of(rule)).isEmpty());
    }

    @Test
    void discoversJsonPathMatchNumberFromTheLaterRequestValue() {
        Rule rule = new Rule(
                "custom-items", "Custom", "Custom items", "item_id",
                ExtractorType.JSON_PATH, ResponseField.BODY, "$.items[*].id", "", 100,
                "", false, false, true);
        HarEntry source = entry(0, "GET", "https://example.test/start");
        source.setResponseContentText(
                "{\"items\":[{\"id\":\"first-item\"},{\"id\":\"second-item\"},{\"id\":\"third-item\"}]}");
        HarEntry target = entry(1, "POST", "https://example.test/next");
        target.setPostData(new PostData("application/json", "{\"item\":\"third-item\"}", List.of()));

        List<HarPredefinedCorrelation> matches =
                HarPredefinedCorrelation.find(List.of(source, target), List.of(rule));

        assertEquals(1, matches.size());
        assertEquals("third-item", matches.get(0).getExtractedValue());
        assertEquals(3, matches.get(0).getMatchNumber());
    }

    @Test
    void buildExtractorRestoresCustomSettings() {
        Rule regexRule = new Rule(
                "custom-regex", "Custom", "Custom regex", "custom_regex", ExtractorType.REGEX,
                ResponseField.HEADERS, "X-Value: (.+)", "value-$1$",
                "fallback", true, false, false);
        RegexExtractor regex = (RegexExtractor) HarPredefinedCorrelation.buildExtractor(regexRule, 2);
        assertTrue(regex.useHeaders());
        assertEquals(2, regex.getMatchNumber());
        assertEquals("fallback", regex.getDefaultValue());
        assertTrue(regex.isEmptyDefaultValue());
        assertFalse(regex.isFailOnNoMatch());

        Rule jsonRule = new Rule(
                "custom-json", "Custom", "Custom JSON", "custom_json", ExtractorType.JSON_PATH,
                ResponseField.BODY, "$.items[*].id", "",
                "missing", false, true, true);
        JSONPostProcessor json = (JSONPostProcessor) HarPredefinedCorrelation.buildExtractor(jsonRule, 2);
        assertEquals("2", json.getMatchNumbers());
        assertEquals("missing", json.getDefaultValues());
        assertTrue(json.getComputeConcatenation());
        assertTrue(json.isFailOnNoMatch());
    }

    private static Rule regexRule(String id, String variableName, String expression,
            String template, int maxMatches, boolean failOnNoMatch) {
        return new Rule(
                id, "Custom", "Custom session", variableName, ExtractorType.REGEX, ResponseField.BODY,
                expression, template, maxMatches,
                "", false, false, failOnNoMatch);
    }

    private static HarEntry entry(int index, String method, String url) {
        HarEntry entry = new HarEntry();
        entry.setOriginalIndex(index);
        entry.setStartMs(index);
        entry.setEndMs(index);
        entry.setMethod(method);
        entry.setUrl(url);
        entry.setServerIpAddress("127.0.0.1");
        entry.setHasPositiveTiming(true);
        return entry;
    }
}
