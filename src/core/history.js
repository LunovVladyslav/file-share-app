import fs from 'node:fs';
import path from 'node:path';
import { configDir } from './config.js';

/**
 * Transfers that are over.
 *
 * Until this existed the list on screen was the process's memory: quit the
 * app and every record of what had been sent or received went with it. The
 * only place a completed transfer survived was the download folder, which
 * says what arrived but not from whom, when, or whether the rest of it made
 * it.
 *
 * An entry is a transfer snapshot with the volatile parts removed, so the
 * interface renders a remembered transfer through exactly the same path as a
 * live one — same statuses, same wording, same layout. What is dropped is
 * what cannot mean anything afterwards: the speed reading, the negotiated
 * cipher, the pause flags.
 *
 * The file list goes too, deliberately. A four-thousand-file folder would put
 * a megabyte of names on disk for something nobody reads a second time, and
 * the folder they landed in answers "what did I get" better than a list
 * copied out of it. The Android app makes the same trade — see History.kt.
 */

const HISTORY_FILE = () => path.join(configDir(), 'history.json');

// A record to glance at, not an audit log. A file that grows without limit is
// a bug that takes a year to show itself.
const LIMIT = 50;

const TERMINAL = new Set(['completed', 'failed', 'declined', 'cancelled']);

/** True once a transfer can no longer change on its own. */
export function isFinished(status) {
  return TERMINAL.has(status);
}

let cache = null;

function load() {
  if (cache) return cache;
  try {
    const parsed = JSON.parse(fs.readFileSync(HISTORY_FILE(), 'utf8'));
    cache = Array.isArray(parsed) ? parsed.filter((e) => e && typeof e.id === 'string') : [];
  } catch {
    cache = [];
  }
  return cache;
}

function persist() {
  fs.mkdirSync(configDir(), { recursive: true });
  fs.writeFileSync(HISTORY_FILE(), JSON.stringify(cache, null, 2));
}

/** Newest first, which is the order the interface wants and the file keeps. */
export function entries() {
  return load();
}

/**
 * Remember a transfer that has ended.
 *
 * Ignores anything still in flight, and replaces rather than appends when the
 * id is already known: a transfer can settle more than once on its way down,
 * and two entries for one transfer would be a lie about how many there were.
 */
export function record(snapshot) {
  if (!snapshot || !isFinished(snapshot.status)) return null;
  const entry = reduce(snapshot);
  cache = [entry, ...load().filter((e) => e.id !== entry.id)]
    .sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0))
    .slice(0, LIMIT);
  persist();
  return entry;
}

export function clear() {
  cache = [];
  persist();
}

/**
 * Forget one transfer. The list on screen is the live transfers and the
 * remembered ones together, so taking a single card off it means both halves
 * — this is the half that would otherwise come straight back on the next
 * frame.
 */
export function forget(id) {
  const before = load().length;
  cache = cache.filter((e) => e.id !== id);
  if (cache.length === before) return false;
  persist();
  return true;
}

function reduce(t) {
  return {
    id: t.id,
    direction: t.direction,
    peer: { id: t.peer?.id ?? null, name: t.peer?.name ?? '?', os: t.peer?.os ?? '' },
    status: t.status,
    error: t.error ?? null,
    totalSize: t.totalSize ?? 0,
    received: t.received ?? 0,
    fileCount: t.fileCount ?? 0,
    destDir: t.destDir ?? null,
    // Two strings rather than the list. The first name is what the transfer
    // is called on screen — without it a remembered transfer reads as a dash
    // where every live one reads as a file name. The path is only worth
    // keeping when there is exactly one file, because that is the case where
    // pointing at the file beats pointing at the folder. Two strings are not
    // a file list.
    // A live snapshot carries these two directly now; the older shape, with
    // the whole list, is still read so a record written by any version of
    // this app comes back the same.
    firstFile: t.firstFile ?? t.files?.[0]?.rel ?? null,
    filePath: t.filePath ?? (t.fileCount === 1 ? (t.files?.[0]?.path ?? null) : null),
    startedAt: t.startedAt ?? null,
    // A transfer that failed never set this, and "when did this happen" has to
    // be answerable for those too. It ended now, because that is when the
    // record is being written.
    finishedAt: t.finishedAt ?? Date.now(),
    createdAt: t.createdAt ?? Date.now(),
    // The interface says so out loud rather than showing an empty file list.
    stored: true,
  };
}
