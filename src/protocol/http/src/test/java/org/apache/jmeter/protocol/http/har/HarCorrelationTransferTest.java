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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarCorrelationTransferTest extends JMeterTestCase {
    @TempDir
    Path directory;
    private String previousCatalog;

    @BeforeEach
    void isolateCatalog() {
        previousCatalog = JMeterUtils.getProperty(HarCorrelationRuleCatalog.CUSTOM_FILE_PROPERTY);
        useCatalog(directory.resolve("isolated.json"));
    }

    @AfterEach
    void restoreCatalog() {
        if (previousCatalog == null) {
            JMeterUtils.getJMeterProperties().remove(HarCorrelationRuleCatalog.CUSTOM_FILE_PROPERTY);
        } else {
            JMeterUtils.setProperty(HarCorrelationRuleCatalog.CUSTOM_FILE_PROPERTY, previousCatalog);
        }
    }

    @Test
    void exportToAnotherCatalogMergesOverridesAndSurvivesPlanReload() throws Exception {
        Path sourceCatalog = directory.resolve("source/catalog.json");
        useCatalog(sourceCatalog);
        Rule regex = rule("company-session", "session=([^;]+)");
        Rule json = new Rule("company-json", "Company", "JSON token", "json_token",
                ExtractorType.JSON_PATH, ResponseField.BODY, "$.token", "", 8,
                "missing", false, true, true);
        TestPlan source = new TestPlan("Source");
        HarCorrelationRuleCatalog.storeCustomRulesEverywhere(source, List.of(regex, json));
        byte[] sourceBefore = Files.readAllBytes(sourceCatalog);
        Path exported = directory.resolve("transfer/rules.json");
        HarCorrelationRuleCatalog.writeRulesFile(exported, HarCorrelationRuleCatalog.loadCustomRules(source));

        Path destinationCatalog = directory.resolve("destination/catalog.json");
        useCatalog(destinationCatalog);
        TestPlan destination = new TestPlan("Destination");
        destination.setProperty(org.apache.jmeter.testelement.TestElement.TEST_CLASS, TestPlan.class.getName());
        destination.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS,
                org.apache.jmeter.control.gui.TestPlanGui.class.getName());
        Rule oldVersion = rule("company-session", "old=([^;]+)");
        Rule unrelated = rule("keep-me", "keep=([^;]+)");
        HarCorrelationRuleCatalog.storeCustomRulesEverywhere(destination, List.of(oldVersion, unrelated));
        List<Rule> imported = HarCorrelationRuleCatalog.readRulesFile(exported);
        HarCorrelationRuleCatalog.storeCustomRulesEverywhere(destination, imported);
        // Repeated imports replace by id rather than creating duplicates.
        HarCorrelationRuleCatalog.storeCustomRulesEverywhere(destination, imported);
        List<Rule> expected = List.of(regex, unrelated, json);
        assertArrayEquals(HarCorrelationRuleCatalog.serialize(expected),
                HarCorrelationRuleCatalog.serialize(HarCorrelationRuleCatalog.readRulesFile(destinationCatalog)));
        assertArrayEquals(sourceBefore, Files.readAllBytes(sourceCatalog));

        Path jmx = directory.resolve("destination.jmx");
        HashTree tree = new HashTree();
        tree.add(destination);
        SaveService.saveTreeToFile(tree, jmx);
        // The archive itself must contain the rules, independent of a shared catalog.
        try (var zip = new java.util.zip.ZipFile(jmx.toFile())) {
            var entry = zip.getEntry(HarCorrelationRuleCatalog.CUSTOM_ARCHIVE_ENTRY);
            try (var input = zip.getInputStream(entry)) {
                assertArrayEquals(HarCorrelationRuleCatalog.serialize(expected), input.readAllBytes());
            }
        }
        useCatalog(directory.resolve("third-installation/missing.json"));
        TestPlan reloaded = (TestPlan) SaveService.loadTree(jmx.toFile()).getArray()[0];
        List<Rule> restored = HarCorrelationRuleCatalog.loadCustomRules(reloaded);
        assertArrayEquals(HarCorrelationRuleCatalog.serialize(expected), HarCorrelationRuleCatalog.serialize(restored));

        // Exercise the transferred regex through the real correlation discovery engine.
        HarEntry response = new HarEntry();
        response.setOriginalIndex(0);
        response.setMethod("GET");
        response.setUrl("https://example.test/start");
        response.setResponseContentText("session=transferred-token;");
        HarEntry request = new HarEntry();
        request.setOriginalIndex(1);
        request.setMethod("POST");
        request.setUrl("https://example.test/next");
        request.setPostData(new HarEntry.PostData("text/plain", "transferred-token", List.of()));
        assertFalse(HarPredefinedCorrelation.find(List.of(response, request), restored).isEmpty());
    }

    @Test
    void rejectsInvalidImportsWithoutChangingDestination() throws Exception {
        Path catalog = directory.resolve("isolated.json");
        TestPlan plan = new TestPlan("Existing");
        HarCorrelationRuleCatalog.storeCustomRulesEverywhere(plan, List.of(rule("existing", "value=(.+)")));
        byte[] before = Files.readAllBytes(catalog);
        byte[] planBefore = HarCorrelationRuleCatalog.serialize(HarCorrelationRuleCatalog.loadCustomRules(plan));
        Path incoming = directory.resolve("incoming.json");
        for (byte[] invalid : List.of(new byte[0], "not JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "{\"format\":\"unsupported\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[1024 * 1024 + 1])) {
            Files.write(incoming, invalid);
            assertThrows(IOException.class, () -> HarCorrelationRuleCatalog.storeCustomRulesEverywhere(
                    plan, HarCorrelationRuleCatalog.readRulesFile(incoming)));
            assertArrayEquals(before, Files.readAllBytes(catalog));
            assertArrayEquals(planBefore,
                    HarCorrelationRuleCatalog.serialize(HarCorrelationRuleCatalog.loadCustomRules(plan)));
        }
        assertThrows(IOException.class, () -> HarCorrelationRuleCatalog.readRulesFile(directory));
        assertThrows(IOException.class, () -> HarCorrelationRuleCatalog.readRulesFile(directory.resolve("missing.json")));
    }

    @Test
    void replacingExportDoesNotAppendOrLeaveTemporaryFiles() throws Exception {
        Path exported = directory.resolve("export.json");
        HarCorrelationRuleCatalog.writeRulesFile(exported, List.of(rule("old", "old=(.+)")));
        HarCorrelationRuleCatalog.writeRulesFile(exported, List.of(rule("new", "new=(.+)")));
        assertEquals(List.of("new"), HarCorrelationRuleCatalog.readRulesFile(exported).stream().map(Rule::getId).toList());
        try (var files = Files.list(directory)) {
            assertEquals(List.of("export.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void swingButtonsExportOnlyCustomRulesAndImportIntoAnotherPanel() throws Exception {
        Path transfer = directory.resolve("button-export.json");
        Rule builtIn = rule("built-in", "built=(.+)");
        Rule custom = rule("custom", "custom=(.+)");
        AtomicInteger exports = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            HarCorrelationRulesPanel.RuleTransfer fileTransfer = new HarCorrelationRulesPanel.RuleTransfer() {
                @Override
                public List<Rule> importRules() {
                    try {
                        return HarCorrelationRuleCatalog.readRulesFile(transfer);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }

                @Override
                public void exportRules(List<Rule> rules) {
                    try {
                        HarCorrelationRuleCatalog.writeRulesFile(transfer, rules);
                        exports.incrementAndGet();
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            };
            HarCorrelationRulesPanel source = new HarCorrelationRulesPanel(
                    List.of(builtIn, custom), Set.of("custom"), null, fileTransfer);
            button(source, "try_predefined_correlations_export_custom").doClick();
            HarCorrelationRulesPanel target = new HarCorrelationRulesPanel(
                    List.of(builtIn), Set.of(), null, fileTransfer);
            assertFalse(button(target, "try_predefined_correlations_export_custom").isEnabled());
            button(target, "try_predefined_correlations_import_custom").doClick();
            button(target, "try_predefined_correlations_import_custom").doClick();
            assertTrue(button(target, "try_predefined_correlations_export_custom").isEnabled());
            assertEquals(List.of("custom"), target.getCustomRules().stream().map(Rule::getId).toList());
            target.setCustomOnly(true);
            assertEquals(List.of("Company > custom"), target.getRulePaths());
            target.setCustomOnly(false);
            assertEquals(2, target.getRulePaths().size());
            button(target, "try_predefined_correlations_export_custom").doClick();
        });
        assertEquals(2, exports.get());
        assertEquals(List.of("custom"), HarCorrelationRuleCatalog.readRulesFile(transfer).stream().map(Rule::getId).toList());
    }

    private static JButton button(Container container, String key) {
        String label = JMeterUtils.getResString(key);
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && label.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = button(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Rule rule(String id, String expression) {
        return new Rule(id, "Company", id, id + "_value", ExtractorType.REGEX,
                ResponseField.BODY, expression, "$1$", 4, "fallback", false, false, false);
    }

    private static void useCatalog(Path file) {
        JMeterUtils.setProperty(HarCorrelationRuleCatalog.CUSTOM_FILE_PROPERTY, file.toString());
    }
}
