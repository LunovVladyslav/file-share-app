import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';

// v2 added pairing and TLS; it deliberately cannot talk to v1.
export const PROTOCOL_VERSION = 2;

// Network constants — identical on every platform, so a Windows box and a Mac
// find each other without either side configuring anything.
const port = (name, fallback) => Number(process.env[name]) || fallback;

export const DISCOVERY_PORT = port('FLYSHARE_DISCOVERY_PORT', 45888);
export const MULTICAST_ADDR = process.env.FLYSHARE_MULTICAST || '239.255.77.88';
export const TRANSFER_PORT = port('FLYSHARE_TRANSFER_PORT', 45889);
export const UI_PORT = port('FLYSHARE_UI_PORT', 45890);

export const ANNOUNCE_INTERVAL_MS = 3000;
export const PEER_TTL_MS = 12000;

/**
 * Where all state lives. FLYSHARE_HOME relocates it, which makes portable
 * installs and side-by-side instances possible without touching the real
 * profile. Resolved on every call rather than at import time, so merely
 * importing a module can never pin the wrong directory.
 */
export function configDir() {
  return process.env.FLYSHARE_HOME || path.join(os.homedir(), '.flyshare');
}

const configFile = () => path.join(configDir(), 'config.json');

function defaultDownloadDir() {
  const downloads = path.join(os.homedir(), 'Downloads');
  return fs.existsSync(downloads)
    ? path.join(downloads, 'FlyShare')
    : path.join(configDir(), 'received');
}

function defaults() {
  return {
    deviceName: os.hostname().replace(/\.local$/, ''),
    downloadDir: defaultDownloadDir(),
    // Parallel TCP streams — the single biggest speed lever on Wi-Fi. One
    // stream is capped by its congestion window and stalls on every lost
    // packet; four recover independently and keep the radio busy.
    streams: 4,
    chunkSize: 32 * 1024 * 1024,
    readBufferSize: 1024 * 1024,
    autoAccept: false,

    // How much of a frame the file list of an offer may fill before it has to
    // be split into pages. Null means the protocol's own ceiling, which is
    // what everything outside the tests wants; the tests shrink it so the
    // paged path can be reached without a manifest of real megabytes.
    manifestBudget: null,

    // Interface preferences live here rather than in the browser, so they
    // follow the device instead of whichever browser profile opened the UI.
    language: 'auto',      // auto | en | de | uk | pl
    theme: 'system',       // system | light | dark
    transferView: 'list',  // list | grid
  };
}

let cache = null;

export function loadConfig() {
  if (cache) return cache;

  let stored = {};
  try {
    stored = JSON.parse(fs.readFileSync(configFile(), 'utf8'));
  } catch {
    stored = {};
  }
  cache = { ...defaults(), ...stored };
  if (!cache.deviceId) cache.deviceId = crypto.randomBytes(8).toString('hex');
  persist();
  return cache;
}

export function saveConfig(patch) {
  loadConfig();
  cache = { ...cache, ...patch };
  persist();
  return cache;
}

function persist() {
  fs.mkdirSync(configDir(), { recursive: true });
  fs.writeFileSync(configFile(), JSON.stringify(cache, null, 2));
}

export function platformLabel() {
  switch (process.platform) {
    case 'win32': return 'windows';
    case 'darwin': return 'macos';
    case 'linux': return 'linux';
    default: return process.platform;
  }
}
