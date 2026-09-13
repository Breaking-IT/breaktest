import assert from 'node:assert/strict';
import { mkdtemp, writeFile, utimes, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { trimCache } from './trim-gradle-cache.mjs';

test('keeps recent entries within the budget and removes transient metadata', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'gradle-cache-'));
  try {
    for (const [digit, size, timestamp] of [['a', 4, 1], ['b', 6, 2], ['c', 7, 3]]) {
      const path = join(directory, digit.repeat(32));
      await writeFile(path, 'x'.repeat(size));
      await utimes(path, timestamp, timestamp);
    }
    await writeFile(join(directory, 'gc.properties'), 'cleanup state');
    await writeFile(join(directory, 'build-cache-1.lock'), 'lock state');
    const result = await trimCache(directory, 10);
    assert.deepEqual(result, { keptBytes: 7, removed: 2 });
    assert.deepEqual(await readdir(directory), ['c'.repeat(32)]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('drops an oversized entry without discarding smaller reusable entries', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'gradle-cache-'));
  try {
    await writeFile(join(directory, 'a'.repeat(32)), 'x'.repeat(11));
    await writeFile(join(directory, 'b'.repeat(32)), 'x'.repeat(4));
    assert.deepEqual(await trimCache(directory, 10), { keptBytes: 4, removed: 1 });
    assert.deepEqual(await readdir(directory), ['b'.repeat(32)]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('handles a failed build that did not create a cache', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'gradle-cache-'));
  try {
    assert.deepEqual(await trimCache(join(directory, 'missing')), { keptBytes: 0, removed: 0 });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
