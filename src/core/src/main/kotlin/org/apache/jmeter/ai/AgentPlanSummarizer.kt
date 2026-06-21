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

public data class AgentPlanContext(
    val dsl: String,
    val elementCount: Int,
    val samplerCount: Int,
    val samplerTruncated: Boolean = false,
    val samplers: List<AgentSamplerContext> = emptyList(),
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
        val transactionNames = mutableListOf<String>()
        private val transactionStack = mutableListOf<String>()
        private val transactionNodeStack = mutableListOf<Boolean>()

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
                if (samplers.size < samplerLimit) {
                    samplers += AgentSamplerContext(
                        index = samplerCount,
                        name = (node as? TestElement)?.name.orEmpty(),
                        className = node::class.java.name,
                        transactionName = transactionStack.lastOrNull(),
                    )
                } else {
                    samplerTruncated = true
                }
                samplerCount++
            }
        }

        override fun subtractNode() {
            val transactionNode = transactionNodeStack.removeAt(transactionNodeStack.lastIndex)
            if (transactionNode) {
                transactionStack.removeAt(transactionStack.lastIndex)
            }
        }

        override fun processPath() {
        }
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
