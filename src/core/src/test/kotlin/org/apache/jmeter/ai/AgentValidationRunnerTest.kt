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
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.samplers.AbstractSampler
import org.apache.jmeter.samplers.Entry
import org.apache.jmeter.samplers.SampleResult
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jmeter.treebuilder.oneRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class AgentValidationRunnerTest : JMeterTestCase() {
    @Test
    fun `run captures ordered sample evidence and first failure`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        sampleName = "Fetch form",
                        success = true,
                        responseHeaders = "Content-Type: text/html",
                        responseBody = "<input name=\"csrf\" value=\"TOKEN-abc123456789\" />",
                    )
                    +ScriptRepairSampler(
                        sampleName = "Submit form",
                        success = false,
                        responseCode = "403",
                        responseMessage = "Forbidden",
                        requestBody = "csrf=TOKEN-abc123456789",
                        responseBody = "bad csrf",
                    )
                }
            }
        }

        val result = AgentValidationRunner().run(
            tree,
            AgentRunOptions(timeout = Duration.ofSeconds(5)),
        )

        assertFalse(result.timedOut)
        assertFalse(result.successful)
        assertEquals(2, result.samples.size)
        assertEquals(1, result.firstFailureIndex)
        assertEquals("Submit form", result.samples[1].label)
        assertEquals("csrf=TOKEN-abc123456789", result.samples[1].requestBody)
    }

    @Test
    fun `agent facade summarizes validates and analyzes`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler("Fetch form", responseBody = "TOKEN-abc123456789")
                    +ScriptRepairSampler(
                        sampleName = "Submit form",
                        success = false,
                        responseCode = "403",
                        requestBody = "csrf=TOKEN-abc123456789",
                    )
                }
            }
        }

        val report = BreakTestAgent().inspectAndValidate(
            tree,
            AgentRunOptions(timeout = Duration.ofSeconds(5)),
        )

        assertEquals(2, report.plan.samplerCount)
        assertEquals(1, report.validation.firstFailureIndex)
        assertEquals(AgentFailureKind.HTTP_AUTH_OR_SESSION, report.analysis.kind)
        assertEquals(1, report.analysis.correlationCandidates.size)
    }

    @Test
    fun `analyzer finds correlation candidate from previous response`() {
        val result = AgentValidationResult(
            timedOut = false,
            samples = listOf(
                AgentSampleSummary(
                    index = 0,
                    label = "Fetch form",
                    success = true,
                    responseCode = "200",
                    responseMessage = "OK",
                    elapsedTimeMillis = 1,
                    requestHeaders = "",
                    requestBody = "",
                    responseHeaders = "Content-Type: text/html",
                    responseBody = "<input name=\"csrf\" value=\"TOKEN-abc123456789\" />",
                    assertions = emptyList(),
                ),
                AgentSampleSummary(
                    index = 1,
                    label = "Submit form",
                    success = false,
                    responseCode = "403",
                    responseMessage = "Forbidden",
                    elapsedTimeMillis = 1,
                    requestHeaders = "",
                    requestBody = "csrf=TOKEN-abc123456789",
                    responseHeaders = "",
                    responseBody = "bad csrf",
                    assertions = emptyList(),
                ),
            ),
        )

        val analysis = AgentFailureAnalyzer().analyze(result)

        assertEquals(AgentFailureKind.HTTP_AUTH_OR_SESSION, analysis.kind)
        assertNotNull(analysis.firstFailure)
        assertEquals(1, analysis.correlationCandidates.size)
        val candidate = analysis.correlationCandidates.single()
        assertEquals(0, candidate.sourceSampleIndex)
        assertEquals(1, candidate.targetSampleIndex)
        assertEquals("TOKEN-abc123456789", candidate.literal)
        assertEquals(AgentExtractorHint.HTML_OR_XML, candidate.extractorHint)
        assertTrue(candidate.variableName.startsWith("ai_tokenabc123"))
    }

    @Test
    fun `analyzer classifies assertion failures before http code`() {
        val assertion = AgentAssertionSummary(
            name = "Response assertion",
            failure = true,
            error = false,
            message = "Missing expected text",
        )
        val result = AgentValidationResult(
            timedOut = false,
            samples = listOf(
                AgentSampleSummary(
                    index = 0,
                    label = "Assert page",
                    success = false,
                    responseCode = "200",
                    responseMessage = "OK",
                    elapsedTimeMillis = 1,
                    requestHeaders = "",
                    requestBody = "",
                    responseHeaders = "",
                    responseBody = "actual",
                    assertions = listOf(assertion),
                ),
            ),
        )

        val analysis = AgentFailureAnalyzer().analyze(result)

        assertEquals(AgentFailureKind.ASSERTION_FAILURE, analysis.kind)
    }

    @Test
    fun `stopped early validation is not successful even when captured samples passed`() {
        val result = AgentValidationResult(
            timedOut = false,
            stoppedEarly = true,
            stopReason = "maxSamples",
            samples = listOf(
                AgentSampleSummary(
                    index = 0,
                    label = "Passed before stop",
                    success = true,
                    responseCode = "200",
                    responseMessage = "OK",
                    elapsedTimeMillis = 1,
                    requestHeaders = "",
                    requestBody = "",
                    responseHeaders = "",
                    responseBody = "",
                    assertions = emptyList(),
                ),
            ),
        )

        assertFalse(result.successful)
    }
}

class ScriptRepairSampler(
    sampleName: String = "Sampler",
    success: Boolean = true,
    responseCode: String = if (success) "200" else "500",
    responseMessage: String = if (success) "OK" else "Error",
    requestHeaders: String = "",
    requestBody: String = "",
    responseHeaders: String = "",
    responseBody: String = "",
    private val assertionResult: AssertionResult? = null,
) : AbstractSampler() {
    init {
        name = sampleName
        setProperty(SUCCESS, success)
        setProperty(RESPONSE_CODE, responseCode)
        setProperty(RESPONSE_MESSAGE, responseMessage)
        setProperty(REQUEST_HEADERS, requestHeaders)
        setProperty(REQUEST_BODY, requestBody)
        setProperty(RESPONSE_HEADERS, responseHeaders)
        setProperty(RESPONSE_BODY, responseBody)
    }

    override fun sample(e: Entry?): SampleResult =
        SampleResult().apply {
            sampleLabel = name
            sampleStart()
            setRequestHeaders(getPropertyAsString(REQUEST_HEADERS))
            samplerData = getPropertyAsString(REQUEST_BODY)
            setResponseHeaders(getPropertyAsString(RESPONSE_HEADERS))
            setResponseData(getPropertyAsString(RESPONSE_BODY), null)
            dataType = SampleResult.TEXT
            responseCode = getPropertyAsString(RESPONSE_CODE)
            responseMessage = getPropertyAsString(RESPONSE_MESSAGE)
            setSuccessful(getPropertyAsBoolean(SUCCESS))
            assertionResult?.let(::addAssertionResult)
            sampleEnd()
        }

    private companion object {
        const val SUCCESS = "ScriptRepairSampler.success"
        const val RESPONSE_CODE = "ScriptRepairSampler.responseCode"
        const val RESPONSE_MESSAGE = "ScriptRepairSampler.responseMessage"
        const val REQUEST_HEADERS = "ScriptRepairSampler.requestHeaders"
        const val REQUEST_BODY = "ScriptRepairSampler.requestBody"
        const val RESPONSE_HEADERS = "ScriptRepairSampler.responseHeaders"
        const val RESPONSE_BODY = "ScriptRepairSampler.responseBody"
    }
}
