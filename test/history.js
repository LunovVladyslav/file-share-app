/**
 * What the app remembers about transfers that are over.
 *
 * Before this existed the list on screen was the process's memory. The point
 * of the store is that it outlives the process, so most of what is worth
 * checking is about the file on disk: that it is written when a transfer
 * settles, that it does not grow without limit, that it reads back, and that
 * a damaged one costs nothing more than the history.
 *
 * Run: node test/history.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const HOME = fs.mkdtempSync(path.join(os.tmpdir(), 'flyshare-history-'));
process.env.FLYSHARE_HOME = HOME;

const history = await import('../src/core/history.js');

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures += 1;
}

const stored = () => JSON.parse(fs.readFileSync(path.join(HOME, 'history.json'), 'utf8'));

/** A transfer snapshot in the shape the server and the sender actually emit. */
function snapshot(patch = {}) {
  return {
    id: 'transfer-1',
    direction: 'in',
    peer: { id: 'aabbccddeeff0011', name: 'Redmi 12', os: 'android' },
    status: 'completed',
    paused: false,
    error: null,
    totalSize: 1024,
    received: 1024,
    fileCount: 2,
    files: [
      { rel: 'a.txt', size: 512, received: 512, path: '/tmp/in/a.txt' },
      { rel: 'b.txt', size: 512, received: 512, path: '/tmp/in/b.txt' },
    ],
    destDir: '/tmp/in',
    speed: 12345678,
    security: 'TLSv1.3 / TLS_AES_128_GCM_SHA256',
    startedAt: 1000,
    finishedAt: 5000,
    createdAt: 900,
    ...patch,
  };
}

console.log('\nTransfer history\n');

/* --- what gets written ---------------------------------------------------- */
{
  check(history.entries().length === 0, 'a fresh profile has no history');

  for (const status of ['pending', 'connecting', 'waiting', 'sending', 'receiving', 'finalizing']) {
    history.record(snapshot({ id: `live-${status}`, status }));
  }
  check(history.entries().length === 0, 'a transfer still in flight is not recorded');

  const entry = history.record(snapshot());
  check(history.entries().length === 1, 'a completed transfer is recorded');
  check(fs.existsSync(path.join(HOME, 'history.json')), 'and it reaches the disk immediately');

  // Everything the list and the detail panel draw has to survive; the panel
  // renders a remembered transfer through the same code as a live one.
  const needed = ['id', 'direction', 'peer', 'status', 'totalSize', 'received',
    'fileCount', 'destDir', 'startedAt', 'finishedAt', 'createdAt'];
  const missing = needed.filter((key) => entry[key] === undefined);
  check(missing.length === 0, 'the record keeps what the interface draws', missing.join(', '));
  check(entry.peer.name === 'Redmi 12', 'including who it was with');

  // And nothing that cannot mean anything afterwards. A file list is the
  // expensive one: four thousand names for something nobody reads twice.
  const dropped = ['files', 'speed', 'security', 'paused', 'canPause'];
  const kept = dropped.filter((key) => entry[key] !== undefined);
  check(kept.length === 0, 'the record drops what cannot mean anything later', kept.join(', '));
  check(entry.stored === true, 'and marks itself as remembered rather than live');
}

/* --- settling more than once ---------------------------------------------- */
{
  history.record(snapshot({ status: 'completed' }));
  history.record(snapshot({ status: 'completed' }));
  const mine = history.entries().filter((e) => e.id === 'transfer-1');
  check(mine.length === 1, 'a transfer that settles twice leaves one entry', `${mine.length}`);
}

/* --- pointing at what arrived --------------------------------------------- */
{
  history.record(snapshot({
    id: 'single',
    fileCount: 1,
    files: [{ rel: 'holiday.mp4', size: 1024, received: 1024, path: '/tmp/in/holiday.mp4' }],
  }));
  const single = history.entries().find((e) => e.id === 'single');
  check(single.filePath === '/tmp/in/holiday.mp4', 'a one-file transfer remembers where that file went');

  const many = history.entries().find((e) => e.id === 'transfer-1');
  check(many.filePath === null, 'a many-file transfer remembers only the folder');
  // Without this a remembered transfer is a dash on a list where every live
  // one is a file name.
  check(many.firstFile === 'a.txt', 'and still knows what it was called', String(many.firstFile));
}

