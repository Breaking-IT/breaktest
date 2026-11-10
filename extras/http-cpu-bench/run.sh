#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to you under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# CPU-per-request benchmark: BreakTest vs other load tools and lean baseline clients, against the same Rust stub.
#
# For every protocol x scenario x rate x tool it starts the load generator, waits WARMUP
# seconds (JIT, connection setup), then measures for DURATION seconds:
#   - CPU seconds used by the load generator's process tree in that window
#   - requests the stub served in that window (its own counter, not the tool's report)
# and reports CPU microseconds per request. Startup and warmup CPU are excluded.
#
# Settings (environment variables):
#   RATES="2000 10000"        target requests/s
#   SCENARIOS="keepalive keepalive-extract newconn"
#   PROTOCOLS="http https"    also h2: https on a stub port that offers HTTP/2 through ALPN; only tools
#                             that negotiate HTTP/2 use it (BreakTest default, java-async*, k6)
#   TOOLS="breaktest java-async rust"
#                             breaktest      default sampler (HttpClient5 async, negotiated version)
#                             breaktest-h11  sampler forced to HTTP/1.1 (HttpClient5 classic)
#                             breaktest-headless  default sampler, started with plain java -jar instead of bin/breaktest
#                             java-async     JavaLoadgen.java, shared HttpClient5 async client
#                             java-classic   JavaLoadgen.java, shared HttpClient5 classic client
#                             java-async-per-user  JavaLoadgen.java, one async client per user with a
#                                            virtual-thread reactor, like BreakTest's HTTPHC5H2Impl
#                             java-async-per-user-adaptor  as above, driven through ClassicToAsyncAdaptor
#                             rust           bench-loadgen (hyper + rustls); decompresses gzip bodies
#                             rust-raw       bench-loadgen --no-decompress: leaves gzip bodies encoded
#                             gatling        gatling/ via mvn gatling:test (Maven's own JVM counts too)
#                             k6             k6/bench.js
#                             locust         locust/locustfile.py, FastHttpUser
#                             jmeter         Apache JMeter (plans/jmeter/), needs JMETER_HOME; JMETER_JAVA_HOME
#                                            picks its JDK (JMeter 5.6.3 predates JDK 27)
#                             breaktest-before  BreakTest with this work's HTTP client changes switched off
#   LOCUST_PROCESSES=4        Locust worker processes (one Python process uses at most one core)
#   THREADS=1000              users/connections per tool; each paces THREADS*1000/rate ms. Every tool
#                             starts one user per millisecond, so start times spread evenly over
#                             the pacing interval (BreakTest timing is whole milliseconds).
#   WARMUP=10 DURATION=15     seconds; at thousands of req/s CPU per request settles fast
#   STUB_WORKERS=2            stub tokio threads; keep the stub well below saturation
#   BODY_BYTES=1024
#   HEAP="-Xms1g -Xmx1g"      BreakTest heap (passed through to bin/breaktest)
#   JVM_ARGS=...              extra BreakTest JVM args (an OOM always exits the JVM)
#   BREAKTEST_ARGS=...        extra BreakTest arguments, e.g. "-Jhttpsampler.http2.virtual_reactor_threads=false"
#   TLS_PROVIDER=jdk          jdk or conscrypt: TLS for BreakTest and the Java baselines (rows get a
#                             +conscrypt suffix); conscrypt needs CONSCRYPT_JAR=path/to/conscrypt-openjdk-uber.jar
#                             for the Java baselines, and that jar copied into BreakTest's lib/
#   RESPONSE_MODE=store_compressed  BreakTest response processing: store_compressed (default),
#                             fetch_discard or store_on_error (both discard bodies when no assertion
#                             or post-processor needs them); rows get a +<mode> suffix when not default
#   BENCH_PATH=/              request path for every tool; /gz serves the body gzip-encoded
#                             (rows get a +gzip suffix). BODY_BYTES sets the uncompressed size.
#   LABEL_SUFFIX=...          extra suffix for this invocation's rows, e.g. platform-threads
#   GRACE=30                  seconds a tool may overrun before it is killed
#   PROFILE=1                 also profile each BreakTest and Java baseline run with async-profiler
#                             (CPU + allocation): <run>.jfr, <run>-cpu.html, <run>-alloc.html and
#                             <run>-cpu.collapsed. Needs asprof and jfrconv on PATH; inflates CPU numbers.
#   STUB_CPUS / LOAD_CPUS     Linux only: taskset CPU lists to keep stub and load apart
#   OUT=results/<timestamp>

