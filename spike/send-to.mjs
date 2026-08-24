/**
 * Send a folder to a FlyShare receiver using the real desktop sender.
 *
 *     node spike/send-to.mjs <host> <port> <peerId> <path...>
 *
 * This exists so the Kotlin receiver can be tested against the actual Node
 * implementation rather than against a second reading of the specification.
 * Every byte here goes through src/core/client.js — the same code the desktop
 * app runs.
 *
 * Pairing is skipped by giving both sides a key derived from a fixed seed, so
 * the test needs no person and no UI. Pairing itself is verified separately,
 * on real devices; what is under test here is the transfer.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const [host, port, peerId, ...paths] = process.argv.slice(2);
if (!host || !port || !peerId || paths.length === 0) {
  console.error('usage: node spike/send-to.mjs <host> <port> <peerId> <path...>');
  process.exit(2);
}

export const NODE_SEED = 'flyshare-interop-node';
export const KOTLIN_SEED = 'flyshare-interop-kotlin';
export const NODE_DEVICE_ID = '1111111111111111';

/** A raw 32-byte X25519 scalar, wrapped in the PKCS#8 header Node expects. */
function privateKeyFromSeed(seed) {
  const raw = crypto.createHash('sha256').update(seed).digest();
  const der = Buffer.concat([Buffer.from('302e020100300506032b656e04220420', 'hex'), raw]);
  return crypto.createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
}

const nodeKey = privateKeyFromSeed(NODE_SEED);
const kotlinPublic = crypto.createPublicKey(privateKeyFromSeed(KOTLIN_SEED));

// A throwaway config directory, so this never touches the real one.
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'flyshare-interop-'));
process.env.FLYSHARE_HOME = home;

fs.writeFileSync(path.join(home, 'identity.json'), JSON.stringify({
  publicJwk: crypto.createPublicKey(nodeKey).export({ format: 'jwk' }),
  privateJwk: nodeKey.export({ format: 'jwk' }),
}));

fs.writeFileSync(path.join(home, 'peers.json'), JSON.stringify({
  [peerId]: {
    name: 'Kotlin receiver',
    os: 'android',
    publicKey: kotlinPublic.export({ format: 'jwk' }).x,
    pairedAt: Date.now(),
  },
}));

fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({
  deviceId: NODE_DEVICE_ID,
  deviceName: 'Node interop sender',
}));

// Imported after FLYSHARE_HOME is set: the sender reads it on first use.
const { Sender } = await import('../src/core/client.js');

const peer = { id: peerId, name: 'Kotlin receiver', os: 'android', address: host, port: Number(port) };
const sender = new Sender();

let previousStatus = null;

// Pause in flight, so §9.5 is exercised against the real Kotlin receiver
// rather than only reasoned about. Timed from the moment sending starts,
// which is after the receiver has answered and canPause is known — a timer
// started any earlier can fire while the receiver is still preallocating,
// when there is nothing to pause and pause() is a no-op that hides the fact.
const pauseAfterMs = Number(process.env.FLYSHARE_PAUSE_AFTER_MS) || 0;
const pauseForMs = Number(process.env.FLYSHARE_PAUSE_FOR_MS) || 3000;
let pausedOnce = false;

const finished = new Promise((resolve) => {
  sender.on('transfer', (t) => {
    if (t.status !== previousStatus) {
      // Every transition, because when this stalls the last one names the step.
      process.stdout.write(`\r  [${t.status}]${t.error ? ' ' + t.error : ''}\n`);
      previousStatus = t.status;

      // Hung off the status change rather than a percentage: over loopback a
      // transfer can finish between two progress events, and a pause that
      // never fired would look like a pause that worked.
      if (t.status === 'sending' && pauseAfterMs && !pausedOnce) {
        pausedOnce = true;
        setTimeout(() => {
          if (!sender.pause(t.id)) {
            console.log('  the receiver does not support pausing');
            return;
          }
          console.log(`  paused; resuming in ${pauseForMs} ms`);
          setTimeout(() => sender.resume(t.id), pauseForMs);
        }, pauseAfterMs);
      }
    }
    if (t.status === 'sending' && t.totalSize) {
      const percent = (t.received / t.totalSize) * 100;
      process.stdout.write(`\r  ${percent.toFixed(1)}%  ${mbps(t.speed)}   `);

    }
    // 'completed' is the sender's success state; 'done' is a frame, not a status.
    if (t.status === 'completed') { resolve({ ok: true, transfer: t }); }
    if (t.status === 'failed' || t.status === 'declined') { resolve({ ok: false, transfer: t }); }
  });
});

const mbps = (bytesPerSecond) =>
  bytesPerSecond ? `${(bytesPerSecond / 1e6).toFixed(1)} MB/s` : '';

const started = Date.now();
await sender.sendPaths(peer, paths);
const { ok, transfer } = await finished;
// From acceptance, not from launch: the wait for a person to tap Accept is
// not the network's fault and would otherwise dominate the number.
const seconds = transfer.startedAt && transfer.finishedAt
  ? (transfer.finishedAt - transfer.startedAt) / 1000
  : (Date.now() - started) / 1000;

console.log(ok ? 'SENT' : 'FAILED');
console.log(`  files      ${transfer.fileCount ?? transfer.files?.length ?? '?'}`);
console.log(`  bytes      ${transfer.totalSize}`);
console.log(`  seconds    ${seconds.toFixed(2)}`);
if (transfer.totalSize && seconds > 0) {
  console.log(`  throughput ${(transfer.totalSize / seconds / 1e6).toFixed(1)} MB/s`);
}
if (!ok) console.log(`  error      ${transfer.error}`);

fs.rmSync(home, { recursive: true, force: true });
process.exit(ok ? 0 : 1);
