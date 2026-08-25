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

/* --- reaching one address by hand — §4.2 ----------------------------------
 *
 * Everything else here is multicast and broadcast, which is exactly what a
 * guest network or an access point with client isolation throws away. The
 * unicast path is what is left when discovery finds nothing, so what matters
 * is which datagrams actually go out and where — hence the spy on the socket
 * rather than a second instance, which on one host would be fighting over the
 * same port. */
{
  const sent = [];
  const realCreate = dgram.createSocket;
  dgram.createSocket = (...args) => {
    const socket = realCreate.apply(dgram, args);
    const realSend = socket.send.bind(socket);
    socket.send = (buf, port, address, cb) => {
      sent.push({ address, port, payload: JSON.parse(buf.toString('utf8')) });
      return realSend(buf, port, address, cb);
    };
    return socket;
  };

  const discovery = new Discovery();
  await discovery.start();
  try {
    sent.length = 0;
    discovery.reach('127.0.0.1');
    await sleep(200);

    const direct = sent.filter((s) => s.address === '127.0.0.1');
    check(direct.some((s) => s.payload.t === 'probe'), 'reaching an address sends it a probe');
    // A probe carries only an id. Without the announcement beside it the far
    // side learns nothing about who is asking, and only one of the two devices
    // ends up able to see the other.
    const announce = direct.find((s) => s.payload.t === 'announce');
    check(Boolean(announce), 'and its own announcement, so the far side learns who asked');
    check(announce?.port === Number(process.env.FLYSHARE_DISCOVERY_PORT),
      'addressed to the discovery port', String(announce?.port));
    check(Array.isArray(announce?.payload.addrs) && announce.payload.addrs.length > 0,
      'carrying somewhere to answer');

    // A probe that had to be typed in came from somewhere this device's own
    // broadcasts evidently do not reach, so the answer has to go back to the
    // source rather than out over the same channels that already failed.
    sent.length = 0;
    const speaker = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    await new Promise((resolve) => {
      const probe = Buffer.from(JSON.stringify({ t: 'probe', id: '1111111111111111' }));
      speaker.send(probe, Number(process.env.FLYSHARE_DISCOVERY_PORT), '127.0.0.1', resolve);
    });
    await sleep(300);
    speaker.close();

    const toSource = sent.filter((s) => s.address === '127.0.0.1' && s.payload.t === 'announce');
    check(toSource.length > 0, 'a probe is answered to the address it came from');
  } finally {
    discovery.stop();
    dgram.createSocket = realCreate;
  }
}

/* --- and the endpoint in front of it -------------------------------------- */
{
  const { EventEmitter } = await import('node:events');
  const discovery = new Discovery();
  await discovery.start();

  const idle = () => Object.assign(new EventEmitter(), { transfers: [], pairings: [] });
  const { UiServer } = await import('../src/ui-server.js');
  const ui = new UiServer({ discovery, server: idle(), sender: idle() });
  await ui.start(45787);

  const find = (address) => fetch('http://127.0.0.1:45787/api/peers/find', {
    method: 'POST',
    headers: { 'x-flyshare-token': ui.token, 'content-type': 'application/json' },
    body: JSON.stringify({ address }),
  });

  // The string becomes the destination of a datagram, so anything that is not
  // four octets is refused rather than handed to the resolver.
  for (const bad of ['192.168.1.999', 'flyshare.example.com', '1.2.3', '', '  ']) {
    const res = await find(bad);
    check(res.status === 400, `"${bad}" is refused`, `${res.status}`);
  }

  const own = localInterfaces()[0]?.address;
  if (own) {
    const res = await find(own);
    check(res.status === 400, 'so is this machine\'s own address', `${res.status}`);
  }

  // 198.51.100.0/24 is reserved for documentation: nothing answers, which is
  // the case that has to end in a plain "no" rather than a hang or a throw.
  const quiet = await find('198.51.100.9');
  check(quiet.status === 200, 'an address nobody answers is not an error', `${quiet.status}`);
  check((await quiet.json()).peer === null, 'it simply found nobody');

  ui.stop();
  discovery.stop();
}

fs.rmSync(process.env.FLYSHARE_HOME, { recursive: true, force: true });
console.log(failures === 0 ? '\nDiscovery runs.' : `\n${failures} failure(s).`);
process.exit(failures === 0 ? 0 : 1);
