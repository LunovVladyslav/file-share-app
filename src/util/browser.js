import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * Open the interface in a window of its own.
 *
 * The app has always handed its URL to the default browser, which put it in a
 * tab among forty others: no icon, an address bar showing a loopback address
 * and a session token, and nothing to tell it apart from a website. Closing
 * the browser looked like closing the app.
 *
 * Every Chromium-based browser can open a page as a standalone window with
 * `--app=`, and that window gets its own entry on the taskbar and in the
 * switcher. It is not a native window, and it is nothing like what a real
 * shell would give — but it is one flag rather than a second runtime, and it
 * removes the part people actually stumble over.
 *
 * Nothing here is required: if no such browser is installed the caller falls
 * back to the default one, which is exactly what happened before.
 */

/**
 * Deliberately no --user-data-dir. Pointing one at a private directory would
 * mean building a second browser profile on first launch — slow, and hundreds
 * of megabytes — to isolate state this page does not keep. Attaching to the
 * browser already running is both faster and less surprising.
 *
 * Window size is left alone too. Chromium remembers where an app window was
 * put, per URL, and overriding that on every launch would undo the person's
 * own arrangement each time they open it.
 */
export function appModeArgs(url) {
  return [`--app=${url}`];
}

const WINDOWS_BROWSERS = [
  ['Google\\Chrome\\Application\\chrome.exe'],
  ['Microsoft\\Edge\\Application\\msedge.exe'],
  ['BraveSoftware\\Brave-Browser\\Application\\brave.exe'],
  ['Vivaldi\\Application\\vivaldi.exe'],
  ['Chromium\\Application\\chrome.exe'],
];

const MAC_BROWSERS = [
  'Google Chrome.app/Contents/MacOS/Google Chrome',
  'Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  'Brave Browser.app/Contents/MacOS/Brave Browser',
  'Vivaldi.app/Contents/MacOS/Vivaldi',
  'Chromium.app/Contents/MacOS/Chromium',
];

const LINUX_BROWSERS = [
  'google-chrome-stable',
  'google-chrome',
  'chromium-browser',
  'chromium',
  'microsoft-edge-stable',
  'microsoft-edge',
  'brave-browser',
  'vivaldi-stable',
];

/** Every place a Chromium browser might be, in the order we would prefer it. */
function candidates() {
  if (process.platform === 'win32') {
    // Per-user installs are as common as machine-wide ones and land somewhere
    // entirely different, so all three roots are searched.
    const roots = [
      process.env['ProgramFiles'],
      process.env['ProgramFiles(x86)'],
      process.env['LOCALAPPDATA'],
    ].filter(Boolean);
    return WINDOWS_BROWSERS.flatMap(([suffix]) => roots.map((root) => path.join(root, suffix)));
  }

  if (process.platform === 'darwin') {
    const roots = ['/Applications', path.join(os.homedir(), 'Applications')];
    return MAC_BROWSERS.flatMap((suffix) => roots.map((root) => path.join(root, suffix)));
  }

  // On Linux these are names on PATH rather than fixed locations, because
  // distributions disagree about where a browser lives and agree about what
  // it is called.
  const dirs = (process.env.PATH ?? '').split(path.delimiter).filter(Boolean);
  return LINUX_BROWSERS.flatMap((name) => dirs.map((dir) => path.join(dir, name)));
}

/** The first Chromium-based browser this machine has, or null. */
export function findAppBrowser() {
  for (const candidate of candidates()) {
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      return candidate;
    } catch {
      // Not there, or not runnable — try the next.
    }
  }
  return null;
}

/** How the default browser is asked to open a URL, per platform. */
export function defaultBrowserCommand(url) {
  if (process.platform === 'win32') {
    // The empty string is the window title `start` would otherwise take the
    // URL for.
    return { command: 'cmd.exe', args: ['/c', 'start', '', url] };
  }
  if (process.platform === 'darwin') return { command: 'open', args: [url] };
  return { command: 'xdg-open', args: [url] };
}
