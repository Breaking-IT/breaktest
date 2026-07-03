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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentReportCompactorTest {
    private fun sample(
        index: Int,
        label: String,
        success: Boolean = true,
    ): AgentSampleSummary =
        AgentSampleSummary(
            index = index,
            label = label,
            success = success,
            responseCode = if (success) "200" else "500",
            responseMessage = if (success) "OK" else "Server Error",
            elapsedTimeMillis = 12,
            requestHeaders = "Header: value\n".repeat(50),
            requestBody = "field=value&".repeat(100),
            responseHeaders = "Set-Cookie: session=abc\n".repeat(40),
            responseBody = "<html><title>$label marker</title></html>" + "x".repeat(3_000),
            assertions = emptyList(),
        )

    private fun report(samples: List<AgentSampleSummary>): BreakTestAgentReport {
        val validation = AgentValidationResult(samples = samples, timedOut = false)
        return BreakTestAgentReport(
            plan = AgentPlanContext(dsl = "", elementCount = samples.size, samplerCount = samples.size),
            validation = validation,
            analysis = AgentFailureAnalyzer().analyze(validation),
        )
    }

    @Test
    fun `green run returns only light evidence`() {
        val samples = (0 until 6).map { sample(it, "req-$it") }
        val compact = AgentReportCompactor.compactForRepair(report(samples))

        @Suppress("UNCHECKED_CAST")
        val validation = compact["validation"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val evidence = validation["evidenceSamples"] as List<Map<String, Any?>>
        assertEquals(6, evidence.size)
        assertTrue(evidence.all { it["evidenceLevel"] == "light" })
        assertTrue(evidence.none { it.containsKey("requestBody") })
        assertTrue(evidence.none { it.containsKey("responseHeaders") })
        assertTrue(evidence.all { (it["responseBodyPreview"] as String).contains("marker") })
    }

    @Test
    fun `failure run keeps full evidence only around the failure`() {
        val samples = (0 until 9).map { sample(it, "req-$it", success = it != 8) }
        val compact = AgentReportCompactor.compactForRepair(report(samples), fullEvidenceWindow = 2)

        @Suppress("UNCHECKED_CAST")
        val validation = compact["validation"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val evidence = validation["evidenceSamples"] as List<Map<String, Any?>>
        assertEquals(9, evidence.size)
        val full = evidence.filter { it["evidenceLevel"] == "full" }
        val light = evidence.filter { it["evidenceLevel"] == "light" }
        // failure sample plus the fullEvidenceWindow samples just before it
        assertEquals(3, full.size)
        assertEquals(6, light.size)
        assertTrue(full.any { it["index"] == 8 && it["success"] == false })
        assertTrue(full.all { it.containsKey("requestBody") && it.containsKey("responseHeaders") })
        assertTrue(light.all { (it["index"] as Int) < 6 })
    }

    @Test
    fun `light evidence keeps a bounded response preview`() {
        val samples = (0 until 3).map { sample(it, "req-$it") }
        val compact = AgentReportCompactor.compactForRepair(report(samples), bodyLimit = 10_000)

        @Suppress("UNCHECKED_CAST")
        val validation = compact["validation"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val evidence = validation["evidenceSamples"] as List<Map<String, Any?>>
        assertTrue(evidence.all { (it["responseBodyPreview"] as String).length <= 800 })
    }

    @Test
    fun `samples unchanged since previous run collapse to minimal entries`() {
        val samples = (0 until 5).map { sample(it, "req-$it") }
        val previousStatus = mapOf(
            "req-0" to (true to "200"),
            "req-1" to (true to "200"),
            // req-2 previously failed, so it changed and keeps its preview
            "req-2" to (false to "500"),
        )
        val compact = AgentReportCompactor.compactForRepair(
            report(samples),
            previousSampleStatus = previousStatus,
        )

        @Suppress("UNCHECKED_CAST")
        val validation = compact["validation"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val evidence = validation["evidenceSamples"] as List<Map<String, Any?>>
        val byLabel = evidence.associateBy { it["label"] }
        assertEquals("unchanged", byLabel["req-0"]?.get("evidenceLevel"))
        assertEquals("unchanged", byLabel["req-1"]?.get("evidenceLevel"))
        assertEquals("light", byLabel["req-2"]?.get("evidenceLevel"))
        assertEquals("light", byLabel["req-3"]?.get("evidenceLevel"))
        assertEquals(2, validation["unchangedEvidenceCount"])
        assertTrue(byLabel["req-0"]?.containsKey("responseBodyPreview") == false)
    }

    @Test
    fun `green run has no first failure`() {
        val samples = (0 until 2).map { sample(it, "req-$it") }
        val compact = AgentReportCompactor.compactForRepair(report(samples))

        @Suppress("UNCHECKED_CAST")
        val validation = compact["validation"] as Map<String, Any?>
        assertNull(validation["firstFailureIndex"])
        @Suppress("UNCHECKED_CAST")
        val analysis = compact["analysis"] as Map<String, Any?>
        assertNull(analysis["firstFailure"])
        assertFalse((validation["evidenceSamples"] as List<*>).isEmpty())
    }
}
