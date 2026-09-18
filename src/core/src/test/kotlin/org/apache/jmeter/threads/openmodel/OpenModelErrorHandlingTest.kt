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

package org.apache.jmeter.threads.openmodel

import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.samplers.AbstractSampler
import org.apache.jmeter.samplers.Entry
import org.apache.jmeter.samplers.SampleResult
import org.apache.jmeter.test.assertions.executePlanAndCollectEvents
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.treebuilder.TreeBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.seconds

class OpenModelErrorHandlingTest : JMeterTestCase() {
    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `start next loop on error ends only the failed arrival`(legacy: Boolean, parentSample: Boolean) {
        val events = executePlanAndCollectEvents(10.seconds) {
            val group: AbstractThreadGroup = if (legacy) {
                OpenModelThreadGroup().apply {
                    scheduleString = "rate(2/sec) even_arrivals(1 sec) pause(1 sec)"
                }
            } else {
                ThreadGroup().apply {
                    setThreadGroupModel(ThreadGroup.MODEL_OPEN)
                    setOpenModelSchedule("rate(2/sec) even_arrivals(1 sec) pause(1 sec)")
                }
            }
            group.setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP)
            group {
                transaction("Homepage", parentSample) {
                    +BoundedSampler("homepage-request")
                }
                transaction("EnterEmailAddress", parentSample) {
                    +BoundedSampler("email-preflight")
                    +BoundedSampler("email-check", fail = true)
                    +BoundedSampler("after-failure")
                }
                +BoundedSampler("after-transaction")
            }
        }
        fun flatten(result: SampleResult): List<SampleResult> =
            listOf(result) + result.subResults.flatMap { flatten(it) }

        val results = events.flatMap { flatten(it.result) }
        assertEquals(
            mapOf("Homepage" to 2, "homepage-request" to 2, "EnterEmailAddress" to 2, "email-preflight" to 2, "email-check" to 2),
            results.groupingBy { it.sampleLabel }.eachCount(),
            "Each scheduled arrival should visit the homepage once, fail once, and skip the remaining journey"
        )
        assertTrue(results.filter { it.sampleLabel == "EnterEmailAddress" }.all { !it.isSuccessful })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ending the only iteration prevents another sampler`(breakLoop: Boolean) {
        val controller = OpenModelThreadGroupController()
        controller.addTestElement(BoundedSampler("first"))
        controller.addTestElement(BoundedSampler("must-not-run"))
        controller.initialize()
        assertEquals("first", controller.next()?.name)

        if (breakLoop) {
            controller.breakLoop()
        } else {
            controller.startNextLoop()
        }

        assertTrue(controller.isDone)
        assertNull(controller.next())
    }

    private fun TreeBuilder.transaction(name: String, parentSample: Boolean, body: TreeBuilder.() -> Unit) {
        TransactionController::class {
            this.name = name
            setGenerateParentSample(parentSample)
            body()
        }
    }

    // Stop a broken controller on its second pass so a regression cannot spin indefinitely.
    class BoundedSampler(name: String = "", fail: Boolean = false) : AbstractSampler() {
        private var calls = 0

        init {
            this.name = name
            setProperty("fail", fail)
        }

        override fun sample(entry: Entry?): SampleResult = SampleResult().apply {
            sampleLabel = name
            sampleStart()
            isSuccessful = !getPropertyAsBoolean("fail")
            responseCode = if (!isSuccessful) "500" else "200"
            isStopThread = ++calls > 1
            sampleEnd()
        }
    }
}
