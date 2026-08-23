/**
 * Conformance check for docs/PROTOCOL.md.
 *
 * Everything below is written from the *document*, not by importing the
 * implementation's helpers — the derivations are spelled out here the way a
 * second implementation would read them. If the two ever disagree, either the
 * spec is wrong or the protocol changed without the spec following, and an
 * Android or iOS client built from that document would silently fail to
 * interoperate.
 *
 * Run: node test/spec.js
 */
import crypto from 'node:crypto';
import { computeSasCode, pairingCommitment } from '../src/core/pairing.js';

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures += 1;
}

const b64u = (buf) => buf.toString('base64url');
const unb64u = (str) => Buffer.from(str, 'base64url');

const keypair = () => crypto.generateKeyPairSync('x25519');
const rawPublic = (pair) => pair.publicKey.export({ format: 'jwk' }).x;
const dh = (mine, theirs) => crypto.diffieHellman({
  privateKey: mine.privateKey,
  publicKey: theirs.publicKey,
});

// --- §7.2, transcribed from the document -----------------------------------

function specCommitment(pub, nonce) {
  return crypto.createHash('sha256')
    .update(Buffer.from('flyshare-sas-commit-v2', 'utf8'))
    .update(Buffer.from(pub, 'utf8'))     // the base64url *string*
    .update(unb64u(nonce))                // the decoded 16 bytes
    .digest('base64url');
}

// --- §7.3, transcribed from the document -----------------------------------

function specSasCode({ initiatorPub, responderPub, initiatorNonce, responderNonce, shared }) {
  const salt = Buffer.concat([unb64u(initiatorNonce), unb64u(responderNonce)]);
  const info = Buffer.from(`flyshare-sas-v2|${initiatorPub}|${responderPub}`, 'utf8');
  const material = Buffer.from(crypto.hkdfSync('sha256', shared, salt, info, 4));
  return String(material.readUInt32BE(0) % 1000000).padStart(6, '0');
}

// --- §8.2, transcribed from the document -----------------------------------

function specSessionKey({ ownEphemeral, peerEphemeralPub, ownIdentity, peerIdentityPub, idA, idB }) {
  const ephemeralShared = crypto.diffieHellman({
    privateKey: ownEphemeral.privateKey,
    publicKey: crypto.createPublicKey({
      key: { kty: 'OKP', crv: 'X25519', x: peerEphemeralPub }, format: 'jwk',
    }),
  });
  const pairingSecret = crypto.diffieHellman({
    privateKey: ownIdentity.privateKey,
    publicKey: crypto.createPublicKey({
      key: { kty: 'OKP', crv: 'X25519', x: peerIdentityPub }, format: 'jwk',
    }),
  });
  const ordered = [idA, idB].sort().join('|');
  const info = Buffer.from(`flyshare-session-v2|${ordered}`, 'utf8');
  return Buffer.from(crypto.hkdfSync('sha256', ephemeralShared, pairingSecret, info, 32));
}

// --- checks ----------------------------------------------------------------

console.log('\ncommitment (§7.2):');
{
  const bob = keypair();
  const nonce = b64u(crypto.randomBytes(16));
  check(specCommitment(rawPublic(bob), nonce) === pairingCommitment(rawPublic(bob), nonce),
    'the document produces the implementation’s commitment');

  // The mixed encoding is the easy thing to get wrong, so pin it down.
  const wrong = crypto.createHash('sha256')
    .update(Buffer.from('flyshare-sas-commit-v2', 'utf8'))
    .update(unb64u(rawPublic(bob)))       // decoding the key too
    .update(unb64u(nonce))
    .digest('base64url');
  check(wrong !== pairingCommitment(rawPublic(bob), nonce),
    'decoding the key as well would not interoperate (as the spec warns)');
}

console.log('\nsix-digit code (§7.3):');
{
  const alice = keypair();
  const bob = keypair();
  const nonceA = b64u(crypto.randomBytes(16));
  const nonceB = b64u(crypto.randomBytes(16));

  const fromSpec = specSasCode({
    initiatorPub: rawPublic(alice),
    responderPub: rawPublic(bob),
    initiatorNonce: nonceA,
    responderNonce: nonceB,
    shared: dh(alice, bob),
  });
  const fromCode = computeSasCode({
    initiatorPublic: rawPublic(alice),
    responderPublic: rawPublic(bob),
    initiatorNonce: nonceA,
    responderNonce: nonceB,
    shared: dh(bob, alice),
  });
  check(fromSpec === fromCode, 'the document produces the implementation’s code', fromSpec);
  check(/^\d{6}$/.test(fromSpec), 'six digits, zero-padded', fromSpec);

  // Role order is fixed, not sorted — a reader who sorts gets a different code.
  const swapped = specSasCode({
    initiatorPub: rawPublic(bob),
    responderPub: rawPublic(alice),
    initiatorNonce: nonceA,
    responderNonce: nonceB,
    shared: dh(alice, bob),
  });
  check(swapped !== fromSpec, 'initiator and responder order matters');
}

console.log('\nsession key (§8.2):');
{
  const idA = 'aaaa1111aaaa1111';
  const idB = 'bbbb2222bbbb2222';
  const identityA = keypair();
  const identityB = keypair();
  const ephA = keypair();
  const ephB = keypair();

  const keyOnA = specSessionKey({
    ownEphemeral: ephA, peerEphemeralPub: rawPublic(ephB),
    ownIdentity: identityA, peerIdentityPub: rawPublic(identityB),
    idA, idB,
  });
  const keyOnB = specSessionKey({
    ownEphemeral: ephB, peerEphemeralPub: rawPublic(ephA),
    ownIdentity: identityB, peerIdentityPub: rawPublic(identityA),
    idA: idB, idB: idA,        // each side lists itself first; sorting must fix it
  });
  check(keyOnA.equals(keyOnB), 'both ends derive the same key whoever dialled');
  check(keyOnA.length === 32, 'key is 32 bytes', String(keyOnA.length));

  // An unpaired device has the wrong identity key and cannot land on the same PSK.
  const stranger = keypair();
  const keyOnStranger = specSessionKey({
    ownEphemeral: ephB, peerEphemeralPub: rawPublic(ephA),
    ownIdentity: stranger, peerIdentityPub: rawPublic(identityA),
    idA: idB, idB: idA,
  });
  check(!keyOnA.equals(keyOnStranger), 'a wrong identity key yields a different PSK');
}

console.log('\nframing (§5):');
{
  const { encodeFrame, FrameReader, MAX_FRAME } = await import('../src/core/protocol.js');
  const frame = encodeFrame({ t: 'ping', n: 1 });
  check(frame.readUInt32BE(0) === frame.length - 4, 'u32 big-endian length prefix');
  check(JSON.parse(frame.subarray(4).toString('utf8')).t === 'ping', 'body is UTF-8 JSON');
  check(MAX_FRAME === 4 * 1024 * 1024, 'maximum body is 4 MiB', String(MAX_FRAME));

  // Split across arbitrary boundaries, as a real socket would deliver it.
  const reader = new FrameReader();
  const both = Buffer.concat([frame, encodeFrame({ t: 'pong' })]);
  const seen = [];
  for (let i = 0; i < both.length; i += 3) {
    reader.push(both.subarray(i, i + 3));
    let f;
    while ((f = reader.next()) !== null) seen.push(f.t);
  }
  check(seen.join(',') === 'ping,pong', 'frames reassemble across split reads', seen.join(','));
}

console.log(failures === 0 ? '\nSpec matches the implementation.' : `\n${failures} mismatch(es).`);
process.exit(failures === 0 ? 0 : 1);
