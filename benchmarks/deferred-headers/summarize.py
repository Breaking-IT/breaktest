#!/usr/bin/env python3
# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.

"""Summarize measured pairs; retain raw JMH uncertainty instead of hiding it."""
import csv
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parent
rows = json.loads((root / 'results/matrix.json').read_text())
assert len(rows) == 84, f'Incomplete matrix: {len(rows)} rows'
for row in rows:
    for metric in [row['primaryMetric'], row['secondaryMetrics']['process.cpu'],
                   row['secondaryMetrics']['gc.alloc.rate.norm']]:
        assert len(metric['rawData']) == 2 and all(len(fork) == 4 for fork in metric['rawData'])
        assert all(math.isfinite(v) and v > 0 for fork in metric['rawData'] for v in fork)
lookup = {(r['params']['protocol'], int(r['params']['totalBytes']), r['params']['readers'],
           r['params']['deferred']): r for r in rows}
readers = ['unread', 'request', 'response', 'both25', 'both50', 'both75', 'both100']
lines = ['<!--\nCopyright 2024-2026 Breaking IT\n\nLicensed under the BreakTest Community Source License 1.0.\nYou may not use this file except in compliance with that license.\nSee the LICENSE file at the root of this distribution.\n-->\n\n', '# Current JDK 21 measurements', '',
         'CPU is process ns/op (mean ± JMH 99.9% CI half-width); allocations are B/op. '
         'Positive CPU change means more CPU with deferral. See README for fixture sizes and limits.', '',
         '| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |',
         '|---|---:|---|---:|---:|---:|---:|']
flat = []
for protocol in ['h1', 'h2']:
    for total in [400, 1600, 8192]:
        differences = []
        for reader in readers:
            e = lookup[protocol, total, reader, 'false']
            d = lookup[protocol, total, reader, 'true']
            ec = e['secondaryMetrics']['process.cpu']; dc = d['secondaryMetrics']['process.cpu']
            ea = e['secondaryMetrics']['gc.alloc.rate.norm']['score']
            da = d['secondaryMetrics']['gc.alloc.rate.norm']['score']
            change = 100 * (dc['score'] / ec['score'] - 1)
            lines.append(f"| {protocol} | {total:,} | {reader} | {ec['score']:.1f} ± {ec['scoreError']:.1f} "
                         f"| {dc['score']:.1f} ± {dc['scoreError']:.1f} | {change:+.1f}% | {ea:.0f} → {da:.0f} |")
            flat.append([protocol, total, reader, ec['score'], dc['score'], ec['scoreError'], dc['scoreError'],
                         e['primaryMetric']['score'], d['primaryMetric']['score'], ea, da])
            if reader == 'unread' or reader.startswith('both'):
                fraction = 0 if reader == 'unread' else int(reader[4:]) / 100
                differences.append((fraction, dc['score'] - ec['score']))
        crossings = []
        for (p0, d0), (p1, d1) in zip(differences, differences[1:]):
            if d0 * d1 < 0:
                crossings.append(f'{100*p0:.0f}–{100*p1:.0f}% (linear estimate {100*(p0-d0*(p1-p0)/(d1-d0)):.0f}%)')
        lines.append('')
        lines.append(f'**{protocol}, {total:,} bytes:** sampled mean CPU sign crossings: '
                     + (', '.join(crossings) if crossings else 'none in the 0–100% both-read sweep')
                     + '. These are fixture-specific estimates, not universal thresholds; inspect the intervals.')
        lines.append('')
        if protocol != 'h2' or total != 8192:
            lines += ['| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |',
                      '|---|---:|---|---:|---:|---:|---:|']
(root / 'RESULTS.md').write_text('\n'.join(lines) + '\n')
with (root / 'results/paired.csv').open('w') as f:
    writer = csv.writer(f, lineterminator="\n")
    writer.writerow(['protocol','totalBytes','consumers','eagerCpuNs','deferredCpuNs','eagerCpuError','deferredCpuError',
                     'eagerWallNs','deferredWallNs','eagerBytes','deferredBytes'])
    writer.writerows(flat)
print('\n'.join(lines))

fixed_path = root / 'results/fixed-heap.json'
if fixed_path.exists() and fixed_path.stat().st_size:
    fixed = json.loads(fixed_path.read_text())
    assert len(fixed) == 36, f'Incomplete confirmation: {len(fixed)} rows'
    for row in fixed:
        assert '-Xms512m' in row['jvmArgs'] and '-Xmx512m' in row['jvmArgs']
        for metric in [row['primaryMetric'], row['secondaryMetrics']['process.cpu'],
                       row['secondaryMetrics']['gc.alloc.rate.norm']]:
            assert len(metric['rawData']) == 2 and all(len(fork) == 4 for fork in metric['rawData'])
            assert all(math.isfinite(v) and v > 0 for fork in metric['rawData'] for v in fork)
    index = {(r['params']['protocol'], int(r['params']['totalBytes']), r['params']['readers'],
              r['params']['deferred']): r for r in fixed}
    extra = ['\n## Explicit 512 MiB heap confirmation\n',
             '| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |',
             '|---|---:|---|---:|---:|---:|---:|']
    for protocol in ['h1', 'h2']:
        for total in [400, 1600, 8192]:
            for reader in ['unread', 'both50', 'both100']:
                e = index[protocol, total, reader, 'false']['secondaryMetrics']
                d = index[protocol, total, reader, 'true']['secondaryMetrics']
                ec = e['process.cpu']; dc = d['process.cpu']
                ea = e['gc.alloc.rate.norm']['score']; da = d['gc.alloc.rate.norm']['score']
                extra.append(f"| {protocol} | {total:,} | {reader} | {ec['score']:.1f} ± {ec['scoreError']:.1f} "
                             f"| {dc['score']:.1f} ± {dc['scoreError']:.1f} | {100*(dc['score']/ec['score']-1):+.1f}% "
                             f"| {ea:.0f} → {da:.0f} |")
    with (root / 'RESULTS.md').open('a') as out:
        out.write('\n'.join(extra)+'\n')
    print('\n'.join(extra))
