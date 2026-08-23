import { LANGUAGES, resolveLanguage, createTranslator } from './i18n.js';

const TOKEN = document.querySelector('meta[name="flyshare-token"]').content;

// Matches the sender's default stream count: more concurrent uploads would
// just queue on the far side of the local socket anyway.
const UPLOAD_CONCURRENCY = 4;
const UPLOAD_CHUNK = 8 * 1024 * 1024;

const el = (id) => document.getElementById(id);
const dom = {
  selfName: el('self-name'),
  selfMeta: el('self-meta'),
  peers: el('peers'),
  peersEmpty: el('peers-empty'),
  peersHint: el('peers-hint'),
  peerCount: el('peer-count'),
  dropzone: el('dropzone'),
  dropzoneLine: el('dropzone-line'),
  pickFiles: el('pick-files'),
  pickFolder: el('pick-folder'),
  transfers: el('transfers'),
  transfersEmpty: el('transfers-empty'),
  openFolder: el('open-folder'),
  settings: el('settings'),
  scrim: el('scrim'),
  settingsToggle: el('settings-toggle'),
  settingsClose: el('settings-close'),
  settingsStatus: el('settings-status'),
  setName: el('set-name'),
  setDir: el('set-dir'),
  browseDir: el('browse-dir'),
  setLanguage: el('set-language'),
  setTheme: el('set-theme'),
  setView: el('set-view'),
  setStreams: el('set-streams'),
  setStreamsValue: el('set-streams-value'),
  setAuto: el('set-auto'),
  pairedList: el('paired-list'),
  pairedEmpty: el('paired-empty'),
  pairDialog: el('pair-dialog'),
  pairPeer: el('pair-peer'),
  pairCode: el('pair-code'),
  pairNote: el('pair-note'),
  pairActions: el('pair-actions'),
};

let state = { self: {}, peers: [], pairings: [], transfers: [], settings: {} };
let selectedPeerId = null;
let settingsDirty = false;
let language = null;
let t = createTranslator(resolveLanguage('auto'));

const peerNodes = new Map();
const transferNodes = new Map();
const traces = new Map(); // transferId -> rolling throughput samples
const dismissedPairings = new Set();
const announcedPairings = new Set();

// --- api -----------------------------------------------------------------

async function api(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-flyshare-token': TOKEN },
    body: JSON.stringify(body ?? {}),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 403) {
    // The app restarted and issued a new session token; this page is stale.
    checkSession();
    throw new Error(t.t('error.stale'));
  }
  if (!res.ok) throw new Error(data.error || t.t('error.requestFailed', { status: res.status }));
  return data;
}

function connect() {
  const events = new EventSource(`/api/events?token=${encodeURIComponent(TOKEN)}`);
  events.onmessage = (event) => {
    state = JSON.parse(event.data);
    render();
  };
  // EventSource retries on its own, but a restarted app issues a new session
  // token and every retry then 403s forever. Detect that and reload, which
  // picks up the current token, instead of leaving a page that looks alive.
  events.onerror = () => { checkSession(); };
}

let sessionCheckPending = false;

async function checkSession() {
  if (sessionCheckPending) return;
  sessionCheckPending = true;
  try {
    const res = await fetch('/api/state', { headers: { 'x-flyshare-token': TOKEN } });
    if (res.status === 403) return window.location.reload();
  } catch {
    // Server is down rather than restarted — keep the page and let it retry.
  } finally {
    sessionCheckPending = false;
  }
}

// --- formatting ----------------------------------------------------------

function bytes(value) {
  const units = t.units();
  let n = Number(value) || 0;
  let unit = 0;
  while (n >= 1024 && unit < units.length - 1) { n /= 1024; unit += 1; }
  const digits = n < 10 && unit > 0 ? 1 : 0;
  return `${n.toFixed(digits)} ${units[unit]}`;
}

function rate(bytesPerSecond) {
  if (!bytesPerSecond) return '—';
  return t.t('units.perSecond', { value: bytes(bytesPerSecond) });
}

