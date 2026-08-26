import net from 'node:net';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { EventEmitter, once } from 'node:events';
import { PROTOCOL_VERSION, loadConfig, platformLabel } from './config.js';
import {
  encodeFrame, readFrames, readFirstFrame,
  pageManifest, peerSupports, CAPABILITIES, MANIFEST_PAGES,
} from './protocol.js';
import { buildManifest } from './manifest.js';
import { peerPublicKey } from './trust.js';
import { initiatePairing } from './pairing.js';
import { newEphemeralKeyPair, deriveSessionKey, secureClient, describeSecurity } from './secure.js';
import { noteContact } from './presence.js';
import { isFinished } from './history.js';
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

    // The session is put on the list before the folder has been walked, so a
    // drop of seventy thousand files shows a card that counts them rather
    // than several seconds of a window that looks stuck.
    const session = new SendSession(this, peer, { files: [], totalSize: 0 });
    session.beginScan();
    this.#sessions.set(session.id, session);
    this.emit('transfer', session.snapshot());

    const manifest = await buildManifest(paths, {
      onProgress: (found) => session.scanProgress(found),
      stopped: () => session.cancelled,
    });

    // Cancelled while counting. The card says so and stays until it is
    // dismissed, the same as any other transfer that ended early.
    if (manifest.stopped) return session.snapshot();

    if (manifest.files.length === 0) {
      this.#sessions.delete(session.id);
      // The payload is beside the point: this is how the interface is told to
      // look at the list again, and the card is no longer in it.
      this.notifyChange(session);
      throw new Error('nothing to send');
    }

    session.setManifest(manifest);
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

  pause(transferId) {
    const session = this.#sessions.get(transferId);
    if (!session?.pause()) return false;
    this.emit('transfer', session.snapshot());
    return true;
  }

  resume(transferId) {
    const session = this.#sessions.get(transferId);
    if (!session?.resume()) return false;
    this.emit('transfer', session.snapshot());
    return true;
  }

  cancel(transferId) {
    const session = this.#sessions.get(transferId);
    if (!session) return false;
    session.cancel('cancelled by user');
    return true;
  }

  /** The first `limit` file rows of one transfer, for the detail drawer. */
  fileRows(transferId, limit) {
    const session = this.#sessions.get(transferId);
    if (!session) return null;
    return { total: session.files.length, files: session.fileRows(limit) };
  }

  /** Drop a finished session from the live list; history keeps the record. */
  forget(transferId) {
    const session = this.#sessions.get(transferId);
    if (!session || !isFinished(session.status)) return false;
    this.#sessions.delete(transferId);
    return true;
  }

  notifyChange(session) {
    this.emit('transfer', session.snapshot());
  }
}

