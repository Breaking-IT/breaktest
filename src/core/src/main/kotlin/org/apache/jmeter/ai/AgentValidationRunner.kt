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

import org.apache.jmeter.JMeter
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.engine.TreeCloner
import org.apache.jmeter.engine.TreeClonerNoTimer
import org.apache.jmeter.engine.util.NoThreadClone
import org.apache.jmeter.reporters.AbstractListenerElement
import org.apache.jmeter.samplers.SampleEvent
import org.apache.jmeter.samplers.SampleListener
import org.apache.jmeter.samplers.SampleResult
import org.apache.jmeter.threads.JMeterContextService
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import java.util.Collections

public data class AgentValidationResult(
    val samples: List<AgentSampleSummary>,
    val timedOut: Boolean,
    val stoppedEarly: Boolean = false,
    val stopReason: String? = null,
    val ignoreStaticAssetFailures: Boolean = false,
) {
    public val firstFailureIndex: Int?
        get() = samples.firstOrNull { it.isFailureForAnalysis(ignoreStaticAssetFailures) }?.index

    public val successful: Boolean
        get() = !timedOut &&
            !stoppedEarly &&
            samples.isNotEmpty() &&
            ignoredStaticFailureCount == 0 &&
            samples.none { it.isFailureForAnalysis(ignoreStaticAssetFailures) }

    public val ignoredStaticFailureCount: Int
        get() = if (ignoreStaticAssetFailures) {
            samples.sumOf { it.ignoredStaticFailureCount() }
        } else {
            0
        }
}

public class AgentValidationRunner {
    public fun run(testTree: HashTree, options: AgentRunOptions = AgentRunOptions()): AgentValidationResult {
        val listener = AgentCollectSamplesListener(options)
        val clonedTree = cloneTree(testTree, options)
        JMeter.convertSubTree(clonedTree, false)
        addListenerToRoot(clonedTree, listener)

        val previousValidationRun = JMeterContextService.isValidationRun()
        JMeterContextService.setValidationRun(options.validationRun)
        val engine = StandardJMeterEngine()
        var timedOut = false
        try {
            listener.engine = engine
            engine.configure(clonedTree)
            engine.runTest()
            try {
                engine.awaitTermination(options.timeout)
            } catch (e: java.util.concurrent.TimeoutException) {
                timedOut = true
                engine.stopTest()
            }
        } finally {
            JMeterContextService.setValidationRun(previousValidationRun)
        }

        return AgentValidationResult(
            samples = listener.events.mapIndexed { index, event ->
                AgentSampleSummary.from(index, event.result, options)
            },
            timedOut = timedOut,
            stoppedEarly = listener.stopReason != null,
            stopReason = listener.stopReason,
            ignoreStaticAssetFailures = options.ignoreStaticAssetFailures,
        )
    }

    private fun cloneTree(testTree: HashTree, options: AgentRunOptions): ListedHashTree {
        val cloner: TreeCloner = if (options.ignoreTimers) {
            TreeClonerNoTimer(false)
        } else {
            TreeCloner(false)
        }
        testTree.traverse(cloner)
        return cloner.clonedTree
    }

    private fun addListenerToRoot(tree: ListedHashTree, listener: AgentCollectSamplesListener) {
        val root = tree.array.firstOrNull()
            ?: throw IllegalArgumentException("Test tree must contain a root test element")
        tree.add(root, listener)
    }
}

private class AgentCollectSamplesListener(
    private val options: AgentRunOptions,
) : AbstractListenerElement(), SampleListener, NoThreadClone {
    private val mutableEvents: MutableList<SampleEvent> = Collections.synchronizedList(mutableListOf())
    @Volatile
    var stopReason: String? = null
        private set
    @Volatile
    var engine: StandardJMeterEngine? = null

    val events: List<SampleEvent>
        get() = mutableEvents.toList()

    init {
        name = "Agent Sample Collector"
    }

    override fun sampleOccurred(e: SampleEvent) {
        mutableEvents += e
        requestStopIfNeeded(e.result)
    }

    override fun sampleStarted(e: SampleEvent) {
    }

    override fun sampleStopped(e: SampleEvent) {
    }

    private fun requestStopIfNeeded(result: SampleResult) {
        if (stopReason != null) {
            return
        }
        val maxSamples = options.maxSamples
        when {
            maxSamples != null && mutableEvents.size >= maxSamples -> {
                stopReason = "maxSamples"
            }
            options.stopOnFirstFailure && result.isFailureForAnalysis() -> {
                stopReason = "firstFailure"
            }
        }
        if (stopReason != null) {
            engine?.stopTest(true)
        }
    }

    private fun SampleResult.isFailureForAnalysis(): Boolean {
        val assertionFailure = assertionResults.any { it.isFailure || it.isError }
        if (assertionFailure) {
            return true
        }
        val ignoredStatic = options.ignoreStaticAssetFailures && AgentStaticAssetClassifier.isStaticAsset(this)
        if (!isSuccessful && !ignoredStatic) {
            return if (subResults.isEmpty()) {
                true
            } else {
                val childFailures = subResults.filter { it.isFailure() }
                childFailures.isEmpty() || childFailures.any { it.isFailureForAnalysis() }
            }
        }
        return subResults.any { it.isFailureForAnalysis() }
    }

    private fun SampleResult.isFailure(): Boolean =
        !isSuccessful ||
            assertionResults.any { it.isFailure || it.isError } ||
            subResults.any { it.isFailure() }
}
