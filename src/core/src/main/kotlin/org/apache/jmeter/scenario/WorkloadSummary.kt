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

package org.apache.jmeter.scenario

import org.apache.jmeter.control.LoopController
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.threads.openmodel.ThreadSchedule
import org.apache.jmeter.threads.openmodel.ThreadScheduleStep

/**
 * Expected load of one scenario workload, as far as it can be known before the test runs.
 *
 * @property open whether the workload uses the open model (arrival rate) rather than a fixed number of threads
 * @property peakThreads highest number of concurrent threads, or `null` when it is not limited
 * @property peakIterationsPerMinute highest number of iterations started per minute, or `null` when it depends on
 *   response times (closed model without pacing) or on values only known at run time
 * @property durationSeconds how long the workload runs, or `null` when it depends on a loop count or never ends
 * @property loops the number of iterations per thread when the duration is set by a loop count, `-1` for forever
 */
public data class WorkloadSummary(
    val open: Boolean,
    val peakThreads: Long?,
    val peakIterationsPerMinute: Double?,
    val durationSeconds: Long?,
    val loops: Int?,
) {
    public companion object {
        @JvmStatic
        public fun of(workload: ScenarioWorkload): WorkloadSummary {
            val threadGroup = ThreadGroup()
            workload.applyTo(threadGroup)
            return if (threadGroup.isOpenModel) open(threadGroup) else closed(threadGroup)
        }

        private fun open(threadGroup: ThreadGroup): WorkloadSummary {
            val schedule = try {
                ThreadSchedule(threadGroup.openModelSchedule)
            } catch (e: Exception) {
                null
            }
            val peakPerSecond = schedule?.steps
                ?.filterIsInstance<ThreadScheduleStep.RateStep>()
                ?.maxOfOrNull { it.rate }
            val maxThreads = threadGroup.openModelMaxThreads
            return WorkloadSummary(
                open = true,
                peakThreads = maxThreads.takeIf { it > 0 },
                peakIterationsPerMinute = peakPerSecond?.let { it * 60 },
                durationSeconds = schedule?.totalDuration?.toLong(),
                loops = null,
            )
        }

        private fun closed(threadGroup: ThreadGroup): WorkloadSummary {
            val threads = threadGroup.numThreads.toLong()
            val phases = if (threadGroup.isCustomClosedModel) {
                try {
                    ThreadGroup.parseClosedModelSchedule(threadGroup.closedModelSchedule)
                } catch (e: IllegalArgumentException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val custom = phases.isNotEmpty()
            val loops = (threadGroup.samplerController as? LoopController)?.loops
            val duration = when {
                custom -> phases.sumOf { it.durationSeconds() }
                threadGroup.scheduler && threadGroup.duration > 0 -> threadGroup.duration
                else -> null
            }
            return WorkloadSummary(
                open = false,
                peakThreads = threads,
                peakIterationsPerMinute = pacingMillis(threadGroup)?.let { threads * 60_000.0 / it },
                durationSeconds = duration,
                loops = if (custom || duration != null) null else loops,
            )
        }

        /** Average time between iteration starts of one thread, when pacing sets it */
        private fun pacingMillis(threadGroup: AbstractThreadGroup): Double? {
            val pacing = when (threadGroup.pacingMode) {
                AbstractThreadGroup.PACING_FIXED -> threadGroup.fixedPacing.trim().toDoubleOrNull()
                AbstractThreadGroup.PACING_RANDOM, AbstractThreadGroup.PACING_GAUSSIAN_RANDOM -> {
                    val min = threadGroup.pacingMin.trim().toDoubleOrNull()
                    val max = threadGroup.pacingMax.trim().toDoubleOrNull()
                    if (min != null && max != null && max >= min) (min + max) / 2 else null
                }
                else -> null
            }
            return pacing?.takeIf { it > 0 }
        }
    }
}
