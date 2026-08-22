// Wire format
// -----------
// Every connection opens with length-prefixed JSON control frames:
//     [4 bytes big-endian length][UTF-8 JSON]
// A control connection keeps exchanging those frames for its whole life.
// A data connection switches to a repeating pattern of
//     [4 bytes length][JSON chunk header][`length` raw bytes]
// so payload bytes are never base64'd, escaped, or copied through a parser.

export const MAX_FRAME = 4 * 1024 * 1024;

export function encodeFrame(obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  const head = Buffer.allocUnsafe(4);
  head.writeUInt32BE(body.length, 0);
  return Buffer.concat([head, body]);
}

/**
 * Incremental reader for length-prefixed JSON frames.
 * Feed it socket bytes, pull complete frames out one at a time.
 */
export class FrameReader {
  #pending = Buffer.alloc(0);

  push(chunk) {
    this.#pending = this.#pending.length ? Buffer.concat([this.#pending, chunk]) : chunk;
  }

  /**
   * @returns {object|null} the next complete frame, or null if more bytes are needed
   * @throws if the stream is not a valid frame stream
   */
  next() {
    if (this.#pending.length < 4) return null;
    const length = this.#pending.readUInt32BE(0);
    if (length > MAX_FRAME) throw new Error(`frame too large: ${length}`);
    if (this.#pending.length < 4 + length) return null;
    const json = this.#pending.subarray(4, 4 + length).toString('utf8');
    this.#pending = this.#pending.subarray(4 + length);
    return JSON.parse(json);
  }

  /** Bytes buffered past the last complete frame — a raw payload, usually. */
  rest() {
    const rest = this.#pending;
    this.#pending = Buffer.alloc(0);
    return rest;
  }
}

/**
 * Deliver every frame on a socket to `onFrame` for as long as it lives.
 * A malformed stream destroys the socket rather than throwing into the
 * 'data' handler, where nothing could catch it.
 */
export function readFrames(socket, onFrame, seed) {
  const reader = new FrameReader();
  if (seed?.length) reader.push(seed);

  const drain = () => {
    for (;;) {
      let frame;
      try {
        frame = reader.next();
      } catch {
        socket.destroy();
        return;
      }
      if (!frame) return;
      onFrame(frame);
    }
  };

  socket.on('data', (chunk) => {
    reader.push(chunk);
    drain();
  });
  socket.resume(); // readFirstFrame parks the socket; this picks it back up
  drain();
}

/**
 * Read exactly one frame, then hand the socket back untouched along with any
 * bytes that arrived after it. Used where the first frame decides what the
 * rest of the connection means.
 */
export function readFirstFrame(socket, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const reader = new FrameReader();
    const timer = timeoutMs > 0
      ? setTimeout(() => finish(() => reject(new Error('timed out waiting for the first message'))), timeoutMs)
      : null;

    function finish(action) {
      clearTimeout(timer);
      socket.off('data', onData);
      socket.off('error', onError);
      socket.off('close', onClose);
      action();
    }
    function onData(chunk) {
      reader.push(chunk);
      let frame;
      try {
        frame = reader.next();
      } catch (err) {
        return finish(() => { socket.destroy(); reject(err); });
      }
      // Detaching happens inside this callback, so no 'data' can slip past:
      // Node will not emit the next one until this handler returns.
      if (frame) {
        // Park the socket so nothing is emitted into the gap before the next
        // reader attaches. Whoever takes over is responsible for resuming it.
        socket.pause();
        finish(() => resolve({ frame, rest: reader.rest() }));
      }
    }
    function onError(err) { finish(() => reject(err)); }
    function onClose() { finish(() => reject(new Error('connection closed'))); }

    socket.on('data', onData);
    socket.once('error', onError);
    socket.once('close', onClose);
  });
}

/**
 * A socket read as a sequence of frames you can `await` one at a time.
 *
 * Step-by-step handshakes need this: reading "the next frame" repeatedly with
 * one-shot readers would either drop bytes or leave the socket parked between
 * steps. One persistent reader feeds a queue instead.
 */
export class FrameChannel {
  #queue = [];
  #waiters = [];
  #closed = null;

  constructor(socket, seed) {
    this.socket = socket;
    socket.setNoDelay(true);
    readFrames(socket, (frame) => {
      const waiter = this.#waiters.shift();
      if (waiter) waiter.resolve(frame);
      else this.#queue.push(frame);
    }, seed);
    socket.once('error', (err) => this.#fail(err));
    socket.once('close', () => this.#fail(new Error('connection closed')));
  }

  #fail(err) {
    if (this.#closed) return;
    this.#closed = err;
    for (const waiter of this.#waiters.splice(0)) waiter.reject(err);
  }

  /** Next frame, or a rejection if the connection dies or the wait expires. */
  read(timeoutMs = 0) {
    if (this.#queue.length) return Promise.resolve(this.#queue.shift());
    if (this.#closed) return Promise.reject(this.#closed);

    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject };
      if (timeoutMs > 0) {
        const timer = setTimeout(() => {
          const index = this.#waiters.indexOf(waiter);
          if (index >= 0) this.#waiters.splice(index, 1);
          reject(new Error('timed out waiting for the next message'));
        }, timeoutMs);
        waiter.resolve = (value) => { clearTimeout(timer); resolve(value); };
        waiter.reject = (err) => { clearTimeout(timer); reject(err); };
      }
      this.#waiters.push(waiter);
    });
  }

  write(obj) {
    if (!this.socket.destroyed) this.socket.write(encodeFrame(obj));
  }
}
