#!/usr/bin/env bash
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

set -euo pipefail
cd "$(dirname "$0")/../.."
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}"
probe_dir="$PWD/build/cache-vary-probe"
mkdir -p "$probe_dir/baseline" "$probe_dir/probe" "$probe_dir/source"
./gradlew -I benchmarks/cache-vary/classpath.gradle :src:protocol:http:cacheVaryClasspath "-Dcache.vary.classpath=$probe_dir/classpath.txt" > "$probe_dir/build.log" 2>&1
probe_cp=$(cat "$probe_dir/classpath.txt")
git show f6b0c11dc:src/protocol/http/src/main/java/org/apache/jmeter/protocol/http/control/CacheManager.java > "$probe_dir/source/CacheManager.java"
"$JAVA_HOME/bin/javac" -cp "$probe_cp" -d "$probe_dir/baseline" "$probe_dir/source/CacheManager.java"
"$JAVA_HOME/bin/javac" -cp "$probe_cp" -d "$probe_dir/probe" benchmarks/cache-vary/CacheVaryProbe.java
for fork in 1 2 3; do
    for transport in hc5 url; do
        for fixture in "4 100" "16 400" "16 2048"; do
            read -r count bytes <<< "$fixture"
            for vary in false true; do
                # Alternate baseline/candidate ordering between forks.
                variants="baseline candidate"
                if [[ "$fork" == 2 ]]; then variants="candidate baseline"; fi
                for variant in $variants; do
                    run_cp="$probe_dir/probe:$probe_cp"
                    if [[ "$variant" == baseline ]]; then run_cp="$probe_dir/baseline:$run_cp"; fi
                    "$JAVA_HOME/bin/java" -Xms256m -Xmx256m -Djdk.net.hosts.file=/etc/hosts -cp "$run_cp" CacheVaryProbe "$transport" "$count" "$bytes" "$vary" > "$probe_dir/$variant-$fork-$transport-$bytes-$vary.log" 2>&1
                done
            done
        done
    done
done
python3 - "$probe_dir" <<'PYTHON'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
with (p / 'results.csv').open('w') as out:
    out.write('variant,fork,transport,headers,bytes,vary,round,ns_per_op,bytes_per_op\n')
    for f in sorted(p.glob('*.log')):
        for line in f.read_text().splitlines():
            if line.startswith('DATA,'):
                variant, fork, *_ = f.stem.split('-')
                out.write(f'{variant},{fork},' + line[5:] + '\n')
print(p / 'results.csv')
PYTHON