function duration(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '—';
  if (seconds < 60) return t.t('time.seconds', { s: Math.max(1, Math.round(seconds)) });
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return t.t('time.minutes', { m: minutes, s: Math.round(seconds % 60) });
  return t.t('time.hours', { h: Math.floor(minutes / 60), m: minutes % 60 });
}

const TONE = { completed: 'is-ok', failed: 'is-err' };
const ACTIVE = new Set(['connecting', 'waiting', 'sending', 'receiving', 'finalizing']);

// --- preferences ---------------------------------------------------------

/**
 * Language, theme and layout come from the server config, so they survive a
 * reload and follow the person to whichever browser they open the UI in.
 */
function applyPreferences(settings) {
  const resolved = resolveLanguage(settings.language ?? 'auto');
  if (resolved !== language) {
    language = resolved;
    t = createTranslator(resolved);
    document.documentElement.lang = resolved;
    translateStatic();
  }

  const theme = settings.theme ?? 'system';
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.dataset.theme = theme;

  dom.transfers.classList.toggle('transfers--grid', settings.transferView === 'grid');
}

/** Fill every element that carries a data-i18n key. */
function translateStatic() {
  for (const node of document.querySelectorAll('[data-i18n]')) {
    node.textContent = t.t(node.dataset.i18n);
  }
  // Rebuilt on every language change so labels inside them follow suit.
  for (const node of transferNodes.values()) node.actionsKind = null;
  dom.pairActions.dataset.kind = '';
}

// --- rendering -----------------------------------------------------------

function render() {
  applyPreferences(state.settings ?? {});
  renderSelf();
  renderPeers();
  renderPairing();
  renderTransfers();
  if (!settingsDirty) renderSettings();
}

function renderSelf() {
  dom.selfName.textContent = state.self.name ?? '—';
  const address = (state.self.addresses ?? [])[0];
  dom.selfMeta.textContent = [state.self.os, address].filter(Boolean).join(' · ');
}

function renderPeers() {
  const peers = state.peers ?? [];
  dom.peerCount.textContent = peers.length ? t.plural('peers.count', peers.length) : '';
  dom.peersEmpty.hidden = peers.length > 0;
  dom.peersHint.hidden = !peers.some((p) => !p.paired);

  const paired = peers.filter((p) => p.paired);
  if (selectedPeerId && !paired.some((p) => p.id === selectedPeerId)) selectedPeerId = null;
  if (!selectedPeerId && paired.length === 1) selectedPeerId = paired[0].id;

  const seen = new Set();
  for (const peer of peers) {
    seen.add(peer.id);
    let node = peerNodes.get(peer.id);
    if (!node) {
      node = buildPeer(peer.id);
      peerNodes.set(peer.id, node);
      dom.peers.append(node.root);
    }
    node.name.textContent = peer.name;
    node.meta.textContent = `${peer.os} · ${peer.address}`;
    node.action.textContent = peer.paired ? '' : t.t('peers.connect');
    node.dot.classList.toggle('is-unpaired', !peer.paired);
    node.root.classList.toggle('is-unpaired', !peer.paired);
    node.root.classList.toggle('is-selected', peer.id === selectedPeerId);
    node.root.setAttribute('aria-pressed', String(peer.id === selectedPeerId));
  }
  for (const [id, node] of peerNodes) {
    if (!seen.has(id)) { node.root.remove(); peerNodes.delete(id); }
  }
  renderDropzone();
}

function buildPeer(peerId) {
  const root = document.createElement('button');
  root.className = 'peer';
  root.type = 'button';
  root.innerHTML = '<span class="peer__dot"></span><div class="peer__name"></div>'
    + '<div class="peer__meta"></div><span class="peer__action"></span>';
  root.addEventListener('click', () => {
    const peer = (state.peers ?? []).find((p) => p.id === peerId);
    if (!peer) return;
    // An unpaired device cannot receive anything yet, so the first click
    // starts pairing rather than selecting it as a target.
    if (!peer.paired) return void api('/api/pair', { peerId }).catch(toast);
    selectedPeerId = selectedPeerId === peerId ? null : peerId;
    renderPeers();
  });
  return {
    root,
    name: root.querySelector('.peer__name'),
    meta: root.querySelector('.peer__meta'),
    action: root.querySelector('.peer__action'),
    dot: root.querySelector('.peer__dot'),
  };
}

