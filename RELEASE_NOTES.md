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

# BreakTest Release Notes

## BreakTest 2026.07.07

BreakTest 2026.07.07 is a focused desktop and reporting update built from the
changes since BreakTest 2026.07.03. It keeps the same Java 21+ baseline and
JMeter-compatible test-plan model while improving the day-to-day GUI workflow,
local settings management, validation, and live performance reporting.

### Highlights

- Added a configurable Performance Report listener with percentile, throughput,
  bandwidth, error, and connect-time columns.
- Added a Settings dialog backed by the full JMeter properties catalog, with
  search, default/modified indicators, reset controls, and safe persistence to
  local override property files.
- Modernized the BreakTest desktop UI with a refreshed application icon,
  modern toolbar icons, cleaner tree rendering, improved menu organization, and
  better light/dark FlatLaf styling.
- Improved View Results Tree and validation ergonomics with detach/dock support,
  validation targeting, auto-detach controls, collapsible search/file panels,
  and comment visibility controls.
- Moved sampler-scoped HTTP headers into the HTTP Request sampler with
  automatic migration from child HTTP Header Manager elements.
- Fixed saved test plans with the new Performance Report listener so they
  reload correctly.
- Added child-count badges in the test-plan tree and compact Transaction
  Controller delay summaries beside transaction names.
- Added counts to HTTP Request Params, Headers, Body, and Files tabs so hidden
  request data is visible at a glance.

### Performance Reporting

- Added a dedicated Performance Report visualizer for configurable live
  aggregate metrics.
- Added optional percentile columns including median, P75, P90, P95, and P99.
- Added controls to ignore error response times in response-time metrics.
- Added resource-use guidance for percentile columns, since they retain richer
  response-time distribution data while enabled.
- Added tests for column configuration, percentile tracking, error handling, and
  report reset behavior.

### Settings Management

- Added a Settings page available from the GUI menu and toolbar.
- Added typed setting definitions, editors, grouping, search, default-value
  display, modified badges, and reset/save actions.
- Added a full settings catalog resource for JMeter/BreakTest properties.
- Persisted changes to local user/system override properties rather than
  modifying the bundled `jmeter.properties` file.
- Added tests for catalog loading, property storage, and model behavior.

### Desktop UI And Validation

- Refreshed the main frame, toolbar, menu factory, tree renderer, logger panel,
  target-rate chart, and scripting log presentation.
- Added modern SVG toolbar assets and updated icon mappings for common actions.
- Improved validation controls so selected thread groups can be targeted more
  directly.
- Added View Results Tree detach/dock workflows and clearer validation result
  viewing.
- Added native HTTP Request headers so sampler-specific headers live on the
  request itself, while higher-level Header Managers still apply by scope.
- Updated the Transaction Controller icon so it reads as a transaction span
  rather than a small generic controller/action glyph.
- Added direct-child count badges to tree nodes and compact delay summaries for
  Transaction Controllers with built-in delay.
- Added value counts to HTTP Request data tabs for Params, Headers, Body, and
  Files.
- Added tests around tree-listener behavior.

### Compatibility Notes

- Java 21 or later is still required.
- Existing JMeter-compatible JMX files remain supported where practical.
- Percentile columns in the Performance Report intentionally keep additional
  response-time history only while those columns are selected.
- Most Settings changes are written as local overrides and take effect after
  restarting BreakTest.

### Security Notes

BreakTest continues to use the trusted-test-plan model. Treat JMX files as
executable input and run untrusted plans only in an isolated environment. See
[SECURITY.md](./SECURITY.md) and [THREAT_MODEL.md](./THREAT_MODEL.md).

## BreakTest 2026.07.03

BreakTest 2026.07.03 is the first public BreakTest release: an independent,
Apache-2.0, JMeter-compatible continuation focused on a leaner runtime, modern
HTTP, better debugging, and a smoother path from local scripts to scaled
execution.

BreakTest keeps the useful JMeter test-plan model and JMX workflow, while
moving the engine toward Java 21+, HTTP/2, lower resource usage, visual
diagnostics, AI-assisted repair workflows, and BreakTest Enterprise scale.

