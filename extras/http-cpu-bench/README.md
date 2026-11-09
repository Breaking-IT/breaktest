# HTTP CPU-per-request benchmark

Measures how much CPU BreakTest spends per HTTP request, next to other load tools and to
lean baseline clients, all sending the same requests to the same Rust stub.

## What it measures

`run.sh` runs each tool at a fixed target rate. After `WARMUP` seconds (JIT, connection
setup) it measures for `DURATION` seconds:

- CPU seconds (user + sys) of the load generator's process tree in that window
- requests the stub served in that window, taken from the stub's own counter

and reports `cpu_us_per_req`. Startup and warmup are excluded. Rows that did not reach 95%
of the target rate are marked `below-target`; `h2_pct` is the share of requests the stub
saw over HTTP/2.

## Tools (`TOOLS`)

| Tool               | What runs                                                                    |
|--------------------|------------------------------------------------------------------------------|
| `breaktest`        | BreakTest from this checkout, default sampler settings                       |
| `breaktest-before` | the same build with this work's HTTP client changes switched off             |
| `breaktest-h11`    | sampler forced to HTTP/1.1                                                   |
| `jmeter`           | Apache JMeter (5.6.3 tested): `JMETER_HOME`, optional `JMETER_JAVA_HOME`     |
| `gatling`          | `gatling/` via `mvn gatling:test` (Gatling 3.14; Maven's JVM is counted)     |
| `k6`               | `k6/bench.js`                                                                |
| `locust`           | `locust/locustfile.py` with `FastHttpUser`, `LOCUST_PROCESSES` (default 4)   |
| `rust`             | `bench-loadgen` (hyper + rustls), decompresses gzip bodies                   |
| `rust-raw`         | `bench-loadgen --no-decompress`: what BreakTest does when nothing reads the body |
| `java-async`, `java-classic` | `java/JavaLoadgen.java`: plain HttpClient5 with one shared client  |
| `java-async-per-user`, `java-async-per-user-adaptor` | HttpClient5 with one async client per user |

BreakTest minus the Java baseline is BreakTest's own overhead. The Java baseline minus the
Rust baseline is what the JVM and HttpClient5 cost.

## Scenarios (`SCENARIOS`) and protocols (`PROTOCOLS`)

| Scenario            | Model                                                                        |
|---------------------|------------------------------------------------------------------------------|
| `keepalive`         | `THREADS` users, each pacing one request every `THREADS * 1000 / rate` ms on its own kept-alive connection |
| `keepalive-extract` | BreakTest only: as `keepalive` plus a regex extractor and a response assertion |
| `newconn`           | open model, a new connection (and full TLS handshake) for every request      |

| Protocol | Stub port                                                                 |
|----------|---------------------------------------------------------------------------|
| `http`   | plain HTTP/1.1                                                            |
| `https`  | TLS, HTTP/1.1 only                                                        |
| `h2`     | TLS offering HTTP/2 through ALPN; skipped for tools without an HTTP/2 client |

Every tool starts one user per millisecond, so requests spread evenly over the pacing
interval. That matters: users that fire at the same instant can double the CPU per request
of any thread-per-user tool. The stub refuses TLS session resumption, so a new connection
is a full handshake for every tool.

`BENCH_PATH=/gz BODY_BYTES=20000` serves a 20 KB JSON-like body gzip-encoded, which is
closer to real API traffic than the default 1 KB plain body. With tiny plain bodies most of
the CPU is the operating system waking threads, and the tools' own work hardly shows.

## Running

```bash
./gradlew createDist
```

```bash
(cd extras/http-cpu-bench && cargo build --release)
```

```bash
RATES=5000 SCENARIOS=keepalive PROTOCOLS="http https h2" BODY_BYTES=20000 BENCH_PATH=/gz \
  TOOLS="rust-raw rust breaktest breaktest-before" extras/http-cpu-bench/run.sh
```

Results go to `extras/http-cpu-bench/results/<timestamp>/` (`results.csv`, `env.txt`,
per-run logs). All settings are environment variables, documented at the top of `run.sh`.
Tools other than BreakTest and the baselines need `mvn`, `k6`, `locust` or `JMETER_HOME`
only when listed in `TOOLS`.

`PROFILE=1` also profiles each BreakTest, JMeter and Java baseline run with async-profiler
(`asprof` and `jfrconv` on `PATH`) and writes CPU and allocation flame graphs. Profiling
adds overhead, so take CPU numbers from a run without it.

## Getting numbers you can trust

- The stub runs on `STUB_WORKERS` threads (default 2) on the same machine; check that
  `stub_cores` stays well below that. On Linux, keep load and stub apart with
  `STUB_CPUS=0-1 LOAD_CPUS=2-7`. macOS has no CPU pinning and its thread wake-ups are
  expensive, so treat laptop numbers as indicative.
- Compare rows from the same invocation. The same configuration has varied by 10–25%
  between sessions on a busy machine.
- At thousands of requests per second CPU per request settles within seconds, so the
  default 10 s warmup and 15 s window are enough. Repeat a configuration to see the spread.
- A tool that overruns its window by `GRACE` seconds is killed and its row marked
  `killed`. An open-model run that cannot keep up queues users until the heap runs out;
  BreakTest then exits on the `OutOfMemoryError` and the row is marked `oom`.
