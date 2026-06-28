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

package org.apache.jmeter.threads

import org.apache.jmeter.control.LoopController
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.test.assertions.executePlanAndCollectEvents
import org.apache.jmeter.test.samplers.CollectSamplesListener
import org.apache.jmeter.test.samplers.ThreadSleep
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup
import org.apache.jmeter.treebuilder.dsl.testTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class ThreadGroupTest : JMeterTestCase() {
    @Test
    fun `threadNum with trailing whitespace`() {
        val events = executePlanAndCollectEvents(10.seconds) {
            ThreadGroup::class {
                props {
                    it[numThreads] = "1 "
                }
                rampUp = 0
                scheduler = true
                delay = 0
                duration = 1
                setSamplerController(
                    LoopController().apply {
                        loops = 1
                        setContinueForever(false)
                    }
                )

                ThreadSleep::class {
                    duration = 0.seconds
                }
            }
        }
        assertEquals(1, events.size) {
            "ThreadGroup.threadNum has trailing whitespace, it should be trimmed, so one event should be generated. " +
                "Actual events are $events"
        }
    }

    @Test
    fun `thread group can execute open model schedule`() {
        val events = executePlanAndCollectEvents(10.seconds) {
            ThreadGroup::class {
                setThreadGroupModel(ThreadGroup.MODEL_OPEN)
                setOpenModelSchedule("rate(2/sec) even_arrivals(1 sec) pause(1 sec)")
                setOpenModelRandomSeedString("0")

                ThreadSleep::class {
                    duration = 0.seconds
                }
            }
        }
        assertEquals(2, events.size) {
            "Open Model ThreadGroup should generate events from its schedule. Actual events are $events"
        }
    }

    @Test
    fun `open model empty maximum active threads means unlimited`() {
        val threadGroup = ThreadGroup()
        threadGroup.setOpenModelMaxThreadsString("")

        assertEquals(0L, threadGroup.openModelMaxThreads)
    }

    @Test
    fun `open model skips arrivals when thread group maximum active threads is reached`() {
        val events = executePlanAndCollectEvents(10.seconds) {
            ThreadGroup::class {
                setThreadGroupModel(ThreadGroup.MODEL_OPEN)
                setOpenModelSchedule("rate(10/sec) even_arrivals(1 sec) pause(2 sec)")
                setOpenModelRandomSeedString("0")
                setOpenModelMaxThreadsString("1")
                setOpenModelMaxThreadsScope(ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP)

                ThreadSleep::class {
                    duration = 2.seconds
                }
            }
        }
        assertEquals(1, events.size) {
            "Open Model ThreadGroup should skip arrivals while the group active-thread limit is reached. " +
                "Actual events are $events"
        }
    }

    @Test
    fun `open model skips arrivals when total maximum active threads is reached`() {
        val events = executePlanAndCollectEvents(10.seconds) {
            repeat(2) {
                ThreadGroup::class {
                    setThreadGroupModel(ThreadGroup.MODEL_OPEN)
                    setOpenModelSchedule("rate(1/sec) even_arrivals(1 sec) pause(2 sec)")
                    setOpenModelRandomSeedString("0")
                    setOpenModelMaxThreadsString("1")
                    setOpenModelMaxThreadsScope(ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL)

                    ThreadSleep::class {
                        duration = 2.seconds
                    }
                }
            }
        }
        assertEquals(1, events.size) {
            "Open Model ThreadGroups should skip arrivals while the total active-thread limit is reached. " +
                "Actual events are $events"
        }
    }

    @Test
    fun `legacy open model skips arrivals when thread group maximum active threads is reached`() {
        val events = executePlanAndCollectEvents(10.seconds) {
            OpenModelThreadGroup::class {
                scheduleString = "rate(10/sec) even_arrivals(1 sec) pause(2 sec)"
                randomSeedString = "0"
                maxThreadsString = "1"
                maxThreadsScope = ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP

                ThreadSleep::class {
                    duration = 2.seconds
                }
            }
        }
        assertEquals(1, events.size) {
            "Legacy Open Model ThreadGroup should skip arrivals while the group active-thread limit is reached. " +
                "Actual events are $events"
        }
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `stopping open model thread group does not start remaining arrivals`() {
        val listener = CollectSamplesListener()
        val tree = testTree {
            TestPlan::class {
                +listener
                ThreadGroup::class {
                    setThreadGroupModel(ThreadGroup.MODEL_OPEN)
                    setOpenModelSchedule("rate(1/sec) even_arrivals(1 sec) pause(5 sec) rate(50/sec) even_arrivals(1 sec)")
                    setOpenModelRandomSeedString("0")

                    ThreadSleep::class {
                        duration = 0.seconds
                    }
                }
            }
        }
        val engine = StandardJMeterEngine()
        engine.configure(tree)
        engine.runTest()

        val firstArrivalObserved = waitUntil(3.seconds) { listener.events.isNotEmpty() }
        assertTrue(firstArrivalObserved, "Expected the first open-model arrival before stopping the test")

        engine.stopTest(false)
        engine.awaitTermination(5.seconds.toJavaDuration())

        assertEquals(1, listener.events.size) {
            "Stopping the Open Model ThreadGroup should not submit arrivals scheduled after the pause. " +
                "Actual events were ${listener.events}"
        }
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `stopping legacy open model thread group lets engine terminate`() {
        val listener = CollectSamplesListener()
        val tree = testTree {
            TestPlan::class {
                +listener
                OpenModelThreadGroup::class {
                    scheduleString = "rate(1/sec) even_arrivals(1 sec) pause(5 sec) rate(50/sec) even_arrivals(1 sec)"
                    randomSeedString = "0"

                    ThreadSleep::class {
                        duration = 0.seconds
                    }
                }
            }
        }
        val engine = StandardJMeterEngine()
        engine.configure(tree)
        engine.runTest()

        val firstArrivalObserved = waitUntil(3.seconds) { listener.events.isNotEmpty() }
        assertTrue(firstArrivalObserved, "Expected the first legacy open-model arrival before stopping the test")

        engine.stopTest(false)
        engine.awaitTermination(5.seconds.toJavaDuration())

        assertEquals(1, listener.events.size) {
            "Stopping the legacy Open Model ThreadGroup should terminate without submitting arrivals after the pause. " +
                "Actual events were ${listener.events}"
        }
    }

    private fun waitUntil(timeout: kotlin.time.Duration, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(25)
        }
        return condition()
    }
}
