/**
 * Discovery, actually running.
 *
 * This suite exists because nothing else started it. Everything else drives
 * the transfer server directly, so a change to discovery could pass the whole
 * suite and still kill the app four seconds after launch — which is exactly
 * what a missing import did: the module loaded fine, and the reference only
 * failed when the reap timer first fired.
 *
 * So the point here is elapsed time. A test that starts discovery and stops it
 * immediately proves almost nothing.
 *
 * Run: node test/discovery.js
 */
import dgram from 'node:dgram';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';

process.env.FLYSHARE_HOME = fs.mkdtempSync(path.join(os.tmpdir(), 'flyshare-discovery-'));
// Ports of its own, so this never collides with a FlyShare someone is using.
process.env.FLYSHARE_DISCOVERY_PORT = '45788';
process.env.FLYSHARE_TRANSFER_PORT = '45789';

const { Discovery, pickReachableAddress, localInterfaces } = await import('../src/core/discovery.js');
const { noteContact, lastContact, forgetContact } = await import('../src/core/presence.js');
const { PEER_TTL_MS } = await import('../src/core/config.js');

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures += 1;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

console.log('\nDiscovery\n');

{
  check(localInterfaces().length > 0, 'this machine reports a usable interface');
  check(
    pickReachableAddress([], '192.168.1.5') === '192.168.1.5',
    'an empty advertisement falls back to the packet source',
  );
}

/* --- the reap sweep --------------------------------------------------------
 *
 * The timer fires every PEER_TTL_MS / 3. Waiting past one full sweep is the
 * whole point: a reference error in there takes the process down, and it is
 * invisible until the first tick. */
{
  const discovery = new Discovery();
  let died = null;
  const onError = (err) => { died = err; };
  process.once('uncaughtException', onError);

  await discovery.start();

  // A peer has to be in the table, or the sweep loops over nothing and a
  // reference error inside it never fires. The first version of this test
  // passed with the bug still in place for exactly that reason.
  const speaker = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  const announcement = Buffer.from(JSON.stringify({
    t: 'announce',
    id: 'ffffffffffffffff',
    name: 'Imaginary device',
    os: 'test',
    port: 45789,
    ver: 2,
    addrs: ['127.0.0.1'],
  }));
  await new Promise((resolve) => {
    speaker.send(announcement, Number(process.env.FLYSHARE_DISCOVERY_PORT), '127.0.0.1', resolve);
  });
  await sleep(500);
  check(discovery.peers.some((p) => p.id === 'ffffffffffffffff'), 'an announcement is registered');

  await sleep((PEER_TTL_MS / 3) + 1500);
  check(died === null, 'survives a reap sweep with a peer in the table', died?.message ?? '');
  check(Array.isArray(discovery.peers), 'still answers for its peer list');

  speaker.close();
  discovery.stop();
  process.removeListener('uncaughtException', onError);
}

/* --- presence beats silence ------------------------------------------------
 *
 * A device being transferred to is the one most likely to have its
 * announcements dropped, because the transfer is what fills the air. A
 * connection that succeeded has to count for more than a datagram that may
 * never have left the radio. */
{
  const id = 'aaaaaaaaaaaaaaaa';
  forgetContact(id);
  check(lastContact(id) === 0, 'an unknown device has no contact time');

  noteContact(id);
  check(Date.now() - lastContact(id) < 1000, 'a completed connection is recorded');

  const stale = Date.now() - PEER_TTL_MS - 5000;
  check(
    Math.max(stale, lastContact(id)) > Date.now() - PEER_TTL_MS,
    'contact keeps a peer that stopped announcing',
  );

  forgetContact(id);
  check(
    Math.max(stale, lastContact(id)) < Date.now() - PEER_TTL_MS,
    'and without it the same peer expires',
  );
}

fs.rmSync(process.env.FLYSHARE_HOME, { recursive: true, force: true });
console.log(failures === 0 ? '\nDiscovery runs.' : `\n${failures} failure(s).`);
process.exit(failures === 0 ? 0 : 1);
