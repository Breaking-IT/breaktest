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
