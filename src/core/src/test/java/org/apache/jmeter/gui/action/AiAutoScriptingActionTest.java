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

    private static String displayLine(String rawLine) throws Exception {
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
        Object copilot = null;
        for (Object tool : toolClass.getEnumConstants()) {
            if ("COPILOT".equals(((Enum<?>) tool).name())) {
                copilot = tool;
            }
        }
        Constructor<?> constructor = filterClass.getDeclaredConstructor(toolClass);
        constructor.setAccessible(true);
        Object filter = constructor.newInstance(copilot);
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
