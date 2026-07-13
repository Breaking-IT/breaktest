# BreakTest

BreakTest is a modern, JMeter-compatible performance testing tool.

It started as an independent fork of Apache JMeter and continues the familiar
JMX workflow with a leaner runtime, current protocols, better debugging, and a
faster development loop. Existing JMeter test plans remain important, but
BreakTest is not intended to be a frozen copy of JMeter.

[![License: source available](https://img.shields.io/badge/license-source--available-blue.svg)](LICENSE)

## Why BreakTest Exists

Apache JMeter gave the performance testing community a durable model: test
plans, samplers, controllers, assertions, extractors, listeners, and a file
format people can keep in version control.

BreakTest keeps that useful model and moves the runtime forward:

- lower memory and CPU pressure for large HTTP tests
- HTTP/2 and a modern HTTP client stack
- Java 21+ and virtual-thread support
- a more modern GUI with simple light/dark themes
- better GUI feedback while building, debugging, and repairing scripts
- safer loading of old plans and plugin-heavy JMX files
- AI-assisted scripting with Codex, Claude Code, opencode, and MCP workflows
- a source-available Community edition that can be scaled by BreakTest Enterprise when one
  machine is no longer enough

Compared with the upstream baseline this work has already shown about 50%
lower memory usage, with CPU reductions around 20-50% depending on workload,
test shape, and runtime conditions.

## BreakTest And BreakTest Enterprise

BreakTest Community is the source-available desktop and runtime distribution.
Organizations may use it to build, debug, validate, and run performance tests
against systems operated for their own internal purposes, subject to the
Community license and any release-specific Community Limits.

BreakTest Enterprise is the paid shell around scaled execution and analysis. It
runs BreakTest, JMeter, and k6 tests across your own infrastructure with
distributed load generators, realtime results, raw data retention, reports, SLO
validation, team workflows, and AI-assisted analysis.

The source stays available for permitted Community use. Enterprise is for
commercially licensed use and running it seriously at scale.

## What Is New Compared With JMeter 5.6.3

BreakTest is based on the JMeter workflow, but it makes deliberate changes where
that helps day-to-day performance engineering. The initial community-licensed
release is not just a rename: it includes protocol, runtime, GUI, AI scripting,
debugging, and migration work that has landed across the BreakTest PR series.

### Modern HTTP Runtime

- Apache HttpClient 5 is the active HTTP sampler path.
- HTTP/2 is first-class, including negotiated fallback, HTTP/2 preferred
  selection, async execution, connect-time reporting, and file upload fixes.
- HTTP/2 resource usage is lower: reactor threads are limited by default, client
  rebuilds are avoided when the same user continues, and parallel samplers reuse
  HTTP state deterministically.
- HTTP/2 closed-session retry handling covers common HC5 connection-closure
  failures without reusing dead multiplexed sessions.
- NTLM authentication works on the HttpClient 5 path, including variable
  resolution before async challenge callbacks.
- Modern response decoding includes gzip, deflate, Brotli, and Zstandard.
- Timeout errors are reported more clearly, with expected connect/response
  timeouts shown as concise messages instead of noisy stack traces.
- HttpClient 4 runtime jars are still bundled for legacy plugin compatibility,
  while the built-in HTTP Request sampler stays on the modern client path.

### Lower Resource Usage

- Lazy response decompression avoids paying decompression cost unless response
  data is actually inspected.
- Response retention modes can store compressed data, discard successful bodies,
  keep only failing bodies, or retain checksums instead of full payloads.
- Lightweight test-plan cloning reduces per-thread object churn for large plans.
- Per-sample metadata is lazy, so rich diagnostics are only stored when
  listeners such as View Results Tree need them.
- Header merging, temporary property handling, and per-thread scratch buffers
  reduce hot-path allocations.
- Java 21+ and virtual threads are used where they reduce runtime overhead.

### Controllers, Scheduling, And User Flow

- Parallel Controller models browser-like concurrent requests with bounded
  parallel sampler execution and HTTP state safeguards.
- Fork Controller runs a child branch asynchronously while the main virtual-user
  flow continues, sharing the same context and variables.
- Standard Thread Group can switch between closed and open workload models.
- Open model scheduling includes helper syntax, even-arrival scheduling, maximum
  active thread limits, and graph preview support.
- Closed model phases let you combine different ramp-up speeds, hold periods,
  and thread targets, making it possible to shape almost any closed workload
  model from one schedule.
- Thread Group pacing controls apply pacing between iterations.
- Runtime user variables are cleared on new iterations when same-user mode is
  disabled, while initial variables remain available.
- Pause and Resume are available in the GUI and in headless runs through the
  same UDP command port used for shutdown, stop, heap dump, and thread dump
  commands. This makes it possible to pause a schedule during ramp-up and hold
  the current load instead of ramping further. In closed model, active threads
  are kept as-is. In open model, new arrivals are paused and active threads
  finish naturally, so load drops while the schedule is paused.
- Shutdown is quicker because it no longer waits for timers to complete. It
  still waits for in-flight server responses, so active requests can finish
  cleanly.

### GUI Workflow Improvements

- The GUI uses a cleaner FlatLaf-based look with simple light and dark themes,
  giving the desktop a more harmonized modern feel without changing the core
  workflow.
- Undo and redo are enabled for semantic test-plan changes such as add, delete,
  update, move, and search/replace operations.
- Large JMX files load in the background with a loading overlay.
- Optional fast GUI loading can skip expensive normalization with
  `jmeter.gui.load.fast=true`.
- JMX files with missing plugin elements can open with disabled placeholders
  instead of failing the whole load.
- Missing plugin placeholders are visible in the GUI and inert in non-GUI runs.
- Search can flag pre-processors, post-processors, assertions, timers, and
  config elements.
- Search results can be selectively removed with confirmation, so bulk cleanup
  is possible without deleting parent elements accidentally.
- GUI validation skips artificial transaction delay and pacing sleeps.

### AI Scripting And Repair

- BreakTest can run AI-assisted script repair from the GUI or through local
  agent tooling.
- The agent workflow works with tools such as Codex, Claude Code, and opencode
  through BreakTest's MCP server and command-line helpers.
- AI repair can inspect a failing or incomplete test plan, run bounded
  validation, and apply concrete JMX edits instead of only suggesting changes.
- The repair flow can add extractors, parameterize dynamic values, improve
  correlations, add assertions, and professionalize recorded scripts so they are
  easier to maintain.
- GUI support highlights the changes made during repair, making it easier to
  review what was added, changed, or removed.
- Project-local AI knowledge can be stored with the test plan so recurring
  conventions, application details, and scripting assumptions travel with the
  work.

### Scripting, Data, And Correlation

- If and While Controllers support structured condition rows with all/any
  matching, while legacy expression mode remains available for old plans.
- Loop and While Controllers expose clearer index behavior and exported index
  variable display.
- Transaction Controller has clearer measurement modes plus built-in delay and
  pacing options with fixed, random, and Gaussian modes.
- Boundary, CSS/HTML, Regex, XPath, XPath2, JSONPath, and JMESPath extractors
  can fail the sampler when no value is found.
- CSV Data Set can read records in random order while preserving header behavior.
- CSV preview shows the first variable assignments before running the test.
- JSR223/Groovy is the intended scripting direction; legacy BeanShell, BSF,
  JEXL2, Rhino JavaScript, and LogKit paths are removed.

### Visual Debugging And Migration

- View Results Tree shows richer request/response diagnostics, endpoint data,
  HTTP/TLS metadata, cookies, variables, binary/text detection, and
  jump-to-source.
- Rendered response views and raw text response views have separate lifecycle
  handling, so switching renderers shows the expected data.
- BreakTest JMX saves origin metadata used by newer debugging tools.
- HAR-backed recorded exchange views can show recorded request and response data
  next to replayed samples.
- Recorded-vs-replayed diffs help debug HAR migration and HTTP/2 replay issues.
- Transaction child samples preserve source paths so recorded exchange diffs can
  still navigate to the right test element.
- HTTP/2 HAR replay strips unsupported hop-by-hop headers and normalizes
  request/status lines for clearer comparisons.
- Host-only cookie matching is stricter so cookies do not leak to unrelated
  hosts or subdomains.

### Modernization Choices

BreakTest removes or de-emphasizes old paths that make the runtime harder to
maintain. That includes legacy HTTP implementations, old scripting engines,
LogKit, and RMI Remote Server style distributed execution.

For local use, run BreakTest directly. For distributed execution, use BreakTest
Enterprise or another controlled orchestration layer rather than reviving the
old Remote Server model.

## Compatibility

BreakTest intentionally keeps many JMeter names in package names, properties,
file formats, command internals, and Maven coordinates. This preserves
compatibility with existing test plans, plugins, scripts, and integrations.

Some compatibility limits are intentional:

- plugins that depend on removed internals may need migration
- legacy scripting engines are not a long-term direction
- new BreakTest JMX features may not round-trip into old JMeter versions
- Java 21 or later is required

The goal is a better tool for current performance engineering, not perfect
preservation of every old edge case.

## Requirements

- Java 21 or later
- A JDK is recommended when recording HTTPS traffic because `keytool` is useful
  for certificate handling
- Optional protocol libraries, such as JDBC or JMS drivers, should be placed in
  `lib` or `lib/opt` as needed

## Install And Run

Unpack the binary distribution, then start BreakTest from `bin`:

```sh
cd breaktest/bin
./breaktest
```

On Windows:

```bat
breaktest.bat
```

For non-GUI execution:

```sh
./breaktest -n -t test-plan.jmx -l results.jtl
```

### GUI updates

The GUI checks for a new stable GitHub release at most once every 24 hours.
You can also check immediately with **Help > Check for Updates**. When a new
version is available, an update button appears in the status bar. BreakTest
downloads the binary release, verifies both its GitHub SHA-256 digest and its
published SHA-512 checksum, installs it after the GUI exits, and then restarts.

Self-update is available from writable binary installations. Source checkouts
are never modified by the updater. Updates preserve `bin/user.properties`,
`bin/system.properties`, non-core plugins in `lib/ext`, and user-installed JARs
such as JDBC/JMS drivers in `lib` or `lib/opt`. Set
`breaktest.update.enabled=false` in `user.properties` to disable automatic
checks, or change `breaktest.update.interval_hours` to adjust their frequency.

Some internal property names, package names, and artifact names still use
`jmeter` for compatibility.

## Build From Source

BreakTest uses Gradle and JVM toolchains.

Build and test:

```sh
./gradlew build
```

Create local `bin` and `lib` contents for development:

```sh
./gradlew createDist
```

Run the GUI through Gradle:

```sh
./gradlew runGui
```

Create release archives:

```sh
./gradlew clean :src:dist:distTar :src:dist:distZip -Prelease
```

Release versions are configured in `gradle.properties`:

```properties
breaktest.version=2026.07.13
```

Do not include `-SNAPSHOT` in that property. Gradle appends the snapshot suffix
automatically unless the build is run with `-Prelease` or `-Prc=<number>`.

## Documentation

Generated distribution documentation is included under `docs/` in release
archives and starts at `docs/index.html`.

Developer notes:

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [gradle.md](gradle.md)
- [SECURITY.md](SECURITY.md)
- [THREAT_MODEL.md](THREAT_MODEL.md)

## Security Model

BreakTest inherits JMeter's trusted-test-plan model. Treat JMX files as
executable input: they can define scripts, load classes, call functions, read
files, make network requests, and interact with systems reachable from the test
runner.

Only open or run test plans you trust, or isolate them first. Report suspected
BreakTest vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
If an issue also affects upstream Apache JMeter, follow the Apache Software
Foundation security process as well.

## Relationship To Apache JMeter

BreakTest was forked from Apache JMeter:

- https://github.com/apache/jmeter
- https://gitbox.apache.org/repos/asf/jmeter.git

BreakTest is independent from The Apache Software Foundation. It is not
affiliated with, endorsed by, or sponsored by The Apache Software Foundation.

Apache, Apache JMeter, JMeter, the Apache feather, and the Apache JMeter logo
are trademarks or registered trademarks of The Apache Software Foundation.
BreakTest uses those marks only to describe the origin of the fork and
compatibility with Apache JMeter.

## License

BreakTest-specific materials are licensed under the
[BreakTest Community Source License 1.0](LICENSE). This is a source-available
license, not an Open Source license.

Community use is limited to testing systems operated by your own organization.
Performance-testing service providers, load-testing platform providers,
third-party testing, hosted or managed offerings, redistribution, reuse in
other products or JMeter extensions, and circumvention of Community Limits
require prior written permission or a commercial license from Breaking IT.

Commercial licensing requests may be sent to `info@breakingit.nl`.

BreakTest is a derivative work of Apache JMeter. Copyright and attribution
notices for Apache JMeter and other third-party components are retained in the
source tree and distribution notices. Those third-party materials, including
Apache JMeter code, remain governed by their own licenses, including the
Apache License, Version 2.0.

For legal and licensing information, see:

- [LICENSE](LICENSE)
- [NOTICE](NOTICE)

## Cryptographic Software Notice

This distribution may include software that has been designed for use with
cryptographic software. The country in which you reside may have restrictions
on the import, possession, use, or re-export of encryption software. Before
using encryption software, check your country's laws, regulations, and policies.

BreakTest interfaces with the Java Secure Socket Extension (JSSE) API to
provide HTTPS support.

BreakTest interfaces, via Apache HttpClient, with the Java Cryptography
Extension (JCE) API to provide authentication features such as NTLM.

BreakTest does not include an implementation of JSSE or JCE.

## Thanks

BreakTest exists because Apache JMeter established a useful, open model for
performance testing. Thank you to the Apache JMeter community and to everyone
testing, reporting, fixing, and modernizing BreakTest from here.
