import net from 'node:net';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { EventEmitter, once } from 'node:events';
import { PROTOCOL_VERSION, loadConfig, platformLabel } from './config.js';
import { encodeFrame, readFrames, readFirstFrame } from './protocol.js';
import { buildManifest } from './manifest.js';
import { peerPublicKey } from './trust.js';
import { initiatePairing } from './pairing.js';
import { newEphemeralKeyPair, deriveSessionKey, secureClient, describeSecurity } from './secure.js';
import { SpeedMeter } from '../util/speed.js';

/**
 * Sending side.
 *
 * Every connection is TLS 1.3 keyed by the pairing, so a peer that was never
 * paired cannot be sent to and there is no unencrypted path to fall back to.
 *
 * Two ways in, one engine underneath:
 *   sendPaths()   - files already on disk, read with parallel positional reads
 *   beginUpload() - files handed over by the browser (drag & drop), streamed
 *                   through without ever touching a staging file
 */
export class Sender extends EventEmitter {
  #sessions = new Map();
  #pairings = new Map();

  get transfers() {
    return [...this.#sessions.values()].map((s) => s.snapshot());
  }

  get pairings() {
    return [...this.#pairings.values()];
  }

  /**
   * Start pairing with a peer. Resolves as soon as the code is on screen —
   * the outcome arrives later as a 'pairing' event, because it depends on a
   * person walking over to the other machine.
   */
  pair(peer) {
    const session = {
      id: crypto.randomUUID(),
      kind: 'pairing',
      direction: 'out',
      peer: { id: peer.id, name: peer.name, os: peer.os },
      code: null,
      status: 'exchanging',
      error: null,
      createdAt: Date.now(),
    };
    this.#pairings.set(session.id, session);
    this.emit('pairing', session);

    const update = (patch) => {
      Object.assign(session, patch);
      this.emit('pairing', session);
    };

    initiatePairing(peer, (code) => update({ code, status: 'awaiting-peer' }))
      .then(() => update({ status: 'paired' }))
      .catch((err) => update({ status: 'failed', error: err.message }))
      .finally(() => setTimeout(() => this.#pairings.delete(session.id), 30000));

    return session;
  }

  cancelPairing(pairingId) {
    const session = this.#pairings.get(pairingId);
    if (!session) return false;
    this.#pairings.delete(pairingId);
    this.emit('pairing', { ...session, status: 'cancelled' });
    return true;
  }

  /** Send files/folders that exist on this machine's disk. */
  async sendPaths(peer, paths) {
    requirePaired(peer);
    const manifest = await buildManifest(paths);
    if (manifest.files.length === 0) throw new Error('nothing to send');

    const session = new SendSession(this, peer, manifest);
    this.#sessions.set(session.id, session);
    this.emit('transfer', session.snapshot());

    session.run().catch(() => { /* status already reflects the failure */ });
    return session.snapshot();
  }

  /** Open a transfer whose bytes will arrive later, chunk by chunk, from the UI. */
  async beginUpload(peer, files) {
    requirePaired(peer);
    const manifest = {
      files: files.map((f) => ({ abs: null, rel: f.rel, size: f.size })),
      totalSize: files.reduce((sum, f) => sum + f.size, 0),
    };
    const session = new SendSession(this, peer, manifest);
    this.#sessions.set(session.id, session);
    this.emit('transfer', session.snapshot());

    await session.open();
    return session.snapshot();
  }

  /** Pipe one browser-supplied chunk straight onto a data connection. */
  async pushChunk(transferId, header, readable) {
    const session = this.#sessions.get(transferId);
    if (!session) throw new Error('unknown transfer');
    return session.pushChunk(header, readable);
  }

  async finishUpload(transferId) {
    const session = this.#sessions.get(transferId);
    if (!session) throw new Error('unknown transfer');
    return session.finish();
  }

  cancel(transferId) {
    const session = this.#sessions.get(transferId);
    if (!session) return false;
    session.cancel('cancelled by user');
    return true;
  }

  notifyChange(session) {
    this.emit('transfer', session.snapshot());
  }
}

class SendSession {
  #control = null;
  #token = null;
  #pool = [];
  #openConns = 0;
  #waiters = [];
  #cancelled = false;
  #doneResolve = null;
  #donePromise = null;
  #pending = new Map();

  constructor(sender, peer, manifest) {
    this.sender = sender;
    this.peer = peer;
    this.config = loadConfig();
    this.id = crypto.randomUUID();
    this.files = manifest.files;
    this.totalSize = manifest.totalSize;
    this.sent = 0;
    this.status = 'connecting';
    this.error = null;
    this.createdAt = Date.now();
    this.startedAt = null;
    this.finishedAt = null;
    this.speed = new SpeedMeter();
    this.streams = clamp(this.config.streams, 1, 16);
  }

  /** Connect, offer the manifest, and wait for the far side to say yes. */
  async open() {
    const socket = await connectSecure(this.peer);
    this.security = describeSecurity(socket);
    this.#control = socket;
    socket.on('error', () => this.#setStatus('failed', 'connection lost'));
    socket.on('close', () => {
      if (this.status === 'sending' || this.status === 'waiting') {
        this.#setStatus('failed', 'receiver disconnected');
      }
    });

    this.#setStatus('waiting');
    socket.write(encodeFrame({
      t: 'offer',
      ver: PROTOCOL_VERSION,
      transferId: this.id,
      from: {
        id: this.config.deviceId,
        name: this.config.deviceName,
        os: platformLabel(),
      },
      files: this.files.map((f) => ({ rel: f.rel, size: f.size })),
      totalSize: this.totalSize,
      streams: this.streams,
    }));

    this.#donePromise = new Promise((resolve) => { this.#doneResolve = resolve; });
    // The waiter is registered before the reader starts delivering, so no
    // frame can slip through between them.
    const pendingResult = this.#waitFor('offer-result', 5 * 60 * 1000);
    readFrames(socket, (frame) => this.#onControlFrame(frame));
    const result = await pendingResult;
    if (!result.accept) {
      this.#setStatus('declined', result.reason ?? 'declined');
      socket.end();
      throw new Error(result.reason ?? 'declined');
    }
    this.#token = result.token;
    this.startedAt = Date.now();
    this.#setStatus('sending');
    return this;
  }

  /** Full disk-to-disk run: offer, then saturate the link with parallel streams. */
  async run() {
    try {
      await this.open();
      const queue = this.#buildQueue();
      const workers = Math.min(this.streams, Math.max(1, queue.length));
      await Promise.all(
        Array.from({ length: workers }, () => this.#diskWorker(queue)),
      );
      await this.finish();
    } catch (err) {
      if (!this.#cancelled && this.status !== 'declined') {
        this.#setStatus('failed', err.message);
      }
      this.#teardown();
      throw err;
    }
  }

  /**
   * Split the manifest into work items. Big files become several chunks so
   * one 8 GB video still uses every stream; small files are one chunk each,
   * which lets workers fan out across a folder of thousands of them.
   */
  #buildQueue() {
    const chunkSize = this.config.chunkSize;
    const queue = [];
    this.files.forEach((file, fileIndex) => {
      if (file.size === 0) return; // already created by the receiver's preallocation
      for (let offset = 0; offset < file.size; offset += chunkSize) {
        queue.push({
          fileIndex,
          offset,
          length: Math.min(chunkSize, file.size - offset),
        });
      }
    });
    return queue;
  }

  async #diskWorker(queue) {
    const socket = await this.#openDataConn();
    try {
      for (;;) {
        if (this.#cancelled) break;
        const item = queue.shift();
        if (!item) break;
        const file = this.files[item.fileIndex];
        socket.write(encodeFrame({ t: 'chunk', ...item }));
        const stream = fs.createReadStream(file.abs, {
          start: item.offset,
          end: item.offset + item.length - 1,
          highWaterMark: this.config.readBufferSize,
        });
        await this.#pump(socket, stream, item.length);
      }
      socket.write(encodeFrame({ t: 'end' }));
      await new Promise((resolve) => socket.end(resolve));
    } catch (err) {
      socket.destroy();
      throw err;
    }
  }

  /** Copy `expected` bytes from a readable onto the socket, respecting backpressure. */
  async #pump(socket, readable, expected) {
    let written = 0;
    for await (const buf of readable) {
      if (this.#cancelled) throw new Error('cancelled');
      written += buf.length;
      if (written > expected) throw new Error('source produced more bytes than declared');
      this.sent += buf.length;
      this.speed.add(buf.length);
      if (!socket.write(buf)) await once(socket, 'drain');
    }
    if (written !== expected) {
      throw new Error(`source ended early: got ${written} of ${expected} bytes`);
    }
  }

  // --- browser-upload path -------------------------------------------------

  async pushChunk(header, readable) {
    if (this.status !== 'sending') throw new Error(`transfer is ${this.status}`);
    const socket = await this.#acquireConn();
    try {
      socket.write(encodeFrame({ t: 'chunk', ...header }));
      await this.#pump(socket, readable, header.length);
      this.#releaseConn(socket);
    } catch (err) {
      // A desynced connection can never be reused: the receiver is now
      // mid-payload and would read the next header as file content.
      socket.destroy();
      this.#openConns -= 1;
      this.cancel(`chunk failed: ${err.message}`);
      throw err;
    }
  }

  async #acquireConn() {
    const pooled = this.#pool.pop();
    if (pooled && !pooled.destroyed) return pooled;
    if (this.#openConns < this.streams) return this.#openDataConn();
    return new Promise((resolve) => this.#waiters.push(resolve));
  }

  #releaseConn(socket) {
    const waiter = this.#waiters.shift();
    if (waiter) waiter(socket);
    else this.#pool.push(socket);
  }

  async #openDataConn() {
    this.#openConns += 1;
    const socket = await connectSecure(this.peer);
    socket.write(encodeFrame({ t: 'data', transferId: this.id, token: this.#token }));
    const { frame } = await readFirstFrame(socket, 20000);
    if (frame.t !== 'data-ok') {
      socket.destroy();
      this.#openConns -= 1;
      throw new Error(frame.reason ?? 'data connection rejected');
    }
    // Nothing else is ever sent back on a data connection; resume so the
    // socket keeps reporting close and error normally.
    socket.resume();
    return socket;
  }

  /** Close the data connections cleanly and wait for the receiver's confirmation. */
  async finish() {
    for (const socket of this.#pool.splice(0)) {
      if (!socket.destroyed) {
        socket.write(encodeFrame({ t: 'end' }));
        socket.end();
      }
    }
    this.#setStatus('finalizing');
    await Promise.race([
      this.#donePromise,
      new Promise((_, reject) => setTimeout(() => reject(new Error('receiver never confirmed')), 120000)),
    ]);
    this.finishedAt = Date.now();
    this.#setStatus('completed');
    this.#control?.end();
    return this.snapshot();
  }

  cancel(reason) {
    if (this.#cancelled || this.status === 'completed') return;
    this.#cancelled = true;
    try { this.#control?.write(encodeFrame({ t: 'cancel', reason })); } catch { /* gone */ }
    this.#setStatus('cancelled', reason);
    this.#teardown();
  }

  #teardown() {
    for (const socket of this.#pool.splice(0)) socket.destroy();
    this.#control?.end();
  }

  #onControlFrame(frame) {
    if (frame.t === 'done') {
      this.#doneResolve?.();
    } else if (frame.t === 'error') {
      this.#setStatus('failed', frame.reason ?? 'receiver reported an error');
      this.#teardown();
    }
    this.#pending?.get(frame.t)?.(frame);
  }

  #waitFor(type, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(type);
        reject(new Error(`timed out waiting for ${type}`));
      }, timeoutMs);
      this.#pending.set(type, (frame) => {
        clearTimeout(timer);
        this.#pending.delete(type);
        resolve(frame);
      });
    });
  }

  #setStatus(status, error) {
    if (this.status === status && !error) return;
    this.status = status;
    if (error) this.error = error;
    this.sender.notifyChange(this);
  }

  snapshot() {
    return {
      id: this.id,
      direction: 'out',
      peer: { id: this.peer.id, name: this.peer.name, os: this.peer.os },
      status: this.status,
      error: this.error,
      totalSize: this.totalSize,
      received: this.sent,
      fileCount: this.files.length,
      files: this.files.map((f) => ({ rel: f.rel, size: f.size })),
      speed: this.speed.bytesPerSecond,
      security: this.security ?? null,
      streams: this.streams,
      startedAt: this.startedAt,
      finishedAt: this.finishedAt,
      createdAt: this.createdAt,
    };
  }
}

/**
 * Dial a peer and hand back an encrypted socket.
 *
 * The plaintext prologue carries nothing secret: two ephemeral public keys.
 * The key that protects the connection mixes that exchange with the secret
 * both devices derived when they were paired, so an impostor gets as far as
 * the TLS handshake and no further.
 */
/**
 * Refuse before anything is read from disk. Pairing is a precondition, not a
 * transfer that happens to fail, so it should surface as an answer to the
 * click rather than as a dead entry in the transfer list.
 */
function requirePaired(peer) {
  if (!peerPublicKey(peer.id)) {
    const err = new Error(`${peer.name} is not paired with this device yet`);
    err.needsPairing = true;
    throw err;
  }
}

export async function connectSecure(peer) {
  const config = loadConfig();
  const peerIdentityKey = peerPublicKey(peer.id);
  if (!peerIdentityKey) {
    throw new Error(`${peer.name} is not paired with this device yet`);
  }

  const socket = await connect(peer.address, peer.port);
  const ephemeral = newEphemeralKeyPair();
  socket.write(encodeFrame({
    t: 'session',
    ver: PROTOCOL_VERSION,
    deviceId: config.deviceId,
    ephPub: ephemeral.publicRaw,
  }));

  const { frame } = await readFirstFrame(socket, 20000);
  if (frame.t !== 'session-ok') {
    socket.destroy();
    const err = new Error(frame.reason ?? 'the other device refused the connection');
    err.needsPairing = Boolean(frame.needsPairing);
    throw err;
  }
  if (frame.deviceId !== peer.id) {
    socket.destroy();
    throw new Error('the device at that address is not the one that was paired');
  }

  const psk = deriveSessionKey({
    ephemeral,
    peerEphemeralRaw: frame.ephPub,
    peerIdentityKey,
    selfId: config.deviceId,
    peerId: peer.id,
  });
  return secureClient(socket, psk);
}

function connect(host, port) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host, port });
    socket.setNoDelay(true);
    const onError = (err) => {
      socket.destroy();
      reject(new Error(`cannot reach ${host}:${port} - ${err.message}`));
    };
    socket.once('error', onError);
    socket.once('connect', () => {
      socket.off('error', onError);
      resolve(socket);
    });
  });
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, Number(value) || min));
}
