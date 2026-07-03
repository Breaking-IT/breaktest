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

import org.apache.jmeter.control.Controller
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.MultiProperty
import org.apache.jmeter.testelement.property.ObjectProperty
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jorphan.collections.HashTree
import java.util.IdentityHashMap

public data class AgentDynamicValueCandidate(
    val samplerIndex: Int,
    val samplerName: String,
    val transactionName: String? = null,
    val ownerName: String,
    val ownerClassName: String,
    val propertyName: String,
    val kind: String,
    val literal: String,
    val tokenPreview: String,
    val reason: String,
    val priority: Int,
)

public class AgentDynamicValueAnalyzer(
    private val includeStaticAssetRequests: Boolean = false,
) {
    public fun analyze(testTree: HashTree, limit: Int = 200): List<AgentDynamicValueCandidate> {
        val candidates = mutableListOf<AgentDynamicValueCandidate>()
        val scanLimit = (limit * 5).coerceAtLeast(500).coerceAtMost(5000)
        var samplerIndex = -1
        val visitedElements = IdentityHashMap<TestElement, Boolean>()
        val visitedProperties = IdentityHashMap<JMeterProperty, Boolean>()

        fun visit(tree: HashTree, currentSampler: SamplerContext?, currentTransaction: String?) {
            for (node in tree.list()) {
                val subTree = tree.getTree(node)
                val nodeName = (node as? TestElement)?.name
                val transaction = when {
                    node is TransactionController -> nodeName.orEmpty()
                    node is Controller && nodeName?.contains("transaction", ignoreCase = true) == true -> nodeName
                    else -> currentTransaction
                }
                val sampler = if (node is Sampler) {
                    samplerIndex++
                    SamplerContext(
                        index = samplerIndex,
                        name = (node as TestElement).name.orEmpty(),
                        transactionName = transaction,
                        staticAssetRequest = node.isStaticAssetRequest(),
                    )
                } else {
                    currentSampler
                }
                if (node is TestElement && sampler != null && candidates.size < scanLimit) {
                    scanElement(node, sampler, candidates, scanLimit, visitedElements, visitedProperties)
                }
                if (candidates.size < scanLimit) {
                    visit(subTree, sampler, transaction)
                }
            }
        }

        visit(testTree, null, null)
        return candidates.distinctBy { "${it.samplerIndex}:${it.propertyName}:${it.literal}:${it.kind}" }
            .sortedWith(
                compareByDescending<AgentDynamicValueCandidate> { it.priority }
                    .thenBy { it.samplerIndex }
                    .thenBy { it.propertyName },
            )
            .take(limit)
    }

    private fun scanElement(
        element: TestElement,
        sampler: SamplerContext,
        candidates: MutableList<AgentDynamicValueCandidate>,
        limit: Int,
        visitedElements: IdentityHashMap<TestElement, Boolean>,
        visitedProperties: IdentityHashMap<JMeterProperty, Boolean>,
    ) {
        if (!includeStaticAssetRequests && sampler.staticAssetRequest) {
            return
        }
        if (visitedElements.put(element, true) != null) {
            return
        }
        val iterator = element.propertyIterator()
        while (iterator.hasNext() && candidates.size < limit) {
            scanProperty(iterator.next(), element, sampler, candidates, limit, visitedElements, visitedProperties)
        }
    }

    private fun scanProperty(
        property: JMeterProperty,
        owner: TestElement,
        sampler: SamplerContext,
        candidates: MutableList<AgentDynamicValueCandidate>,
        limit: Int,
        visitedElements: IdentityHashMap<TestElement, Boolean>,
        visitedProperties: IdentityHashMap<JMeterProperty, Boolean>,
    ) {
        if (visitedProperties.put(property, true) != null) {
            return
        }
        if (property is StringProperty) {
            candidates += candidatesFrom(owner, sampler, property.name, property.stringValue)
                .take(limit - candidates.size)
        }
        if (property is ObjectProperty && property.objectValue is String) {
            candidates += candidatesFrom(owner, sampler, property.name, property.objectValue as String)
                .take(limit - candidates.size)
        }
        if (property is TestElementProperty && property.objectValue is TestElement) {
            scanElement(property.objectValue as TestElement, sampler, candidates, limit, visitedElements, visitedProperties)
        }
        if (property is MultiProperty) {
            val iterator = property.iterator()
            while (iterator.hasNext() && candidates.size < limit) {
                scanProperty(iterator.next(), owner, sampler, candidates, limit, visitedElements, visitedProperties)
            }
        }
        if (property is ObjectProperty) {
            when (val nested = property.objectValue) {
                is TestElement -> scanElement(
                    nested,
                    sampler,
                    candidates,
                    limit,
                    visitedElements,
                    visitedProperties,
                )
                is JMeterProperty -> scanProperty(
                    nested,
                    owner,
                    sampler,
                    candidates,
                    limit,
                    visitedElements,
                    visitedProperties,
                )
            }
        }
    }

    private fun candidatesFrom(
        owner: TestElement,
        sampler: SamplerContext,
        propertyName: String,
        value: String,
    ): List<AgentDynamicValueCandidate> {
        if (value.isBlank()) {
            return emptyList()
        }
        if (isLowSignalRequestContext(sampler, propertyName, value)) {
            return emptyList()
        }
        val result = mutableListOf<AgentDynamicValueCandidate>()
        fun add(kind: String, literal: String, reason: String) {
            if (literal.isCandidateToken(kind) && result.none { it.literal == literal }) {
                result += AgentDynamicValueCandidate(
                    samplerIndex = sampler.index,
                    samplerName = sampler.name,
                    transactionName = sampler.transactionName,
                    ownerName = owner.name.orEmpty(),
                    ownerClassName = owner::class.java.name,
                    propertyName = propertyName,
                    kind = kind,
                    literal = literal,
                    tokenPreview = literal.preview(),
                    reason = reason,
                    priority = priorityFor(kind, propertyName, value),
                )
            }
        }

        for (match in BEARER_REGEX.findAll(value)) {
            add(
                "bearer-token",
                match.groupValues[1],
                "Hard-coded bearer token in request data; correlate from login/token response or parameterize.",
            )
        }
        for (match in UUID_REGEX.findAll(value)) {
            val fieldName = fieldNameBefore(value, match.range.first)
            add(
                "uuid",
                match.value,
                if (fieldName != null && CLIENT_GENERATED_UUID_FIELDS.any { fieldName.contains(it, ignoreCase = true) }) {
                    "Hard-coded UUID/GUID in request field '$fieldName'. If no earlier response issues it, treat it as a likely browser/client-generated identifier and generate it at runtime, reusing one variable for all dependent occurrences."
                } else {
                    "Hard-coded UUID/GUID in request data; verify source in earlier response, generate it at runtime when client-created, or randomize from prior listing."
                },
            )
        }
        for (match in EPOCH_MS_REGEX.findAll(value)) {
            add(
                "epoch-ms",
                match.value,
                "Hard-coded millisecond timestamp; correlate from earlier response or generate current epoch milliseconds.",
            )
        }
        for (match in DATE_TIME_REGEX.findAll(value)) {
            add(
                "date-time",
                match.value,
                "Hard-coded date/time value in request data; verify whether it should be generated, parameterized, or correlated from earlier scenario data.",
            )
        }
        for (match in FIELD_VALUE_REGEX.findAll(value)) {
            val fieldName = match.groupValues[1]
            val literal = match.groupValues[2].ifBlank { match.groupValues[3].ifBlank { match.groupValues[4] } }
            if (fieldName.contains("draw", ignoreCase = true) && DRAW_ID_VALUE_REGEX.matches(literal)) {
                add(
                    "draw-id",
                    literal,
                    "Hard-coded draw-like value in request field '$fieldName'; correlate it from the selected product/draw response or derive it from prior scenario data.",
                )
            }
        }
        for (match in CREDENTIAL_FIELD_VALUE_REGEX.findAll(value)) {
            val fieldName = match.groupValues[1]
            val literal = match.groupValues[2].ifBlank { match.groupValues[3].ifBlank { match.groupValues[4] } }
            add(
                "credential",
                literal,
                "Hard-coded credential-like value in request field '$fieldName'; move it to a top-level User Defined Variable and replace every occurrence.",
            )
        }
        for (match in QUERY_ID_REGEX.findAll(value)) {
            add(
                "numeric-id",
                match.groupValues[2],
                "Hard-coded query parameter ending in id; verify whether it is dynamic scenario data.",
            )
        }
        if (propertyName.contains("csrf", ignoreCase = true) ||
            propertyName.contains("verification", ignoreCase = true) ||
            value.contains("csrf", ignoreCase = true) ||
            value.contains("requestverification", ignoreCase = true)
        ) {
            for (match in OPAQUE_TOKEN_REGEX.findAll(value)) {
                add(
                    "csrf-or-verification-token",
                    match.value,
                    "CSRF/request-verification related token; correlate from the response that issued it.",
                )
            }
        }
        // Long pure-hex literals (hashes, process/order ids) and opaque tokens that
        // form a URL path segment are near-certain server-issued dynamic values, so
        // they are classified as their own high-confidence kinds instead of blending
        // into the low-priority opaque-token pool.
        val inPathProperty = propertyName == "HTTPSampler.path" || propertyName.endsWith(".path", ignoreCase = true)
        for (match in HEX_ID_REGEX.findAll(value)) {
            add(
                "hex-id",
                match.value,
                "Long hexadecimal literal in request data; almost always a server-issued hash, process id, or order id. " +
                    "Correlate it from the earlier response that issued it (search validated/recorded responses for the literal).",
            )
        }
        for (match in OPAQUE_TOKEN_REGEX.findAll(value)) {
            add(
                if (inPathProperty) "path-opaque-id" else "opaque-token",
                match.value,
                if (inPathProperty) {
                    "Long opaque literal inside a request URL path; random-looking path segments are almost always " +
                        "server-issued and must be correlated from the response that issued them."
                } else {
                    "Long mixed alpha-numeric literal in request data; review as possible session, nonce, hash, or scenario id."
                },
            )
        }
        return result
    }

    private fun String.isCandidateToken(kind: String): Boolean =
        length >= (if (kind == "numeric-id") 4 else 8) &&
            !contains("__") &&
            LOW_VALUE_TOKENS.none { contains(it, ignoreCase = true) } &&
            !startsWith("http", ignoreCase = true)

    private fun String.preview(): String =
        if (length <= 20) this else take(10) + "..." + takeLast(6)

    private fun priorityFor(kind: String, propertyName: String, value: String): Int {
        val context = "$propertyName\n$value"
        val base = when (kind) {
            "bearer-token", "csrf-or-verification-token", "credential" -> 100
            "path-opaque-id" -> 95
            "hex-id" -> 92
            "uuid" -> 90
            "epoch-ms" -> 80
            "draw-id" -> 78
            "date-time" -> 70
            "opaque-token" -> 60
            "numeric-id" -> 45
            else -> 50
        }
        val dynamicFieldBoost = if (DYNAMIC_FIELD_HINTS.any { context.contains(it, ignoreCase = true) }) {
            20
        } else {
            0
        }
        val requestDataBoost = if (REQUEST_DATA_HINTS.any { context.contains(it, ignoreCase = true) }) {
            10
        } else {
            0
        }
        val lowSignalPenalty = when {
            STATIC_ASSET_HINTS.any { context.contains(it, ignoreCase = true) } -> 40
            TELEMETRY_HINTS.any { context.contains(it, ignoreCase = true) } -> 30
            else -> 0
        }
        return (base + dynamicFieldBoost + requestDataBoost - lowSignalPenalty).coerceIn(0, 130)
    }

    private fun isLowSignalRequestContext(sampler: SamplerContext, propertyName: String, value: String): Boolean {
        if (!includeStaticAssetRequests && sampler.staticAssetRequest) {
            return true
        }
        val context = "${sampler.name}\n$propertyName\n$value"
        return !includeStaticAssetRequests && STATIC_ASSET_HINTS.any { context.contains(it, ignoreCase = true) } ||
            TELEMETRY_HINTS.any { context.contains(it, ignoreCase = true) }
    }

    private fun fieldNameBefore(value: String, tokenStart: Int): String? {
        val prefix = value.take(tokenStart).takeLast(120)
        JSON_FIELD_BEFORE_TOKEN.find(prefix)?.let { return it.groupValues[1] }
        FORM_FIELD_BEFORE_TOKEN.find(prefix)?.let { return it.groupValues[1] }
        return null
    }

    private data class SamplerContext(
        val index: Int,
        val name: String,
        val transactionName: String?,
        val staticAssetRequest: Boolean,
    )

    private fun TestElement.isStaticAssetRequest(): Boolean =
        staticAssetTextCandidates().any { STATIC_ASSET_REQUEST_REGEX.containsMatchIn(it) }

    private fun TestElement.staticAssetTextCandidates(): List<String> {
        val values = mutableListOf(name.orEmpty())
        val iterator = propertyIterator()
        while (iterator.hasNext()) {
            val property = iterator.next()
            val propertyName = property.name.lowercase()
            if (propertyName.contains("path") ||
                propertyName.contains("url") ||
                propertyName.contains("filename")
            ) {
                val value = when (property) {
                    is StringProperty -> property.stringValue
                    is ObjectProperty -> property.objectValue as? String
                    else -> null
                }
                if (!value.isNullOrBlank()) {
                    values += value
                }
            }
        }
        return values
    }

    private companion object {
        val UUID_REGEX: Regex =
            Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
        val EPOCH_MS_REGEX: Regex = Regex("""(?<!\d)1[6-9]\d{11}(?!\d)""")
        val HEX_ID_REGEX: Regex =
            Regex("""(?i)(?<![0-9a-f])(?=[0-9a-f]*[a-f])(?=[0-9a-f]*\d)[0-9a-f]{32,}(?![0-9a-f])""")
        val DATE_TIME_REGEX: Regex =
            Regex("""(?<!\d)(?:20\d{2}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/]\d{1,2}[-/]20\d{2})(?:[T\s]+\d{1,2}:\d{2}(?::\d{2})?(?:\.\d{1,6})?(?:Z|[+-]\d{2}:?\d{2})?)?(?!\d)""")
        val BEARER_REGEX: Regex = Regex("""(?i)\bBearer\s+([A-Za-z0-9._~+/=-]{16,})""")
        val QUERY_ID_REGEX: Regex = Regex("""(?i)([?&][A-Za-z0-9_-]*id=)(\d{4,})""")
        val CREDENTIAL_FIELD_VALUE_REGEX: Regex =
            Regex("""(?i)(?:["']?([A-Za-z0-9_.-]*(?:user(?:name)?|login|email|password|passwd|pwd)[A-Za-z0-9_.-]*)["']?\s*[:=]\s*(?:"([^"]{3,160})"|'([^']{3,160})'|([^&\s,;}]{3,160})))""")
        val FIELD_VALUE_REGEX: Regex =
            Regex("""(?i)(?:["']?([A-Za-z0-9_.-]{1,80})["']?\s*[:=]\s*(?:"([^"]{3,160})"|'([^']{3,160})'|([^&\s,;}]{3,160})))""")
        val DRAW_ID_VALUE_REGEX: Regex = Regex("""(?i)(?:20\d{6}[a-z]{2,5}|[a-z]{2,8}[-_]?\d{4,12}|\d{6,14})""")
        val OPAQUE_TOKEN_REGEX: Regex =
            Regex("""(?<![A-Za-z0-9])(?=[A-Za-z0-9._~-]*[A-Za-z])(?=[A-Za-z0-9._~-]*\d)[A-Za-z0-9._~-]{24,}(?![A-Za-z0-9])""")
        val STATIC_ASSET_REQUEST_REGEX: Regex =
            Regex("""(?i)(?:^|[\s"'=,(])\S+\.(?:css|js|mjs|map|png|jpg|jpeg|gif|svg|ico|webp|avif|woff|woff2|ttf|eot)(?:[?#]\S*)?(?:$|[\s"',)])""")
        val JSON_FIELD_BEFORE_TOKEN: Regex = Regex(""""([^"]{1,80})"\s*:\s*"?$""")
        val FORM_FIELD_BEFORE_TOKEN: Regex = Regex("""(?:^|[?&{,;]\s*)([A-Za-z0-9_.-]{1,80})\s*[=:]\s*"?$""")
        val LOW_VALUE_TOKENS: List<String> = listOf(
            "application",
            "javascript",
            "stylesheet",
            "fontawesome",
            "bootstrap",
            "cloudflare",
            "localhost",
            "127.0.0.1",
            "schema.org",
        )
        val STATIC_ASSET_HINTS: List<String> = listOf(
            ".css",
            ".js",
            ".mjs",
            ".map",
            ".png",
            ".jpg",
            ".jpeg",
            ".gif",
            ".svg",
            ".ico",
            ".webp",
            ".woff",
            ".woff2",
            "/assets/",
            "/static/",
            "/fonts/",
        )
        val REQUEST_DATA_HINTS: List<String> = listOf(
            "/api/",
            "post",
            "body",
            "argument",
            "query",
            "path",
            "header",
            "authorization",
            "cookie",
        )
        val DYNAMIC_FIELD_HINTS: List<String> = listOf(
            "csrf",
            "verification",
            "token",
            "authorization",
            "id",
            "uuid",
            "guid",
            "nonce",
            "state",
            "session",
            "transaction",
            "basket",
            "cart",
            "order",
            "ticket",
            "product",
            "draw",
            "timestamp",
            "date",
            "time",
            "username",
            "user",
            "login",
            "email",
            "password",
            "passwd",
            "pwd",
        )
        val TELEMETRY_HINTS: List<String> = listOf(
            "traceparent",
            "tracestate",
            "request-id",
            "x-request-id",
            "x-correlation-id",
            "x-datadog",
            "newrelic",
            "elastic-apm",
        )
        val CLIENT_GENERATED_UUID_FIELDS: List<String> = listOf(
            "transactionid",
            "transaction_id",
            "basketitemid",
            "basket_item_id",
            "cartitemid",
            "cart_item_id",
            "clientid",
            "client_id",
            "correlationid",
            "correlation_id",
            "requestid",
            "request_id",
        )
    }
}
