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

# Idle-delay benchmark: 2026-09-28

Historical results for the superseded Object.wait implementation. The current awaitDelay method uses targeted interruption of sleeping workers; see IdleDelayTargetedBenchmark.md for its measurements. The commands below now exercise the current implementation, not the historical monitor-based version.

## Conclusion

The monitor-based wakeable wait is not a zero-penalty replacement for sleep. In the 1 ms virtual-user stress cases, median CPU per completed wait rises substantially, especially on Java 21. Java 21 also expands the platform-thread pool. Longer 1,000 ms waits have low absolute CPU cost and noisy short-run measurements; these runs do not establish a precise percentage overhead or prove zero overhead.

## Method

- macOS arm64, 14 logical processors, 48 GiB RAM. Java 21.0.5 (Oracle) and Java 26.0.1 (Homebrew).
- Standalone benchmark calls the actual JMeterThread.awaitDelay method; baseline uses the former Thread.sleep primitive with equivalent running-state/deadline checks.
- Each virtual user has its own JMeterThread and therefore its own wait lock. Users begin together. No HTTP traffic or requests to BenchMart.
- Separate JVM for each implementation/configuration, 256 MiB initial and 512 MiB maximum heap. Two-second warm-up, then three three-second measurement rounds per JVM. Implementation order alternates across configurations.
- Process CPU time includes virtual-thread carriers and JVM housekeeping. Worker creation happens before timing; releasing workers, final worker bookkeeping and joins are included.
- The platform-thread count is sampled halfway through each measurement, including JVM service threads; it is not a peak measurement.
- Only complete requested wait intervals are counted. For a 3 s window and 1 s delay, each user completes two full waits; a final partial interval is excluded.
- CPU per wait is normalized by completed work. One-millisecond waits are a scheduler stress case, not the supplied plan’s 5–25 second think times. Those think times currently sleep in 1 s chunks.
- These are exploratory local measurements on a desktop, not statistically independent process forks or an isolated performance lab. Small differences and noisy ranges should not be treated as conclusive.

## Results

Values are medians of three rounds. CPU range is the observed minimum–maximum, not a confidence interval.

| Java | Threads | Users | Delay ms | Implementation | CPU ms / 3 s | CPU range ms | CPU µs / wait | Waits/s | Platform threads |
|---|---|---:|---:|---|---:|---|---:|---:|---:|
| 21.0.5 | virtual | 25 | 1000 | sleep | 9.83 | 8.37–11.18 | 196.68 | 16.6 | 21 |
| 21.0.5 | virtual | 25 | 1000 | wakeable | 9.38 | 8.54–10.04 | 187.62 | 16.7 | 38 |
| 21.0.5 | virtual | 250 | 1000 | wakeable | 60.84 | 30.65–206.11 | 121.68 | 166.4 | 262 |
| 21.0.5 | virtual | 250 | 1000 | sleep | 55.04 | 45.26–55.13 | 110.08 | 166.5 | 21 |
| 21.0.5 | virtual | 25 | 1 | sleep | 402.40 | 321.10–512.84 | 6.92 | 19390.5 | 21 |
| 21.0.5 | virtual | 25 | 1 | wakeable | 1185.84 | 1165.04–1273.56 | 20.32 | 19521.7 | 38 |
| 21.0.5 | virtual | 250 | 1 | wakeable | 25802.15 | 22832.56–25905.55 | 43.52 | 197748.2 | 262 |
| 21.0.5 | virtual | 250 | 1 | sleep | 2971.94 | 2932.10–3722.89 | 4.86 | 206611.8 | 21 |
| 21.0.5 | platform | 25 | 1000 | sleep | 6.24 | 5.97–8.49 | 124.78 | 16.6 | 31 |
| 21.0.5 | platform | 25 | 1000 | wakeable | 7.56 | 7.32–7.63 | 151.18 | 16.7 | 31 |
| 21.0.5 | platform | 25 | 1 | wakeable | 690.73 | 683.63–766.30 | 11.68 | 19791.9 | 31 |
| 21.0.5 | platform | 25 | 1 | sleep | 705.67 | 682.84–765.63 | 11.91 | 19789.1 | 31 |
| 26.0.1 | virtual | 25 | 1000 | sleep | 14.58 | 10.87–21.28 | 291.66 | 16.6 | 22 |
| 26.0.1 | virtual | 25 | 1000 | wakeable | 16.72 | 14.49–30.07 | 334.38 | 16.6 | 22 |
| 26.0.1 | virtual | 250 | 1000 | wakeable | 39.47 | 31.42–39.64 | 78.95 | 166.5 | 22 |
| 26.0.1 | virtual | 250 | 1000 | sleep | 34.56 | 33.42–44.79 | 69.11 | 166.4 | 22 |
| 26.0.1 | virtual | 25 | 1 | sleep | 678.96 | 668.96–847.89 | 11.65 | 19401.5 | 22 |
| 26.0.1 | virtual | 25 | 1 | wakeable | 821.45 | 815.17–1064.00 | 14.13 | 19666.2 | 22 |
| 26.0.1 | virtual | 250 | 1 | wakeable | 6491.45 | 6146.47–6868.68 | 10.41 | 207908.6 | 22 |
| 26.0.1 | virtual | 250 | 1 | sleep | 2077.97 | 1813.39–2951.05 | 3.52 | 196742.4 | 22 |
| 26.0.1 | platform | 25 | 1000 | sleep | 6.42 | 6.26–7.65 | 128.34 | 16.6 | 31 |
| 26.0.1 | platform | 25 | 1000 | wakeable | 6.42 | 6.10–8.25 | 128.36 | 16.6 | 31 |
| 26.0.1 | platform | 25 | 1 | wakeable | 616.00 | 588.49–775.80 | 10.45 | 19645.3 | 31 |
| 26.0.1 | platform | 25 | 1 | sleep | 652.83 | 585.31–685.51 | 10.91 | 19669.2 | 31 |

## Reproduce

Build from the repository root:

    ./gradlew :src:core:compileJmhJava :src:core:checkstyleJmh autostyleCheck createDist

Run each command in a separate JVM; change java to the desired JVM executable. Arguments are thread type, users, wait milliseconds, measurement seconds, rounds, implementation.

    java -Xms256m -Xmx512m -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.IdleDelayBenchmark virtual 250 1 3 3 sleep
    java -Xms256m -Xmx512m -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.IdleDelayBenchmark virtual 250 1 3 3 wakeable


Measured combinations: virtual users 25/250 with delays 1/1000 ms, and platform users 25 with delays 1/1000 ms, for both JVMs.

Raw data and process logs are in build/reports/benchmarks/idle-delay/. The production wait implementation was not changed during benchmarking.