function selectedPeer() {
  return (state.peers ?? []).find((p) => p.id === selectedPeerId) ?? null;
}

function renderDropzone() {
  const peer = selectedPeer();
  dom.dropzoneLine.textContent = peer
    ? t.t('drop.dropFor', { name: peer.name })
    : t.t('drop.selectDevice');
  dom.pickFiles.disabled = !peer;
  dom.pickFolder.disabled = !peer;
  dom.dropzone.classList.toggle('is-armed', Boolean(peer));
}

// --- pairing --------------------------------------------------------------

const PAIRING_LIVE = new Set(['exchanging', 'awaiting-confirmation', 'awaiting-peer']);

function formatCode(code) {
  return code ? `${code.slice(0, 3)} ${code.slice(3)}` : '••• •••';
}

function renderPairing() {
  const pairings = state.pairings ?? [];

  for (const pairing of pairings) {
    if (pairing.status === 'paired' && !announcedPairings.has(pairing.id)) {
      announcedPairings.add(pairing.id);
      dismissedPairings.add(pairing.id);
      toast(t.t('pair.success', { name: pairing.peer.name }), 'ok');
    }
  }

  const active = pairings.find((p) => !dismissedPairings.has(p.id)
    && (PAIRING_LIVE.has(p.status) || p.status === 'failed' || p.status === 'rejected'));

  dom.pairDialog.hidden = !active;
  if (!active) {
    if (dom.settings.hidden) dom.scrim.hidden = true;
    return;
  }
  dom.scrim.hidden = false;

  const broken = active.status === 'failed' || active.status === 'rejected';
  dom.pairDialog.classList.toggle('is-failed', broken);
  dom.pairPeer.textContent = active.peer.name;
  dom.pairCode.textContent = broken ? t.t('pair.failed') : formatCode(active.code);

  if (broken) dom.pairNote.textContent = active.error ?? t.t('pair.retryHint');
  else if (active.status === 'exchanging') dom.pairNote.textContent = t.t('pair.exchanging');
  else if (active.status === 'awaiting-confirmation') {
    dom.pairNote.textContent = t.t('pair.compareOn', { name: active.peer.name });
  } else dom.pairNote.textContent = t.t('pair.waitingOn', { name: active.peer.name });

  renderPairActions(active, broken);
}

function renderPairActions(pairing, broken) {
  const kind = `${pairing.id}:${broken ? 'close' : pairing.status}:${language}`;
  if (dom.pairActions.dataset.kind === kind) return;
  dom.pairActions.dataset.kind = kind;
  dom.pairActions.replaceChildren();

  const dismiss = () => {
    dismissedPairings.add(pairing.id);
    renderPairing();
  };

  if (broken) {
    dom.pairActions.append(button(t.t('pair.close'), 'button', async () => dismiss()));
    return;
  }
  if (pairing.status === 'awaiting-confirmation') {
    dom.pairActions.append(
      button(t.t('pair.match'), 'button button--primary',
        () => api('/api/pair/respond', { pairingId: pairing.id, accept: true })),
      button(t.t('pair.noMatch'), 'button',
        () => api('/api/pair/respond', { pairingId: pairing.id, accept: false })),
    );
    return;
  }
  dom.pairActions.append(button(t.t('action.cancel'), 'button', async () => {
    if (pairing.direction === 'out') await api('/api/pair/cancel', { pairingId: pairing.id }).catch(() => {});
    else await api('/api/pair/respond', { pairingId: pairing.id, accept: false }).catch(() => {});
    dismiss();
  }));
}

// --- transfers ------------------------------------------------------------

