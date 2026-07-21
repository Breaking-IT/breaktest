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

# BreakTest 2026.07.21 — HTTP Replay Fidelity and Response Comparison

BreakTest 2026.07.21 makes modern HTTP recordings more faithful during replay
and adds opt-in response comparison metrics to the Results Tree table. It also
updates the browser recorder to 1.1.0 and refines several high-density GUI
controls.

## Results Tree Response Metrics

- Adds **Received bytes** and compact **Encoding** columns to the Results Tree
  table. Encoding identifies `gzip`, `deflate`, `br`, and `zstd` response
  compression, including stacked encodings.
- Adds an optional **Diff %** column that compares recorded and replayed
  response bodies when both are available.
- Keeps response comparison disabled by default behind a **Calculate diff**
  toggle because large bodies can be CPU intensive.
- Calculates multi-line differences by changed lines after normalizing line
  endings, so a one-line change in a large document remains proportionally
  small. Single-line bodies use a character-based comparison.
- Leaves the percentage blank for binary or unavailable response bodies and
  displays tiny non-zero differences as `<0.1%`.

## Modern HTTP Replay Fidelity

- Sends the exact HTTP/2 `content-length` whenever the request body length is
  known, including `content-length: 0` for an empty POST, following RFC 9110.
- Shows HTTP/2 and HTTP/3 pseudo-headers consistently in recorded and replayed
  request details and normalizes modern-protocol header names, including
  `cookie`, to lowercase.
- Identifies HTTP/3 over QUIC explicitly in connection timeout and other
  HTTP/3 error details.
- Recognizes unmarked Chromium memory-cache reuses during HAR import and omits
  the non-network reuse without replacing the original request's complete
  headers with sparse contextual headers.

## Browser Recorder and GUI

- Updates the bundled browser extension to 1.1.0 with browser-local IndexedDB
  staging for large HAR exports and transaction rename, removal, and
  reassignment controls.
- Preserves complete Chrome wire request headers, sent-cookie metadata, and
  HTTP protocol when CDP extra-info events arrive out of order.
- Vertically centers the duration, virtual-user, warning, and AI Log controls
  in the command bar.

## Compatibility

- Existing JMeter-compatible JMX plans continue to load and save normally.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.07.21`.

# BreakTest 2026.07.19 — HTTP/3 over QUIC Beta

BreakTest 2026.07.19 introduces beta HTTP/3 sampling over QUIC on Java 26,
while retaining Java 21 compatibility for the rest of BreakTest. It also makes
non-GUI startup faster, keeps validation independent of workload scheduling,
and improves settings and modern-JDK compatibility.

## HTTP/3 over QUIC Beta

- Adds HTTP/3 as an explicit HTTP Request protocol, powered by the Java 26
  `java.net.http` QUIC implementation.
- Supports HTTP/3-only requests, direct HTTP/3 with TCP fallback, and optional
  browser-like Alt-Svc discovery for samplers using the default protocol.
- Set `httpsampler.http3.prefer_for_default=true` to let compatible default
  samplers start over HTTP/2 or HTTP/1.1 and upgrade to HTTP/3 after the server
  advertises Alt-Svc.
- Records the negotiated HTTP version, destination endpoint, and TLS version in
  sample results so QUIC traffic remains visible during debugging and analysis.
- Reuses clients per thread, supports interruption and lifecycle cleanup, and
  maps QUIC timeouts and connectivity failures to BreakTest's concise network
  error codes.
- Keeps the project build and standard HTTP samplers compatible with Java 21.
  On Java 21–25, selecting HTTP/3 logs one warning and falls back to negotiated
  HTTP/2 or HTTP/1.1.

HTTP/3 support is beta. Explicit HTTP/3 samplers currently reject proxies and
multipart uploads rather than silently ignoring them. Default samplers using
those features remain on HttpClient 5. The JDK QUIC stack also requires its
built-in trust manager, so JMeter client-certificate keystores and lenient
certificate trust do not apply to HTTP/3 requests.

## Faster Startup and Modern Java

- Caches classpath scan results and invalidates them automatically when the
  runtime JAR set changes.
- Creates and reuses a per-Java-version AppCDS archive for non-GUI launches;
  set `BREAKTEST_CDS=off` to disable it.
- Avoids legacy XStream Unsafe and final-field mutation probes by using the
  pure-Java reflection provider.
- Enables native access for packaged launches to avoid FlatLaf native-library
  warnings on recent JDKs.
- Keeps the Gradle daemon on Java 21 for reproducible builds while the packaged
  application remains compatible with Java 21 and newer.

## Validation, Archives, and Settings

- Runs validation as exactly one closed-model thread and one iteration,
  regardless of whether the source Thread Group uses an open or closed model.
- Stops validation when that iteration completes instead of waiting for the
  original open-model schedule.
- Identifies compressed JMX archives before emitting normal-file load events,
  preventing plugin analysis from attempting to parse ZIP data as XML.
- Reworks the Settings dialog into compact rows that show effective defaults
  and adds an Overrides tab for values changed by user properties, system
  properties, or command-line arguments.

## Compatibility

- Existing JMeter-compatible JMX plans continue to load and save normally.
- HTTP/3-specific JMX values can be opened on Java 21–25 and use the documented
  HTTP/2 or HTTP/1.1 fallback.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.07.19`.

