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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;

/**
 * Standalone benchmark for process CPU cost of timed waits, including virtual threads.
 * Run from the repository root after compileJmhJava and createDist:
 * java -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*'
 * org.apache.jmeter.threads.IdleDelayBenchmark virtual 25 1000 3 3 wakeable
 *
 * Arguments: virtual/platform, users, delay milliseconds, measurement seconds, rounds, sleep/wakeable.
 * Process CPU includes carrier threads and JVM housekeeping. Worker creation is excluded.
 * This measures waiting overhead, not HTTP throughput. Run on an otherwise idle machine.
 */
public final class IdleDelayBenchmark {
    private IdleDelayBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        boolean virtual = switch (args[0]) {
            case "virtual" -> true;
            case "platform" -> false;
            default -> throw new IllegalArgumentException("Expected virtual or platform");
        };
        int users = Integer.parseInt(args[1]);
        long delayMillis = Long.parseLong(args[2]);
        int seconds = Integer.parseInt(args[3]);
        int rounds = Integer.parseInt(args[4]);
        if (users <= 0 || delayMillis <= 0 || seconds <= 0 || rounds <= 0) {
            throw new IllegalArgumentException("All numeric arguments must be positive");
        }
        boolean wakeable = switch (args[5]) {
            case "wakeable" -> true;
            case "sleep" -> false;
            default -> throw new IllegalArgumentException("Expected sleep or wakeable");
        };
        JMeterUtils.loadJMeterProperties("bin/jmeter.properties");
        System.out.println("java,thread_type,users,delay_ms,round,implementation,wall_ms,cpu_ms,completed_waits,platform_threads");
        // Use a separate JVM for each implementation: virtual scheduler carrier pools can grow
        // while waiting and remain enlarged in later trials, contaminating an in-process A/B.
        run(virtual, users, delayMillis, 2, wakeable);
        for (int round = 1; round <= rounds; round++) {
            Result result = run(virtual, users, delayMillis, seconds, wakeable);
            System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%s,%.3f,%.3f,%d,%d%n",
                    System.getProperty("java.version"), virtual ? "virtual" : "platform",
                    users, delayMillis, round, wakeable ? "wakeable" : "sleep",
                    result.wallNanos / 1e6, result.cpuNanos / 1e6,
                    result.completed, result.platformThreads);
        }
    }

    private static Result run(boolean virtual, int users, long delayMillis, int seconds, boolean wakeable)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(users);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] deadline = new long[1]; // Published through the start latch.
        long[] completed = new long[users];
        List<Thread> workers = new ArrayList<>(users);
        for (int i = 0; i < users; i++) {
            int index = i;
            JMeterThread user = newUser();
            Runnable work = () -> {
                ready.countDown();
                try {
                    start.await();
                    while (user.isIterationRunning()) {
                        long waitEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
                        // Do not count a partial final interval as a completed wait.
                        if (waitEnd > deadline[0]) {
                            break;
                        }
                        long remaining;
                        while (user.isIterationRunning() && (remaining = waitEnd - System.nanoTime()) > 0) {
                            long millis = TimeUnit.NANOSECONDS.toMillis(remaining - 1) + 1;
                            if (wakeable) {
                                user.awaitDelay(millis);
                            } else {
                                TimeUnit.MILLISECONDS.sleep(millis);
                            }
                        }
                        completed[index]++;
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    JMeterContextService.removeContext();
                }
            };
            Thread worker = virtual ? Thread.ofVirtual().unstarted(work) : new Thread(work);
            workers.add(worker);
            worker.start();
        }
        if (!ready.await(30, TimeUnit.SECONDS)) {
            start.countDown();
            workers.forEach(Thread::interrupt);
            throw new IllegalStateException("Workers did not become ready");
        }
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = os.getProcessCpuTime();
        long wallStart = System.nanoTime();
        deadline[0] = wallStart + TimeUnit.SECONDS.toNanos(seconds);
        start.countDown();
        // Keep equal wall windows even when the last full interval ends before the deadline.
        TimeUnit.MILLISECONDS.sleep(TimeUnit.SECONDS.toMillis(seconds) / 2);
        int platformThreads = ManagementFactory.getThreadMXBean().getThreadCount();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(seconds + 10L));
            if (worker.isAlive()) {
                workers.forEach(Thread::interrupt);
                throw new IllegalStateException("Worker did not finish");
            }
        }
        long remaining = deadline[0] - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
        long wallNanos = System.nanoTime() - wallStart;
        long cpuNanos = os.getProcessCpuTime() - cpuStart;
        if (failure.get() != null) {
            throw new IllegalStateException("Worker failed", failure.get());
        }
        if (cpuStart < 0 || cpuNanos < 0) {
            throw new IllegalStateException("Process CPU measurement is unavailable");
        }
        long total = 0;
        for (long count : completed) {
            total += count;
        }
        return new Result(wallNanos, cpuNanos, total, platformThreads);
    }

    private static JMeterThread newUser() {
        LoopController loop = new LoopController();
        loop.setLoops(LoopController.INFINITE_LOOP_COUNT);
        ListedHashTree tree = new ListedHashTree();
        tree.add(loop);
        return new JMeterThread(tree, thread -> { }, new ListenerNotifier());
    }

    private record Result(long wallNanos, long cpuNanos, long completed, int platformThreads) {
    }
}