set -euo pipefail

BENCH_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$BENCH_DIR/../.." && pwd)

RATES=${RATES:-"2000 10000"}
SCENARIOS=${SCENARIOS:-"keepalive keepalive-extract newconn"}
PROTOCOLS=${PROTOCOLS:-"http https"}
TOOLS=${TOOLS:-"breaktest java-async rust"}
THREADS=${THREADS:-1000}
WARMUP=${WARMUP:-10}
DURATION=${DURATION:-15}
GRACE=${GRACE:-30}
STUB_WORKERS=${STUB_WORKERS:-2}
BODY_BYTES=${BODY_BYTES:-1024}
HTTP_PORT=${HTTP_PORT:-18080}
HTTPS_PORT=${HTTPS_PORT:-18443}
H2_PORT=${H2_PORT:-18444}
TLS_PROVIDER=${TLS_PROVIDER:-jdk}
RESPONSE_MODE=${RESPONSE_MODE:-store_compressed}
BENCH_PATH=${BENCH_PATH:-/}
OUT=${OUT:-"$BENCH_DIR/results/$(date +%Y%m%d-%H%M%S)"}
export HEAP=${HEAP:-"-Xms1g -Xmx1g"}
# An overloaded open model can queue users until the heap runs out; exit instead of hanging
# and skip the launcher's heap dump (it comes after the launcher's flags, so it wins).
export JVM_ARGS="-XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError ${JVM_ARGS:-}"

STUB="$BENCH_DIR/target/release/bench-stub"
LOADGEN="$BENCH_DIR/target/release/bench-loadgen"
BREAKTEST="$ROOT/bin/breaktest"

log() { printf '%s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

pin() {
    # pin <cpu-list> cmd...: run under taskset when a CPU list is given and taskset exists.
    local cpus=$1; shift
    if [ -n "$cpus" ] && command -v taskset >/dev/null; then
        taskset -c "$cpus" "$@"
    else
        "$@"
    fi
}

# CPU seconds (user+sys) of a process; macOS ps reports [dd-][hh:]mm:ss.cc
proc_cpu() {
    local pid=$1
    if [ -r "/proc/$pid/stat" ]; then
        # Fields 14 and 15 are utime and stime in clock ticks; the comm field may contain spaces.
        awk -v hz="$(getconf CLK_TCK)" '{ sub(/^.*\) /, ""); printf "%.3f\n", ($12 + $13) / hz }' "/proc/$pid/stat" 2>/dev/null || echo 0
    else
        ps -o time= -p "$pid" 2>/dev/null | awk '{
            t = $1; d = 0
            if (index(t, "-")) { split(t, a, "-"); d = a[1]; t = a[2] }
            n = split(t, p, ":"); s = 0
            for (i = 1; i <= n; i++) s = s * 60 + p[i]
            printf "%.3f\n", d * 86400 + s }' || echo 0
    fi
}

descendants() {
    local pid=$1 child
    echo "$pid"
    for child in $(pgrep -P "$pid" 2>/dev/null || true); do
        descendants "$child"
    done
}

tree_cpu() {
    local total=0 pid
    for pid in $(descendants "$1"); do
        total=$(awk -v a="$total" -v b="$(proc_cpu "$pid")" 'BEGIN { printf "%.3f", a + b }')
    done
    echo "$total"
}

kill_tree() {
    local pid
    for pid in $(descendants "$1"); do
        kill -9 "$pid" 2>/dev/null || true
    done
}