# BreakTest 2026.07.17 — HAR Import and HTTP Request Controls

BreakTest 2026.07.17 streamlines transaction-aware HAR imports, clarifies
redirect handling, and keeps generated HTTP samplers compatible with native
JMeter JMX metadata.

## Highlights

- Makes the HAR importer more compact and hides idle-based transaction
  splitting when the recording already contains explicit transactions.
- Shows estimated compressed storage sizes for each request and response
  retention choice before importing a recording.
- Removes redundant importer choices and increases the default randomized
  transaction delay range to 5–25 seconds.
- Moves request options and timeouts to the Advanced tab in a compact layout.
- Replaces two mutually exclusive redirect checkboxes with one clear
  three-state selector: do not follow, follow and retain each response, or
  follow and retain only the final response.
- Preserves the native JMeter redirect properties when loading and saving JMX
  files.
- Writes native HTTP sampler `testclass` metadata during HAR conversion so
  compact JMX output retains sampler identity and transaction attribution.

## HAR Import Workflow

- Dedicated BreakTest HAR recordings with explicit transaction markers no
  longer show the inapplicable idle transaction threshold.
- Storage choices report their estimated compressed size using the current HAR
  data, making the impact of retaining bodies and static resources visible.
- The importer removes the continue-on-error control and the redundant
  all-except-static-resources retention mode.
- Request indexing, dynamic URL detection, transaction delay, and storage
  choices remain available in a smaller dialog without excess empty space.

## HTTP Request Controls

- Follow Redirects and Redirect Automatically are represented by a single
  mutually exclusive selector in both HTTP Request and recorder settings.
- Existing `HTTPSampler.follow_redirects` and `HTTPSampler.auto_redirects`
  values map to the new selector without changing native JMX compatibility.
- Follow redirects while keeping each response retains intermediate redirect
  sub-results; final-response-only mode delegates redirect handling to the HTTP
  client.
- Advanced request options use a horizontal layout to reduce scrolling and
  keep the primary request fields focused.

## Compatibility

- HAR-generated HTTP samplers include both `guiclass` and `testclass`
  metadata, matching native JMeter elements and supporting downstream listener
  transaction matching.
- Existing JMeter-compatible JMX files retain their redirect behavior when
  opened and saved.
- Java 21 or later is required.
- This release uses the direct Git tag `2026.07.17`.

# BreakTest 2026.07.16 — Load Profiles and Network Diagnostics

BreakTest 2026.07.16 adds clearer closed-model load profiles, hardens parallel
controller restarts, and makes common HTTP network failures easier to identify
from results and reports. It also restores reliable installation and relaunch
for updates prepared from the GUI.

## Highlights

- Adds a **Load Profile** selector for closed-model Thread Groups. Standard mode
  retains the familiar thread settings, while Custom mode exposes phase-based
  scheduling without leaving inactive controls in the way.
- Interprets `threadsPhase(targetThreads, durationSeconds)` durations relative
  to the preceding phase, so each value describes the length of that phase.
- Keeps pacing, same-user behaviour, and delayed thread creation available for
  Custom profiles, while phase entries can express an initial delay directly.
- Fixes stale marker handshakes in Parallel, Fork, and parallel ForEach
  controllers after loop restarts, preventing work from being skipped in the
  next iteration.
- Replaces verbose Java exception class names for recognized HTTP failures with
  concise codes such as `Connect timeout`, `Response timeout`, and
  `Connection reset`.
