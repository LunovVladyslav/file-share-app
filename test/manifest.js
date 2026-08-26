/**
 * Large manifests — docs/PROTOCOL.md §9.1.
 *
 * A folder of seventy thousand files is a file list several megabytes long,
 * and no frame may exceed 4 MiB. These cover the split, the reassembly, and
 * the two ways a sender can get the pages wrong.
 *
 * The sender here runs with a deliberately tiny manifest budget, so a few
 * hundred short names take the same path seventy thousand real ones would.
 *
 * Run: node test/manifest.js
 */
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { makeHome, pairHomes, startReceiver } from './helpers/instance.js';

const ROOT = path.join(os.tmpdir(), `flyshare-manifest-${process.pid}`);
const SRC = path.join(ROOT, 'src');
const DEST = path.join(ROOT, 'dest');
const PORT = 45896;

await fsp.rm(ROOT, { recursive: true, force: true });
await fsp.mkdir(SRC, { recursive: true });
await fsp.mkdir(DEST, { recursive: true });

const senderHome = makeHome(path.join(ROOT, 'sender'), {
  deviceId: 'sender-device', deviceName: 'test-sender', streams: 2,
  manifestBudget: 4096,
});
const receiverHome = makeHome(path.join(ROOT, 'receiver'), {
  deviceId: 'receiver-device', deviceName: 'test-receiver',
  autoAccept: true, downloadDir: DEST,
});
pairHomes(senderHome, receiverHome);

process.env.FLYSHARE_HOME = senderHome.dir;
const { Sender, connectSecure } = await import('../src/core/client.js');
const {
  pageManifest, peerSupports, encodeFrame, FrameChannel, MAX_FRAME, MANIFEST_PAGES,
} = await import('../src/core/protocol.js');

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures += 1;
}

const peer = {
  id: receiverHome.deviceId, name: receiverHome.deviceName,
  os: 'test', address: '127.0.0.1', port: PORT,
};

function offerHead(transferId, fileCount) {
  return {
    t: 'offer',
    ver: 2,
    transferId,
    from: { id: senderHome.deviceId, name: 'test-sender', os: 'test' },
    files: [],
    paged: true,
    fileCount,
    totalSize: 0,
    streams: 1,
  };
}

/** Splitting a file list, with no network in sight. */
function splitting() {
  console.log('\nsplitting a file list:');

  const small = [{ rel: 'a.txt', size: 1 }, { rel: 'b/c.txt', size: 2 }];
  const one = pageManifest(small);
  check(one.length === 1, 'a list that fits stays in one page', `${one.length} page(s)`);
  check(JSON.stringify(one[0]) === JSON.stringify(small), 'and comes back unchanged');

  // The shape of the problem that started this: a macOS home folder.
  const many = Array.from({ length: 69686 }, (_, i) => ({
    rel: `Home/Library/Application Support/Vendor/Caches/entry_${i}/data.bin`,
    size: i * 7919,
  }));
  const pages = pageManifest(many);
  check(pages.length > 1, `${many.length} files do not`, `${pages.length} pages`);

  const largest = Math.max(...pages.map((p) => encodeFrame({ t: 'offer-files', files: p }).length));
  check(largest <= MAX_FRAME, 'every page fits in a frame', `largest ${largest} of ${MAX_FRAME}`);

  const rejoined = pages.flat();
  check(rejoined.length === many.length, 'the pages account for every file', `${rejoined.length}`);
  check(
    rejoined.every((f, i) => f.rel === many[i].rel && f.size === many[i].size),
    'in the original order, with every size intact',
  );

  // A name costs the bytes it becomes, not the characters it looks like: one
  // Cyrillic filename is twice its own length once it is UTF-8 on the wire.
  const budget = 8 * 1024;
  const cyrillic = Array.from({ length: 4000 }, (_, i) => ({ rel: `Документи/звіт-${i}.pdf`, size: i }));
  const tight = pageManifest(cyrillic, budget);
  const worst = Math.max(...tight.map((p) => Buffer.byteLength(JSON.stringify(p))));
  check(worst <= budget, 'pages are budgeted in bytes, not characters', `largest ${worst} of ${budget}`);
  check(tight.flat().length === cyrillic.length, 'and lose nothing in the counting');
}

