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

# BreakTest 2026.09.25 — Fork Controls, Live Transactions, and Schedule Tables

This release simplifies Transaction Controller by removing Generate parent sample and combining immediate metrics, low memory use, and live transaction progress in one reporting model. It also makes background forks configurable, adds schedule tables with random arrivals, and introduces script-free sampler-result filtering and While Controller iteration limits.

## Simpler Transactions, Immediate Metrics, and Live Progress

- **Generate parent sample is removed.** There is now one Transaction Controller reporting model, combining the benefits of both previous modes without having to choose between immediate results and a transaction hierarchy.
- **Lower memory use:** parent mode used to retain all child sampler results and response data until the transaction completed, potentially keeping them until the following sampler ran. Transactions now retain only running totals and lightweight links, so they no longer hold every response body in memory for the duration of a long transaction.
- **Immediate metrics:** each sampler result reaches listeners as soon as it completes, with a link to its transaction. Metrics and results no longer have to wait for all samplers in the transaction to finish.
- **Live progress:** interested listeners receive transaction-start notifications and the completed transaction result, allowing them to visualize a transaction while it is still running. In GUI mode, View Results Tree now shows both running transactions and running samplers with elapsed timers, replacing them with their results as they finish.
- Enable optional transaction and parent-transaction IDs in CSV/XML JTL output with `jmeter.save.saveservice.transaction_ids=true`. Start events are opt-in, avoiding live-display overhead for listeners that do not need them. Transaction starts are notified at the first sampler, or at completion for naturally empty transactions.
- Transactions cut short when a fork error ends the iteration report failure, including nested and parallel-flow transactions. Cancellation during an initial delay produces no empty transaction summary or orphaned start event.

