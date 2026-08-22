import net from 'node:net';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { PROTOCOL_VERSION, loadConfig, platformLabel } from './config.js';
import { FrameChannel } from './protocol.js';
import { publicKeyString, importPublicKey, sharedSecret } from './identity.js';
import { rememberPeer } from './trust.js';

const COMMIT_LABEL = 'flyshare-sas-commit-v2';
const SAS_LABEL = 'flyshare-sas-v2';
const DECISION_TIMEOUT_MS = 3 * 60 * 1000;
const STEP_TIMEOUT_MS = 30 * 1000;

/**
 * Six-digit short authentication string.
 *
 * Both machines derive it from the same X25519 exchange, so a person comparing
 * the two screens is checking that nothing sits in the middle: an attacker
 * relaying the pairing ends up with a different shared secret on each leg, and
 * therefore a different number on each screen.
 */
export function computeSasCode({ initiatorPublic, responderPublic, initiatorNonce, responderNonce, shared }) {
  const salt = Buffer.concat([b64(initiatorNonce), b64(responderNonce)]);
  const info = Buffer.from(`${SAS_LABEL}|${initiatorPublic}|${responderPublic}`, 'utf8');
  const material = Buffer.from(crypto.hkdfSync('sha256', shared, salt, info, 4));
  return String(material.readUInt32BE(0) % 1000000).padStart(6, '0');
}

/**
 * The responder commits to its public key and nonce before seeing the
 * initiator's.
 *
 * Without this step the scheme is broken, not merely weaker: a machine in the
 * middle picks its own key pairs, so it could try key after key until the two
 * codes it shows happen to match — about a million cheap attempts. Committing
 * first means neither side can still be shopping for a key once it knows what
 * the other one chose, which puts an attacker back at one chance in a million.
 */
export function pairingCommitment(publicKey, nonce) {
  return crypto.createHash('sha256')
    .update(COMMIT_LABEL)
    .update(publicKey)
    .update(b64(nonce))
    .digest('base64url');
}

function b64(value) {
  return Buffer.from(value, 'base64url');
}

function newNonce() {
  return crypto.randomBytes(16).toString('base64url');
}

function selfDescriptor() {
  const config = loadConfig();
  return { id: config.deviceId, name: config.deviceName, os: platformLabel() };
}

/**
 * Dial a peer and run the pairing exchange.
 * `onCode` fires as soon as the code is known, so the UI can show it while the
 * far side is still waiting for a person to press the button.
 */
export function initiatePairing(peer, onCode) {
  const self = selfDescriptor();
  const myPublic = publicKeyString();
  const myNonce = newNonce();

  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: peer.address, port: peer.port });
    let settled = false;

    const fail = (err) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      reject(err);
    };
    const done = (value) => {
      if (settled) return;
      settled = true;
      socket.end();
      resolve(value);
    };

    socket.setTimeout(STEP_TIMEOUT_MS, () => fail(new Error('the other device stopped responding')));
    socket.once('error', (err) => fail(new Error(`cannot reach ${peer.name} — ${err.message}`)));
    socket.once('connect', async () => {
      try {
        const channel = new FrameChannel(socket);
        channel.write({ t: 'pair', ver: PROTOCOL_VERSION, device: self });

        const commitFrame = await expect(channel, 'pair-commit');
        channel.write({ t: 'pair-reveal', pub: myPublic, nonce: myNonce });

        const open = await expect(channel, 'pair-open');
        if (pairingCommitment(open.pub, open.nonce) !== commitFrame.commit) {
          throw new Error('the other device changed its key mid-pairing — pairing aborted');
        }

        const peerKey = importPublicKey(open.pub);
        const code = computeSasCode({
          initiatorPublic: myPublic,
          responderPublic: open.pub,
          initiatorNonce: myNonce,
          responderNonce: open.nonce,
          shared: sharedSecret(peerKey),
        });
        onCode?.(code);

        // The person now compares the two screens and answers on the far side.
        socket.setTimeout(DECISION_TIMEOUT_MS);
        const result = await expect(channel, 'pair-result', DECISION_TIMEOUT_MS);
        if (!result.accept) throw new Error(result.reason ?? 'the other device declined');

        const device = open.device ?? { id: peer.id, name: peer.name, os: peer.os };
        rememberPeer({ id: device.id, name: device.name, os: device.os, publicKey: open.pub });
        done({ ...device, code });
      } catch (err) {
        fail(err);
      }
    });
  });
}

