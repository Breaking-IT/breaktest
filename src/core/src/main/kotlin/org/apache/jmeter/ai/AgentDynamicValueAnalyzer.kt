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

import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.control.Controller
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.MultiProperty
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jorphan.collections.HashTree

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
)

public class AgentDynamicValueAnalyzer {
    public fun analyze(testTree: HashTree, limit: Int = 200): List<AgentDynamicValueCandidate> {
        val candidates = mutableListOf<AgentDynamicValueCandidate>()
        var samplerIndex = -1

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
                        name = (node as? TestElement)?.name.orEmpty(),
                        transactionName = transaction,
                    )
                } else {
                    currentSampler
                }
                if (node is TestElement && sampler != null && candidates.size < limit) {
                    scanElement(node, sampler, candidates, limit)
                }
                if (candidates.size < limit) {
                    visit(subTree, sampler, transaction)
                }
            }
        }

        visit(testTree, null, null)
        return candidates.distinctBy { "${it.samplerIndex}:${it.propertyName}:${it.literal}:${it.kind}" }
            .take(limit)
    }

    private fun scanElement(
        element: TestElement,
        sampler: SamplerContext,
        candidates: MutableList<AgentDynamicValueCandidate>,
        limit: Int,
    ) {
        val iterator = element.propertyIterator()
        while (iterator.hasNext() && candidates.size < limit) {
            scanProperty(iterator.next(), element, sampler, candidates, limit)
        }
    }

    private fun scanProperty(
        property: JMeterProperty,
        owner: TestElement,
        sampler: SamplerContext,
        candidates: MutableList<AgentDynamicValueCandidate>,
        limit: Int,
    ) {
        if (property is StringProperty) {
            candidates += candidatesFrom(owner, sampler, property.name, property.stringValue)
                .take(limit - candidates.size)
        }
        if (property is MultiProperty) {
            val iterator = property.iterator()
            while (iterator.hasNext() && candidates.size < limit) {
                scanProperty(iterator.next(), owner, sampler, candidates, limit)
            }
        }
    }

    private fun candidatesFrom(
        owner: TestElement,
        sampler: SamplerContext,
        propertyName: String,
        value: String,
    ): List<AgentDynamicValueCandidate> {
        if (value.isBlank() || value.contains("\${")) {
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
            add(
                "uuid",
                match.value,
                "Hard-coded UUID/GUID in request data; verify source in earlier response or randomize from prior listing.",
            )
        }
        for (match in EPOCH_MS_REGEX.findAll(value)) {
            add(
                "epoch-ms",
                match.value,
                "Hard-coded millisecond timestamp; correlate from earlier response or generate current epoch milliseconds.",
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
        for (match in OPAQUE_TOKEN_REGEX.findAll(value)) {
            add(
                "opaque-token",
                match.value,
                "Long mixed alpha-numeric literal in request data; review as possible session, nonce, hash, or scenario id.",
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

    private data class SamplerContext(
        val index: Int,
        val name: String,
        val transactionName: String?,
    )

    private companion object {
        val UUID_REGEX: Regex =
            Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b""")
        val EPOCH_MS_REGEX: Regex = Regex("""(?<!\d)1[6-9]\d{11}(?!\d)""")
        val BEARER_REGEX: Regex = Regex("""(?i)\bBearer\s+([A-Za-z0-9._~+/=-]{16,})""")
        val QUERY_ID_REGEX: Regex = Regex("""(?i)([?&][A-Za-z0-9_-]*id=)(\d{4,})""")
        val OPAQUE_TOKEN_REGEX: Regex =
            Regex("""(?<![A-Za-z0-9])(?=[A-Za-z0-9._~-]*[A-Za-z])(?=[A-Za-z0-9._~-]*\d)[A-Za-z0-9._~-]{24,}(?![A-Za-z0-9])""")
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
    }
}
