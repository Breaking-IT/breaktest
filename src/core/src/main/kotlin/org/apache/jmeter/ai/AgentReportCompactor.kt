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

/**
 * Creates a first-failure repair packet from a full validation report.
 *
 * Large browser recordings can produce enough sampler evidence to make an LLM
 * rerun validation just to filter the output. This compact form keeps the
 * evidence needed for the first repair pass while dropping static asset noise.
 */
public object AgentReportCompactor {
    public fun compactForRepair(
        report: BreakTestAgentReport,
        sampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
        bodyLimit: Int = DEFAULT_BODY_LIMIT,
        fullEvidenceWindow: Int = DEFAULT_FULL_EVIDENCE_WINDOW,
        previousSampleStatus: Map<String, Pair<Boolean, String>>? = null,
    ): Map<String, Any?> {
        val firstFailureIndex = report.validation.firstFailureIndex
        val greenRun = report.validation.successful && firstFailureIndex == null
        val reachedSamples = report.validation.samples.filter { sample ->
            firstFailureIndex == null || sample.index <= firstFailureIndex
        }
        val evidenceCandidates = reachedSamples
            .filter { sample -> sample.index == firstFailureIndex || !sample.isStaticAssetRequest() }
            .take(sampleLimit.coerceAtLeast(1))
        // Full request/response evidence is only useful around the failure; samples
        // that already passed long before it (and every sample of a green run) only
        // need a response-body marker preview. This is what keeps repeated
        // validation payloads from dwarfing everything else in the agent's context.
        val fullEvidenceFrom = if (greenRun) {
            evidenceCandidates.size
        } else {
            (evidenceCandidates.size - (fullEvidenceWindow.coerceAtLeast(1) + 1)).coerceAtLeast(0)
        }
        val evidenceSamples = evidenceCandidates.mapIndexed { position, sample ->
            val failed = !sample.success || sample.hasAssertionFailure
            val unchanged = !failed &&
                position < fullEvidenceFrom &&
                previousSampleStatus?.get(sample.label) == (sample.success to sample.responseCode)
            when {
                position >= fullEvidenceFrom || failed -> sample.compact(bodyLimit.coerceAtLeast(0))
                // Same sampler, same outcome as the previous validation run: the agent
                // already has this evidence, so repeat runs shrink instead of grow.
                unchanged -> mapOf(
                    "evidenceLevel" to "unchanged",
                    "index" to sample.index,
                    "label" to sample.label,
                    "success" to sample.success,
                    "responseCode" to sample.responseCode,
                )
                else -> sample.compactLight(bodyLimit.coerceAtLeast(0))
            }
        }
        val validationRepairActions = validationRepairActions(report, bodyLimit.coerceAtLeast(0))
        return mapOf(
            "compact" to true,
            "plan" to mapOf(
                "elementCount" to report.plan.elementCount,
                "samplerCount" to report.plan.samplerCount,
                "functionalSamplerCount" to report.plan.functionalSamplerCount,
                "omittedStaticSamplerCount" to report.plan.omittedStaticSamplerCount,
                "samplerTruncated" to report.plan.samplerTruncated,
                "transactionNames" to report.plan.transactionNames,
                "extractors" to report.plan.extractors,
            ),
            "validation" to mapOf(
                "successful" to report.validation.successful,
                "timedOut" to report.validation.timedOut,
                "stoppedEarly" to report.validation.stoppedEarly,
                "stopReason" to report.validation.stopReason,
                "ignoreStaticAssetFailures" to report.validation.ignoreStaticAssetFailures,
                "ignoredStaticFailureCount" to report.validation.ignoredStaticFailureCount,
                "sampleCount" to report.validation.samples.size,
                "firstFailureIndex" to firstFailureIndex,
                "reachedSampleSummary" to reachedSamples.map { sample ->
                    mapOf(
                        "index" to sample.index,
                        "label" to sample.label,
                        "success" to sample.success,
                        "responseCode" to sample.responseCode,
                        "elapsedTimeMillis" to sample.elapsedTimeMillis,
                        "staticAsset" to sample.isStaticAssetRequest(),
                    )
                },
                "evidenceSamples" to evidenceSamples,
                "unchangedEvidenceCount" to evidenceSamples.count { it["evidenceLevel"] == "unchanged" },
                "omittedReachedStaticSampleCount" to reachedSamples.count { it.isStaticAssetRequest() },
            ),
            "analysis" to mapOf(
                "kind" to report.analysis.kind,
                "firstFailure" to report.analysis.firstFailure?.compact(bodyLimit.coerceAtLeast(0)),
                "correlationCandidates" to report.analysis.correlationCandidates,
                "validationRepairActions" to validationRepairActions,
            ),
            "preFailureDynamicCandidates" to compactDynamicCandidates(report.preFailureDynamicCandidates, 50),
            "preFailureRequestCandidates" to compactRequestCandidates(report.preFailureRequestCandidates, 60),
            "laterDynamicCandidateSummary" to candidateSummary(report.laterDynamicCandidates),
            "laterDynamicCandidates" to compactDynamicCandidates(report.laterDynamicCandidates, 25),
        )
    }

