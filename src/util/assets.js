import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Interface files, from wherever this copy of FlyShare keeps them.
 *
 * Run from a checkout they are read off disk; run from a packaged single
 * executable they are baked into the binary. The server does not need to care
 * which — but the two cases are compiled differently, so everything here stays
 * lazy: a packaged build is CommonJS, where neither top-level await nor
 * import.meta exists.
 */

let seaModule;

async function sea() {
  if (seaModule === undefined) {
    try {
      seaModule = await import('node:sea');
    } catch {
      seaModule = null; // older runtime, or not available
    }
  }
  return seaModule;
}

export async function isPackaged() {
  const module = await sea();
  try {
    return Boolean(module?.isSea?.());
  } catch {
    return false;
  }
}

// An allowlist rather than path checks: these are the only files the interface
// is made of, so no request can wander outside the set.
export const UI_FILES = new Set(['index.html', 'app.css', 'app.js', 'i18n.js']);

/** Only ever called when running from source, where import.meta is real. */
function uiDir() {
  return path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'ui');
}

export async function readUiFile(name) {
  if (!UI_FILES.has(name)) return null;

  const module = await sea();
  if (module?.isSea?.()) {
    try {
      return Buffer.from(module.getRawAsset(name));
    } catch {
      return null;
    }
  }

  try {
    return await fs.readFile(path.join(uiDir(), name));
  } catch {
    return null;
  }
}
