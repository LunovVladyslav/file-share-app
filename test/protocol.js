/**
 * Protocol, consent and authorization tests.
 *
 * These guard the invariants that are easy to break and hard to notice:
 * nothing is written without the user saying yes, an unpaired device gets
 * nowhere, data connections need the token handed out at accept time, and a
 * hostile manifest cannot write outside the download folder.
 *
 * Run: node test/protocol.js
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { makeHome, pairHomes, startReceiver } from './helpers/instance.js';

const ROOT = path.join(os.tmpdir(), `flyshare-proto-${process.pid}`);
const SRC = path.join(ROOT, 'src');
const DEST = path.join(ROOT, 'dest');
const PORT = 45897;
const STRANGER_PORT = 45898;

await fsp.rm(ROOT, { recursive: true, force: true });
await fsp.mkdir(SRC, { recursive: true });
await fsp.mkdir(DEST, { recursive: true });
await fsp.writeFile(path.join(SRC, 'doc.txt'), 'hello from the other machine\n');

const senderHome = makeHome(path.join(ROOT, 'sender'), {
  deviceId: 'sender-device', deviceName: 'test-sender', streams: 2,
});
const receiverHome = makeHome(path.join(ROOT, 'receiver'), {
  deviceId: 'receiver-device', deviceName: 'test-receiver',
  autoAccept: false, downloadDir: DEST,
});
const strangerHome = makeHome(path.join(ROOT, 'stranger'), {
  deviceId: 'stranger-device', deviceName: 'test-stranger',
  autoAccept: true, downloadDir: path.join(ROOT, 'stranger-inbox'),
});
pairHomes(senderHome, receiverHome);
// The sender knows the stranger, but the stranger has never heard of it —
// exactly the one-sided state an attacker would be in.
pairOneWay(senderHome, strangerHome);

function pairOneWay(self, peer) {
  const file = path.join(self.dir, 'peers.json');
  const store = JSON.parse(fs.readFileSync(file, 'utf8'));
  store[peer.deviceId] = { name: peer.deviceName, os: 'test', publicKey: peer.publicKey, pairedAt: Date.now() };
  fs.writeFileSync(file, JSON.stringify(store, null, 2));
}

process.env.FLYSHARE_HOME = senderHome.dir;
const { Sender, connectSecure } = await import('../src/core/client.js');
const { FrameChannel, encodeFrame } = await import('../src/core/protocol.js');

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures += 1;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const peer = {
  id: receiverHome.deviceId, name: receiverHome.deviceName,
  os: 'test', address: '127.0.0.1', port: PORT,
};

async function main() {
  const receiver = await startReceiver(receiverHome, PORT);
  const stranger = await startReceiver(strangerHome, STRANGER_PORT);
  const sender = new Sender();

  // --- consent ------------------------------------------------------------
  console.log('\nconsent:');
  await sender.sendPaths(peer, [path.join(SRC, 'doc.txt')]);
  const offered = await receiver.waitFor((m) => m.type === 'offer', 15000, 'the offer');
  check(true, 'offer reaches the receiver');

  await sleep(1200);
  let state = await receiver.state();
  let current = state.transfers.find((t) => t.id === offered.payload.id);
  check(current?.status === 'pending', 'stays pending with no answer', current?.status);
  check(fs.readdirSync(DEST).length === 0, 'nothing written to disk while pending');

  receiver.send({ cmd: 'respond', transferId: offered.payload.id, accept: true });
  const finished = await receiver.waitFor(
    (m) => m.type === 'transfer' && m.payload.status === 'completed', 20000, 'completion',
  );
  check(true, 'completes once accepted');
  check(fs.existsSync(path.join(DEST, 'doc.txt')), 'file lands in the download folder');

  console.log('\nencryption:');
  check(/^TLSv1\.3/.test(finished.payload.security ?? ''),
    'the transfer ran over TLS 1.3', finished.payload.security);

  // --- decline ------------------------------------------------------------
  console.log('\ndecline:');
  await fsp.rm(DEST, { recursive: true, force: true });
  await fsp.mkdir(DEST, { recursive: true });
  receiver.events.length = 0;

  sender.sendPaths(peer, [path.join(SRC, 'doc.txt')]).catch(() => {});
  const second = await receiver.waitFor((m) => m.type === 'offer', 15000, 'the second offer');
  receiver.send({ cmd: 'respond', transferId: second.payload.id, accept: false });
  await receiver.waitFor(
    (m) => m.type === 'transfer' && m.payload.status === 'declined', 15000, 'the decline',
  );
  check(true, 'declined offer is marked declined');
  check(fs.readdirSync(DEST).length === 0, 'declined offer writes nothing');

  // --- authorization ------------------------------------------------------
  console.log('\nauthorization:');
  let refused = null;
  await connectSecure({ ...peer, id: 'never-paired-device' }).catch((err) => { refused = err; });
  check(refused !== null && /not paired/i.test(refused.message),
    'dialling a device this one never paired with is refused', refused?.message);

  refused = null;
  await connectSecure({
    id: strangerHome.deviceId, name: strangerHome.deviceName,
    os: 'test', address: '127.0.0.1', port: STRANGER_PORT,
  }).catch((err) => { refused = err; });
  check(refused !== null && refused.needsPairing === true,
    'a device that has not paired us back refuses the connection', refused?.message);

  // A real, paired session — but with a token it was never given.
  receiver.events.length = 0;
  sender.sendPaths(peer, [path.join(SRC, 'doc.txt')]).catch(() => {});
  const third = await receiver.waitFor((m) => m.type === 'offer', 15000, 'the third offer');
  receiver.send({ cmd: 'respond', transferId: third.payload.id, accept: true });
  await sleep(500);

  const attacker = new FrameChannel(await connectSecure(peer));
  attacker.write({ t: 'data', transferId: third.payload.id, token: 'not-the-real-token' });
  const reply = await attacker.read(10000);
  check(reply.t === 'data-err', 'a data connection with the wrong token is refused', reply.t);
  attacker.socket.destroy();

  const unknown = new FrameChannel(await connectSecure(peer));
  unknown.write({ t: 'data', transferId: 'no-such-transfer', token: 'x' });
  const reply2 = await unknown.read(10000);
  check(reply2.t === 'data-err', 'a data connection for an unknown transfer is refused', reply2.t);
  unknown.socket.destroy();
  await sleep(800);

  // --- pause, resume and cancel on the control connection -----------------
  //
  // §9.5. These reach the receiver as frames on the control connection long
  // after the offer, and the reply is a status the interface draws — a bar
  // that has stopped with nothing to explain it is the failure to avoid.
  console.log('\npause, resume and cancel:');
  receiver.events.length = 0;

  const live = new FrameChannel(await connectSecure(peer));
  live.write({
    t: 'offer',
    ver: 2,
    transferId: 'pause-test',
    from: { id: senderHome.deviceId, name: 'test-sender', os: 'test' },
    files: [{ rel: 'held.bin', size: 4 }],
    totalSize: 4,
    streams: 1,
  });
  const held = await receiver.waitFor((m) => m.type === 'offer', 15000, 'the offer');
  receiver.send({ cmd: 'respond', transferId: held.payload.id, accept: true });
  const accepted = await live.read(15000);
  check(accepted.t === 'offer-result' && accepted.accept === true, 'a transfer is under way');

  const flagged = async (want, label) => {
    const seen = await receiver.waitFor(
      (m) => m.type === 'transfer' && m.payload.id === 'pause-test' && m.payload.paused === want,
      10000, label,
    );
    return seen.payload;
  };

  live.write({ t: 'pause', transferId: 'pause-test' });
  check((await flagged(true, 'the pause to register')).paused === true,
    'pausing the sender shows on the receiving side');

  live.write({ t: 'resume', transferId: 'pause-test' });
  check((await flagged(false, 'the resume to register')).paused === false,
    'and so does resuming it');

  live.write({ t: 'cancel', transferId: 'pause-test', reason: 'changed my mind' });
  const stopped = await receiver.waitFor(
    (m) => m.type === 'transfer' && m.payload.id === 'pause-test' && m.payload.status === 'cancelled',
    10000, 'the cancel to land',
  );
  check(stopped.payload.status === 'cancelled', 'and cancelling ends it', stopped.payload.error ?? '');
  live.socket.destroy();

  // --- path safety --------------------------------------------------------
  console.log('\npath safety:');
  await fsp.rm(DEST, { recursive: true, force: true });
  await fsp.mkdir(DEST, { recursive: true });
  const escapeTarget = path.resolve(DEST, '..', 'ESCAPED.txt');
  await fsp.rm(escapeTarget, { force: true });
  receiver.events.length = 0;

  const payload = Buffer.from('pwned');
  const control = new FrameChannel(await connectSecure(peer));
  control.write({
    t: 'offer',
    ver: 2,
    transferId: 'traversal-test',
    from: { id: senderHome.deviceId, name: 'test-sender', os: 'test' },
    files: [{ rel: '../../ESCAPED.txt', size: payload.length }],
    totalSize: payload.length,
    streams: 1,
  });
  const hostile = await receiver.waitFor((m) => m.type === 'offer', 15000, 'the hostile offer');
  receiver.send({ cmd: 'respond', transferId: hostile.payload.id, accept: true });

  const result = await control.read(15000);
  check(result.t === 'offer-result' && result.accept === true,
    'hostile manifest is accepted but rewritten');

  const data = new FrameChannel(await connectSecure(peer));
  data.write({ t: 'data', transferId: 'traversal-test', token: result.token });
  await data.read(10000);
  data.socket.write(encodeFrame({ t: 'chunk', fileIndex: 0, offset: 0, length: payload.length }));
  data.socket.write(payload);
  await sleep(1000);

  check(!fs.existsSync(escapeTarget), 'nothing is written outside the download folder');
  const landed = fs.readdirSync(DEST, { recursive: true })
    .filter((f) => String(f).includes('ESCAPED'));
  check(landed.length === 1, 'the file is contained inside it instead', landed.join(', '));

  data.socket.destroy();
  control.socket.destroy();
  receiver.stop();
  stranger.stop();
  await sleep(300);
  await fsp.rm(ROOT, { recursive: true, force: true }).catch(() => {});
}

main()
  .then(() => {
    console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`);
    process.exit(failures === 0 ? 0 : 1);
  })
  .catch((err) => {
    console.error('\nTest crashed:', err);
    process.exit(1);
  });
