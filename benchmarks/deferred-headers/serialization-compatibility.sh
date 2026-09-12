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
compat_dir=$(mktemp -d)
trap 'rm -rf "$compat_dir"' EXIT
mkdir -p "$compat_dir/old" "$compat_dir/probe"
for entry in 'core SampleResult' 'protocol/http HTTPSampleResult'; do
    read -r module class_name <<< "$entry"
    if [[ "$module" == core ]]; then package_path=org/apache/jmeter/samplers; else package_path=org/apache/jmeter/protocol/http/sampler; fi
    git show "f6b0c11dc:src/$module/src/main/java/$package_path/$class_name.java" > "$compat_dir/$class_name.java"
done
"$JAVA_HOME/bin/javac" -proc:none -cp "$jar_path" -d "$compat_dir/old" "$compat_dir/SampleResult.java" "$compat_dir/HTTPSampleResult.java"
"$JAVA_HOME/bin/javac" -proc:none -cp "$jar_path" -d "$compat_dir/probe" benchmarks/deferred-headers/SerializationCompatibility.java
probe=org.apache.jmeter.protocol.http.sampler.SerializationCompatibility
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -cp "$compat_dir/probe:$jar_path" "$probe" deferred "$compat_dir/new.bin"
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -cp "$compat_dir/old:$compat_dir/probe:$jar_path" "$probe" read "$compat_dir/new.bin"
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -cp "$compat_dir/old:$compat_dir/probe:$jar_path" "$probe" eager "$compat_dir/old.bin"
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file=/etc/hosts -cp "$compat_dir/probe:$jar_path" "$probe" read "$compat_dir/old.bin"