# wait_or_kill <pid> <logfile>: wait up to GRACE seconds; prints a note when the run went wrong.
wait_or_kill() {
    local pid=$1 logfile=$2 waited=0 note=""
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$GRACE" ]; do
        sleep 1
        waited=$((waited + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
        kill_tree "$pid"
        note="killed"
    fi
    wait "$pid" 2>/dev/null || true
    grep -q OutOfMemoryError "$logfile.out" "$logfile" 2>/dev/null && note="${note:+$note+}oom"
    echo "$note"
}

stub_count() { curl -sf "http://127.0.0.1:$HTTP_PORT/__stats" || echo 0; }
stub_h2_count() { curl -sf "http://127.0.0.1:$HTTP_PORT/__stats/h2" || echo 0; }

java_pid() {
    local pid
    for pid in $(descendants "$1"); do
        if ps -o comm= -p "$pid" 2>/dev/null | grep -q java; then
            echo "$pid"
            return
        fi
    done
}

pacing_ms() {
    # Closed-model users pace start-to-start in whole milliseconds: THREADS / pacing = rate.
    awk -v t="$THREADS" -v r="$1" 'BEGIN { p = int(t * 1000 / r + 0.5); print (p < 1 ? 1 : p) }'
}

start_tool() {
    # start_tool <tool> <scenario> <protocol> <rate> <port> <logfile>; sets TOOL_PID.
    # Not called through $(...): the tool must be a child of this shell so it can be waited for.
    local tool=$1 scenario=$2 protocol=$3 rate=$4 port=$5 logfile=$6
    local total=$((WARMUP + DURATION + 5))
    case $tool in
        breaktest | breaktest-h11 | breaktest-headless | breaktest-before)
            local http_protocol="" launcher=("$BREAKTEST") before=()
            [ "$tool" = breaktest-h11 ] && http_protocol=HTTP/1.1
            # The HTTP client behaviour from before this benchmark work: no shared reactor, and
            # negotiated cleartext requests on the async client.
            [ "$tool" = breaktest-before ] && before=(-Jhttpsampler.hc5.h2.shared_reactor=false
                -Jhttpsampler.cleartext.http11_impl=false -Jhttpsampler.hc5.h2.direct_async=false
                -Jhttpclient5.http1.buffer_size=32768 -Jhttpclient5.http2.max_frame_size=65536)
            # bin/breaktest registers the JVM as a macOS app (-Xdock), which App Nap may throttle.
            # shellcheck disable=SC2206 # HEAP and JVM_ARGS are flat flag strings
            [ "$tool" = breaktest-headless ] && launcher=(java $HEAP $JVM_ARGS -Djava.awt.headless=true -jar "$ROOT/bin/breaktest.jar")
            pin "${LOAD_CPUS:-}" "${launcher[@]}" -n -t "$BENCH_DIR/plans/$scenario.jmx" -j "$logfile" \
                -Jbench.protocol="$protocol" -Jbench.host=127.0.0.1 -Jbench.port="$port" -Jbench.path="$BENCH_PATH" \
                -Jbench.threads="$THREADS" -Jbench.duration="$total" \
                -Jbench.rate="$rate" -Jbench.pacing_ms="$(pacing_ms "$rate")" \
                -Jbench.http_protocol="$http_protocol" -Jbench.ramp_s="$(( (THREADS + 999) / 1000 ))" \
                -Jhttps.tls_provider="$TLS_PROVIDER" \
                -Jbench.response_mode="response_processing_$RESPONSE_MODE" "${before[@]}" \
                ${BREAKTEST_ARGS:-} \
                >"$logfile.out" 2>&1 &
            ;;
        java-async | java-classic | java-async-per-user | java-async-per-user-adaptor)
            local mode=()
            [ "$scenario" = newconn ] && mode=(--new-connection)
            # Same heap and OOM handling as BreakTest; the source launcher compiles during warmup.
            # shellcheck disable=SC2086 # HEAP and JVM_ARGS are flat flag strings, like in bin/breaktest
            pin "${LOAD_CPUS:-}" java $HEAP $JVM_ARGS -cp "$ROOT/lib/*${CONSCRYPT_JAR:+:$CONSCRYPT_JAR}" \
                "$BENCH_DIR/java/JavaLoadgen.java" --tls-provider "$TLS_PROVIDER" \
                --client "${tool#java-}" --url "$protocol://127.0.0.1:$port$BENCH_PATH" --rate "$rate" --ramp-step-ms 1 \
                --connections "$THREADS" --duration "$total" "${mode[@]}" \
                >"$logfile.out" 2>&1 &
            ;;
        jmeter)
            # Apache JMeter (5.6.3 tested), on its own JDK; HTTP/1.1 only, so no h2 rows.
            JAVA_HOME="${JMETER_JAVA_HOME:-${JAVA_HOME:-}}" pin "${LOAD_CPUS:-}" "$JMETER_HOME/bin/jmeter" -n \
                -t "$BENCH_DIR/plans/jmeter/$scenario.jmx" -j "$logfile" \
                -Jbench.protocol="$protocol" -Jbench.host=127.0.0.1 -Jbench.port="$port" -Jbench.path="$BENCH_PATH" \
                -Jbench.threads="$THREADS" -Jbench.duration="$total" -Jbench.rate="$rate" \
                -Jbench.ramp_s="$(( (THREADS + 999) / 1000 ))" \
                -Jbench.per_user_per_min="$(awk -v p="$(pacing_ms "$rate")" 'BEGIN { printf "%.3f", 60000 / p }')" \
                -Jsummariser.name= \
                >"$logfile.out" 2>&1 &
            ;;
        rust | rust-raw)
            local mode=()
            [ "$scenario" = newconn ] && mode=(--new-connection)
            [ "$port" = "$H2_PORT" ] && mode+=(--http2)
            # rust-raw leaves gzip bodies encoded, as BreakTest does when nothing reads them.
            [ "$tool" = rust-raw ] && mode+=(--no-decompress)
            pin "${LOAD_CPUS:-}" "$LOADGEN" --url "$protocol://127.0.0.1:$port$BENCH_PATH" --rate "$rate" --ramp-step-ms 1 \
                --connections "$THREADS" --duration "$total" "${mode[@]}" \
                >"$logfile.out" 2>&1 &
            ;;
        gatling)
            pin "${LOAD_CPUS:-}" mvn -q -f "$BENCH_DIR/gatling/pom.xml" gatling:test \
                -Dbench.url="$protocol://127.0.0.1:$port$BENCH_PATH" -Dbench.scenario="$scenario" \
                -Dbench.http2="$([ "$port" = "$H2_PORT" ] && echo true || echo false)" \
                -Dbench.threads="$THREADS" -Dbench.rate="$rate" -Dbench.duration="$total" \
                -Dbench.pacing_ms="$(pacing_ms "$rate")" \
                >"$logfile.out" 2>&1 &
            ;;
        k6)
            BENCH_URL="$protocol://127.0.0.1:$port$BENCH_PATH" BENCH_SCENARIO="$scenario" BENCH_THREADS="$THREADS" \
                BENCH_RATE="$rate" BENCH_DURATION="$total" BENCH_PACING_MS="$(pacing_ms "$rate")" \
                pin "${LOAD_CPUS:-}" k6 run -q "$BENCH_DIR/k6/bench.js" \
                >"$logfile.out" 2>&1 &
            ;;
        locust)
            local processes=()
            [ "${LOCUST_PROCESSES:-4}" -gt 1 ] && processes=(--processes "${LOCUST_PROCESSES:-4}")
            BENCH_URL="$protocol://127.0.0.1:$port/" BENCH_SCENARIO="$scenario" BENCH_PATH="$BENCH_PATH" \
                BENCH_PACING_MS="$(pacing_ms "$rate")" \
                pin "${LOAD_CPUS:-}" locust -f "$BENCH_DIR/locust/locustfile.py" --headless --only-summary \
                -u "$THREADS" -r 1000 -t "${total}s" "${processes[@]}" \
                >"$logfile.out" 2>&1 &
            ;;
    esac
    TOOL_PID=$!
}

