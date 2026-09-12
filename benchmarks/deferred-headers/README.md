<!--
Copyright 2024-2026 Breaking IT

Licensed under the BreakTest Community Source License 1.0.
You may not use this file except in compliance with that license.
See the LICENSE file at the root of this distribution.
-->

# Deferred HC5 diagnostic headers — candidate review

This worktree isolates the header prototype from `/Users/jvangaalen/.codex/worktrees/5b7a/breaktest`; that worktree is preserved. Base: `f6b0c11dc` (`origin/main`, checked 2026-09-12). Open PRs #137–144 do not implement deferred headers. #138 and #144 change adjacent response-body handling in `HTTPSamplerBase`; #139 contains the separate backend-listener optimization, which is excluded here.

## Setting and performance tradeoffs

Deferred formatting is enabled by default for HC5 HTTP/1 and HTTP/2 and the Java HTTP/3 client. In the GUI, open **Options → Settings → HttpClient5** and toggle `httpclient5.defer_diagnostic_headers` (search for `defer` or `headers`). Save and restart BreakTest for the change to take effect. Set `httpclient5.defer_diagnostic_headers=false` in `user.properties`, or pass `-Jhttpclient5.defer_diagnostic_headers=false` at startup, to restore eager formatting. Set it to `true` to enable it explicitly. Restart the process after changing it: the property is read at class initialization.

The default targets workloads where most diagnostic headers remain unread, such as headless API tests whose TSDB listener captures headers only on errors. This is not an unconditional performance improvement. Small read-heavy headers can cost more CPU, unread snapshots can retain more heap, and the two snapshot references enlarge every HTTP result by 8 bytes even when disabled. The switch lets those workloads retain eager formatting. End-to-end benefits depend on the plan and other consumers.

The explicitly fixed-heap confirmation measured these changes in process CPU for **header capture/consumption only**; negative means less CPU with deferral:

| Combined fixture bytes | Protocol | Unread | Both read 50% | Both read 100% |
|---:|---|---:|---:|---:|
| 400 | HTTP/1 buffered | −31% | −8% | +12% |
| 400 | HTTP/2 basic | −37% | −8% | +32% |
| 1,600 | HTTP/1 buffered | −59% | −37% | −13% |
| 1,600 | HTTP/2 basic | −67% | −31% | +4% (inconclusive intervals) |
| 8,192 | HTTP/1 buffered | −78% | −59% | −42% |
| 8,192 | HTTP/2 basic | −86% | −51% | −17% |

For the 400-byte HTTP/1 fixture, 50% reads reduce mean CPU from **197.5 to 181.3 ns/pair**, while allocation rises **1,456→1,568 B/pair**. At 100% reads, CPU rises **191.1→213.1 ns** and allocation **1,456→1,952 B**. The equivalent HTTP/2 100%-read case rises **133.5→175.7 ns**, **1,264→1,488 B**. Thus the assumption that half the headers remain unread can support some CPU benefit without proving an overall memory benefit. These are not HTTP request latency or throughput improvements.

There is no universal break-even read rate or size. In the full ergonomic-heap sweep, the 400-byte mean CPU crossing lies between 75–100% both-read for HTTP/1 (linear estimate 82%) and 50–75% for HTTP/2 (57%). Fixed-heap confirmation samples only 0/50/100%; its interpolation gives approximately 71% and 60%, respectively. Treat these as coarse fixture/heap-specific estimates, not deployment thresholds. In contrast, the 400-byte allocation crossing is about **35%** reads for HTTP/1 and **71%** for HTTP/2. The 1,600-byte HTTP/2 all-read CPU result is sensitive to heap settings and uncertainty; do not claim a consistent gain there.

The 84-configuration full sweep and 36-configuration fixed-heap confirmation each contain two forks × four valid measured iterations. Unread, request-only, response-only and mixed rates are covered by the full sweep. The final malformed-redirect correction affects only an error path outside the benchmark; the timed capture/snapshot/getter implementation is unchanged. [Detailed measurements and intervals](RESULTS.md) and the raw evidence below make the limitations reviewable.

## Scope and compatibility

`httpclient5.defer_diagnostic_headers` controls formatting at startup for HC5 HTTP/1 and HTTP/2 and the Java HTTP/3 client and defaults to true. Request cookies retain their existing separate handling and are excluded from `getRequestHeaders()`. HTTP/3 uses the same deferred storage with snapshots of Java client header maps. The original measurements below cover HC5; [separate Java HTTP/3 header-path measurements](HTTP3.md) cover the extension, including its read-heavy regressions. [Java 26 results](HTTP3-JAVA26.md) include response-only comparisons and passing live HTTP/3 validation. The option does not promise that lazy formatting improves every workload.

