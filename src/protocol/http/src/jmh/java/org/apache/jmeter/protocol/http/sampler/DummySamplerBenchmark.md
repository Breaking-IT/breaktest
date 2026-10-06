<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Dummy sampler throughput

Measured locally on 2026-10-05 on macOS arm64 with Oracle Java 21.0.5.
Baseline: commit `5a5fe6add` with the same benchmark harness copied into it.
Optimized: cached field keys and last parsed URL, a single charset lookup, and no redundant empty response allocation.

## Method

JMH 1.37, one worker thread, two JVM forks, three 1-second warmup iterations and five
1-second measurement iterations per fork, 512 MiB fixed heap, GC allocation profiler.
All cases use native HTTP results and explicitly stored GUI defaults. Sleeping is disabled.
The static workload uses a fixed URL and a 1,024-byte ASCII response; the variable workload
also changes the URL and success flag through FunctionProperty on each invocation.

The harness returns each result to JMH to prevent dead-code elimination. It measures
sampler calls, not complete test plans: no thread-engine dispatch, listeners, assertions,
result writing, or network I/O. Desktop scheduling and JVM differences affect these numbers;
they are not an end-to-end RPS guarantee or a demonstrated maximum. Baseline and optimized
runs were sequential, with identical JVM settings.

## Results

Throughput is the mean across both forks. Allocation is GC-profiler bytes per operation.

| Workload | Before samples/s | After samples/s | Speedup | Before bytes/sample | After bytes/sample |
|---|---:|---:|---:|---:|---:|
| empty | 1,101,905 | 5,800,147 | 5.26x | 3,952 | 352 |
| static | 847,955 | 4,363,494 | 5.15x | 5,736 | 1,392 |
| variable | 708,796 | 1,673,814 | 2.36x | 6,200 | 2,640 |

## Reproduce

Build `:src:protocol:http:jmhJar`, then run the benchmark with Java 21:

```sh
java -cp "$JMH_JAR:$DNSJAVA_JAR" org.openjdk.jmh.Main DummySamplerBenchmark \
  -p resultType=HTTP -prof gc -jvmArgs '-Xms512m -Xmx512m' \
  -rf json -rff dummy-results.json
```

Set `JMH_JAR` to the HTTP module's generated JMH jar and `DNSJAVA_JAR` to the original
resolved dnsjava 3.6.4 dependency jar. The original jar is needed because the assembled JMH
jar's DNS provider service entry references a multi-release class. The existing bundled
logging configuration also emits a missing GUI-appender warning during initialization;
this occurs before measurement in both versions.

Omit `-p resultType=HTTP` to include standard and statistical results. Use `-t 8` for a
separate multi-worker measurement; each worker owns its sampler. Neither variant was
part of the single-worker HTTP comparison above.

Properties still evaluate at sampling time. Only the last parsed URL is retained, so
changing URLs cannot grow an unbounded cache. Non-empty response buffers remain independent
between results; sharing mutable result instances or payload arrays would be unsafe for
listeners and scripts.