preflight() {
    # Short BreakTest run with a results file, to prove the extractor scenario really
    # passes its assertion before measuring without any results writer.
    log "preflight: keepalive-extract over http, 3s"
    local jtl="$OUT/preflight.jtl"
    "$BREAKTEST" -n -t "$BENCH_DIR/plans/keepalive-extract.jmx" -j "$OUT/preflight.log" -l "$jtl" \
        -Jjmeter.save.saveservice.output_format=csv -Jbench.port="$HTTP_PORT" \
        -Jbench.threads=5 -Jbench.duration=3 -Jbench.pacing_ms=50 >/dev/null 2>&1 \
        || die "preflight run failed, see $OUT/preflight.log"
    local samples failures
    samples=$(($(wc -l <"$jtl") - 1))
    failures=$(awk -F, 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "success") c = i; next } $c != "true"' "$jtl" | wc -l | tr -d ' ')
    [ "$samples" -gt 0 ] || die "preflight produced no samples, see $OUT/preflight.log"
    [ "$failures" -eq 0 ] || die "preflight: $failures of $samples samples failed, see $jtl"
    log "preflight: $samples samples, all passed"
}

measure() {
    local tool=$1 scenario=$2 protocol=$3 rate=$4
    local port=$HTTP_PORT scheme=$protocol
    case $protocol in
        https) port=$HTTPS_PORT ;;
        h2) port=$H2_PORT scheme=https ;;
    esac
    local label=$tool
    [ "$TLS_PROVIDER" != jdk ] && [[ $tool == breaktest* || $tool == java-* ]] && label="$tool+$TLS_PROVIDER"
    [ "$RESPONSE_MODE" != store_compressed ] && [[ $tool == breaktest* ]] && label="$label+$RESPONSE_MODE"
    [ "$BENCH_PATH" = /gz ] && label="$label+gzip"
    [ -n "${LABEL_SUFFIX:-}" ] && label="$label+$LABEL_SUFFIX"
    local name="$label-$scenario-$protocol-$rate"
    if [[ $tool != rust* && $tool != java-* ]] && [ "$scenario" != newconn ] && [ "$(pacing_ms "$rate")" -lt 10 ]; then
        log "warning: $name paces users every $(pacing_ms "$rate") ms; raise THREADS for a steadier rate"
    fi
    start_tool "$tool" "$scenario" "$scheme" "$rate" "$port" "$OUT/$name.log"
    local pid=$TOOL_PID
    local failure=""

    sleep "$WARMUP"
    if ! kill -0 "$pid" 2>/dev/null; then
        failure=$(wait_or_kill "$pid" "$OUT/$name.log")
        log "warning: $name exited during warmup, see $OUT/$name.log.out"
        echo "$label,$scenario,$protocol,$rate,0,0,0,0,0,exited-in-warmup${failure:++$failure}" | tee -a "$OUT/results.csv"
        return
    fi
    local c0 r0 h0 s0 c1 r1 h1 s1 profiler=""
    c0=$(tree_cpu "$pid"); r0=$(stub_count); h0=$(stub_h2_count); s0=$(tree_cpu "$STUB_PID")
    if [ "${PROFILE:-0}" = 1 ] && [[ $tool == breaktest* || $tool == java-* || $tool == jmeter ]]; then
        asprof -d "$DURATION" -e cpu --alloc 512k -f "$OUT/$name.jfr" "$(java_pid "$pid")" >/dev/null 2>&1 &
        profiler=$!
    fi
    sleep "$DURATION"
    c1=$(tree_cpu "$pid"); r1=$(stub_count); h1=$(stub_h2_count); s1=$(tree_cpu "$STUB_PID")
    if [ -n "$profiler" ]; then
        wait "$profiler" || true
        jfrconv --cpu "$OUT/$name.jfr" "$OUT/$name-cpu.html" >/dev/null 2>&1 || true
        jfrconv --cpu -o collapsed "$OUT/$name.jfr" "$OUT/$name-cpu.collapsed" >/dev/null 2>&1 || true
        jfrconv --alloc "$OUT/$name.jfr" "$OUT/$name-alloc.html" >/dev/null 2>&1 || true
    fi
    failure=$(wait_or_kill "$pid" "$OUT/$name.log")
    [ -n "$failure" ] && log "warning: $name $failure, see $OUT/$name.log.out"

    awk -v tool="$label" -v sc="$scenario" -v pr="$protocol" -v rate="$rate" -v d="$DURATION" \
        -v c="$(awk -v a="$c0" -v b="$c1" 'BEGIN { print b - a }')" \
        -v s="$(awk -v a="$s0" -v b="$s1" 'BEGIN { print b - a }')" \
        -v n="$((r1 - r0))" -v h2="$((h1 - h0))" -v failure="$failure" 'BEGIN {
            got = n / d
            us = n > 0 ? c / n * 1e6 : 0
            note = got < 0.95 * rate ? "below-target" : ""
            if (failure != "") note = note (note == "" ? "" : "+") failure
            # A process that died inside the window loses its CPU counters; its numbers are void.
            if (c < 0) { c = 0; us = 0; note = note (note == "" ? "" : "+") "died-in-window" }
            h2pct = n > 0 ? 100 * h2 / n : 0
            printf "%s,%s,%s,%d,%.0f,%.2f,%.1f,%.2f,%.0f,%s\n", tool, sc, pr, rate, got, c / d, us, s / d, h2pct, note
        }' | tee -a "$OUT/results.csv"
}

