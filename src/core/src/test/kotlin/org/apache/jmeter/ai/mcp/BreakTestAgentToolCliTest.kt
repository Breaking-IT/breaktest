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

package org.apache.jmeter.ai.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BreakTestAgentToolCliTest {
    @Test
    fun `assertion batch embeds the complete single assertion schema`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val tools = mapper.readTree(
            BreakTestAgentMcpServer.toolsListForCli(
                listOf("add_response_assertions_open_plan", "add_response_assertion_open_plan")
            )
        ).path("tools")
        val batch = tools[0].path("inputSchema").path("properties")
        assertEquals(tools[1].path("inputSchema"), batch.path("assertions").path("items"))
        assertEquals("boolean", batch.path("compact").path("type").asText())
    }

    @Test
    fun `core toolset advertises only existing general tools and the rest stays discoverable`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val all = BreakTestAgentMcpServer.advertisedToolsResult(coreOnly = false).path("tools").map { it.path("name").asText() }
        val core = BreakTestAgentMcpServer.advertisedToolsResult(coreOnly = true).path("tools").map { it.path("name").asText() }
        assertEquals(org.apache.jmeter.ai.AgentToolSpecs.coreToolNames, core.toSet(), "every core name must be a real tool")
        assertEquals(all.size, all.toSet().size, "tool names must be unique")
        org.junit.jupiter.api.Assertions.assertTrue(core.size < all.size / 2)

        val summaries = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            BreakTestAgentMcpServer.describeTools(mapper.createObjectNode())
        ).path("tools")
        assertEquals(all.toSet(), summaries.map { it.path("name").asText() }.toSet())
        val schema = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            BreakTestAgentMcpServer.describeTools(mapper.readTree("""{"names":["update_regex_extractor_open_plan"]}"""))
        ).path("tools").single()
        org.junit.jupiter.api.Assertions.assertTrue(schema.path("inputSchema").path("properties").size() > 0)
    }

    @Test
    fun `tool list without names gives summaries and an unknown name is named`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val listing = BreakTestAgentMcpServer.toolsListForCli()
        val tools = mapper.readTree(listing).path("tools")
        org.junit.jupiter.api.Assertions.assertTrue(tools.size() > 60)
        org.junit.jupiter.api.Assertions.assertTrue(tools.all { it.has("summary") && !it.has("inputSchema") })
        org.junit.jupiter.api.Assertions.assertTrue(listing.length < 16_000, "listing was ${listing.length} characters")
        val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BreakTestAgentMcpServer.toolsListForCli(listOf("validate_open_plan", "get_sampel"))
        }
        org.junit.jupiter.api.Assertions.assertTrue(failure.message!!.contains("get_sampel"), failure.message)
    }

    @Test
    fun `named schemas omit unrelated tools and reject unknown names`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val packet = mapper.readTree(BreakTestAgentMcpServer.toolsListForCli(listOf("validate_open_plan", "apply_repair_actions_open_plan")))
        assertEquals(2, packet.path("tools").size())
        for (retired in listOf("get_ai_knowledge_open_plan", "update_ai_knowledge_open_plan")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                BreakTestAgentMcpServer.toolsListForCli(listOf(retired))
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BreakTestAgentMcpServer.toolsListForCli(listOf("not_a_tool"))
        }
    }

    @Test
    fun readsComplexJsonFromArgumentsFileUnderPathWithSpaces(@TempDir temp: Path) {
        val argumentsFile = temp.resolve("agent arguments.json")
        val json = """{"regex":"token\\s*=\\s*\"([^\"]+)\"","path":"C:\\Test Plan\\$1"}"""
        Files.writeString(argumentsFile, json)

        val invocation = BreakTestAgentToolCli.parseInvocation(
            arrayOf("apply_regex", "--arguments-file", argumentsFile.toString(), "C:\\BreakTest Home"),
        )

        assertEquals("apply_regex", invocation.tool)
        assertEquals(json, invocation.argumentsJson)
        assertEquals("C:\\BreakTest Home", invocation.jmeterHome)
    }

    @Test
    fun keepsInlineJsonInvocationCompatible() {
        val invocation = BreakTestAgentToolCli.parseInvocation(
            arrayOf("agent_activity", "{\"level\":\"info\"}", "/opt/breaktest"),
        )

        assertEquals("agent_activity", invocation.tool)
        assertEquals("{\"level\":\"info\"}", invocation.argumentsJson)
        assertEquals("/opt/breaktest", invocation.jmeterHome)
    }

    @Test
    fun supportsAtFileShorthand(@TempDir temp: Path) {
        val argumentsFile = temp.resolve("arguments.json")
        Files.writeString(argumentsFile, "{\"compact\":true}")

        val invocation = BreakTestAgentToolCli.parseInvocation(
            arrayOf("validate_open_plan", "@$argumentsFile", "/opt/breaktest"),
        )

        assertEquals("{\"compact\":true}", invocation.argumentsJson)
        assertEquals("/opt/breaktest", invocation.jmeterHome)
    }
}
