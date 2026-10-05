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

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Measures result generation only; excludes the thread engine, listeners and result serialization. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class DummySamplerBenchmark {
    @Param({"HTTP", "STANDARD", "STATISTICAL"})
    public String resultType;

    @Param({"empty", "static", "variable"})
    public String workload;

    private DummySampler sampler;
    private JMeterVariables variables;
    private int sequence;

    @Setup
    public void setup() throws Exception {
        sampler = new DummySampler();
        sampler.setName("Dummy benchmark");
        // Match a GUI-saved sampler, including explicitly stored defaults.
        for (DummySamplerField field : DummySamplerField.values()) {
            sampler.setProperty(field.propertyName(), field.defaultValue());
        }
        sampler.setProperty(DummySampler.RESULT_TYPE, resultType);
        if (!"empty".equals(workload)) {
            sampler.setProperty(DummySamplerField.URL.propertyName(), "https://example.invalid/test?value=1");
            sampler.setProperty(DummySamplerField.RESPONSE_DATA.propertyName(), "x".repeat(1024));
        }
        variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        JMeterContextService.getContext().setSamplingStarted(true);
        if ("variable".equals(workload)) {
            sampler.setProperty(new FunctionProperty(DummySamplerField.URL.propertyName(),
                    new CompoundVariable("https://example.invalid/test?value=${value}")));
            sampler.setProperty(new FunctionProperty(DummySamplerField.SUCCESSFUL.propertyName(),
                    new CompoundVariable("${success}")));
        }
        sampler.setRunningVersion(true);
    }

    @Benchmark
    public SampleResult sample() {
        if ("variable".equals(workload)) {
            variables.incIteration();
            variables.put("value", Integer.toString(sequence++));
            variables.put("success", (sequence & 1) == 0 ? "true" : "false");
        }
        return sampler.sample(null);
    }

    @TearDown
    public void tearDown() {
        JMeterContextService.getContext().clear();
    }
}
