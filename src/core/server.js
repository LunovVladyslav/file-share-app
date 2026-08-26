import net from 'node:net';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { TRANSFER_PORT, PROTOCOL_VERSION, loadConfig } from './config.js';
import {
  encodeFrame, readFirstFrame, FrameChannel, MAX_FRAME, CAPABILITIES,
} from './protocol.js';
import { safeJoin, uniquePath, sanitizeSegment } from './manifest.js';
import { peerPublicKey, refreshPeerName } from './trust.js';
import { noteContact } from './presence.js';
import { isFinished } from './history.js';
import { newEphemeralKeyPair, deriveSessionKey, secureServer, describeSecurity } from './secure.js';
import { IncomingPairing } from './pairing.js';
import { SpeedMeter } from '../util/speed.js';

const HEADER = 0;
const BODY = 1;

// A manifest larger than one frame arrives as pages. These bound what a peer
// can make this device hold and how long it may take over saying it.
const MAX_MANIFEST_FILES = 500000;
const MANIFEST_PAGE_TIMEOUT = 60000;

// Preallocation is round trips, not computation — the number is chosen for
// disk and virus-scanner latency rather than for cores.
const PREPARE_CONCURRENCY = 32;
const PREPARE_REPORT_FROM = 2000;
const PREPARE_REPORT_EVERY = 1000;

/**
 * Receiving side.
 *
 * One TCP port serves everything. The first plaintext frame says what the
 * connection is for:
 *   {t:'pair'}    -> pairing exchange, protected by the six-digit code
 *   {t:'session'} -> ephemeral key exchange, then the socket becomes TLS 1.3
 *
 * Inside TLS the connection declares itself again:
 *   {t:'offer'} -> control connection, stays JSON for its whole life
 *   {t:'data'}  -> data connection, flips to [header frame][raw bytes] forever
 */
export class TransferServer extends EventEmitter {
  #server = null;
  #transfers = new Map();
  #pairings = new Map();

  constructor() {
    super();
    this.config = loadConfig();
  }

