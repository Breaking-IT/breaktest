<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Generic HTTP request regression benchmark

This JMH benchmark compares two builds using the same real local HTTPS server.
It exercises the complete `HTTPSamplerProxy.sample()` path with HTTP/1.1 and
HTTP/2, one and eight concurrent clients, and absent or Basic Authorization
Managers. Each client reuses its sampler and connection. Setup verifies the
server-observed protocol and Basic authorization header; every measured request
must succeed and return the expected 512-byte body.

Build `:src:protocol:http:jmhJar` in each checkout using identical benchmark
sources, then run from the changed checkout:

```sh
python3 benchmarks/kerberos-http/run.py \
  --baseline /absolute/path/to/original/checkout \
  --java /absolute/path/to/java \
  --output build/reports/kerberos-http-benchmark/results
```

Requires Docker with `caddy:2`, Python 3, and OpenSSL. The runner binds only to
loopback, creates a temporary self-signed certificate, and removes its server
container afterwards. It repairs the JMH fat jar's missing Multi-Release manifest
flag in benchmark copies for both versions (required by bundled dnsjava).
Production jars and sources are untouched. Run against trusted local checkouts.

Four paired repeats use fresh JVMs and alternate original/changed order. Each
fork has three one-second warm-up iterations and three one-second measurement
iterations, a fixed 512 MiB heap, and the same Java executable. CLI parameters
allow longer runs. Avoid builds and other heavy work while measuring.

JMH sample-time results provide mean and percentile latency. The custom profiler
reports completed operations per elapsed iteration second and process CPU per
operation (including async client I/O threads). The GC profiler reports allocation
per operation. Compare independent forks, not individual request samples, and
inspect variation before interpreting small percentage differences.

This measures steady-state ordinary traffic, not Kerberos throughput, startup,
connection churn, high-concurrency saturation, or WAN performance. A local Docker
server and a shared developer machine add noise. Results cannot establish that
there is zero overhead or that every workload is unaffected.

## Comparison recorded on 2026-09-30

Compared original production code at `2c36d9c0782e88ef226a8a4c01d666e4cfe92fe2`
with the Kerberos and sampler lifecycle fixes. Both builds used identical
benchmark sources, Oracle Java 21.0.5, and a 512 MiB heap. The local Caddy fixture
returned 512-byte responses over HTTPS. No measured request failed.

The main matrix used `--seconds 2`: four paired fresh-JVM repeats per scenario,
three two-second warm-up and three two-second measurement iterations. Changes
below are geometric means of paired throughput ratios (positive means faster).

| Protocol | Clients | No Authorization Manager | Basic auth |
|---|---:|---:|---:|
| HTTP/1.1 | 1 | -0.3% | +4.0% |
| HTTP/1.1 | 8 | -3.2% | +0.4% |
| HTTP/2 | 1 | -8.2% | -2.8% |
| HTTP/2 | 8 | -0.4% | -0.3% |

The single-client HTTP/2 result prompted four additional longer pairs using
`--seconds 5 --protocol HTTP/2 --threads 1 --auth none`. This follow-up measured
2,930 versus 2,938 requests/sec (+0.3%), with a paired 95% interval of -2.6% to
+3.3%. The original slower runs remain included above. Longer warm-up and later
execution both changed, so the cause of the earlier variation is unresolved.

HTTP/1.1 Basic auth allocated approximately 0.6–0.7 KB more per request (4–5%).
No-auth allocation differences were below 0.5%. No sustained throughput
regression was demonstrated, but zero overhead is not established: in particular,
the eight-client HTTP/1.1 no-auth interval was wide (-16.3% to +12.0%). Intervals
use Student-t on four paired log ratios and are exploratory, without adjustment
for multiple comparisons. This shared developer-machine run is not a universal
performance guarantee.

Run `python3 benchmarks/kerberos-http/summarize.py OUTPUT_DIRECTORY` to aggregate
raw results. It preserves fork-level values, paired intervals, latency, allocation,
and process CPU in `summary.json`, with a throughput table in `summary.md`.