function renderTransfers() {
  const transfers = state.transfers ?? [];
  dom.transfersEmpty.hidden = transfers.length > 0;

  const seen = new Set();
  transfers.forEach((transfer, index) => {
    seen.add(transfer.id);
    let node = transferNodes.get(transfer.id);
    if (!node) {
      node = buildTransfer(transfer);
      transferNodes.set(transfer.id, node);
    }
    updateTransfer(node, transfer);
    if (dom.transfers.children[index] !== node.root) {
      dom.transfers.insertBefore(node.root, dom.transfers.children[index] ?? null);
    }
  });
  for (const [id, node] of transferNodes) {
    if (!seen.has(id)) { node.root.remove(); transferNodes.delete(id); traces.delete(id); }
  }
}

function buildTransfer(transfer) {
  const root = document.createElement('article');
  root.className = 'transfer';
  root.innerHTML = `
    <div class="transfer__head">
      <span class="transfer__arrow"></span>
      <span class="transfer__title"></span>
      <span class="transfer__status"></span>
      <span class="transfer__rate"></span>
    </div>
    <canvas class="transfer__trace" hidden></canvas>
    <div class="transfer__track"><div class="transfer__fill"></div></div>
    <div class="transfer__meta">
      <span class="transfer__progress"></span>
      <span class="transfer__meta-spacer"></span>
      <span class="transfer__eta"></span>
    </div>
    <p class="transfer__hint" hidden></p>
    <div class="transfer__actions"></div>`;

  const node = {
    root,
    arrow: root.querySelector('.transfer__arrow'),
    title: root.querySelector('.transfer__title'),
    status: root.querySelector('.transfer__status'),
    rate: root.querySelector('.transfer__rate'),
    trace: root.querySelector('.transfer__trace'),
    fill: root.querySelector('.transfer__fill'),
    progress: root.querySelector('.transfer__progress'),
    eta: root.querySelector('.transfer__eta'),
    hint: root.querySelector('.transfer__hint'),
    actions: root.querySelector('.transfer__actions'),
  };
  node.arrow.textContent = transfer.direction === 'in' ? '↓' : '↑';
  return node;
}

function titleFor(transfer) {
  const first = transfer.files?.[0]?.rel ?? '';
  const name = first.split('/').pop() || '—';
  const extra = (transfer.fileCount ?? 1) - 1;
  const label = extra > 0 ? `${name} ${t.t('transfer.andMore', { n: extra })}` : name;
  const direction = t.t(transfer.direction === 'in' ? 'transfer.from' : 'transfer.to');
  return `${label} · ${direction} ${transfer.peer?.name ?? '?'}`;
}

function updateTransfer(node, transfer) {
  node.title.textContent = titleFor(transfer);
  node.status.textContent = t.t(`status.${transfer.status}`);
  node.status.className = `transfer__status ${TONE[transfer.status] ?? ''}`;
  node.root.classList.toggle('is-pending', transfer.status === 'pending');
  node.root.classList.toggle('is-failed', transfer.status === 'failed');

  const active = ACTIVE.has(transfer.status);
  const done = transfer.status === 'completed';
  const ratio = transfer.totalSize > 0 ? transfer.received / transfer.totalSize : (done ? 1 : 0);

  node.fill.style.width = `${Math.min(100, ratio * 100).toFixed(2)}%`;
  node.rate.textContent = active ? rate(transfer.speed) : '';

  const files = t.plural('transfer.files', transfer.fileCount ?? 0);
  if (done) {
    const seconds = (transfer.finishedAt - transfer.startedAt) / 1000;
    const average = seconds > 0 ? transfer.totalSize / seconds : 0;
    node.progress.textContent = `${bytes(transfer.totalSize)} · ${files}`;
    node.eta.textContent = average
      ? `${duration(seconds)} · ${t.t('transfer.average', { rate: rate(average) })}`
      : duration(seconds);
  } else if (['failed', 'declined', 'cancelled'].includes(transfer.status)) {
    node.progress.textContent = transfer.error ?? '';
    node.eta.textContent = '';
  } else {
    node.progress.textContent = `${t.t('transfer.of', {
      received: bytes(transfer.received), total: bytes(transfer.totalSize),
    })} · ${files}`;
    const remaining = transfer.speed > 0 ? (transfer.totalSize - transfer.received) / transfer.speed : NaN;
    node.eta.textContent = active && transfer.speed > 0
      ? t.t('transfer.remaining', { time: duration(remaining) })
      : '';
  }

  updateTrace(node, transfer, active);

  const hint = slowLinkHint(transfer, traces.get(transfer.id) ?? []);
  node.hint.hidden = !hint;
  if (hint) node.hint.textContent = hint;

  updateActions(node, transfer);
}

