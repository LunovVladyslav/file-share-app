import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fork } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RECEIVER = path.join(HERE, 'receiver.js');

/**
 * Each device needs its own identity key, and that key is a per-process
 * singleton — so tests run the two ends as two processes, the way they run in
 * real life.
 */
export function makeHome(dir, { deviceId, deviceName, ...config }) {
  fs.mkdirSync(dir, { recursive: true });

  const pair = crypto.generateKeyPairSync('x25519');
  const publicJwk = pair.publicKey.export({ format: 'jwk' });
  const privateJwk = pair.privateKey.export({ format: 'jwk' });
  fs.writeFileSync(path.join(dir, 'identity.json'), JSON.stringify({ publicJwk, privateJwk }));

  fs.writeFileSync(path.join(dir, 'config.json'), JSON.stringify({
    deviceId, deviceName, ...config,
  }));

  return { dir, deviceId, deviceName, publicKey: publicJwk.x };
}

/** Pin each device's key on the other, as a completed pairing would. */
export function pairHomes(a, b) {
  writeTrust(a, b);
  writeTrust(b, a);
}

function writeTrust(self, peer) {
  const file = path.join(self.dir, 'peers.json');
  let store = {};
  try {
    store = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch { /* first entry */ }
  store[peer.deviceId] = {
    name: peer.deviceName,
    os: 'test',
    publicKey: peer.publicKey,
    pairedAt: Date.now(),
  };
  fs.writeFileSync(file, JSON.stringify(store, null, 2));
}

/** Start a receiver in its own process and wait until it is listening. */
export function startReceiver(home, port) {
  const child = fork(RECEIVER, [], {
    env: { ...process.env, FLYSHARE_HOME: home.dir, PORT: String(port) },
    stdio: ['ignore', 'inherit', 'inherit', 'ipc'],
  });

  const events = [];
  const waiters = [];
  child.on('message', (message) => {
    if (message.type === 'ready') return;
    const waiter = waiters.find((w) => w.match(message));
    if (waiter) {
      waiters.splice(waiters.indexOf(waiter), 1);
      clearTimeout(waiter.timer);
      waiter.resolve(message);
    }
    events.push(message);
  });

  const handle = {
    child,
    events,
    send: (msg) => child.send(msg),
    /** Wait for the first message matching `match`, checking history first. */
    waitFor(match, timeoutMs = 15000, label = 'message') {
      const existing = events.find(match);
      if (existing) return Promise.resolve(existing);
      return new Promise((resolve, reject) => {
        const waiter = { match, resolve };
        waiter.timer = setTimeout(() => {
          waiters.splice(waiters.indexOf(waiter), 1);
          reject(new Error(`timed out waiting for ${label}`));
        }, timeoutMs);
        waiters.push(waiter);
      });
    },
    async state() {
      handle.send({ cmd: 'state' });
      const reply = await handle.waitFor((m) => m.type === 'state', 5000, 'state');
      events.splice(events.indexOf(reply), 1);
      return reply.payload;
    },
    stop() {
      child.kill();
    },
  };

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('receiver did not start')), 15000);
    child.once('message', (message) => {
      if (message.type === 'ready') {
        clearTimeout(timer);
        resolve(handle);
      }
    });
  });
}
