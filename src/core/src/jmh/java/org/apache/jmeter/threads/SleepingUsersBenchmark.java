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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Measures steady idle CPU while real JMeterThread virtual users are in transaction think time.
 * Arguments: users, signalled/polling/plain, seconds per measurement, measurement rounds.
 * Run each mode in a separate JVM after compileJmhJava and createDist. Use users=0 for the
 * process background baseline. Startup, GC before settling, probes and stopping are excluded.
 */
public final class SleepingUsersBenchmark {
    private static final long THINK_TIME_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private SleepingUsersBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(args[0]);
        String mode = args[1];
        int seconds = Integer.parseInt(args[2]);
        int rounds = Integer.parseInt(args[3]);
        if (count < 0 || seconds <= 0 || rounds <= 0
                || (long) seconds * rounds > 120
                || !List.of("signalled", "polling", "plain").contains(mode)) {
            throw new IllegalArgumentException("Expected users>=0, mode, seconds>0, rounds>0; at most 120 s");
        }
        JMeterUtils.loadJMeterProperties("bin/jmeter.properties");
        Configurator.setLevel(JMeterThread.class.getName(), Level.OFF);
        CountDownLatch entered = new CountDownLatch(count);
        AtomicInteger delayEntries = new AtomicInteger();
        AtomicInteger delayReturns = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<JMeterThread> users = new ArrayList<>(count);
        List<Thread> workers = new ArrayList<>(count);
        ThreadGroup group = new ThreadGroup();
        group.setName("Sleeping users");
        group.setNumThreads(count);
        try {
            for (int i = 0; i < count; i++) {
                LoopController loop = new LoopController();
                loop.setLoops(1);
                TransactionController transaction = new TransactionController();
                transaction.setName("Think time");
                transaction.setDelayMode(TransactionController.DELAY_FIXED);
                transaction.setFixedDelay(Long.toString(THINK_TIME_MILLIS));
                ListedHashTree tree = new ListedHashTree();
                tree.add(loop).add(transaction);
                JMeterThread user = new JMeterThread(tree, thread -> { }, new ListenerNotifier()) {
                    private boolean first = true;

                    @Override
                    public void awaitDelay(long millis) throws InterruptedException {
                        delayEntries.incrementAndGet();
                        if (first) {
                            first = false;
                            entered.countDown();
                        }
                        if ("plain".equals(mode)) {
                            TimeUnit.MILLISECONDS.sleep(millis);
                        } else {
                            super.awaitDelay("polling".equals(mode) ? Math.min(1000, millis) : millis);
                        }
                        delayReturns.incrementAndGet();
                    }
                };
                user.setThreadGroup(group);
                user.setThreadName("sleeping-user-" + i);
                users.add(user);
                workers.add(Thread.ofVirtual().start(() -> {
                    try {
                        user.run();
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    }
                }));
            }
            if (!entered.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Some users did not enter think time: " + entered.getCount());
            }
            System.gc();
            TimeUnit.SECONDS.sleep(2);
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            var threads = ManagementFactory.getThreadMXBean();
            System.out.println("java,mode,users,round,wall_ms,cpu_ms,one_core_percent,delay_entries,delay_returns,"
                    + "sleeping_users,platform_threads,heap_mib,gc_collections");
            for (int round = 1; round <= rounds; round++) {
                long sleepingBefore = sleepingCount(workers);
                if (!"polling".equals(mode) && sleepingBefore != count) {
                    throw new IllegalStateException("Not all users are sleeping: " + sleepingBefore);
                }
                long gcBefore = gcCount();
                int entriesBefore = delayEntries.get();
                int returnsBefore = delayReturns.get();
                long cpuBefore = os.getProcessCpuTime();
                long wallBefore = System.nanoTime();
                TimeUnit.SECONDS.sleep(seconds);
                long wallNanos = System.nanoTime() - wallBefore;
                long cpuNanos = os.getProcessCpuTime() - cpuBefore;
                int entries = delayEntries.get() - entriesBefore;
                int returns = delayReturns.get() - returnsBefore;
                long sleepingAfter = sleepingCount(workers);
                if (cpuBefore < 0 || cpuNanos < 0 || failure.get() != null) {
                    throw new IllegalStateException("CPU counters or workers failed", failure.get());
                }
                if (!"polling".equals(mode) && (entries != 0 || returns != 0 || sleepingAfter != count)) {
                    throw new IllegalStateException("An idle worker unexpectedly woke or exited");
                }
                double heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        / (1024.0 * 1024.0);
                System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%.3f,%.3f,%.4f,%d,%d,%d,%d,%.2f,%d%n",
                        System.getProperty("java.version"), mode, count, round, wallNanos / 1e6,
                        cpuNanos / 1e6, cpuNanos * 100.0 / wallNanos, entries, returns,
                        sleepingAfter, threads.getThreadCount(), heap, gcCount() - gcBefore);
            }
        } finally {
            // Deliberately outside the measurement window.
            users.forEach(JMeterThread::stop);
            if ("plain".equals(mode)) {
                workers.forEach(Thread::interrupt);
            }
            for (Thread worker : workers) {
                worker.join(5000);
                if (worker.isAlive()) {
                    workers.forEach(Thread::interrupt);
                    failure.compareAndSet(null, new IllegalStateException("User failed to stop"));
                    break;
                }
            }
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Worker failed", failure.get());
        }
    }

    private static long sleepingCount(List<Thread> workers) {
        return workers.stream().filter(worker -> worker.getState() == Thread.State.TIMED_WAITING).count();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }
}
