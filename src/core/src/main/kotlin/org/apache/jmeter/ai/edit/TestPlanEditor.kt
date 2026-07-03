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
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.MultiProperty
import org.apache.jmeter.testelement.property.ObjectProperty
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jorphan.collections.HashTree
import java.util.IdentityHashMap

public data class BoundaryCorrelationRequest(
    val sourceSamplerIndex: Int? = null,
    val sourceSamplerLabel: String? = null,
    val targetSamplerIndex: Int? = null,
    val targetSamplerLabel: String? = null,
    val variableName: String,
    val leftBoundary: String,
    val rightBoundary: String,
    val literal: String,
    val failOnNoMatch: Boolean = true,
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
    val literal: String? = null,
    val failOnNoMatch: Boolean = true,
)

public data class LiteralReplacementRequest(
    val targetNodeId: String? = null,
    val targetSamplerIndex: Int? = null,
    val targetSamplerLabel: String? = null,
    val targetNodePath: String? = null,
    val targetOccurrenceIndex: Int? = null,
    val scopeNodePath: String? = null,
    val threadGroupName: String? = null,
    val allowWholePlan: Boolean = false,
    val literal: String,
    val replacement: String,
    val includeNames: Boolean = false,
    val excludeUserDefinedVariables: Boolean = false,
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

public data class RegexExtractorUpdateRequest(
    val sourceSamplerIndex: Int? = null,
    val sourceSamplerLabel: String? = null,
    val variableName: String,
    val regex: String? = null,
    val template: String? = null,
    val matchNumber: String? = null,
    val defaultValue: String? = null,
    val useField: String? = null,
    val failOnNoMatch: Boolean? = null,
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

private const val TEST_PLAN_USER_DEFINED_VARIABLES = "TestPlan.user_defined_variables"

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
        configureRegexExtractor(extractor, request)
        return extractor
    }

    public fun configureRegexExtractor(extractor: TestElement, request: RegexCorrelationRequest) {
        val clazz = extractor::class.java
        extractor.name = "AI Regex Extractor - ${request.variableName}"
        clazz.getMethod("setRefName", String::class.java).invoke(extractor, request.variableName)
        clazz.getMethod("setRegex", String::class.java).invoke(extractor, request.regex)
        clazz.getMethod("setTemplate", String::class.java).invoke(extractor, request.template)
        clazz.getMethod("setDefaultValue", String::class.java).invoke(extractor, request.defaultValue)
        clazz.getMethod("setMatchNumber", String::class.java).invoke(extractor, request.matchNumber)
        clazz.getMethod("setUseField", String::class.java).invoke(extractor, normalizeExtractorUseField(request.useField))
        clazz.getMethod("setFailOnNoMatch", Boolean::class.javaPrimitiveType).invoke(extractor, request.failOnNoMatch)
    }

    public fun updateRegexExtractor(extractor: TestElement, request: RegexExtractorUpdateRequest) {
        val clazz = extractor::class.java
        request.regex?.let { clazz.getMethod("setRegex", String::class.java).invoke(extractor, it) }
        request.template?.let { clazz.getMethod("setTemplate", String::class.java).invoke(extractor, it) }
        request.defaultValue?.let { clazz.getMethod("setDefaultValue", String::class.java).invoke(extractor, it) }
        request.matchNumber?.let { clazz.getMethod("setMatchNumber", String::class.java).invoke(extractor, it) }
        request.useField?.let {
            clazz.getMethod("setUseField", String::class.java).invoke(extractor, normalizeExtractorUseField(it))
        }
        request.failOnNoMatch?.let {
            clazz.getMethod("setFailOnNoMatch", Boolean::class.javaPrimitiveType).invoke(extractor, it)
        }
    }

    public fun createResponseAssertion(request: ResponseAssertionRequest): TestElement {
        val clazz = Class.forName("org.apache.jmeter.assertions.ResponseAssertion")
        val assertion = clazz.getDeclaredConstructor().newInstance() as TestElement
        configureResponseAssertion(assertion, request)
        return assertion
    }

    public fun configureResponseAssertion(
        assertion: TestElement,
        request: ResponseAssertionRequest,
        clearExisting: Boolean = false,
    ) {
        val clazz = assertion::class.java
        assertion.name = request.assertionName
        if (clearExisting) {
            clazz.getMethod("clearTestStrings").invoke(assertion)
        }
        when (request.field.lowercase()) {
            "headers", "responseheaders" -> clazz.getMethod("setTestFieldResponseHeaders").invoke(assertion)
            "code", "responsecode" -> clazz.getMethod("setTestFieldResponseCode").invoke(assertion)
            "message", "responsemessage" -> clazz.getMethod("setTestFieldResponseMessage").invoke(assertion)
            "url" -> clazz.getMethod("setTestFieldURL").invoke(assertion)
            else -> clazz.getMethod("setTestFieldResponseData").invoke(assertion)
        }
        when (request.matchType.lowercase()) {
            // JMeter's "Contains" type is a Perl5 regex match, which silently breaks
            // literal markers holding ? [ ] etc. Agents saying "contains" mean a
            // literal substring, so both map to the substring type; regex matching
            // must be requested explicitly.
            "matches", "match", "regex" -> clazz.getMethod("setToMatchType").invoke(assertion)
            "regex_contains", "contains_regex" -> clazz.getMethod("setToContainsType").invoke(assertion)
            "equals" -> clazz.getMethod("setToEqualsType").invoke(assertion)
            else -> clazz.getMethod("setToSubstringType").invoke(assertion)
        }
        clazz.getMethod("addTestString", String::class.java).invoke(assertion, request.pattern)
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
        configureBoundaryExtractor(extractor, request)
        return extractor
    }

    public fun configureBoundaryExtractor(extractor: TestElement, request: BoundaryCorrelationRequest) {
        val clazz = extractor::class.java
        extractor.name = "AI Boundary Extractor - ${request.variableName}"
        clazz.getMethod("setRefName", String::class.java).invoke(extractor, request.variableName)
        clazz.getMethod("setLeftBoundary", String::class.java).invoke(extractor, request.leftBoundary)
        clazz.getMethod("setRightBoundary", String::class.java).invoke(extractor, request.rightBoundary)
        clazz.getMethod("setDefaultValue", String::class.java).invoke(extractor, "NOT_FOUND")
        clazz.getMethod("setMatchNumber", String::class.java).invoke(extractor, "1")
        clazz.getMethod("setFailOnNoMatch", Boolean::class.javaPrimitiveType).invoke(extractor, request.failOnNoMatch)
    }

    public fun normalizeExtractorUseField(useField: String): String =
        when (useField.lowercase()) {
            "headers", "responseheaders", "response_headers", "response-headers", "header", "true" -> "true"
            "requestheaders", "request_headers", "request-headers" -> "request_headers"
            "body", "responsedata", "response_data", "response-data", "false" -> "false"
            "unescaped", "bodyunescaped", "body_unescaped", "body-unescaped" -> "unescaped"
            "document", "asdocument", "as_document", "as-document" -> "as_document"
            "url" -> "URL"
            "code", "responsecode", "response_code", "response-code" -> "code"
            "message", "responsemessage", "response_message", "response-message" -> "message"
            else -> useField
        }

    public fun replaceLiteral(
        element: TestElement,
        subTree: HashTree,
        literal: String,
        replacement: String,
        includeNames: Boolean = false,
        excludeUserDefinedVariables: Boolean = false,
    ): Int {
        val visitedElements = IdentityHashMap<TestElement, Boolean>()
        val visitedProperties = IdentityHashMap<JMeterProperty, Boolean>()
        var replacements = replaceLiteralInElement(
            element,
            literal,
            replacement,
            includeNames,
            excludeUserDefinedVariables,
            visitedElements,
            visitedProperties,
        )
        for (child in subTree.list()) {
            if (child is TestElement) {
                replacements += replaceLiteral(
                    child,
                    subTree.getTree(child),
                    literal,
                    replacement,
                    includeNames,
                    excludeUserDefinedVariables,
                    visitedElements,
                    visitedProperties,
                )
            }
        }
        return replacements
    }

    public fun replaceLiteralInTree(
        tree: HashTree,
        literal: String,
        replacement: String,
        includeNames: Boolean = false,
        excludeUserDefinedVariables: Boolean = false,
    ): Int {
        val visitedElements = IdentityHashMap<TestElement, Boolean>()
        val visitedProperties = IdentityHashMap<JMeterProperty, Boolean>()
        return replaceLiteralInTree(
            tree,
            literal,
            replacement,
            includeNames,
            excludeUserDefinedVariables,
            visitedElements,
            visitedProperties,
        )
    }

    public fun replaceLiteralInNamesInTree(
        tree: HashTree,
        literal: String,
        replacement: String,
    ): Int {
        val visitedElements = IdentityHashMap<TestElement, Boolean>()
        fun visit(currentTree: HashTree): Int {
            var replacements = 0
            for (node in currentTree.list()) {
                if (node is TestElement && visitedElements.put(node, true) == null) {
                    val currentName = node.name.orEmpty()
                    if (currentName.contains(literal)) {
                        node.name = currentName.replace(literal, replacement)
                        replacements++
                    }
                }
                replacements += visit(currentTree.getTree(node))
            }
            return replacements
        }
        return visit(tree)
    }

    private fun replaceLiteral(
        element: TestElement,
        subTree: HashTree,
        literal: String,
        replacement: String,
        includeNames: Boolean,
        excludeUserDefinedVariables: Boolean,
        visitedElements: IdentityHashMap<TestElement, Boolean>,
        visitedProperties: IdentityHashMap<JMeterProperty, Boolean>,
    ): Int {
        var replacements = replaceLiteralInElement(
            element,
            literal,
            replacement,
            includeNames,
            excludeUserDefinedVariables,
            visitedElements,
            visitedProperties,
        )
        for (child in subTree.list()) {
            if (child is TestElement) {
                replacements += replaceLiteral(
                    child,
                    subTree.getTree(child),
                    literal,
                    replacement,
                    includeNames,
                    excludeUserDefinedVariables,
                    visitedElements,
                    visitedProperties,
                )
            }
        }
        return replacements
    }

    private fun replaceLiteralInTree(
        tree: HashTree,
        literal: String,
        replacement: String,
        includeNames: Boolean,
        excludeUserDefinedVariables: Boolean,
        visitedElements: IdentityHashMap<TestElement, Boolean>,
        visitedProperties: IdentityHashMap<JMeterProperty, Boolean>,
    ): Int {
        var replacements = 0
        for (node in tree.list()) {
            if (node is TestElement) {
                replacements += replaceLiteral(
                    node,
                    tree.getTree(node),
                    literal,
                    replacement,
                    includeNames,
                    excludeUserDefinedVariables,
                    visitedElements,
                    visitedProperties,
                )
            } else {
                replacements += replaceLiteralInTree(
                    tree.getTree(node),
                    literal,
                    replacement,
                    includeNames,
                    excludeUserDefinedVariables,
                    visitedElements,
                    visitedProperties,
                )
            }
        }
        return replacements
    }

    private fun replaceLiteralInElement(
        element: TestElement,
        literal: String,
        replacement: String,
        includeNames: Boolean,
        excludeUserDefinedVariables: Boolean,
        visitedElements: IdentityHashMap<TestElement, Boolean>,
        visitedProperties: IdentityHashMap<JMeterProperty, Boolean>,
    ): Int {
        if (visitedElements.put(element, true) != null) {
            return 0
        }
        var replacements = 0
        val properties = mutableListOf<JMeterProperty>()
        val iterator = element.propertyIterator()
        while (iterator.hasNext()) {
            properties += iterator.next()
        }
        for (property in properties) {
            replacements += replaceLiteralInProperty(
                property,
                literal,
                replacement,
                includeNames,
                excludeUserDefinedVariables,
                visitedElements,
                visitedProperties,
            )
        }
        return replacements
    }

    private fun replaceLiteralInProperty(
        property: JMeterProperty,
        literal: String,
        replacement: String,
        includeNames: Boolean,
        excludeUserDefinedVariables: Boolean,
        visitedElements: IdentityHashMap<TestElement, Boolean>,
        visitedProperties: IdentityHashMap<JMeterProperty, Boolean>,
    ): Int {
        if (visitedProperties.put(property, true) != null) {
            return 0
        }
        if (!includeNames && property.name == TestElement.NAME) {
            return 0
        }
        if (excludeUserDefinedVariables && property.name == TEST_PLAN_USER_DEFINED_VARIABLES) {
            return 0
        }
        var replacements = 0
        if (property is StringProperty && property.stringValue.contains(literal)) {
            property.setObjectValue(property.stringValue.replace(literal, replacement))
            replacements++
        } else if (property is ObjectProperty && property.objectValue is String) {
            val value = property.objectValue as String
            if (value.contains(literal)) {
                property.setObjectValue(value.replace(literal, replacement))
                replacements++
            }
        }
        if (property is TestElementProperty && property.objectValue is TestElement) {
            replacements += replaceLiteralInElement(
                property.objectValue as TestElement,
                literal,
                replacement,
                includeNames,
                excludeUserDefinedVariables,
                visitedElements,
                visitedProperties,
            )
        } else if (property is CollectionProperty || property is MultiProperty) {
            for (child in property as MultiProperty) {
                replacements += replaceLiteralInProperty(
                    child,
                    literal,
                    replacement,
                    includeNames,
                    excludeUserDefinedVariables,
                    visitedElements,
                    visitedProperties,
                )
            }
        }
        return replacements
    }

    private data class SamplerNode(
        val element: TestElement,
        val subTree: HashTree,
    )
}
