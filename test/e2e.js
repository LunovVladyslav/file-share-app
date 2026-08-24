/**
 * End-to-end transfer test: two processes, two identities, one encrypted link.
 *
 * Builds a source tree that exercises the awkward cases (multi-chunk file,
 * many small files, nested folders, an empty file, non-ASCII names), sends it
 * through the real server/client pair, and compares SHA-256 on both sides.
 *
 * Run: node test/e2e.js
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { makeHome, pairHomes, startReceiver } from './helpers/instance.js';

const ROOT = path.join(os.tmpdir(), `flyshare-e2e-${process.pid}`);
const SRC = path.join(ROOT, 'src');
const DEST = path.join(ROOT, 'dest');
const PORT = 45899;

await fsp.rm(ROOT, { recursive: true, force: true });

const senderHome = makeHome(path.join(ROOT, 'sender'), {
  deviceId: 'sender-device', deviceName: 'test-sender',
  streams: 4, chunkSize: 4 * 1024 * 1024,
});
const receiverHome = makeHome(path.join(ROOT, 'receiver'), {
  deviceId: 'receiver-device', deviceName: 'test-receiver',
  autoAccept: true, downloadDir: DEST,
});
pairHomes(senderHome, receiverHome);

process.env.FLYSHARE_HOME = senderHome.dir;
const { Sender } = await import('../src/core/client.js');

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures += 1;
}

function sha256(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('data', (b) => hash.update(b))
      .on('end', () => resolve(hash.digest('hex')))
      .on('error', reject);
  });
}

async function buildSourceTree() {
  await fsp.mkdir(path.join(SRC, 'nested', 'deeper'), { recursive: true });
  await fsp.mkdir(DEST, { recursive: true });

  // 40 MB across 4 MB chunks = 10 work items fanned over 4 streams.
  const out = fs.createWriteStream(path.join(SRC, 'big.bin'));
  for (let i = 0; i < 40; i += 1) {
    if (!out.write(crypto.randomBytes(1024 * 1024))) await new Promise((r) => out.once('drain', r));
  }
  await new Promise((r) => out.end(r));

  for (let i = 0; i < 120; i += 1) {
    await fsp.writeFile(path.join(SRC, 'nested', `part-${i}.txt`), `payload ${i}\n`.repeat(50));
  }
  await fsp.writeFile(path.join(SRC, 'nested', 'deeper', 'звіт річний.txt'), 'юнікод у назві та вмісті\n');
  await fsp.writeFile(path.join(SRC, 'empty.txt'), '');
  await fsp.writeFile(path.join(SRC, 'exactly-one-chunk.bin'), crypto.randomBytes(4 * 1024 * 1024));
}

async function hashTree(root) {
  const map = new Map();
  async function walk(dir, rel) {
    for (const entry of await fsp.readdir(dir, { withFileTypes: true })) {
      const abs = path.join(dir, entry.name);
      const key = rel ? `${rel}/${entry.name}` : entry.name;
      if (entry.isDirectory()) await walk(abs, key);
      else map.set(key, await sha256(abs));
    }
  }
  await walk(root, '');
  return map;
}

async function main() {
  console.log('building source tree...');
  await buildSourceTree();

  const receiver = await startReceiver(receiverHome, PORT);
  const sender = new Sender();
  const peer = {
    id: receiverHome.deviceId,
    name: receiverHome.deviceName,
    os: 'test',
    address: '127.0.0.1',
    port: PORT,
  };

  const sourceHashes = await hashTree(SRC);
  console.log(`sending ${sourceHashes.size} files over an encrypted link...`);

  const started = Date.now();
  await sender.sendPaths(peer, [SRC]);
  const completion = await receiver.waitFor(
    (m) => m.type === 'transfer' && ['completed', 'failed', 'cancelled'].includes(m.payload.status),
    120000, 'the transfer to finish',
  );
  const result = completion.payload;
  if (result.status !== 'completed') throw new Error(`transfer ${result.status}: ${result.error}`);
  const outgoing = sender.transfers.find((x) => x.id === result.id);

  const seconds = (Date.now() - started) / 1000;
  const mib = result.totalSize / 1024 / 1024;
  console.log(`transferred ${mib.toFixed(1)} MiB in ${seconds.toFixed(2)}s `
    + `(${(mib / seconds).toFixed(0)} MiB/s over loopback)`);
  console.log(`link: ${result.security}\n`);

  console.log('verifying:');
  check(/^TLSv1\.3/.test(result.security ?? ''), 'transfer ran over TLS 1.3', result.security);

  const destRoot = path.join(result.destDir, path.basename(SRC));
  const destHashes = await hashTree(destRoot);

  check(destHashes.size === sourceHashes.size, 'file count matches',
    `${destHashes.size} vs ${sourceHashes.size}`);

  const missing = [];
  const mismatched = [];
  for (const [rel, hash] of sourceHashes) {
    if (!destHashes.has(rel)) missing.push(rel);
    else if (destHashes.get(rel) !== hash) mismatched.push(rel);
  }
  check(missing.length === 0, 'no missing files', missing.slice(0, 3).join(', '));
  check(mismatched.length === 0, 'every SHA-256 matches', mismatched.slice(0, 3).join(', '));

  const emptyStat = await fsp.stat(path.join(destRoot, 'empty.txt')).catch(() => null);
  check(emptyStat?.size === 0, 'empty file preserved');
  check(fs.existsSync(path.join(destRoot, 'nested', 'deeper', 'звіт річний.txt')),
    'unicode filename preserved');

  const bigStat = await fsp.stat(path.join(destRoot, 'big.bin'));
  check(bigStat.size === 40 * 1024 * 1024, 'multi-chunk file has exact size', `${bigStat.size}`);

  // Per-file accounting, which is what the detail panel draws one bar per file
  // from. The totals agreeing proves nothing here: a sender that credited
  // every byte to the first file would still add up to the right number, and
  // would draw one full bar and four hundred empty ones.
  for (const [side, files] of [['sender', outgoing?.files ?? []], ['receiver', result.files ?? []]]) {
    check(files.length === sourceHashes.size, `the ${side} accounts for every file`,
      `${files.length} of ${sourceHashes.size}`);
    const short = files.filter((f) => (f.received ?? 0) !== f.size);
    check(short.length === 0, `every file is credited its own bytes on the ${side}`,
      short.slice(0, 3).map((f) => `${f.rel} ${f.received}/${f.size}`).join(', '));
    const sum = files.reduce((n, f) => n + (f.received ?? 0), 0);
    check(sum === result.totalSize, `per-file bytes sum to the total on the ${side}`,
      `${sum} vs ${result.totalSize}`);
  }

  receiver.stop();
  await fsp.rm(ROOT, { recursive: true, force: true }).catch(() => {});

  console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('\nTest crashed:', err);
  process.exit(1);
});
