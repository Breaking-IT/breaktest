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

import org.apache.jmeter.control.Controller
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.gui.GUIMenuSortOrder
import org.apache.jmeter.testelement.schema.PropertiesAccessor
import org.apache.jmeter.threads.AbstractThreadGroup
import org.apache.jmeter.threads.JMeterContextService
import org.apache.jmeter.threads.JMeterThread
import org.apache.jmeter.threads.JMeterThreadMonitor
import org.apache.jmeter.threads.ListenerNotifier
import org.apache.jmeter.threads.TestCompilerHelper
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.util.JMeterUtils
import org.apache.jorphan.collections.ListedHashTree
import org.apiguardian.api.API
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The thread group that emulates open model.
 * Currently, threads are created on demand, every thread exists after completion,
 * and the maximum number of threads is not limited.
 */
@GUIMenuSortOrder(1)
@API(status = API.Status.EXPERIMENTAL, since = "5.5")
public class OpenModelThreadGroup :
    AbstractThreadGroup(),
    Serializable,
    Controller,
    JMeterThreadMonitor,
    TestCompilerHelper {
    public companion object {
        private val log = LoggerFactory.getLogger(OpenModelThreadGroup::class.java)

        /** Whether to use Java 21 Virtual Threads */
        private val VIRTUAL_THREADS_ENABLED =
            JMeterUtils.getPropDefault("jmeter.threads.virtual.enabled", true)

        /** Counter for naming virtual threads */
        private val virtualThreadCounter = AtomicLong(0)

        private val rampupGranularity =
            JMeterUtils.getPropDefault("jmeterthread.rampup.granularity", 1000).toLong()

        /** Thread group schedule. See [ThreadSchedule]. */
        @Deprecated(
            message = "Use OpenModelThreadGroupSchema instead",
            replaceWith = ReplaceWith(
                "OpenModelThreadGroupSchema.getSchedule",
                imports = ["org.apache.jmeter.threads.openmodel.OpenModelThreadGroupSchema"]
            ),
            DeprecationLevel.WARNING
        )
        public val SCHEDULE: String = OpenModelThreadGroupSchema.schedule.name

        /** The seed for reproducible workloads. 0 means no seed, so the schedule would be new on every execution */
        @Deprecated(
            message = "Use OpenModelThreadGroupSchema instead",
            replaceWith = ReplaceWith(
                "OpenModelThreadGroupSchema.randomSeed",
                imports = ["org.apache.jmeter.threads.openmodel.OpenModelThreadGroupSchema"]
            ),
            DeprecationLevel.WARNING
        )
        public val RANDOM_SEED: String = OpenModelThreadGroupSchema.randomSeed.name

        /**
         * A thread pool for "thread starter thread".
         * It is not re-created across JMeter test restart
         */
        private val houseKeepingThreadPool = Executors.newCachedThreadPool()

        private const val serialVersionUID: Long = 1L

        /**
         * Creates an ExecutorService that uses virtual threads when enabled.
         */
        private fun createExecutorService(): ExecutorService {
            if (VIRTUAL_THREADS_ENABLED) {
                val factory = ThreadFactory { runnable ->
                    Thread.ofVirtual()
                        .name("OpenModel-vt-${virtualThreadCounter.incrementAndGet()}")
                        .unstarted(runnable)
                }
                log.debug("Created virtual thread executor for OpenModelThreadGroup")
                return Executors.newThreadPerTaskExecutor(factory)
            }
            return Executors.newCachedThreadPool()
        }
    }

    // A thread pool that executes main workload.
    // It is created on test start and it is shutdown on every test stop.
    // ExecutorService shutdown is the only way to wait for completion of all tasks.
    private var executorService: ExecutorService? = null

    private val threadStarterFuture = AtomicReference<Future<*>?>()
    private val activeThreads = ConcurrentHashMap<JMeterThread, Future<*>>()
    @Volatile
    private var running = false

    override val schema: OpenModelThreadGroupSchema
        get() = OpenModelThreadGroupSchema

    override val props: PropertiesAccessor<@JvmWildcard OpenModelThreadGroup, @JvmWildcard OpenModelThreadGroupSchema>
        get() = PropertiesAccessor(this, schema)

    /**
     * Schedule expression (see [ThreadSchedule]).
     */
    public var scheduleString: String by OpenModelThreadGroupSchema.schedule

    public val randomSeed: Long by OpenModelThreadGroupSchema.randomSeed

    /**
     * Random seed for building reproducible schedules. 0 means random seed.
     */
    public var randomSeedString: String by OpenModelThreadGroupSchema.randomSeed.asString

    public val maxThreads: Long
        get() = getPropertyAsString(ThreadGroup.OPEN_MODEL_MAX_THREADS)
            .trim()
            .toLongOrNull()
            ?: 0

    public var maxThreadsString: String
        get() = getPropertyAsString(ThreadGroup.OPEN_MODEL_MAX_THREADS)
        set(value) {
            setProperty(ThreadGroup.OPEN_MODEL_MAX_THREADS, value)
        }

    public var maxThreadsScope: String
        get() = if (ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL ==
            getPropertyAsString(ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE)
        ) {
            ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL
        } else {
            ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP
        }
        set(value) {
            setProperty(
                ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE,
                if (ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL == value) {
                    ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL
                } else {
                    ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP
                }
            )
        }

    init {
        this[OpenModelThreadGroupSchema.mainController] = OpenModelThreadGroupController()
    }

    private class ThreadsStarter(
        private val testStartTime: Long,
        private val executorService: ExecutorService,
        private val activeThreads: MutableMap<JMeterThread, Future<*>>,
        private val gen: ThreadScheduleProcessGenerator,
        private val engine: StandardJMeterEngine,
        private val maxThreads: Long,
        private val maxThreadsScope: String,
        private val isRunning: () -> Boolean,
        private val jmeterThreadFactory: (threadNumber: Int) -> JMeterThread,
    ) : Runnable {
        override fun run() {
            log.info("Thread starting init")
            var scheduleOffsetInMillis = 0L
            var endTime = (testStartTime + gen.totalDuration * 1000).roundToLong()
            var threadNumber = 0
            var prevTime = 0L
            while (isRunning() && !Thread.currentThread().isInterrupted && gen.hasNext()) {
                var pauseTime = waitIfSchedulingPaused()
                if (pauseTime < 0) {
                    shutdownAfterStop()
                    return
                }
                scheduleOffsetInMillis += pauseTime
                endTime += pauseTime
                val scheduledTime = testStartTime + scheduleOffsetInMillis + (gen.nextDouble() * 1000).roundToLong()
                // If multiple events are scheduled for the same millisecond, we don't want to call currentTimeMillis
                if (scheduledTime >= prevTime) {
                    prevTime = System.currentTimeMillis()
                    val nextDelay = scheduledTime - prevTime
                    if (nextDelay > 0) {
                        pauseTime = sleepUntil(scheduledTime)
                        if (pauseTime < 0) {
                            shutdownAfterStop()
                            return
                        }
                        scheduleOffsetInMillis += pauseTime
                        endTime += pauseTime
                    }
                }
                if (!isRunning() || Thread.currentThread().isInterrupted) {
                    shutdownAfterStop()
                    return
                }
                pauseTime = waitIfSchedulingPaused()
                if (pauseTime < 0) {
                    shutdownAfterStop()
                    return
                }
                scheduleOffsetInMillis += pauseTime
                endTime += pauseTime
                if (!reserveThreadSlot()) {
                    log.debug("Skipping Open Model arrival because the active thread limit is reached")
                    continue
                }
                val jmeterThread = try {
                    jmeterThreadFactory(threadNumber++)
                } catch (t: Throwable) {
                    ThreadGroup.releaseOpenModelThreadSlot()
                    throw t
                }
                jmeterThread.endTime = endTime
                val task = FutureTask<Unit> {
                    Thread.currentThread().name = jmeterThread.threadName
                    jmeterThread.run()
                }
                activeThreads[jmeterThread] = task
                try {
                    executorService.execute(task)
                } catch (e: RuntimeException) {
                    removeThread(jmeterThread)
                    throw e
                }
            }
            if (!isRunning() || Thread.currentThread().isInterrupted) {
                shutdownAfterStop()
                return
            }
            // If test schedule ends with a pause, then we need to wait for it
            val timeLeft = endTime - System.currentTimeMillis()
            if (timeLeft > 0) {
                log.info(
                    "There will be no more events, so will wait for {} sec till the end of the schedule",
                    TimeUnit.MILLISECONDS.toSeconds(timeLeft)
                )
                if (sleepUntil(endTime) < 0) {
                    shutdownAfterStop()
                    return
                }
            } else {
                log.info("Thread schedule finished {} ms ago", -timeLeft)
            }
            // No more actions will be scheduled, let awaitTermination to see the completion
            val threadsStillRunning = activeThreads.size
            if (threadsStillRunning == 0) {
                log.info("There will be no more events, will shutdown the thread pool")
            } else {
                log.info(
                    "Test schedule finished, however, there are {} thread(s) still running." +
                        " Will interrupt the threads." +
                        " If you want to keep some time for the threads to complete, consider" +
                        " adding pause(10 min) at the end of the schedule.",
                    threadsStillRunning
                )
                @Suppress("JavaMapForEach")
                activeThreads.forEach { thread, future ->
                    log.info("Terminating thread {}", thread)
                    // Safe stop the thread
                    thread.stop()
                    thread.interrupt()
                    // Interrupt it
                    future.cancel(true)
                    removeThread(thread)
                }
            }
            executorService.shutdownNow()
            log.info("Thread starting done")
        }

        private fun reserveThreadSlot(): Boolean = ThreadGroup.reserveOpenModelThreadSlot(
            maxThreadsScope,
            maxThreads,
            activeThreads.size
        )

        private fun removeThread(thread: JMeterThread?) {
            if (thread != null && activeThreads.remove(thread) != null) {
                ThreadGroup.releaseOpenModelThreadSlot()
            }
        }

        private fun shutdownAfterStop() {
            log.info("Open Model thread scheduling stopped")
            executorService.shutdown()
        }

        private fun sleepUntil(endTime: Long): Long {
            var pauseTime = 0L
            var adjustedEndTime = endTime
            while (true) {
                val paused = waitIfSchedulingPaused()
                if (paused < 0) {
                    return -1
                }
                pauseTime += paused
                adjustedEndTime += paused
                val delay = adjustedEndTime - System.currentTimeMillis()
                if (delay <= 0) {
                    val finalPaused = waitIfSchedulingPaused()
                    if (finalPaused < 0) {
                        return -1
                    }
                    if (finalPaused == 0L) {
                        return pauseTime
                    }
                    pauseTime += finalPaused
                    adjustedEndTime += finalPaused
                    continue
                }
                try {
                    Thread.sleep(min(delay, rampupGranularity))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
        }

        private fun waitIfSchedulingPaused(): Long {
            if (Thread.currentThread().isInterrupted) {
                return -1
            }
            return engine.waitIfPaused()
        }
    }

    override fun recoverRunningVersion() {
        // There's no state in OpenModelThreadGroup, so we can skip recoverRunningVersion
    }

    override fun start(
        threadGroupIndex: Int,
        notifier: ListenerNotifier,
        threadGroupTree: ListedHashTree,
        engine: StandardJMeterEngine
    ) {
        try {
            val jMeterContext = JMeterContextService.getContext()
            val variables = jMeterContext.variables
            val schedule = scheduleString
            log.info("Starting OpenModelThreadGroup#{} with schedule {}", threadGroupIndex, schedule)
            val parsedSchedule = ThreadSchedule(schedule)
            val seed = randomSeed
            val rnd = if (seed == 0L) Random() else Random(seed)
            val gen = ThreadScheduleProcessGenerator(rnd, parsedSchedule)
            val testStartTime = this.startTime
            val executorService = createExecutorService()
            this.executorService = executorService
            running = true
            val starter = ThreadsStarter(
                testStartTime,
                executorService,
                activeThreads,
                gen,
                engine,
                maxThreads,
                maxThreadsScope,
                { running },
            ) { threadNumber ->
                val clonedTree = cloneTree(threadGroupTree)
                makeThread(engine, this, notifier, threadGroupIndex, threadNumber, clonedTree, variables)
            }
            threadStarterFuture.set(
                houseKeepingThreadPool.submit {
                    Thread.currentThread().name = "open-model-thread-starter-$name-$threadGroupIndex"
                    starter.run()
                }
            )
        } catch (t: Throwable) {
            throw t.apply {
                addSuppressed(IllegalArgumentException("Failed to start OpenModelThreadGroup $name-$threadGroupIndex"))
            }
        }
    }

    override fun threadFinished(thread: JMeterThread?) {
        if (thread != null && activeThreads.remove(thread) != null) {
            ThreadGroup.releaseOpenModelThreadSlot()
        }
    }

    override fun addNewThread(delay: Int, engine: StandardJMeterEngine?): JMeterThread {
        TODO("Will not be implemented as the semantics of the API is unclear")
    }

    override fun stopThread(threadName: String?, now: Boolean): Boolean {
        TODO("Will not be implemented as the semantics of the API is unclear")
    }

    override fun numberOfActiveThreads(): Int = activeThreads.size

    override fun verifyThreadsStopped(): Boolean =
        executorService?.awaitTermination(0, TimeUnit.SECONDS) != false

    override fun waitThreadsStopped() {
        executorService?.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS)
    }

    override fun stop() {
        running = false
        log.info("Gracefully stopping the threads")
        threadStarterFuture.getAndSet(null)?.cancel(true)
        // We use Java's forEach since ConcurrentHashMap has a slightly better implementation
        // than Kotlin's generic forEach
        @Suppress("JavaMapForEach")
        activeThreads.forEach { thread, _ ->
            log.info("Gracefully stopping thread {}", thread)
            // Safe stop the thread
            thread.stop()
        }
        executorService?.shutdown()
    }

    override fun tellThreadsToStop() {
        running = false
        // Graceful stop
        stop()
        log.info("Interrupting the threads")
        activeThreads.forEach { (thread, future) ->
            log.info("Interrupting thread {}", thread)
            // Interrupting the thread
            thread.interrupt()
            future.cancel(true)
            if (activeThreads.remove(thread) != null) {
                ThreadGroup.releaseOpenModelThreadSlot()
            }
        }
        // Interrupting all the threads
        // shutdownNow will interrupt the threads
        executorService?.shutdownNow()?.forEach {
            // In theory, there should be no "queued" runnables left,
            // However, if there's something, we remove it from activeThreads
            activeThreads.remove(it)
        }
    }
}
