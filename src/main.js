#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { loadConfig, platformLabel, TRANSFER_PORT, UI_PORT, DISCOVERY_PORT } from './core/config.js';
import { Discovery, localInterfaces } from './core/discovery.js';
import { loadIdentity } from './core/identity.js';
import { pairedPeers } from './core/trust.js';
import { TransferServer } from './core/server.js';
import { Sender } from './core/client.js';
import { UiServer } from './ui-server.js';
import { findAppBrowser, appModeArgs, defaultBrowserCommand } from './util/browser.js';

const args = new Set(process.argv.slice(2));
const openBrowser = !args.has('--no-open');
// --no-app for anyone who would rather have it in the browser they already
// have open, with their extensions and their history.
const appMode = !args.has('--no-app');

async function main() {
  const config = loadConfig();
  // Mint the identity key now rather than during the first pairing, so a
  // failure to write it shows up at startup where it can be understood.
  loadIdentity();

  const server = new TransferServer();
  const discovery = new Discovery();
  const sender = new Sender();

  server.on('error', (err) => fatal('transfer port', err));
  discovery.on('error', (err) => console.error(`discovery: ${err.message}`));

  await server.start(TRANSFER_PORT).catch((err) => fatal('transfer port', err));
  await discovery.start().catch((err) => fatal('discovery port', err));

  const ui = new UiServer({ discovery, server, sender });
  await ui.start(UI_PORT).catch((err) => fatal('UI port', err));

  const addresses = localInterfaces().map((i) => i.address).join(', ') || 'no network interface found';
  const paired = pairedPeers();
  console.log(`
  FlyShare — fast, encrypted file transfer over the local network

  this device : ${config.deviceName}  (${platformLabel()})
  addresses   : ${addresses}
  saving to   : ${config.downloadDir}
  paired with : ${paired.length ? paired.map((p) => p.name).join(', ') : 'nothing yet — pair a device in the UI'}

  open the UI : ${ui.url}
`);

  if (openBrowser) openUrl(ui.url);

  const shutdown = () => {
    console.log('\nshutting down...');
    discovery.stop();
    server.stop();
    ui.stop();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

function fatal(what, err) {
  const busy = err.code === 'EADDRINUSE';
  console.error(`\n  Cannot start: ${what} is unavailable — ${err.message}`);
  if (busy) {
    console.error(`  Another FlyShare instance is probably already running.`);
    console.error(`  Ports used: ${DISCOVERY_PORT} (discovery), ${TRANSFER_PORT} (transfer), ${UI_PORT} (UI).`);
  }
  process.exit(1);
}

function openUrl(url) {
  // A window of its own where that is possible, a browser tab where it is not.
  const browser = appMode ? findAppBrowser() : null;
  const { command, args: cmdArgs } = browser
    ? { command: browser, args: appModeArgs(url) }
    : defaultBrowserCommand(url);

  try {
    const child = spawn(command, cmdArgs, {
      detached: true, stdio: 'ignore', windowsHide: true,
    });
    // A browser that fails to launch must not take this process with it: the
    // URL is on screen, and opening it by hand still works.
    child.once('error', () => {});
    child.unref();
  } catch {
    // Headless, or no browser at all — the URL is printed above either way.
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