// Below this a modern Wi-Fi link is clearly not delivering what it could:
// ~100 Mbit/s, well under 5 GHz and under gigabit Ethernet.
const SLOW_LINK = 12 * 1024 * 1024;

// Many small files are legitimately slow — the per-file work dominates and the
// radio is not the culprit — so the hint stays quiet for those.
const SMALL_FILE_AVERAGE = 2 * 1024 * 1024;

/**
 * Say something only when the speed is genuinely disappointing *and* the
 * transfer is long enough for advice to be worth acting on. A warning on a
 * six-second transfer would be noise, and a warning about the radio when the
 * real cause is a thousand tiny files would be wrong.
 */
function slowLinkHint(transfer, samples) {
  if (!ACTIVE.has(transfer.status) || samples.length < 20) return null;
  if (transfer.totalSize / Math.max(transfer.fileCount, 1) < SMALL_FILE_AVERAGE) return null;

  const recent = samples.slice(-20);
  const median = [...recent].sort((a, b) => a - b)[Math.floor(recent.length / 2)];
  if (median === 0 || median >= SLOW_LINK) return null;

  // No point advising someone whose transfer is about to end anyway. A full
  // minute of waiting is enough to justify one line of advice.
  const secondsLeft = (transfer.totalSize - transfer.received) / median;
  if (secondsLeft < 60) return null;

  return t.t('transfer.slowLink');
}

function updateActions(node, transfer) {
  const wanted = transfer.status === 'pending' ? 'decide'
    : ACTIVE.has(transfer.status) ? 'cancel'
      : transfer.status === 'completed' && transfer.direction === 'in' ? 'reveal' : 'none';

  if (node.actionsKind === wanted) return;
  node.actionsKind = wanted;
  node.actions.replaceChildren();

  if (wanted === 'decide') {
    node.actions.append(
      button(t.t('action.accept'), 'button button--primary button--quiet',
        () => api('/api/respond', { transferId: transfer.id, accept: true })),
      button(t.t('action.decline'), 'button button--quiet',
        () => api('/api/respond', { transferId: transfer.id, accept: false })),
    );
  } else if (wanted === 'cancel') {
    node.actions.append(button(t.t('action.cancel'), 'button button--quiet',
      () => api('/api/cancel', { transferId: transfer.id })));
  } else if (wanted === 'reveal') {
    node.actions.append(button(t.t('action.reveal'), 'button button--quiet', () => api('/api/open-folder', {
      path: transfer.destDir,
      // A single file gets highlighted; a folder of them just gets opened.
      select: transfer.fileCount === 1 ? (transfer.files?.[0]?.path ?? null) : null,
    })));
  }
}

function button(text, className, onClick) {
  const node = document.createElement('button');
  node.type = 'button';
  node.className = className;
  node.textContent = text;
  node.addEventListener('click', () => onClick().catch(toast));
  return node;
}

// --- throughput trace ----------------------------------------------------
// The one place saturated colour is allowed: hue encodes the actual rate on a
// log ramp, so Wi-Fi jitter and recovery are visible rather than averaged away.

const RAMP = [
  [0.00, [61, 90, 254]],
  [0.30, [0, 178, 202]],
  [0.55, [140, 198, 63]],
  [0.78, [255, 179, 0]],
  [1.00, [255, 82, 82]],
];

