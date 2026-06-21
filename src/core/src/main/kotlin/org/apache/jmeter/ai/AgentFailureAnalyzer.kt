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

public enum class AgentFailureKind {
    ASSERTION_FAILURE,
    HTTP_AUTH_OR_SESSION,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    SAMPLE_ERROR,
    TIMEOUT,
    NO_FAILURE,
}

public enum class AgentExtractorHint {
    JSON,
    HTML_OR_XML,
    BOUNDARY,
}

public data class AgentCorrelationCandidate(
    val sourceSampleIndex: Int,
    val sourceSampleLabel: String,
    val targetSampleIndex: Int,
    val targetSampleLabel: String,
    val literal: String,
    val tokenPreview: String,
    val extractorHint: AgentExtractorHint,
    val variableName: String,
    val reason: String,
)

public data class AgentFailureAnalysis(
    val kind: AgentFailureKind,
    val firstFailure: AgentSampleSummary?,
    val correlationCandidates: List<AgentCorrelationCandidate>,
)

public class AgentFailureAnalyzer {
    public fun analyze(result: AgentValidationResult): AgentFailureAnalysis {
        if (result.timedOut) {
            return AgentFailureAnalysis(AgentFailureKind.TIMEOUT, null, emptyList())
        }
        val samples = result.samples.flatMap { it.flatten() }
        val failure = samples.firstOrNull { it.isLeafFailure() }
            ?: samples.firstOrNull { !it.success || it.hasAssertionFailure }
            ?: return AgentFailureAnalysis(AgentFailureKind.NO_FAILURE, null, emptyList())
        return AgentFailureAnalysis(
            kind = classify(failure),
            firstFailure = failure,
            correlationCandidates = findCorrelationCandidates(samples, failure),
        )
    }

    private fun classify(sample: AgentSampleSummary): AgentFailureKind =
        when {
            sample.hasAssertionFailure -> AgentFailureKind.ASSERTION_FAILURE
            sample.responseCode == "401" || sample.responseCode == "403" -> AgentFailureKind.HTTP_AUTH_OR_SESSION
            sample.responseCode.startsWith("4") -> AgentFailureKind.HTTP_CLIENT_ERROR
            sample.responseCode.startsWith("5") -> AgentFailureKind.HTTP_SERVER_ERROR
            else -> AgentFailureKind.SAMPLE_ERROR
        }

    private fun AgentSampleSummary.isLeafFailure(): Boolean =
        subResults.isEmpty() && (!success || hasAssertionFailure)

    private fun findCorrelationCandidates(
        samples: List<AgentSampleSummary>,
        failure: AgentSampleSummary,
    ): List<AgentCorrelationCandidate> {
        val targetText = sequenceOf(failure.requestHeaders, failure.requestBody)
            .joinToString("\n")
        val targetTokens = TOKEN_REGEX.findAll(targetText)
            .map { it.value }
            .filterNot(::isLowValueToken)
            .distinct()
            .take(50)
            .toList()
        if (targetTokens.isEmpty()) {
            return emptyList()
        }

        val previousSamples = samples.take(failure.index)
        val candidates = mutableListOf<AgentCorrelationCandidate>()
        for (token in targetTokens) {
            val source = previousSamples.lastOrNull { it.responseBody.contains(token) }
                ?: continue
            candidates += AgentCorrelationCandidate(
                sourceSampleIndex = source.index,
                sourceSampleLabel = source.label,
                targetSampleIndex = failure.index,
                targetSampleLabel = failure.label,
                literal = token,
                tokenPreview = token.preview(),
                extractorHint = extractorHint(source),
                variableName = variableNameFor(token, candidates.size + 1),
                reason = "Token from failed request appears in an earlier response and is a correlation candidate.",
            )
        }
        return candidates
    }

    private fun extractorHint(source: AgentSampleSummary): AgentExtractorHint {
        val headers = source.responseHeaders.lowercase()
        val body = source.responseBody.trimStart()
        return when {
            "json" in headers || body.startsWith("{") || body.startsWith("[") -> AgentExtractorHint.JSON
            "html" in headers || "xml" in headers || body.startsWith("<") -> AgentExtractorHint.HTML_OR_XML
            else -> AgentExtractorHint.BOUNDARY
        }
    }

    private fun variableNameFor(token: String, index: Int): String {
        val letters = token.filter(Char::isLetterOrDigit)
            .take(12)
            .lowercase()
        return if (letters.isBlank()) {
            "ai_correlation_$index"
        } else {
            "ai_${letters}_$index"
        }
    }

    private fun String.preview(): String =
        if (length <= 16) this else take(8) + "..." + takeLast(4)

    private fun isLowValueToken(token: String): Boolean =
        token.length < 8 ||
            token.all(Char::isDigit) ||
            LOW_VALUE_TOKENS.any { token.contains(it, ignoreCase = true) }

    private companion object {
        val TOKEN_REGEX = Regex("[A-Za-z0-9._~+/-]{8,}")
        val LOW_VALUE_TOKENS = listOf(
            "localhost",
            "application",
            "text/html",
            "text/plain",
            "gzip",
            "keep-alive",
        )
    }
}