[ -x "$STUB" ] && [ -x "$LOADGEN" ] || die "build the Rust tools first: (cd $BENCH_DIR && cargo build --release)"
[ -x "$BREAKTEST" ] && [ -f "$ROOT/bin/breaktest.jar" ] || die "build BreakTest first: ./gradlew createDist"
if [ "${PROFILE:-0}" = 1 ]; then
    command -v asprof >/dev/null && command -v jfrconv >/dev/null || die "PROFILE=1 needs asprof and jfrconv on PATH"
fi

mkdir -p "$OUT"
pin "${STUB_CPUS:-}" "$STUB" --port "$HTTP_PORT" --tls-port "$HTTPS_PORT" --h2-port "$H2_PORT" \
    --workers "$STUB_WORKERS" --body-bytes "$BODY_BYTES" >"$OUT/stub.log" 2>&1 &
STUB_PID=$!
cleanup() {
    # pin runs commands in a subshell on macOS, so stop the whole tree, not just $!.
    local pid
    for pid in $(descendants "$STUB_PID") ${TOOL_PID:+$(descendants "$TOOL_PID")}; do
        kill "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT
for _ in $(seq 50); do curl -sf "http://127.0.0.1:$HTTP_PORT/__stats" >/dev/null && break; sleep 0.1; done
kill -0 "$STUB_PID" 2>/dev/null || die "stub did not start, see $OUT/stub.log"

{
    echo "date=$(date -u +%FT%TZ) host=$(uname -srm) cpus=$(getconf _NPROCESSORS_ONLN)"
    echo "java=$(java -version 2>&1 | head -1)"
    echo "breaktest=$(git -C "$ROOT" rev-parse --short HEAD) threads=$THREADS warmup=${WARMUP}s duration=${DURATION}s"
    echo "stub_workers=$STUB_WORKERS body_bytes=$BODY_BYTES heap=$HEAP jvm_args=${JVM_ARGS:-}"
} >"$OUT/env.txt"
cat "$OUT/env.txt" >&2

preflight
if [[ " $TOOLS " == *" gatling "* ]]; then
    # Compile once up front so the Gatling runs start within the warmup.
    mvn -q -f "$BENCH_DIR/gatling/pom.xml" test-compile >"$OUT/gatling-compile.log" 2>&1 \
        || die "Gatling simulation does not compile, see $OUT/gatling-compile.log"
fi

echo "tool,scenario,protocol,target_rps,achieved_rps,loadgen_cores,cpu_us_per_req,stub_cores,h2_pct,note" >"$OUT/results.csv"
log ""
log "tool,scenario,protocol,target_rps,achieved_rps,loadgen_cores,cpu_us_per_req,stub_cores,h2_pct,note"
for protocol in $PROTOCOLS; do
    for scenario in $SCENARIOS; do
        for rate in $RATES; do
            for tool in $TOOLS; do
                # The baselines have no extractor or assertion; their keepalive rows are the baseline.
                [[ $tool != breaktest* ]] && [ "$scenario" = keepalive-extract ] && continue
                # JMeter 5.6.3 and Locust have no HTTP/2 client.
                [[ $tool == jmeter || $tool == locust ]] && [ "$protocol" = h2 ] && continue
                measure "$tool" "$scenario" "$protocol" "$rate"
            done
        done
    done
done
log ""
log "results: $OUT/results.csv"
