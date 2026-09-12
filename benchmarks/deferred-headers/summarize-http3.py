#!/usr/bin/env python3
# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.

"""Validate and summarize the Java HTTP/3 capture matrix."""
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parent
rows = json.loads((root / 'results/http3-matrix.json').read_text())
assert len(rows) == 42, f'Incomplete matrix: {len(rows)} rows'
lookup = {}
for row in rows:
    assert row['params']['protocol'] == 'h3'
    for metric in [row['primaryMetric'], row['secondaryMetrics']['process.cpu'],
                   row['secondaryMetrics']['gc.alloc.rate.norm']]:
        assert len(metric['rawData']) == 2 and all(len(fork) == 4 for fork in metric['rawData'])
        assert all(math.isfinite(v) and v > 0 for fork in metric['rawData'] for v in fork)
    key = (int(row['params']['totalBytes']), row['params']['readers'], row['params']['deferred'])
    assert key not in lookup
    lookup[key] = row
lines = [
    '# Java HTTP/3 header capture measurements', '',
    'See [method and interpretation](HTTP3.md). CPU is process ns/op; ± is the JMH 99.9% confidence interval half-width. '
    'Wall time is ns/op; allocation is B/op. Positive changes mean deferral costs more.', '',
    '| Fixture bytes | Readers | Eager CPU | Deferred CPU | CPU change | Eager → deferred wall | Eager → deferred allocation |',
    '|---:|---|---:|---:|---:|---:|---:|'
]
for size in [400, 1600, 8192]:
    for reader in ['unread', 'request', 'response', 'both25', 'both50', 'both75', 'both100']:
        e = lookup[size, reader, 'false']
        d = lookup[size, reader, 'true']
        ec = e['secondaryMetrics']['process.cpu']
        dc = d['secondaryMetrics']['process.cpu']
        ea = e['secondaryMetrics']['gc.alloc.rate.norm']['score']
        da = d['secondaryMetrics']['gc.alloc.rate.norm']['score']
        change = 100 * (dc['score'] / ec['score'] - 1)
        lines.append(f"| {size:,} | {reader} | {ec['score']:.1f} ± {ec['scoreError']:.1f} | "
                     f"{dc['score']:.1f} ± {dc['scoreError']:.1f} | {change:+.1f}% | "
                     f"{e['primaryMetric']['score']:.1f} → {d['primaryMetric']['score']:.1f} | {ea:.0f} → {da:.0f} |")
license_text = (root / 'README.md').read_text().split('-->')[0] + '-->\n\n'
(root / 'HTTP3-RESULTS.md').write_text(license_text + '\n'.join(lines) + '\n')
print('Validated all 42 configurations, two forks × four measurements each.')
