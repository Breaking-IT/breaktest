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
    val preFailureDynamicCandidates: List<AgentDynamicValueCandidate> = emptyList(),
    val laterDynamicCandidates: List<AgentDynamicValueCandidate> = emptyList(),
    val preFailureRequestCandidates: List<AgentValidationRequestCandidate> = emptyList(),
)

public data class AgentValidationRequestCandidate(
    val sampleIndex: Int,
    val sampleLabel: String,
    val surface: String,
    val kind: String,
    val fieldName: String? = null,
    val literal: String,
    val tokenPreview: String,
    val reason: String,
    val priority: Int,
)

/**
 * Facade for agent clients such as MCP tools or future BreakTest GUI actions.
 */
public class BreakTestAgent(
    private val summarizer: AgentPlanSummarizer = AgentPlanSummarizer(),
    private val runner: AgentValidationRunner = AgentValidationRunner(),
    private val analyzer: AgentFailureAnalyzer = AgentFailureAnalyzer(),
    private val dynamicValueAnalyzer: AgentDynamicValueAnalyzer = AgentDynamicValueAnalyzer(),
) {
    public fun inspect(
        testTree: HashTree,
        dslCharacterLimit: Int? = null,
        includeStaticAssets: Boolean = true,
    ): AgentPlanContext =
        summarizer.summarize(testTree, dslCharacterLimit, includeStaticAssets = includeStaticAssets)

    public fun inspectAndValidate(
        testTree: HashTree,
        options: AgentRunOptions = AgentRunOptions(),
        dslCharacterLimit: Int? = null,
        includeStaticAssets: Boolean = true,
    ): BreakTestAgentReport {
        val plan = summarizer.summarize(testTree, dslCharacterLimit, includeStaticAssets = includeStaticAssets)
        val validation = runner.run(testTree, options)
        val dynamicCandidates = dynamicValueAnalyzer.analyze(testTree, limit = 1_000)
        val firstFailureIndex = validation.firstFailureIndex
        val preFailureCandidates = firstFailureIndex?.let { failureIndex ->
            dynamicCandidates
                .filter { it.samplerIndex <= failureIndex }
                .sortedWith(compareBy<AgentDynamicValueCandidate> { it.samplerIndex }.thenByDescending { it.priority })
                .take(MAX_DEPENDENCY_AUDIT_CANDIDATES)
        }.orEmpty()
        val laterCandidates = firstFailureIndex?.let { failureIndex ->
            dynamicCandidates
                .filter { it.samplerIndex > failureIndex }
                .sortedWith(compareBy<AgentDynamicValueCandidate> { it.samplerIndex }.thenByDescending { it.priority })
                .take(MAX_DEPENDENCY_AUDIT_CANDIDATES)
        }.orEmpty()
        val preFailureRequestCandidates = firstFailureIndex?.let { failureIndex ->
            validation.samples
                .filter { it.index <= failureIndex }
                .flatMap { it.flatten() }
                .filterNot { it.isStaticAssetRequest() }
                .flatMap(::requestCandidates)
                .sortedWith(compareBy<AgentValidationRequestCandidate> { it.sampleIndex }.thenByDescending { it.priority })
                .take(MAX_DEPENDENCY_AUDIT_CANDIDATES)
        }.orEmpty()
        return BreakTestAgentReport(
            plan = plan,
            validation = validation,
            analysis = analyzer.analyze(validation),
            preFailureDynamicCandidates = preFailureCandidates,
            laterDynamicCandidates = laterCandidates,
            preFailureRequestCandidates = preFailureRequestCandidates,
        )
    }

    private fun requestCandidates(sample: AgentSampleSummary): List<AgentValidationRequestCandidate> =
        listOf(
            "requestHeaders" to sample.requestHeaders,
            "requestBody" to sample.requestBody,
        ).flatMap { (surface, text) ->
            candidatesFromText(sample, surface, text)
        }

    private fun candidatesFromText(
        sample: AgentSampleSummary,
        surface: String,
        text: String,
    ): List<AgentValidationRequestCandidate> {
        if (text.isBlank()) {
            return emptyList()
        }
        val candidates = mutableListOf<AgentValidationRequestCandidate>()
        fun add(kind: String, fieldName: String?, literal: String, reason: String, priority: Int) {
            if (literal.length < 4 || candidates.any { it.kind == kind && it.literal == literal }) {
                return
            }
            candidates += AgentValidationRequestCandidate(
                sampleIndex = sample.index,
                sampleLabel = sample.label,
                surface = surface,
                kind = kind,
                fieldName = fieldName,
                literal = literal,
                tokenPreview = literal.preview(),
                reason = reason,
                priority = priority,
            )
        }
        for (match in OAUTH_FIELD_REGEX.findAll(text)) {
            val fieldName = match.groupValues[1]
            val literal = match.groupValues[2]
            add(
                "oauth-$fieldName",
                fieldName,
                literal,
                "OAuth/OpenID '$fieldName' value in validated request data; correlate it from the authorize/login response, redirect Location, or hidden field before replaying callback.",
                120,
            )
        }
        for (match in CREDENTIAL_FIELD_REGEX.findAll(text)) {
            val fieldName = match.groupValues[1]
            val literal = match.groupValues[2].ifBlank { match.groupValues[3] }
            add(
                "credential",
                fieldName,
                literal,
                "Credential-like '$fieldName' value in validated request data; move it to a top-level User Defined Variable and replace request data before continuing auth repair.",
                115,
            )
        }
        for (match in DYNAMIC_FIELD_REGEX.findAll(text)) {
            val fieldName = match.groupValues[1]
            val literal = match.groupValues[2].ifBlank { match.groupValues[3].ifBlank { match.groupValues[4] } }
            val kind = kindForField(fieldName, literal)
            add(
                kind,
                fieldName,
                literal,
                "Dynamic-looking '$fieldName' value in validated request data; search earlier responses for the same field name and value before replaying a recorded literal.",
                priorityForField(fieldName, literal),
            )
        }
        for (match in UUID_REGEX.findAll(text)) {
            add(
                "uuid",
                null,
                match.value,
                "UUID/GUID in validated request data; correlate from an earlier response before considering runtime generation.",
                90,
            )
        }
        for (match in EPOCH_MS_REGEX.findAll(text)) {
            add(
                "epoch-ms",
                null,
                match.value,
                "Millisecond timestamp in validated request data; correlate from an earlier response or generate at runtime if proven client-side.",
                80,
            )
        }
        for (match in OPAQUE_TOKEN_REGEX.findAll(text)) {
            add(
                "opaque-token",
                null,
                match.value,
                "Long opaque value in validated request data; review as nonce, token, state, session, or correlation data.",
                60,
            )
        }
        return candidates
    }

    private fun String.preview(): String =
        if (length <= 20) this else take(10) + "..." + takeLast(6)

    private fun kindForField(fieldName: String, literal: String): String {
        val lowerName = fieldName.lowercase()
        return when {
            "csrf" in lowerName || "verification" in lowerName || "requestverification" in lowerName -> "csrf-or-verification-token"
            "nonce" in lowerName -> "nonce"
            lowerName == "state" || lowerName.endsWith(".state") -> "oauth-state"
            lowerName == "code" || lowerName.endsWith(".code") -> "oauth-code"
            "client_id" in lowerName || "clientid" in lowerName -> "client-id"
            "draw" in lowerName -> "draw-id"
            "basket" in lowerName || "cart" in lowerName -> "basket-id"
            "ticket" in lowerName -> "ticket-id"
            "order" in lowerName -> "order-id"
            "product" in lowerName -> "product-id"
            "token" in lowerName -> "token"
            "date" in lowerName || "time" in lowerName || "timestamp" in lowerName -> "timestamp"
            UUID_REGEX.matches(literal) -> "uuid"
            EPOCH_MS_REGEX.matches(literal) -> "epoch-ms"
            else -> "dynamic-field"
        }
    }

    private fun priorityForField(fieldName: String, literal: String): Int {
        val lowerName = fieldName.lowercase()
        return when {
            "csrf" in lowerName || "verification" in lowerName || "nonce" in lowerName -> 120
            lowerName == "state" || lowerName == "code" -> 120
            "token" in lowerName || "client_id" in lowerName || "clientid" in lowerName -> 112
            "basket" in lowerName || "cart" in lowerName || "ticket" in lowerName ||
                "order" in lowerName || "transaction" in lowerName -> 105
            "product" in lowerName || "draw" in lowerName -> 98
            "date" in lowerName || "time" in lowerName || "timestamp" in lowerName -> 92
            UUID_REGEX.matches(literal) -> 90
            EPOCH_MS_REGEX.matches(literal) -> 80
            else -> 70
        }
    }

    private companion object {
        const val MAX_DEPENDENCY_AUDIT_CANDIDATES = 120
        val UUID_REGEX: Regex =
            Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
        val EPOCH_MS_REGEX: Regex = Regex("""(?<!\d)1[6-9]\d{11}(?!\d)""")
        val OAUTH_FIELD_REGEX: Regex =
            Regex("""(?i)(?:^|[?&\s])(?:[A-Za-z0-9_.-]*_)?(code|state|nonce)=([^&\s]{4,2048})""")
        val CREDENTIAL_FIELD_REGEX: Regex =
            Regex("""(?i)(?:^|[?&\s])([A-Za-z0-9_.-]*(?:user(?:name)?|email|password|passwd|pwd)[A-Za-z0-9_.-]*|login)=(?:"([^"]{3,160})"|([^&\s]{3,160}))""")
        val DYNAMIC_FIELD_REGEX: Regex =
            Regex("""(?i)(?:^|[?&\s{,])["']?([A-Za-z0-9_.:-]*(?:id|uuid|token|nonce|state|code|csrf|verification|challenge|draw|date|time|timestamp|session|basket|cart|ticket|order|product|client)[A-Za-z0-9_.:-]*)["']?\s*(?:=|:)\s*(?:"([^"]{4,2048})"|'([^']{4,2048})'|([^"',&\s{}<>\[\]]{4,2048}))""")
        val OPAQUE_TOKEN_REGEX: Regex =
            Regex("""(?<![A-Za-z0-9])(?=[A-Za-z0-9._~-]*[A-Za-z])(?=[A-Za-z0-9._~-]*\d)[A-Za-z0-9._~-]{24,}(?![A-Za-z0-9])""")
    }
}