    private fun AgentSampleSummary.compact(bodyLimit: Int): Map<String, Any?> =
        mapOf(
            "evidenceLevel" to "full",
            "index" to index,
            "label" to label,
            "success" to success,
            "responseCode" to responseCode,
            "responseMessage" to responseMessage,
            "elapsedTimeMillis" to elapsedTimeMillis,
            "requestHeaders" to requestHeaders.limit(bodyLimit),
            "requestBody" to requestBody.limit(bodyLimit),
            "responseHeaders" to responseHeaders.limit(bodyLimit),
            "responseBody" to responseBody.limit(bodyLimit),
            "assertions" to assertions,
            "subResultCount" to subResults.size,
            "subResultSummary" to subResults.map { it.summary() }.take(MAX_SUB_RESULT_SUMMARY),
            "subResultEvidence" to subResults
                .flatMap { it.flatten() }
                .filter { !it.isStaticAssetRequest() || !it.success || it.hasAssertionFailure }
                .take(MAX_SUB_RESULT_EVIDENCE)
                .map { it.compactLeaf((bodyLimit / 2).coerceAtLeast(300)) },
        )

    // Failed leaves carry the full request/response surfaces; leaves that passed only
    // need a response preview, which keeps multi-sampler transactions from exploding
    // the payload of every full-evidence sample.
    private fun AgentSampleSummary.compactLeaf(bodyLimit: Int): Map<String, Any?> =
        if (!success || hasAssertionFailure) {
            compactFullLeaf(bodyLimit)
        } else {
            mapOf(
                "index" to index,
                "label" to label,
                "success" to success,
                "responseCode" to responseCode,
                "elapsedTimeMillis" to elapsedTimeMillis,
                "responseBodyPreview" to responseBody.limit(bodyLimit.coerceAtMost(MAX_LIGHT_PREVIEW)),
                "assertions" to assertions,
                "staticAsset" to isStaticAssetRequest(),
            )
        }

    // Marker-preview form for samples that passed well before the first failure and
    // for green runs: enough response text to pick assertion markers, without the
    // request/response header and body payloads.
    private fun AgentSampleSummary.compactLight(bodyLimit: Int): Map<String, Any?> =
        mapOf(
            "evidenceLevel" to "light",
            "index" to index,
            "label" to label,
            "success" to success,
            "responseCode" to responseCode,
            "elapsedTimeMillis" to elapsedTimeMillis,
            "responseBodyPreview" to responseBody.limit(lightPreviewLimit(bodyLimit)),
            "assertions" to assertions,
            "subResultCount" to subResults.size,
        )

    private fun lightPreviewLimit(bodyLimit: Int): Int =
        (bodyLimit / 2).coerceIn(MIN_LIGHT_PREVIEW, MAX_LIGHT_PREVIEW)

    private fun AgentSampleSummary.compactFullLeaf(bodyLimit: Int): Map<String, Any?> =
        mapOf(
            "index" to index,
            "label" to label,
            "success" to success,
            "responseCode" to responseCode,
            "responseMessage" to responseMessage,
            "elapsedTimeMillis" to elapsedTimeMillis,
            "requestHeaders" to requestHeaders.limit(bodyLimit),
            "requestBody" to requestBody.limit(bodyLimit),
            "responseHeaders" to responseHeaders.limit(bodyLimit),
            "responseBody" to responseBody.limit(bodyLimit),
            "assertions" to assertions,
            "staticAsset" to isStaticAssetRequest(),
        )

    private fun AgentSampleSummary.summary(): Map<String, Any?> =
        mapOf(
            "index" to index,
            "label" to label,
            "success" to success,
            "responseCode" to responseCode,
            "elapsedTimeMillis" to elapsedTimeMillis,
            "staticAsset" to isStaticAssetRequest(),
            "assertionFailure" to hasAssertionFailure,
        )

