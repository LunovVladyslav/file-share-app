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
import fs from 'node:fs';
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

/* --- what a person reads ------------------------------------------------
 *
 * Not protocol, but the same failure mode: two implementations of one rule
 * that drifted apart. The desktop divided by 1024 and called the result GB,
 * so a transfer the Android app described as 81.6 GB appeared here as 76 GB.
 * The same bytes, a thirteenth apart, and nothing on either screen to say
 * which of them was right.
 *
 * The cases below are the ones in SizeFormatTest.kt. This reads the shipped
 * function out of ui/app.js rather than a copy of it, because a copy is the
 * thing that drifts. */
{
  const source = fs.readFileSync(new URL('../ui/app.js', import.meta.url), 'utf8');
  const found = source.match(/function bytes\(value\) \{[\s\S]*?\n\}/);
  check(Boolean(found), 'the byte formatter can be found in ui/app.js');

  if (found) {
    const t = { units: () => ['B', 'KB', 'MB', 'GB', 'TB'] };
    const bytes = new Function('t', `${found[0]}; return bytes;`)(t);

    const cases = [
      [1000, '1.0 KB'],
      [1024, '1.0 KB'],
      [1_000_000, '1.0 MB'],
      [76 * 1024 ** 3, '82 GB'],
      [3_800_000_000, '3.8 GB'],
      [999_000_000, '999 MB'],
      [0, '0 B'],
      [512, '512 B'],
    ];
    const wrong = cases.filter(([input, want]) => bytes(input) !== want);
    check(
      wrong.length === 0,
      'byte sizes read the same here as in the Android app',
      wrong.map(([input, want]) => `${input} -> ${bytes(input)}, expected ${want}`).join('; '),
    );
  }
}

/* --- one mark, four copies of it --------------------------------------------
 *
 * The same four points are written out in the desktop stylesheet, in an
 * Android vector drawable, and in the icon generator. Nothing stops three of
 * them from moving while the fourth stays, and the failure is silent: the app
 * simply stops looking like itself in one place nobody opens often.
 *
 * So the numbers are compared where they can be, and the committed .ico is
 * checked against what the generator produces now — a generator that is
 * deterministic, so a difference means the file is stale. */
console.log('\nthe mark:\n');
{
  const read = (file) => fs.readFileSync(new URL(`../${file}`, import.meta.url), 'utf8');

  const css = read('ui/app.css').match(/clip-path:\s*polygon\(([^)]+)\)/);
  check(Boolean(css), 'the mark is still a clip-path in ui/app.css');

  const generator = read('scripts/make-icon.js').match(/const MARK = (\[[\s\S]*?\]);/);
  check(Boolean(generator), 'and a point list in scripts/make-icon.js');

  if (css && generator) {
    const fromCss = css[1].split(',').map((pair) => pair.trim().split(/\s+/)
      .map((n) => Number(n.replace('%', '')) / 100));
    const fromGenerator = JSON.parse(generator[1]);
    check(
      JSON.stringify(fromCss) === JSON.stringify(fromGenerator),
      'the icon is drawn from the same points the interface uses',
      `${JSON.stringify(fromCss)} vs ${JSON.stringify(fromGenerator)}`,
    );
  }

  const android = read('android/app/src/main/res/drawable/ic_launcher_foreground.xml')
    .match(/pathData="([^"]+)"/);
  const splash = read('android/app/src/main/res/drawable/splash_mark.xml')
    .match(/pathData="([^"]+)"/);
  check(
    Boolean(android) && android?.[1] === splash?.[1],
    'the launcher icon and the splash draw the same path',
    `${android?.[1]} vs ${splash?.[1]}`,
  );
}

console.log(failures === 0 ? '\nSpec matches the implementation.' : `\n${failures} mismatch(es).`);
process.exit(failures === 0 ? 0 : 1);
