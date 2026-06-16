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

# BreakTest Initial Release Notes

BreakTest is a forked continuation of Apache JMeter focused on the same familiar test-plan model with a much leaner runtime profile. This initial release keeps compatibility with JMeter-style `.jmx` plans, plugin APIs, package names, and many existing properties, while introducing a set of CPU and memory reductions aimed at large, long-running HTTP load tests.

Compared with the upstream JMeter baseline this work has already shown about 50% lower memory usage, with CPU reductions around 20-50% depending on workload and runtime conditions.

## Highlights

- Lower memory pressure across high-volume HTTP tests by avoiding eager response decompression, reducing per-sample allocations, and sharing safe immutable test-plan state.
- Lower CPU usage by skipping unnecessary decompression and deep cloning work in the hot path.
- New HTTP response-processing modes let tests store compressed responses, discard successful response bodies, keep only error bodies, or retain MD5 checksums instead of full payloads.
- Support for modern HTTP content encodings now includes Brotli (`br`) and Zstandard (`zstd`) in addition to gzip and deflate.
- Java virtual threads are supported for regular thread groups, open-model thread groups, and concurrent embedded-resource downloads.
- BreakTest requires Java 21 or later.

## Resource Usage Improvements

### Lazy Response Decompression

HTTP samplers can now keep compressed response bytes in `SampleResult` and decompress only when response data is actually accessed. This avoids paying gzip/deflate/Brotli/zstd decompression cost for tests that only need status, timing, headers, assertions, summaries, or checksums.

The decoder system is also extensible through `ResponseDecoder`, with built-in support for gzip and deflate and service-loaded HTTP decoders for Brotli and zstd.

### Response Body Retention Controls

HTTP requests now expose response-processing modes:

- `Store response (decompress on access)`: keep response data compressed and decode lazily.
- `Fetch and discard (headers only)`: read the response stream but do not retain the body.
- `Store on error only (discard on success)`: discard successful response bodies while preserving failing responses for troubleshooting.
- `Checksum (MD5 of compressed)`: retain a checksum without storing the full response.
- `Checksum (MD5 of decompressed)`: stream-decompress for the checksum without keeping the full body.

These options are especially useful for high-throughput tests where response bodies dominate heap use. Validation runs keep response bodies available so users can still inspect sampler behavior while building a plan.

### Lightweight Test-Plan Cloning

BreakTest reduces the cost of preparing per-thread test plans with lightweight cloning. Eligible test elements share safe property state instead of deep-cloning every property for every virtual user, while runtime changes remain thread-local where needed.

This substantially reduces object counts and heap use on large plans. The feature is enabled by default and can be disabled with:

```properties
jmeter.clone.lightweight.enabled=false
```

Additional cloning improvements include less deep-cloning during next-iteration handling and cleanup of stale transaction data.

## HTTP Compression Support

BreakTest can decode:

- `gzip` and `x-gzip`
- `deflate`
- `br` (Brotli)
- `zstd` (Zstandard)

The zstd support bundles `zstd-jni` and is loaded through the same decoder SPI as other optional HTTP decoders.

## Concurrency And Shutdown

Virtual-thread support is enabled by default:

```properties
jmeter.threads.virtual.enabled=true
```

This applies to regular thread groups, open-model thread groups, and concurrent embedded-resource downloads, reducing the cost of large numbers of virtual users and parallel resource fetches on Java 21+.

Shutdown behavior was also improved so timers and delayed waits respond more promptly when a test is stopped.

## Compatibility Notes

BreakTest intentionally keeps many JMeter names in packages, properties, file formats, command internals, and Maven coordinates so existing `.jmx` files, scripts, and plugins keep working where possible.

The main runtime compatibility change for this release is the Java floor: BreakTest requires Java 21 or later.

## Security Notes

BreakTest inherits Apache JMeter's trusted-test-plan security model. Treat `.jmx` files as executable input and only open or run plans you trust, or isolate them first. See [SECURITY.md](./SECURITY.md) and [THREAT_MODEL.md](./THREAT_MODEL.md) for the project security model and vulnerability reporting process.
