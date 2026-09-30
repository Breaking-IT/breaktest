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

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
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

/** Real keep-alive requests to a controlled HTTPS endpoint; no Kerberos or test listeners. */
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HttpSamplerRequestBenchmark {
    @Param({"HTTP/1.1", "HTTP/2"})
    public String protocol;
    @Param({"none", "basic"})
    public String authentication;
    @Param({"https://localhost:8443/"})
    public String endpoint;

    private HTTPSamplerProxy sampler;

    @Setup
    public void setup() throws Exception {
        synchronized (HttpSamplerRequestBenchmark.class) {
            if (JMeterUtils.getJMeterProperties() == null) {
                String home = System.getProperty("jmeter.home");
                JMeterUtils.setJMeterHome(home);
                JMeterUtils.loadJMeterProperties(Path.of(home, "bin", "jmeter.properties").toString());
                JMeterUtils.setProperty("httpclient5.http2.io_thread_count", "1");
                JMeterUtils.setProperty("httpsampler.http3.prefer_for_default", "false");
            }
        }
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        URI uri = URI.create(endpoint);
        sampler = new HTTPSamplerProxy();
        sampler.setName("Benchmark GET");
        sampler.setProtocol(uri.getScheme());
        sampler.setDomain(uri.getHost());
        sampler.setPort(uri.getPort());
        sampler.setPath(uri.getRawPath());
        sampler.setMethod("GET");
        sampler.setHttpProtocol(protocol);
        sampler.setUseKeepAlive(true);
        sampler.setConnectTimeout("5000");
        sampler.setResponseTimeout("5000");
        if (authentication.equals("basic")) {
            AuthManager manager = new AuthManager();
            manager.set(-1, endpoint, "benchmark", "fixture", "", "", AuthManager.Mechanism.BASIC);
            sampler.setAuthManager(manager);
        }
        HTTPSampleResult probe = request();
        String expectedProtocol = protocol.equals("HTTP/2") ? "HTTP/2.0" : "HTTP/1.1";
        if (!probe.getResponseHeaders().contains("X-Bench-Protocol: " + expectedProtocol)
                && !probe.getResponseHeaders().contains("x-bench-protocol: " + expectedProtocol)) {
            throw new IllegalStateException("Wrong transport: " + probe.getResponseHeaders());
        }
        if (authentication.equals("basic") && !probe.getRequestHeaders().contains("Authorization: Basic ")) {
            throw new IllegalStateException("Basic auth was not exercised");
        }
    }

    @Benchmark
    public HTTPSampleResult request() {
        HTTPSampleResult result = (HTTPSampleResult) sampler.sample();
        if (!result.isSuccessful() || !result.getResponseCode().equals("200") || result.getResponseData().length != 512) {
            throw new IllegalStateException(result.getResponseCode() + ": " + result.getResponseMessage());
        }
        return result;
    }

    @TearDown
    public void teardown() {
        sampler.threadFinished();
        JMeterContextService.getContext().clear();
    }
}