/* --- a failure is still a record ------------------------------------------ */
{
  const before = Date.now();
  history.record(snapshot({ id: 'broken', status: 'failed', error: 'receiver disconnected', finishedAt: null }));
  const broken = history.entries().find((e) => e.id === 'broken');
  check(broken.error === 'receiver disconnected', 'a failure keeps the reason');
  // Nothing sets finishedAt on the way down, and "when did this happen" has
  // to be answerable for the failures too — they are the interesting ones.
  check(broken.finishedAt >= before, 'a transfer that never finished still has a time',
    String(broken.finishedAt));
}

/* --- order and the cap ---------------------------------------------------- */
{
  for (let i = 0; i < 60; i += 1) {
    history.record(snapshot({ id: `bulk-${i}`, createdAt: 10000 + i }));
  }
  const entries = history.entries();
  check(entries.length === 50, 'the file is capped', `${entries.length}`);
  check(entries[0].id === 'bulk-59', 'the newest entry comes first', entries[0].id);
  check(!entries.some((e) => e.id === 'transfer-1'), 'and the oldest fall off the end');
  check(stored().length === 50, 'the cap is on the disk too, not only in memory');
}

/* --- surviving the process ------------------------------------------------ */
{
  const reloaded = await import('../src/core/history.js?reload');
  const entries = reloaded.entries();
  check(entries.length === 50, 'entries survive a reload from disk', `${entries.length}`);
  check(entries[0].id === 'bulk-59', 'in the same order');
}

/* --- clearing ------------------------------------------------------------- */
{
  history.clear();
  check(history.entries().length === 0, 'clear empties the list');
  check(stored().length === 0, 'and the file, not just the copy in memory');
}

/* --- a damaged file ------------------------------------------------------- */
{
  const broken = fs.mkdtempSync(path.join(os.tmpdir(), 'flyshare-history-bad-'));
  fs.writeFileSync(path.join(broken, 'history.json'), '{ this is not json');
  process.env.FLYSHARE_HOME = broken;

  const fresh = await import('../src/core/history.js?corrupt');
  check(fresh.entries().length === 0, 'a damaged file reads as no history rather than a crash');

  process.env.FLYSHARE_HOME = HOME;
  fs.rmSync(broken, { recursive: true, force: true });
}

/* --- forgetting one, rather than all of them ------------------------------ */
{
  history.clear();
  history.record(snapshot({ id: 'keep-me', createdAt: 2 }));
  history.record(snapshot({ id: 'drop-me', createdAt: 1 }));

  check(history.forget('drop-me'), 'a remembered transfer can be forgotten on its own');
  check(history.entries().map((e) => e.id).join(',') === 'keep-me', 'and the rest stay',
    history.entries().map((e) => e.id).join(','));
  check(stored().length === 1, 'the file agrees, not only the copy in memory', `${stored().length}`);
  check(!history.forget('drop-me'), 'forgetting it twice says there was nothing to forget');
  check(!history.forget('never-existed'), 'as does one that was never there');
  history.clear();
}

/* --- which statuses are over ---------------------------------------------- */
{
  const over = ['completed', 'failed', 'declined', 'cancelled'];
  const running = ['pending', 'connecting', 'waiting', 'sending', 'receiving', 'finalizing'];
  check(over.every(history.isFinished), 'every terminal status counts as finished');
  check(!running.some(history.isFinished), 'and no status a transfer can leave on its own does');
}

