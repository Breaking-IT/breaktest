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

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Measures the actual callback, bounded queue, and worker; excludes HTTP and plugin serialization. */
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class BackendListenerContextBenchmark {
    @State(Scope.Benchmark)
    public static class ListenerState {
        @Param({"false", "true"})
        public boolean cached;

        @Param({"0", "5", "16"})
        public int argumentCount;

        @Param({"queue", "filter"})
        public String delivery;

        public BackendListener listener;

        @Setup(Level.Trial)
        public void setup() {
            listener = new BackendListener();
            listener.setName("context-benchmark");
            Class<? extends BackendListenerClient> client;
            if ("queue".equals(delivery)) {
                client = cached ? ReusingCountingClient.class : CountingClient.class;
            } else {
                client = cached ? ReusingFilteringClient.class : FilteringClient.class;
            }
            listener.setClassname(client.getName());
            Arguments args = new Arguments();
            for (int i = 0; i < argumentCount; i++) {
                args.addArgument("parameter" + i, "static-value-" + i);
            }
            listener.setArguments(args);
            CountingClient.delivered = 0;
            listener.testStarted();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            listener.testEnded();
            if ("queue".equals(delivery) && CountingClient.delivered == 0) {
                throw new IllegalStateException("Worker received no results");
            }
        }
    }

    @State(Scope.Thread)
    public static class EventState {
        public SampleEvent event;

        @Setup
        public void setup() {
            SampleResult result = new SampleResult();
            result.setSampleLabel("dummy");
            result.setSuccessful(true);
            event = new SampleEvent(result, "group");
        }
    }

    public static class CountingClient extends AbstractBackendListenerClient {
        // Only the worker writes; testEnded awaits its completion before reading.
        static long delivered;

        @Override
        public void handleSampleResults(List<SampleResult> results, BackendListenerContext context) {
            delivered += results.size();
        }
    }

    public static class FilteringClient extends CountingClient {
        @Override
        public SampleResult createSampleResult(BackendListenerContext context, SampleResult result) {
            return null;
        }
    }

    public static class ReusingCountingClient extends CountingClient {
        @Override
        public boolean canReuseSampleContext() {
            return true;
        }
    }

    public static class ReusingFilteringClient extends FilteringClient {
        @Override
        public boolean canReuseSampleContext() {
            return true;
        }
    }

    @Benchmark
    public void enqueue(ListenerState state, EventState event) {
        state.listener.sampleOccurred(event.event);
    }
}