const RAMP_CEILING = 300 * 1024 * 1024; // covers loopback and 2.5 GbE, not just Wi-Fi

function rampColor(bytesPerSecond) {
  const t01 = Math.min(1, Math.log2(bytesPerSecond / 65536 + 1) / Math.log2(RAMP_CEILING / 65536 + 1));
  for (let i = 1; i < RAMP.length; i += 1) {
    if (t01 > RAMP[i][0] && i < RAMP.length - 1) continue;
    const [t0, c0] = RAMP[i - 1];
    const [t1, c1] = RAMP[i];
    const k = (t01 - t0) / (t1 - t0);
    const mix = c0.map((v, j) => Math.round(v + (c1[j] - v) * Math.max(0, Math.min(1, k))));
    return `rgb(${mix.join(',')})`;
  }
  return 'rgb(61,90,254)';
}

const BAR_WIDTH = 3;
const BAR_GAP = 1;

/**
 * How many bars fit on a canvas — and therefore how much history is worth
 * keeping. Both the ring buffer and the renderer read this, because a fixed
 * cap smaller than the number of bars that fit would leave part of the chart
 * permanently blank no matter how long the transfer ran.
 */
function traceSlots(canvas) {
  const width = canvas.clientWidth;
  return width > 0 ? Math.floor(width / (BAR_WIDTH + BAR_GAP)) : 240;
}

function updateTrace(node, transfer, active) {
  let samples = traces.get(transfer.id);
  if (!samples) { samples = []; traces.set(transfer.id, samples); }

  // Only while bytes are actually moving. Sampling during 'connecting' or
  // 'waiting' would fill the history with zeroes and draw a chart of nothing.
  const moving = transfer.status === 'sending' || transfer.status === 'receiving';
  if (moving) {
    samples.push(transfer.speed || 0);
    const cap = Math.max(traceSlots(node.trace), 240);
    while (samples.length > cap) samples.shift();
  }
  // The trace earns its space while bytes are moving, or afterwards if it
  // recorded enough of the link to be a real record. A transfer that finished
  // in a second would otherwise leave an empty band where a chart should be.
  const show = samples.length >= 4 && (active || samples.length >= 12);
  node.trace.hidden = !show;
  if (show) drawTrace(node.trace, samples);
}

function drawTrace(canvas, samples) {
  const dpr = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  if (width === 0) return;
  if (canvas.width !== Math.round(width * dpr)) {
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
  }

  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const slots = traceSlots(canvas);
  const visible = samples.slice(-slots);
  const peak = Math.max(...visible, 1024 * 1024);

  visible.forEach((value, i) => {
    const barHeight = Math.max(1, (value / peak) * (height - 2));
    const x = width - (visible.length - i) * (BAR_WIDTH + BAR_GAP);
    ctx.fillStyle = rampColor(value);
    ctx.fillRect(x, height - barHeight, BAR_WIDTH, barHeight);
  });
}

// --- sending -------------------------------------------------------------

async function sendPicked(mode) {
  const peer = selectedPeer();
  if (!peer) return;
  try {
    const { paths } = await api('/api/pick', { mode });
    if (!paths.length) return;
    await api('/api/send', { peerId: peer.id, paths });
  } catch (err) {
    toast(err);
  }
}

/**
 * Files dropped into the page have no path on disk we can read, so their bytes
 * are streamed through the local server and straight onto the wire — sliced
 * into chunks that go out on parallel connections, same as a disk-side send.
 */
