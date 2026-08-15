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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

class AiAutoScriptingActionTest {

    @Test
    void copilotToolCallDecorationIsKeptOutOfTheActivityLog() throws Exception {
        // Verbatim from a Copilot CLI run: the CLI draws each tool call as a box.
        String[] decoration = {
            "\u25CF Check HAR exchanges (shell)",
            "\u2502 '{\"level\":\"info\",\"message\":\"Knowledge default/missing.\"}'",
            "\u2502 '{\"threadGroupName\":\"Thread Group\",\"includeStaticAssets\":false}'",
            "\u2514 2 lines\u2026",
            "\u25CF Read key tool schemas (shell)",
            "\u2502 cd \"/Users/x/Downloads/breaktest 2\" && python3 -c \"",
            "\u2502 import json",
            "\u2514 106 lines\u2026",
        };
        for (String line : decoration) {
            assertNull(displayLine(line), "decoration leaked into the log: " + line);
        }
    }

    @Test
    void copilotAgentTextAndErrorsStillReachTheActivityLog() throws Exception {
        assertEquals(
                "Knowledge is default/missing; inspecting plan and HAR evidence.",
                displayLine("Knowledge is default/missing; inspecting plan and HAR evidence."));
        assertEquals("Status: completed", displayLine("Status: completed"));
        // A path or JSON body in agent prose must not be mistaken for decoration.
        assertEquals("Applied 7 actions (0 rolled back).", displayLine("Applied 7 actions (0 rolled back)."));
    }

    @Test
    void geminiStartupDecorationIsKeptOutOfTheActivityLog() throws Exception {
        assertNull(displayLine("GEMINI", "Warning: Basic terminal detected (TERM=dumb)."));
        assertNull(displayLine("GEMINI", "Warning: True color (24-bit) support not detected."));
        assertNull(displayLine("GEMINI", "Warning: 256-color support not detected."));
        assertNull(displayLine("GEMINI", "YOLO mode is enabled. All tool calls will be automatically approved."));
        assertEquals("GEMINI_HARNESS_OK", displayLine("GEMINI", "GEMINI_HARNESS_OK"));
    }

    private static String displayLine(String rawLine) throws Exception {
        return displayLine("COPILOT", rawLine);
    }

    private static String displayLine(String toolName, String rawLine) throws Exception {
        Class<?> filterClass = null;
        for (Class<?> candidate : AiAutoScriptingAction.class.getDeclaredClasses()) {
            if ("AiOutputFilter".equals(candidate.getSimpleName())) {
                filterClass = candidate;
            }
        }
        Class<?> toolClass = null;
        for (Class<?> candidate : AiAutoScriptingAction.class.getDeclaredClasses()) {
            if ("AiTool".equals(candidate.getSimpleName())) {
                toolClass = candidate;
            }
        }
        Object selectedTool = null;
        for (Object tool : toolClass.getEnumConstants()) {
            if (toolName.equals(((Enum<?>) tool).name())) {
                selectedTool = tool;
            }
        }
        Constructor<?> constructor = filterClass.getDeclaredConstructor(toolClass);
        constructor.setAccessible(true);
        Object filter = constructor.newInstance(selectedTool);
        Method method = filterClass.getDeclaredMethod("displayLine", String.class);
        method.setAccessible(true);
        return (String) method.invoke(filter, rawLine);
    }

    @Test
    void explicitBlockedStatusIsNotReportedAsSuccess() throws Exception {
        assertTrue(hasRepairBlocker("Status: blocked"));
        assertTrue(hasRepairBlocker("Status: Blocked by a BreakTest GUI bridge failure."));
    }

    @Test
    void recoveryAndValidationFailuresAreRepairBlockers() throws Exception {
        assertTrue(hasRepairBlocker("The GUI plan could not be restored or validated."));
        assertTrue(hasRepairBlocker("Stopped after a GUI bridge failure."));
    }

    @Test
    void completedGreenStatusIsNotARepairBlocker() throws Exception {
        assertFalse(hasRepairBlocker("Status: completed", "Final validation is green."));
    }

    @Test
    void fileBackedRepairTargetsActivePlanInsteadOfBackupOrClone() {
        assertEquals("", AiAutoScriptingAction.repairTargetPath(false, "/plans/current.jmx"));
        assertEquals(new java.io.File("/plans/current.jmx").getAbsolutePath(),
                AiAutoScriptingAction.repairTargetPath(true, "/plans/current.jmx"));
    }

