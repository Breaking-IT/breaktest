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
printf 'Premain-Class: org.apache.jmeter.protocol.http.sampler.HeaderRetentionProbe\n' > "$probe_dir/MANIFEST.MF"
"$JAVA_HOME/bin/jar" cfm "$probe_dir/agent.jar" "$probe_dir/MANIFEST.MF" -C src/protocol/http/build/classes/java/jmh org/apache/jmeter/protocol/http/sampler/HeaderRetentionProbe.class
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -javaagent:"$probe_dir/agent.jar" \
  --add-opens java.base/java.lang=ALL-UNNAMED -cp "$jar_path" \
  org.apache.jmeter.protocol.http.sampler.HeaderRetentionProbe

mkdir -p "$probe_dir/old"
git show f6b0c11dc:src/protocol/http/src/main/java/org/apache/jmeter/protocol/http/sampler/HTTPSampleResult.java > "$probe_dir/HTTPSampleResult.java"
"$JAVA_HOME/bin/javac" -proc:none -cp "$jar_path" -d "$probe_dir/old" "$probe_dir/HTTPSampleResult.java"
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -javaagent:"$probe_dir/agent.jar" \
  -cp "$probe_dir/old:$jar_path" org.apache.jmeter.protocol.http.sampler.HeaderRetentionProbe baseline
