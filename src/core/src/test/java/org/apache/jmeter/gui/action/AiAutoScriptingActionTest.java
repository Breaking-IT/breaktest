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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class AiAutoScriptingActionTest {

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