- Adds useful duration and host/IP context to timeout and reset messages while
  omitting synthetic values such as `local IP unavailable`.
- Keeps the Chrome, Edge, and Firefox recorder in the binary and source
  distributions through its pinned standalone repository checkout.
- Fixes the detached updater's standalone classpath so a prepared update can
  install after the GUI exits and then relaunch BreakTest.

## Closed-Model Load Profiles

- Standard and Custom profiles now show only the controls that apply to the
  selected mode, keeping the load graph close to the active settings.
- Custom phases support ramp-up, steady load, ramp-down, and initial delay by
  defining a target thread count and the duration of each successive phase.
- Phase graphs and runtime scheduling use the same cumulative timeline.
- Custom profiles retain pacing configuration alongside the shared same-user
  and delayed-thread-creation options.

## Controller Restart Reliability

- Parallel Controller clears an in-flight marker handshake when a thread loop
  is restarted after an error or flow-control action.
- Fork Controller and parallel ForEach apply the same reset invariant so future
  iterations cannot consume stale marker state.
- Regression coverage exercises restart paths for all three controllers.

## HTTP Network Diagnostics

- Connect, response, reset, refused, unknown-host, and no-route failures use
  short response codes and readable messages instead of implementation class
  names.
- Wrapped HttpClient 5 socket timeouts are classified using the configured
  connect and response timeout values, including TLS-handshake timeout shapes.
- Connection-reset messages include the hostname and resolved endpoint when
  available.
- Result bodies remain compact and omit stack traces for recognized network
  failures.

## Distribution And Compatibility

- Browser recorder sources are maintained in the standalone
  `breaktest-browser-extension` repository and remain included under
  `browser-extension/` in release archives.
- The updater installer is validated in a separate JVM using only its packaged
  runtime, including successful installation and relaunch coverage.
- Java 21 or later is required.
- Existing JMeter-compatible JMX files remain supported where practical.
- This release uses the direct Git tag `2026.07.16`.

# BreakTest 2026.07.13 — Initial Community Release

BreakTest is a modern, JMeter-compatible performance testing tool for building,
debugging, validating, and running test plans against systems operated by your
own organization. This initial Community release brings together a faster
runtime, modern protocols, advanced workload modelling, browser recording,
portable test evidence, richer diagnostics, and AI-assisted scripting in one
desktop and command-line distribution.

## Highlights

- A redesigned desktop experience with modern themes, richer HTTP editing,
  searchable settings, enhanced results, and live performance reporting.
- A leaner runtime with lazy decompression, configurable response retention,
  lightweight cloning, lazy diagnostics, and fewer hot-path allocations.
  Measured scenarios show roughly 50–80% lower memory use and up to 50% lower
  CPU use, depending on the test plan and workload.
- First-class HTTP/2 support powered by Apache HttpClient 5.
- Parallel Controller and parallel ForEach model browser-style concurrency
  instead of processing every embedded request sequentially.
- Fork Controller supports asynchronous polling and background flows while the
  main virtual-user journey continues.
- Unified open and closed workload models provide realistic concurrency,
  flexible scheduling, traffic shaping, and thread-level pacing.
- Local Chrome, Edge, and Firefox browser recorders export transaction-aware
  HAR files for guided import into BreakTest.
- Recorded-versus-replayed request and response comparisons make scripting,
  debugging, and correlation faster.
- Improved tree flagging, search-and-replace, and semantic undo and redo make
  large test plans safer to edit.
- AI-assisted scripting and repair through Codex, Claude Code, and MCP-based
  workflows apply changes transactionally and provide recovery safeguards.
- Portable compressed `.jmx` plans can embed HAR or replay evidence,
  attachments, request bodies, response bodies, and checksums.
- Native UDP Request and UDP Receiver samplers provide configurable codecs,
  timeouts, local binding, and reusable per-user sockets.
- Running schedules can be paused and resumed, and Transaction Controllers
  support built-in think time and pacing.
- Extractors can fail a sampler when an expected correlation value is missing.
- Verified in-application updates support rollback and restart while preserving
  user configuration, plugins, and driver libraries.
- BreakTest-specific materials are released under the BreakTest Community
  Source License 1.0.

## Browser Recording And HAR Import

- Browser recorder extensions for Chrome, Chromium-based Edge, and Firefox are
  maintained in the separate `breaktest-browser-extension` repository and are
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