  get transfers() {
    return [...this.#transfers.values()].map((t) => t.snapshot());
  }

  async start(port = TRANSFER_PORT) {
    this.#server = net.createServer((socket) => this.#onConnection(socket));
    this.#server.on('error', (err) => this.emit('error', err));
    await new Promise((resolve, reject) => {
      this.#server.once('error', reject);
      this.#server.listen(port, '0.0.0.0', () => {
        this.#server.off('error', reject);
        resolve();
      });
    });
    return this;
  }

  stop() {
    for (const transfer of this.#transfers.values()) transfer.abort('server shutting down');
    this.#server?.close();
  }

  async #onConnection(socket) {
    socket.setNoDelay(true);
    socket.on('error', () => socket.destroy());
    // Watchdog for connections that open and then never say what they are.
    socket.setTimeout(30000, () => socket.destroy());

    let first;
    try {
      first = await readFirstFrame(socket);
    } catch {
      return socket.destroy();
    }
    socket.setTimeout(0);

    if (first.frame.t === 'pair') this.#handlePairing(socket, first.frame, first.rest);
    else if (first.frame.t === 'session') this.#handleSession(socket, first.frame, first.rest);
    else {
      // Almost certainly a peer still running the older, unencrypted build.
      socket.write(encodeFrame({
        t: 'session-err',
        reason: `this device speaks protocol v${PROTOCOL_VERSION}; the other side is older and unencrypted`,
      }));
      socket.end();
    }
  }

  #handlePairing(socket, hello, leftover) {
    const pairing = new IncomingPairing(socket, hello, leftover);
    this.#pairings.set(pairing.id, pairing);
    pairing.on('change', () => {
      this.emit('pairing', pairing.snapshot());
      if (pairing.status !== 'awaiting-confirmation') {
        setTimeout(() => this.#pairings.delete(pairing.id), 30000);
      }
    });
    this.emit('pairing', pairing.snapshot());
    pairing.run();
  }

  /**
   * Turn an accepted connection into an encrypted one, then let the existing
   * transfer protocol run inside it. An unpaired peer never gets past here.
   */
  async #handleSession(socket, hello, leftover) {
    if (leftover.length) return socket.destroy();

    if (hello.ver !== PROTOCOL_VERSION) {
      socket.write(encodeFrame({
        t: 'session-err',
        reason: `protocol mismatch (peer v${hello.ver}, this device v${PROTOCOL_VERSION})`,
      }));
      return socket.end();
    }

    const peerIdentityKey = peerPublicKey(hello.deviceId);
    if (!peerIdentityKey) {
      socket.write(encodeFrame({
        t: 'session-err',
        needsPairing: true,
        reason: 'this device has not been paired yet',
      }));
      return socket.end();
    }

    const config = loadConfig();
    const ephemeral = newEphemeralKeyPair();
    socket.write(encodeFrame({
      t: 'session-ok',
      deviceId: config.deviceId,
      ephPub: ephemeral.publicRaw,
      caps: CAPABILITIES,
    }));

    let secure;
    try {
      const psk = deriveSessionKey({
        ephemeral,
        peerEphemeralRaw: hello.ephPub,
        peerIdentityKey,
        selfId: config.deviceId,
        peerId: hello.deviceId,
      });
      secure = await secureServer(socket, psk);
    } catch {
      // A peer whose pinned key no longer matches cannot complete this, and
      // there is deliberately no unencrypted path to fall back to.
      return socket.destroy();
    }

    let next;
    try {
      next = await readFirstFrame(secure);
    } catch {
      return secure.destroy();
    }

    // The handshake completed, so this device is unquestionably present —
    // which discovery's expiry has no other way of knowing.
    noteContact(hello.deviceId);
    const identity = { deviceId: hello.deviceId, security: describeSecurity(secure) };
    if (next.frame.t === 'offer') this.#handleOffer(secure, next.frame, next.rest, identity);
    else if (next.frame.t === 'data') this.#handleData(secure, next.frame, next.rest);
    else secure.destroy();
  }

  async #handleOffer(socket, offer, leftover, identity) {
    // One reader for the whole control connection, attached before anything is
    // awaited: the pages of a large manifest arrive immediately behind the
    // offer, and whatever follows them has to be queued rather than missed.
    const channel = new FrameChannel(socket, leftover);

    let manifest = offer;
    if (offer.paged) {
      try {
        manifest = { ...offer, files: await collectManifestPages(channel, offer) };
      } catch (err) {
        channel.write({ t: 'offer-result', accept: false, reason: `manifest: ${err.message}` });
        return socket.end();
      }
    }

    // The sender's claimed name is only a label; its id came from the TLS
    // handshake, which it could not have completed without the paired key.
    const peer = {
      ...(manifest.from ?? {}),
      id: identity.deviceId,
    };
    refreshPeerName(peer.id, peer.name, peer.os);

    const transfer = new IncomingTransfer(this, channel, { ...manifest, from: peer });
    transfer.security = identity.security;
    this.#transfers.set(transfer.id, transfer);
    this.emit('offer', transfer.snapshot());

    const config = loadConfig();
    if (config.autoAccept) {
      await transfer.accept();
    } else {
      transfer.awaitDecision();
    }
  }

  #handleData(socket, header, leftover) {
    const transfer = this.#transfers.get(header.transferId);
    if (!transfer || transfer.token !== header.token) {
      socket.write(encodeFrame({ t: 'data-err', reason: 'unknown or unauthorized transfer' }));
      return socket.end();
    }
    socket.write(encodeFrame({ t: 'data-ok' }));
    transfer.attachDataSocket(socket, leftover);
  }

  /** Called by the UI layer when the user answers an incoming offer. */
  respond(transferId, accept, reason) {
    const transfer = this.#transfers.get(transferId);
    if (!transfer) return false;
    if (accept) transfer.accept();
    else transfer.decline(reason ?? 'declined by user');
    return true;
  }

  cancel(transferId) {
    const transfer = this.#transfers.get(transferId);
    if (!transfer) return false;
    transfer.abort('cancelled by user');
    return true;
  }

  get pairings() {
    return [...this.#pairings.values()].map((p) => p.snapshot());
  }

  /** Called by the UI when the user answers an incoming pairing request. */
  respondToPairing(pairingId, accept) {
    const pairing = this.#pairings.get(pairingId);
    if (!pairing) return false;
    pairing.decide(accept);
    return true;
  }

  /** The first `limit` file rows of one transfer, for the detail drawer. */
  fileRows(transferId, limit) {
    const transfer = this.#transfers.get(transferId);
    if (!transfer) return null;
    return { total: transfer.files.length, files: transfer.fileRows(limit) };
  }

  /** Drop a finished transfer from the live list; history keeps the record. */
  forget(transferId) {
    const transfer = this.#transfers.get(transferId);
    if (!transfer || !isFinished(transfer.status)) return false;
    this.#transfers.delete(transferId);
    return true;
  }

  notifyChange(transfer) {
    this.emit('transfer', transfer.snapshot());
  }
}

