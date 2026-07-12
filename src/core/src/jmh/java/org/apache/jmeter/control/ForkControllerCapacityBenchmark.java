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

package org.apache.jmeter.control;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 1, jvmArgsAppend = {
        "-Xms256m",
        "-Xmx2g",
        "-Dbreaktest.threads.virtual.enabled=true"
})
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 0)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ForkControllerCapacityBenchmark {

    @Param({"100", "1000", "10000"})
    int forkCount;

    @Param("30")
    int startTimeoutSeconds = 30;

    @Param("4")
    int representativeSamplersPerFork = 4;

    @Param("4")
    int sampleResultPayloadKilobytes = 4;

    @Param("16")
    int retainedPayloadKilobytes = 16;

    @Benchmark
    public CapacityResult uniqueForkControllers() throws Exception {
        ForkPlan plan = createPlanWithUniqueForkControllers(forkCount);
        return runPlanUntilForksAreStalled(plan, forkCount);
    }

    @Benchmark
    public CapacityResult representativeForkControllers() throws Exception {
        ForkPlan plan = createPlanWithRepresentativeForkControllers(
                forkCount,
                representativeSamplersPerFork,
                sampleResultPayloadKilobytes,
                retainedPayloadKilobytes);
        return runPlanUntilForksAreStalled(plan, forkCount);
    }

    @Benchmark
    public CapacityResult repeatedSameForkControllerInLoop() throws Exception {
        ForkPlan plan = createPlanWithRepeatedForkController(forkCount);
        return runPlanUntilForksAreStalled(plan, 1);
    }

    private CapacityResult runPlanUntilForksAreStalled(ForkPlan plan, int expectedActiveForks) throws Exception {
        long heapBefore = usedHeap();
        int platformThreadsBefore = platformThreadCount();
        Thread runner = new Thread(plan.jMeterThread, "fork-capacity-benchmark");
        long startNanos = System.nanoTime();
        runner.start();

        boolean expectedForksStarted = plan.forksStarted.await(startTimeoutSeconds, TimeUnit.SECONDS);
        boolean mainFlowReachedEnd = plan.mainFlowReachedEnd.await(startTimeoutSeconds, TimeUnit.SECONDS);
        long activeForks = plan.activeForks.get();
        long elapsedNanos = System.nanoTime() - startNanos;
        long heapAtPeak = usedHeap();
        int platformThreadsAtPeak = platformThreadCount();

        plan.releaseForks.countDown();
        runner.join(TimeUnit.SECONDS.toMillis(startTimeoutSeconds));
        if (runner.isAlive()) {
            plan.jMeterThread.stop();
            runner.join(TimeUnit.SECONDS.toMillis(5));
        }
        if (runner.isAlive()) {
            throw new IllegalStateException("JMeterThread did not finish after releasing fork samplers");
        }
        if (!expectedForksStarted) {
            throw new IllegalStateException(
                    "Only " + activeForks + " fork samplers started; expected " + expectedActiveForks);
        }
        if (!mainFlowReachedEnd) {
            throw new IllegalStateException("Main flow did not reach the sampler after the fork controllers");
        }

        return new CapacityResult(
                expectedActiveForks,
                activeForks,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                heapAtPeak - heapBefore,
                platformThreadsAtPeak - platformThreadsBefore,
                platformThreadsAtPeak);
    }

    private static ForkPlan createPlanWithUniqueForkControllers(int forkCount) {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        CountDownLatch releaseForks = new CountDownLatch(1);
        CountDownLatch forksStarted = new CountDownLatch(forkCount);
        AtomicInteger activeForks = new AtomicInteger();
        CountDownLatch mainFlowReachedEnd = new CountDownLatch(1);

        testTree.add(loop);
        for (int i = 0; i < forkCount; i++) {
            ForkController fork = new ForkController();
            fork.setName("fork-" + i);
            fork.setEnabled(true);
            testTree.add(loop, fork);
            testTree.add(fork, new StallingSampler("stall-" + i, forksStarted, releaseForks, activeForks));
        }
        testTree.add(loop, new LatchSampler("main-flow-after-forks", mainFlowReachedEnd));

        return createForkPlan(testTree, releaseForks, forksStarted, activeForks, mainFlowReachedEnd);
    }

    private static ForkPlan createPlanWithRepresentativeForkControllers(int forkCount, int samplersPerFork,
            int sampleResultPayloadKilobytes, int retainedPayloadKilobytes) {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        CountDownLatch releaseForks = new CountDownLatch(1);
        CountDownLatch forksStarted = new CountDownLatch(forkCount);
        AtomicInteger activeForks = new AtomicInteger();
        CountDownLatch mainFlowReachedEnd = new CountDownLatch(1);

        testTree.add(loop);
        for (int i = 0; i < forkCount; i++) {
            ForkController fork = new ForkController();
            fork.setName("representative-fork-" + i);
            fork.setEnabled(true);
            testTree.add(loop, fork);
            for (int samplerIndex = 0; samplerIndex < samplersPerFork; samplerIndex++) {
                testTree.add(fork, new PayloadSampler(
                        "dummy-sampler-" + i + '-' + samplerIndex,
                        sampleResultPayloadKilobytes));
            }
            testTree.add(fork, new StallingSampler(
                    "stall-" + i,
                    forksStarted,
                    releaseForks,
                    activeForks,
                    retainedPayloadKilobytes));
        }
        testTree.add(loop, new LatchSampler("main-flow-after-forks", mainFlowReachedEnd));

        return createForkPlan(testTree, releaseForks, forksStarted, activeForks, mainFlowReachedEnd);
    }

    private static ForkPlan createPlanWithRepeatedForkController(int loopCount) {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(loopCount);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForkController fork = new ForkController();
        fork.setName("fork");
        fork.setEnabled(true);
        CountDownLatch releaseForks = new CountDownLatch(1);
        CountDownLatch forksStarted = new CountDownLatch(1);
        AtomicInteger activeForks = new AtomicInteger();
        CountDownLatch mainFlowReachedEnd = new CountDownLatch(1);

        testTree.add(loop);
        testTree.add(loop, fork);
        testTree.add(fork, new StallingSampler("stall", forksStarted, releaseForks, activeForks));
        testTree.add(loop, new LatchSampler("main-flow-after-fork", mainFlowReachedEnd));

        return createForkPlan(testTree, releaseForks, forksStarted, activeForks, mainFlowReachedEnd);
    }

    private static ForkPlan createForkPlan(HashTree testTree, CountDownLatch releaseForks,
            CountDownLatch forksStarted, AtomicInteger activeForks, CountDownLatch mainFlowReachedEnd) {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("fork-capacity-thread");
        jMeterThread.setThreadGroup(threadGroup);
        return new ForkPlan(jMeterThread, releaseForks, forksStarted, activeForks, mainFlowReachedEnd);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static int platformThreadCount() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return threadMXBean.getThreadCount();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--sweep".equals(args[0])) {
            runSweep(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Options opt = new OptionsBuilder()
                .include(ForkControllerCapacityBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .detectJvmArgs()
                .build();
        new Runner(opt).run();
    }

    private static void runSweep(String[] forkCounts) throws Exception {
        if (forkCounts.length == 0) {
            forkCounts = new String[] { "100", "1000", "10000" };
        }
        ForkControllerCapacityBenchmark benchmark = new ForkControllerCapacityBenchmark();
        benchmark.startTimeoutSeconds = 60;

        System.out.println("scenario,forkCount,samplersPerFork,sampleResultPayloadKilobytes,"
                + "retainedPayloadKilobytes,expectedActiveForks,activeForks,startMillis,heapDeltaBytes,"
                + "platformThreadDelta,platformThreadsAtPeak");
        for (String forkCountValue : forkCounts) {
            benchmark.forkCount = Integer.parseInt(forkCountValue);
            printSweepResult("uniqueForkControllers", benchmark.forkCount, 0, 0, 0,
                    runForSweep(benchmark::uniqueForkControllers));
            printSweepResult("representativeForkControllers", benchmark.forkCount,
                    benchmark.representativeSamplersPerFork,
                    benchmark.sampleResultPayloadKilobytes,
                    benchmark.retainedPayloadKilobytes,
                    runForSweep(benchmark::representativeForkControllers));
            printSweepResult("repeatedSameForkControllerInLoop", benchmark.forkCount,
                    0, 0, 0,
                    runForSweep(benchmark::repeatedSameForkControllerInLoop));
        }
    }

    private static CapacityResult runForSweep(Callable<CapacityResult> benchmark) throws Exception {
        System.gc();
        Thread.sleep(100);
        return benchmark.call();
    }

    private static void printSweepResult(String scenario, int forkCount, int samplersPerFork,
            int sampleResultPayloadKilobytes, int retainedPayloadKilobytes, CapacityResult result) {
        System.out.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                scenario,
                forkCount,
                samplersPerFork,
                sampleResultPayloadKilobytes,
                retainedPayloadKilobytes,
                result.expectedActiveForks(),
                result.activeForks(),
                result.startMillis(),
                result.heapDeltaBytes(),
                result.platformThreadDelta(),
                result.platformThreadsAtPeak());
    }

    private record ForkPlan(
            JMeterThread jMeterThread,
            CountDownLatch releaseForks,
            CountDownLatch forksStarted,
            AtomicInteger activeForks,
            CountDownLatch mainFlowReachedEnd) {
    }

    public record CapacityResult(
            long expectedActiveForks,
            long activeForks,
            long startMillis,
            long heapDeltaBytes,
            int platformThreadDelta,
            int platformThreadsAtPeak) {
    }

    private static final class StallingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicInteger activeForks;
        private final int retainedPayloadKilobytes;

        private StallingSampler(String name, CountDownLatch started, CountDownLatch release,
                AtomicInteger activeForks) {
            this(name, started, release, activeForks, 0);
        }

        private StallingSampler(String name, CountDownLatch started, CountDownLatch release,
                AtomicInteger activeForks, int retainedPayloadKilobytes) {
            setName(name);
            this.started = started;
            this.release = release;
            this.activeForks = activeForks;
            this.retainedPayloadKilobytes = retainedPayloadKilobytes;
        }

        @Override
        public SampleResult sample(Entry e) {
            byte[] retainedPayload = createPayload(retainedPayloadKilobytes, getName().hashCode());
            activeForks.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                activeForks.decrementAndGet();
            }
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            if (retainedPayload.length > 0) {
                result.setResponseData(new byte[] { retainedPayload[0] });
            }
            result.sampleEnd();
            return result;
        }
    }

    private static final class PayloadSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final int payloadKilobytes;

        private PayloadSampler(String name, int payloadKilobytes) {
            setName(name);
            this.payloadKilobytes = payloadKilobytes;
        }

        @Override
        public SampleResult sample(Entry e) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSamplerData("GET /dummy/" + getName());
            result.setRequestHeaders("accept-encoding: br,deflate,gzip,zstd");
            result.setResponseData(createPayload(payloadKilobytes, getName().hashCode()));
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static final class LatchSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch latch;

        private LatchSampler(String name, CountDownLatch latch) {
            setName(name);
            this.latch = latch;
        }

        @Override
        public SampleResult sample(Entry e) {
            latch.countDown();
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.setSuccessful(true);
            result.sampleEnd();
            return result;
        }
    }

    private static byte[] createPayload(int kilobytes, int seed) {
        int bytes = kilobytes * 1024;
        byte[] payload = new byte[bytes];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (seed + i);
        }
        return payload;
    }
}
