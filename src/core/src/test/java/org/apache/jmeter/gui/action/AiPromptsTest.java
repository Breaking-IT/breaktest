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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiPromptsTest {

    /** Every token any shipped template may reference. */
    private static final Map<String, String> ALL_TOKENS = Map.ofEntries(
            Map.entry("BRIDGE", "/opt/breaktest/bin/breaktest-agent-tool"),
            Map.entry("BRIDGE_CALL", "run the bridge with JSON arguments"),
            Map.entry("START_ACTIVITY_INSTRUCTION", "First call agent_activity"),
            Map.entry("AGENT_BOOTSTRAP", "Use the bundled bridge"),
            Map.entry("THREAD_GROUP_NAME", "Checkout"),
            Map.entry("THREAD_GROUP_PATH", "Test Plan > Checkout"),
            Map.entry("RUN_OPTIONS", "- run options"),
            Map.entry("TEST_PLAN_FILE", "/plans/shop.jmx"),
            Map.entry("BACKUP_PATH", "/plans/shop.jmx.bak"),
            Map.entry("USER_INSTRUCTIONS", "- user instructions"),
            Map.entry("TASK_SCOPE", "task scope"),
            Map.entry("REPAIR_TARGET", "/plans/shop.repair.jmx"),
            Map.entry("REPAIR_SUMMARY_PATH", "/plans/shop.repair.jmx.ai-summary.json"),
            Map.entry("TOOL", "Copilot CLI"),
            Map.entry("MODE", "Full script repair"),
            Map.entry("EDIT_SURFACE", "Live GUI"),
            Map.entry("ADD_ASSERTIONS", "yes"),
            Map.entry("MAX_RUNTIME_SECONDS", "900"),
            Map.entry("MAX_SIMILAR_RETRIES", "3"),
            Map.entry("EXTRA_INSTRUCTIONS", "(none provided)"),
            Map.entry("ASSERTION_INSTRUCTION", "- assertion instruction"));

    @ParameterizedTest
    @ValueSource(strings = {
        AiPrompts.LIVE_GUI_REPAIR,
        AiPrompts.SPECIFIC_REQUEST,
        AiPrompts.FILE_BACKED_REPAIR,
        AiPrompts.USER_INSTRUCTIONS,
        AiPrompts.RUN_OPTIONS,
    })
    void everyTemplateRendersWithoutUnresolvedTokens(String template) {
        String rendered = AiPrompts.render(template, ALL_TOKENS);
        assertFalse(rendered.isBlank(), template + " rendered empty");
        assertFalse(rendered.contains("{{"), () -> template + " left an unresolved token: " + firstToken(rendered));
        // The templates are no longer String.format inputs; stray specifiers would be a bad port.
        assertFalse(rendered.contains("%s") || rendered.contains("%d"),
                template + " still contains a format specifier");
    }

    @Test
    void repairPromptKeepsOroRegexAndEncodingExamplesIntact() {
        String rendered = AiPrompts.render(AiPrompts.LIVE_GUI_REPAIR, ALL_TOKENS);
        // Single-backslash escapes and the literal percent-encoding example are easy to lose when
        // prompt text moves between Java text blocks and resource files.
        assertTrue(rendered.contains("\\Q...\\E"), "lost the ORO quoting example");
        assertTrue(rendered.contains("\"pageId\"\\s*:\\s*\"([^\"]+)\""), "lost the regex example");
        assertTrue(rendered.contains("@ appears as %40"), "lost the percent-encoding example");
    }

    @Test
    void allFragmentsResolve() {
        for (String key : new String[] {
            "taskScope.fullRepair", "taskScope.specificRequest",
            "assertions.enabled", "assertions.disabled",
        }) {
            assertFalse(AiPrompts.fragment(key).isBlank(), "missing fragment " + key);
        }
        assertEquals("", AiPrompts.fragment("no.such.fragment"));
    }

    @Test
    void replacementValuesAreInsertedLiterally() {
        Map<String, String> tokens = new HashMap<>(ALL_TOKENS);
        // Backslashes and dollars are legal in Windows paths and in user instructions; they must
        // not be interpreted as regex replacement syntax.
        tokens.put("BACKUP_PATH", "C:\\plans\\$1 backup.jmx");
        String rendered = AiPrompts.render(AiPrompts.LIVE_GUI_REPAIR, tokens);
        assertTrue(rendered.contains("C:\\plans\\$1 backup.jmx"), "replacement value was mangled");
    }

    @Test
    void unknownTokensAreLeftAloneRatherThanFailingTheRun() {
        assertTrue(AiPrompts.render(AiPrompts.RUN_OPTIONS, Map.of()).contains("{{MAX_RUNTIME_SECONDS}}"));
    }

    private static String firstToken(String rendered) {
        int start = rendered.indexOf("{{");
        return rendered.substring(start, Math.min(start + 40, rendered.length()));
    }
}
