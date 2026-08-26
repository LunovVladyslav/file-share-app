import http from 'node:http';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { UI_PORT, loadConfig, saveConfig, platformLabel } from './core/config.js';
import { localInterfaces } from './core/discovery.js';
import { isPaired, pairedPeers, forgetPeer } from './core/trust.js';
import {
  entries as historyEntries, record as recordTransfer, clear as clearHistory,
  forget as forgetRemembered, isFinished,
} from './core/history.js';
import { pickFiles, pickFolder } from './util/picker.js';
import { readUiFile } from './util/assets.js';

// The detail drawer draws at most this many rows, so this is the most it is
// ever sent. It has to match DETAIL_FILE_LIMIT in ui/app.js.
const FILE_ROW_LIMIT = 500;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
};

/**
 * Local UI host.
 *
 * Bound to 127.0.0.1 and gated by a per-run token that is injected into the
 * page. A random website in another tab cannot read that token, and cannot set
 * the custom header without a CORS preflight — which this server never grants.
 */
export class UiServer {
  #server = null;
  #clients = new Set();
  #pushTimer = null;
  #pushQueued = false;

  constructor({ discovery, server, sender }) {
    this.discovery = discovery;
    this.server = server;
    this.sender = sender;
    this.token = crypto.randomBytes(24).toString('base64url');

    discovery.on('change', () => this.push());
    server.on('offer', () => this.push());
    server.on('transfer', (transfer) => this.#onTransfer(transfer));
    server.on('pairing', () => this.push());
    sender.on('transfer', (transfer) => this.#onTransfer(transfer));
    sender.on('pairing', () => this.push());
  }

  /**
   * Every transfer event passes through here, and the ones that are over get
   * written down. Recording at the moment a transfer settles rather than on
   * the way out means an app that is killed, crashes, or loses power still
   * remembers what it did.
   */
  #onTransfer(transfer) {
    recordTransfer(transfer);
    this.push();
  }

  async start(port = UI_PORT) {
    this.#server = http.createServer((req, res) => {
      this.#handle(req, res).catch((err) => sendJson(res, 500, { error: err.message }));
    });
    await new Promise((resolve, reject) => {
      this.#server.once('error', reject);
      this.#server.listen(port, '127.0.0.1', () => {
        this.#server.off('error', reject);
        resolve();
      });
    });
    this.port = port;
    this.url = `http://127.0.0.1:${port}/?t=${this.token}`;

    // While anything is moving, refresh a few times a second so the progress
    // bars and speed readouts stay live even when no event fires.
    this.#pushTimer = setInterval(() => {
      if (this.#hasActiveTransfer()) this.push();
    }, 300);
    return this;
  }

  stop() {
    clearInterval(this.#pushTimer);
    for (const client of this.#clients) client.end();
    this.#server?.close();
  }

  #hasActiveTransfer() {
    return [...this.server.transfers, ...this.sender.transfers]
      .some((t) => ACTIVE_STATUSES.has(t.status));
  }

  async #handle(req, res) {
    const url = new URL(req.url, `http://127.0.0.1:${this.port}`);

    if (url.pathname === '/api/events') {
      if (url.searchParams.get('token') !== this.token) return sendJson(res, 403, { error: 'forbidden' });
      return this.#openEventStream(res);
    }

    if (url.pathname.startsWith('/api/')) {
      // Custom-header auth: cross-origin JS cannot set it without a preflight,
      // and there is no OPTIONS handler here, so the preflight always fails.
      if (req.headers['x-flyshare-token'] !== this.token) {
        return sendJson(res, 403, { error: 'forbidden' });
      }
      return this.#handleApi(req, res, url);
    }

    return this.#serveStatic(url.pathname, res);
  }

  async #handleApi(req, res, url) {
    switch (`${req.method} ${url.pathname}`) {
      case 'GET /api/state':
        return sendJson(res, 200, this.state());

      case 'POST /api/pick': {
        const body = await readJson(req);
        const paths = body.mode === 'folder' ? await pickFolder() : await pickFiles();
        return sendJson(res, 200, { paths });
      }

      case 'POST /api/send': {
        const body = await readJson(req);
        const peer = this.#requirePeer(body.peerId);
        const transfer = await this.sender.sendPaths(peer, body.paths);
        return sendJson(res, 200, transfer);
      }

      case 'POST /api/pair': {
        const body = await readJson(req);
        const peer = this.#requirePeer(body.peerId);
        return sendJson(res, 200, this.sender.pair(peer));
      }

      case 'POST /api/pair/respond': {
        const body = await readJson(req);
        const ok = this.server.respondToPairing(body.pairingId, Boolean(body.accept));
        return sendJson(res, ok ? 200 : 404, { ok });
      }

      case 'POST /api/pair/cancel': {
        const body = await readJson(req);
        const ok = this.sender.cancelPairing(body.pairingId);
        return sendJson(res, ok ? 200 : 404, { ok });
      }

      case 'POST /api/peers/find': {
        const body = await readJson(req);
        const address = String(body.address ?? '').trim();
        if (!isIpv4(address)) return sendJson(res, 400, { error: 'not an IPv4 address' });
        if (localInterfaces().some((i) => i.address === address)) {
          return sendJson(res, 400, { error: 'that is this device' });
        }
        const peer = await this.#findByAddress(address);
        return sendJson(res, 200, { peer: peer ?? null });
      }

      case 'POST /api/pair/forget': {
        const body = await readJson(req);
        const ok = forgetPeer(body.deviceId);
        this.push();
        return sendJson(res, ok ? 200 : 404, { ok });
      }

      case 'POST /api/upload/begin': {
        const body = await readJson(req);
        const peer = this.#requirePeer(body.peerId);
        const transfer = await this.sender.beginUpload(peer, body.files);
        return sendJson(res, 200, transfer);
      }

      case 'POST /api/upload/chunk': {
        const header = {
          fileIndex: Number(req.headers['x-file-index']),
          offset: Number(req.headers['x-offset']),
          length: Number(req.headers['x-length']),
        };
        await this.sender.pushChunk(String(req.headers['x-transfer-id']), header, req);
        return sendJson(res, 200, { ok: true });
      }

      case 'POST /api/upload/finish': {
        const body = await readJson(req);
        const transfer = await this.sender.finishUpload(body.transferId);
        return sendJson(res, 200, transfer);
      }

      case 'POST /api/respond': {
        const body = await readJson(req);
        const ok = this.server.respond(body.transferId, Boolean(body.accept));
        return sendJson(res, ok ? 200 : 404, { ok });
      }

      case 'POST /api/pause': {
        const body = await readJson(req);
        const ok = this.sender.pause(body.transferId);
        return sendJson(res, ok ? 200 : 404, { ok });
      }

      case 'POST /api/resume': {
        const body = await readJson(req);
        const ok = this.sender.resume(body.transferId);
        return sendJson(res, ok ? 200 : 404, { ok });
      }

      case 'POST /api/history/clear': {
        // The stored record and the finished entries still in memory are one
        // list on screen, so "clear" has to mean both — otherwise half of it
        // would come straight back on the next frame.
        clearHistory();
        for (const side of [this.server, this.sender]) {
          for (const transfer of side.transfers) {
            // Not the one still moving bytes: dropping that would leave it
            // running with nothing on screen to cancel it with.
            if (isFinished(transfer.status)) side.forget(transfer.id);
          }
        }
        this.push();
        return sendJson(res, 200, { ok: true });
      }

      case 'POST /api/forget': {
        const body = await readJson(req);
        // One card can be two records: the finished transfer still in memory
        // and the remembered one behind it. Both go, or the next frame puts
        // the card back. Neither side drops one that is still running.
        const dropped = [
          this.server.forget(body.transferId),
          this.sender.forget(body.transferId),
          forgetRemembered(body.transferId),
        ].some(Boolean);
        if (dropped) this.push();
        return sendJson(res, dropped ? 200 : 404, { ok: dropped });
      }

      case 'POST /api/transfer/files': {
        // The file list left the pushed state: at seventy thousand files it
        // was megabytes in every frame, for rows only the open drawer draws.
        // It is asked for here instead, capped at what fits on screen.
        const body = await readJson(req);
        const rows = this.server.fileRows(body.transferId, FILE_ROW_LIMIT)
          ?? this.sender.fileRows(body.transferId, FILE_ROW_LIMIT);
        // A remembered transfer has none — history drops the list on purpose,
        // and the drawer already knows how to say so.
        return sendJson(res, 200, rows ?? { total: 0, files: [] });
      }

      case 'POST /api/cancel': {
        const body = await readJson(req);
        const ok = this.server.cancel(body.transferId) || this.sender.cancel(body.transferId);
        return sendJson(res, ok ? 200 : 404, { ok });
      }

      case 'POST /api/settings': {
        const body = await readJson(req);
        const patch = {};
        if (typeof body.deviceName === 'string' && body.deviceName.trim()) {
          patch.deviceName = body.deviceName.trim().slice(0, 40);
        }
        if (typeof body.downloadDir === 'string' && body.downloadDir.trim()) {
          patch.downloadDir = body.downloadDir.trim();
        }
        if (body.streams !== undefined) {
          patch.streams = Math.max(1, Math.min(16, Number(body.streams) || 4));
        }
        if (body.autoAccept !== undefined) patch.autoAccept = Boolean(body.autoAccept);
        if (LANGUAGES.has(body.language)) patch.language = body.language;
        if (THEMES.has(body.theme)) patch.theme = body.theme;
        if (VIEWS.has(body.transferView)) patch.transferView = body.transferView;
        saveConfig(patch);
        this.discovery.config = loadConfig();
        this.discovery.announce();
        this.push();
        return sendJson(res, 200, this.state().settings);
      }

      case 'POST /api/open-folder': {
        const body = await readJson(req);
        const opened = await revealInFileManager({
          target: body.path || loadConfig().downloadDir,
          select: body.select || null,
        });
        return sendJson(res, 200, { ok: true, opened });
      }

      default:
        return sendJson(res, 404, { error: 'not found' });
    }
  }

  #requirePeer(peerId) {
    const peer = this.discovery.peers.find((p) => p.id === peerId);
    if (!peer) throw new Error('that device is no longer on the network');
    return peer;
  }

  /**
   * Knock on one address until somebody answers, or three seconds pass.
   *
   * Repeated rather than sent once: these are UDP datagrams on a network that
   * has already proved it drops things, and the far side may still be starting
   * up. Once it answers, the peer is in the list like any other — nothing
   * downstream knows or cares that this one was typed in.
   */
  async #findByAddress(address) {
    const before = new Set(this.discovery.peers.map((p) => p.id));
    const deadline = Date.now() + 3000;
    while (Date.now() < deadline) {
      this.discovery.reach(address);
      await new Promise((resolve) => setTimeout(resolve, 400));
      const peers = this.discovery.peers;
      const found = peers.find((p) => p.address === address)
        ?? peers.find((p) => !before.has(p.id));
      if (found) return found;
    }
    return null;
  }

  state() {
    const config = loadConfig();
    // One list, two sources. A transfer this run knows about is shown live —
    // it still has per-file progress and a throughput trace behind it — and
    // the stored copy of the same transfer is skipped, so nothing appears
    // twice. Everything older comes from history, which is what makes the
    // list survive a restart at all.
    const live = [...this.server.transfers, ...this.sender.transfers];
    const known = new Set(live.map((t) => t.id));
    const transfers = [...live, ...historyEntries().filter((e) => !known.has(e.id))]
      .sort((a, b) => b.createdAt - a.createdAt)
      .slice(0, 50);
    return {
      self: {
        id: config.deviceId,
        name: config.deviceName,
        os: platformLabel(),
        addresses: localInterfaces().map((i) => i.address),
      },
      peers: this.discovery.peers.map((peer) => ({ ...peer, paired: isPaired(peer.id) })),
      pairings: [...this.server.pairings, ...this.sender.pairings]
        .sort((a, b) => b.createdAt - a.createdAt),
      transfers,
      settings: {
        deviceName: config.deviceName,
        downloadDir: config.downloadDir,
        streams: config.streams,
        autoAccept: config.autoAccept,
        language: config.language,
        theme: config.theme,
        transferView: config.transferView,
        pairedDevices: pairedPeers().sort((a, b) => b.pairedAt - a.pairedAt),
      },
    };
  }

  #openEventStream(res) {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.write('retry: 1000\n\n');
    this.#clients.add(res);
    res.on('close', () => this.#clients.delete(res));
    this.#write(res, this.state());
  }

  /** Coalesce bursts of events into at most one frame every 100 ms. */
  push() {
    if (this.#pushQueued) return;
    this.#pushQueued = true;
    setTimeout(() => {
      this.#pushQueued = false;
      if (this.#clients.size === 0) return;
      const state = this.state();
      for (const client of this.#clients) this.#write(client, state);
    }, 100);
  }

  #write(res, state) {
    // A client that cannot keep up would otherwise have frames queued in
    // memory without limit. Dropping one is the right loss: the next carries
    // the same thing, only newer.
    if (res.writableNeedDrain) return;
    res.write(`data: ${JSON.stringify(state)}\n\n`);
  }

  async #serveStatic(pathname, res) {
    const name = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
    let body = await readUiFile(name);
    if (!body) return sendJson(res, 404, { error: 'not found' });

    if (name === 'index.html') {
      body = Buffer.from(
        body.toString('utf8').replace('__FLYSHARE_TOKEN__', this.token),
        'utf8',
      );
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(name)] ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; "
        + "style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; base-uri 'none'",
    });
    res.end(body);
  }
}

