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
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkloadSummaryTest : JMeterTestCase() {
    private fun summaryOf(configure: ThreadGroup.() -> Unit): WorkloadSummary {
        val threadGroup = ThreadGroup().apply {
            setSamplerController(LoopController().apply { loops = 3 })
            configure()
        }
        return WorkloadSummary.of(ScenarioWorkload().apply { copyWorkloadFrom(threadGroup) })
    }

    @Test
    fun `closed model with fixed pacing has a known rate`() {
        val summary = summaryOf {
            numThreads = 10
            scheduler = true
            duration = 600
            pacingMode = AbstractThreadGroup.PACING_FIXED
            fixedPacing = "30000"
        }
        assertEquals(WorkloadSummary(open = false, peakThreads = 10, peakIterationsPerMinute = 20.0, durationSeconds = 600, loops = null), summary)
    }

    @Test
    fun `closed model without pacing reports loops and no rate`() {
        val summary = summaryOf { numThreads = 4 }
        assertEquals(WorkloadSummary(open = false, peakThreads = 4, peakIterationsPerMinute = null, durationSeconds = null, loops = 3), summary)
    }

    @Test
    fun `expressions are shown as written instead of as zero`() {
        val summary = summaryOf {
            setProperty(AbstractThreadGroup.NUM_THREADS, "\${__P(users,1)}")
            setSamplerController(LoopController().apply { setProperty(LoopController.LOOPS, "\${__P(loops,5)}") })
        }
        assertEquals("\${__P(users,1)}", summary.threadsExpression)
        assertEquals("\${__P(loops,5)}", summary.loopsExpression)
    }

    @Test
    fun `custom closed model uses its highest phase`() {
        val summary = summaryOf {
            setClosedModelMode(ThreadGroup.CLOSED_MODEL_MODE_CUSTOM)
            closedModelSchedule = "threadsPhase(10, 60) threadsPhase(50, 120) threadsPhase(20, 60)"
        }
        assertEquals(50, summary.peakThreads)
        assertEquals(240, summary.durationSeconds)
    }

    @Test
    fun `open model uses the peak arrival rate`() {
        val summary = summaryOf {
            setThreadGroupModel(ThreadGroup.MODEL_OPEN)
            openModelSchedule = "rate(1/sec) random_arrivals(1 min) rate(5/sec) random_arrivals(2 min)"
        }
        assertEquals(WorkloadSummary(open = true, peakThreads = null, peakIterationsPerMinute = 300.0, durationSeconds = 180, loops = null), summary)
    }
}
