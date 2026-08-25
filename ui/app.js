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
  clearHistory: el('clear-history'),
  detail: el('detail'),
  detailTitle: el('detail-title'),
  detailLine: el('detail-line'),
  detailTiming: el('detail-timing'),
  detailError: el('detail-error'),
  detailActions: el('detail-actions'),
  detailNote: el('detail-note'),
  detailFiles: el('detail-files'),
  detailClose: el('detail-close'),
  advanced: el('advanced'),
  advancedOpen: el('advanced-open'),
  advancedClose: el('advanced-close'),
  directAddress: el('direct-address'),
  directFind: el('direct-find'),
  directStatus: el('direct-status'),
  guideMine: el('guide-mine'),
  guideTheirs: el('guide-theirs'),
  guideRoutes: el('guide-routes'),
  guideWhy: el('guide-why'),
  guideSteps: el('guide-steps'),
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

// Which transfer the detail drawer is showing, and which one its file rows
// were built for — the rows are reused between frames, so they are only torn
// down when the drawer moves to a different transfer.
let detailId = null;
let detailFilesFor = null;

const peerNodes = new Map();
const transferNodes = new Map();
const detailFileNodes = new Map();
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

/**
 * Decimal units — a kilobyte is 1000 bytes here, not 1024.
 *
 * This used to divide by 1024 while calling the result GB, which is what a
 * gibibyte is, not a gigabyte. The number was a thirteenth too small: a
 * transfer the phone described as 81.6 GB showed here as 76 GB, and anyone
 * comparing the two screens would reasonably conclude that files were being
 * lost. The Android app follows the same rule, deliberately — see
 * formatBytes() there. Change one and you must change both.
 */
