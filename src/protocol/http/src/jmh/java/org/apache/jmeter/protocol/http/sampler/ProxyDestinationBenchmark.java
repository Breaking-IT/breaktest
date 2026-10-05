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

package org.apache.jmeter.protocol.http.sampler;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Run with -prof gc and -t 1 / -t 8 to compare allocation and concurrent reads. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class ProxyDestinationBenchmark {
    @Param({"0", "10", "100", "1000"})
    public int ruleCount;

    @Param({"exact", "suffix"})
    public String ruleType;

    @Param({"first", "last", "miss"})
    public String match;

    private ProxyDestinationPolicy policy;
    private String host;
    private String rules;
    private String mode;

    @Setup
    public void setup() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ruleCount; i++) {
            builder.append(ruleType.equals("suffix") ? "*." : "api.")
                    .append("host").append(i).append(".example\n");
        }
        rules = builder.toString();
        mode = ruleCount == 0 ? "proxy_filter_all" : "proxy_filter_include";
        policy = ProxyDestinationPolicy.compile(mode, rules);
        host = match.equals("miss") ? "api.unmatched-host.example"
                : "api.host" + (match.equals("first") ? 0 : ruleCount - 1) + ".example";
    }

    @Benchmark
    public boolean match() {
        return policy.allowsProxy(host);
    }

    @Benchmark
    public ProxyDestinationPolicy changedConfiguration() {
        return ProxyDestinationPolicy.compile(mode, rules);
    }
}
