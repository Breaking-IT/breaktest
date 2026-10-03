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

# WebSocket transport benchmark — 2026-10-03

Measured the production Connect/Close samplers before changing client ownership,
then reran the same workload with clients shared by TLS identity and virtual-thread
executor tasks.

## Results

CPU is process CPU time divided by elapsed time: **100% means one fully used core**.
Thread counts are JVM platform threads, excluding virtual threads. RSS is peak
client resident memory across the whole run. Heap is maximum used Java heap
during steady state, not a retained-memory measurement.

| Version | Requested / connected | Steady window | Platform threads | Mean CPU | Peak RSS MiB | Peak steady heap MiB |
| --- | --- | --- | --- | --- | --- | --- |
| Before | 300 / 300 | 20 s | 633 | 10.15% | 376.8 | 58.6 |
| Before | 2000 / 604 | 20 s | 1201 | 22.72% | 492.1 | 105.4 |
| After | 300 / 300 | 20 s | 39 | 7.19% | 283.6 | 48.7 |
| After | 2000 / 2000 | 20 s | 39 | 9.73% | 446.3 | 236.8 |
| After, repeat | 2000 / 2000 | 60 s | 26–39 | 6.97% | 446.4 | 249.5 |

The baseline 2000-user attempt stopped ramping at the 1200-platform-thread safety
guard (1201 observed with in-flight starts). **It is not a completed 2000-user
baseline or an observed operating-system thread-limit failure.** Do not extrapolate
its CPU measurements to 2000 users.

At equal load (300 connections), the observed differences were about 94% fewer
platform threads, 29% lower CPU, and 25% lower peak process RSS. The single before/after
CPU and RSS runs do not establish a repeatable improvement. These are exploratory measurements, not confidence
intervals. Both changes were benchmarked together; this does not isolate the
benefit of virtual threads from client sharing.

The largest one-second ping/pong p99, including connection establishment and teardown, was
14.56 ms before at 300, 5.52 ms after at 300, 13.73 ms after at 2000, and 19.23 ms
in the longer 2000-user repeat. These are **maxima of per-second percentiles**, not
whole-run p99 values. Server timestamps include local server scheduling delays.

All started connections succeeded. Both 2000-user runs returned to zero active
server connections after cleanup, with no peer-handler errors. The longer run
recorded 66,680 pings and 66,678 pongs; two pings were outstanding at shutdown.
The harness does not establish whether shutdown-interrupted pings would have
completed. After cleanup and a GC request, client used heap was about 13.7 MiB
and platform threads were 26. This is not a long-duration leak test.

## Environment and workload

- Apple M4 Pro, 14 logical CPUs, 48 GiB RAM; macOS 27.0.1.
- Oracle JDK 21.0.5; client flags: -Xms256m -Xmx1g -Xss256k.
- Python asyncio TLS server in a separate local process; its CPU is excluded from
  client measurements. Both processes still compete for the same host.
- Secure WebSocket connections on loopback, self-signed RSA certificate.
- One virtual user per connection, production Connect/Close samplers, 16
  concurrent connection attempts, no client certificate.
- Native 8-byte ping every two seconds per connection, staggered across users:
  about 1000 pings/second at 2000 connections.
- Native automatic pong, incoming/control-frame samples disabled, no GUI listener.
- Five-second warm-up followed by GC and one-second settling before measurements.
- 1200-platform-thread guard and 180-second process watchdog.

This validates the idle/ping-pong case for users sharing a TLS identity. It does
not validate heavy messages, high-throughput listeners, reconnect storms, WAN
latency, long-running stability, or thousands of distinct client certificates.
Every distinct TLS identity still needs its own JDK HttpClient/selector; virtual
threads do not remove those selector threads.

## Implementation

WebSocketTransportPool holds reference-counted clients. Plain sessions share a
client. Secure clients share only when the SSL Manager, key store, trust store and
selected alias are equal. Aliases are resolved on the virtual user's thread before
asynchronous work; TLS callbacks use the captured identity. Cookies, headers and
session registries remain user-owned. Closing one session releases one lease;
closing the last session shuts down the client and its executor.

The load driver uses virtual threads directly, not the full JMeterThread engine.
Engine cleanup is covered separately by integration tests.

Tests cover simultaneous Alice/Bob mutual-TLS connections, sharing between Alice
sessions, continued operation after another session closes, final client shutdown,
failed connection cleanup and ordinary user-thread teardown.

## Reproduce the current implementation

The pre-pooling baseline was uncommitted and was not archived as a source snapshot.
Its raw observations are retained, but these commands cannot reproduce that exact
baseline. Do not interpret this as a reproducible historical A/B release comparison.

From the repository root, with JDK 21 available through JAVA_HOME (the driver
defaults to the JDK 21 location on this benchmark machine):

    mkdir -p /tmp/ws-load
    openssl req -x509 -newkey rsa:2048 -nodes -keyout /tmp/ws-load/key.pem -out /tmp/ws-load/cert.pem -days 2 -subj /CN=localhost
    ./gradlew -I extras/websocket-benchmark/classpath.gradle :src:protocol:http:benchmarkClasspath
    python3 extras/websocket-benchmark/run.py /tmp/ws-load/new-300 300
    python3 extras/websocket-benchmark/run.py /tmp/ws-load/new-2000 2000 60

Use a fresh output directory per run. Port 19443 must be available. The runner uses
the macOS/Linux ps command for process RSS and compiles the Java driver against
the production runtime classpath. Raw observations are in results-2026-10-03/.
The ps rows contain PID, RSS in KiB, and OS-reported CPU percentage; client CPU
percentages in the table instead come from the JVM process CPU clock.

The machine-generated observations under `results-2026-10-03/` are distributed
under the Apache License, Version 2.0, like this benchmark harness. They omit
inline license headers to preserve their raw JSONL/text format.