/**
 * A manifest too large for one frame arrives as `offer` + a run of
 * `offer-files` pages + `offer-end`. Collect the whole thing before anyone is
 * asked to decide: the question on screen has to name the entire transfer,
 * not the part of it that happened to fit in the first frame.
 */
async function collectManifestPages(channel, offer) {
  const expected = Number(offer.fileCount);
  if (!Number.isInteger(expected) || expected < 0) {
    throw new Error('a paged offer did not say how many files to expect');
  }
  if (expected > MAX_MANIFEST_FILES) {
    throw new Error(`${expected} files is more than this device accepts in one transfer`);
  }

  const files = [];
  for (;;) {
    const frame = await channel.read(MANIFEST_PAGE_TIMEOUT);
    if (frame.t === 'offer-end') break;
    if (frame.t !== 'offer-files') throw new Error(`unexpected ${frame.t} while reading the file list`);
    if (!Array.isArray(frame.files)) throw new Error('a page carried no file list');
    // Spreading into push() would be an argument list fifty thousand long.
    for (const file of frame.files) files.push(file);
    if (files.length > expected) throw new Error('more files arrived than the offer promised');
  }
  if (files.length !== expected) {
    throw new Error(`the offer promised ${expected} files and sent ${files.length}`);
  }
  return files;
}

/**
 * Pick a name no other file in this manifest has taken. In memory, and
 * deliberately so: the destination folder was made for this transfer, so the
 * only collision possible is with a sibling — two names that sanitised to
 * one. Asking the filesystem would be a stat per file and a race besides.
 */
function claimTarget(target, claimed) {
  if (!claimed.has(target)) {
    claimed.add(target);
    return target;
  }
  const ext = path.extname(target);
  const base = target.slice(0, target.length - ext.length);
  for (let i = 2; ; i += 1) {
    const candidate = `${base} (${i})${ext}`;
    if (!claimed.has(candidate)) {
      claimed.add(candidate);
      return candidate;
    }
  }
}

class IncomingTransfer {
  #dataSockets = new Set();
  #pendingWrites = new Set();
  #decisionMade = false;
  #ownDir = false;

  constructor(server, channel, offer) {
    this.server = server;
    this.channel = channel;
    this.socket = channel.socket;
    this.id = offer.transferId;
    this.token = crypto.randomBytes(16).toString('hex');
    this.peer = offer.from ?? { name: 'unknown', os: 'unknown' };
    this.totalSize = offer.totalSize;
    this.received = 0;
    this.status = 'pending';
    this.error = null;
    this.startedAt = null;
    this.finishedAt = null;
    this.createdAt = Date.now();
    this.destDir = null;
    this.speed = new SpeedMeter();

    this.files = offer.files.map((f) => ({
      rel: f.rel,
      size: f.size,
      path: null,
      received: 0,
    }));

    this.socket.on('close', () => {
      if (this.status === 'receiving' || this.status === 'pending') {
        this.#setStatus('failed', 'sender disconnected');
      }
    });
    this.socket.on('error', () => { /* handled via close */ });
    this.#listenForControl();
  }

