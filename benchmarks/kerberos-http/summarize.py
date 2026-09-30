#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Summarize four independent paired JMH forks with descriptive paired intervals."""
import collections
import json
import math
import pathlib
import statistics
import sys

root = pathlib.Path(sys.argv[1])
groups = collections.defaultdict(dict)
for path in sorted(root.glob('r*.json')):
    raw = path.read_text()
    if not raw.strip():
        continue
    content = json.loads(raw)
    if not content:
        continue
    result = content[0]
    repeat, _, _, _, variant = path.stem.split('-')
    key = (result['params']['protocol'], result['threads'], result['params']['authentication'])
    metrics = result['secondaryMetrics']
    values = {'ops/s': metrics['request.rate']['score'], 'mean us': result['primaryMetric']['score'],
              'p95 us': result['primaryMetric']['scorePercentiles']['95.0'],
              'CPU ns/op': metrics['process.cpu']['score'],
              'bytes/op': metrics['gc.alloc.rate.norm']['score']}
    groups[key][repeat, variant] = values
summary = []
for key, runs in sorted(groups.items()):
    repeats = sorted({r for r, v in runs if (r, 'baseline') in runs and (r, 'changed') in runs})
    if not repeats:
        continue
    item = {'protocol': key[0], 'threads': key[1], 'auth': key[2], 'pairs': len(repeats), 'metrics': {}}
    for metric in runs[repeats[0], 'baseline']:
        before = [runs[r, 'baseline'][metric] for r in repeats]
        after = [runs[r, 'changed'][metric] for r in repeats]
        ratios = [math.log(b/a) for a, b in zip(before, after)]
        mean = statistics.mean(ratios)
        # Student-t paired interval on log ratios; exploratory with only four forks.
        critical = {2: 12.706, 3: 4.303, 4: 3.182, 5: 2.776, 6: 2.571, 8: 2.365}.get(len(repeats))
        interval = None
        if critical:
            margin = critical * statistics.stdev(ratios) / math.sqrt(len(ratios))
            interval = [100*math.expm1(mean-margin), 100*math.expm1(mean+margin)]
        item['metrics'][metric] = {'baseline_mean': statistics.mean(before), 'changed_mean': statistics.mean(after),
                                  'paired_change_percent': 100*math.expm1(mean), 'paired_95_percent_interval': interval,
                                  'baseline_runs': before, 'changed_runs': after}
    summary.append(item)
(root/'summary.json').write_text(json.dumps(summary, indent=2))
lines = ['| Protocol | Clients | Auth | Pairs | Original req/s | Changed req/s | Paired change | 95% interval |',
         '|---|---:|---|---:|---:|---:|---:|---|']
for item in summary:
    m = item['metrics']['ops/s']
    ci = m['paired_95_percent_interval']
    interval = f'{ci[0]:+.1f}% to {ci[1]:+.1f}%' if ci else 'pending'
    lines.append(f'| {item["protocol"]} | {item["threads"]} | {item["auth"]} | {item["pairs"]} | '
                 f'{m["baseline_mean"]:.0f} | {m["changed_mean"]:.0f} | {m["paired_change_percent"]:+.1f}% | {interval} |')
(root/'summary.md').write_text('\n'.join(lines)+'\n')
print('\n'.join(lines))