## Highlights

- Modern HTTP path based on Apache HttpClient 5 with first-class HTTP/2 support.
- Lower memory and CPU pressure through lazy decompression, retention modes,
  lightweight cloning, lazy diagnostics, and allocation reductions.
- Java 21+ baseline with virtual-thread support.
- Parallel Controller and Fork Controller for realistic concurrency and
  independent branches of work.
- Unified Thread Group scheduling with closed/open workload models, phases,
  open-model helpers, pacing, and pause/resume support.
- Undo/redo support for common GUI test-plan edits.
- Background JMX loading and safe missing-plugin placeholders.
- Richer View Results Tree diagnostics, source navigation, and HAR-backed
  recorded-vs-replayed request/response diff views.
- Easier If/While conditions, transaction timing controls, random CSV ordering,
  CSV preview, and extractor fail-on-no-match options.
- Apache License 2.0, with Apache JMeter attribution retained.

## Modern HTTP Runtime

- Added Apache HttpClient 5 sampler support for HTTP/1.1.
- Added an async HttpClient 5 HTTP/2 sampler path with HTTP/2 and HTTP/2
  preferred protocol selection.
- Added negotiated fallback, connect-time reporting, HTTP/2 upload content
  length handling, and safer HTTP/2 replay behavior.
- Reduced HTTP/2 resource usage by limiting reactor threads by default and
  avoiding unnecessary async client/reactor rebuilds between same-user
  iterations.
- Added conservative HTTP/2 closed-session retry handling for common HC5
  connection-closure failures.
- Fixed NTLM authentication on the HttpClient 5 path, including variable
  resolution before async auth callbacks.
- Kept HttpClient 4 runtime jars available for legacy plugin compatibility while
  keeping the built-in HTTP Request sampler on HttpClient 5.
- Added Brotli and Zstandard response decoding alongside gzip and deflate.

## Resource Usage Improvements

- Lazy response decompression stores compressed bytes and decompresses only when
  response data is accessed.
- Response-processing modes can store compressed data, discard successful
  bodies, keep only failing bodies, or retain MD5 checksums.
- Lightweight test-plan cloning shares safe property state where possible and
  reduces per-thread object churn.
- Header merging, temporary property handling, and response read buffers were
  optimized for hot-path sampler execution.
- Rich SampleResult metadata is now lazy and listener-driven, avoiding
  unnecessary per-sample storage when diagnostics are not needed.
- A throughput regression in temporary property recovery was fixed while keeping
  the correctness improvements for identity semantics.

## Controllers, Scheduling, And Flow Control

- Added native Parallel Controller execution with bounded parallel sampler
  scheduling and HTTP state safeguards.
- Added Fork Controller for asynchronous child branches that share virtual-user
  context and complete before thread teardown.
- Added unified Thread Group scheduling controls with closed/open model
  selection.
- Added open-model schedule helpers, even-arrival scheduling, max active thread
  limits, safe schedule parsing, and graph preview support.
- Added closed-model phases so a schedule can combine different ramp-up speeds,
  hold periods, and thread targets. This makes it possible to shape almost any
  closed workload model from one Thread Group schedule.
- Added Thread Group pacing controls.
- Cleared runtime user variables on new iterations when same-user mode is
  disabled, while preserving initial variables.
- Added Pause and Resume in the GUI and on the existing UDP command port, with
  `bin/pause.*` and `bin/resume.*` wrappers. Pausing is useful during ramp-up
  when you want to stop increasing load and hold the current point. In closed
  model, active threads stay active. In open model, new arrivals are paused and
  active threads finish naturally, so load drops while paused.
- Improved shutdown so it is quicker: shutdown no longer waits for timers to
  complete and only waits for in-flight server responses to finish cleanly.

## GUI And Test-Plan Editing

- HTTP headers are now part of the HTTP Request sampler itself: a Headers tab
  on the sampler replaces the child HTTP Header Manager element. Header
  Managers at higher levels (Test Plan, Thread Group, controllers) still apply
  to all samplers in scope, with sampler-level headers winning on conflicts.
  Legacy JMX files with a Header Manager under an HTTP Request are migrated
  automatically on load (GUI and non-GUI), and saving writes the new format.
  Recording and cURL import now produce sampler headers directly, search and
  search/replace cover them, and copying a sampler carries its headers along.
