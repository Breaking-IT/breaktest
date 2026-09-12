#!/usr/bin/env bash
# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
jar_path=$(ls src/protocol/http/build/libs/*-jmh.jar)
probe_dir=$(mktemp -d)
trap 'rm -rf "$probe_dir"' EXIT
"$JAVA_HOME/bin/javac" -proc:none -cp "$jar_path" -d "$probe_dir" benchmarks/deferred-headers/SamplerIntegrationProbe.java
for deferred in default false true; do
  "$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -cp "$probe_dir:$jar_path" \
    org.apache.jmeter.protocol.http.sampler.SamplerIntegrationProbe "$deferred"
done
