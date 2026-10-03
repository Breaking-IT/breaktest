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

# Claude Opus 5.5 review disposition

Requested using claude -p --model claude-opus-5-5 --effort high with read-only
Read/Glob/Grep tools. Full response: claude-opus-5.5-review.md.
The review was static; the test and benchmark observations below are our checks.

## Fixed

- Stale Connect/Close now checks session-object identity before acquiring or
  releasing a lease. Delayed cleanup cannot shut down a replacement session.
- Send and Wait uses one resolved session for both waiting and sending.
- Registry creation locks on the stable JMeterThread owner, not per-worker variable
  wrappers. A concurrent test uses actual ParallelWorkerVariables.
- Sends queue per session. Cancelling a caller's wait does not break send ordering.
  Malformed UTF-16 is rejected before the connection is marked for failure cleanup.
- Notifications leave the session monitor before invoking external listeners.
  Per-user notification serialization uses a ReentrantLock, with an attached
  callback context carrying the user's variables, thread/group and engine.
  Per-thread listeners are cloned for callbacks; NoThreadClone listeners retain
  their documented shared/thread-safe behavior. A real Groovy JSR223 Listener test
  reads and writes the originating user's variables.
- Incoming notifications are suppressed once the owning engine thread stops.
- Inherited transport-controlled HTTP headers are filtered; explicit Connect
  headers still fail clearly when they conflict with the WebSocket protocol.
- TLS client creation uses per-key futures, outside a global lock. Last-lease
  shutdown also runs outside locks, and the executor remains available until
  HttpClient termination.
- HTTP TLS protocol/cipher configuration is applied and participates in the pool
  key. Tests verify distinct configurations use separate clients.
- Binary preview has a separate 64 KiB default byte cap. Best-effort Text preview
  bounds the decoded byte range when the configured display limit is exceeded.
- Removed the unused asynchronous SSL-context helper.
- Benchmark report now explicitly limits CPU/RSS conclusions to the observed runs,
  labels latency maxima as including teardown, distinguishes the raw-thread driver
  from full-engine tests, and discloses the unavailable baseline source snapshot.
- Documented TLS-session resumption across same-identity users, explicit alias
  selection for mixed HTTP/WebSocket mutual TLS, and compatibility of encoded
  control-character JMX properties with older readers.

## Not accepted as a current defect

- The compile-failure finding combined snapshots from before and during the
  session-identity fix. The finalized method signature and all callers agree;
  compilation and regression tests are the authority.
- A dead session name still requires Close before reuse. This is an existing,
  documented policy; automatically replacing it would change lifecycle semantics.
- Shared same-identity TLS caches are deliberate for this implementation's scaling
  goal. They do not share application cookies or sockets. Their difference from
  HTTP's default per-user TLS contexts is now explicit.
- No idle grace cache was added: prompt last-lease release is intentional.
  Connect/Close churn and thousands of distinct certificate identities remain
  separate workloads requiring measurements.
- Handshake Set-Cookie import remains an explicitly documented limitation. The
  public JDK WebSocket API does not expose successful handshake headers.

## Validation

Focused WebSocket tests cover the new races, listener context, send ordering and TLS
configuration. The final run also exercises the full HTTP suite, visualizer tests,
archive persistence tests, Autostyle and Checkstyle.

A post-review smoke benchmark again opened all 2000 secure WebSockets with 39
platform threads. Mean client CPU was 11.53% of one core over 20 seconds.
The peer recorded 26,584 pings and 26,574 pongs, with ten outstanding at shutdown
and zero active connections after cleanup. Raw measurements are in results-2026-10-03/review-after-2000/.
This remains a short native-ping/pong smoke test, not a message-throughput,
listener-load or long-duration benchmark.
