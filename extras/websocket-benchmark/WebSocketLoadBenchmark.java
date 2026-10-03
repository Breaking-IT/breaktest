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

package org.apache.jmeter.protocol.websocket.sampler;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;

/** Standalone load driver using the production Connect/Close samplers and session registry. */
public class WebSocketLoadBenchmark {
    public static void main(String[] args) throws Exception {
        JMeterUtils.setJMeterHome(args[0]);
        JMeterUtils.loadJMeterProperties(args[0] + "/bin/jmeter.properties");
        JMeterUtils.setLocale(Locale.ENGLISH);
        int target = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        int guard = Integer.parseInt(args[3]);
        var threads = ManagementFactory.getThreadMXBean();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        CountDownLatch release = new CountDownLatch(1);
        Semaphore ramp = new Semaphore(16);
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Thread> users = new ArrayList<>();
        long start = System.nanoTime();
        long startCpu = os.getProcessCpuTime();
        for (int i = 0; i < target; i++) {
            ramp.acquire();
            if (threads.getThreadCount() >= guard || failed.get() > 0) {
                ramp.release();
                System.out.println("GUARD_STOP nativeThreads=" + threads.getThreadCount());
                break;
            }
            users.add(Thread.ofVirtual().start(() -> {
                JMeterContextService.getContext().setVariables(new JMeterVariables());
                boolean permit = true;
                try {
                    WebSocketConnectSampler connect = new WebSocketConnectSampler();
                    connect.setUrl("wss://127.0.0.1:19443/");
                    connect.setTimeout(15000);
                    connect.setCountIncoming(false);
                    SampleResult result = connect.sample(null);
                    if (!result.isSuccessful()) {
                        failed.incrementAndGet();
                        System.out.println("CONNECT_ERROR " + result.getResponseMessage());
                    } else {
                        connected.incrementAndGet();
                    }
                    ramp.release();
                    permit = false;
                    release.await();
                    if (result.isSuccessful()) {
                        new WebSocketCloseSampler().sample(null);
                    }
                } catch (Throwable failure) {
                    failed.incrementAndGet();
                    System.out.println("USER_ERROR " + failure.getClass().getSimpleName());
                } finally {
                    if (permit) {
                        ramp.release();
                    }
                    WebSocketSessions.cleanup();
                    JMeterContextService.getContext().clear();
                }
            }));
        }
        ramp.acquire(16);
        System.out.printf(Locale.ROOT,
                "RAMP users=%d connected=%d failed=%d seconds=%.3f cpuSeconds=%.3f nativeThreads=%d peakNativeThreads=%d%n",
                users.size(), connected.get(), failed.get(), (System.nanoTime()-start)/1e9,
                (os.getProcessCpuTime()-startCpu)/1e9, threads.getThreadCount(), threads.getPeakThreadCount());
        // Warm up before measuring steady-state; an explicit GC stabilizes retained-heap comparison.
        Thread.sleep(5000);
        System.gc();
        Thread.sleep(1000);
        long previousCpu = os.getProcessCpuTime();
        long previousTime = System.nanoTime();
        for (int i = 0; i < seconds; i++) {
            Thread.sleep(1000);
            long now = System.nanoTime();
            long cpu = os.getProcessCpuTime();
            System.out.printf(Locale.ROOT, "STEADY second=%d cpuPercent=%.3f nativeThreads=%d heapMiB=%.3f%n",
                    i, 100.0*(cpu-previousCpu)/(now-previousTime), threads.getThreadCount(),
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576.0);
            previousCpu = cpu;
            previousTime = now;
        }
        release.countDown();
        for (Thread user : users) {
            user.join();
        }
        Thread.sleep(5000);
        System.gc();
        System.out.printf(Locale.ROOT, "CLEANUP nativeThreads=%d heapMiB=%.3f%n", threads.getThreadCount(),
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576.0);
        System.exit(0);
    }
}
