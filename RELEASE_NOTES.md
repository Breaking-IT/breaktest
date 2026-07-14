<!--
Copyright 2024-2026 BreakTest contributors

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy of the
License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
-->

# BreakTest 2026.07.13 — Initial Community Release

BreakTest is a modern, JMeter-compatible performance testing tool for building,
debugging, validating, and running test plans against systems operated by your
own organization. This initial Community release brings together a faster
runtime, modern protocols, advanced workload modelling, browser recording,
portable test evidence, richer diagnostics, and AI-assisted scripting in one
desktop and command-line distribution.

## Highlights

- Modern HTTP runtime built on Apache HttpClient 5, with first-class HTTP/2,
  negotiated fallback, NTLM authentication, and Brotli and Zstandard decoding.
- Local Chrome, Edge, and Firefox browser recorders that export transaction-aware
  HAR files for guided import into BreakTest.
- Portable compressed `.jmx` plans that can embed HAR or replay evidence,
  attachments, request bodies, response bodies, and checksums.
- Parallel Controller, parallel ForEach, Fork Controller, and unified open and
  closed workload models for realistic concurrency and traffic shaping.
- Native UDP Request and UDP Receiver samplers with configurable codecs,
  timeouts, local binding, and reusable per-user sockets.
- AI-assisted scripting and repair through Codex, Claude Code, opencode, and
  MCP-based workflows, with transactional application and recovery safeguards.
- A redesigned desktop experience with modern themes, richer HTTP editing,
  undo and redo, searchable settings, enhanced results, and live performance
  reporting.
- Lower runtime overhead through lazy decompression, configurable response
  retention, lightweight cloning, lazy diagnostics, and reduced hot-path
  allocation.
- Verified in-application updates with rollback, restart, and preservation of
  user configuration, plugins, and driver libraries.
- BreakTest-specific materials released under the BreakTest Community Source
  License 1.0.

## Browser Recording And HAR Import

- Browser recorder extensions for Chrome, Chromium-based Edge, and Firefox are
  included under `browser-extension/` in both binary and source distributions.
- Recording can start in a prepared blank tab or private browser context so the
  first navigation and all dependent resources are captured.
- Authors can name transactions while recording; those names become Transaction
  Controllers when the HAR file is imported.
- Optional cache disabling supports repeatable cold-cache recordings, while the
  recorder reports requests whose response bodies are unavailable from the
  browser debugging API.
- The guided **File > Import HAR...** workflow supports hostname selection,
  request grouping, error-response handling, request indexes, dynamic URL
  detection, shared and request-local headers, and configurable transaction
  delays.
- Generated plans can include Transaction Controllers, Parallel Controllers,
  HTTP Request Defaults, Cookie Managers, assertions, and preserved BreakTest
  recording metadata.

## Portable Test Plans And Recorded Evidence

- Newly saved `.jmx` files use a compressed archive format while retaining the
  familiar filename extension; legacy plain-XML JMX files continue to load.
- Test plans can carry referenced attachments and filtered HAR or replay
  evidence without extracting content to the filesystem.
- Recording retention can keep all traffic, omit static bodies, omit static
  resources, or exclude recording evidence entirely.
- Request and response bodies are stored as deduplicated archive entries with
  validated references and checksums.
- **Store Replay** captures the latest replayed requests and responses as the
  plan's current evidence bundle.
- Recorded and replayed data remains available after save and reopen for HTTP
  sampler inspection, result analysis, and recorded-versus-replayed comparison.
- Archive paths, linked content, required dependencies, and checksums are
  validated before use, and validation never rewrites the open plan.

## Modern HTTP And Native UDP

- Apache HttpClient 5 powers the built-in HTTP/1.1 and asynchronous HTTP/2
  sampler paths.
- HTTP/2 supports explicit and preferred selection, negotiated fallback,
  connect-time reporting, upload content lengths, safer replay, and conservative
  retry of common closed-session failures.
- HTTP clients and reactor threads are reused where safe, reducing connection
  churn and resource consumption across same-user iterations.
- NTLM authentication resolves test variables before asynchronous challenge
  callbacks.
- Responses support gzip, deflate, Brotli, and Zstandard decoding, with lazy
  decompression when response content is not inspected.
- HttpClient 4 runtime libraries remain bundled for compatibility with legacy
  third-party plugins while built-in HTTP Requests use HttpClient 5.
- Native UDP Request supports request/response and fire-and-forget datagrams,
  configurable timeouts, local address and port binding, unreachable-destination
  handling, and named socket reuse.
