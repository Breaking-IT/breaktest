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
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
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
        "-Djmeter.threads.virtual.enabled=true"
})
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 0)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ForeachControllerCapacityBenchmark {

    @Param({"100", "10000", "100000"})
    int itemCount;

    @Param("8")
    int maxParallel;

    @Param("30")
    int startTimeoutSeconds = 30;

    @Param("4")
    int representativeSamplersPerItem = 4;

    @Param("4")
    int sampleResultPayloadKilobytes = 4;

    @Param("16")
    int retainedPayloadKilobytes = 16;

    @Benchmark
    public CapacityResult stallingParallelForEachWindow() throws Exception {
        ForEachPlan plan = createPlan(0, 0, 0);
        return runPlanUntilBranchesAreStalled(plan);
    }

    @Benchmark
    public CapacityResult representativeParallelForEachWindow() throws Exception {
        ForEachPlan plan = createPlan(
                representativeSamplersPerItem,
                sampleResultPayloadKilobytes,
                retainedPayloadKilobytes);
        return runPlanUntilBranchesAreStalled(plan);
    }

    @Benchmark
    public CapacityResult transactionParallelForEachRetentionWindow() throws Exception {
        ForEachPlan plan = createPlan(0, 0, 0, true);
        return runPlanUntilBranchesAreStalled(plan);
    }

    private CapacityResult runPlanUntilBranchesAreStalled(ForEachPlan plan) throws Exception {
        int expectedActiveBranches = Math.min(itemCount, maxParallel);
        long heapBefore = usedHeap();
        int platformThreadsBefore = platformThreadCount();
        Thread runner = new Thread(plan.jMeterThread, "foreach-capacity-benchmark");
        long startNanos = System.nanoTime();
        runner.start();

        boolean expectedBranchesStarted = plan.branchesStarted.await(startTimeoutSeconds, TimeUnit.SECONDS);
        long activeBranches = plan.activeBranches.get();
        long elapsedNanos = System.nanoTime() - startNanos;
        long heapAtPeak = usedHeap();
        int platformThreadsAtPeak = platformThreadCount();

        plan.jMeterThread.stop();
        plan.releaseBranches.countDown();
        runner.join(TimeUnit.SECONDS.toMillis(startTimeoutSeconds));
        if (runner.isAlive()) {
            throw new IllegalStateException("JMeterThread did not finish after releasing ForEach branches");
        }
        if (!expectedBranchesStarted) {
            throw new IllegalStateException(
                    "Only " + activeBranches + " ForEach branches started; expected " + expectedActiveBranches);
        }

        return new CapacityResult(
                itemCount,
                maxParallel,
                expectedActiveBranches,
                activeBranches,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                heapAtPeak - heapBefore,
                platformThreadsAtPeak - platformThreadsBefore,
                platformThreadsAtPeak);
    }

    private ForEachPlan createPlan(int payloadSamplersPerItem, int payloadKilobytes, int retainedPayloadKilobytes) {
        return createPlan(payloadSamplersPerItem, payloadKilobytes, retainedPayloadKilobytes, false);
    }

    private ForEachPlan createPlan(int payloadSamplersPerItem, int payloadKilobytes,
            int retainedPayloadKilobytes, boolean transactionRetentionScenario) {
        HashTree testTree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        ForeachController foreach = new ForeachController();
        foreach.setName("foreach");
        foreach.setInputVal("input");
        foreach.setReturnVal("item");
        foreach.setUseSeparator(true);
        foreach.setParallel(true);
        foreach.setMaxParallel(maxParallel);
        foreach.setEnabled(true);
        CountDownLatch releaseBranches = new CountDownLatch(1);
        CountDownLatch branchesStarted = new CountDownLatch(Math.min(itemCount, maxParallel));
        AtomicInteger activeBranches = new AtomicInteger();

        testTree.add(loop);
        testTree.add(loop, foreach);
        GenericController samplerParent = foreach;
        if (transactionRetentionScenario) {
            TransactionController transaction = new TransactionController();
            transaction.setName("transaction");
            transaction.setGenerateParentSample(true);
            transaction.setEnabled(true);
            foreach.addTestElement(transaction);
            testTree.add(foreach, transaction);
            samplerParent = transaction;
        }
        for (int samplerIndex = 0; samplerIndex < payloadSamplersPerItem; samplerIndex++) {
            PayloadSampler payloadSampler = new PayloadSampler("payload-" + samplerIndex, payloadKilobytes);
            samplerParent.addTestElement(payloadSampler);
            testTree.add(samplerParent, payloadSampler);
        }
        int stallFromIndex = transactionRetentionScenario ? Math.max(1, itemCount - maxParallel + 1) : 1;
        StallingSampler stallingSampler = new StallingSampler(
                "stall",
                branchesStarted,
                releaseBranches,
                activeBranches,
                retainedPayloadKilobytes,
                stallFromIndex);
        samplerParent.addTestElement(stallingSampler);
        testTree.add(samplerParent, stallingSampler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("thread group");
        threadGroup.setNumThreads(1);
        JMeterThread jMeterThread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        jMeterThread.setThreadName("foreach-capacity-thread");
        jMeterThread.setThreadGroup(threadGroup);
        JMeterVariables variables = new JMeterVariables();
        for (int i = 1; i <= itemCount; i++) {
            variables.putObject("input_" + i, "value-" + i);
        }
        jMeterThread.putVariables(variables);
        return new ForEachPlan(jMeterThread, releaseBranches, branchesStarted, activeBranches);
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
                .include(ForeachControllerCapacityBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .detectJvmArgs()
                .build();
        new Runner(opt).run();
    }

    private static void runSweep(String[] itemCounts) throws Exception {
        if (itemCounts.length == 0) {
            itemCounts = new String[] { "100", "10000", "100000" };
        }
        ForeachControllerCapacityBenchmark benchmark = new ForeachControllerCapacityBenchmark();
        benchmark.maxParallel = 8;
        benchmark.startTimeoutSeconds = 60;

        System.out.println("scenario,itemCount,maxParallel,samplersPerItem,sampleResultPayloadKilobytes,"
                + "retainedPayloadKilobytes,expectedActiveBranches,activeBranches,startMillis,heapDeltaBytes,"
                + "platformThreadDelta,platformThreadsAtPeak");
        for (String itemCountValue : itemCounts) {
            benchmark.itemCount = Integer.parseInt(itemCountValue);
            printSweepResult("stallingParallelForEachWindow", benchmark.itemCount, benchmark.maxParallel,
                    0, 0, 0, runForSweep(benchmark::stallingParallelForEachWindow));
            printSweepResult("representativeParallelForEachWindow", benchmark.itemCount, benchmark.maxParallel,
                    benchmark.representativeSamplersPerItem,
                    benchmark.sampleResultPayloadKilobytes,
                    benchmark.retainedPayloadKilobytes,
                    runForSweep(benchmark::representativeParallelForEachWindow));
            printSweepResult("transactionParallelForEachRetentionWindow", benchmark.itemCount, benchmark.maxParallel,
                    0, 0, 0, runForSweep(benchmark::transactionParallelForEachRetentionWindow));
        }
    }

    private static CapacityResult runForSweep(Callable<CapacityResult> benchmark) throws Exception {
        System.gc();
        Thread.sleep(100);
        return benchmark.call();
    }

    private static void printSweepResult(String scenario, int itemCount, int maxParallel, int samplersPerItem,
            int sampleResultPayloadKilobytes, int retainedPayloadKilobytes, CapacityResult result) {
        System.out.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                scenario,
                itemCount,
                maxParallel,
                samplersPerItem,
                sampleResultPayloadKilobytes,
                retainedPayloadKilobytes,
                result.expectedActiveBranches(),
                result.activeBranches(),
                result.startMillis(),
                result.heapDeltaBytes(),
                result.platformThreadDelta(),
                result.platformThreadsAtPeak());
    }

    private record ForEachPlan(
            JMeterThread jMeterThread,
            CountDownLatch releaseBranches,
            CountDownLatch branchesStarted,
            AtomicInteger activeBranches) {
    }

    public record CapacityResult(
            int itemCount,
            int maxParallel,
            long expectedActiveBranches,
            long activeBranches,
            long startMillis,
            long heapDeltaBytes,
            int platformThreadDelta,
            int platformThreadsAtPeak) {
    }

    private static final class StallingSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicInteger activeBranches;
        private final int retainedPayloadKilobytes;
        private final int stallFromIndex;

        private StallingSampler(String name, CountDownLatch started, CountDownLatch release,
                AtomicInteger activeBranches, int retainedPayloadKilobytes, int stallFromIndex) {
            setName(name);
            this.started = started;
            this.release = release;
            this.activeBranches = activeBranches;
            this.retainedPayloadKilobytes = retainedPayloadKilobytes;
            this.stallFromIndex = stallFromIndex;
        }

        @Override
        public SampleResult sample(Entry e) {
            String item = JMeterContextService.getContext().getVariables().get("item");
            int itemIndex = Integer.parseInt(item.substring("value-".length()));
            if (itemIndex < stallFromIndex) {
                return successfulResult(new byte[0]);
            }
            byte[] retainedPayload = createPayload(retainedPayloadKilobytes, getName().hashCode());
            activeBranches.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                activeBranches.decrementAndGet();
            }
            return successfulResult(retainedPayload);
        }

        private SampleResult successfulResult(byte[] retainedPayload) {
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

    private static byte[] createPayload(int kilobytes, int seed) {
        int bytes = kilobytes * 1024;
        byte[] payload = new byte[bytes];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (seed + i);
        }
        return payload;
    }
}
