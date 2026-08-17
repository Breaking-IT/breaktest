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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.StringUtilities;
import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.Perl5Compiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Loads and stores the shared, user, and plan-local predefined correlation catalogs. */
final class HarCorrelationRuleCatalog {

    static final String FORMAT = "breaktest-predefined-correlations-v1";
    static final String CUSTOM_ARCHIVE_ENTRY = "correlations/custom-predefined-rules.json";
    static final String CUSTOM_FILE_PROPERTY = "breaktest.predefined_correlations.file";
    static final String DEFAULT_CUSTOM_FILE = "predefined-correlations.custom.json";

    private static final Logger LOG = LoggerFactory.getLogger(HarCorrelationRuleCatalog.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUILT_IN_RESOURCE =
            "/org/apache/jmeter/protocol/http/har/predefined-correlation-rules.json";
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final int MAX_RULES = 1000;
    private static final int MAX_GROUPS = 100;
    private static final int MAX_MATCHES = 100_000;
    private static final int MAX_EXPRESSION_LENGTH = 16 * 1024;
    private static final int MAX_GROUP_LENGTH = 100;
    private static final List<Rule> BUILT_IN_RULES = loadBuiltInRules();

    private HarCorrelationRuleCatalog() {
    }

    static List<Rule> builtInRules() {
        return BUILT_IN_RULES;
    }

    static List<Rule> sharedRules() {
        return merge(BUILT_IN_RULES, loadConfiguredCustomRules());
    }

    static List<Rule> rulesFor(JMeterTreeNode contextNode) {
        TestElement testPlan = findTestPlan(contextNode);
        return rulesFor(testPlan);
    }

    static List<Rule> rulesFor(TestElement testPlan) {
        return testPlan == null ? sharedRules() : merge(sharedRules(), customRules(testPlan));
    }

    static List<Rule> customRules(TestElement testPlan) {
        try {
            return loadCustomRules(testPlan);
        } catch (IOException ex) {
            LOG.warn("Unable to load custom predefined correlation rules", ex);
            return List.of();
        }
    }

    static Set<String> customRuleIds(TestElement testPlan) {
        Set<String> ids = new LinkedHashSet<>();
        loadConfiguredCustomRules().stream().map(Rule::getId).forEach(ids::add);
        customRules(testPlan).stream().map(Rule::getId).forEach(ids::add);
        return Set.copyOf(ids);
    }

    static List<Rule> loadCustomRules(TestElement testPlan) throws IOException {
        if (testPlan == null) {
            return List.of();
        }
        String entryName = testPlan.getPropertyAsString(
                JmxArchiveEntryStore.CORRELATION_RULES_FILENAME_PROPERTY);
        String checksum = testPlan.getPropertyAsString(
                JmxArchiveEntryStore.CORRELATION_RULES_CHECKSUM_PROPERTY);
        if (StringUtilities.isEmpty(entryName)) {
            return List.of();
        }
        Optional<byte[]> content = JmxArchiveEntryStore.find(entryName, checksum);
        if (content.isEmpty()) {
            throw new IOException("Custom predefined correlation rules are unavailable: " + entryName);
        }
        return parse(content.orElseThrow());
    }

    static void storeCustomRules(TestElement testPlan, List<Rule> rules) throws IOException {
        byte[] content = serialize(rules);
        parse(content);
        String checksum = sha256(content);
        JmxArchiveEntryStore.register(CUSTOM_ARCHIVE_ENTRY, checksum, content);
        testPlan.setProperty(
                JmxArchiveEntryStore.CORRELATION_RULES_FILENAME_PROPERTY, CUSTOM_ARCHIVE_ENTRY);
        testPlan.setProperty(
                JmxArchiveEntryStore.CORRELATION_RULES_CHECKSUM_PROPERTY, checksum);
    }

    static void storeCustomRulesEverywhere(TestElement testPlan, List<Rule> newRules) throws IOException {
        List<Rule> sharedCustomRules = merge(loadConfiguredCustomRulesStrict(), newRules);
        List<Rule> planCustomRules = merge(loadCustomRules(testPlan), newRules);
        byte[] sharedContent = serialize(sharedCustomRules);
        byte[] planContent = serialize(planCustomRules);
        parse(sharedContent);
        parse(planContent);
        storeConfiguredCustomRules(sharedContent);
        storeCustomRules(testPlan, planCustomRules);
    }

    @SafeVarargs
    static List<Rule> merge(List<Rule>... catalogs) {
        Map<String, Rule> byId = new LinkedHashMap<>();
        for (List<Rule> catalog : catalogs) {
            for (Rule rule : catalog) {
                byId.put(rule.getId(), rule);
            }
        }
        return List.copyOf(byId.values());
    }

    static List<Rule> parse(byte[] content) throws IOException {
        if (content == null || content.length == 0 || content.length > MAX_CONFIG_BYTES) {
            throw new IOException("Predefined correlation config must contain 1 to "
                    + MAX_CONFIG_BYTES + " bytes");
        }
        JsonNode root = JSON.readTree(content);
        if (!FORMAT.equals(root.path("format").asText())) {
            throw new IOException("Unsupported predefined correlation config format");
        }
        JsonNode groupsNode = root.get("groups");
        JsonNode legacyRulesNode = root.get("rules");
        if (groupsNode != null && legacyRulesNode != null) {
            throw new IOException("Predefined correlation config cannot contain both groups and rules");
        }
        List<Rule> rules = new ArrayList<>();
        Map<String, Rule> byId = new LinkedHashMap<>();
        if (groupsNode != null) {
            if (!groupsNode.isArray() || groupsNode.size() > MAX_GROUPS) {
                throw new IOException("Predefined correlation config must contain at most "
                        + MAX_GROUPS + " groups");
            }
            Map<String, Boolean> groupNames = new LinkedHashMap<>();
            for (JsonNode groupNode : groupsNode) {
                String group = requiredText(groupNode, "name");
                validateGroup(group, "group " + group);
                if (groupNames.putIfAbsent(group, Boolean.TRUE) != null) {
                    throw new IOException("Duplicate predefined correlation group: " + group);
                }
                JsonNode rulesNode = groupNode.path("rules");
                if (!rulesNode.isArray()) {
                    throw new IOException("Predefined correlation group must contain a rules array: " + group);
                }
                for (JsonNode ruleNode : rulesNode) {
                    if (ruleNode.has("group")) {
                        throw new IOException("Grouped predefined correlation rule must not repeat its group");
                    }
                    addRule(rules, byId, parseRule(ruleNode, group));
                }
            }
        } else {
            if (legacyRulesNode == null || !legacyRulesNode.isArray()) {
                throw new IOException("Predefined correlation config must contain a groups array");
            }
            for (JsonNode ruleNode : legacyRulesNode) {
                addRule(rules, byId, parseRule(ruleNode, ruleNode.path("group").asText("Custom").trim()));
            }
        }
        if (rules.size() > MAX_RULES) {
            throw new IOException("Predefined correlation config must contain at most " + MAX_RULES + " rules");
        }
        return List.copyOf(rules);
    }

    static byte[] serialize(List<Rule> rules) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        ArrayNode groupsNode = root.putArray("groups");
        Map<String, List<Rule>> groupedRules = new LinkedHashMap<>();
        for (Rule rule : rules) {
            groupedRules.computeIfAbsent(rule.getGroup(), ignored -> new ArrayList<>()).add(rule);
        }
        for (Map.Entry<String, List<Rule>> group : groupedRules.entrySet()) {
            ObjectNode groupNode = groupsNode.addObject();
            groupNode.put("name", group.getKey());
            ArrayNode rulesNode = groupNode.putArray("rules");
            for (Rule rule : group.getValue()) {
                ObjectNode ruleNode = rulesNode.addObject();
                ruleNode.put("id", rule.getId());
                ruleNode.put("name", rule.getName());
                ruleNode.put("variableName", rule.getVariableName());
                ruleNode.put("extractorType", rule.getExtractorType().name());
                ruleNode.put("responseField", rule.getResponseField().name());
                ruleNode.put("expression", rule.getExpression());
                ruleNode.put("template", rule.getTemplate());
                ruleNode.put("maxMatches", rule.getMaxMatches());
                ruleNode.put("defaultValue", rule.getDefaultValue());
                ruleNode.put("emptyDefaultValue", rule.isEmptyDefaultValue());
                ruleNode.put("computeConcatenation", rule.isComputeConcatenation());
                ruleNode.put("failOnNoMatch", rule.isFailOnNoMatch());
            }
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private static void addRule(List<Rule> rules, Map<String, Rule> byId, Rule rule) throws IOException {
        if (byId.putIfAbsent(rule.getId(), rule) != null) {
            throw new IOException("Duplicate predefined correlation rule id: " + rule.getId());
        }
        rules.add(rule);
    }

    private static Rule parseRule(JsonNode node, String group) throws IOException {
        String id = requiredText(node, "id");
        String name = requiredText(node, "name");
        String variableName = requiredText(node, "variableName");
        String expression = requiredText(node, "expression");
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Invalid predefined correlation rule id: " + id);
        }
        if (!variableName.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            throw new IOException("Invalid predefined correlation variable name: " + variableName);
        }
        validateGroup(group, "rule " + id);
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IOException("Predefined correlation expression is too long: " + id);
        }
        ExtractorType extractorType = enumValue(
                ExtractorType.class, requiredText(node, "extractorType"), "extractorType", id);
        ResponseField responseField = enumValue(
                ResponseField.class, node.path("responseField").asText("BODY"), "responseField", id);
        if (extractorType == ExtractorType.JSON_PATH && responseField != ResponseField.BODY) {
            throw new IOException("JSON_PATH rule must use BODY: " + id);
        }
        String template = node.path("template").asText(extractorType == ExtractorType.REGEX ? "$1$" : "");
        int maxMatches = node.path("maxMatches").asInt(1);
        if (maxMatches <= 0 || maxMatches > MAX_MATCHES) {
            throw new IOException("Predefined correlation maxMatches must be between 1 and "
                    + MAX_MATCHES + ": " + id);
        }
        if (extractorType == ExtractorType.REGEX) {
            try {
                new Perl5Compiler().compile(expression);
            } catch (MalformedPatternException ex) {
                throw new IOException("Invalid JMeter regular expression for rule " + id, ex);
            }
        }
        return new Rule(
                id, group, name, variableName, extractorType, responseField, expression, template,
                maxMatches,
                node.path("defaultValue").asText(""), node.path("emptyDefaultValue").asBoolean(false),
                node.path("computeConcatenation").asBoolean(false),
                node.path("failOnNoMatch").asBoolean(true));
    }

