import crypto from 'node:crypto';
import tls from 'node:tls';
import { loadIdentity, importPublicKey } from './identity.js';

const SESSION_INFO = 'flyshare-session-v2';
const PSK_IDENTITY = 'flyshare';
const HANDSHAKE_TIMEOUT_MS = 15000;

/**
 * A throwaway X25519 key pair, one per connection.
 *
 * This is what gives the link forward secrecy: the long-term pairing key only
 * authenticates, it never encrypts. Recording today's traffic and stealing the
 * identity file tomorrow does not decrypt anything, because the key that
 * actually protected the bytes existed only in memory for one connection.
 */
export function newEphemeralKeyPair() {
  const pair = crypto.generateKeyPairSync('x25519');
  return {
    privateKey: pair.privateKey,
    publicRaw: pair.publicKey.export({ format: 'jwk' }).x,
  };
}

/**
 * Derive the pre-shared key both ends will feed to TLS.
 *
 * Mixing the ephemeral exchange (secrecy) with the pinned pairing secret
 * (authentication) means a peer that never paired cannot produce this value,
 * so its TLS handshake simply fails — there is nothing to fall back to.
 */
export function deriveSessionKey({ ephemeral, peerEphemeralRaw, peerIdentityKey, selfId, peerId }) {
  const ephemeralShared = crypto.diffieHellman({
    privateKey: ephemeral.privateKey,
    publicKey: importPublicKey(peerEphemeralRaw),
  });
  const pairingSecret = crypto.diffieHellman({
    privateKey: loadIdentity().privateKey,
    publicKey: peerIdentityKey,
  });

  // Both device ids in a fixed order, so each side derives the same key
  // regardless of who dialled.
  const ordered = [selfId, peerId].sort().join('|');
  const info = Buffer.from(`${SESSION_INFO}|${ordered}`, 'utf8');

  return Buffer.from(crypto.hkdfSync('sha256', ephemeralShared, pairingSecret, info, 32));
}

/** Upgrade the dialling side of an open socket to TLS 1.3. */
export function secureClient(socket, psk) {
  return upgrade(() => tls.connect({
    socket,
    minVersion: 'TLSv1.3',
    pskCallback: () => ({ psk, identity: PSK_IDENTITY }),
    // There is no certificate and no CA here; the PSK is the authentication.
    checkServerIdentity: () => undefined,
  }), socket);
}

/** Upgrade the listening side of an open socket to TLS 1.3. */
export function secureServer(socket, psk) {
  return upgrade(() => new tls.TLSSocket(socket, {
    isServer: true,
    minVersion: 'TLSv1.3',
    pskCallback: () => psk,
    pskIdentityHint: PSK_IDENTITY,
  }), socket);
}

function upgrade(build, rawSocket) {
  return new Promise((resolve, reject) => {
    let secure;
    const timer = setTimeout(() => {
      cleanup();
      secure?.destroy();
      rawSocket.destroy();
      reject(new Error('secure handshake timed out'));
    }, HANDSHAKE_TIMEOUT_MS);

    function cleanup() {
      clearTimeout(timer);
      secure?.off('secure', onSecure);
      secure?.off('secureConnect', onSecure);
      secure?.off('error', onError);
    }
    function onSecure() {
      cleanup();
      secure.setNoDelay(true);
      resolve(secure);
    }
    function onError(err) {
      cleanup();
      secure.destroy();
      rawSocket.destroy();
      // The only realistic cause is a key mismatch, and "handshake failure"
      // means nothing to someone who just wants to send a file.
      reject(new Error(`could not establish an encrypted connection — ${err.message}`));
    }

    try {
      secure = build();
    } catch (err) {
      clearTimeout(timer);
      rawSocket.destroy();
      return reject(err);
    }
    secure.once('secure', onSecure);
    secure.once('secureConnect', onSecure);
    secure.once('error', onError);
  });
}

/** Human-readable description of what protected a connection. */
export function describeSecurity(secureSocket) {
  try {
    return `${secureSocket.getProtocol()} / ${secureSocket.getCipher().name}`;
  } catch {
    return 'unknown';
  }
}
