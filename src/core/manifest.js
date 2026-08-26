import fs from 'node:fs/promises';
import path from 'node:path';

/**
 * Turn a list of dropped paths (files and/or folders) into a flat manifest.
 * Folder structure is preserved through `rel`, always with forward slashes so
 * a Windows sender and a macOS receiver agree on the layout.
 */
// A folder of seventy thousand files is a stat call each, and takes seconds.
// Reporting a few times a second is enough to show that something is
// happening without making the counting itself the expensive part.
const SCAN_REPORT_EVERY = 150;

/**
 * @param {object} [watch]
 * @param {(found: {files: number, bytes: number, first: string|null}) => void}
 *   [watch.onProgress] Called as the walk goes. Until this existed a big
 *   folder meant several seconds in which the interface showed nothing at
 *   all, which reads as a hang.
 * @param {() => boolean} [watch.stopped] Asked between entries, so cancelling
 *   stops the walk rather than only ignoring its result.
 */
export async function buildManifest(inputPaths, watch = {}) {
  const scan = {
    files: [],
    bytes: 0,
    stopped: false,
    lastReport: 0,
    onProgress: watch.onProgress,
    isStopped: watch.stopped ?? (() => false),
  };

  for (const input of inputPaths) {
    if (scan.isStopped()) { scan.stopped = true; break; }
    const resolved = path.resolve(input);
    const stat = await fs.stat(resolved);
    if (stat.isDirectory()) {
      await walk(resolved, path.basename(resolved), scan);
    } else if (stat.isFile()) {
      found(scan, { abs: resolved, rel: path.basename(resolved), size: stat.size });
    }
    if (scan.stopped) break;
  }

  return { files: scan.files, totalSize: scan.bytes, stopped: scan.stopped };
}

function found(scan, file) {
  scan.files.push(file);
  scan.bytes += file.size;
  const now = Date.now();
  if (scan.onProgress && now - scan.lastReport >= SCAN_REPORT_EVERY) {
    scan.lastReport = now;
    scan.onProgress({ files: scan.files.length, bytes: scan.bytes, first: scan.files[0].rel });
  }
}

async function walk(dir, relBase, scan) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (scan.isStopped()) { scan.stopped = true; return; }
    const abs = path.join(dir, entry.name);
    const rel = `${relBase}/${entry.name}`;
    if (entry.isDirectory()) {
      await walk(abs, rel, scan);
      if (scan.stopped) return;
    } else if (entry.isFile()) {
      const stat = await fs.stat(abs);
      found(scan, { abs, rel, size: stat.size });
    }
    // Symlinks and special files are skipped: they don't survive a
    // Windows <-> macOS round trip in any predictable way.
  }
}

/**
 * Map an incoming relative path onto a safe absolute path inside `root`.
 * Rejects absolute paths, drive letters, and any `..` that would escape.
 */
export function safeJoin(root, rel) {
  const cleaned = String(rel)
    .split(/[/\\]+/)
    .filter((seg) => seg && seg !== '.' && seg !== '..')
    .map((seg) => sanitizeSegment(seg));
  if (cleaned.length === 0) throw new Error(`unsafe path: ${rel}`);
  const target = path.join(root, ...cleaned);
  const rootWithSep = path.resolve(root) + path.sep;
  if (!path.resolve(target).startsWith(rootWithSep)) throw new Error(`unsafe path: ${rel}`);
  return target;
}

// Characters that are legal on macOS but not on Windows, plus reserved device names.
const WINDOWS_RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i;
const ILLEGAL_CHARS = /[<>:"|?*\x00-\x1f]/g;

/** Strip only what the filesystem actually rejects — Cyrillic, accents and
 *  spaces are all valid filenames and must survive. */
export function sanitizeSegment(seg) {
  let out = seg.replace(ILLEGAL_CHARS, '_');
  if (process.platform === 'win32') {
    out = out.replace(/[. ]+$/, '_');
    if (WINDOWS_RESERVED.test(out)) out = `_${out}`;
  }
  return out || '_';
}

/** Pick a non-colliding name: `report.pdf` -> `report (2).pdf`. */
export async function uniquePath(target) {
  let candidate = target;
  const dir = path.dirname(target);
  const ext = path.extname(target);
  const base = path.basename(target, ext);
  for (let i = 2; i < 1000; i += 1) {
    try {
      await fs.access(candidate);
    } catch {
      return candidate;
    }
    candidate = path.join(dir, `${base} (${i})${ext}`);
  }
  return candidate;
}