The snapshots contain only owned strings: mutable parser buffers are copied at capture time. Basic header names and values are immutable strings. Ordering, duplicates, null values, original buffered whitespace and protocol-specific status-line formatting are preserved. Response-size accounting uses the original character-count convention, including the extra CR/LF accounting; it does not silently redefine size as UTF-8 bytes or HTTP/2 compressed wire bytes. Body and sent-byte paths remain unchanged. A review fix restores the original zero header size for a redirect missing `Location`: capture now computes size earlier, so that error path explicitly resets it before reporting the protocol failure. Its response headers remain available for diagnostics.

First materialization synchronizes and publishes a cached string using a volatile reference, releasing the component array. Shallow clones can share that snapshot safely. String setters override the snapshot on that result. As with existing `SampleResult`, publication to other threads must be safe; concurrent mutation by setters is not a supported thread-safety contract. Copy constructors and debug output use getters. The wrappers and response prefix remain reachable after reading.

Java serialization materializes both getters into the existing `SampleResult` String fields. Snapshot fields are transient, and existing serialVersionUID values are unchanged. The two-direction compatibility probe compiles actual baseline `SampleResult` and `HTTPSampleResult` classes and uses separate JVMs with `SampleEvent` and nested HTTP results. XML/JTL output continues through existing converters/getters. This does not guarantee compatibility with arbitrary third-party reflection-based serializers. This repository no longer contains the legacy RMI sample-sender implementations and directs distributed use to Enterprise or external orchestration; those external transports are not exercised here. The cross-version stream probe is compatibility evidence, not a full distributed deployment test.

Some internal consumers already read headers: cache `Vary` processing, redirect aggregation, resource user-agent extraction, recording, debug output and result saving. Any distributed consumer using Java serialization forces both sides to be read. A user's unread fraction must include those consumers, not only visible listeners.

## Benchmark method

Apple M4 Pro, macOS 26.6.2, JDK 21 at `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`, JMH 1.37. One thread, two JVM forks per configuration, three 500 ms warmups and four 500 ms measurements per fork. The full matrix uses JVM ergonomic heap sizing: the initial command-line JVM arguments replaced the fixed-heap annotation. A separate confirmation run explicitly sets a 512 MiB heap for unread, 50% both-read and 100% both-read configurations at every size/protocol. No Gradle tests or retention probes run concurrently with timing. Normal desktop background activity is not controlled; this is a local microbenchmark, not a dedicated performance host.

An operation creates an `HTTPSampleResult`, captures both sides, computes response header accounting, and optionally reads request only, response only, or both at 0%, 25%, 50%, 75%, or 100%. Mixed cases use an identical interleaved permutation of 0–99 for eager and deferred paths. Inputs are constructed outside timed code; snapshots, arrays, text formatting and result allocation are timed. Blackholes consume the result and requested strings. CPU is whole-fork process CPU including GC/JIT/harness; wall time is JMH average time. Allocation is JMH `gc.alloc.rate.norm`. Timings exclude HTTP transport, parsing, TLS, body processing, cookie creation and serialization.

Fixtures are entirely synthetic ASCII, with exact UTF-8 length assertions during setup. `totalBytes` means the sum of the LF-normalized request headers, the excluded Cookie line, and the LF-normalized response headers including its status line. It is **not wire size**: no request line, CRLF expansion, HTTP/2 pseudo-headers, HPACK or TLS framing is counted. Cookies make up one quarter of each total. The response makes up one half, and optimized request text one quarter:

| Total | Optimized request | Response | Excluded Cookie line | Captured headers per side |
|---:|---:|---:|---:|---:|
| 400 | 100 | 200 | 100 | 4 |
| 1,600 | 400 | 800 | 400 | 16 |
| 8,192 | 2,048 | 4,096 | 2,048 | 16 |

HTTP/1 response headers use `BufferedHeader` to model parser-buffer ownership; HTTP/2 uses `BasicHeader`. Request headers use `BasicHeader` in both. Equal-sized synthetic values deliberately control size/count; they are not a distribution of all user workloads. No inference about the user's unstated units or average real header size is made.

The eager comparison runs in the candidate build, so it includes the added result fields. Baseline result-layout cost is measured separately. Large all-read gains can arise from exact builder pre-sizing in the snapshot formatter, not from skipping formatting. An eager pre-sizing alternative deserves separate investigation before attributing such gains to laziness.

Header-graph measurements use instrumentation plus identity-deduplicated traversal, including String backing arrays. This is reachable object-graph size, **not dominator retained heap or incremental heap**: some immutable strings are shared with input messages. The result container is reported separately. Unread snapshots may allocate less over time while retaining more objects per queued result.

