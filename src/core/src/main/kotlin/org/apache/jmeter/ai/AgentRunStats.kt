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
import org.apache.jmeter.threads.JMeterContextService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.LongAdder
import kotlin.math.ln
import kotlin.math.pow

/**
 * Running totals of the current run for agents: per-label counts, timings and a few error samples.
 * It is off unless the GUI agent service switches it on, and the per-sample cost is a handful of
 * counter updates, so load generation is not held up by it.
 */
public object AgentRunStats {
    private const val BUCKETS = 170
    private const val BUCKET_GROWTH = 1.1
    private const val MAX_LABELS = 2000
    private const val MAX_ERROR_KINDS = 200
    private const val MAX_ERROR_SAMPLES = 20
    private const val RESPONSE_HEAD_CHARS = 600
    private const val MAX_CAPTURED = 200
    private const val ERRORS_CAPTURED_PER_LABEL = 2
    private val CAPTURE_LIMITS = AgentRunOptions(responseBodyLimit = 256 * 1024, requestBodyLimit = 64 * 1024)

    private class Label(val transaction: Boolean, val order: Long) {
        val count = LongAdder()
        val errors = LongAdder()
        val totalMillis = LongAdder()
        val min = AtomicLong(Long.MAX_VALUE)
        val max = AtomicLong(0)
        val buckets = AtomicLongArray(BUCKETS)
        val capturedSuccess = java.util.concurrent.atomic.AtomicBoolean()
        val capturedErrors = java.util.concurrent.atomic.AtomicInteger()
    }

    @Volatile
    private var enabled = false

    @Volatile
    private var runStart = 0L

    private val firstSample = AtomicLong(Long.MAX_VALUE)
    private val lastSample = AtomicLong(0L)
    private val order = AtomicLong()
    private val labels = ConcurrentHashMap<String, Label>()
    private val errorKinds = ConcurrentHashMap<String, LongAdder>()
    private val errorSamples = ArrayDeque<Map<String, Any?>>()
    private val captured = java.util.concurrent.CopyOnWriteArrayList<AgentSampleSummary>()

    @JvmStatic
    public fun isEnabled(): Boolean = enabled

    @JvmStatic
    public fun setEnabled(value: Boolean) {
        enabled = value
    }

    @JvmStatic
    public fun reset() {
        labels.clear()
        errorKinds.clear()
        synchronized(errorSamples) { errorSamples.clear() }
        captured.clear()
        order.set(0)
        firstSample.set(Long.MAX_VALUE)
        lastSample.set(0L)
    }

    /** Called for every sample and transaction result of a run. */
    @JvmStatic
    public fun record(result: SampleResult, transaction: Boolean) {
        val start = JMeterContextService.getTestStartTime()
        if (start != runStart) {
            synchronized(this) {
                if (start != runStart) {
                    reset()
                    runStart = start
                }
            }
        }
        // Completion callbacks can arrive out of timestamp order across load threads.
        firstSample.accumulateAndGet(result.startTime, ::minOf)
        lastSample.accumulateAndGet(result.endTime, ::maxOf)
        val name = result.sampleLabel.orEmpty()
        val label = labels[name] ?: run {
            if (labels.size >= MAX_LABELS) return
            labels.computeIfAbsent(name) { Label(transaction, order.incrementAndGet()) }
        }
        val elapsed = result.time.coerceAtLeast(0)
        label.count.increment()
        label.totalMillis.add(elapsed)
        label.min.accumulateAndGet(elapsed, ::minOf)
        label.max.accumulateAndGet(elapsed, ::maxOf)
        label.buckets.incrementAndGet(bucket(elapsed))
        if (!transaction) {
            capture(label, result)
        }
        if (!result.isSuccessful) {
            label.errors.increment()
            if (!transaction) {
                recordError(name, result)
            }
        }
    }

    /**
     * Keeps the full request and response of the first passing sample and the first failures of
     * each label, so an agent can read real traffic of a run without every sample being copied.
     */
    private fun capture(label: Label, result: SampleResult) {
        val wanted = if (result.isSuccessful) {
            !label.capturedSuccess.get() && label.capturedSuccess.compareAndSet(false, true)
        } else {
            label.capturedErrors.get() < ERRORS_CAPTURED_PER_LABEL &&
                label.capturedErrors.incrementAndGet() <= ERRORS_CAPTURED_PER_LABEL
        }
        if (wanted && captured.size < MAX_CAPTURED) {
            runCatching { captured += AgentSampleSummary.from(captured.size, result, CAPTURE_LIMITS) }
        }
    }