/** Read the next frame, insisting it is the one the protocol calls for. */
async function expect(channel, type, timeoutMs = STEP_TIMEOUT_MS) {
  const frame = await channel.read(timeoutMs);
  if (frame.t === 'pair-error') throw new Error(frame.reason ?? 'pairing failed');
  if (frame.t !== type) throw new Error(`unexpected reply "${frame.t}" during pairing`);
  return frame;
}

/**
 * The receiving half of a pairing exchange, held open while a person decides.
 * Mirrors IncomingTransfer: the UI sees it, shows the code, and answers.
 */
export class IncomingPairing extends EventEmitter {
  #decided = false;
  #timer = null;

  constructor(socket, hello, seed) {
    super();
    this.socket = socket;
    this.channel = new FrameChannel(socket, seed);
    this.id = crypto.randomUUID();
    this.peer = hello.device ?? { id: 'unknown', name: 'unknown device', os: 'unknown' };
    this.code = null;
    this.status = 'exchanging';
    this.error = null;
    this.createdAt = Date.now();

    this.publicKey = publicKeyString();
    this.nonce = newNonce();

    socket.once('error', () => this.#fail('connection lost'));
    socket.once('close', () => this.#fail('the other device disconnected'));
  }

  /** Run the commit / reveal / open exchange, then park until answered. */
  async run() {
    try {
      this.socket.setTimeout(STEP_TIMEOUT_MS, () => this.#fail('the other device stopped responding'));
      this.#send({ t: 'pair-commit', commit: pairingCommitment(this.publicKey, this.nonce) });

      const reveal = await expect(this.channel, 'pair-reveal');
      this.peerPublic = reveal.pub;
      this.peerNonce = reveal.nonce;

      this.#send({
        t: 'pair-open',
        pub: this.publicKey,
        nonce: this.nonce,
        device: selfDescriptor(),
      });

      this.code = computeSasCode({
        initiatorPublic: reveal.pub,
        responderPublic: this.publicKey,
        initiatorNonce: reveal.nonce,
        responderNonce: this.nonce,
        shared: sharedSecret(importPublicKey(reveal.pub)),
      });

      this.status = 'awaiting-confirmation';
      this.socket.setTimeout(0);
      this.#timer = setTimeout(() => this.decide(false, 'no answer'), DECISION_TIMEOUT_MS);
      this.emit('change');
    } catch (err) {
      this.#fail(err.message);
    }
  }

  decide(accept, reason) {
    if (this.#decided) return;
    this.#decided = true;
    clearTimeout(this.#timer);

    if (accept) {
      rememberPeer({
        id: this.peer.id,
        name: this.peer.name,
        os: this.peer.os,
        publicKey: this.peerPublic,
      });
      this.status = 'paired';
    } else {
      this.status = 'rejected';
      this.error = reason ?? 'declined on this device';
    }
    this.#send({ t: 'pair-result', accept, reason });
    this.emit('change');
    setTimeout(() => this.socket.end(), 100);
  }

  #send(obj) {
    this.channel.write(obj);
  }

  #fail(message) {
    if (this.#decided || this.status === 'paired') return;
    this.#decided = true;
    clearTimeout(this.#timer);
    this.status = 'failed';
    this.error = message;
    this.socket.destroy();
    this.emit('change');
  }

  snapshot() {
    return {
      id: this.id,
      kind: 'pairing',
      direction: 'in',
      peer: this.peer,
      code: this.code,
      status: this.status,
      error: this.error,
      createdAt: this.createdAt,
    };
  }
}
