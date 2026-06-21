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

import org.apache.jmeter.assertions.AssertionResult
import org.apache.jmeter.samplers.SampleResult

public data class AgentAssertionSummary(
    val name: String?,
    val failure: Boolean,
    val error: Boolean,
    val message: String?,
)

public data class AgentSampleSummary(
    val index: Int,
    val label: String,
    val success: Boolean,
    val responseCode: String,
    val responseMessage: String,
    val elapsedTimeMillis: Long,
    val requestHeaders: String,
    val requestBody: String,
    val responseHeaders: String,
    val responseBody: String,
    val assertions: List<AgentAssertionSummary>,
    val subResults: List<AgentSampleSummary> = emptyList(),
) {
    public val hasAssertionFailure: Boolean
        get() = assertions.any { it.failure || it.error }

    public fun flatten(): List<AgentSampleSummary> =
        listOf(this) + subResults.flatMap { it.flatten() }

    public companion object {
        public fun from(index: Int, result: SampleResult, options: AgentRunOptions): AgentSampleSummary =
            AgentSampleSummary(
                index = index,
                label = result.sampleLabel.orEmpty(),
                success = result.isSuccessful,
                responseCode = result.responseCode.orEmpty(),
                responseMessage = result.responseMessage.orEmpty(),
                elapsedTimeMillis = result.time,
                requestHeaders = result.requestHeaders.orEmpty().limit(options.requestBodyLimit),
                requestBody = result.samplerData.orEmpty().limit(options.requestBodyLimit),
                responseHeaders = result.responseHeaders.orEmpty().limit(options.responseBodyLimit),
                responseBody = result.responseDataAsString.orEmpty().limit(options.responseBodyLimit),
                assertions = result.assertionResults.map(AssertionResult::toSummary),
                subResults = result.subResults.mapIndexed { subIndex, subResult ->
                    from(subIndex, subResult, options)
                },
            )
    }
}

private fun AssertionResult.toSummary() =
    AgentAssertionSummary(
        name = name,
        failure = isFailure,
        error = isError,
        message = failureMessage,
    )

internal fun String.limit(maxLength: Int): String =
    if (maxLength >= 0 && length > maxLength) {
        take(maxLength)
    } else {
        this
    }
