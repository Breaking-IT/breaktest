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

package org.apache.jmeter.visualizers.backend;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.List;

import com.sun.management.OperatingSystemMXBean;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

/** Whole-fork CPU, including listener worker, GC and harness; not elapsed request latency. */
public class ProcessCpuProfiler implements InternalProfiler {
    private final OperatingSystemMXBean os =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private long startCpu;

    @Override
    public String getDescription() {
        return "Process CPU nanoseconds per benchmark operation";
    }

    @Override
    public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        startCpu = os.getProcessCpuTime();
        if (startCpu < 0) {
            throw new IllegalStateException("Process CPU time is unavailable");
        }
    }

    @Override
    @SuppressWarnings("rawtypes") // JMH's InternalProfiler interface uses a raw Result bound.
    public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams,
            IterationParams iterationParams, IterationResult result) {
        long cpu = os.getProcessCpuTime() - startCpu;
        long operations = result.getMetadata().getAllOps();
        return List.of(new ScalarResult("process.cpu", (double) cpu / operations,
                "ns/op", AggregationPolicy.AVG));
    }
}