    /** Full samples kept from the current or last run, in the order they were captured. */
    @JvmStatic
    public fun capturedSamples(): List<AgentSampleSummary> =
        captured.mapIndexed { index, sample -> if (sample.index == index) sample else sample.copy(index = index) }

    private fun recordError(name: String, result: SampleResult) {
        val kind = "$name | ${result.responseCode} ${result.responseMessage.orEmpty().take(120)}".trim()
        (errorKinds[kind] ?: if (errorKinds.size < MAX_ERROR_KINDS) errorKinds.computeIfAbsent(kind) { LongAdder() } else null)
            ?.increment()
        synchronized(errorSamples) {
            if (errorSamples.size >= MAX_ERROR_SAMPLES) {
                // Keep the first failures (usually the cause) and only rotate the tail.
                errorSamples.removeAt(MAX_ERROR_SAMPLES / 2)
            }
            val failedAssertion = result.assertionResults.firstOrNull { it.isFailure || it.isError }
            errorSamples += mapOf(
                "label" to name,
                "responseCode" to result.responseCode,
                "responseMessage" to result.responseMessage,
                "url" to result.urlAsString,
                "thread" to result.threadName,
                "assertion" to failedAssertion?.let { "${it.name}: ${it.failureMessage}".take(300) },
                "responseHead" to runCatching { result.responseDataAsString.take(RESPONSE_HEAD_CHARS) }.getOrNull(),
            ).filterValues { it != null && it != "" }
        }
    }

    private fun bucket(millis: Long): Int =
        if (millis <= 0) 0 else (ln(millis.toDouble()) / ln(BUCKET_GROWTH)).toInt().plus(1).coerceAtMost(BUCKETS - 1)

    private fun bucketUpperBound(index: Int): Long = if (index == 0) 0 else BUCKET_GROWTH.pow(index).toLong() + 1

    private fun percentile(label: Label, fraction: Double): Long {
        val total = label.count.sum()
        if (total == 0L) return 0
        val target = (total * fraction).toLong().coerceAtLeast(1)
        var seen = 0L
        for (index in 0 until BUCKETS) {
            seen += label.buckets[index]
            if (seen >= target) {
                return minOf(bucketUpperBound(index), label.max.get())
            }
        }
        return label.max.get()
    }

    /**
     * A compact report: one text row per label, error kinds by count and the first error samples.
     * Percentiles are approximate (within about 10 percent).
     */
    @JvmStatic
    public fun snapshot(maxLabels: Int, errorSampleLimit: Int): Map<String, Any?> {
        val seconds = ((lastSample.get() - firstSample.get()).coerceAtLeast(1)) / 1000.0
        val sorted = labels.entries.sortedBy { it.value.order }
        val requests = sorted.filter { !it.value.transaction }
        val rows = sorted.take(maxLabels).map { (name, label) ->
            val count = label.count.sum()
            val errors = label.errors.sum()
            listOf(
                if (label.transaction) "T" else "R",
                name,
                count,
                errors,
                if (count == 0L) 0 else label.totalMillis.sum() / count,
                percentile(label, 0.90),
                percentile(label, 0.95),
                label.max.get(),
                "%.1f".format(count / seconds),
            ).joinToString(" | ")
        }
        val totalRequests = requests.sumOf { it.value.count.sum() }
        val totalErrors = requests.sumOf { it.value.errors.sum() }
        return linkedMapOf(
            "samples" to totalRequests,
            "errors" to totalErrors,
            "errorPercent" to if (totalRequests == 0L) 0.0 else Math.round(totalErrors * 1000.0 / totalRequests) / 10.0,
            "requestsPerSecond" to Math.round(totalRequests / seconds * 10) / 10.0,
            "columns" to "kind(R=request,T=transaction) | label | count | errors | avgMs | p90Ms | p95Ms | maxMs | perSecond",
            "rows" to rows.joinToString("\n"),
            "labelsOmitted" to (sorted.size - rows.size).takeIf { it > 0 },
            "errorKinds" to errorKinds.entries.sortedByDescending { it.value.sum() }.take(15)
                .map { "${it.value.sum()} x ${it.key}" }.takeIf { it.isNotEmpty() },
            "errorSamples" to synchronized(errorSamples) { errorSamples.take(errorSampleLimit) }.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }
}
