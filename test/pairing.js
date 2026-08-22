/**
 * Pairing tests.
 *
 * The six-digit code is the only thing standing between a person and a machine
 * in the middle, so these check the properties it depends on: both ends agree,
 * an interceptor cannot make them agree, and the commitment stops either side
 * from choosing its key after it knows the other's.
 *
 * Run: node test/pairing.js
 */
import fsp from 'node:fs/promises';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { computeSasCode, pairingCommitment } from '../src/core/pairing.js';
import { makeHome, startReceiver } from './helpers/instance.js';

const ROOT = path.join(os.tmpdir(), `flyshare-pair-${process.pid}`);
const PORT = 45896;

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures += 1;
}

const keypair = () => crypto.generateKeyPairSync('x25519');
const rawPublic = (pair) => pair.publicKey.export({ format: 'jwk' }).x;
const dh = (mine, theirs) => crypto.diffieHellman({
  privateKey: mine.privateKey,
  publicKey: theirs.publicKey,
});
const nonce = () => crypto.randomBytes(16).toString('base64url');

function sasBetween(initiator, responder, initiatorNonce, responderNonce) {
  return computeSasCode({
    initiatorPublic: rawPublic(initiator),
    responderPublic: rawPublic(responder),
    initiatorNonce,
    responderNonce,
    shared: dh(initiator, responder),
  });
}

function unitChecks() {
  console.log('\nshort authentication string:');

  const alice = keypair();
  const bob = keypair();
  const nonceA = nonce();
  const nonceB = nonce();

  // Each side computes with its own private key and the other's public key.
  const codeOnAlice = computeSasCode({
    initiatorPublic: rawPublic(alice),
    responderPublic: rawPublic(bob),
    initiatorNonce: nonceA,
    responderNonce: nonceB,
    shared: dh(alice, bob),
  });
  const codeOnBob = computeSasCode({
    initiatorPublic: rawPublic(alice),
    responderPublic: rawPublic(bob),
    initiatorNonce: nonceA,
    responderNonce: nonceB,
    shared: dh(bob, alice),
  });
  check(codeOnAlice === codeOnBob, 'both devices derive the same code', codeOnAlice);
  check(/^\d{6}$/.test(codeOnAlice), 'code is exactly six digits', codeOnAlice);

  // A machine in the middle terminates two separate exchanges. It sees both
  // codes but cannot make them equal without grinding keys, which the
  // commitment forbids.
  const mallory = keypair();
  const legAlice = sasBetween(alice, mallory, nonceA, nonce());
  const legBob = sasBetween(mallory, bob, nonce(), nonceB);
  check(legAlice !== legBob, 'an interceptor shows a different code on each side',
    `${legAlice} vs ${legBob}`);

  // Fresh nonces mean a captured code is worthless on a later attempt.
  const replay = sasBetween(alice, bob, nonce(), nonce());
  check(replay !== codeOnAlice, 'a new attempt produces a new code', `${codeOnAlice} then ${replay}`);

  console.log('\ncommitment:');
  const commit = pairingCommitment(rawPublic(bob), nonceB);
  check(pairingCommitment(rawPublic(bob), nonceB) === commit, 'commitment is reproducible');
  check(pairingCommitment(rawPublic(mallory), nonceB) !== commit,
    'swapping the key after committing is detected');
  check(pairingCommitment(rawPublic(bob), nonce()) !== commit,
    'swapping the nonce after committing is detected');

  // Spot-check that codes are spread over the whole range rather than clumping.
  const seen = new Set();
  for (let i = 0; i < 300; i += 1) {
    const x = keypair();
    const y = keypair();
    seen.add(sasBetween(x, y, nonce(), nonce()));
  }
  check(seen.size === 300, 'no collisions across 300 pairings', `${seen.size} distinct`);
}

async function integrationChecks() {
  console.log('\nlive pairing:');
  await fsp.rm(ROOT, { recursive: true, force: true });

  // Deliberately NOT pre-paired: that is what this test establishes.
  const initiatorHome = makeHome(path.join(ROOT, 'initiator'), {
    deviceId: 'initiator-device', deviceName: 'Initiator',
  });
  const responderHome = makeHome(path.join(ROOT, 'responder'), {
    deviceId: 'responder-device', deviceName: 'Responder',
    downloadDir: path.join(ROOT, 'inbox'),
  });

  process.env.FLYSHARE_HOME = initiatorHome.dir;
  const { Sender } = await import('../src/core/client.js');

  const receiver = await startReceiver(responderHome, PORT);
  const sender = new Sender();
  const peer = {
    id: responderHome.deviceId,
    name: responderHome.deviceName,
    os: 'test',
    address: '127.0.0.1',
    port: PORT,
  };

  // Sending before pairing must not be possible.
  let refused = null;
  await sender.sendPaths(peer, [initiatorHome.dir]).catch((err) => { refused = err; });
  check(refused !== null && /not paired/i.test(refused.message),
    'sending to an unpaired device is refused', refused?.message);

  const codes = { initiator: null, responder: null };
  const paired = new Promise((resolve, reject) => {
    sender.on('pairing', (session) => {
      if (session.code) codes.initiator = session.code;
      if (session.status === 'paired') resolve(session);
      if (session.status === 'failed') reject(new Error(session.error));
    });
  });

  sender.pair(peer);
  const request = await receiver.waitFor(
    (m) => m.type === 'pairing' && m.payload.status === 'awaiting-confirmation',
    20000, 'the pairing request',
  );
  codes.responder = request.payload.code;

  check(Boolean(codes.initiator) && codes.initiator === codes.responder,
    'the same code appears on both devices', `${codes.initiator} / ${codes.responder}`);

  receiver.send({ cmd: 'respond-pairing', pairingId: request.payload.id, accept: true });
  await paired;

  const initiatorTrust = JSON.parse(fs.readFileSync(path.join(initiatorHome.dir, 'peers.json'), 'utf8'));
  const responderTrust = JSON.parse(fs.readFileSync(path.join(responderHome.dir, 'peers.json'), 'utf8'));
  check(initiatorTrust[responderHome.deviceId]?.publicKey === responderHome.publicKey,
    'initiator pinned the responder key');
  check(responderTrust[initiatorHome.deviceId]?.publicKey === initiatorHome.publicKey,
    'responder pinned the initiator key');

  // And the pairing is immediately usable.
  await fsp.mkdir(path.join(ROOT, 'payload'), { recursive: true });
  await fsp.writeFile(path.join(ROOT, 'payload', 'after-pairing.txt'), 'it works\n');
  await sender.sendPaths(peer, [path.join(ROOT, 'payload', 'after-pairing.txt')]);
  const offer = await receiver.waitFor((m) => m.type === 'offer', 15000, 'the offer');
  receiver.send({ cmd: 'respond', transferId: offer.payload.id, accept: true });
  const done = await receiver.waitFor(
    (m) => m.type === 'transfer' && ['completed', 'failed'].includes(m.payload.status),
    30000, 'the transfer',
  );
  check(done.payload.status === 'completed', 'a transfer works right after pairing', done.payload.error ?? '');
  check(/^TLSv1\.3/.test(done.payload.security ?? ''), 'and it is encrypted', done.payload.security);

  receiver.stop();
  await fsp.rm(ROOT, { recursive: true, force: true }).catch(() => {});
}

unitChecks();
await integrationChecks().catch((err) => {
  console.error('\nTest crashed:', err);
  failures += 1;
});

console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
