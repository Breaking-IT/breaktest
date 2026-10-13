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

package org.apache.jmeter.util;

import java.util.concurrent.TimeUnit;

import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Measures cached inline Groovy execution, including engine lookup and fresh bindings. */
@Fork(value = 2, jvmArgsPrepend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class JSR223EngineBenchmark {
    private JSR223TestElement element;

    @Setup
    public void setup() throws Exception {
        element = new JSR223TestElement() { };
        element.setName("Cached Groovy");
        element.setScriptLanguage("groovy");
        element.setScript("def n = Integer.parseInt(vars.get('counter')); vars.put('counter', Integer.toString(n + 1)); n");
        JMeterVariables variables = new JMeterVariables();
        variables.put("counter", "0");
        JMeterContextService.getContext().setVariables(variables);
        execute();
    }

    @Benchmark
    public Object execute() throws Exception {
        return element.processFileOrScript(element.getScriptEngine(), null);
    }

    @TearDown
    public void tearDown() {
        JMeterContextService.getContext().clear();
    }
}
