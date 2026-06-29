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

package org.apache.jmeter.threads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ClosedModelThreadGroupScheduleTest {
    @Test
    void parsesClosedModelPhases() {
        List<ThreadGroup.ClosedModelPhase> phases = ThreadGroup.parseClosedModelSchedule("""
                threadsPhase(10, 10)
                threadsPhase(25, 30)
                """);

        assertEquals(2, phases.size());
        assertEquals(10, phases.get(0).targetThreads());
        assertEquals(10, phases.get(0).timeSeconds());
        assertEquals(25, phases.get(1).targetThreads());
        assertEquals(30, phases.get(1).timeSeconds());
    }

    @Test
    void rejectsUnknownClosedModelScheduleText() {
        assertThrows(IllegalArgumentException.class,
                () -> ThreadGroup.parseClosedModelSchedule("threadsPhase(10, 5, 20) bad"));
    }

    @Test
    void rejectsDecreasingClosedModelScheduleTimes() {
        assertThrows(IllegalArgumentException.class,
                () -> ThreadGroup.parseClosedModelSchedule("""
                        threadsPhase(10, 20)
                        threadsPhase(25, 10)
                        """));
    }

    @Test
    void closedModelScheduleContributesMaximumThreadCount() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(5);
        threadGroup.setClosedModelSchedule("""
                threadsPhase(10, 10)
                threadsPhase(25, 30)
                """);

        assertEquals(25, threadGroup.getNumThreads());
    }

    @Test
    void malformedClosedModelScheduleDoesNotBreakThreadCount() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(5);
        threadGroup.setClosedModelSchedule("threadsPhase(10, 20) bad");

        assertEquals(5, threadGroup.getNumThreads());
    }

    @Test
    void closedModelScheduleThreadCountCacheFollowsScheduleChanges() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(5);
        threadGroup.setClosedModelSchedule("""
                threadsPhase(10, 10)
                threadsPhase(25, 30)
                """);
        assertEquals(25, threadGroup.getNumThreads());

        threadGroup.setClosedModelSchedule("""
                threadsPhase(3, 10)
                threadsPhase(8, 30)
                """);
        assertEquals(8, threadGroup.getNumThreads());
    }

    @Test
    @Timeout(5)
    void closedModelScheduleErrorStopsStartedThreads() throws Exception {
        FailingClosedModelThreadGroup threadGroup = new FailingClosedModelThreadGroup();
        threadGroup.setName("failing closed model");
        threadGroup.setClosedModelSchedule("threadsPhase(2, 0)");

        threadGroup.start(1, new ListenerNotifier(), new ListedHashTree(), new StandardJMeterEngine());

        assertTrue(threadGroup.threadStarted.await(2, TimeUnit.SECONDS));
        assertTrue(threadGroup.threadStopped.await(2, TimeUnit.SECONDS));
        assertEquals(0, threadGroup.numberOfActiveThreads());
    }

    private static final class FailingClosedModelThreadGroup extends ThreadGroup {
        private final AtomicInteger makeThreadCalls = new AtomicInteger();
        private final CountDownLatch threadStarted = new CountDownLatch(1);
        private final CountDownLatch threadStopped = new CountDownLatch(1);

        @Override
        protected JMeterThread makeThread(
                StandardJMeterEngine engine,
                JMeterThreadMonitor monitor, ListenerNotifier notifier,
                int groupNumber, int threadNumber,
                ListedHashTree threadGroupTree,
                JMeterVariables variables) {
            if (makeThreadCalls.incrementAndGet() > 1) {
                throw new IllegalStateException("synthetic closed-model scheduler failure");
            }
            JMeterThread thread = new StoppableJMeterThread(this, threadStarted, threadStopped);
            thread.setThreadName("failing closed model " + groupNumber + "-" + (threadNumber + 1));
            thread.setThreadGroup(this);
            thread.setEngine(engine);
            return thread;
        }
    }

    private static final class StoppableJMeterThread extends JMeterThread {
        private final ThreadGroup monitor;
        private final CountDownLatch threadStarted;
        private final CountDownLatch threadStopped;

        private StoppableJMeterThread(ThreadGroup monitor, CountDownLatch threadStarted, CountDownLatch threadStopped) {
            super(singleLoopControllerTree(), monitor, new ListenerNotifier());
            this.monitor = monitor;
            this.threadStarted = threadStarted;
            this.threadStopped = threadStopped;
        }

        @Override
        public void run() {
            threadStarted.countDown();
            try {
                while (isRunning()) {
                    TimeUnit.MILLISECONDS.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                monitor.threadFinished(this);
                threadStopped.countDown();
            }
        }
    }

    private static ListedHashTree singleLoopControllerTree() {
        LoopController loopController = new LoopController();
        loopController.setLoops(LoopController.INFINITE_LOOP_COUNT);
        loopController.setContinueForever(true);
        ListedHashTree tree = new ListedHashTree();
        tree.add(loopController);
        return tree;
    }
}
