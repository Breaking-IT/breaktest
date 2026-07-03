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

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * CLI fallback for Codex runs where native MCP tools are not exposed.
 */
public object BreakTestAgentToolCli {
    private val mapper = ObjectMapper()

    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
            System.err.println("Usage: breaktest-agent-tool <tool-name|tools|tools/list> [json-arguments] [jmeter-home]")
            kotlin.system.exitProcess(if (args.isEmpty()) 2 else 0)
        }
        val tool = args[0]
        val argumentsJson = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "{}"
        val jmeterHome = args.getOrNull(2)
        BreakTestAgentMcpServer.initializeForCli(jmeterHome)
        if (tool == "tools" || tool == "tools/list") {
            print(BreakTestAgentMcpServer.toolsListForCli())
            println()
            return
        }
        val result = BreakTestAgentMcpServer.callToolForCli(tool, mapper.readTree(argumentsJson))
        print(result)
        println()
    }
}