    private fun compactDynamicCandidates(
        candidates: List<AgentDynamicValueCandidate>,
        limit: Int,
    ): List<Map<String, Any?>> =
        candidates
            .sortedWith(compareByDescending<AgentDynamicValueCandidate> { it.priority }.thenBy { it.samplerIndex })
            .take(limit)
            .map { candidate ->
                mapOf(
                    "samplerIndex" to candidate.samplerIndex,
                    "samplerName" to candidate.samplerName,
                    "transactionName" to candidate.transactionName,
                    "propertyName" to candidate.propertyName,
                    "kind" to candidate.kind,
                    "literal" to candidate.literal.limit(MAX_LITERAL_LENGTH),
                    "literalTruncated" to (candidate.literal.length > MAX_LITERAL_LENGTH),
                    "tokenPreview" to candidate.tokenPreview,
                    "reason" to candidate.reason,
                    "priority" to candidate.priority,
                )
            }

    private fun compactRequestCandidates(
        candidates: List<AgentValidationRequestCandidate>,
        limit: Int,
    ): List<Map<String, Any?>> =
        candidates
            .sortedWith(compareByDescending<AgentValidationRequestCandidate> { it.priority }.thenBy { it.sampleIndex })
            .take(limit)
            .map { candidate ->
                mapOf(
                    "sampleIndex" to candidate.sampleIndex,
                    "sampleLabel" to candidate.sampleLabel,
                    "surface" to candidate.surface,
                    "kind" to candidate.kind,
                    "fieldName" to candidate.fieldName,
                    "literal" to candidate.literal.limit(MAX_LITERAL_LENGTH),
                    "literalTruncated" to (candidate.literal.length > MAX_LITERAL_LENGTH),
                    "tokenPreview" to candidate.tokenPreview,
                    "reason" to candidate.reason,
                    "priority" to candidate.priority,
                )
            }

    private fun candidateSummary(candidates: List<AgentDynamicValueCandidate>): Map<String, Any?> =
        mapOf(
            "count" to candidates.size,
            "byKind" to candidates.groupingBy { it.kind }.eachCount().toSortedMap(),
            "top" to compactDynamicCandidates(candidates, 10),
        )

    private fun validationRepairActions(
        report: BreakTestAgentReport,
        bodyLimit: Int,
    ): List<Map<String, Any?>> {
        val firstFailureIndex = report.validation.firstFailureIndex ?: return emptyList()
        val reached = report.validation.samples
            .filter { it.index <= firstFailureIndex }
            .flatMap { it.flatten() }
            .filterNot { it.isStaticAssetRequest() }
        val actions = mutableListOf<Map<String, Any?>>()
        val seen = mutableSetOf<String>()
        for (candidate in report.preFailureRequestCandidates.sortedByDescending { it.priority }) {
            if (!seen.add("${candidate.kind}:${candidate.literal}")) {
                continue
            }
            val targetPosition = reached.indexOfFirst { it.label == candidate.sampleLabel }
                .takeIf { it >= 0 }
                ?: reached.indexOfFirst { it.index == candidate.sampleIndex }
            if (targetPosition <= 0) {
                continue
            }
            val previousSamples = reached.take(targetPosition)
            val exactSource = previousSamples.lastOrNull {
                it.responseBody.contains(candidate.literal) || it.responseHeaders.contains(candidate.literal)
            }
            val fieldSource = candidate.fieldName?.let { fieldName ->
                previousSamples.lastOrNull {
                    it.responseBody.contains(fieldName, ignoreCase = true) ||
                        it.responseHeaders.contains(fieldName, ignoreCase = true)
                }
            }
            val source = exactSource ?: fieldSource ?: continue
            val sourceText = if (source.responseBody.contains(candidate.literal)) {
                source.responseBody
            } else if (source.responseHeaders.contains(candidate.literal)) {
                source.responseHeaders
            } else if (candidate.fieldName != null && source.responseBody.contains(candidate.fieldName, ignoreCase = true)) {
                source.responseBody
            } else {
                source.responseHeaders
            }
            val literalIndex = sourceText.indexOf(candidate.literal).takeIf { it >= 0 }
            val regex = candidate.fieldName?.let { fieldName ->
                regexForFieldValue(sourceText, fieldName, candidate.literal)
            }
            val useField = if (
                source.responseHeaders.contains(candidate.literal) ||
                (candidate.fieldName != null && source.responseHeaders.contains(candidate.fieldName, ignoreCase = true))
            ) {
                "headers"
            } else {
                "body"
            }
            actions += mapOf(
                "id" to "validation-${actions.size + 1}",
                "type" to if (exactSource != null) "correlate_from_validated_response" else "inspect_field_source",
                "confidence" to confidenceFor(candidate, exactSource != null, regex != null),
                "priority" to candidate.priority,
                "kind" to candidate.kind,
                "fieldName" to candidate.fieldName,
                "literal" to candidate.literal.limit(MAX_LITERAL_LENGTH),
                "literalTruncated" to (candidate.literal.length > MAX_LITERAL_LENGTH),
                "tokenPreview" to candidate.tokenPreview,
                "variableName" to variableNameFor(candidate),
                "sourceSampleIndex" to source.index,
                "sourceSampleLabel" to source.label,
                "targetSampleIndex" to candidate.sampleIndex,
                "targetSampleLabel" to candidate.sampleLabel,
                "regex" to regex,
                "useField" to useField,
                "evidence" to if (literalIndex != null) {
                    sourceText.contextAround(literalIndex, literalIndex + candidate.literal.length, bodyLimit.coerceAtMost(800))
                } else {
                    candidate.fieldName?.let { sourceText.contextAroundField(it, bodyLimit.coerceAtMost(800)) }
                },
                "summary" to if (exactSource != null) {
                    "Validated request ${candidate.sampleLabel} sends ${candidate.fieldName ?: candidate.kind} ${candidate.tokenPreview}; earlier response ${source.label} contains the same value."
                } else {
                    "Validated request ${candidate.sampleLabel} sends ${candidate.fieldName} ${candidate.tokenPreview}; earlier response ${source.label} contains the field name but not the exact literal, inspect before editing."
                },
            )
            if (actions.size >= MAX_REPAIR_ACTIONS) {
                break
            }
        }
        return actions
    }