/** What the receiver does with pages, including pages that do not add up. */
async function pagesOnTheWire(receiver) {
  console.log('\npages on the wire:');

  const control = new FrameChannel(await connectSecure(peer));
  check(
    peerSupports(control.socket, MANIFEST_PAGES),
    'the session handshake says the far side can read pages',
    (control.socket.peerCaps ?? []).join(', '),
  );

  const files = Array.from({ length: 250 }, (_, i) => ({ rel: `paged/file_${i}.txt`, size: 0 }));
  control.write(offerHead('paged-ok', files.length));
  for (let i = 0; i < files.length; i += 40) {
    control.write({ t: 'offer-files', transferId: 'paged-ok', files: files.slice(i, i + 40) });
  }
  control.write({ t: 'offer-end', transferId: 'paged-ok' });

  const answer = await control.read(10000);
  check(answer.t === 'offer-result' && answer.accept === true,
    'a manifest sent in pages is accepted', answer.reason ?? '');
  const asked = receiver.events.find((e) => e.type === 'offer' && e.payload.id === 'paged-ok');
  check(asked?.payload.fileCount === files.length,
    'and the question names the whole transfer, not the first page',
    `${asked?.payload.fileCount} of ${files.length}`);
  control.socket.destroy();

  // Pages that do not match the count they promised.
  const short = new FrameChannel(await connectSecure(peer));
  short.write(offerHead('paged-short', 10));
  short.write({ t: 'offer-files', transferId: 'paged-short', files: [{ rel: 'a.txt', size: 0 }] });
  short.write({ t: 'offer-end', transferId: 'paged-short' });
  const refused = await short.read(10000);
  check(refused.t === 'offer-result' && refused.accept === false,
    'a manifest that does not add up is refused', refused.reason ?? '');
  check(!receiver.events.some((e) => e.type === 'offer' && e.payload.id === 'paged-short'),
    'and nobody is asked about it');
  short.socket.destroy();

  // A count no honest sender would offer.
  const huge = new FrameChannel(await connectSecure(peer));
  huge.write(offerHead('paged-huge', 5000001));
  const capped = await huge.read(10000);
  check(capped.t === 'offer-result' && capped.accept === false,
    'an absurd file count is refused before a byte is held', capped.reason ?? '');
  huge.socket.destroy();
}

/** The whole thing, over a real encrypted link, with real files. */
async function pagedTransfer(receiver, sender) {
  console.log('\na paged transfer end to end:');

  const wanted = new Map();
  for (let i = 0; i < 300; i += 1) {
    const rel = `pack/dir_${i % 12}/file_${i}.txt`;
    const abs = path.join(SRC, rel);
    await fsp.mkdir(path.dirname(abs), { recursive: true });
    const body = `contents of file ${i}\n`;
    await fsp.writeFile(abs, body);
    wanted.set(rel, body);
  }

  // Matched by id: earlier checks left their own completed transfers behind,
  // and waitFor is happy to hand back one of those instead.
  const started = await sender.sendPaths(peer, [path.join(SRC, 'pack')]);
  const finished = await receiver.waitFor(
    (m) => m.type === 'transfer' && m.payload.id === started.id && m.payload.status === 'completed',
    30000,
    'the transfer to finish',
  );
  check(finished.payload.fileCount === wanted.size,
    'every file in the manifest arrived', `${finished.payload.fileCount} of ${wanted.size}`);

  let wrong = 0;
  let missing = 0;
  for (const [rel, body] of wanted) {
    const landed = path.join(finished.payload.destDir, rel);
    try {
      if (await fsp.readFile(landed, 'utf8') !== body) wrong += 1;
    } catch {
      missing += 1;
    }
  }
  check(missing === 0, 'each one is where the manifest said it would be', `${missing} missing`);
  check(wrong === 0, 'holding what it held on the sender', `${wrong} differ`);
}

/**
 * What the interface is told about a transfer, which must not grow with the
 * number of files in it. The state goes out three times a second while
 * anything is moving; a file list in it made that megabytes per frame.
 */