    private static void validateGroup(String group, String context) throws IOException {
        if (group.isEmpty() || group.length() > MAX_GROUP_LENGTH || group.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Invalid predefined correlation group for " + context);
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IOException("Predefined correlation rule is missing " + field);
        }
        return value;
    }

    private static <T extends Enum<T>> T enumValue(
            Class<T> type, String value, String field, String ruleId) throws IOException {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid " + field + " for predefined correlation rule " + ruleId, ex);
        }
    }

    private static List<Rule> loadBuiltInRules() {
        try (InputStream input = HarCorrelationRuleCatalog.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Built-in predefined correlation config is missing");
            }
            return parse(input.readAllBytes());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load built-in predefined correlation config", ex);
        }
    }

    private static List<Rule> loadConfiguredCustomRules() {
        try {
            return loadConfiguredCustomRulesStrict();
        } catch (IOException | RuntimeException ex) {
            Path customFile = configuredCustomFile();
            LOG.warn("Unable to load custom predefined correlation file {}", customFile, ex);
            return List.of();
        }
    }

    private static List<Rule> loadConfiguredCustomRulesStrict() throws IOException {
        Path customFile = configuredCustomFile();
        if (!Files.exists(customFile)) {
            return List.of();
        }
        if (!Files.isRegularFile(customFile)) {
            throw new IOException("Custom predefined correlation path is not a regular file: " + customFile);
        }
        long size = Files.size(customFile);
        if (size <= 0 || size > MAX_CONFIG_BYTES) {
            throw new IOException("Custom predefined correlation file size must be between 1 and "
                    + MAX_CONFIG_BYTES + " bytes");
        }
        return parse(Files.readAllBytes(customFile));
    }

    private static void storeConfiguredCustomRules(byte[] content) throws IOException {
        Path customFile = configuredCustomFile().toAbsolutePath().normalize();
        Path parent = customFile.getParent();
        if (parent == null) {
            throw new IOException("Custom predefined correlation file has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, customFile.getFileName().toString(), ".tmp");
        try {
            Files.write(temporaryFile, content);
            try {
                Files.move(temporaryFile, customFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporaryFile, customFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static Path configuredCustomFile() {
        String configured = JMeterUtils.getPropDefault(CUSTOM_FILE_PROPERTY, "").trim();
        String binDirectory = JMeterUtils.getJMeterBinDir();
        if (configured.isEmpty()) {
            return binDirectory == null
                    ? Path.of(DEFAULT_CUSTOM_FILE) : Path.of(binDirectory, DEFAULT_CUSTOM_FILE);
        }
        Path path = Path.of(configured);
        return path.isAbsolute() || binDirectory == null
                ? path : Path.of(binDirectory).resolve(path).normalize();
    }

    private static TestElement findTestPlan(JMeterTreeNode contextNode) {
        JMeterTreeNode current = contextNode;
        while (current != null) {
            if (current.getTestElement() instanceof TestPlan) {
                return current.getTestElement();
            }
            current = current.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return null;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
