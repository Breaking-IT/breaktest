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

package org.apache.jmeter.ai.edit

import org.apache.jmeter.samplers.Sampler
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jorphan.collections.HashTree

public data class BoundaryCorrelationRequest(
    val sourceSamplerIndex: Int? = null,
    val sourceSamplerLabel: String? = null,
    val targetSamplerIndex: Int? = null,
    val targetSamplerLabel: String? = null,
    val variableName: String,
    val leftBoundary: String,
    val rightBoundary: String,
    val literal: String,
)

public data class RegexCorrelationRequest(
    val sourceSamplerIndex: Int? = null,
    val sourceSamplerLabel: String? = null,
    val targetSamplerIndex: Int? = null,
    val targetSamplerLabel: String? = null,
    val variableName: String,
    val regex: String,
    val template: String = "$1$",
    val matchNumber: String = "1",
    val defaultValue: String = "NOT_FOUND",
    val useField: String = "body",
    val literal: String,
)

public data class LiteralReplacementRequest(
    val targetSamplerIndex: Int? = null,
    val targetSamplerLabel: String? = null,
    val literal: String,
    val replacement: String,
)

public data class ResponseAssertionRequest(
    val targetSamplerIndex: Int? = null,
    val targetSamplerLabel: String? = null,
    val assertionName: String,
    val pattern: String,
    val field: String = "body",
    val matchType: String = "substring",
)

public data class RedirectModeRequest(
    val targetSamplerIndex: Int? = null,
    val targetSamplerLabel: String? = null,
    val followRedirects: Boolean? = null,
    val autoRedirects: Boolean? = null,
)

public data class TestPlanEditResult(
    val sourceSamplerLabel: String,
    val targetSamplerLabel: String,
    val variableReference: String,
    val replacements: Int,
    val extractorClass: String,
)

public data class LiteralReplacementResult(
    val targetSamplerLabel: String,
    val replacements: Int,
)

public class TestPlanEditor {
    public fun applyBoundaryCorrelation(
        tree: HashTree,
        request: BoundaryCorrelationRequest,
    ): TestPlanEditResult {
        val samplers = findSamplerNodes(tree)
        val source = selectSampler(samplers, request.sourceSamplerIndex, request.sourceSamplerLabel, "source")
        val target = selectSampler(samplers, request.targetSamplerIndex, request.targetSamplerLabel, "target")
        val extractor = createBoundaryExtractor(request)
        source.subTree.add(extractor)
        val variableReference = "\${${request.variableName}}"
        val replacements = replaceLiteral(target.element, target.subTree, request.literal, variableReference)
        require(replacements > 0) {
            "Literal '${request.literal}' was not found under target sampler '${target.element.name}'"
        }
        return TestPlanEditResult(
            sourceSamplerLabel = source.element.name.orEmpty(),
            targetSamplerLabel = target.element.name.orEmpty(),
            variableReference = variableReference,
            replacements = replacements,
            extractorClass = extractor::class.java.name,
        )
    }

    public fun createRegexExtractor(request: RegexCorrelationRequest): TestElement {
        val clazz = Class.forName("org.apache.jmeter.extractor.RegexExtractor")
        val extractor = clazz.getDeclaredConstructor().newInstance() as TestElement
        extractor.name = "AI Regex Extractor - ${request.variableName}"
        clazz.getMethod("setRefName", String::class.java).invoke(extractor, request.variableName)
        clazz.getMethod("setRegex", String::class.java).invoke(extractor, request.regex)
        clazz.getMethod("setTemplate", String::class.java).invoke(extractor, request.template)
        clazz.getMethod("setDefaultValue", String::class.java).invoke(extractor, request.defaultValue)
        clazz.getMethod("setMatchNumber", String::class.java).invoke(extractor, request.matchNumber)
        clazz.getMethod("setUseField", String::class.java).invoke(extractor, request.useField)
        return extractor
    }