async function whatTheInterfaceIsTold(sender) {
  console.log('\nwhat the interface is told:');

  const snapshot = sender.transfers[0];
  check(snapshot.files === undefined, 'a snapshot carries no file list');
  check(typeof snapshot.firstFile === 'string',
    'but does carry the one name the card shows', snapshot.firstFile ?? 'null');

  const { EventEmitter } = await import('node:events');
  const { UiServer } = await import('../src/ui-server.js');
  const idle = Object.assign(new EventEmitter(), { transfers: [], pairings: [], forget: () => false });
  const ui = new UiServer({
    discovery: Object.assign(new EventEmitter(), { peers: [] }), server: idle, sender,
  });

  const frame = JSON.stringify(ui.state()).length;
  check(frame < 4096, `${snapshot.fileCount} files still make a small frame`, `${frame} bytes`);

  const rows = sender.fileRows(snapshot.id, 500);
  check(rows?.files.length === snapshot.fileCount,
    'and the drawer can still ask for every row it draws', `${rows?.files.length}`);
  check(rows.total === snapshot.fileCount, 'and is told how many there are in all', `${rows.total}`);
  check(rows.files.every((f) => typeof f.rel === 'string' && typeof f.received === 'number'),
    'each row carrying what one bar per file needs');
  check(sender.fileRows(snapshot.id, 10).files.length === 10,
    'never more than it asked for', `${sender.fileRows(snapshot.id, 10).files.length}`);
  check(sender.fileRows('no-such-transfer', 10) === null, 'and nothing at all for a transfer that is gone');
}

/**
 * Counting a folder is visible while it happens, and can be stopped.
 *
 * The walk takes milliseconds on three hundred files and seconds on seventy
 * thousand, so nothing here races it: what is checked is the order of the
 * statuses the sender announces, which is the same at either size.
 */
async function countingIsVisible(sender) {
  console.log('\ncounting a folder:');

  const { buildManifest } = await import('../src/core/manifest.js');
  const pack = path.join(SRC, 'pack');

  const reports = [];
  const whole = await buildManifest([pack], { onProgress: (found) => reports.push(found) });
  check(whole.files.length === 300, 'the walk finds every file', `${whole.files.length}`);
  check(whole.stopped === false, 'and says it was not cut short');
  check(reports.length > 0 && reports[0].files === 1,
    'reporting what it has found from the first file on', `${reports.length} report(s)`);
  check(reports.at(-1).first === whole.files[0].rel,
    'including the name the card is called by', reports.at(-1).first ?? 'null');

  const halted = await buildManifest([pack], { stopped: () => true });
  check(halted.stopped === true, 'a walk told to stop says so');
  check(halted.files.length === 0, 'and stops rather than finishing and being ignored',
    `${halted.files.length} files`);

  // The card is on the list before the walk starts, which is the whole point:
  // seventy thousand files are seconds of counting, and an empty window for
  // those seconds is indistinguishable from a hung one.
  const seen = [];
  const watch = (s) => seen.push({ id: s.id, status: s.status });
  sender.on('transfer', watch);
  const started = await sender.sendPaths(peer, [pack]);
  sender.off('transfer', watch);
  const mine = seen.filter((s) => s.id === started.id).map((s) => s.status);
  check(mine[0] === 'scanning', 'the card appears before the counting does',
    mine.slice(0, 3).join(' -> '));

  // Cancelling while it counts has to stop the walk, not just discard it.
  const cancelWhileScanning = (s) => { if (s.status === 'scanning') sender.cancel(s.id); };
  sender.on('transfer', cancelWhileScanning);
  const stopped = await sender.sendPaths(peer, [pack]);
  sender.off('transfer', cancelWhileScanning);
  const after = sender.transfers.find((x) => x.id === stopped.id);
  check(after?.status === 'cancelled', 'cancelling while counting ends it there', after?.status);
  check(after?.fileCount === 0, 'with nothing queued to send', `${after?.fileCount}`);
}

async function main() {
  const receiver = await startReceiver(receiverHome, PORT);
  const sender = new Sender();
  try {
    splitting();
    await pagesOnTheWire(receiver);
    await pagedTransfer(receiver, sender);
    await whatTheInterfaceIsTold(sender);
    await countingIsVisible(sender);
  } finally {
    receiver.stop();
    await fsp.rm(ROOT, { recursive: true, force: true }).catch(() => {});
  }
}

main()
  .then(() => {
    console.log(failures === 0 ? '\nManifests hold.' : `\n${failures} check(s) failed.`);
    process.exit(failures === 0 ? 0 : 1);
  })
  .catch((err) => {
    console.error('\nTest crashed:', err);
    process.exit(1);
  });