    @Test
    void piCommandUsesNonInteractiveEphemeralModeAndConfiguredEngine() throws Exception {
        Properties properties = jmeterProperties();
        String[] keys = {
            "breaktest.pi.command",
            "breaktest.pi.provider",
            "breaktest.pi.model",
            "breaktest.pi.thinking"
        };
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            previous[i] = properties.getProperty(keys[i]);
        }
        try {
            JMeterUtils.setProperty(keys[0], "pi-test");
            JMeterUtils.setProperty(keys[1], "local-provider");
            JMeterUtils.setProperty(keys[2], "local-model");
            JMeterUtils.setProperty(keys[3], "high");

            Object request = newRunRequest("PI");
            Class<?> requestClass = request.getClass();
            Method method = AiAutoScriptingAction.class.getDeclaredMethod("piCommand", requestClass);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> command = (List<String>) method.invoke(null, request);

            assertEquals(List.of(
                    "pi-test",
                    "--print",
                    "--approve",
                    "--no-session",
                    "--mode",
                    "text",
                    "--provider",
                    "local-provider",
                    "--model",
                    "local-model",
                    "--thinking",
                    "high"), command.subList(0, command.size() - 1));
            assertTrue(command.get(command.size() - 1).contains("Pi Code"));
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) {
                    properties.remove(keys[i]);
                } else {
                    properties.setProperty(keys[i], previous[i]);
                }
            }
        }
    }

    @Test
    void geminiCommandUsesTrustedHeadlessTextModeWithoutHandlingApiKeys() throws Exception {
        Properties properties = jmeterProperties();
        String[] keys = {
            "breaktest.gemini.command",
            "breaktest.gemini.model",
            "breaktest.gemini.approval",
            "breaktest.gemini.sandbox"
        };
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            previous[i] = properties.getProperty(keys[i]);
        }
        try {
            JMeterUtils.setProperty(keys[0], "gemini-test");
            JMeterUtils.setProperty(keys[1], "gemini-test-model");
            JMeterUtils.setProperty(keys[2], "auto_edit");
            JMeterUtils.setProperty(keys[3], "true");

            Object request = newRunRequest("GEMINI");
            Class<?> requestClass = request.getClass();
            Method method = AiAutoScriptingAction.class.getDeclaredMethod("geminiCommand", requestClass);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> command = (List<String>) method.invoke(null, request);

            assertEquals(List.of(
                    "gemini-test",
                    "--skip-trust",
                    "--approval-mode",
                    "auto_edit",
                    "--sandbox=true",
                    "--output-format",
                    "text",
                    "--model",
                    "gemini-test-model",
                    "--prompt"), command.subList(0, command.size() - 1));
            assertTrue(command.get(command.size() - 1).contains("Gemini CLI"));
            assertFalse(command.stream().anyMatch(value -> value.contains("GEMINI_API_KEY")));
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) {
                    properties.remove(keys[i]);
                } else {
                    properties.setProperty(keys[i], previous[i]);
                }
            }
        }
    }

    @Test
    void geminiCommandLeavesModelSelectionToCliByDefault() throws Exception {
        Properties properties = jmeterProperties();
        String previous = properties.getProperty("breaktest.gemini.model");
        try {
            properties.remove("breaktest.gemini.model");

            Object request = newRunRequest("GEMINI");
            Method method = AiAutoScriptingAction.class.getDeclaredMethod("geminiCommand", request.getClass());
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> command = (List<String>) method.invoke(null, request);

            assertFalse(command.contains("--model"));
        } finally {
            if (previous == null) {
                properties.remove("breaktest.gemini.model");
            } else {
                properties.setProperty("breaktest.gemini.model", previous);
            }
        }
    }

    private static Properties jmeterProperties() throws Exception {
        Properties properties = JMeterUtils.getJMeterProperties();
        if (properties != null) {
            return properties;
        }
        Path emptyProperties = Files.createTempFile("breaktest-ai-command", ".properties");
        try {
            JMeterUtils.loadJMeterProperties(emptyProperties.toString());
            return JMeterUtils.getJMeterProperties();
        } finally {
            Files.deleteIfExists(emptyProperties);
        }
    }

    private static Object newRunRequest(String toolName) throws Exception {
        Class<?> requestClass = nestedClass("AiRunRequest");
        Class<?> toolClass = nestedClass("AiTool");
        Class<?> modeClass = nestedClass("AiRunMode");
        Class<?> editSurfaceClass = nestedClass("AiEditSurface");
        Object tool = enumConstant(toolClass, toolName);
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : requestClass.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 8) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) {
            throw new IllegalStateException("Missing eight-argument AiRunRequest constructor");
        }
        constructor.setAccessible(true);
        return constructor.newInstance(
                tool,
                null,
                modeClass.getEnumConstants()[0],
                editSurfaceClass.getEnumConstants()[0],
                false,
                60,
                0,
                "");
    }

    private static Class<?> nestedClass(String simpleName) {
        for (Class<?> candidate : AiAutoScriptingAction.class.getDeclaredClasses()) {
            if (simpleName.equals(candidate.getSimpleName())) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Missing nested class " + simpleName);
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        for (Object constant : enumClass.getEnumConstants()) {
            if (name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Missing enum constant " + name);
    }

    private static boolean hasRepairBlocker(String... lines) throws Exception {
        Class<?> outputClass = Class.forName(AiAutoScriptingAction.class.getName() + "$AiRunOutput");
        Constructor<?> constructor = outputClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object output = constructor.newInstance();

        Method capture = outputClass.getDeclaredMethod("captureFinalResponse", String.class);
        capture.setAccessible(true);
        for (String line : lines) {
            capture.invoke(output, line);
        }

        Method hasRepairBlocker = outputClass.getDeclaredMethod("hasRepairBlocker");
        hasRepairBlocker.setAccessible(true);
        return (boolean) hasRepairBlocker.invoke(output);
    }
}
