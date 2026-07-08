# Analysis: Top Apache JMeter Bugs/Issues and Top Requested Features

_Prepared for BreakTest roadmap planning — 2026-07-08_

BreakTest is an independent fork of Apache JMeter. This document summarizes the
most impactful **bugs/issues** and the most **requested features/improvements**
in the upstream JMeter community, and maps each to BreakTest's current status so
we can see where the fork already differentiates and where the remaining
opportunities are.

Sources are drawn from the Apache JMeter GitHub issue tracker (ranked by 👍
reactions and by comment volume), independent user-review aggregators
(PeerSpot, TrustRadius), troubleshooting knowledge bases, and 2025/2026
JMeter-vs-k6-vs-Gatling comparisons. Links are listed at the end.

---

## Part 1 — Top Bugs / Issues

Ranked by a blend of community engagement (reactions, comments), frequency in
"common problems" writeups, and how often they drive users to competing tools.

### A. Structural pain points (the reasons people leave JMeter)

| # | Issue | Why it matters | BreakTest status |
|---|-------|----------------|------------------|
| 1 | **High memory usage / poor memory efficiency** | Most-cited complaint across every review source. Thread-per-user model consumes far more RAM per virtual user than event-driven k6/Gatling, forcing high-end infra and distribution earlier than users want. | **Largely addressed.** ~50% lower memory reported; lazy response decompression, response-retention modes, lazy per-sample metadata, lightweight plan cloning, Java 21 + virtual threads. |
| 2 | **GUI freezes / high CPU under load** | GUI mode freezes "halfway through the test in most cases" at high load; memory-heavy listeners (View Results Tree, graphical listeners) freeze the console with no warning. | **Partially addressed.** Background JMX loading with overlay, lazy metadata so listeners only pay when needed, fast-load option. GUI is still not the recommended path for load generation (same guidance as JMeter). |
| 3 | **Crashes / OOM at high scale (>10k users)** | JMeter struggles and can crash beyond ~10k users on a single node. | **Partially addressed** locally via lower footprint; true scale is deferred to BreakTest Enterprise (distributed load generators) rather than the removed RMI Remote Server model. |
| 4 | **View Results Tree crashes on large payloads** (GH #6336, #5507) | High-comment bug; large responses OOM or fail to render/format. | **Addressed direction.** Response-retention modes (store compressed / discard bodies / checksums) and separate raw/rendered lifecycle reduce the blast radius. Worth an explicit large-payload guard/streaming view. |
| 5 | **Distributed testing instability** | Remote engines dump core / OOM / freeze before writing the exception; setup is complex. | **Changed by design.** RMI Remote Server removed; distributed execution moves to BreakTest Enterprise / external orchestration. Removes the flaky path but leaves an OSS-only distribution gap (see Features §7). |

### B. Concrete tracker bugs with community traction

| Issue | Type | BreakTest status |
|-------|------|------------------|
| **#6330 HTTP connections stuck in CLOSED_WAIT** | Connection lifecycle bug (high comments) | Relevant to the new HC5 path — HTTP/2 closed-session retry handling exists; worth verifying CLOSED_WAIT does not recur on HC5. |
| **#3038 HTTPS via HC4 hangs 4–5s without reverse DNS** | HC4-specific hang | HC4 is no longer the primary sampler path (HC5 is), so the specific defect is sidestepped; HC4 jars retained only for legacy plugins. |
| **#6244 max-width lost on maximize/restore** | GUI layout bug | Not specifically addressed; low effort, good candidate given the FlatLaf GUI rework. |
| **#6457 Thread Group iterations unexpected warning** | Scheduling/validation bug | Thread Group was substantially reworked (open/closed models, phases); re-validate this warning path. |
| **Response body not formatted correctly in VRT (#5507)** | Rendering defect | Rendered vs raw views now have separate lifecycle handling — likely improved; verify. |

### C. Cross-cutting complaints (not single tickets)

- **Rudimentary/limited built-in reporting** — recurring across review sites.
- **Weak automation / manual-heavy workflows** — hard to drive without add-ons.
- **Documentation gaps for newcomers** — steep learning curve.
- **GC pauses adding latency spikes** — JVM artifact affecting result accuracy.

---

## Part 2 — Top Requested Features / Improvements

Ranked by tracker engagement and repetition across review/comparison sources.

### A. Highest-signal tracker requests

| Rank | Request | Tracker | BreakTest status |
|------|---------|---------|------------------|
| 1 | **Undo/redo on test-plan tree edits** | #1930 (most-commented request in tracker history) | **Done.** Undo/redo enabled for add/delete/update/move/search-replace. |
| 2 | **Parallel execution — ForEach parallel / concurrent requests** | #5553 (top by reactions) | **Done (adjacent).** Parallel Controller for browser-like concurrency + Fork Controller for async branches. A directly-parallel ForEach iteration mode is still a clean gap to close. |
| 3 | **Open Model Thread Group: concurrency limiter** | #5966 (high reactions, from a JMeter maintainer) | **Done.** Open-model scheduling with max active-thread limits, even-arrival scheduling, graph preview. |
| 4 | **Zoom / remember zoom level / shortcuts** | #6104 | **Not addressed.** Small GUI ergonomics win worth picking up with the FlatLaf work. |
| 5 | **SOAP request with attachment** | #2870 | **Not addressed.** Niche/legacy; low priority given SOAP's decline. |
| 6 | **JDBC/data hashing to avoid holding all output in memory** | #1896 | **Aligned.** Checksum/compressed retention modes fit this philosophy; not specifically wired into JDBC sampler. |

### B. Strategic features users want (the modernization asks)

| # | Theme | What users ask for | BreakTest status |
|---|-------|--------------------|------------------|
| 1 | **Modern HTTP** | HTTP/2, modern client, current compression | **Done.** HC5, first-class HTTP/2 (async, fallback, connect timing), gzip/deflate/Brotli/Zstandard, NTLM on HC5. Strong differentiator. |
| 2 | **Tests-as-code / VCS-friendly** | Engineers "think in code, not clicks"; want reusable, versionable scripts | **Partial.** JMX stays version-controllable; JSR223/Groovy is the sanctioned scripting direction (BeanShell/BSF/JEXL/Rhino removed). No code-first DSL like k6/Gatling — a strategic decision point. |
| 3 | **Observability integration** | Native Prometheus/Grafana/OpenTelemetry, real-time metrics | **Gap/opportunity.** Upstream relies on Backend Listener (InfluxDB/Graphite) + community Prometheus plugin. Native OTel/Prometheus export would be a high-value, well-scoped differentiator. |
| 4 | **Better reporting/visualization** | Richer, percentile-aware, good-looking reports (Gatling-style) | **Gap/opportunity.** Not called out as reworked; the single most consistent competitive complaint. High ROI. |
| 5 | **CI/CD & cloud-native** | Easy CI integration, Kubernetes-friendly distribution | **Split.** OSS local + Enterprise for distribution. A first-class containerized/K8s story for the OSS core is an open question. |
| 6 | **AI-assisted scripting/repair** | (Emerging expectation) correlation, parameterization, script cleanup | **Done — leading.** AI repair via Codex/Claude Code/opencode + MCP; extractor insertion, correlation, assertions, change highlighting. Ahead of upstream. |
| 7 | **OSS distributed load** | Multi-node without paying | **Deliberately not in OSS.** RMI removed; distribution is Enterprise. Clear positioning, but a competitive gap vs k6/Gatling OSS clustering. |
| 8 | **More protocols** | Beyond HTTP(S): gRPC, WebSocket, etc. | **Mostly unaddressed** in core; historically plugin territory. Candidate if broadening scope. |

---

## Part 3 — Synthesis for BreakTest

**Where BreakTest already wins (keep marketing these):**
memory/CPU footprint, HTTP/2 + modern client stack, undo/redo, open+closed
workload modeling, parallel/fork controllers, richer View Results Tree
diagnostics, and AI-assisted repair. These map directly onto the top upstream
pain points (#1, #2 bugs) and top requests (#1, #2, #3 features).

**Highest-ROI open opportunities (ranked):**
1. **Native observability export** (Prometheus/OpenTelemetry) — recurring ask, well-scoped, strong differentiator.
2. **Modern reporting/visualization** — the most persistent competitive weakness vs Gatling/k6.
3. **Large-payload-safe results viewing** — close out the #6336/#5507 class of VRT failures with streaming/guarded rendering.
4. **OSS distribution/CI-native story** — decide the boundary with Enterprise deliberately; it's the main "why not just use k6" objection.
5. **GUI ergonomics quick wins** — zoom/shortcuts (#6104), max-width restore (#6244), iteration-warning (#6457).

**Deliberate non-goals (document, don't drift):**
RMI remote server, legacy scripting engines, HC4 as primary path, niche legacy
protocol asks (e.g., SOAP attachments). These are intentionally out of scope and
should be stated as such so they aren't re-litigated as "missing features."

---

## Part 4 — Extended list: gaps BreakTest has **not** yet tackled

Parts 1–3 lean toward areas where BreakTest already leads. This section is the
opposite: still-open JMeter pain points and requests where the fork currently
has little or nothing. These are the real backlog candidates.

### D. Protocol breadth (all plugin-only or absent upstream)

| Item | Detail | BreakTest status |
|------|--------|------------------|
| **Native gRPC** | Only community plugins exist upstream, and most support **unary RPC only** — client/server **streaming and bidirectional** are largely unsupported. | Not in core. High-value modern protocol; owning unary + streaming would be a real differentiator. |
| **Native WebSocket** | Load-testing WS needs a third-party sampler pack; not in core. | Not in core. Common in modern apps (chat, trading, live dashboards). |
| **GraphQL** | Only thin HTTP-body helpers; no first-class query/variables/introspection support. | Not in core. Increasingly requested. |
| **Messaging: Kafka / MQTT / AMQP / SSE** | JMS exists; modern event/streaming protocols are plugin territory. | Not in core. Candidate for event-driven system testing. |

### E. Scripting & correlation productivity (the #1 friction upstream)

| Item | Detail | BreakTest status |
|------|--------|------------------|
| **Deterministic auto-correlation on record** | JMeter has **no native auto-correlation**; every session token/CSRF/redirect value is manually extracted. This is *the* productivity gap vs commercial tools and the top learning-curve complaint. | **Partial via AI repair**, but a fast, deterministic, offline record-time correlation engine (rules + heuristics) is distinct and still missing. |
| **Recorder modernization** | Open requests: **headless/CLI recorder** (#6464), **automatic proxy configuration** for the recorder (#6635). | Not addressed. Recording still assumes GUI + manual proxy setup. |
| **Python 3 scripting** | Jython is stuck on Python 2; #6498 asks for Python 3. | Not addressed (BreakTest's sanctioned path is JSR223/Groovy — but Py3 is a real ask). |

### F. Debugging & authoring ergonomics

| Item | Detail | BreakTest status |
|------|--------|------------------|
| **Step debugger / breakpoints** | No native breakpoint-and-inspect; users lean on Debug Sampler + View Results Tree. | Not addressed. VRT diagnostics improved, but no true step-through debugging. |
| **Search bar in HTTP request body** (#6443) | Large bodies are hard to navigate/edit. | Not addressed; small win, pairs with the FlatLaf work. |
| **Response Assertion prints each pattern** (#6700) | Better assertion failure output. | Not addressed; low effort. |
| **Test-plan modularity/reuse ergonomics** | Include Controller / Module Controller are clunky; reuse across plans is painful. | Not addressed; strong quality-of-life target. |

### G. Reporting, analysis & CI gating

| Item | Detail | BreakTest status |
|------|--------|------------------|
| **HTML dashboard rigidity** | Graphs must be **pre-declared in properties** or render empty; **custom decimal percentiles** requested (#6468); report is static and dated. | Not addressed. Modern interactive/live report is high ROI (ties to Part 3 opportunity #2). |
| **Native SLA / threshold pass-fail gating** | No first-class "fail the run if p95 > X" for CI; users bolt it on. | Not addressed. Small, high-value CI feature. |
| **Result diffing across runs / baselines** | No native run-to-run regression comparison. | Not addressed (BreakTest has recorded-vs-replayed diffs, but not run-vs-baseline perf diffs). |

### H. Data, secrets & maintenance

| Item | Detail | BreakTest status |
|------|--------|------------------|
| **Native secrets/credential management** | Tokens/passwords live in plaintext CSV/properties; no vault integration. | Not addressed. Security-relevant, increasingly expected. |
| **Synthetic/test-data generation** | No native faker/data-gen; users hand-build CSVs. | Not addressed. |
| **JDBC multi-statement scripts** (#6467) | Run several SQL statements from one sampler. | Not addressed. |
| **Deprecated-library modernization** | Replace ORO regex (#6645) and the xpp pull parser in xstream (#3708); reduces CVE surface and speeds regex/XPath. | Partially aligned with BreakTest's modernization thrust, but these specific libs likely remain. Also note XPath "Use Tidy" is memory-heavy/slow. |
| **Parent Controller path-caching optimization** (#6720) | Recent perf enhancement request. | Not specifically addressed; fits BreakTest's low-footprint story. |

### Updated highest-ROI shortlist (incorporating the extended gaps)

1. **Native observability export** (Prometheus/OpenTelemetry) — _still #1._
2. **Modern, interactive reporting + native CI SLA gating** — reporting is the most persistent competitive weakness; SLA gating is cheap and high-value.
3. **Deterministic auto-correlation engine** — the top JMeter productivity complaint; complements (not replaces) AI repair.
4. **Protocol expansion: gRPC (incl. streaming) + WebSocket** — where modern apps live; mostly unsolved even by plugins.
5. **Recorder modernization** — headless/CLI recording (#6464) + auto proxy config (#6635).
6. **Native secrets management** — security-relevant, differentiating.

---

## Sources

- [Apache JMeter — Issues (project page)](https://jmeter.apache.org/issues.html)
- [apache/jmeter GitHub Issues](https://github.com/apache/jmeter/issues)
- [Troubleshooting Common Issues in Apache JMeter — Mindful Chase](https://www.mindfulchase.com/explore/troubleshooting-tips/testing-frameworks/troubleshooting-common-issues-in-apache-jmeter.html)
- [Apache JMeter: Pros and Cons 2026 — PeerSpot](https://www.peerspot.com/products/apache-jmeter-pros-and-cons)
- [What needs improvement with Apache JMeter? — PeerSpot](https://www.peerspot.com/questions/what-needs-improvement-with-apache-jmeter)
- [Apache JMeter Reviews — TrustRadius](https://www.trustradius.com/products/apache-jmeter/reviews/all)
- [3 Common Issues When Running a JMeter Script — BlazeMeter](https://www.blazemeter.com/blog/jmeter-scripts)
- [JMeter vs Gatling vs k6 (2026 comparison) — Vervali](https://www.vervali.com/blog/jmeter-vs-gatling-vs-k6-the-complete-2026-comparison-benchmarks-ci-cd-scripting-and-use-cases/)
- [What engineers want in performance testing tools (Reddit analysis) — Gatling](https://gatling.io/blog/performance-testing-tools-reddit)
- [Load Testing Tools compared — Ranorex](https://www.ranorex.com/blog/load-testing-tools/)
- [Real-time Results — Apache JMeter User Manual](https://jmeter.apache.org/usermanual/realtime-results.html)
- [jmeter-prometheus-plugin (community) — GitHub](https://github.com/johrstrom/jmeter-prometheus-plugin)
- [JMeter gRPC Request plugin (zalopay-oss) — GitHub](https://github.com/zalopay-oss/jmeter-grpc-request)
- [WebSockets, gRPC, and GraphQL in the Core — DZone](https://dzone.com/articles/websockets-grpc-graphql-core)
- [How to Handle Correlation in JMeter — BlazeMeter](https://www.blazemeter.com/blog/correlation-in-jmeter)
- [Generating Dashboard Report — Apache JMeter User Manual](https://jmeter.apache.org/usermanual/generating-dashboard.html)
- [How to speed up JMeter (Part 1) — PFLB](https://pflb.us/blog/how-to-speed-up-jmeter-part-1/)

_Specific tracker items referenced: #1896, #1930, #2870, #2892, #3038, #3075,
#3708, #5507, #5553, #5966, #6104, #6244, #6330, #6336, #6443, #6457, #6461,
#6464, #6467, #6468, #6498, #6635, #6645, #6700, #6720._
