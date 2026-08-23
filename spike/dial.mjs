/**
 * Pair with, and send to, a peer at a known address — using the real desktop
 * code, with no discovery involved.
 *
 *     node spike/dial.mjs pair <host> <port>
 *     node spike/dial.mjs send <host> <port> <peerId> <path...>
 *
 * Discovery needs both machines on one broadcast domain. An emulator is behind
 * NAT and never will be, so this reaches it over a forwarded port instead.
 * Everything above the address — pairing, the session key, TLS, the transfer —
 * is the same code the desktop app runs.
 */
import fs from 'node:fs';
import path from 'node:path';

const [command, host, port, ...rest] = process.argv.slice(2);
if (!['pair', 'send'].includes(command) || !host || !port) {
  console.error('usage: node spike/dial.mjs pair|send <host> <port> [peerId] [path...]');
  process.exit(2);
}

// A directory of its own, so an experiment never disturbs the real one.
const home = process.env.FLYSHARE_HOME ?? path.join(process.cwd(), '.flyshare-dial');
fs.mkdirSync(home, { recursive: true });
process.env.FLYSHARE_HOME = home;

const { initiatePairing } = await import('../src/core/pairing.js');
const { Sender } = await import('../src/core/client.js');
const { pairedPeers } = await import('../src/core/trust.js');
const { loadConfig } = await import('../src/core/config.js');

const peer = {
  id: rest[0] ?? 'unknown',
  name: 'device',
  os: 'android',
  address: host,
  port: Number(port),
};

console.log(`this device: ${loadConfig().deviceName} [${loadConfig().deviceId}]`);

if (command === 'pair') {
  const outcome = await initiatePairing(peer, (code) => {
    console.log(`\n  compare this code on the other screen:  ${code.slice(0, 3)} ${code.slice(3)}\n`);
    console.log('  waiting for it to be confirmed there…');
  });
  console.log(`paired with ${outcome.name} [${outcome.id}]`);
  console.log('now trusted here:', pairedPeers().map((p) => `${p.id} ${p.name}`).join(', '));
  process.exit(0);
}

const paths = rest.slice(1);
if (paths.length === 0) {
  console.error('nothing to send');
  process.exit(2);
}

const known = pairedPeers().find((p) => p.id === peer.id);
if (!known) {
  console.error(`${peer.id} is not paired — run the pair command first`);
  process.exit(1);
}
peer.name = known.name;
peer.os = known.os;

const sender = new Sender();
let previousStatus = null;

const finished = new Promise((resolve) => {
  sender.on('transfer', (t) => {
    if (t.status !== previousStatus) {
      process.stdout.write(`\r  [${t.status}]${t.error ? ' ' + t.error : ''}\n`);
      previousStatus = t.status;
    }
    if (t.status === 'sending' && t.totalSize) {
      const percent = ((t.received / t.totalSize) * 100).toFixed(1);
      const rate = t.speed ? `${(t.speed / 1e6).toFixed(1)} MB/s` : '';
      process.stdout.write(`\r  ${percent}%  ${rate}   `);
    }
    if (t.status === 'completed') resolve({ ok: true, transfer: t });
    if (t.status === 'failed' || t.status === 'declined') resolve({ ok: false, transfer: t });
  });
});

const started = Date.now();
await sender.sendPaths(peer, paths);
const { ok, transfer } = await finished;
// From acceptance, not from launch: the wait for a person to tap Accept is
// not the network's fault and would otherwise dominate the number.
const seconds = transfer.startedAt && transfer.finishedAt
  ? (transfer.finishedAt - transfer.startedAt) / 1000
  : (Date.now() - started) / 1000;

console.log(ok ? 'SENT' : 'FAILED');
console.log(`  files      ${transfer.fileCount}`);
console.log(`  bytes      ${transfer.totalSize}`);
console.log(`  seconds    ${seconds.toFixed(2)}`);
if (transfer.totalSize && seconds > 0) {
  console.log(`  throughput ${(transfer.totalSize / seconds / 1e6).toFixed(1)} MB/s`);
}
if (transfer.security) console.log(`  security   ${transfer.security}`);
if (!ok) console.log(`  error      ${transfer.error}`);

process.exit(ok ? 0 : 1);