- Modernized the HTTP Request editor: a URL bar row (method, protocol, server,
  path), a Params / Headers / Body / Files / Advanced tab strip, and a side
  column with Request Options, Web Server, and Timeouts boxes.
- Added undo and redo for semantic tree edits including add, delete, update,
  move, and search/replace operations.
- Added background JMX loading with a loading overlay.
- Added opt-in fast GUI loading through `jmeter.gui.load.fast=true`.
- JMX files with missing plugin elements now load with disabled placeholders
  rather than failing the whole file.
- Added placeholder GUI panels and clear notifications for missing plugin
  elements.
- Improved Search Tree flagging for pre-processors, post-processors,
  assertions, timers, and config elements.
- Added selective "Remove Matching" for search results with confirmation so bulk
  cleanup can avoid deleting parent nodes accidentally.
- Improved GUI layout and editor resizing around controller panels and tree
  editors.

## Scripting, Data, And Correlation

- Added structured If and While Controller condition rows with all/any matching,
  while preserving legacy condition expressions.
- Added loop/while index behavior options and exported index variable display.
- Added Transaction Controller measurement modes plus built-in delay and pacing
  controls.
- Added extractor fail-on-no-match options for Boundary, CSS/HTML, Regex,
  XPath, XPath2, JSONPath, and JMESPath extractors.
- Added random-order reading for CSV Data Set records.
- Added CSV preview of the first sample variable assignments.
- Focused scripting on JSR223/Groovy by removing legacy BeanShell, BSF/JEXL2,
  Rhino JavaScript, and LogKit paths.

## Visual Debugging And HAR Migration

- View Results Tree now shows richer request/response diagnostics, endpoint
  details, HTTP/TLS metadata, cookies, variables, binary/text detection, and
  jump-to-source.
- Rendered response views and raw text response views now keep the expected
  response display lifecycle.
- BreakTest JMX metadata preserves origin and source-path information for
  debugging tools.
- HAR-backed recorded request/response tabs appear in View Results Tree and HTTP
  sampler editors when matching BreakTest HAR metadata is available.
- Recorded-vs-replayed diffs make HAR migration and HTTP/2 replay debugging
  easier.
- Transaction child samples preserve source test-element paths so folded
  transaction results can still navigate to the correct source element.
- HTTP/2 replay removes unsupported hop-by-hop headers and normalizes
  request/status lines for cleaner diffs.
- Host-only cookie matching is stricter so cookies do not leak to unrelated
  hosts or subdomains.

## Compatibility Notes

- Existing JMeter JMX files remain supported where practical.
- Many package names, property names, command internals, and Maven coordinates
  intentionally still use `jmeter` or `org.apache.jmeter` for compatibility.
- BreakTest-specific JMX metadata may not round-trip into older JMeter versions.
- Plugins that depend on removed internals may need migration.
- Legacy HttpClient 4 jars are bundled for plugin compatibility, but the built-in
  HTTP Request sampler runs on HttpClient 5.
- RMI Remote Server style distributed execution is removed. Use BreakTest
  Enterprise or another controlled orchestration layer for distributed tests.
- Java 21 or later is required.

## Security Notes

BreakTest inherits Apache JMeter's trusted-test-plan security model. Treat JMX
files as executable input: they can run scripts, load classes, read files, make
network requests, and interact with systems reachable from the runner.

Only open or run test plans you trust, or isolate them first. See
[SECURITY.md](./SECURITY.md) and [THREAT_MODEL.md](./THREAT_MODEL.md) for the
project security model and vulnerability reporting process.

## Legal And Attribution

BreakTest is licensed under the Apache License, Version 2.0. BreakTest is a
derivative work of Apache JMeter, and Apache JMeter copyright and attribution
notices are retained in the source tree and distribution notices.

BreakTest is independent from The Apache Software Foundation. It is not
affiliated with, endorsed by, or sponsored by The Apache Software Foundation.
