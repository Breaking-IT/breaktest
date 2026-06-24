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

import org.apache.jmeter.dsl.DslPrinterTraverser
import org.apache.jmeter.control.Controller
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.testelement.TestElement
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.HashTreeTraverser

public data class AgentSamplerContext(
    val index: Int,
    val name: String,
    val className: String,
    val transactionName: String? = null,
)

public data class AgentExtractorContext(
    val samplerIndex: Int? = null,
    val samplerName: String? = null,
    val transactionName: String? = null,
    val name: String,
    val className: String,
    val variableName: String? = null,
    val regex: String? = null,
    val leftBoundary: String? = null,
    val rightBoundary: String? = null,
    val useField: String? = null,
    val matchNumber: String? = null,
    val defaultValue: String? = null,
    val failOnNoMatch: Boolean? = null,
)

public data class AgentPlanContext(
    val dsl: String,
    val elementCount: Int,
    val samplerCount: Int,
    val samplerTruncated: Boolean = false,
    val samplers: List<AgentSamplerContext> = emptyList(),
    val extractors: List<AgentExtractorContext> = emptyList(),
    val transactionNames: List<String> = emptyList(),
    val dynamicValueCandidates: List<AgentDynamicValueCandidate> = emptyList(),
    val dslCharacterCount: Int = dsl.length,
    val dslTruncated: Boolean = false,
)

public class AgentPlanSummarizer(
    private val dynamicValueAnalyzer: AgentDynamicValueAnalyzer = AgentDynamicValueAnalyzer(),
) {
    public fun summarize(
        testTree: HashTree,
        dslCharacterLimit: Int? = null,
        samplerLimit: Int = 500,
    ): AgentPlanContext {
        val dslPrinter = DslPrinterTraverser()
        val counter = Counter(samplerLimit)
        testTree.traverse(dslPrinter)
        testTree.traverse(counter)
        val fullDsl = dslPrinter.toString()
        val dsl = fullDsl.truncateTo(dslCharacterLimit)
        return AgentPlanContext(
            dsl = dsl,
            elementCount = counter.elementCount,
            samplerCount = counter.samplerCount,
            samplerTruncated = counter.samplerTruncated,
            samplers = counter.samplers,
            extractors = counter.extractors,
            transactionNames = counter.transactionNames,
            dynamicValueCandidates = dynamicValueAnalyzer.analyze(testTree),
            dslCharacterCount = fullDsl.length,
            dslTruncated = dsl.length < fullDsl.length,
        )
    }

    private class Counter(
        private val samplerLimit: Int,
    ) : HashTreeTraverser {
        var elementCount = 0
            private set
        var samplerCount = 0
            private set
        var samplerTruncated = false
            private set
        val samplers = mutableListOf<AgentSamplerContext>()
        val extractors = mutableListOf<AgentExtractorContext>()
        val transactionNames = mutableListOf<String>()
        private val transactionStack = mutableListOf<String>()
        private val transactionNodeStack = mutableListOf<Boolean>()
        private val samplerStack = mutableListOf<AgentSamplerContext>()
        private val samplerNodeStack = mutableListOf<Boolean>()

        override fun addNode(node: Any, subTree: HashTree) {
            if (node is TestElement) {
                elementCount++
            }
            val nodeName = (node as? TestElement)?.name
            var transactionNode = false
            if (node is TransactionController) {
                transactionStack += nodeName.orEmpty()
                transactionNames += nodeName.orEmpty()
                transactionNode = true
            } else if (node is Controller && nodeName?.contains("transaction", ignoreCase = true) == true) {
                transactionStack += nodeName
                transactionNames += nodeName
                transactionNode = true
            }
            transactionNodeStack += transactionNode
            if (node is Sampler) {
                val samplerContext = AgentSamplerContext(
                    index = samplerCount,
                    name = (node as? TestElement)?.name.orEmpty(),
                    className = node::class.java.name,
                    transactionName = transactionStack.lastOrNull(),
                )
                if (samplers.size < samplerLimit) {
                    samplers += samplerContext
                } else {
                    samplerTruncated = true
                }
                samplerStack += samplerContext
                samplerCount++
            }
            samplerNodeStack += node is Sampler
            if (node is TestElement && isExtractor(node)) {
                extractors += extractorContext(node, samplerStack.lastOrNull(), transactionStack.lastOrNull())
            }
        }

        override fun subtractNode() {
            val samplerNode = samplerNodeStack.removeAt(samplerNodeStack.lastIndex)
            if (samplerNode) {
                samplerStack.removeAt(samplerStack.lastIndex)
            }
            val transactionNode = transactionNodeStack.removeAt(transactionNodeStack.lastIndex)
            if (transactionNode) {
                transactionStack.removeAt(transactionStack.lastIndex)
            }
        }

        override fun processPath() {
        }

        private fun isExtractor(element: TestElement): Boolean =
            element::class.java.name in setOf(
                "org.apache.jmeter.extractor.RegexExtractor",
                "org.apache.jmeter.extractor.BoundaryExtractor",
            ) || callString(element, "getRefName") != null &&
                (callString(element, "getRegex") != null || callString(element, "getLeftBoundary") != null)

        private fun extractorContext(
            element: TestElement,
            sampler: AgentSamplerContext?,
            transactionName: String?,
        ): AgentExtractorContext =
            AgentExtractorContext(
                samplerIndex = sampler?.index,
                samplerName = sampler?.name,
                transactionName = sampler?.transactionName ?: transactionName,
                name = element.name.orEmpty(),
                className = element::class.java.name,
                variableName = callString(element, "getRefName"),
                regex = callString(element, "getRegex"),
                leftBoundary = callString(element, "getLeftBoundary"),
                rightBoundary = callString(element, "getRightBoundary"),
                useField = extractorUseField(element),
                matchNumber = callString(element, "getMatchNumberAsString"),
                defaultValue = callString(element, "getDefaultValue"),
                failOnNoMatch = callBoolean(element, "isFailOnNoMatch"),
            )

        private fun extractorUseField(element: TestElement): String? {
            val clazz = element::class.java
            return when {
                callBoolean(element, "useHeaders") == true -> "headers"
                callBoolean(element, "useRequestHeaders") == true -> "request_headers"
                callBoolean(element, "useUnescapedBody") == true -> "unescaped"
                callBoolean(element, "useBodyAsDocument") == true -> "as_document"
                callBoolean(element, "useUrl") == true -> "url"
                callBoolean(element, "useCode") == true -> "code"
                callBoolean(element, "useMessage") == true -> "message"
                runCatching { clazz.getMethod("useBody") }.isSuccess && callBoolean(element, "useBody") == true -> "body"
                else -> null
            }
        }

        private fun callString(element: TestElement, methodName: String): String? =
            runCatching { element::class.java.getMethod(methodName).invoke(element) as? String }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }

        private fun callBoolean(element: TestElement, methodName: String): Boolean? =
            runCatching { element::class.java.getMethod(methodName).invoke(element) as? Boolean }.getOrNull()
    }

    private fun String.truncateTo(limit: Int?): String {
        if (limit == null || limit < 0 || length <= limit) {
            return this
        }
        if (limit == 0) {
            return "... [DSL omitted $length chars]"
        }
        return take(limit) + "\n... [DSL truncated ${length - limit} chars]"
    }
}
