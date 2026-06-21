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

package org.apache.jmeter.ai

import org.apache.jorphan.collections.HashTree

public data class BreakTestAgentReport(
    val plan: AgentPlanContext,
    val validation: AgentValidationResult,
    val analysis: AgentFailureAnalysis,
)

/**
 * Facade for agent clients such as MCP tools or future BreakTest GUI actions.
 */
public class BreakTestAgent(
    private val summarizer: AgentPlanSummarizer = AgentPlanSummarizer(),
    private val runner: AgentValidationRunner = AgentValidationRunner(),
    private val analyzer: AgentFailureAnalyzer = AgentFailureAnalyzer(),
) {
    public fun inspect(
        testTree: HashTree,
        dslCharacterLimit: Int? = null,
    ): AgentPlanContext =
        summarizer.summarize(testTree, dslCharacterLimit)

    public fun inspectAndValidate(
        testTree: HashTree,
        options: AgentRunOptions = AgentRunOptions(),
        dslCharacterLimit: Int? = null,
    ): BreakTestAgentReport {
        val plan = summarizer.summarize(testTree, dslCharacterLimit)
        val validation = runner.run(testTree, options)
        return BreakTestAgentReport(
            plan = plan,
            validation = validation,
            analysis = analyzer.analyze(validation),
        )
    }
}