  /** Listen for follow-up control frames from the sender: cancel, pause, resume. */
  async #listenForControl() {
    for (;;) {
      let frame;
      // The channel rejects when the connection dies, and the 'close' handler
      // above has already turned that into a status. Nothing to add here.
      try {
        frame = await this.channel.read();
      } catch {
        return;
      }

      if (frame.t === 'cancel') this.abort(frame.reason ?? 'cancelled by sender');
      else if (frame.t === 'pause' || frame.t === 'resume') {
        // Without this the bar simply stops and nothing explains why.
        this.paused = frame.t === 'pause';
        this.server.emit('transfer', this.snapshot());
      }
    }
  }

  awaitDecision() {
    // Nothing else to do; the UI will call accept()/decline().
  }

  async accept() {
    if (this.#decisionMade) return;
    this.#decisionMade = true;
    const config = loadConfig();

    try {
      this.destDir = await this.#prepareDestination(config.downloadDir);
      await this.#preallocate();
    } catch (err) {
      this.#send({ t: 'offer-result', accept: false, reason: `cannot prepare destination: ${err.message}` });
      this.#setStatus('failed', err.message);
      this.socket.end();
      return;
    }

    this.status = 'receiving';
    this.startedAt = Date.now();
    // §9.5: saying this undertakes to understand pause and resume. Our data
    // connections carry no idle timeout, so there is nothing to suspend — only
    // the interface has to say what is happening.
    this.#send({ t: 'offer-result', accept: true, token: this.token, canPause: true });
    this.server.notifyChange(this);
    // A manifest of nothing but empty files is already finished.
    if (this.totalSize === 0) this.#maybeComplete();
  }

  decline(reason) {
    if (this.#decisionMade) return;
    this.#decisionMade = true;
    this.#send({ t: 'offer-result', accept: false, reason });
    this.#setStatus('declined', reason);
    this.socket.end();
  }

  /**
   * Multi-file transfers land in their own folder so a 400-file drop does not
   * carpet the Downloads directory. A single file goes straight in.
   */
  async #prepareDestination(downloadDir) {
    await fsp.mkdir(downloadDir, { recursive: true });
    const multi = this.files.length > 1 || this.files.some((f) => f.rel.includes('/'));
    if (!multi) return downloadDir;
    const label = sanitizeSegment(this.peer.name || 'Received');
    const dir = await uniquePath(path.join(downloadDir, label));
    await fsp.mkdir(dir, { recursive: true });
    // Made for this transfer and empty, which is what lets preallocation
    // settle collisions in memory instead of a stat per file.
    this.#ownDir = true;
    return dir;
  }

  /**
   * Reserving each file at its full length up front lets every parallel stream
   * write at its own offset without extending anything concurrently.
   *
   * Done one file at a time this is a round trip per file per step, and a
   * folder of seventy thousand spends half a minute in it before the sender
   * hears a word — so the work fans out, and while it runs the sender is told
   * how far it has got rather than being left to wonder whether we died.
   */
  async #preallocate() {
    const total = this.files.length;
    const claimed = new Set();
    const dirs = new Map();
    let next = 0;
    let done = 0;
    let failed = false;

    // Below this it is over before the first report would have been sent.
    const report = total >= PREPARE_REPORT_FROM
      ? setInterval(
        () => this.#send({ t: 'offer-progress', transferId: this.id, prepared: done, total }),
        PREPARE_REPORT_EVERY,
      )
      : null;

    const worker = async () => {
      for (;;) {
        if (failed) return;
        const index = next;
        next += 1;
        if (index >= total) return;
        const file = this.files[index];

        const target = this.#ownDir
          ? claimTarget(safeJoin(this.destDir, file.rel), claimed)
          : await uniquePath(safeJoin(this.destDir, file.rel));

        // One mkdir per directory, with every worker that wants it waiting on
        // that one: remembering only the name would let a worker open a file
        // inside a directory that is still being created.
        const dir = path.dirname(target);
        let made = dirs.get(dir);
        if (!made) {
          made = fsp.mkdir(dir, { recursive: true });
          dirs.set(dir, made);
        }
        await made;

        const handle = await fsp.open(target, 'w');
        try {
          if (file.size > 0) await handle.truncate(file.size);
        } finally {
          await handle.close();
        }
        file.path = target;
        done += 1;
      }
    };

    try {
      await Promise.all(Array.from({ length: PREPARE_CONCURRENCY }, worker));
    } catch (err) {
      // Stop the others rather than leave them filling a folder whose
      // transfer has already been refused.
      failed = true;
      throw err;
    } finally {
      if (report) clearInterval(report);
    }
  }

  attachDataSocket(socket, leftover) {
    if (this.status !== 'receiving') return socket.destroy();
    const reader = new DataSocketReader(this, socket);
    this.#dataSockets.add(reader);
    socket.on('close', () => {
      this.#dataSockets.delete(reader);
      this.#maybeComplete();
    });
    reader.start(leftover);
  }

  openChunkStream(fileIndex, offset, length) {
    const file = this.files[fileIndex];
    if (!file || !file.path) throw new Error(`bad file index ${fileIndex}`);
    if (offset < 0 || offset + length > file.size) {
      throw new Error(`chunk out of bounds for ${file.rel}`);
    }
    return fs.createWriteStream(file.path, { flags: 'r+', start: offset });
  }

  countBytes(fileIndex, bytes) {
    this.received += bytes;
    const file = this.files[fileIndex];
    if (file) file.received += bytes;
    this.speed.add(bytes);
  }

  trackWrite(promise) {
    this.#pendingWrites.add(promise);
    promise.finally(() => {
      this.#pendingWrites.delete(promise);
      this.#maybeComplete();
    });
  }

  async #maybeComplete() {
    if (this.status !== 'receiving') return;
    if (this.received < this.totalSize) return;
    if (this.#pendingWrites.size > 0) return;
    this.finishedAt = Date.now();
    this.#setStatus('completed');
    this.#send({ t: 'done', transferId: this.id });
    for (const reader of this.#dataSockets) reader.socket.end();
    this.socket.end();
  }

  fail(message) {
    this.#setStatus('failed', message);
    this.abort(message);
  }

  abort(reason) {
    if (this.status === 'completed') return;
    if (this.status !== 'failed') this.#setStatus('cancelled', reason);
    for (const reader of this.#dataSockets) reader.socket.destroy();
    this.#dataSockets.clear();
    try { this.#send({ t: 'error', reason }); } catch { /* socket may be gone */ }
    this.socket.destroy();
  }

  /** The rows the detail drawer draws, and nothing past them. */
  fileRows(limit) {
    return this.files.slice(0, limit)
      .map((f) => ({ rel: f.rel, size: f.size, received: f.received, path: f.path }));
  }

  #setStatus(status, error) {
    if (this.status === status) return;
    this.status = status;
    if (error) this.error = error;
    this.server.notifyChange(this);
  }

  #send(obj) {
    this.channel.write(obj);
  }

  snapshot() {
    return {
      id: this.id,
      direction: 'in',
      peer: this.peer,
      status: this.status,
      paused: Boolean(this.paused),
      error: this.error,
      totalSize: this.totalSize,
      received: this.received,
      fileCount: this.files.length,
      // Two strings rather than the list, as on the sending side and in
      // history: this goes out three times a second, and a folder of seventy
      // thousand would make every frame five megabytes of file names.
      firstFile: this.files[0]?.rel ?? null,
      // Where the one file actually landed after collision renaming, which is
      // what the interface needs to point at it.
      filePath: this.files.length === 1 ? (this.files[0]?.path ?? null) : null,
      destDir: this.destDir,
      speed: this.speed.bytesPerSecond,
      security: this.security ?? null,
      startedAt: this.startedAt,
      finishedAt: this.finishedAt,
      createdAt: this.createdAt,
    };
  }
}