async function sendDropped(picked) {
  const peer = selectedPeer();
  if (!peer || picked.length === 0) return;

  let transfer;
  try {
    transfer = await api('/api/upload/begin', {
      peerId: peer.id,
      files: picked.map((p) => ({ rel: p.rel, size: p.file.size })),
    });
  } catch (err) {
    return toast(err);
  }

  const jobs = [];
  picked.forEach((entry, fileIndex) => {
    for (let offset = 0; offset < entry.file.size; offset += UPLOAD_CHUNK) {
      const length = Math.min(UPLOAD_CHUNK, entry.file.size - offset);
      jobs.push({ fileIndex, offset, length, blob: entry.file.slice(offset, offset + length) });
    }
  });

  try {
    let next = 0;
    await Promise.all(Array.from({ length: UPLOAD_CONCURRENCY }, async () => {
      while (next < jobs.length) {
        const job = jobs[next];
        next += 1;
        const res = await fetch('/api/upload/chunk', {
          method: 'POST',
          headers: {
            'x-flyshare-token': TOKEN,
            'x-transfer-id': transfer.id,
            'x-file-index': String(job.fileIndex),
            'x-offset': String(job.offset),
            'x-length': String(job.length),
          },
          body: job.blob,
        });
        if (!res.ok) {
          throw new Error((await res.json().catch(() => ({}))).error ?? t.t('error.chunkFailed'));
        }
      }
    }));
    await api('/api/upload/finish', { transferId: transfer.id });
  } catch (err) {
    api('/api/cancel', { transferId: transfer.id }).catch(() => {});
    toast(err);
  }
}

/** Walk the drop payload, keeping folder structure intact. */
async function collectDrop(dataTransfer) {
  // getAsEntry must be called before any await: the item list is torn down
  // as soon as the drop handler yields.
  const entries = [...dataTransfer.items]
    .filter((item) => item.kind === 'file')
    .map((item) => item.webkitGetAsEntry?.())
    .filter(Boolean);

  if (entries.length === 0) {
    return [...dataTransfer.files].map((file) => ({ file, rel: file.name }));
  }
  const out = [];
  for (const entry of entries) await walkEntry(entry, '', out);
  return out;
}

async function walkEntry(entry, prefix, out) {
  const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
  if (entry.isFile) {
    const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
    out.push({ file, rel });
    return;
  }
  if (!entry.isDirectory) return;
  const reader = entry.createReader();
  for (;;) {
    const batch = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
    if (batch.length === 0) break;
    for (const child of batch) await walkEntry(child, rel, out);
  }
}

// --- settings ------------------------------------------------------------

function renderSettings() {
  const s = state.settings ?? {};
  dom.setName.value = s.deviceName ?? '';
  dom.setDir.value = s.downloadDir ?? '';
  dom.setStreams.value = s.streams ?? 4;
  dom.setStreamsValue.value = s.streams ?? 4;
  dom.setAuto.checked = Boolean(s.autoAccept);

  if (dom.setLanguage.options.length !== Object.keys(LANGUAGES).length + 1) {
    dom.setLanguage.replaceChildren(
      option('auto', `${t.t('settings.themeSystem')} (${LANGUAGES[language]})`),
      ...Object.entries(LANGUAGES).map(([code, label]) => option(code, label)),
    );
  }
  dom.setLanguage.value = s.language ?? 'auto';

  markSegmented(dom.setTheme, s.theme ?? 'system');
  markSegmented(dom.setView, s.transferView ?? 'list');

  const devices = s.pairedDevices ?? [];
  dom.pairedEmpty.hidden = devices.length > 0;
  dom.pairedList.replaceChildren(...devices.map((device) => {
    const item = document.createElement('li');
    item.className = 'paired__item';

    const name = document.createElement('span');
    name.className = 'paired__name';
    name.textContent = device.name;

    const meta = document.createElement('span');
    meta.className = 'paired__meta';
    meta.textContent = device.os;

    item.append(name, meta, button(t.t('settings.forget'), 'button button--quiet',
      () => api('/api/pair/forget', { deviceId: device.id })));
    return item;
  }));
}

function option(value, label) {
  const node = document.createElement('option');
  node.value = value;
  node.textContent = label;
  return node;
}

function markSegmented(group, value) {
  for (const node of group.querySelectorAll('.segmented__option')) {
    const on = node.dataset.value === value;
    node.classList.toggle('is-on', on);
    node.setAttribute('aria-checked', String(on));
  }
}

