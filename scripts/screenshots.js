/**
 * Capture the documentation screenshots straight from a running FlyShare.
 *
 * Drives headless Chrome over the DevTools Protocol using Node's built-in
 * WebSocket, so the pictures in the README are always the real interface at
 * whatever the current design is — never a mock-up that quietly goes stale.
 *
 * Usage:
 *   node src/main.js --no-open            # in another terminal
 *   node scripts/screenshots.js http://127.0.0.1:45890/
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, 'docs', 'screenshots');
const DEBUG_PORT = 9333;

const TARGET = process.argv[2];
const ONLY = new Set(process.argv.slice(3));
if (!TARGET) {
  console.error('usage: node scripts/screenshots.js <flyshare-ui-url> [shot-name...]');
  process.exit(1);
}

const CHROME_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
];

async function findChrome() {
  for (const candidate of CHROME_CANDIDATES) {
    try {
      await fs.access(candidate);
      return candidate;
    } catch { /* try the next one */ }
  }
  throw new Error('no Chrome or Edge found — set one of the paths in CHROME_CANDIDATES');
}

/** Minimal DevTools Protocol client: send a command, await its reply. */
class Devtools {
  #id = 0;
  #pending = new Map();

  static async attach(wsUrl) {
    const socket = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', () => reject(new Error('cannot reach the browser')), { once: true });
    });
    return new Devtools(socket);
  }

  constructor(socket) {
    this.socket = socket;
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      const pending = this.#pending.get(message.id);
      if (!pending) return;
      this.#pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
    });
  }

  send(method, params = {}) {
    const id = ++this.#id;
    this.socket.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.#pending.set(id, { resolve, reject }));
  }

  close() {
    this.socket.close();
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Each shot names the state it wants; `setup` runs inside the page and is
 * responsible for leaving the interface in that state.
 */
const SHOTS = [
  {
    file: 'main-dark.png',
    size: [1180, 860],
    setup: `settings({ language: 'en', theme: 'dark', transferView: 'list' })`,
  },
  {
    // The Ukrainian README needs a Ukrainian window.
    file: 'main-dark-uk.png',
    size: [1180, 860],
    setup: `settings({ language: 'uk', theme: 'dark', transferView: 'list' })`,
  },
  {
    file: 'main-light.png',
    size: [1180, 860],
    setup: `settings({ language: 'en', theme: 'light', transferView: 'list' })`,
  },
  {
    file: 'grid-view.png',
    size: [1180, 860],
    setup: `settings({ language: 'en', theme: 'dark', transferView: 'grid' })`,
  },
  {
    file: 'settings.png',
    size: [1180, 900],
    setup: `settings({ language: 'en', theme: 'light', transferView: 'list' });
            await pause(400);
            document.getElementById('settings-toggle').click();`,
  },
  {
    // Wants a real transfer on screen — ideally one still running, so the
    // file list has something in every state. Run it while sending a folder.
    file: 'transfer-detail.png',
    size: [1180, 900],
    setup: `settings({ language: 'en', theme: 'dark', transferView: 'list' });
            await pause(500);
            document.querySelectorAll('.transfer__title')[0]?.click();`,
  },
  {
    file: 'advanced.png',
    size: [1180, 940],
    setup: `settings({ language: 'en', theme: 'dark' });
            await pause(500);
            document.getElementById('advanced-open').click();
            await pause(200);
            // Two computers, which is the case with something to choose between.
            const mine = document.getElementById('guide-mine');
            mine.value = 'windows'; mine.dispatchEvent(new Event('change'));
            const theirs = document.getElementById('guide-theirs');
            theirs.value = 'macos'; theirs.dispatchEvent(new Event('change'));`,
  },
  {
    // Captured against a real handshake left waiting on the other machine —
    // the code in this picture was actually derived, not typed in.
    file: 'pairing.png',
    size: [1180, 760],
    setup: `settings({ language: 'en', theme: 'dark' }); await pause(600);`,
  },
];

/** Helpers injected into the page before each shot. */
const PAGE_HELPERS = `
  window.__token = document.querySelector('meta[name="flyshare-token"]').content;
  window.pause = (ms) => new Promise(r => setTimeout(r, ms));
  window.settings = (body) => fetch('/api/settings', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-flyshare-token': window.__token },
    body: JSON.stringify(body),
  });
`;

async function main() {
  await fs.mkdir(OUT_DIR, { recursive: true });
  const chrome = await findChrome();
  const profile = path.join(ROOT, 'node_modules', '.screenshot-profile');

  const browser = spawn(chrome, [
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--no-first-run',
    '--no-default-browser-check',
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${profile}`,
    'about:blank',
  ], { stdio: 'ignore', detached: false });

  try {
    const wsUrl = await waitForPage();
    const dt = await Devtools.attach(wsUrl);
    await dt.send('Page.enable');
    await dt.send('Runtime.enable');

    for (const shot of SHOTS) {
      if (ONLY.size && !ONLY.has(path.basename(shot.file, '.png'))) continue;
      const [width, height] = shot.size;
      // deviceScaleFactor 2 gives retina-sharp images for the README and the
      // landing page without rendering the layout at a silly width.
      await dt.send('Emulation.setDeviceMetricsOverride', {
        width, height, deviceScaleFactor: 2, mobile: false,
      });

      await dt.send('Page.navigate', { url: TARGET });
      await sleep(1400); // let discovery, SSE state and the first render settle

      await dt.send('Runtime.evaluate', { expression: PAGE_HELPERS });
      await dt.send('Runtime.evaluate', {
        expression: `(async () => { ${shot.setup} })()`,
        awaitPromise: true,
      });
      await sleep(900);

      const { data } = await dt.send('Page.captureScreenshot', { format: 'png' });
      const file = path.join(OUT_DIR, shot.file);
      await fs.writeFile(file, Buffer.from(data, 'base64'));
      const { size } = await fs.stat(file);
      console.log(`  ${shot.file.padEnd(18)} ${width}x${height}@2x  ${(size / 1024).toFixed(0)} KB`);
    }

    dt.close();
  } finally {
    browser.kill();
    await fs.rm(profile, { recursive: true, force: true }).catch(() => {});
  }
  console.log(`\nwrote ${SHOTS.length} screenshots to docs/screenshots/`);
}

/**
 * Wait for the debugging port, then attach to a *page* target — the
 * browser-level endpoint does not accept Page.* commands.
 */
async function waitForPage() {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const res = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`);
      const targets = await res.json();
      const page = targets.find((target) => target.type === 'page' && target.webSocketDebuggerUrl);
      if (page) return page.webSocketDebuggerUrl;
    } catch { /* not up yet */ }
    await sleep(200);
  }
  throw new Error('the browser never opened a page to drive');
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