/**
 * Consumes one data connection: alternating [JSON header frame][raw payload].
 * Raw payload bytes go straight from the socket to a positional write stream —
 * they are never parsed, copied into a parser, or held in a JS string.
 */
class DataSocketReader {
  #state = HEADER;
  #pending = Buffer.alloc(0);
  #remaining = 0;
  #stream = null;
  #fileIndex = -1;
  #paused = false;
  #dead = false;

  constructor(transfer, socket) {
    this.transfer = transfer;
    this.socket = socket;
    socket.setNoDelay(true);
    socket.setTimeout(0);
    socket.on('data', (chunk) => this.feed(chunk));
    socket.on('error', () => this.#die());
  }

  /** Consume bytes that arrived before this reader existed, then open the tap. */
  start(leftover) {
    this.feed(leftover);
    this.socket.resume(); // parked by readFirstFrame during classification
  }

  feed(chunk) {
    if (this.#dead || chunk.length === 0) return;
    this.#pending = this.#pending.length ? Buffer.concat([this.#pending, chunk]) : chunk;
    this.#process();
  }

  #process() {
    while (!this.#dead && !this.#paused) {
      if (this.#state === BODY) {
        if (this.#pending.length === 0) return;
        const take = Math.min(this.#remaining, this.#pending.length);
        const slice = this.#pending.subarray(0, take);
        this.#pending = this.#pending.subarray(take);
        this.#remaining -= take;

        const stream = this.#stream;
        const ok = stream.write(slice);
        this.transfer.countBytes(this.#fileIndex, take);

        if (this.#remaining === 0) {
          // Chunk finished. end() flushes whatever is still buffered and
          // trackWrite() waits for it, so there is nothing to throttle here —
          // and a stream that has been ended will never emit 'drain' again.
          this.#endChunk();
        } else if (!ok) {
          this.#paused = true;
          this.socket.pause();
          stream.once('drain', () => this.#resume());
          return;
        }
      } else {
        if (this.#pending.length < 4) return;
        const len = this.#pending.readUInt32BE(0);
        if (len > MAX_FRAME) return this.#fail('oversized header frame');
        if (this.#pending.length < 4 + len) return;

        let frame;
        try {
          frame = JSON.parse(this.#pending.subarray(4, 4 + len).toString('utf8'));
        } catch {
          return this.#fail('malformed header frame');
        }
        this.#pending = this.#pending.subarray(4 + len);
        if (!this.#onFrame(frame)) return;
      }
    }
  }

  #onFrame(frame) {
    if (frame.t === 'end') {
      this.socket.end();
      this.#dead = true;
      return false;
    }
    if (frame.t !== 'chunk') {
      this.#fail(`unexpected frame ${frame.t}`);
      return false;
    }
    try {
      this.#stream = this.transfer.openChunkStream(frame.fileIndex, frame.offset, frame.length);
      this.#stream.on('error', (err) => this.#fail(`write failed: ${err.message}`));
    } catch (err) {
      this.#fail(err.message);
      return false;
    }
    this.#fileIndex = frame.fileIndex;
    this.#remaining = frame.length;
    this.#state = BODY;
    if (frame.length === 0) this.#endChunk();
    return true;
  }

  #endChunk() {
    const stream = this.#stream;
    this.#stream = null;
    this.#state = HEADER;
    this.transfer.trackWrite(new Promise((resolve) => {
      stream.end(() => resolve());
    }));
  }

  #resume() {
    if (this.#dead) return;
    this.#paused = false;
    this.socket.resume();
    this.#process();
  }

  #fail(message) {
    if (this.#dead) return;
    this.#dead = true;
    this.socket.destroy();
    this.transfer.fail(message);
  }

  #die() {
    this.#dead = true;
  }
}
