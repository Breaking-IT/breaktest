import { readdir, stat, unlink } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Restored entries can outlive Gradle's access journal on ephemeral runners.
// Bound each snapshot; removing an entry only causes that task to rebuild.
export async function trimCache(directory, maxBytes = 256 * 1024 * 1024) {
  let files;
  try {
    files = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return { keptBytes: 0, removed: 0 };
    throw error;
  }
  const entries = [];
  for (const file of files) {
    if (!file.isFile()) continue;
    const path = join(directory, file.name);
    if (file.name === 'gc.properties' || file.name.endsWith('.lock')) {
      // The workflow waits for the single-use Gradle daemon to exit before this.
      await unlink(path);
    } else if (/^[a-f0-9]{32}$/.test(file.name)) {
      const { size, mtimeMs } = await stat(path);
      entries.push({ path, size, mtimeMs });
    }
  }
  entries.sort((a, b) => b.mtimeMs - a.mtimeMs || a.path.localeCompare(b.path));
  let keptBytes = 0;
  let removed = 0;
  for (const entry of entries) {
    if (keptBytes + entry.size > maxBytes) {
      await unlink(entry.path);
      removed++;
    } else {
      keptBytes += entry.size;
    }
  }
  return { keptBytes, removed };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = await trimCache(join(homedir(), '.gradle/caches/build-cache-1'));
  console.log(`Gradle cache: kept ${result.keptBytes} bytes; removed ${result.removed} old/oversized entries`);
}
