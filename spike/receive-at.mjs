/**
 * Receive a transfer using the real desktop receiver.
 *
 *     node spike/receive-at.mjs <port> <downloadDir>
 *
 * The mirror of send-to.mjs, for testing the Kotlin *sender*. Same fixed seeds,
 * so no pairing and no person is needed, and everything below the seed is the
 * production src/core/server.js — the code the desktop app actually runs.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const [port, downloadDir] = process.argv.slice(2);
if (!port || !downloadDir) {
  console.error('usage: node spike/receive-at.mjs <port> <downloadDir>');
  process.exit(2);
}

const NODE_SEED = 'flyshare-interop-node';
const KOTLIN_SEED = 'flyshare-interop-kotlin';
const NODE_DEVICE_ID = '1111111111111111';
const KOTLIN_DEVICE_ID = '2222222222222222';

/** A raw 32-byte X25519 scalar, wrapped in the PKCS#8 header Node expects. */
function privateKeyFromSeed(seed) {
  const raw = crypto.createHash('sha256').update(seed).digest();
  const der = Buffer.concat([Buffer.from('302e020100300506032b656e04220420', 'hex'), raw]);
  return crypto.createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
}

const nodeKey = privateKeyFromSeed(NODE_SEED);
const kotlinPublic = crypto.createPublicKey(privateKeyFromSeed(KOTLIN_SEED));

const home = fs.mkdtempSync(path.join(os.tmpdir(), 'flyshare-recv-'));
process.env.FLYSHARE_HOME = home;

fs.writeFileSync(path.join(home, 'identity.json'), JSON.stringify({
  publicJwk: crypto.createPublicKey(nodeKey).export({ format: 'jwk' }),
  privateJwk: nodeKey.export({ format: 'jwk' }),
}));

fs.writeFileSync(path.join(home, 'peers.json'), JSON.stringify({
  [KOTLIN_DEVICE_ID]: {
    name: 'Kotlin sender',
    os: 'android',
    publicKey: kotlinPublic.export({ format: 'jwk' }).x,
    pairedAt: Date.now(),
  },
}));

fs.rmSync(downloadDir, { recursive: true, force: true });
fs.mkdirSync(downloadDir, { recursive: true });

fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({
  deviceId: NODE_DEVICE_ID,
  deviceName: 'Node interop receiver',
  downloadDir,
  autoAccept: true, // a probe has nobody to ask
}));

const { TransferServer } = await import('../src/core/server.js');

const server = new TransferServer();
let lastPercent = -1;

const finished = new Promise((resolve) => {
  server.on('transfer', (t) => {
    if (t.status === 'receiving' && t.totalSize) {
      const percent = Math.floor((t.received / t.totalSize) * 100);
      if (percent !== lastPercent) {
        lastPercent = percent;
        process.stdout.write(`\r  ${percent}%   `);
      }
    }
    if (t.status === 'completed' || t.status === 'done') resolve({ ok: true, transfer: t });
    if (t.status === 'failed' || t.status === 'declined') resolve({ ok: false, transfer: t });
  });
  server.on('error', (message) => console.error(`\nserver error: ${message}`));
});

await server.start(Number(port));
console.log(`listening on ${port} as ${NODE_DEVICE_ID}`);
console.log(`saving into ${downloadDir}`);

const { ok, transfer } = await finished;
process.stdout.write('\r');
console.log(ok ? 'RECEIVED' : 'FAILED');
console.log(`  bytes    ${transfer.received} of ${transfer.totalSize}`);
if (!ok) console.log(`  error    ${transfer.error}`);

// A digest per file, so the comparison with the sender is exact.
const digests = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else digests.push(full);
  }
})(downloadDir);

for (const file of digests.sort()) {
  const hash = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const rel = path.relative(downloadDir, file).split(path.sep).join('/');
  console.log(`  ${hash}  ${String(fs.statSync(file).size).padStart(12)}  ${rel}`);
}

server.stop();
fs.rmSync(home, { recursive: true, force: true });
process.exit(ok ? 0 : 1);