function bytes(value) {
  const units = t.units();
  let n = Number(value) || 0;
  let unit = 0;
  while (n >= 1000 && unit < units.length - 1) { n /= 1000; unit += 1; }
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

/**
 * When something happened, in the interface's language rather than the
 * browser's — the two are only the same by accident, and a Polish window
 * printing Ukrainian month names is how you find that out.
 */
function when(millis) {
  if (!millis) return '';
  return new Intl.DateTimeFormat(language ?? undefined, {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(millis));
}

const TONE = { completed: 'is-ok', failed: 'is-err' };
const ACTIVE = new Set(['connecting', 'waiting', 'sending', 'receiving', 'finalizing']);
const BROKEN = new Set(['failed', 'declined', 'cancelled']);

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
  for (const node of transferNodes.values()) node.actions.dataset.kind = '';
  dom.detailActions.dataset.kind = '';
  dom.pairActions.dataset.kind = '';
  detailFilesFor = null;
  // The guide is assembled in script rather than marked up, so nothing above
  // reaches its steps.
  if (!dom.advanced.hidden) renderGuide();
}

// --- rendering -----------------------------------------------------------

function render() {
  applyPreferences(state.settings ?? {});
  renderSelf();
  renderPeers();
  renderPairing();
  renderTransfers();
  renderDetail();
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
  syncOverlay();
  if (!active) return;

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
  // Nothing to clear while everything on the list is still running, and a
  // button that would do nothing should not be there to press.
  dom.clearHistory.hidden = !transfers.some((x) => !ACTIVE.has(x.status) && x.status !== 'pending');

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
  // The title is a real button rather than a click handler on the card: the
  // card already contains buttons, and something that opens a panel has to be
  // reachable by keyboard like the rest of them.
  root.innerHTML = `
    <div class="transfer__head">
      <span class="transfer__arrow"></span>
      <button type="button" class="transfer__title"></button>
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
  node.title.addEventListener('click', () => openDetail(transfer.id));
  return node;
}

function titleFor(transfer) {
  // A remembered transfer kept the first name and nothing else of its file
  // list, which is exactly enough to be called the same thing it was called
  // while it was running — see history.js.
  const first = transfer.files?.[0]?.rel ?? transfer.firstFile ?? '';
  const name = first.split('/').pop() || '—';
  const extra = (transfer.fileCount ?? 1) - 1;
  const label = extra > 0 ? `${name} ${t.t('transfer.andMore', { n: extra })}` : name;
  const direction = t.t(transfer.direction === 'in' ? 'transfer.from' : 'transfer.to');
  return `${label} · ${direction} ${transfer.peer?.name ?? '?'}`;
}

function updateTransfer(node, transfer) {
  node.title.textContent = titleFor(transfer);
  node.status.textContent = transfer.paused
    ? t.t('status.paused')
    : t.t(`status.${transfer.status}`);
  node.status.className = `transfer__status ${TONE[transfer.status] ?? ''}`;
  node.root.classList.toggle('is-pending', transfer.status === 'pending');
  node.root.classList.toggle('is-failed', transfer.status === 'failed');

  const active = ACTIVE.has(transfer.status);
  const done = transfer.status === 'completed';
  const ratio = transfer.totalSize > 0 ? transfer.received / transfer.totalSize : (done ? 1 : 0);

  node.fill.style.width = `${Math.min(100, ratio * 100).toFixed(2)}%`;
  node.rate.textContent = active ? rate(transfer.speed) : '';

  const { progress, timing } = describe(transfer);
  // On a card the error takes the place of the byte count: there is one line
  // for it, and a transfer that failed has nothing else worth putting there.
  node.progress.textContent = BROKEN.has(transfer.status) ? (transfer.error ?? progress) : progress;
  node.eta.textContent = timing;

  updateTrace(node, transfer, active);

  const hint = slowLinkHint(transfer, traces.get(transfer.id) ?? []);
  node.hint.hidden = !hint;
  if (hint) node.hint.textContent = hint;

  renderActions(node.actions, transfer);
}

/**
 * The two lines of numbers under a transfer, written once for the card and
 * the detail drawer both. They show the same transfer and disagreeing about
 * how far along it is would be worse than either version alone.
 */
function describe(transfer) {
  const files = t.plural('transfer.files', transfer.fileCount ?? 0);
  const finished = transfer.finishedAt ?? 0;

  if (transfer.status === 'completed') {
    const seconds = (finished - transfer.startedAt) / 1000;
    const average = seconds > 0 ? transfer.totalSize / seconds : 0;
    const took = average
      ? `${duration(seconds)} · ${t.t('transfer.average', { rate: rate(average) })}`
      : duration(seconds);
    return { progress: `${bytes(transfer.totalSize)} · ${files}`, timing: [when(finished), took].filter(Boolean).join(' · ') };
  }

  if (BROKEN.has(transfer.status)) {
    return {
      progress: `${t.t('transfer.of', {
        received: bytes(transfer.received), total: bytes(transfer.totalSize),
      })} · ${files}`,
      timing: when(finished),
    };
  }

  // Elapsed as well as remaining. A long transfer wants both: the estimate
  // moves around with the link, and how long it has already been running is
  // the one number that cannot be wrong.
  const parts = [];
  if (transfer.startedAt) {
    parts.push(t.t('transfer.elapsed', { time: duration((Date.now() - transfer.startedAt) / 1000) }));
  }
  if (ACTIVE.has(transfer.status) && transfer.speed > 0) {
    parts.push(t.t('transfer.remaining', {
      time: duration((transfer.totalSize - transfer.received) / transfer.speed),
    }));
  }
  return {
    progress: `${t.t('transfer.of', {
      received: bytes(transfer.received), total: bytes(transfer.totalSize),
    })} · ${files}`,
    timing: parts.join(' · '),
  };
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

/**
 * The controls for one transfer, into whichever container asked for them —
 * the card, or the detail drawer, which covers the card while it is open and
 * would otherwise put the pause button out of reach.
 */
function renderActions(container, transfer) {
  // A sender that can pause gets a second button, and which one it is depends
  // on the current state — so the paused flag is part of the identity of this
  // set of controls, not just of their labels.
  const canPause = transfer.direction === 'out' && transfer.canPause;
  const wanted = transfer.status === 'pending' ? 'decide'
    : ACTIVE.has(transfer.status) ? (canPause ? `cancel:${transfer.paused ? 'resume' : 'pause'}` : 'cancel')
      : transfer.status === 'completed' && transfer.direction === 'in' ? 'reveal' : 'none';

  const kind = `${transfer.id}:${wanted}`;
  if (container.dataset.kind === kind) return;
  container.dataset.kind = kind;
  container.replaceChildren();

  if (wanted === 'decide') {
    container.append(
      button(t.t('action.accept'), 'button button--primary button--quiet',
        () => api('/api/respond', { transferId: transfer.id, accept: true })),
      button(t.t('action.decline'), 'button button--quiet',
        () => api('/api/respond', { transferId: transfer.id, accept: false })),
    );
  } else if (wanted.startsWith('cancel')) {
    if (wanted.endsWith('resume')) {
      container.append(button(t.t('action.resume'), 'button button--quiet',
        () => api('/api/resume', { transferId: transfer.id })));
    } else if (wanted.endsWith('pause')) {
      container.append(button(t.t('action.pause'), 'button button--quiet',
        () => api('/api/pause', { transferId: transfer.id })));
    }
    container.append(button(t.t('action.cancel'), 'button button--quiet',
      () => api('/api/cancel', { transferId: transfer.id })));
  } else if (wanted === 'reveal') {
    container.append(button(t.t('action.reveal'), 'button button--quiet', () => api('/api/open-folder', {
      path: transfer.destDir,
      // A single file gets highlighted; a folder of them just gets opened.
      // A remembered transfer kept that one path and nothing else — see
      // history.js — so both shapes are read here.
      select: transfer.fileCount === 1 ? (transfer.files?.[0]?.path ?? transfer.filePath ?? null) : null,
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

// --- transfer detail ------------------------------------------------------
//
// The list answers "is anything happening". This answers "what exactly": which
// files have arrived, which one is moving right now, which are still queued —
// and, for a file that has landed, where to find it.

// Nobody reads four thousand rows, and building that many would stall the
// frame that opens the drawer. Past this the panel says how many it is not
// showing rather than pretending the list is complete.
const DETAIL_FILE_LIMIT = 500;

function openDetail(id) {
  detailId = id;
  detailFilesFor = null;
  if (!dom.settings.hidden) openSettings(false);
  if (!dom.advanced.hidden) openAdvanced(false);
  renderDetail();
}

function closeDetail() {
  detailId = null;
  detailFilesFor = null;
  detailFileNodes.clear();
  dom.detail.hidden = true;
  dom.detailActions.dataset.kind = '';
  syncOverlay();
}

function renderDetail() {
  if (!detailId) return;
  const transfer = (state.transfers ?? []).find((x) => x.id === detailId);
  // Cleared, or pushed off the end of a list that keeps only the last fifty.
  if (!transfer) return closeDetail();

  dom.detail.hidden = false;
  syncOverlay();

  dom.detailTitle.textContent = titleFor(transfer);
  // The heading is one line and a folder of photos runs past it.
  dom.detailTitle.title = dom.detailTitle.textContent;
  const { progress, timing } = describe(transfer);
  const status = transfer.paused ? t.t('status.paused') : t.t(`status.${transfer.status}`);
  dom.detailLine.textContent = `${status} · ${progress}`;
  dom.detailTiming.textContent = timing;
  dom.detailError.hidden = !transfer.error;
  dom.detailError.textContent = transfer.error ?? '';

  renderActions(dom.detailActions, transfer);
  renderDetailFiles(transfer);
}

function renderDetailFiles(transfer) {
  const files = transfer.files ?? [];
  const shown = files.slice(0, DETAIL_FILE_LIMIT);

  dom.detailNote.hidden = files.length > 0 && files.length <= DETAIL_FILE_LIMIT;
  if (files.length === 0) {
    // Only a remembered transfer reaches this: the file list is deliberately
    // not stored, and the folder it landed in answers the question instead.
    dom.detailNote.textContent = t.t('detail.noFileList');
  } else if (files.length > DETAIL_FILE_LIMIT) {
    dom.detailNote.textContent = t.t('detail.more', { shown: DETAIL_FILE_LIMIT, total: files.length });
  }

  // Rows are built once and then updated in place. At four hundred files a
  // rebuild three times a second is a page that cannot be scrolled.
  if (detailFilesFor !== transfer.id) {
    detailFilesFor = transfer.id;
    detailFileNodes.clear();
    dom.detailFiles.replaceChildren(...shown.map((file, index) => {
      const node = buildFileRow(transfer, index);
      detailFileNodes.set(index, node);
      return node.root;
    }));
  }
  shown.forEach((file, index) => updateFileRow(detailFileNodes.get(index), transfer, file));
}

function buildFileRow(transfer, index) {
  const root = document.createElement('li');
  root.className = 'file';
  root.innerHTML = '<span class="file__mark"></span>'
    + '<span class="file__body"><span class="file__name"></span>'
    + '<span class="file__track" hidden><span class="file__fill"></span></span></span>'
    + '<span class="file__size"></span>';

  const node = {
    root,
    mark: root.querySelector('.file__mark'),
    name: root.querySelector('.file__name'),
    track: root.querySelector('.file__track'),
    fill: root.querySelector('.file__fill'),
    size: root.querySelector('.file__size'),
    shape: null,
  };

  // Reveal rather than open. A phone has nowhere to put you down, so the
  // Android app launches the file; a desktop has a file manager, and pointing
  // at a file someone just accepted over the network is the safer half of
  // what they wanted anyway.
  node.reveal = button(t.t('action.revealFile'), 'button button--quiet file__reveal', () => {
    // Read at click time, not from the transfer these rows were built from.
    // The destination is only chosen when an offer is accepted, and a file's
    // path only exists once it has been written — both are still empty if the
    // drawer was opened while the transfer was waiting for an answer.
    const live = (state.transfers ?? []).find((x) => x.id === transfer.id);
    return api('/api/open-folder', {
      path: live?.destDir ?? null,
      select: live?.files?.[index]?.path ?? null,
    });
  });
  node.reveal.title = t.t('action.reveal');
  return node;
}

function updateFileRow(node, transfer, file) {
  if (!node) return;

  const status = fileStatus(transfer, file);
  const moved = file.received ?? 0;
  const ratio = file.size > 0 ? moved / file.size : 1;
  // Only redraw when something visible actually moved. Most rows in a long
  // transfer are waiting or done and do not change from one frame to the next.
  const shape = `${status}:${status === 'moving' ? Math.round(ratio * 200) : 0}`;
  if (node.shape === shape) return;
  node.shape = shape;

  node.root.className = `file is-${status}`;
  node.name.textContent = file.rel;
  node.name.title = file.rel;
  node.mark.title = t.t(`detail.${status}`);
  node.track.hidden = status !== 'moving';
  if (status === 'moving') node.fill.style.width = `${Math.min(100, ratio * 100).toFixed(1)}%`;
  node.size.textContent = status === 'moving'
    ? t.t('transfer.of', { received: bytes(moved), total: bytes(file.size) })
    : bytes(file.size);

  const canReveal = transfer.direction === 'in' && status === 'done' && Boolean(file.path);
  if (canReveal && !node.reveal.isConnected) node.root.append(node.reveal);
  else if (!canReveal && node.reveal.isConnected) node.reveal.remove();
}

function fileStatus(transfer, file) {
  const size = file.size ?? 0;
  const moved = file.received ?? 0;
  if (transfer.status === 'pending') return 'waiting';
  // An empty file never goes on the wire — the receiver creates it while it
  // prepares the destination — so it is finished as soon as anything is.
  if (size === 0 || moved >= size) return 'done';
  if (BROKEN.has(transfer.status)) return moved > 0 ? 'failed' : 'waiting';
  return moved > 0 ? 'moving' : 'waiting';
}

/** One scrim, four things that can sit on top of it. */
function syncOverlay() {
  dom.scrim.hidden = dom.settings.hidden && dom.detail.hidden
    && dom.advanced.hidden && dom.pairDialog.hidden;
}

// --- advanced -------------------------------------------------------------
//
// Two things for the case the rest of the interface assumes away: that the two
// devices are on a network at all, and that they can see each other on it.

const PLATFORMS = ['windows', 'macos', 'linux', 'android'];
const OS_LABEL = { windows: 'Windows', macos: 'macOS', linux: 'Linux', android: 'Android' };
const HOTSPOT_COMMAND = 'nmcli device wifi hotspot ssid flyshare password 12345678';

let guideMine = null;
let guideTheirs = null;
let guideRoute = null;

function openAdvanced(open) {
  dom.advanced.hidden = !open;
  if (open) {
    if (!dom.settings.hidden) openSettings(false);
    if (!dom.detail.hidden) closeDetail();
  }
  syncOverlay();
  if (open) {
    renderAdvanced();
    dom.directAddress.focus();
  }
}

function renderAdvanced() {
  // This machine's own platform is known, so it is the starting point — but it
  // is still a choice, because someone may be reading this to set up two other
  // devices from the one that has the screen.
  if (guideMine === null) {
    guideMine = PLATFORMS.includes(state.self.os) ? state.self.os : 'windows';
  }
  if (guideTheirs === null) guideTheirs = guideMine === 'android' ? 'windows' : 'android';

  for (const [select, value] of [[dom.guideMine, guideMine], [dom.guideTheirs, guideTheirs]]) {
    // Platform names are the same in every language, so these are built once.
    if (select.options.length !== PLATFORMS.length) {
      select.replaceChildren(...PLATFORMS.map((os) => option(os, OS_LABEL[os])));
    }
    select.value = value;
  }
  renderGuide();
}

/**
 * Who can make a network when there isn't one.
 *
 * A phone always can, which settles it whenever there is one in the pair.
 * Between two computers it is a real choice: a cable is far faster but needs
 * two ports, and of the three desktop systems only Linux will raise an access
 * point without an internet connection to share — which is precisely the
 * situation this whole panel exists for.
 */
function routesFor(mine, theirs) {
  if (mine === 'android' || theirs === 'android') {
    return [{ id: 'hotspot', host: mine === 'android' ? 'mine' : 'theirs' }];
  }
  const routes = [{ id: 'cable' }];
  if (mine === 'linux' || theirs === 'linux') {
    routes.push({ id: 'linux', host: mine === 'linux' ? 'mine' : 'theirs' });
  }
  routes.push({ id: 'phone' });
  return routes;
}

function renderGuide() {
  const routes = routesFor(guideMine, guideTheirs);
  if (!routes.some((r) => r.id === guideRoute)) guideRoute = routes[0].id;
  const route = routes.find((r) => r.id === guideRoute);

  // A single route is not a choice; showing one chip to press would only ask
  // a question that has no second answer.
  dom.guideRoutes.replaceChildren(...(routes.length > 1 ? routes.map((each) => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'segmented__option';
    chip.setAttribute('role', 'radio');
    chip.textContent = t.t(`guide.route.${each.id}`);
    const on = each.id === guideRoute;
    chip.classList.toggle('is-on', on);
    chip.setAttribute('aria-checked', String(on));
    chip.addEventListener('click', () => { guideRoute = each.id; renderGuide(); });
    return chip;
  }) : []));

  dom.guideWhy.textContent = t.t(`guide.why.${route.id}`);
  dom.guideSteps.replaceChildren(...stepsFor(route).map(buildStep));
}

function stepsFor(route) {
  const host = route.host === 'theirs' ? guideTheirs : guideMine;
  const guest = route.host === 'theirs' ? guideMine : guideTheirs;
  const finish = [
    { text: t.t('guide.open') },
    { text: t.t('guide.fallback') },
  ];

  if (route.id === 'hotspot') {
    return [
      { os: host, text: t.t('guide.create.android') },
      { os: guest, text: t.t(`guide.join.${guest}`) },
      ...finish,
    ];
  }
  if (route.id === 'linux') {
    return [
      { os: host, text: t.t('guide.create.linux'), code: HOTSPOT_COMMAND },
      { os: guest, text: t.t(`guide.join.${guest}`) },
      ...finish,
    ];
  }
  if (route.id === 'cable') {
    return [{ text: t.t('guide.cable.plug') }, { text: t.t('guide.cable.wait') }, ...finish];
  }
  // Two computers that cannot raise a network, and a phone that can — without
  // needing FlyShare on it at all. Joining is one step when both are the same
  // platform, because it would otherwise be the same sentence printed twice.
  const joins = [...new Set([guideMine, guideTheirs])]
    .map((os) => ({ os, text: t.t(`guide.join.${os}`) }));
  return [{ text: t.t('guide.phone.enable') }, ...joins, ...finish];
}

function buildStep(step) {
  const item = document.createElement('li');
  item.className = 'guide__step';

  const body = document.createElement('span');
  if (step.os) {
    const chip = document.createElement('span');
    chip.className = 'guide__os';
    chip.textContent = OS_LABEL[step.os];
    body.append(chip);
  }
  body.append(step.text);
  if (step.code) {
    const code = document.createElement('code');
    code.textContent = step.code;
    body.append(document.createElement('br'), code);
  }

  item.append(body);
  return item;
}

// --- reaching one device by address ---------------------------------------

function directStatus(text, tone = '') {
  dom.directStatus.textContent = text;
  dom.directStatus.className = `direct__status ${tone}`;
}

/** Same rule as the server's: four octets, nothing that needs resolving. */
function isIpv4(value) {
  const parts = value.split('.');
  return parts.length === 4
    && parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255);
}

async function findByAddress() {
  const address = dom.directAddress.value.trim();
  if (!isIpv4(address)) return directStatus(t.t('direct.badAddress'), 'is-err');
  if ((state.self.addresses ?? []).includes(address)) {
    return directStatus(t.t('direct.ownAddress'), 'is-err');
  }

  dom.directFind.disabled = true;
  directStatus(t.t('direct.searching'));
  try {
    const { peer } = await api('/api/peers/find', { address });
    if (!peer) return directStatus(t.t('direct.notFound'), 'is-err');
    directStatus(t.t('direct.found', { name: peer.name }), 'is-ok');
    // The point of pressing this was to get to that device, and it is behind
    // the panel now. Long enough to read the name, then out of the way.
    setTimeout(() => { if (!dom.advanced.hidden) openAdvanced(false); }, 1200);
  } catch (err) {
    directStatus(err.message, 'is-err');
  } finally {
    dom.directFind.disabled = false;
  }
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
  dom.settingsToggle.setAttribute('aria-expanded', String(open));
  if (open) {
    if (!dom.detail.hidden) closeDetail();
    if (!dom.advanced.hidden) openAdvanced(false);
  }
  syncOverlay();
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
dom.clearHistory.addEventListener('click', () => api('/api/history/clear').catch(toast));

dom.settingsToggle.addEventListener('click', () => openSettings(dom.settings.hidden));
dom.settingsClose.addEventListener('click', () => openSettings(false));
dom.detailClose.addEventListener('click', closeDetail);
dom.advancedOpen.addEventListener('click', () => openAdvanced(true));
dom.advancedClose.addEventListener('click', () => openAdvanced(false));
dom.directFind.addEventListener('click', findByAddress);
dom.directAddress.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') findByAddress();
});
for (const [select, set] of [
  [dom.guideMine, (v) => { guideMine = v; }],
  [dom.guideTheirs, (v) => { guideTheirs = v; }],
]) {
  select.addEventListener('change', () => { set(select.value); renderGuide(); });
}

// Whichever panel is on top gets dismissed; the pairing dialog is not one of
// them, because walking away from a half-finished pairing by mistake is worse
// than having to answer it.
const dismissTop = () => {
  if (!dom.advanced.hidden) openAdvanced(false);
  else if (!dom.detail.hidden) closeDetail();
  else if (!dom.settings.hidden) openSettings(false);
};
dom.scrim.addEventListener('click', dismissTop);
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') dismissTop();
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