async function saveSettings(patch) {
  try {
    await api('/api/settings', {
      deviceName: dom.setName.value,
      downloadDir: dom.setDir.value,
      streams: Number(dom.setStreams.value),
      autoAccept: dom.setAuto.checked,
      ...patch,
    });
    settingsDirty = false;
    dom.settingsStatus.textContent = t.t('settings.saved');
    setTimeout(() => { dom.settingsStatus.textContent = ''; }, 2000);
  } catch (err) {
    toast(err);
  }
}

function openSettings(open) {
  dom.settings.hidden = !open;
  dom.scrim.hidden = !open && dom.pairDialog.hidden;
  dom.settingsToggle.setAttribute('aria-expanded', String(open));
  if (open) { settingsDirty = false; renderSettings(); dom.setName.focus(); }
}

// --- toast ---------------------------------------------------------------

let toastTimer = null;

function toast(error, tone = 'error') {
  const message = error instanceof Error ? error.message : String(error);
  let node = document.querySelector('.toast');
  if (!node) {
    node = document.createElement('div');
    node.setAttribute('role', 'alert');
    document.body.append(node);
  }
  node.className = `toast toast--${tone}`;
  node.textContent = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.remove(), 6000);
}

// --- wiring --------------------------------------------------------------

dom.pickFiles.addEventListener('click', () => sendPicked('files'));
dom.pickFolder.addEventListener('click', () => sendPicked('folder'));
dom.openFolder.addEventListener('click', () => api('/api/open-folder', {}).catch(toast));

dom.settingsToggle.addEventListener('click', () => openSettings(dom.settings.hidden));
dom.settingsClose.addEventListener('click', () => openSettings(false));
dom.scrim.addEventListener('click', () => { if (!dom.settings.hidden) openSettings(false); });
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !dom.settings.hidden) openSettings(false);
});

for (const input of [dom.setName, dom.setDir]) {
  input.addEventListener('input', () => { settingsDirty = true; });
  input.addEventListener('change', () => saveSettings());
}
dom.setStreams.addEventListener('input', () => {
  settingsDirty = true;
  dom.setStreamsValue.value = dom.setStreams.value;
});
dom.setStreams.addEventListener('change', () => saveSettings());
dom.setAuto.addEventListener('change', () => saveSettings());
dom.setLanguage.addEventListener('change', () => saveSettings({ language: dom.setLanguage.value }));

/** Open the OS folder chooser rather than making someone type a path. */
dom.browseDir.addEventListener('click', async () => {
  try {
    const { paths } = await api('/api/pick', { mode: 'folder' });
    if (!paths.length) return;
    dom.setDir.value = paths[0];
    await saveSettings({ downloadDir: paths[0] });
  } catch (err) {
    toast(err);
  }
});

for (const [group, key] of [[dom.setTheme, 'theme'], [dom.setView, 'transferView']]) {
  group.addEventListener('click', (event) => {
    const option = event.target.closest('.segmented__option');
    if (!option) return;
    markSegmented(group, option.dataset.value);
    // Apply immediately so the choice feels instant, then persist.
    applyPreferences({ ...state.settings, [key]: option.dataset.value });
    saveSettings({ [key]: option.dataset.value });
  });
}

let dragDepth = 0;
window.addEventListener('dragenter', (event) => {
  event.preventDefault();
  dragDepth += 1;
  if (selectedPeer()) dom.dropzone.classList.add('is-over');
});
window.addEventListener('dragover', (event) => event.preventDefault());
window.addEventListener('dragleave', () => {
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) dom.dropzone.classList.remove('is-over');
});
window.addEventListener('drop', async (event) => {
  event.preventDefault();
  dragDepth = 0;
  dom.dropzone.classList.remove('is-over');
  if (!selectedPeer()) return toast(t.t('error.selectDeviceFirst'));
  const picked = await collectDrop(event.dataTransfer);
  await sendDropped(picked);
});

window.addEventListener('resize', () => {
  for (const [id, node] of transferNodes) {
    const samples = traces.get(id);
    if (samples && samples.length > 1) drawTrace(node.trace, samples);
  }
});

translateStatic();
connect();
