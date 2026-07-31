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

package org.apache.jmeter.ai.gui

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/**
 * A recording is linked to samplers as request/response exchanges; HAR is only one
 * import format. Nothing the agent reads should say HAR, or it repeats the term back
 * in its own progress and in the AI Knowledge it writes.
 */
class RecordedExchangeToolNamingTest {

    private val renamedTools = listOf(
        "list_recorded_exchanges_open_plan",
        "get_recorded_exchange_open_plan",
        "search_recorded_exchanges_open_plan",
        "audit_recorded_correlations_open_plan",
    )

    private val legacyTools = listOf(
        "list_recorded_har_exchanges_open_plan",
        "get_recorded_har_exchange_open_plan",
        "search_recorded_har_open_plan",
        "audit_recorded_har_correlations_open_plan",
    )

    /** Routes the tool without a GUI; anything but "Unknown GUI agent tool" means it dispatched. */
    private fun isDispatched(tool: String): Boolean {
        val method = BreakTestAgentGuiService::class.java
            .getDeclaredMethod("handleTool", String::class.java, com.fasterxml.jackson.databind.JsonNode::class.java)
        method.isAccessible = true
        return try {
            method.invoke(BreakTestAgentGuiService, tool, ObjectMapper().createObjectNode())
            true
        } catch (failure: InvocationTargetException) {
            failure.cause?.message?.startsWith("Unknown GUI agent tool") != true
        }
    }

    @Test
    fun `renamed tools dispatch`() {
        for (tool in renamedTools) {
            assertTrue(isDispatched(tool), "$tool is not dispatched")
        }
    }

    @Test
    fun `legacy har spellings still dispatch`() {
        // AI Knowledge written by earlier runs, and hand-written MCP config, name these.
        for (tool in legacyTools) {
            assertTrue(isDispatched(tool), "legacy alias $tool stopped working")
        }
    }

    @Test
    fun `prompt templates mention neither HAR nor the legacy tool names`() {
        for (
            template in listOf(
                "ai-prompt-live-gui-repair.txt",
                "ai-prompt-specific-request.txt",
                "ai-prompt-file-backed-repair.txt",
                "ai-prompt-user-instructions.txt",
                "ai-prompt-run-options.txt",
                "ai-prompt-fragments.properties",
            )
        ) {
            val text = requireNotNull(
                Class.forName("org.apache.jmeter.gui.action.AiPrompts")
                    .getResourceAsStream(template),
            ) { "missing prompt resource $template" }.use { it.readBytes().decodeToString() }
            assertFalse(text.contains("HAR"), "$template still says HAR")
            for (legacy in legacyTools) {
                assertFalse(text.contains(legacy), "$template still names $legacy")
            }
        }
    }
}
