import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { configDir } from './config.js';

const IDENTITY_FILE = () => path.join(configDir(), 'identity.json');

let cache = null;

/**
 * This device's long-term X25519 identity.
 *
 * Pairing pins the public half on the other machine; every later connection
 * proves possession of the private half. Losing this file means every paired
 * device has to be paired again — which is the correct, visible failure.
 */
export function loadIdentity() {
  if (cache) return cache;

  const file = IDENTITY_FILE();
  try {
    const stored = JSON.parse(fs.readFileSync(file, 'utf8'));
    cache = {
      privateKey: crypto.createPrivateKey({ key: stored.privateJwk, format: 'jwk' }),
      publicKey: crypto.createPublicKey({ key: stored.publicJwk, format: 'jwk' }),
      publicRaw: stored.publicJwk.x,
    };
    return cache;
  } catch {
    // No identity yet, or an unreadable one — mint a fresh pair.
  }

  const pair = crypto.generateKeyPairSync('x25519');
  const publicJwk = pair.publicKey.export({ format: 'jwk' });
  const privateJwk = pair.privateKey.export({ format: 'jwk' });

  fs.mkdirSync(configDir(), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({ publicJwk, privateJwk }, null, 2), { mode: 0o600 });
  try {
    fs.chmodSync(file, 0o600); // no-op on Windows, meaningful everywhere else
  } catch { /* not fatal */ }

  cache = { privateKey: pair.privateKey, publicKey: pair.publicKey, publicRaw: publicJwk.x };
  return cache;
}

/** Our public key in the compact base64url form used on the wire. */
export function publicKeyString() {
  return loadIdentity().publicRaw;
}

/** Turn a wire-format public key back into a usable key object. */
export function importPublicKey(raw) {
  if (typeof raw !== 'string' || Buffer.from(raw, 'base64url').length !== 32) {
    throw new Error('malformed public key');
  }
  return crypto.createPublicKey({
    key: { kty: 'OKP', crv: 'X25519', x: raw },
    format: 'jwk',
  });
}

/** Raw X25519 shared secret with a peer's public key. */
export function sharedSecret(peerPublicKey) {
  return crypto.diffieHellman({
    privateKey: loadIdentity().privateKey,
    publicKey: peerPublicKey,
  });
}