// Allowlists, so a malformed request cannot write junk into the config file.
const LANGUAGES = new Set(['auto', 'en', 'de', 'uk', 'pl']);
const THEMES = new Set(['system', 'light', 'dark']);
const VIEWS = new Set(['list', 'grid']);

const ACTIVE_STATUSES = new Set([
  'pending', 'scanning', 'connecting', 'waiting', 'sending', 'receiving', 'finalizing',
]);

/**
 * Strict, because this string becomes the destination of a datagram. A regex
 * that accepts "192.168.1.999" would have the socket resolve it as a hostname,
 * which is a DNS lookup nobody asked for.
 */
function isIpv4(value) {
  const parts = value.split('.');
  return parts.length === 4 && parts.every((part) => (
    /^\d{1,3}$/.test(part) && Number(part) <= 255
  ));
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 8 * 1024 * 1024) throw new Error('request body too large');
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

/**
 * Show a received file or folder in the system file manager.
 *
 * Two things this deliberately does not do: fail silently, or fire and forget.
 * spawn() reports a missing binary through an 'error' event, and an unhandled
 * one of those takes the whole process down — so it is always listened for,
 * and the caller is told what happened rather than left guessing.
 */
async function revealInFileManager({ target, select }) {
  const wanted = select ?? target;
  try {
    await fsp.access(wanted);
  } catch {
    throw new Error(`that folder is no longer there: ${wanted}`);
  }

  const { command, args } = revealCommand(wanted, Boolean(select));

  await new Promise((resolve, reject) => {
    let child;
    try {
      child = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: true });
    } catch (err) {
      return reject(new Error(`cannot open the file manager: ${err.message}`));
    }
    child.once('error', (err) => reject(new Error(`cannot open the file manager: ${err.message}`)));
    // explorer.exe exits non-zero even when it worked, so a clean spawn is
    // the only success signal worth waiting for.
    child.once('spawn', () => {
      child.unref();
      resolve();
    });
  });

  return wanted;
}

function revealCommand(target, isFile) {
  if (process.platform === 'win32') {
    // The comma form is one argument, not two.
    return { command: 'explorer.exe', args: isFile ? [`/select,${target}`] : [target] };
  }
  if (process.platform === 'darwin') {
    return { command: 'open', args: isFile ? ['-R', target] : [target] };
  }
  return { command: 'xdg-open', args: [isFile ? path.dirname(target) : target] };
}
