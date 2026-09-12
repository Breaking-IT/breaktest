<!--
Copyright 2024-2026 Breaking IT

Licensed under the BreakTest Community Source License 1.0.
You may not use this file except in compliance with that license.
See the LICENSE file at the root of this distribution.
-->

# Java HTTP/3 deferred-header benchmark

The supported-runtime follow-up is in [Java 26 results, including response-only reads](HTTP3-JAVA26.md). The numbers below remain the historical Java 21 measurements.

## Findings

| Combined fixture bytes | Unread CPU change | Both read 50% CPU change | Both read 100% CPU change | Unread allocation, eager → deferred |
|---:|---:|---:|---:|---:|
| 400 | −20.2% | +14.5% | +53.9% | 1,160 → 560 B/op |
| 1,600 | −30.3% | +10.1% | +45.2% | 4,880 → 752 B/op |
| 8,192 | −71.0% | −38.5% | −8.7% | 21,720 → 752 B/op |

Deferral helps unread headers at all tested sizes, while reading either side can already make smaller fixtures slower: request-only costs +11.2% CPU at 400 bytes and +4.4% at 1,600; response-only costs +17.8% and +11.7%. The 1,600-byte request-only CPU intervals overlap, so that small difference is inconclusive. It is incorrect to assume the downside appears only when both sides are read.

The 400-byte both25 case is effectively tied in process CPU; both50 is clearly more expensive. For 1,600 bytes, both25 reduces process CPU but both50 increases it. These are fixture-specific sampled crossings, not universal thresholds. The 8,192-byte fixture reduces process CPU across all tested readers, but its both100 wall time is slightly higher (902.5 → 921.4 ns/op): process CPU savings do not automatically mean lower elapsed time. Inspect the complete measurements and intervals before generalizing.

Unread allocation falls approximately 52%, 85% and 97%. At both100, allocation rises for 400 bytes (1,160 → 1,328 B/op) but falls for 1,600 (4,864 → 3,312) and 8,192 (21,704 → 13,200). CPU and allocation can move in opposite directions.

The default-enabled setting remains useful for predominantly unread diagnostic headers, but these HTTP/3 measurements show a narrower CPU benefit for small/read-heavy workloads than the earlier HC5 measurements. No total-request throughput improvement is claimed.

### Response-only reads on Java 21

These cases were included in the original full matrix. Both sides are captured, and the response string alone is read on every result.

| Combined fixture bytes | CPU change | Eager → deferred CPU (ns/op) | Eager → deferred allocation (B/op) |
|---:|---:|---:|---:|
| 400 | +17.8% | 128.7 → 151.6 | 1,160 → 1,040 |
| 1,600 | +11.7% | 480.4 → 536.3 | 4,864 → 2,432 |
| 8,192 | −33.5% | 1,220.5 → 812.1 | 21,704 → 9,024 |

## Scope and method

Measures the production Java HTTP/3 request/response capture helpers in commit `32db1cab1`, with the `h3` fixture added to `HeaderCaptureBenchmark`. The eager and deferred branches run in the same candidate build. Both allocate a new `HTTPSampleResult`, capture both header sides, compute response-header size, and optionally consume header strings. Inputs are created outside the timed operation using Java `HttpRequest` and `HttpHeaders.of(...).map()`; no artificial Apache-header adapter is included in the timed path.

Apple M4 Pro, macOS 26.6.2, JDK 21, JMH 1.37. One thread, fixed 512 MiB heap, two JVM forks, three 500 ms warmups and four 500 ms measurements per configuration. The 42 configurations cover eager/deferred, three sizes, and unread/request-only/response-only/both25/both50/both75/both100. A deterministic permutation interleaves mixed reads. Process CPU includes JIT, GC and harness overhead; wall time is average time per operation, allocation is JMH GC bytes per operation. Normal desktop background activity is uncontrolled. No tests or build run concurrently with measurements.

Fixture sizes of 400, 1,600 and 8,192 bytes include an excluded Cookie line equal to one quarter of the total, diagnostic request text equal to one quarter, and response text (including the HTTP/3 status line) equal to one half. There are four headers per side in the smallest fixture and sixteen in the others. Synthetic ASCII fixture lengths are asserted during setup. These are diagnostic text sizes, not QPACK or QUIC wire bytes.

This is a benchmark of the changed header path, not a live HTTP/3 load test. Java 21 can execute these capture methods, but cannot exercise Java 26's HTTP/3 transport. Transport, parsing, TLS, body handling and long-term retained heap are not measured; JVM-version effects are not established. Allocation reductions do not by themselves establish retained-memory reductions. Larger fully-read gains can come from exact builder sizing, rather than avoided formatting.

## Reproduce

From the repository root, build with JDK 21:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew :src:protocol:http:jmhJar :src:protocol:http:checkstyleJmh
HEADER_RESULTS_PATH=benchmarks/deferred-headers/results/http3-matrix.json \
  bash benchmarks/deferred-headers/run.sh -p protocol=h3
python3 benchmarks/deferred-headers/summarize-http3.py
```

The runner applies the existing DNS-provider workaround and explicitly fixes both initial and maximum heap to 512 MiB. Standalone missing GUI log-appender diagnostics occur during warmup; they are not measurement failures. [Raw JMH results](results/http3-matrix.json), [source manifest](results/http3-source-manifest.json), and [all measured pairs with uncertainty](HTTP3-RESULTS.md) preserve the evidence.
