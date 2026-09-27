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

package org.apache.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Jsr223AiHelperTest {

    @Test
    void scriptFileMatchesTheScriptLanguage() {
        assertEquals("script.groovy", Jsr223AiHelper.scriptFileName("groovy"));
        assertEquals("script.js", Jsr223AiHelper.scriptFileName("javascript"));
        assertEquals("script.java", Jsr223AiHelper.scriptFileName("java"));
        assertEquals("script.txt", Jsr223AiHelper.scriptFileName("jexl3"));
    }

    @Test
    void promptPointsTheAgentAtTheScriptFileInsteadOfInliningTheScript() {
        Jsr223AiHelper.ScriptContext context = new Jsr223AiHelper.ScriptContext(
                "vars.put('unique-script-body', '1')", 0, 0, "", "JSR223 PreProcessor", "groovy", "Add logging");

        String prompt = Jsr223AiHelper.prompt(context, "script.groovy");

        assertTrue(prompt.contains("The current script is in the file script.groovy"));
        assertTrue(prompt.contains("Add logging"));
        assertFalse(prompt.contains("unique-script-body"), "The script is read from the file, not the prompt");
    }
}