    public fun createResponseAssertion(request: ResponseAssertionRequest): TestElement {
        val clazz = Class.forName("org.apache.jmeter.assertions.ResponseAssertion")
        val assertion = clazz.getDeclaredConstructor().newInstance() as TestElement
        assertion.name = request.assertionName
        when (request.field.lowercase()) {
            "headers", "responseheaders" -> clazz.getMethod("setTestFieldResponseHeaders").invoke(assertion)
            "code", "responsecode" -> clazz.getMethod("setTestFieldResponseCode").invoke(assertion)
            "message", "responsemessage" -> clazz.getMethod("setTestFieldResponseMessage").invoke(assertion)
            "url" -> clazz.getMethod("setTestFieldURL").invoke(assertion)
            else -> clazz.getMethod("setTestFieldResponseData").invoke(assertion)
        }
        when (request.matchType.lowercase()) {
            "contains" -> clazz.getMethod("setToContainsType").invoke(assertion)
            "matches", "match", "regex" -> clazz.getMethod("setToMatchType").invoke(assertion)
            "equals" -> clazz.getMethod("setToEqualsType").invoke(assertion)
            else -> clazz.getMethod("setToSubstringType").invoke(assertion)
        }
        clazz.getMethod("addTestString", String::class.java).invoke(assertion, request.pattern)
        return assertion
    }

    private fun selectSampler(
        samplers: List<SamplerNode>,
        index: Int?,
        label: String?,
        role: String,
    ): SamplerNode {
        if (index != null) {
            return samplers.getOrNull(index)
                ?: throw IllegalArgumentException("No $role sampler at index $index")
        }
        if (!label.isNullOrBlank()) {
            return samplers.singleOrNull { it.element.name == label }
                ?: throw IllegalArgumentException("Expected exactly one $role sampler named '$label'")
        }
        throw IllegalArgumentException("Specify ${role}SamplerIndex or ${role}SamplerLabel")
    }

    private fun findSamplerNodes(tree: HashTree): List<SamplerNode> {
        val result = mutableListOf<SamplerNode>()
        fun visit(currentTree: HashTree) {
            for (node in currentTree.list()) {
                val subTree = currentTree.getTree(node)
                if (node is Sampler) {
                    result += SamplerNode(node, subTree)
                }
                visit(subTree)
            }
        }
        visit(tree)
        return result
    }

    public fun createBoundaryExtractor(request: BoundaryCorrelationRequest): TestElement {
        val clazz = Class.forName("org.apache.jmeter.extractor.BoundaryExtractor")
        val extractor = clazz.getDeclaredConstructor().newInstance() as TestElement
        extractor.name = "AI Boundary Extractor - ${request.variableName}"
        clazz.getMethod("setRefName", String::class.java).invoke(extractor, request.variableName)
        clazz.getMethod("setLeftBoundary", String::class.java).invoke(extractor, request.leftBoundary)
        clazz.getMethod("setRightBoundary", String::class.java).invoke(extractor, request.rightBoundary)
        clazz.getMethod("setDefaultValue", String::class.java).invoke(extractor, "NOT_FOUND")
        clazz.getMethod("setMatchNumber", String::class.java).invoke(extractor, "1")
        return extractor
    }

    public fun replaceLiteral(
        element: TestElement,
        subTree: HashTree,
        literal: String,
        replacement: String,
    ): Int {
        var replacements = replaceLiteralInElement(element, literal, replacement)
        for (child in subTree.list()) {
            if (child is TestElement) {
                replacements += replaceLiteral(child, subTree.getTree(child), literal, replacement)
            }
        }
        return replacements
    }

    private fun replaceLiteralInElement(
        element: TestElement,
        literal: String,
        replacement: String,
    ): Int {
        var replacements = 0
        val properties = mutableListOf<JMeterProperty>()
        val iterator = element.propertyIterator()
        while (iterator.hasNext()) {
            properties += iterator.next()
        }
        for (property in properties) {
            if (property is StringProperty && property.stringValue.contains(literal)) {
                property.setObjectValue(property.stringValue.replace(literal, replacement))
                replacements++
            }
        }
        return replacements
    }

    private data class SamplerNode(
        val element: TestElement,
        val subTree: HashTree,
    )
}