/* --- clearing the list, with something still running ----------------------
 *
 * "Clear the list" has to mean the stored record and the finished transfers
 * still in memory, or half the list would come straight back on the next
 * frame. The danger in that is the transfer that is still running: dropping
 * it would leave it moving bytes with nothing on screen to cancel it with.
 *
 * The two ends are stubs. What is under test is which transfers the endpoint
 * asks to forget, and the real server and sender guard the same way. */
{
  const { EventEmitter } = await import('node:events');

  const forgotten = [];
  const source = (transfers) => Object.assign(new EventEmitter(), {
    transfers,
    pairings: [],
    forget(id) { forgotten.push(id); return true; },
  });

  const server = source([
    { id: 'still-going', direction: 'in', status: 'receiving', createdAt: 3 },
    { id: 'not-answered', direction: 'in', status: 'pending', createdAt: 2 },
    { id: 'over', direction: 'in', status: 'completed', createdAt: 1 },
  ]);
  const sender = source([
    { id: 'sending-now', direction: 'out', status: 'sending', createdAt: 5 },
    { id: 'gave-up', direction: 'out', status: 'failed', createdAt: 4 },
  ]);
  const discovery = Object.assign(new EventEmitter(), { peers: [] });

  const { UiServer } = await import('../src/ui-server.js');
  const ui = new UiServer({ discovery, server, sender });
  await ui.start(45897);

  history.record(snapshot({ id: 'remembered' }));
  check(ui.state().transfers.length === 6, 'the list is the live transfers and the remembered ones',
    `${ui.state().transfers.length}`);

  const response = await fetch('http://127.0.0.1:45897/api/history/clear', {
    method: 'POST',
    headers: { 'x-flyshare-token': ui.token, 'content-type': 'application/json' },
  });
  check(response.status === 200, 'clearing answers', `${response.status}`);
  check(history.entries().length === 0, 'the stored record is gone');
  check(forgotten.sort().join(',') === 'gave-up,over',
    'the finished transfers are dropped from memory too', forgotten.join(','));
  check(!forgotten.includes('still-going') && !forgotten.includes('sending-now'),
    'and a transfer still moving bytes is left alone');
  check(!forgotten.includes('not-answered'), 'as is one nobody has answered yet');

  ui.stop();
  history.clear();
}

/* --- taking one card off the list -----------------------------------------
 *
 * "Clear the list" is all of them or none, which is no use to someone who
 * wants one failed attempt gone and the rest kept. Removing a single card has
 * to reach both halves of the list — the finished transfer still in memory and
 * the remembered one behind it — and must leave a running transfer alone. */
{
  const { EventEmitter } = await import('node:events');

  const forgotten = [];
  const source = (transfers) => Object.assign(new EventEmitter(), {
    transfers,
    pairings: [],
    // The real ends refuse to forget a transfer that is still running. So
    // does this one, or the check below would prove nothing.
    forget(id) {
      const found = transfers.find((x) => x.id === id);
      if (!found || !history.isFinished(found.status)) return false;
      forgotten.push(id);
      return true;
    },
  });

  const server = source([{ id: 'over', direction: 'in', status: 'completed', createdAt: 1 }]);
  const sender = source([
    { id: 'gave-up', direction: 'out', status: 'failed', createdAt: 4 },
    { id: 'sending-now', direction: 'out', status: 'sending', createdAt: 5 },
  ]);
  const discovery = Object.assign(new EventEmitter(), { peers: [] });

  const { UiServer } = await import('../src/ui-server.js');
  const ui = new UiServer({ discovery, server, sender });
  await ui.start(45895);

  const drop = (id) => fetch('http://127.0.0.1:45895/api/forget', {
    method: 'POST',
    headers: { 'x-flyshare-token': ui.token, 'content-type': 'application/json' },
    body: JSON.stringify({ transferId: id }),
  });

  history.record(snapshot({ id: 'remembered' }));

  let response = await drop('gave-up');
  check(response.status === 200, 'a failed transfer can be removed on its own', `${response.status}`);
  check(forgotten.join(',') === 'gave-up', 'and it is the only one dropped', forgotten.join(','));
  check(history.entries().length === 1, 'the remembered ones are left where they were');

  response = await drop('remembered');
  check(response.status === 200, 'so can one that only history still holds', `${response.status}`);
  check(history.entries().length === 0, 'and the stored record goes with the card');

  response = await drop('sending-now');
  check(response.status === 404, 'a transfer still moving bytes is refused', `${response.status}`);
  check(!forgotten.includes('sending-now'), 'and stays on the list, cancel button and all');

  response = await drop('never-existed');
  check(response.status === 404, 'as is a card that was never there', `${response.status}`);

  ui.stop();
  history.clear();
}

fs.rmSync(HOME, { recursive: true, force: true });
console.log(failures === 0 ? '\nHistory holds.' : `\n${failures} failure(s).`);
process.exit(failures === 0 ? 0 : 1);