class SendSession {
  #canPause = false;
  #paused = false;
  #waitingOnResume = [];
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
    this.preparing = null;
    this.scanning = null;
    this.error = null;
    this.createdAt = Date.now();
    this.startedAt = null;
    this.finishedAt = null;
    this.speed = new SpeedMeter();
    this.streams = clamp(this.config.streams, 1, 16);
  }

  /**
   * A session exists before its manifest does. These three carry it from
   * "counting files" to "ready to connect" without the card ever leaving the
   * list or changing shape.
   */
  beginScan() {
    this.scanning = { files: 0, bytes: 0, first: null };
    this.status = 'scanning';
  }

  scanProgress(found) {
    if (!this.scanning) return;
    this.scanning = found;
    this.sender.notifyChange(this);
  }

  setManifest(manifest) {
    this.files = manifest.files;
    this.totalSize = manifest.totalSize;
    this.scanning = null;
    this.#setStatus('connecting');
  }

  /** Asked by the walk between entries, so cancelling actually stops it. */
  get cancelled() {
    return this.#cancelled;
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
    this.#sendOffer(socket);

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
    this.#canPause = Boolean(result.canPause);
    this.startedAt = Date.now();
    this.#setStatus('sending');
    return this;
  }

  /**
   * Put the manifest on the wire. It rides inside the offer frame, as it
   * always has, unless it is too big for one — then the offer says so and the
   * file list follows as a run of pages. Only a peer that announced it reads
   * them ever gets sent one.
   */
  #sendOffer(socket) {
    const head = {
      t: 'offer',
      ver: PROTOCOL_VERSION,
      transferId: this.id,
      from: {
        id: this.config.deviceId,
        name: this.config.deviceName,
        os: platformLabel(),
      },
      totalSize: this.totalSize,
      streams: this.streams,
    };

    const pages = pageManifest(this.files, this.config.manifestBudget);
    if (pages.length <= 1) {
      socket.write(encodeFrame({ ...head, files: pages[0] ?? [] }));
      return;
    }

    if (!peerSupports(socket, MANIFEST_PAGES)) {
      // Writing it anyway would be the worse failure: the far side reads the
      // length prefix, drops the connection before the first entry, and the
      // person is told the link died when the truth is the drop was too big
      // for the build at the other end.
      const reason = `${this.peer.name} runs a version that takes at most about `
        + `${pages[0].length} files in one transfer, and this one has ${this.files.length}. `
        + `Send them in smaller batches, or update that device.`;
      // open() has a caller that does not run the transfer loop, so the
      // failure is recorded here rather than left to whoever catches it.
      this.#setStatus('failed', reason);
      this.#teardown();
      throw new Error(reason);
    }

    socket.write(encodeFrame({ ...head, files: [], paged: true, fileCount: this.files.length }));
    for (const files of pages) {
      socket.write(encodeFrame({ t: 'offer-files', transferId: this.id, files }));
    }
    socket.write(encodeFrame({ t: 'offer-end', transferId: this.id }));
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
        // Between chunks only, which is what §9.5 requires and what makes a
        // pause free: nothing is half-delivered, so nothing is re-sent.
        await this.#awaitResume();
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
        await this.#pump(socket, stream, item.length, item.fileIndex);
      }
      socket.write(encodeFrame({ t: 'end' }));
      await new Promise((resolve) => socket.end(resolve));
    } catch (err) {
      socket.destroy();
      throw err;
    }
  }

  /** Copy `expected` bytes from a readable onto the socket, respecting backpressure. */
  async #pump(socket, readable, expected, fileIndex) {
    const file = this.files[fileIndex];
    let written = 0;
    for await (const buf of readable) {
      if (this.#cancelled) throw new Error('cancelled');
      written += buf.length;
      if (written > expected) throw new Error('source produced more bytes than declared');
      this.sent += buf.length;
      // Per file as well as in total, because a transfer of four hundred
      // files has four hundred answers to "how far along is this" and one of
      // them is not enough to tell which one is stuck.
      if (file) file.sent = (file.sent ?? 0) + buf.length;
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
      await this.#pump(socket, readable, header.length, header.fileIndex);
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
    // An 'error' with no listener is thrown, and takes the whole process with
    // it — one broken data connection would kill the app. Past 'sending' every
    // byte is already out and the control connection decides the outcome, so a
    // late error there is noise, not a failure.
    socket.on('error', (err) => {
      if (this.status === 'sending' || this.status === 'waiting') {
        this.cancel(`data connection lost: ${err.message}`);
      }
    });
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

  /** True once the receiver has said it understands a pause — §9.5. */
  get canPause() {
    return Boolean(this.#canPause);
  }

  get paused() {
    return Boolean(this.#paused);
  }

  pause() {
    if (!this.#canPause || this.#paused || this.status !== 'sending') return false;
    this.#paused = true;
    try { this.#control?.write(encodeFrame({ t: 'pause' })); } catch { /* gone */ }
    this.#setStatus('sending');
    return true;
  }

  resume() {
    if (!this.#paused) return false;
    this.#paused = false;
    try { this.#control?.write(encodeFrame({ t: 'resume' })); } catch { /* gone */ }
    for (const wake of this.#waitingOnResume.splice(0)) wake();
    this.#setStatus('sending');
    return true;
  }

  /** Park until resumed. Polled rather than event-driven: a pause is rare. */
  #awaitResume() {
    if (!this.#paused || this.#cancelled) return Promise.resolve();
    return new Promise((resolve) => this.#waitingOnResume.push(resolve));
  }

  cancel(reason) {
    if (this.#cancelled || this.status === 'completed') return;
    this.#paused = false;
    for (const wake of this.#waitingOnResume.splice(0)) wake();
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
    } else if (frame.t === 'offer-progress') {
      // Creating tens of thousands of empty files takes real time on the far
      // side. Each report is proof of life and pushes the deadline back, so
      // the wait does not have to be long enough to cover the slowest disk
      // anyone might ever receive onto.
      this.preparing = { prepared: frame.prepared ?? 0, total: frame.total ?? this.files.length };
      this.#pending?.get('offer-result')?.refresh();
      this.sender.notifyChange(this);
    }
    this.#pending?.get(frame.t)?.deliver(frame);
  }

  /**
   * Wait for one frame by type. The deadline can be pushed back through
   * `refresh`, which is how a receiver that is still preparing keeps the wait
   * alive without the timeout having to assume the worst case up front.
   */
  /**
   * The rows the detail drawer draws, and nothing past them. `received` on
   * both sides of the wire: the interface draws one bar per file and should
   * not have to know which end of the transfer it is looking at.
   */
  fileRows(limit) {
    return this.files.slice(0, limit)
      .map((f) => ({ rel: f.rel, size: f.size, received: f.sent ?? 0, path: null }));
  }

  #waitFor(type, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(type);
        reject(new Error(`timed out waiting for ${type}`));
      }, timeoutMs);
      this.#pending.set(type, {
        deliver: (frame) => {
          clearTimeout(timer);
          this.#pending.delete(type);
          resolve(frame);
        },
        refresh: () => timer.refresh(),
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
      paused: this.paused,
      canPause: this.canPause,
      error: this.error,
      // While the folder is still being walked these are what has been found
      // so far, which is the whole point of showing the card that early.
      totalSize: this.scanning ? this.scanning.bytes : this.totalSize,
      received: this.sent,
      fileCount: this.scanning ? this.scanning.files : this.files.length,
      preparing: this.preparing,
      // Two strings rather than the list — the same trade history.js makes,
      // and for the same reason. At seventy thousand files that list is five
      // megabytes of JSON, and this snapshot goes to the interface three
      // times a second while anything is moving. The drawer asks separately
      // for the few hundred rows it actually draws.
      firstFile: this.files[0]?.rel ?? this.scanning?.first ?? null,
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
    caps: CAPABILITIES,
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
  // Better evidence of presence than an announcement: this one arrived.
  noteContact(peer.id);

  const psk = deriveSessionKey({
    ephemeral,
    peerEphemeralRaw: frame.ephPub,
    peerIdentityKey,
    selfId: config.deviceId,
    peerId: peer.id,
  });
  const secure = await secureClient(socket, psk);
  // Kept on the socket because everything downstream that needs to know what
  // this peer understands — the offer, and nothing else — already holds it.
  secure.peerCaps = Array.isArray(frame.caps) ? frame.caps : [];
  return secure;
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
