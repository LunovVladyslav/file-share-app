import fs from 'node:fs';
import path from 'node:path';
import { configDir } from './config.js';
import { importPublicKey } from './identity.js';

const TRUST_FILE = () => path.join(configDir(), 'peers.json');

let cache = null;

function load() {
  if (cache) return cache;
  try {
    cache = JSON.parse(fs.readFileSync(TRUST_FILE(), 'utf8'));
  } catch {
    cache = {};
  }
  return cache;
}

function persist() {
  fs.mkdirSync(configDir(), { recursive: true });
  fs.writeFileSync(TRUST_FILE(), JSON.stringify(cache, null, 2), { mode: 0o600 });
}

/** Every device this one has been paired with. */
export function pairedPeers() {
  const store = load();
  return Object.entries(store).map(([id, entry]) => ({
    id,
    name: entry.name,
    os: entry.os,
    pairedAt: entry.pairedAt,
  }));
}

export function isPaired(deviceId) {
  return Boolean(load()[deviceId]);
}

/** The peer's pinned public key, or null if it was never paired. */
export function peerPublicKey(deviceId) {
  const entry = load()[deviceId];
  if (!entry) return null;
  try {
    return importPublicKey(entry.publicKey);
  } catch {
    return null;
  }
}

export function rememberPeer({ id, name, os, publicKey }) {
  const store = load();
  store[id] = { name, os, publicKey, pairedAt: Date.now() };
  persist();
}

export function forgetPeer(deviceId) {
  const store = load();
  if (!store[deviceId]) return false;
  delete store[deviceId];
  persist();
  return true;
}

/** Keep the stored label in step with a peer that renamed itself. */
export function refreshPeerName(deviceId, name, os) {
  const store = load();
  const entry = store[deviceId];
  if (!entry || (entry.name === name && entry.os === os)) return;
  entry.name = name;
  entry.os = os;
  persist();
}