- UDP Receiver can consume later datagrams from a named socket and treat receive
  timeouts as successful no-content samples or failures.
- UDP payloads support UTF-8, hexadecimal, and single-byte text, with a native
  `UDPTrafficCodec` extension point for custom encoding.
- Datagram size, truncation, buffer reuse, and per-virtual-user socket isolation
  are handled consistently across sequential, parallel, and forked execution.

## Workload Modelling, Scheduling, And Concurrency

- Parallel Controller executes bounded concurrent sampler branches with HTTP
  state safeguards.
- ForEach Controller can run iterations in parallel with a configurable maximum;
  branches are created lazily so very large collections do not allocate every
  branch up front.
- Fork Controller starts asynchronous child flows that share virtual-user
  context and complete before thread teardown.
- Groovy and JSR223 scripts can call `stopForks()` or `stopForksNow()` to end
  active fork branches without stopping the main virtual user.
- Parallel and fork workers isolate mutable sampler, loop, status, transaction,
  and compiler state while preserving intentional shared-variable behaviour.
- Nested parallel sections and parent-mode Transaction Controllers retain
  deterministic source mappings, completion status, and listener behaviour.
- The standard Thread Group supports closed and open workload models from one
  schedule, including phases, even-arrival scheduling, maximum active threads,
  graph previews, and safe schedule parsing.
- Built-in pacing and Transaction Controller delay modes support fixed, random,
  Gaussian, recorded, or disabled delays.
- Pause and Resume work in the GUI and through the command port; closed-model
  users remain active while open-model arrivals pause cleanly.
- Shutdown wakes timers and waits only for in-flight responses, reducing stop
  time without abandoning active server calls.

## AI-Assisted Scripting And Repair

- BreakTest integrates with Codex, Claude Code, opencode, and MCP-compatible
  agents for guided test-plan inspection and repair.
- GUI-backed tools can find, clone, move, edit, and safely delete live plan
  nodes while preserving complete controller and sampler subtrees.
- Repair planning detects conflicting dynamic literals and encoded variants,
  and proposes native Regex Extractors only when recorded evidence supports a
  safe correlation pattern.
- Quoted values, duplicate replacements, stale node identifiers, and orphaned
  extractor scenarios are handled explicitly.
- Repair batches are transactional: failed changes roll back through GUI undo,
  with backup restoration available if tree integrity cannot be preserved.
- Protected-container checks, exact match counts, stable path fallback, null-safe
  refresh, and full bridge diagnostics reduce the risk of editing the wrong
  node.
- Incomplete repair runs report a blocked status instead of appearing
  successful.

## Desktop Authoring Experience

- A refreshed FlatLaf-based interface provides consistent light and dark
  themes, updated application and toolbar icons, cleaner trees, and improved
  menu organisation.
- The HTTP Request editor uses a compact URL bar and dedicated Params, Headers,
  Body, Files, and Advanced tabs with visible value counts.
- Sampler-specific HTTP headers live directly on the HTTP Request. Legacy child
  Header Managers migrate automatically, while higher-level Header Managers
  continue to apply by scope.
- Semantic undo and redo covers add, delete, update, move, and search/replace
  operations.
- Large JMX files load in the background with progress feedback; optional fast
  loading can skip expensive normalisation.
- Missing plugin elements load as disabled placeholders instead of preventing
  the rest of the plan from opening.
- Search Tree recognises processors, assertions, timers, and configuration
  elements, and selective removal includes confirmation safeguards.
- Tree nodes show child counts, Transaction Controllers show compact delay
  summaries, and HTTP request tabs reveal hidden data counts.
- View Results can detach and dock, validation can target selected thread
  groups, and the log and AI scripting panel has a clearer resize boundary.
- A searchable Settings screen exposes the full property catalogue with typed
  editors, defaults, modified indicators, reset controls, and safe persistence
  to local override files.

## Results, Diagnostics, And Performance Reporting

- View Results offers Tree and Table layouts with timestamp, duration, latency,
  connect time, request and response size, thread group, thread name, label,
  URL, and status information.
- Columns are selectable and sortable, filters can target thread groups,
  threads, and sampler labels, and live refresh preserves automatic scrolling.
- Full-value tooltips and direct HTTP URL display make truncated or grouped
  results easier to inspect.
- Request and response diagnostics include endpoint, HTTP, TLS, cookie,
  variable, binary/text, and source test-element information.
