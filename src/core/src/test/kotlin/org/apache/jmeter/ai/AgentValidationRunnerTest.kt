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
import org.junit.jupiter.api.Assertions.assertNull
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
        val preFailureUuid = "11111111-2222-3333-4444-555555555555"
        val laterUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "Fetch form",
                        requestHeaders = "__Host-nlportal.loginCancelUrl=https%253A%252F%252Fstaatsloterij.lotteries-acc.nl%252F;",
                        requestBody = "username=jane@example.test&nonce=$preFailureUuid",
                        responseBody = "TOKEN-abc123456789",
                    )
                    +ScriptRepairSampler(
                        sampleName = "Submit form",
                        success = false,
                        responseCode = "403",
                        requestBody = "state=STATE-abc123456789&code=CODE-abc123456789&csrf=TOKEN-abc123456789",
                    )
                    +ScriptRepairSampler(
                        "Later request",
                        requestBody = "orderId=$laterUuid",
                    )
                }
            }
        }

        val report = BreakTestAgent().inspectAndValidate(
            tree,
            AgentRunOptions(timeout = Duration.ofSeconds(5), stopOnFirstFailure = true),
        )

        assertEquals(3, report.plan.samplerCount)
        assertEquals(1, report.validation.firstFailureIndex)
        assertEquals(AgentFailureKind.HTTP_AUTH_OR_SESSION, report.analysis.kind)
        assertEquals(1, report.analysis.correlationCandidates.size)
        assertTrue(report.preFailureDynamicCandidates.any { it.literal == preFailureUuid })
        assertFalse(report.preFailureDynamicCandidates.any { it.literal == laterUuid })
        assertTrue(report.laterDynamicCandidates.any { it.literal == laterUuid })
        assertTrue(report.preFailureRequestCandidates.any { it.kind == "credential" && it.literal == "jane@example.test" })
        assertFalse(
            report.preFailureRequestCandidates.any {
                it.kind == "credential" && it.fieldName == "__Host-nlportal.loginCancelUrl"
            },
        )
        assertTrue(report.preFailureRequestCandidates.any { it.kind == "oauth-state" && it.literal == "STATE-abc123456789" })
        assertTrue(report.preFailureRequestCandidates.any { it.kind == "oauth-code" && it.literal == "CODE-abc123456789" })
    }

    @Test
    fun `compact report summarizes transaction children and suggests field correlation actions`() {
        val drawId = "DRAW20260110"
        val source = AgentSampleSummary(
            index = 0,
            label = "GET /api/draws",
            success = true,
            responseCode = "200",
            responseMessage = "OK",
            elapsedTimeMillis = 1,
            requestHeaders = "",
            requestBody = "",
            responseHeaders = "Content-Type: application/json",
            responseBody = """{"drawId":"$drawId","name":"Saturday draw"}""",
            assertions = emptyList(),
        )
        val staticChildren = (1..25).map { index ->
            AgentSampleSummary(
                index = index,
                label = "GET /static/app-$index.js",
                success = true,
                responseCode = "200",
                responseMessage = "OK",
                elapsedTimeMillis = 1,
                requestHeaders = "GET /static/app-$index.js",
                requestBody = "",
                responseHeaders = "",
                responseBody = "x".repeat(20_000),
                assertions = emptyList(),
            )
        }
        val failingChild = AgentSampleSummary(
            index = 26,
            label = "POST /api/basket",
            success = false,
            responseCode = "500",
            responseMessage = "Server Error",
            elapsedTimeMillis = 1,
            requestHeaders = "",
            requestBody = "drawId=$drawId",
            responseHeaders = "",
            responseBody = "bad draw",
            assertions = emptyList(),
        )
        val transaction = AgentSampleSummary(
            index = 1,
            label = "02_Transaction",
            success = false,
            responseCode = "500",
            responseMessage = "Child failed",
            elapsedTimeMillis = 2,
            requestHeaders = "",
            requestBody = "",
            responseHeaders = "",
            responseBody = "",
            assertions = emptyList(),
            subResults = staticChildren + failingChild,
        )
        val report = BreakTestAgentReport(
            plan = AgentPlanContext(
                dsl = "",
                elementCount = 2,
                samplerCount = 2,
            ),
            validation = AgentValidationResult(
                timedOut = false,
                samples = listOf(source, transaction),
            ),
            analysis = AgentFailureAnalysis(
                kind = AgentFailureKind.HTTP_SERVER_ERROR,
                firstFailure = transaction,
                correlationCandidates = emptyList(),
            ),
            preFailureRequestCandidates = listOf(
                AgentValidationRequestCandidate(
                    sampleIndex = failingChild.index,
                    sampleLabel = failingChild.label,
                    surface = "requestBody",
                    kind = "draw-id",
                    fieldName = "drawId",
                    literal = drawId,
                    tokenPreview = drawId,
                    reason = "drawId request value",
                    priority = 98,
                )
            ),
        )

        val compact = AgentReportCompactor.compactForRepair(report, sampleLimit = 20, bodyLimit = 1_500)
        val validation = compact["validation"] as Map<*, *>
        val evidence = validation["evidenceSamples"] as List<*>
        val transactionEvidence = evidence.last() as Map<*, *>
        val subResultEvidence = transactionEvidence["subResultEvidence"] as List<*>
        val analysis = compact["analysis"] as Map<*, *>
        val actions = analysis["validationRepairActions"] as List<*>
        val firstAction = actions.first() as Map<*, *>

        assertFalse(transactionEvidence.containsKey("subResults"))
        assertTrue(subResultEvidence.size < staticChildren.size)
        assertEquals("correlate_from_validated_response", firstAction["type"])
        assertEquals("drawId", firstAction["fieldName"])
        assertEquals("GET /api/draws", firstAction["sourceSampleLabel"])
        assertEquals("POST /api/basket", firstAction["targetSampleLabel"])
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
    fun `validation and analysis can ignore static asset failures for functional repair`() {
        val result = AgentValidationResult(
            timedOut = false,
            ignoreStaticAssetFailures = true,
            samples = listOf(
                AgentSampleSummary(
                    index = 0,
                    label = "GET /statics/app.1234567890abcdef.mjs",
                    success = false,
                    responseCode = "404",
                    responseMessage = "Not Found",
                    elapsedTimeMillis = 1,
                    requestHeaders = "GET /statics/app.1234567890abcdef.mjs HTTP/1.1",
                    requestBody = "",
                    responseHeaders = "",
                    responseBody = "",
                    assertions = emptyList(),
                ),
                AgentSampleSummary(
                    index = 1,
                    label = "POST /api/basket/verify",
                    success = false,
                    responseCode = "500",
                    responseMessage = "Server Error",
                    elapsedTimeMillis = 1,
                    requestHeaders = "",
                    requestBody = "{\"transactionId\":\"11111111-2222-3333-4444-555555555555\"}",
                    responseHeaders = "",
                    responseBody = "bad basket",
                    assertions = emptyList(),
                ),
            ),
        )

        val analysis = AgentFailureAnalyzer().analyze(result)

        assertFalse(result.successful)
        assertEquals(1, result.firstFailureIndex)
        assertEquals(1, result.ignoredStaticFailureCount)
        assertEquals("POST /api/basket/verify", analysis.firstFailure?.label)
        assertEquals(AgentFailureKind.HTTP_SERVER_ERROR, analysis.kind)
    }

    @Test
    fun `validation ignores transaction parent failures caused only by static asset children`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        sampleName = "02_Transaction",
                        success = false,
                        responseCode = "404",
                        responseMessage = "Static child failed",
                        staticChildFailureLabel = "GET /_next/static/chunks/app.js",
                    )
                    +ScriptRepairSampler(
                        sampleName = "POST /api/basket/verify",
                        success = false,
                        responseCode = "500",
                        responseMessage = "Server Error",
                        requestBody = "{\"transactionId\":\"11111111-2222-3333-4444-555555555555\"}",
                    )
                }
            }
        }

        val result = AgentValidationRunner().run(
            tree,
            AgentRunOptions(
                timeout = Duration.ofSeconds(5),
                stopOnFirstFailure = true,
                ignoreStaticAssetFailures = true,
            ),
        )
        val analysis = AgentFailureAnalyzer().analyze(result)

        assertFalse(result.successful)
        assertEquals(2, result.samples.size)
        assertEquals(1, result.firstFailureIndex)
        assertEquals(1, result.ignoredStaticFailureCount)
        assertEquals("POST /api/basket/verify", analysis.firstFailure?.label)
    }

    @Test
    fun `validation result can ignore static-only transaction failure`() {
        val result = AgentValidationResult(
            timedOut = false,
            ignoreStaticAssetFailures = true,
            samples = listOf(
                AgentSampleSummary(
                    index = 0,
                    label = "02_Transaction",
                    success = false,
                    responseCode = "404",
                    responseMessage = "Static child failed",
                    elapsedTimeMillis = 1,
                    requestHeaders = "",
                    requestBody = "",
                    responseHeaders = "",
                    responseBody = "",
                    assertions = emptyList(),
                    subResults = listOf(
                        AgentSampleSummary(
                            index = 0,
                            label = "GET /fonts/app.woff2",
                            success = false,
                            responseCode = "404",
                            responseMessage = "Not Found",
                            elapsedTimeMillis = 1,
                            requestHeaders = "GET /fonts/app.woff2 HTTP/1.1",
                            requestBody = "",
                            responseHeaders = "",
                            responseBody = "",
                            assertions = emptyList(),
                        ),
                    ),
                ),
            ),
        )

        val analysis = AgentFailureAnalyzer().analyze(result)

        assertNull(result.firstFailureIndex)
        assertEquals(1, result.ignoredStaticFailureCount)
        assertEquals(AgentFailureKind.NO_FAILURE, analysis.kind)
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
    staticChildFailureLabel: String? = null,
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
        setProperty(STATIC_CHILD_FAILURE_LABEL, staticChildFailureLabel.orEmpty())
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
            val staticChildFailureLabel = getPropertyAsString(STATIC_CHILD_FAILURE_LABEL)
            if (staticChildFailureLabel.isNotBlank()) {
                addSubResult(
                    SampleResult().apply {
                        sampleLabel = staticChildFailureLabel
                        setRequestHeaders("$staticChildFailureLabel HTTP/1.1")
                        responseCode = "404"
                        responseMessage = "Not Found"
                        setSuccessful(false)
                    },
                )
            }
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
        const val STATIC_CHILD_FAILURE_LABEL = "ScriptRepairSampler.staticChildFailureLabel"
    }
}
