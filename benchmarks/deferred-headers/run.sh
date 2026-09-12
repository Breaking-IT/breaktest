#!/usr/bin/env bash
# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
: "${JAVA_HOME:?Set JAVA_HOME to the benchmark JDK}"
jar_path=$(ls src/protocol/http/build/libs/*-jmh.jar)
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -jar "$jar_path" HeaderCaptureBenchmark \
  -jvmArgsAppend "${HEADER_JVM_ARGS:--Xms512m -Xmx512m -Djdk.net.hosts.file=/etc/hosts}" \
  -f 2 -wi 3 -i 4 -w 500ms -r 500ms -t 1 \
  -prof gc -prof org.apache.jmeter.protocol.http.sampler.HeaderProcessCpuProfiler \
  -rf json -rff "${HEADER_RESULTS_PATH:-benchmarks/deferred-headers/results/matrix.json}" "$@"