## Reproduce

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew :src:protocol:http:test --tests '*DeferredHttpHeadersTest' --tests '*TestHTTPHC5Impl' --tests '*TestRedirects' --tests '*HTTPHC5H2ThreadLifecycleTest' :src:core:test --tests '*TestSampleResult' --tests '*TestSampleSaveConfiguration' :src:protocol:http:jmhJar :src:protocol:http:checkstyleMain :src:protocol:http:checkstyleTest :src:protocol:http:checkstyleJmh :src:core:checkstyleMain
# Reproduce the full ergonomic-heap matrix exactly:
HEADER_JVM_ARGS='-Djdk.net.hosts.file=/etc/hosts' bash benchmarks/deferred-headers/run.sh > benchmarks/deferred-headers/results/matrix.log 2>&1
# Confirm main decision points with the corrected fixed-heap defaults:
HEADER_RESULTS_PATH=benchmarks/deferred-headers/results/fixed-heap.json bash benchmarks/deferred-headers/run.sh -p readers=unread,both50,both100 > benchmarks/deferred-headers/results/fixed-heap.log 2>&1
bash benchmarks/deferred-headers/serialization-compatibility.sh
bash benchmarks/deferred-headers/integration.sh
bash benchmarks/deferred-headers/retention.sh
python3 benchmarks/deferred-headers/summarize.py
```

The shaded JMH jar has a dnsjava multi-release service-provider packaging issue. Probes and forks use `-Djdk.net.hosts.file=/etc/hosts` to bypass provider loading; no DNS or external network is timed. Missing GUI log-appender diagnostics occur during initialization/warmup only. The first invocation without that workaround failed before measurement and was replaced with the successful run.

Historical results were located under `5b7a/breaktest/build/reports/deferred-http-headers/`, including `analysis.md`, `representative-analysis.md`, retention CSVs and `crossover/analysis.md`. Those used JDK 26 and different sizes/layouts. The previously quoted 171→146 ns and other values are historical context, not results of this candidate run.

## Functional evidence

The final targeted Gradle run completed successfully: **156 passed, 3 skipped, 0 failed** (133 HTTP tests including 3 skips; 26 core tests). The 13 new tests cover exact text/accounting, duplicates/null/Unicode/buffered values, parser-buffer and message mutation, copy construction, shallow cloning, setters, concurrent cached reads, Java serialization, XML saving, request/response extractors and assertions, recording header rewriting, and live HTTP success/error with cookies. Existing HC5, redirect, H2 lifecycle, SampleResult and save-configuration suites also passed. Main/test/JMH Checkstyle and `git diff --check` passed.

A subsequent GUI-settings regression test exercises the actual Swing checkbox on the event-dispatch thread, verifies search matches and the enabled default, and saves/reloads both disabled and enabled choices using temporary property files. The settings suite, Checkstyle and license check pass; see [GUI validation](results/gui-validation.log).

The skipped tests are the existing externally gated `http2NegotiatesHttp2AgainstBreaktestApp`, `http11ReportsConnectTimeAgainstBreaktestApp`, and `benchmarkShortLivedParallelUserChurn`. No broad test pass is claimed for those environments.

`integration.sh` runs isolated JVMs with the property absent (default enabled), explicitly false, and explicitly true. It samples only a temporary loopback server, exercises explicit HTTP/1 and the default H2 facade negotiating HTTP/1 fallback, and verifies successful/error responses, redirect subresults, malformed-redirect diagnostics/accounting, cookies, header text, accounting, and that response snapshots are still unmaterialized on return from ordinary sampling. It does not establish live HTTP/2 wire negotiation. Recording tests exercise the recorder's actual response-header rewriting method, not a full GUI recording session.

`serialization-compatibility.sh` passes candidate→baseline and baseline→candidate streams, including nested results, using baseline source compiled from the pinned base commit. External Enterprise/orchestrator deployments and arbitrary plugin serializers remain untested.

## Retention evidence

| Combined fixture bytes | Eager header graph | Deferred unread graph (H1 / H2) | Deferred after both reads |
|---:|---:|---:|---:|
| 400 | 384 | 1,064 / 1,064 | 544 |
| 1,600 | 1,280 | 3,656 / 3,656 | 1,440 |
| 8,192 | 6,224 | 8,648 / 8,640 | 6,384 |

Baseline `HTTPSampleResult` shallow size is 248 bytes; candidate size is 256 bytes, including with the option disabled. Every materialized pair retains 160 extra reachable bytes in wrapper/prefix storage for these fixtures. These graph measurements include shared strings and must not be multiplied into a precise incremental-heap estimate for a real plan. They do demonstrate why fewer allocated bytes per operation does not establish lower memory use for long-lived results.

## Artifacts

- [Full ergonomic-heap matrix and crossover estimates](RESULTS.md)
- [Raw matrix](results/matrix.json), [paired CPU/wall/allocation CSV](results/paired.csv), [JMH log](results/matrix.log)
- [Fixed-heap confirmation](results/fixed-heap.json), [confirmation log](results/fixed-heap.log)
- [Retention graph CSV](results/retention.csv), [layout log](results/retention.log)
- [Functional/style validation](results/validation.log), [property integration probe](results/integration.txt), [cross-version serialization](results/serialization.txt)

The full-matrix launcher reported a shell error after JMH had successfully written all 84 configurations because the script was edited while its JVM was running. All 84 records and their expected fork/iteration data were checked; the raw log preserves this footer. The fixed-heap run uses the corrected, unchanged launcher. This launcher issue did not interrupt any JMH fork.