- HAR-backed tabs compare recorded and replayed traffic, normalize HTTP/2
  differences, and preserve source navigation through folded transaction
  results.
- The Performance Report listener provides configurable throughput, bandwidth,
  error, connect-time, median, P75, P90, P95, and P99 metrics.
- Error response times can be excluded from response-time calculations, and
  percentile columns clearly communicate their additional retention cost.

## Runtime Efficiency And Reliability

- Response retention can keep compressed bytes, discard successful bodies,
  retain only failures, or store checksums instead of full content.
- Lightweight test-plan cloning and shared safe property state reduce per-thread
  object churn.
- Rich sample metadata is listener-driven and allocated only when diagnostics
  need it.
- Header merging, temporary property handling, response buffers, parent-path
  lookup, and repeated controller traversal are optimized for hot execution
  paths.
- Fork tasks, executors, timers, samplers, and controller mappings are cleaned
  up promptly and by identity.
- Parallel ForEach retains state proportional to configured parallelism rather
  than total collection size.
- Update scheduling, archive loading, concurrency, rollback, HAR conversion,
  GUI lifecycle, and high-capacity controller behaviour have dedicated
  regression and benchmark coverage.

## Scripting, Data, And Correlation

- If and While Controllers support structured condition rows with all/any
  matching while retaining legacy expressions.
- Loop and While Controllers expose configurable index behaviour and exported
  index variables.
- Transaction Controllers provide measurement modes, delay, and pacing controls.
- Boundary, CSS/HTML, Regex, XPath, XPath2, JSONPath, and JMESPath extractors can
  fail the sample when no match is found.
- CSV Data Set supports random record order and previews initial variable
  assignments.
- The scripting direction is focused on JSR223 and Groovy; legacy BeanShell,
  BSF/JEXL2, Rhino JavaScript, and LogKit paths have been removed.

## Distribution And Updates

- Distributions use a stable `breaktest/` top-level directory and ship the
  browser recorder extensions alongside the runtime.
- `breaktest.jar`, `BREAKTEST_HOME`, `BREAKTEST_OPTS`, and
  `BREAKTEST_LANGUAGE` are the preferred launcher and environment names, with
  transitional compatibility where needed.
- BreakTest-owned Maven artifacts use the `nl.breakingit.breaktest` group.
- GUI update checks run at startup and periodically, and can also be triggered
  through **Help > Check for Updates**.
- Downloads are accepted only when the expected binary ZIP and SHA-512 asset
  are present and the GitHub SHA-256 digest also matches.
- Installation runs after the GUI exits, rolls back failed replacement, restarts
  after success, and preserves user and system properties, third-party plugins,
  and installed JDBC or JMS drivers.
- Archive extraction and installation paths are confined against traversal and
  symlink escapes; source checkouts are never modified by the updater.

## Compatibility Notes

- Java 21 or later is required.
- Existing JMeter-compatible JMX files remain supported where practical.
- Newly written compressed JMX archives require a BreakTest version that
  understands the archive format; embedded recordings are a BreakTest
  extension.
- Many package names, property names, command internals, and compatibility
  coordinates intentionally retain `jmeter` or `org.apache.jmeter` names.
- Plugins that depend on removed internals may require migration.
- Parallel ForEach is opt-in; existing ForEach Controllers remain sequential
  unless explicitly enabled.
- RMI Remote Server-style distributed execution is not included. Use BreakTest
  Enterprise or another controlled orchestration layer for distributed tests.

## Security Model

BreakTest follows the trusted-test-plan model. Treat JMX files as executable
input: a test plan can run scripts, load classes, read files, make network
requests, and interact with systems reachable from the runner.

Only open or execute plans you trust, or isolate them first. See
[SECURITY.md](./SECURITY.md) and [THREAT_MODEL.md](./THREAT_MODEL.md) for the
project security model and vulnerability reporting process.

## License And Attribution

BreakTest-specific materials are licensed under the BreakTest Community Source
License 1.0. Community use is limited to testing systems operated by your own
organization. Performance-testing service providers, testing-platform
providers, hosted or managed offerings, redistribution, reuse in other
products, and circumvention of Community Limits require prior written
permission or a commercial license from Breaking IT.

Commercial licensing requests may be sent to `info@breakingit.nl`.

BreakTest is a derivative work of Apache JMeter. Applicable copyright, license,
and attribution notices for Apache JMeter and other third-party materials are
retained in the source tree and distribution. BreakTest is independent from
The Apache Software Foundation and is not affiliated with, endorsed by, or
sponsored by The Apache Software Foundation.
