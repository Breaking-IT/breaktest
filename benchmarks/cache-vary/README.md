# Cache Manager request-header parsing

## Change and scope

Base: `f6b0c11dc`, matching fetched `origin/main` on 2026-09-12. Branch:
`codex/cache-manager-vary-headers`. Open PRs #137–144 were checked by changed
file: none touches CacheManager or its tests. #141 touches the adjacent H2
caller.

Both `saveDetails(URLConnection, HTTPSampleResult)` and
`saveDetails(HttpResponse, HTTPSampleResult)` now pass a null Vary entry directly
when the response has no Vary header. Previously Java evaluated
`asHeaders(res.getRequestHeaders())` before `getVaryHeader` returned null.
The guard skips the getter, line splitting, per-line splitting, temporary
strings/arrays, list and BasicHeader objects. It adds no retained fields.

The guard is deliberately a null check: empty and whitespace values retain
existing behavior. Exact `Vary: *` still prevents caching before request-header
access. Other Vary parsing, cache keys, expiration, no-store, cacheable methods,
status checks and conditional-request logic are unchanged. This is not a change
to Vary normalization or standards compliance.

Current production callers of the HttpResponse overload are HTTPHC5Impl and
HTTPHC5H2Impl. They format request headers before saving cache details and pass
the response object for individual response-header reads. There is no production
caller of the URLConnection overload in this checkout; its API and tests remain.
HTTPJavaHttp3Impl does not call these saveDetails overloads.

The standalone saving is avoided parsing of an **already formatted string**.
If deferred request formatting is introduced separately, skipping this getter
could additionally avoid materializing that string when no other consumer reads
it. No deferred formatting is included or measured here.

## Functional validation

JDK 21.0.5: **66 tests passed, zero failures or skips** across the existing HC5,
URLConnection and iteration suites plus 32 new parameterized cases. Main/test
Checkstyle and `git diff --check` pass.

The new cases exercise both overloads and 200/304 responses with absent, empty,
whitespace, wildcard, single-name, multiple-name, duplicate-name and missing
request-field Vary values. They assert getter read counts, matching conditional
ETag/Last-Modified headers, and nonmatching requests receiving no validators.
Existing tests cover expiration, no-cache/no-store, cache methods and clearing.
Running the new suite with the production file restored from baseline fails
exactly four absent-Vary cases; the other 28 pass. Restoring the candidate makes
all 32 pass.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew :src:protocol:http:test --tests '*TestCacheManager*' \
  :src:protocol:http:checkstyleMain :src:protocol:http:checkstyleTest
git diff --check
bash benchmarks/cache-vary/run.sh
```

## Focused comparison

`run.sh` compiles the pinned baseline CacheManager into a separate output directory
and selects it ahead of the candidate classes in separate JVMs. No worktree
source is changed by the probe. Both variants use the same harness and dependencies.
Each configuration has three JVM forks, alternating baseline/candidate order,
300,000 warmup saves and five measured batches of 100,000 saves per fork, with
`-Xms256m -Xmx256m`. `ThreadMXBean` reports allocating-thread bytes and `nanoTime`
reports wall time. This is a focused allocation probe, not JMH or an end-to-end
load test; timings are indicative and subject to JIT, GC and desktop noise.

Fixtures contain 4 headers / 100 ASCII bytes, 16 / 400, or 16 / 2,048. These are
exact LF-formatted request-string sizes, not wire bytes. Results, strings and
responses are preconstructed. The cache is warmed with one URL and ETag; repeated
saves hit an existing cache entry, with expiration processing disabled. Both
save overloads and absent/present (`X-0`) Vary are compared. A conditional request
after measurement checks that the cached validator remains usable.

Raw per-batch values are in `results.csv`; complete fork logs and compilation
outputs are generated under `build/cache-vary-probe/`. The DNS provider workaround
`-Djdk.net.hosts.file=/etc/hosts` keeps initialization independent of DNS provider
packaging; no HTTP or DNS operations are timed.

### Results (JDK 21.0.5, macOS aarch64)

Values below are medians of the three per-fork medians (five batches each).
All 72 JVM configurations completed successfully, producing 360 measured batches.

| Overload | Request bytes | Baseline B/save | Candidate B/save | Avoided B/save | Baseline ns/save | Candidate ns/save |
|---|---:|---:|---:|---:|---:|---:|
| hc5 | 100 | 5,072 | 264 | 4,808 | 1,157.1 | 83.3 |
| hc5 | 400 | 17,344 | 264 | 17,080 | 4,251.1 | 79.6 |
| hc5 | 2,048 | 20,672 | 264 | 20,408 | 12,658.8 | 83.8 |
| url | 100 | 5,072 | 264 | 4,808 | 1,126.7 | 83.9 |
| url | 400 | 17,344 | 264 | 17,080 | 4,280.9 | 84.0 |
| url | 2,048 | 20,672 | 264 | 20,408 | 12,633.1 | 86.7 |

Vary-present control:

| Overload | Request bytes | Baseline B/save | Candidate B/save | Baseline ns/save | Candidate ns/save |
|---|---:|---:|---:|---:|---:|
| hc5 | 100 | 6,832 | 6,832 | 1,484.0 | 1,450.8 |
| hc5 | 400 | 19,104 | 19,104 | 4,477.9 | 4,384.2 |
| hc5 | 2,048 | 23,472 | 23,472 | 12,894.1 | 12,981.6 |
| url | 100 | 6,832 | 6,832 | 1,521.5 | 1,454.1 |
| url | 400 | 19,104 | 19,104 | 4,534.9 | 4,336.1 |
| url | 2,048 | 23,472 | 23,472 | 13,768.7 | 12,707.8 |

The absent-Vary path consistently avoids 4,808 / 17,080 / 20,408 bytes per save
for these fixtures in both overloads. Candidate allocation is independent of
request-string size. Vary-present allocation remains similar, with minor
fork/JIT variation; no Vary-present performance improvement is claimed. The
large absent-Vary timing reduction concerns only repeated cache saves, not a
whole HTTP sample. No claim is made about throughput, retained heap, fresh-URL
insertion workloads, or formatting savings.
