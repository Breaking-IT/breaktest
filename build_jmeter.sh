#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to you under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Build JMeter into the repo-root bin/ and lib/ layout (Gradle createDist).
# After this succeeds, you can run bin/jmeter.sh or package with create_jmeter_archive.sh.
#
# Usage:
#   ./build_jmeter.sh              # compile and sync jars (default)
#   ./build_jmeter.sh --clean      # ./gradlew clean createDist
#   ./build_jmeter.sh --full       # ./gradlew build then createDist (includes tests)
#   ./build_jmeter.sh -- --scan    # extra args passed to Gradle (after --)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [[ ! -x ./gradlew ]]; then
    echo "Error: ./gradlew not found or not executable in ${ROOT}" >&2
    exit 1
fi

CLEAN=0
FULL=0
GRADLE_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            cat <<'EOF'
Build JMeter (Gradle) into repo-root bin/ and lib/ via createDist.

Usage:
  ./build_jmeter.sh              compile and sync jars (default)
  ./build_jmeter.sh --clean      ./gradlew clean createDist
  ./build_jmeter.sh --full       ./gradlew build then createDist (includes tests)
  ./build_jmeter.sh --full --clean   clean, then build, then createDist
  ./build_jmeter.sh -- --scan    extra Gradle args after --

Then: ./bin/jmeter.sh  or  ./create_jmeter_archive.sh
EOF
            exit 0
            ;;
        --clean)
            CLEAN=1
            shift
            ;;
        --full)
            FULL=1
            shift
            ;;
        --)
            shift
            GRADLE_ARGS+=("$@")
            break
            ;;
        *)
            GRADLE_ARGS+=("$1")
            shift
            ;;
    esac
done

if [[ "$(uname -s)" != Darwin ]] && [[ -z "${DISPLAY:-}" ]] && [[ -z "${JAVA_TOOL_OPTIONS:-}" || "${JAVA_TOOL_OPTIONS}" != *headless* ]]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }-Djava.awt.headless=true"
fi

GRADLE_CACHE_ARGS=()
if [[ "$CLEAN" -eq 1 ]]; then
  stop_conflicting_gradle_daemons() {
    # Cursor/VS Code Java tooling can start Gradle 8.9 for this workspace while
    # the wrapper uses Gradle 9.2.1. That recreates included-build task history
    # with older Kotlin DSL metadata during clean builds.
    pkill -f "GradleDaemon 8.9" >/dev/null 2>&1 || true
    pkill -f "kotlin-compiler-embeddable/1.9.23" >/dev/null 2>&1 || true
  }

  # Stale included-build metadata breaks kotlin-dsl accessor generation. The IDE Gradle
  # extension (vscode-gradle) often runs Gradle 8.9 against build-logic while the wrapper
  # uses 9.2.1, which recreates build-logic/.gradle/8.9/ and corrupts clean builds.
  echo "Stopping Gradle daemons and clearing included-build caches…"
  ./gradlew --stop >/dev/null 2>&1 || true
  stop_conflicting_gradle_daemons
  (
    while :; do
      stop_conflicting_gradle_daemons
      sleep 1
    done
  ) &
  GRADLE_GUARD_PID=$!
  trap 'kill "$GRADLE_GUARD_PID" >/dev/null 2>&1 || true' EXIT
  rm -rf .gradle-build_jmeter \
    build-logic/.gradle build-logic-commons/.gradle \
    build-logic/.kotlin build-logic-commons/.kotlin \
    build-logic-commons/gradle-plugin/build
  GRADLE_CACHE_ARGS=(
            --project-cache-dir .gradle-build_jmeter
            --no-daemon
            --no-build-cache
            -Dorg.gradle.parallel=false
            -Dorg.gradle.vfs.watch=false
    -Pkotlin.compiler.execution.strategy=in-process
    -Pkotlin.daemon.jvmargs=-Xmx2g
  )
fi

if [[ "$FULL" -eq 1 ]]; then
    if [[ "$CLEAN" -eq 1 ]]; then
        echo "Clean + full build (tests)…"
        ./gradlew "${GRADLE_CACHE_ARGS[@]}" "${GRADLE_ARGS[@]}" clean build
    else
        echo "Running full build (tests)…"
        ./gradlew "${GRADLE_CACHE_ARGS[@]}" "${GRADLE_ARGS[@]}" build
    fi
    echo "Refreshing bin/ and lib/…"
    ./gradlew "${GRADLE_CACHE_ARGS[@]}" "${GRADLE_ARGS[@]}" createDist
else
    if [[ "$CLEAN" -eq 1 ]]; then
        ./gradlew "${GRADLE_CACHE_ARGS[@]}" "${GRADLE_ARGS[@]}" clean createDist
    else
        ./gradlew "${GRADLE_CACHE_ARGS[@]}" "${GRADLE_ARGS[@]}" createDist
    fi
fi

echo "Done. Run JMeter from ${ROOT}/bin/jmeter.sh or archive with ${ROOT}/create_jmeter_archive.sh"