Sources: [#175](https://github.com/Breaking-IT/breaktest/pull/175), [#179](https://github.com/Breaking-IT/breaktest/pull/179).

## Fork Lifecycle and Error Handling

- Choose what happens at each main-flow iteration end: stop immediately without cancellation errors, finish current samplers gracefully, wait for the entire fork, or keep it running with the same user. Reaching an already-running fork can skip it, restart it, or wait before starting again. This avoids keep-alive forks blocking later iterations or accumulating concurrent executions.
- Configure fork errors independently of the thread group: continue, stop the fork and its descendants while the main flow continues, or end the current main iteration gracefully or immediately. Later configured iterations still run, and the original failed sample is retained.
- Use compact, bordered radio groups. Keep running is hidden when the enclosing thread group disables Same user, but remains available in reusable fragments. Temporarily disabling Same user or opening the editor does not silently overwrite a saved Keep running choice. A separate final-stop policy applies before changing users and at the final iteration or duration limit.
- Clean up cancelled workers before restarting forks or resetting user variables. Fix cancellation-before-start hangs, nested-worker cleanup, timer cancellation, and fork identity through runtime controller clones.

Sources: [#177](https://github.com/Breaking-IT/breaktest/pull/177), [#179](https://github.com/Breaking-IT/breaktest/pull/179).

## Ignore Sampler Results Without Scripting

- Choose **Included**, **Ignore on success**, or **Ignore always** directly from the sampler header. This removes the need to add a post-processor just to call `prev.setIgnore()`: it is easier to configure and avoids running an extra script for each sample. Existing scripts using `prev.setIgnore()` remain supported.
- **Ignore on success is especially useful for long polling:** successful polling responses can be left out of response-time metrics while connection errors, unsuccessful responses, and assertion failures remain visible and can still fail the transaction or trigger configured error actions. The policy runs after post-processors and assertions, so it can distinguish a successful wait from a real failure.
- Ignored results are excluded from listeners and transaction counts, bytes, latency, connect time, and failure status; post-processing, assertions, and error actions still run. To also exclude long-poll waits from an enclosing transaction's duration, select **Sum child sampler times**; wall-clock timing modes still include the wait.

Source: [#178](https://github.com/Breaking-IT/breaktest/pull/178).

## Scheduling and Test Plan Editing

- Edit closed- and open-model load phases in compact tables, including adding, deleting, and reordering rows. Open-model phases support random arrivals; comments and advanced expressions remain available in text view. Merely viewing a schedule or leaving a cell unchanged preserves its original text.
- Convert representable literal legacy rate/arrival/pause schedules on load while preserving timing, distribution, comments, and rate precision. Unresolved or non-equivalent expressions remain untouched, and source files change only when saved.
- Set an optional Max iterations on While Controller. Blank preserves the existing behavior, zero skips the loop, and variables/functions can provide the limit. Conditions can still end a loop earlier; invalid or negative limits stop the loop and log an error.
- Get variable and function autocomplete by typing `${` in the Search Tree dialog's Search and Replace by fields.

Sources: [#176](https://github.com/Breaking-IT/breaktest/pull/176), [#180](https://github.com/Breaking-IT/breaktest/pull/180), [#174](https://github.com/Breaking-IT/breaktest/pull/174).

## HTTP/2 Cleanup

- Close HTTP/2 connections immediately when a virtual user ends or resets, avoiding CPU-intensive TLS-close waits and delays when a server does not respond. This also applies to HTTP/2 selected through automatic protocol negotiation. Explicit HTTP/1.1 and HTTP/3 are unchanged.

Source: [#173](https://github.com/Breaking-IT/breaktest/pull/173).

## Upgrade Notes and Known Limitations

- **Fork defaults change for existing plans without lifecycle settings:** Graceful at iteration end and Skip on re-entry replace the old carry-over/wait behavior. Fork errors default to Continue independently of the thread group's error action. Review keep-alive and looping forks before running an older plan.
- **Generate parent sample is removed.** Old plans still load, but `TransactionController.parent` is discarded. Transaction-level listeners now also receive child samples; assertions apply to samplers, not transaction summaries. Counts include actual nested samples, and a failed transaction reports its first failing sample's response code. Update custom listeners or result consumers that depend on the old parent-sample structure.
- Ignored sampler durations remain part of wall-clock transaction timing. Select Sum child sampler times to exclude them; parallel sampler durations can sum to more than elapsed wall-clock time. Ignore always can hide a failed sampler from its transaction's failure status.
- A keep-running fork that fails during pacing ends the iteration that is just starting. During immediate iteration cancellation, suppression of cancelled samplers' results and control exceptions can also suppress a concurrent explicit stop-test request from those samplers. External engine stop requests are unaffected.
- Random-arrival phase flags require this release's updated schedule parser. HTTP/2 cleanup closes TCP connections without waiting for HTTP/2 GOAWAY or TLS close-notify, which some servers may log.
- Java 21 or later remains required. HTTP/3 over QUIC requires Java 26 or later; automatic HTTP/3 discovery remains opt-in.

[Full changelog since 2026.09.23](https://github.com/Breaking-IT/breaktest/compare/2026.09.23...2026.09.25)

# BreakTest 2026.09.23 — Variable Autocomplete, Reliable Validation, and Replay Recovery

This release adds scoped variable and function autocomplete, improves validation and transaction reporting, and lets new replay recordings be stored when older data is unavailable. AI script repair is now significantly faster and uses fewer tokens, with improved prompts, repair logic, controls, and reporting. CSV editing and correlation rule organization also improve.

## AI Repair

- Significantly reduce the total time spent on AI script repair and use fewer tokens through improved prompts and repair logic.
- Choose model and thinking settings per repair run. Improve Pi progress and usage reporting, and clear AI logs with Clear All.
- Supply correlation preflight evidence, batch edits and assertions, and compact validation results to reduce repeated analysis and oversized tool responses.
- Require an explicit structured completion status from every supported agent in GUI and file-backed repair. Missing or malformed status is reported as blocked, and failed agent processes remain failures.

Sources: [#163](https://github.com/Breaking-IT/breaktest/pull/163), [#164](https://github.com/Breaking-IT/breaktest/pull/164).

## Test Plan Editing

- Get inline variable suggestions by typing `${` in component fields, table cells, multiline editors, and expanded dialogs. Suggestions use enabled definitions in the test plan and current thread group, including configured CSV variable names, extractors, counters, User Parameters, and literal inline JSR223 `vars.put()` names. Type `${_` for installed functions; use Up/Down to select, Enter or Tab to accept, and Escape to dismiss.
- Completion preserves surrounding text and supports extractor match counts, indexed matches, and capture groups. JSR223 script bodies are excluded; CSV header inference, external script files, and computed script variable names are not supported.
- Create a missing local or archived CSV directly from CSV Data Set Config → Edit CSV. The file is created only when saved; cancelling leaves it uncreated.
- Choose an existing custom correlation group from an editable dropdown when saving an extractor as a predefined correlation, or enter a new group name.

Sources: [#167](https://github.com/Breaking-IT/breaktest/pull/167), [#160](https://github.com/Breaking-IT/breaktest/pull/160), [#159](https://github.com/Breaking-IT/breaktest/pull/159).

## Validation and Transaction Accuracy

- Validate the selected thread group or the group containing the selected element. When selection is outside a thread group, the validation shortcut and toolbar button reuse the last valid validation target instead of running every group. Deleted targets and targets from a previous plan are discarded.
- End an open-model user's journey after an error triggers Start next thread loop, preventing unscheduled repeated transactions and inflated request throughput. Subsequent scheduled arrivals continue normally.
- Correct timer exclusion in transaction measurements: trailing pauses outside a parent transaction's time window no longer produce negative or understated durations, and waits inside Synchronizing Timer or JSR223 Timer evaluation are now excluded.
- Avoid duplicate parent transaction results when stopping an in-flight request with Start next thread loop on error enabled. Preserve separate results for non-parent transactions.

Sources: [#168](https://github.com/Breaking-IT/breaktest/pull/168), [#162](https://github.com/Breaking-IT/breaktest/pull/162), [#166](https://github.com/Breaking-IT/breaktest/pull/166), [#170](https://github.com/Breaking-IT/breaktest/pull/170).

## Results and HTTP Replay

- Store new replay request/response data even when the previous recording is absent. If the recording is missing only from memory, recover its complete bundle from the saved test plan before updating it, preserving other samplers' recordings. Checksum mismatches and incomplete bundles stop the update rather than silently replacing data.
- Fix Jump to navigation with disabled duplicate elements and preserve buffered result links when their source elements are renamed or moved. Deleted targets no longer redirect to another sampler with the old name.
- Preserve `TE: trailers` on HTTP/2 and HTTP/3 requests, including HAR replay. Other unsupported TE values continue to be removed.

Sources: [#171](https://github.com/Breaking-IT/breaktest/pull/171), [#161](https://github.com/Breaking-IT/breaktest/pull/161), [#169](https://github.com/Breaking-IT/breaktest/pull/169).

## Upgrade Notes

- **Copy any legacy AI Knowledge notes you need before saving a plan.** Existing plans still open and display the notes as read-only, selectable text, but saving omits AI Knowledge elements and their notes. Their child elements are preserved in order. AI repair no longer reads or writes Knowledge.
- Transaction measurements that exclude timers may change because timer waits and trailing pauses are now accounted for correctly. Open-model error handling no longer generates extra unscheduled iterations.
- Java 21 or later remains required. HTTP/3 over QUIC requires Java 26 or later; automatic HTTP/3 discovery remains opt-in.

[Full changelog since 2026.09.16](https://github.com/Breaking-IT/breaktest/compare/2026.09.16...2026.09.23)

# BreakTest 2026.09.16 — Correlation Rule Management, Broader Token Detection, and Authentication Fixes

This release adds persistent correlation rule controls and broader automatic token detection, improves HAR imports and authentication across negotiated HTTP protocols, and reduces unnecessary work during command-line execution.

## Correlation Rules and HAR Import

- Manage rules through Tools → Correlation Rules: enable or disable individual rules or groups, save preferences across application updates, manage custom rules, and process the selected thread group. Corrupt or unreadable preference files now produce a warning instead of blocking HAR import.
- Add generic correlation rules for Matrix, CAS, Amazon Cognito, Jenkins, Engine.IO v4 HTTP polling, Flask-WTF, CSRF tokens, and JSON accessToken fields. Improve Keycloak matching and preserve URL-decoding when encoded response tokens are reused in request headers. Remove automatic ETag correlation.
- Correct URL encoding for imported parameter values and POST parameter names, including @ and other reserved characters. Move correlation matching to the top of HAR import options.
- Avoid showing the upload-review step when a recording contains only empty upload metadata.

Sources: [#157](https://github.com/Breaking-IT/breaktest/pull/157), [#155](https://github.com/Breaking-IT/breaktest/pull/155).

## Authentication and Navigation

- Fix NTLM authentication when the HTTP protocol is selected automatically. HTTP/3-capable requests use compatible fallback clients for NTLM, Kerberos, and Digest authentication; HTTP/3-only mode reports an explicit error when authentication requires a fallback.
- Fix Performance Report Jump to and double-click navigation for requests and transactions executed through Module Controllers. Add Jump to in the Module Controller target tree.

Sources: [#154](https://github.com/Breaking-IT/breaktest/pull/154), [#156](https://github.com/Breaking-IT/breaktest/pull/156).

## Non-GUI Execution and Maintenance

- Reduce command-line startup work and memory use by skipping unnecessary GUI processing and unused recording attachments. Runtime inputs such as archived CSV and upload files remain available.
- Allow non-GUI execution when a runtime test-element class is available but its GUI editor class is missing. GUI mode retains its existing missing-element validation.
- Improve CI caching and parallel test execution, remove unused legacy code, and address deprecated APIs and build warnings.

Sources: [#152](https://github.com/Breaking-IT/breaktest/pull/152), [#150](https://github.com/Breaking-IT/breaktest/pull/150), [#153](https://github.com/Breaking-IT/breaktest/pull/153).

## Upgrade Notes

- Custom correlation rules must use IDs distinct from built-in rules. Built-in IDs can no longer be overridden by custom definitions.
- Newly imported parameters may have URL Encode enabled more often, including values containing /, :, or comma. Existing plans are not automatically rewritten by this import change.
- Third-party plugins referencing removed legacy APIs, including Base64Encoder and LoopbackHTTPSocket, may require updates.
- Java 21 or later remains required. HTTP/3 over QUIC requires Java 26 or later; automatic HTTP/3 discovery remains opt-in.

[Full changelog since 2026.09.13](https://github.com/Breaking-IT/breaktest/compare/2026.09.13...2026.09.16)

# BreakTest 2026.09.13 — HAR Uploads, Shared Correlation Rules, and HTTP Improvements

This release improves replaying recorded file uploads, adds import and export
for custom correlation rules, and fixes response handling and result navigation.
It also reduces HTTP processing overhead and expands HTTP/3 compatibility with
test certificates and client-certificate authentication.

## HAR Uploads and Correlation Rules

- **Replay browser-captured file uploads.** HAR import recognizes multipart
  uploads and preserves filenames, field names, and content types. When the HAR
  includes supported browser upload captures, import binary and empty files
  into the JMX archive, save them to the working directory, or keep filename
  references only. Missing or ambiguous captures produce warnings.
- Reuse identical captured files and give different files with the same name
  distinct archive filenames. Archived upload references now resolve even
  before the imported plan has been saved.
- **Share custom correlation rules as JSON** through **Tools → Try Predefined
  Correlations**, with import, export, and a custom-rules-only filter. Reimporting
  updates matching rule IDs without duplicating them or removing unrelated rules.
- Fix custom correlation selection after opening another JMX, so the dialog
  uses the loaded plan's archived rules and imports into the correct plan.

Sources: [#140](https://github.com/Breaking-IT/breaktest/pull/140),
[#142](https://github.com/Breaking-IT/breaktest/pull/142).

## Fixes and Usability

- **Keep response bodies available to assertions and extractors during normal
  runs.** HTTP samplers configured with **Fetch and discard** or **Store on
  error** automatically retain bodies when assertions or post-processors apply,
  including inherited elements. Saved settings and explicit checksum modes
  remain unchanged.
- Navigate from redirect and embedded-resource subresults to their originating
  sampler using **Jump to** or double-click in either Results Tree view.
- Fix the **fast JMX loading** setting so it takes effect. Expose additional
  controls in **Options → Settings**, including extractor preview timeouts,
  custom correlation files, class-discovery caching, UDP receive limits,
  HTTP/2 closed-session retries, and the long-line response display guard.

Sources: [#138](https://github.com/Breaking-IT/breaktest/pull/138),
[#143](https://github.com/Breaking-IT/breaktest/pull/143),
[#145](https://github.com/Breaking-IT/breaktest/pull/145).

## HTTP Performance and Resource Use

- Reduce temporary allocations when decoding gzip, Deflate, Brotli, and
  Zstandard responses. Release decoder resources promptly, including when
  calculating checksums of decoded responses.
- Format diagnostic request and response headers only when a consumer reads
  them, by default across HC5 HTTP/1.1 and HTTP/2 and the Java HTTP/3 client.
  This reduces unnecessary work when headers are unused; workloads that
  frequently read small headers may benefit from disabling the option under
  **Options → Settings → HttpClient5** and restarting BreakTest.
- Avoid unnecessary request-header parsing when caching responses without a
  `Vary` header.
- Let compatible backend-listener plugins opt into reusing a static sample
  context, reducing per-sample allocations. Existing plugins retain their
  current behavior unless they opt in.

Sources: [#137](https://github.com/Breaking-IT/breaktest/pull/137),
[#139](https://github.com/Breaking-IT/breaktest/pull/139),
[#144](https://github.com/Breaking-IT/breaktest/pull/144),
[#145](https://github.com/Breaking-IT/breaktest/pull/145),
[#146](https://github.com/Breaking-IT/breaktest/pull/146).

## HTTP/3 and TLS Compatibility

- Support HTTP/3 test targets with self-signed or expired certificates through
  a certificate retry, and use configured client certificates on the normal
  HTTP/3 connection path for mutual TLS.
- **HTTP/2 samplers now ignore certificate hostname mismatches**, matching
  HTTP/1.1. Tests that relied on rejecting a wrong-host certificate will no
  longer receive that validation failure. The updater retains certificate and
  hostname verification.
- HTTP/3 certificate retries require TCP TLS at the original origin with a
  matching certificate. They cannot recover certificate failures at a different
  origin reached through automatic redirects. The initial handshake failure,
  TCP probe, and retry count toward sample elapsed time.
- The JDK hostname setting in `bin/system.properties` also affects JDK HTTP
  clients in plugins and JSR223 scripts. Embedded applications and test runners
  that do not load that file must pass
  `-Djdk.internal.httpclient.disableHostnameVerification=true` at JVM startup,
  before any JDK HTTP client initializes, to obtain the same sampler behavior.
  Security-sensitive JDK clients must explicitly enable HTTPS endpoint
  identification.
- Java 21 or later remains required; HTTP/3 over QUIC requires Java 26 or later.
  Automatic HTTP/3 discovery remains opt-in.

Source: [#141](https://github.com/Breaking-IT/breaktest/pull/141).

Also includes test and CI reliability improvements across Linux, macOS, and
Windows, with expanded Java 26 and HTTP/3 coverage.

[Full changelog since 2026.09.07](https://github.com/Breaking-IT/breaktest/compare/2026.09.07...e0e2f67ac668bd8d78b13f5a754c64a4eb799829)

# BreakTest 2026.09.07 — Portable Files, CSV Editing, and Correlation Tools

BreakTest 2026.09.07 makes test plans easier to transport by keeping CSVs and
upload files inside the JMX archive. It adds CSV editing, archive cleanup, and
new tools for finding dynamic values and creating extractors and assertions
from recorded responses.

## Portable Files and Archive Management

- Adds **Tools → Archive browser** to add, browse, export, and delete shared
  files stored under `files/` inside the JMX archive.
- Lets CSV Data Sets and HTTP file uploads use files from the archive. Other
  function-capable file fields can use `${__archiveFile(filename)}`.
- Adds recording cleanup with options to remove recorded headers and bodies,
  exclude static-resource recording data, and remove orphaned recording data.
  A preview shows the proposed removal and retained exchanges before applying
  changes. Shared files are preserved by recording cleanup.
- Keeps recordings without clear sampler links during orphan cleanup to avoid
  accidentally removing an entire recording.
- Includes recording manifests, request/response content, HAR attachments, and
  correlation rules when copying elements between BreakTest processes.

Sources: [#135](https://github.com/Breaking-IT/breaktest/pull/135),
[#132](https://github.com/Breaking-IT/breaktest/pull/132).

## CSV Editing and Responsiveness

- Adds an **Edit CSV** dialog with Save and Cancel for external and archived
  CSV files. Copy a CSV file directly into the JMX archive for easy portability,
  or export it back to disk.

Source: [#135](https://github.com/Breaking-IT/breaktest/pull/135).

## Correlation and Response Tools

- Shows live regular-expression extraction results using recorded sample data,
  including extracted variables, capture groups, occurrence counts, and parse
  time. Double-clicking a shortened value opens its full selectable text.
- Adds replacement actions for extracted values, with affected sampler paths
  shown before replacement. Existing variable references remain protected.
- Creates assertions and regex extractors directly from selected response body
  or header text, with undo support.
- Searches HTTP parameter values in earlier recorded responses from the value
  cell's context menu. Results show sampler paths and surrounding content;
  opening a result selects the matching value in the recorded response.

Sources: [#131](https://github.com/Breaking-IT/breaktest/pull/131),
[#134](https://github.com/Breaking-IT/breaktest/pull/134).

## Saving, Startup, and Diagnostics

- Improves archive read/write performance, including saves and backups, and
  avoids repeatedly hashing archived uploads during test execution.
- Saves plans through atomic file replacement while preserving existing POSIX
  permissions. Archives with missing or corrupt indexed files can open for
  repair; saving is blocked until those indexed files are restored or removed.
- Makes Java version detection more reliable, including Java 21 patch releases,
  and falls back to normal class loading when a shared class cache is unusable.
- Shows recent startup messages in the GUI log panel, including messages emitted
  before the panel initialized.
- Removes the misleading warning when no client-certificate keystore is
  configured, while retaining warnings for explicitly configured missing files.

Sources: [#135](https://github.com/Breaking-IT/breaktest/pull/135),
[#130](https://github.com/Breaking-IT/breaktest/pull/130),
[#133](https://github.com/Breaking-IT/breaktest/pull/133),
[#129](https://github.com/Breaking-IT/breaktest/pull/129).

## Build and Test Improvements

- Cuts clean build times by up to half in measured cache-warmed builds by
  running independent batch integration suites in three parallel groups while
  keeping tests with shared ports or output files serialized. Intentional
  delays and assertions remain unchanged.
- Improves GUI test isolation and makes HTTP interruption testing wait for
  request receipt before interrupting it.

Source: [#135](https://github.com/Breaking-IT/breaktest/pull/135).

## Compatibility Notes

- Java 21 or later is required; HTTP/3 over QUIC requires Java 26 or later.
- Both BreakTest processes must use an updated build to transfer recording
  attachments through copy/paste.
- Embedded files are not automatically transferred to remote RMI engines.
- Imported archive filenames must be portable across operating systems,
  including Windows filename restrictions.

[Full changelog since 2026.08.24](https://github.com/Breaking-IT/breaktest/compare/2026.08.24...757c5c73323c5b578e27bc1e54269bd91d742bff)

# BreakTest 2026.08.24 — Scoped Search, Flagging, and Reliability Fixes

BreakTest 2026.08.24 makes large test plans easier to inspect and edit with
scoped search and broader, safer flagging. It also hardens update restarts, AI
Auto Scripting, HTTP client cleanup, and saved JMX files.

## Search and Flagging

- Scopes searches to the Thread Group containing the selected element by
  default, with controls to choose another Thread Group or the full test plan.
- Separates broad, read-only flagging from search and replace, so recorded
  request and response content can be flagged without exposing it to edits.
- Adds live text and node-type flagging with compact, mutually exclusive
  controls.
- Supports safe replacement in element names, comments, HTTP request fields,
  parameters, headers, and upload metadata.
- Improves result navigation, regular-expression validation, case handling,
  replacement behavior, and the compact Search Tree dialog layout.

## Reliability Fixes

- Reliably reopens the active JMX file after an update restart while removing
  stale reopen properties from reconstructed launch commands.
- Self-heals stale or closed AI Auto Scripting GUI agent bridges and isolates
  Unix sockets per BreakTest process.
- Closes HC5/H2 clients for short-lived users and runtime sampler clones across
  parallel and exceptional execution paths, preventing leaked client threads
  and file descriptors.
- Saves runtime function properties as portable JMX values without mutating
  the live GUI tree, and refuses to write properties whose compiled function
  has already been lost.

## Compatibility

- Existing JMeter-compatible JMX plans and BreakTest archives continue to load
  and save normally.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.08.24`.

# BreakTest 2026.08.20 — Restart Reliability and Safer Thread Group Defaults

BreakTest 2026.08.20 improves self-update restart behavior, clarifies permitted
Community testing, and makes new Thread Groups safer to configure.

## Update Restart

- Reopens the GUI's currently active JMX file after an update is installed and
  the application restarts.
- Replaces stale command-line test-file arguments so the restarted process
  receives exactly one plan.

## Thread Group Defaults

- Places the **Load Profile** row below **Pacing option**, keeping the controls
  affected by the profile selection together.
- Defaults new Thread Groups to **Start Next Thread Loop** after a sampler
  error instead of **Continue**.

## Community License

- Permits testing systems that the user's organization owns or operates, or
  that the user is authorized by the system operator to test, including testing
  carried out for clients under a services engagement.
- Updates the documentation and license terms to match the permitted testing
  scope and prohibited hosted, redistributed, competing, and circumvention
  uses.

## Compatibility

- Existing JMeter-compatible JMX plans and BreakTest archives continue to load
  and save normally.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.08.20`.

# BreakTest 2026.08.18 — GUI Persistence Hotfix

BreakTest 2026.08.18 is a focused hotfix that prevents editor changes from
being lost when navigating between test elements. It also completes the move of
the browser recorder to its independent companion-project release lifecycle.

## GUI Persistence

- Preserves the selected **Module To Run** in Module Controller when navigating
  away from the element and back.
- Preserves edits to existing **Patterns to Test** rows in Response Assertion;
  rows no longer need to be removed and re-added for changes to stick.
- Writes the visible editor state back before switching nodes, including custom
  or lazily-created editing surfaces that are not covered by built-in Swing
  change trackers.
- Tracks tree and list selections, list-model updates, and table structure
  changes while keeping undo history precise and protecting shared GUI
  components from stale double write-backs.
- Restores `PowerTableModel` cell-update notifications so committed table edits
  are visible to model listeners.

## Packaging

- Keeps the browser recorder in its own repository and release lifecycle; it is
  no longer tracked as a BreakTest submodule or included in BreakTest archives.

## Compatibility

- Existing JMeter-compatible JMX plans and BreakTest archives continue to load
  and save normally.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.08.18`.

# BreakTest 2026.08.17 — Predefined Correlations and Smarter HAR Imports

BreakTest 2026.08.17 adds guided predefined correlation discovery, models
parallel browser traffic more faithfully during HAR import, and expands AI
Repair with Pi Code, Gemini CLI, and Cursor Agent. It also includes HTTP
authentication, recording search, listener metadata, and packaging fixes.

## Predefined Correlations

- Scans HAR imports for grouped predefined correlations by default and previews
  the later request values that will be parameterized.
- Adds **Tools > Try Predefined Correlations** for existing Thread Groups, with
  grouped rule selection, match previews, custom Regex and JSONPath rules, and
  reviewed application.
- Ships predefined rules for common OAuth/OIDC, SAML, identity-provider,
  enterprise, web-framework, and generic JSON/REST flows.
- Handles repeated and chained values with unique variables and the nearest
  valid preceding extraction, while enforcing configurable match limits.
- Skips binary response bodies and unwritable request headers, and bounds
  built-in tag expressions to avoid pathological Regex Extractor CPU usage.

## HAR Import and Parallel Flow Recognition

- Groups browser requests into parallel waves using in-flight completion times
  instead of splitting solely on the immediately preceding request.
- Keeps redirect chains sequential even when their recorded timings overlap.
- Splits parallel waves when a request consumes a value extracted by another
  request in the same wave, including nested and chained dependencies.
- Preserves request order, controller enabled state, and parallelism
  expressions, while avoiding unnecessary single-request parallel wrappers.

## AI Repair Harnesses

- Adds experimental Pi Code, Gemini CLI, and Cursor Agent harnesses for AI
  Repair, with harness-specific model, authentication, and execution settings.
- Sorts available AI tools ahead of unavailable tools and provides
  harness-aware setup and repair guidance.
- Supports npm-installed `.cmd` tools, standard-input prompts, bundled Command
  Prompt and PowerShell bridges, and argument-file JSON on Windows.
- Restores action-local unsaved state when a batched repair action fails, so
  earlier successful edits remain available.

## Bug Fixes and Improvements

- Fixes HC5 authentication across HTTP/1.1 and HTTP/2, including proxy
  credentials, wildcard realms, and credentials backed by JMeter variables.
- Allows recorded request and response content to be searched immediately after
  HAR import, before the recording is first saved.
- Lets listeners and Backend Listener clients opt into source test-element paths
  independently from JMeter variable snapshots, using one allocation-free
  capability check per sample delivery.
- Excludes the generated `breaktest_html_report.log` file from archives created
  by `create_breaktest_archive.sh`.
## Compatibility

- Existing JMeter-compatible JMX plans and BreakTest archives continue to load
  and save normally.
- Existing flat custom predefined-correlation catalogs remain readable.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.08.17`.

# BreakTest 2026.08.07 — Safer AI Repair and Desktop Polish

BreakTest 2026.08.07 makes AI-assisted repairs safer by keeping backups
untouched, brings Results Tree table mode in line with tree mode, and polishes
desktop behavior across themes, menus, icons, and modern Java launches.

## AI Repair Safety

- Creates a backup of the current JMX file before AI repair, then continues the
  repair against the active file instead of modifying the backup.
- Keeps the backup as an unchanged recovery point while preserving the normal
  save and reload behavior of the active test plan.
- Updates the file-backed repair prompt and setup guidance to describe the
  corrected backup workflow consistently.

## Results Tree and Element Navigation

- Adds the tree view's search and filtering controls to Results Tree table mode.
- Shows a full-width horizontal divider between the result list and details so
  the draggable resize boundary is easy to discover.
- Adds distinct tree icons for CSV Data Set Config and User Defined Variables
  elements and uses the HTTP Header Manager icon consistently in the tree and
  Add menu.
- Restores the original Add menu category order and separators, keeping Pre
  Processors and Post Processors together.

## Desktop and Build Reliability

- Refreshes reused file chooser components before display so open and save
  dialogs follow a theme changed while BreakTest is running.
- Enables native access for FlatLaf in packaged launchers, removing restricted
  native-library warnings on recent Java versions.
- Restores `./gradlew clean compile` as an explicit aggregate compilation task
  across all subprojects.

## Compatibility

- Existing JMeter-compatible JMX plans and BreakTest archives continue to load
  and save normally.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.08.07`.

# BreakTest 2026.07.31 — AI Scripting, JMX Migration, and Results Tree Responsiveness

BreakTest 2026.07.31 expands AI Auto Scripting with GitHub Copilot CLI,
improves correlation planning performance and correctness, adds a safe guided
upgrade from standard JMeter JMX files, and keeps the Results Tree responsive
when inspecting very large response bodies.

## AI Auto Scripting

- Adds GitHub Copilot CLI as an experimental AI Auto Scripting harness alongside
  Codex, Claude Code, OpenCode, and MCP workflows.
- Reads model and token statistics without showing Copilot's tool-call boxes in
  the activity log, and reports clear setup guidance when a configured CLI is
  missing.
- Speeds up correlation planning by indexing candidate literals in one pass per
  recorded response and caching the native recording store instead of reopening
  and reformatting it for every tool call.
- Validates that a proposed regular expression captures the intended dynamic
  value with the same Perl5 engine used by the Regex Extractor. Invalid
  candidates remain unresolved instead of producing a misleading correlation.
- Prompts to save an unsaved plan before reading its linked recording and
  distinguishes an unreadable recording from a plan that has none.
- Lists the individual nodes changed by small search-and-replace operations so
  each can be reached directly from the AI activity log.
- Uses recording-oriented tool names instead of calling every linked recording
  HAR, while retaining the previous tool spellings as compatibility aliases.
- Moves the repair prompts into named resource templates and improves bridge
  diagnostics without changing the rendered prompt variants.

## Standard JMX Upgrade

- Detects standard XML JMeter JMX files when they are opened and asks for
  explicit confirmation before converting them to BreakTest's native archive
  format.
- Keeps an exact, collision-safe JMeter-compatible backup next to the source
  file and replaces the source through a temporary file, atomically when the
  platform supports it.
- Leaves the source untouched when conversion is declined or fails and skips
  the prompt for plans already stored in native BreakTest format.
- Converts GUI tree nodes through the normal Save preparation path before
  serialization, preventing XStream conversion failures during the upgrade.

## Results Tree and Desktop Reliability

- Adds the existing **Jump to** action to the Results Tree table-view context
  menu, matching tree-view navigation.
- Avoids inserting a large Text-renderer response into a hidden Swing document
  before showing the long-line warning.
- Loads oversized single-line bodies as plain text with display-only safety line
  breaks, avoiding expensive syntax highlighting while preserving the original
  response for Pretty Print and response comparison.
- Allows packaged launches from installation paths containing spaces, including
  macOS dock icons and Class-Data Sharing paths.

## Compatibility

- Existing JMeter-compatible JMX plans can still be opened without conversion
  by declining the upgrade prompt.
- Previous AI recording tool names remain accepted as aliases.
- Java 21 or later is required; Java 26 or later is required for HTTP/3 over
  QUIC.
- This release uses the direct Git tag `2026.07.31`.

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
- Request indexing, transaction delay, and storage
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

- BreakTest integrates with Codex, Claude Code, OpenCode, and MCP-compatible
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
License 1.0. Community use covers testing systems you operate and systems you
are authorized to test, including testing performed for clients. Reselling or
redistributing BreakTest, offering it to third parties as a hosted or managed
service, reusing it in another product, using it to build a competing product,
rebranding it, and circumventing Community Limits require prior written
permission or a commercial license from Breaking IT.

Commercial licensing requests may be sent to `info@breakingit.nl`.

BreakTest is a derivative work of Apache JMeter. Applicable copyright, license,
and attribution notices for Apache JMeter and other third-party materials are
retained in the source tree and distribution. BreakTest is independent from
The Apache Software Foundation and is not affiliated with, endorsed by, or
sponsored by The Apache Software Foundation.
