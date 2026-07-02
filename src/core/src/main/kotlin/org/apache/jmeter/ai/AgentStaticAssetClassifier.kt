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

import org.apache.jmeter.samplers.SampleResult
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.property.ObjectProperty
import org.apache.jmeter.testelement.property.StringProperty

internal object AgentStaticAssetClassifier {
    fun isStaticAsset(element: TestElement): Boolean =
        isStaticAssetText(staticAssetTextCandidates(element).joinToString(" "))

    fun isStaticAsset(sample: AgentSampleSummary): Boolean {
        val text = sequenceOf(sample.label, sample.requestBody, sample.requestHeaders)
            .joinToString(" ")
        return isStaticAssetText(text)
    }

    fun isStaticAsset(result: SampleResult): Boolean {
        val text = sequenceOf(
            result.sampleLabel.orEmpty(),
            result.samplerData.orEmpty(),
            result.requestHeaders.orEmpty(),
        ).joinToString(" ")
        return isStaticAssetText(text)
    }

    private fun isStaticAssetText(text: String): Boolean =
        STATIC_ASSET_REGEX.containsMatchIn(text.lowercase())

    private fun staticAssetTextCandidates(element: TestElement): List<String> {
        val values = mutableListOf(element.name.orEmpty())
        val iterator = element.propertyIterator()
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

    private val STATIC_ASSET_REGEX =
        Regex("""(?:\.|/)(?:css|js|mjs|map|png|jpe?g|gif|svg|webp|avif|ico|woff2?|ttf|otf|eot)(?:[?/"'\s]|$)""")
}

internal fun AgentSampleSummary.isStaticAssetRequest(): Boolean =
    AgentStaticAssetClassifier.isStaticAsset(this)

internal fun AgentSampleSummary.isFailureForAnalysis(ignoreStaticAssetFailures: Boolean): Boolean {
    if (hasAssertionFailure) {
        return true
    }
    if (!success && !(ignoreStaticAssetFailures && isStaticAssetRequest())) {
        return if (subResults.isEmpty()) {
            true
        } else {
            val childFailures = subResults.filter { !it.success || it.hasAssertionFailure }
            childFailures.isEmpty() || childFailures.any { it.isFailureForAnalysis(ignoreStaticAssetFailures) }
        }
    }
    return subResults.any { it.isFailureForAnalysis(ignoreStaticAssetFailures) }
}

internal fun AgentSampleSummary.ignoredStaticFailureCount(): Int {
    val ownIgnored = if ((!success || hasAssertionFailure) && isStaticAssetRequest()) 1 else 0
    return ownIgnored + subResults.sumOf { it.ignoredStaticFailureCount() }
}