    private fun regexForFieldValue(sourceText: String, fieldName: String, literal: String): String? {
        // The probing regexes run in Kotlin, but the returned pattern runs in
        // JMeter's ORO engine, so it must use ORO-safe escaping (Regex.escape's
        // \Q...\E form never matches there).
        val field = Regex.escape(fieldName)
        val value = Regex.escape(literal)
        val oroField = AgentRegexSupport.oroEscape(fieldName)
        return when {
            Regex(""""$field"\s*:\s*"$value"""").containsMatchIn(sourceText) ->
                """"$oroField"\s*:\s*"([^"]+)""""
            Regex("""'$field'\s*:\s*'$value'""").containsMatchIn(sourceText) ->
                """'$oroField'\s*:\s*'([^']+)'"""
            Regex("""(?is)name\s*=\s*["']$field["'][^>]{0,300}value\s*=\s*["']$value["']""")
                .containsMatchIn(sourceText) ->
                """(?is)name\s*=\s*["']$oroField["'][^>]{0,300}value\s*=\s*["']([^"']+)["']"""
            Regex("""(?:^|[?&\s])$field=$value(?:[&\s]|$)""").containsMatchIn(sourceText) ->
                """(?:^|[?&\s])$oroField=([^&\s]+)"""
            else -> null
        }
    }

    private fun confidenceFor(
        candidate: AgentValidationRequestCandidate,
        exactValueFound: Boolean,
        regexFound: Boolean,
    ): Int {
        val base = when {
            candidate.priority >= 115 -> 94
            candidate.priority >= 100 -> 90
            candidate.priority >= 80 -> 82
            else -> 70
        }
        val exactAdjustment = if (exactValueFound) 6 else -18
        val regexAdjustment = if (regexFound) 4 else 0
        return (base + exactAdjustment + regexAdjustment).coerceIn(0, 99)
    }

    private fun variableNameFor(candidate: AgentValidationRequestCandidate): String {
        val base = candidate.fieldName
            ?.replace(Regex("""[^A-Za-z0-9]+"""), "_")
            ?.trim('_')
            ?.takeIf { it.isNotBlank() }
            ?: candidate.kind.replace("-", "_")
        return base.replaceFirstChar { it.lowercase() }
    }

    private fun String.contextAround(start: Int, endExclusive: Int, contextChars: Int): String {
        val from = (start - contextChars).coerceAtLeast(0)
        val to = (endExclusive + contextChars).coerceAtMost(length)
        val prefix = if (from > 0) "..." else ""
        val suffix = if (to < length) "..." else ""
        return prefix + substring(from, to) + suffix
    }

    private fun String.contextAroundField(fieldName: String, contextChars: Int): String {
        val index = indexOf(fieldName, ignoreCase = true)
        return if (index >= 0) {
            contextAround(index, index + fieldName.length, contextChars)
        } else {
            limit(contextChars * 2)
        }
    }

    private const val MAX_SUB_RESULT_SUMMARY = 25
    private const val MAX_SUB_RESULT_EVIDENCE = 10
    private const val MAX_LITERAL_LENGTH = 220
    private const val MAX_REPAIR_ACTIONS = 30
    private const val DEFAULT_SAMPLE_LIMIT = 20
    private const val DEFAULT_BODY_LIMIT = 1_500
    private const val DEFAULT_FULL_EVIDENCE_WINDOW = 4
    private const val MIN_LIGHT_PREVIEW = 400
    private const val MAX_LIGHT_PREVIEW = 800
}
