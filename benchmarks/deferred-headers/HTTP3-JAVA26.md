<!--
Copyright 2024-2026 Breaking IT

Licensed under the BreakTest Community Source License 1.0.
You may not use this file except in compliance with that license.
See the LICENSE file at the root of this distribution.
-->

# HTTP/3 deferred headers on Java 26

## Findings

CPU change from enabling deferral; negative means less CPU:

| Combined fixture bytes | Unread | Response only, every result | Both read 50% | Both read 100% |
|---:|---:|---:|---:|---:|
| 400 | −50.7% | +23.1% | +13.8% | +65.1% |
| 1,600 | −50.5% | +0.1% (tied) | −1.6% (inconclusive) | +49.4% |
| 8,192 | −77.5% | −29.5% | −38.8% | +2.1% (inconclusive) |

### Response-only detail

| Combined fixture bytes | Eager → deferred CPU (ns/op) | Eager → deferred elapsed (ns/op) | Eager → deferred allocation (B/op) |
|---:|---:|---:|---:|
| 400 | 140.0 → 172.3 | 132.7 → 165.8 | 1,192 → 1,040 |
| 1,600 | 532.1 → 532.4 | 502.6 → 517.7 | 4,880 → 2,432 |
| 8,192 | 1,130.6 → 797.3 | 990.7 → 738.3 | 21,736 → 9,024 |

The 1,600-byte response-only process CPU intervals overlap strongly: there is no demonstrated CPU difference. Its mean elapsed time is about 3% higher despite the CPU tie. At 400 bytes, reading only the response still increases CPU; the downside is not confined to reading both strings. At 8,192 bytes response-only saves both CPU and elapsed time. Response-only allocation decreases approximately 13%, 50% and 58%, respectively.

The Java 21 response-only CPU changes were +17.8%, +11.7%, and −33.5%. The Java 26 results above are the relevant supported-runtime measurements; do not carry the Java 21 percentages forward as Java 26 expectations. The large all-read case is also no longer a demonstrated CPU win: +2.1% has overlapping intervals, while mean elapsed time increases about 9%.

Deferral remains effective for unread headers across the tested sizes. Workloads that consume response headers on every result need to consider header size, CPU and allocation separately. These percentages describe the header operation, not whole-request CPU or throughput.

## Method and validation

OpenJDK 26.0.2.1+1-7, macOS AArch64, Apple M4 Pro, fixed 512 MiB heap. Same benchmark jar and production capture methods as the [Java 21 measurements](HTTP3.md), now executed by Java 26 in every JMH fork. JMH 1.37; one thread; two forks; three 500 ms warmups and four 500 ms measurements per configuration; 42 configurations. Process CPU includes GC/JIT/harness; elapsed time and allocation are also reported. No build or tests run concurrently with timing. Normal desktop background activity remains uncontrolled.

Response-only means both sides are captured for every result, but only `getResponseHeaders()` is read, on every operation. The request diagnostic text is never read. Unread and mixed/both-read cases retain their original definitions. Fixture bytes include the excluded Cookie line (one quarter), request diagnostic text (one quarter), and response diagnostic text (one half). These are ASCII diagnostic text sizes, not QUIC wire sizes.

All 38 targeted tests passed on Java 26 with no skips, including the three existing live HTTP/3 tests against cloudflare-quic.com. This validates the supported runtime and live protocol path separately from timing. The benchmark measures header capture/consumption, not network throughput, QPACK parsing, TLS, body handling or retained heap. Allocation reductions do not establish lower retained heap. Comparing Java 21 and Java 26 runs is descriptive: they were not interleaved, and runtime/GC/JIT differences and background load can affect the comparison.

## Evidence and reproduction

- [All 42 configurations, CPU confidence intervals, elapsed time and allocation](HTTP3-java26-RESULTS.md)
- [Raw JMH data](results/http3-java26-matrix.json)
- [Source and runtime manifest](results/http3-java26-source-manifest.json)
- [Java 26 test results](results/http3-java26-validation.json)
- [Official OpenJDK download metadata](https://github.com/oracle-actions/setup-java/blob/main/jdk.java.net-uri.properties)

Build the benchmark jar using the project's Java 21 build toolchain, then run it with Java 26:

```bash
JAVA_HOME=<jdk21> ./gradlew :src:protocol:http:jmhJar
JAVA_HOME=<jdk26> \
  HEADER_RESULTS_PATH=benchmarks/deferred-headers/results/http3-java26-matrix.json \
  bash benchmarks/deferred-headers/run.sh -p protocol=h3
python3 benchmarks/deferred-headers/summarize-http3.py --jdk 26

JAVA_HOME=<jdk21> BREAKTEST_HTTP3_LIVE=true ./gradlew \
  -PjdkTestVersion=26 -Porg.gradle.java.installations.paths=<jdk26> \
  :src:protocol:http:test --tests '*DeferredHttpHeadersTest' --tests '*TestHTTPJavaHttp3Impl'
```

Replace `<jdk21>` and `<jdk26>` with absolute JDK home directories. The JMH runner retains the existing DNS-provider workaround. JMH emits an Unsafe deprecation warning on Java 26, and the standalone jar emits missing GUI log-appender diagnostics during warmup; neither caused a failed fork.
