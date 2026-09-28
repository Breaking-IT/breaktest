# Targeted delay interruption benchmark: 2026-09-28

For the subsequent removal of one-second polling and measurements of 10,000 idle users, see [SleepingUsersBenchmark.md](SleepingUsersBenchmark.md). The results below measure repeated delay boundaries.

## Implementation

Normal delays use Thread.sleep again. Each JMeterThread keeps an identity registry of its sleeping workers. Registration and removal use a short synchronized block; sleep never holds that monitor. On graceful stop, only registered workers are interrupted. The worker consumes the stop interrupt before leaving the registry, preventing a late stop interrupt from leaking into a later request or cleanup. External interruptions without a graceful stop remain observable.

There is no change to the request-execution path. There is additional registration work at delay boundaries; this is not literally free. The registry also supports multiple parallel delay workers belonging to one virtual user.

## Results and interpretation

The previous monitor-wait implementation is superseded. The large virtual-thread CPU penalty and Java 21 platform-thread expansion are absent in this run. At 250 virtual users with 1 ms waits, observed median CPU per completed wait was about 3% higher on Java 21 and 13% higher on Java 26, versus approximately 9x and 3x in the historical monitor-wait benchmark. These are synthetic waiting-cost measurements, not HTTP-throughput penalties.

For 1,000 ms intervals, absolute CPU use is low and short-run variability is substantial. The measurements do not establish a reliable percentage overhead or prove zero overhead. A modest difference in the stress test remains a tradeoff, not a guarantee of identical performance.

## Method

- Same IdleDelayBenchmark harness and machine as the historical report: macOS arm64, 14 logical processors, 48 GiB RAM; Java 21.0.5 and 26.0.1.
- Fresh baseline measurements, no concurrent Gradle build. Each implementation/configuration uses a separate JVM, a 2 s warm-up and three 3 s measurement rounds; 256 MiB initial / 512 MiB maximum heap.
- The wakeable CSV label now means targeted sleep interruption, calling the actual current JMeterThread.awaitDelay implementation. No Stop is pressed during these CPU measurements.
- CPU is process CPU, including virtual-thread carriers and JVM housekeeping. Worker creation precedes timing. Platform-thread count is sampled halfway through a round and includes JVM service threads.
- Only complete intervals are counted; the last partial interval is excluded. With 1,000 ms waits and a 3 s window, each user completes two waits.
- Values below are medians; ranges are observed minimum–maximum, not confidence intervals. Three short rounds in one warmed JVM are exploratory measurements, not independent forks or proof of statistical equivalence.
- Users start together. The benchmark does not model HTTP traffic, allocation-heavy samplers, or many parallel waiters within a single virtual user.

| Java | Threads | Users | Delay ms | Implementation | CPU ms / 3 s | CPU range ms | CPU µs / wait | Waits/s | Platform threads |
|---|---|---:|---:|---|---:|---|---:|---:|---:|
| 21.0.5 | virtual | 25 | 1000 | sleep | 7.71 | 7.07–8.32 | 154.30 | 16.6 | 21 |
| 21.0.5 | virtual | 25 | 1000 | wakeable | 7.14 | 6.40–13.35 | 142.84 | 16.6 | 21 |
| 21.0.5 | virtual | 250 | 1000 | wakeable | 51.45 | 30.29–53.75 | 102.89 | 166.5 | 21 |
| 21.0.5 | virtual | 250 | 1000 | sleep | 50.79 | 50.23–61.31 | 101.58 | 166.4 | 21 |
| 21.0.5 | virtual | 25 | 1 | sleep | 421.28 | 320.32–515.42 | 7.28 | 19424.2 | 21 |
| 21.0.5 | virtual | 25 | 1 | wakeable | 406.40 | 315.52–531.44 | 6.98 | 19491.4 | 21 |
| 21.0.5 | virtual | 250 | 1 | wakeable | 2970.18 | 2893.54–3803.26 | 4.80 | 205970.0 | 21 |
| 21.0.5 | virtual | 250 | 1 | sleep | 2868.59 | 2858.87–3615.00 | 4.66 | 206322.5 | 21 |
| 21.0.5 | platform | 25 | 1000 | sleep | 8.11 | 6.76–9.40 | 162.20 | 16.7 | 31 |
| 21.0.5 | platform | 25 | 1000 | wakeable | 8.30 | 6.26–9.93 | 166.00 | 16.6 | 31 |
| 21.0.5 | platform | 25 | 1 | wakeable | 728.66 | 702.61–752.43 | 12.24 | 19794.8 | 31 |
| 21.0.5 | platform | 25 | 1 | sleep | 689.76 | 671.67–741.03 | 11.55 | 19792.3 | 31 |
| 26.0.1 | virtual | 25 | 1000 | sleep | 15.08 | 14.05–29.79 | 301.64 | 16.7 | 22 |
| 26.0.1 | virtual | 25 | 1000 | wakeable | 16.92 | 16.44–30.03 | 338.32 | 16.6 | 22 |
| 26.0.1 | virtual | 250 | 1000 | wakeable | 47.18 | 34.96–51.24 | 94.36 | 166.4 | 22 |
| 26.0.1 | virtual | 250 | 1000 | sleep | 39.70 | 34.77–46.73 | 79.40 | 166.4 | 22 |
| 26.0.1 | virtual | 25 | 1 | sleep | 790.87 | 714.82–943.52 | 13.49 | 19540.1 | 22 |
| 26.0.1 | virtual | 25 | 1 | wakeable | 672.06 | 648.80–974.07 | 11.40 | 19562.9 | 22 |
| 26.0.1 | virtual | 250 | 1 | wakeable | 3131.74 | 2981.74–3187.00 | 5.18 | 200257.3 | 22 |
| 26.0.1 | virtual | 250 | 1 | sleep | 2759.39 | 2722.12–3803.69 | 4.60 | 200217.0 | 22 |
| 26.0.1 | platform | 25 | 1000 | sleep | 6.87 | 6.71–7.38 | 137.44 | 16.7 | 31 |
| 26.0.1 | platform | 25 | 1000 | wakeable | 7.45 | 5.92–7.80 | 148.96 | 16.6 | 31 |
| 26.0.1 | platform | 25 | 1 | wakeable | 625.25 | 579.33–646.29 | 10.37 | 20104.3 | 31 |
| 26.0.1 | platform | 25 | 1 | sleep | 579.08 | 558.99–651.18 | 9.61 | 20076.7 | 31 |

## Reproduce

Build from the repository root:

    ./gradlew :src:core:compileJmhJava createDist

Run each implementation in a separate JVM, substituting the Java version to compare:

    java -Xms256m -Xmx512m -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.IdleDelayBenchmark virtual 250 1 3 3 sleep
    java -Xms256m -Xmx512m -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.IdleDelayBenchmark virtual 250 1 3 3 wakeable

Arguments are thread type, users, delay milliseconds, measurement seconds, rounds, implementation. Measured cases: virtual 25/250 users at 1/1000 ms; platform 25 users at 1/1000 ms.

Raw CSV and process logs: build/reports/benchmarks/idle-delay-targeted/. Historical monitor-wait measurements: build/reports/benchmarks/idle-delay/ and IdleDelayBenchmark.md.

## Correctness checks

Regression tests cover prompt wake-up of transaction think time, timers and iteration pacing; a stop issued before waiting; multiple delay workers; preservation of unrelated interrupts; and no interruption of subsequent work after deregistration. The engine test also verifies graceful stop does not interrupt an active blocked sampler.

Full core checks passed: 1,101 tests passed, 3 skipped. Autostyle passed and the local distribution was rebuilt.
