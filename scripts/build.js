/**
 * Build a single-file FlyShare executable for the machine it runs on.
 *
 *   npm run build            ->  dist/flyshare-<platform>-<arch>[.exe]
 *
 * Three things have to happen, in this order:
 *   1. the ESM sources become one CommonJS file — Node's single-executable
 *      support does not accept an ES module entry point;
 *   2. that file plus the interface assets become a preparation blob;
 *   3. the blob is injected into a copy of the Node binary.
 *
 * Each platform then needs its own bit of aftercare, which is what the
 * signature handling below is about.
 */
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';

const execFileAsync = promisify(execFile);

/** execFile that reports what the tool actually said when it fails. */
async function run(command, args) {
  try {
    return await execFileAsync(command, args, { maxBuffer: 32 * 1024 * 1024 });
  } catch (err) {
    const detail = [err.stderr, err.stdout].filter(Boolean).join('\n').trim();
    const suffix = detail ? `:\n${detail}` : ` (${err.message})`;
    throw new Error(`${path.basename(command)} failed${suffix}`);
  }
}
const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const BUILD = path.join(ROOT, 'build');
const DIST = path.join(ROOT, 'dist');

const SENTINEL = 'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2';
const UI_FILES = ['index.html', 'app.css', 'app.js', 'i18n.js'];

const isWindows = process.platform === 'win32';
const isMac = process.platform === 'darwin';

const NAME = `flyshare-${process.platform === 'win32' ? 'win' : process.platform}-${os.arch()}`;
const OUTPUT = path.join(DIST, isWindows ? `${NAME}.exe` : NAME);

async function main() {
  await fs.rm(BUILD, { recursive: true, force: true });
  await fs.mkdir(BUILD, { recursive: true });
  await fs.mkdir(DIST, { recursive: true });

  const version = JSON.parse(await fs.readFile(path.join(ROOT, 'package.json'), 'utf8')).version;
  console.log(`building FlyShare ${version} for ${process.platform}/${os.arch()}\n`);

  await bundle();
  await writeSeaConfig();
  await makeBlob();
  await injectIntoNode();

  const { size } = await fs.stat(OUTPUT);
  console.log(`\n  ${path.relative(ROOT, OUTPUT)}  ${(size / 1024 / 1024).toFixed(1)} MB`);
}

/** Step 1 — one CommonJS file, because SEA cannot start from an ES module. */
async function bundle() {
  const result = await esbuild.build({
    entryPoints: [path.join(ROOT, 'src', 'main.js')],
    outfile: path.join(BUILD, 'flyshare.cjs'),
    bundle: true,
    platform: 'node',
    format: 'cjs',
    target: 'node20',
    // node:sea is resolved by the runtime, and bundling it would break the
    // dynamic import that detects whether we are packaged at all.
    external: ['node:sea'],
    logLevel: 'warning',
    legalComments: 'none',
    // import.meta only appears on the read-from-disk path, which a packaged
    // build never takes, so its absence in CommonJS is not a problem.
    logOverride: { 'empty-import-meta': 'silent' },
  });
  if (result.warnings.length) {
    for (const warning of result.warnings) console.log(`  note: ${warning.text}`);
  }
  const { size } = await fs.stat(path.join(BUILD, 'flyshare.cjs'));
  console.log(`  bundled  ${(size / 1024).toFixed(0)} KB of JavaScript`);
}

/** Step 2 — the interface travels inside the binary as named assets. */
async function writeSeaConfig() {
  const assets = {};
  for (const file of UI_FILES) assets[file] = path.join(ROOT, 'ui', file);

  await fs.writeFile(path.join(BUILD, 'sea-config.json'), JSON.stringify({
    main: path.join(BUILD, 'flyshare.cjs'),
    output: path.join(BUILD, 'flyshare.blob'),
    disableExperimentalSEAWarning: true,
    useSnapshot: false,
    useCodeCache: false,
    assets,
  }, null, 2));
  console.log(`  embedded ${UI_FILES.length} interface files`);
}

async function makeBlob() {
  await run(process.execPath, ['--experimental-sea-config', path.join(BUILD, 'sea-config.json')]);
  const { size } = await fs.stat(path.join(BUILD, 'flyshare.blob'));
  console.log(`  blob     ${(size / 1024).toFixed(0)} KB`);
}

async function injectIntoNode() {
  await fs.copyFile(process.execPath, OUTPUT);
  await fs.chmod(OUTPUT, 0o755).catch(() => {});

  // Both signed platforms reject a binary whose contents changed under an
  // existing signature, so the signature comes off before injection.
  if (isWindows) await stripWindowsSignature();
  if (isMac) await run('codesign', ['--remove-signature', OUTPUT]).catch(() => {});

  // Before the blob, not after: this rewrites the resource section and moves
  // everything past it, which is exactly what postject's sentinel must be the
  // last thing to touch.
  if (isWindows) await setWindowsIcon();

  const postject = path.join(ROOT, 'node_modules', 'postject', 'dist', 'cli.js');
  const args = [postject, OUTPUT, 'NODE_SEA_BLOB', path.join(BUILD, 'flyshare.blob'),
    '--sentinel-fuse', SENTINEL];
  if (isMac) args.push('--macho-segment-name', 'NODE_SEA');
  await run(process.execPath, args);
  console.log('  injected the blob into a copy of Node');

  // An ad-hoc signature is enough to make macOS run it locally; a real
  // Developer ID would still be needed to avoid the Gatekeeper warning.
  if (isMac) {
    await run('codesign', ['--sign', '-', '--force', OUTPUT]);
    console.log('  signed ad-hoc for macOS');
  }
}

/**
 * Give the executable its own face.
 *
 * Without this it inherits Node's, because that is literally what it is — a
 * copy of node.exe with a blob inside. The icon is the first thing anyone sees
 * of this app, in a downloads folder next to a dozen other files, and Node's
 * hexagon there says "a script someone renamed".
 *
 * The version fields come along for the ride: they are what Windows shows in
 * the file's Properties and in the SmartScreen prompt, where "unknown
 * publisher, FlyShare" reads better than "unknown publisher, Node.js".
 *
 * resedit rewrites the resource section in JavaScript rather than shelling out
 * to a bundled rcedit.exe, which is what the obvious package for this does —
 * and that package is marked as no longer supported. Being plain JS also means
 * this step is not the reason the Windows build has to happen on Windows.
 */
// Windows keeps resources per language; 1033 is en-US, and version strings
// filed under a language nothing asks for are version strings nothing shows.
const LANG_EN_US = 1033;

async function setWindowsIcon() {
  const icon = path.join(ROOT, 'assets', 'flyshare.ico');
  if (!(await fs.stat(icon).catch(() => null))) {
    console.log('  warning: assets/flyshare.ico is missing — run scripts/make-icon.js');
    return;
  }

  const version = JSON.parse(await fs.readFile(path.join(ROOT, 'package.json'), 'utf8')).version;
  const [major, minor, patch] = version.split('.').map(Number);

  const ResEdit = await import('resedit');
  const exe = ResEdit.NtExecutable.from(await fs.readFile(OUTPUT));
  const resources = ResEdit.NtExecutableResource.from(exe);

  const ico = ResEdit.Data.IconFile.from(await fs.readFile(icon));
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(
    resources.entries,
    1,
    LANG_EN_US,
    ico.icons.map((frame) => frame.data),
  );

  const info = ResEdit.Resource.VersionInfo.createEmpty();
  info.lang = LANG_EN_US;
  info.setFileVersion(major, minor, patch, 0);
  info.setProductVersion(major, minor, patch, 0);
  // The third argument is the one that matters and defaults to false: Windows
  // finds these strings by reading the translation table first, so a language
  // missing from it makes every field come back empty while the numeric
  // version — which lives elsewhere — still reads fine.
  info.setStringValues({ lang: LANG_EN_US, codepage: 1200 }, {
    ProductName: 'FlyShare',
    FileDescription: 'FlyShare — file transfer over the local network',
    CompanyName: 'FlyShare',
    LegalCopyright: 'MIT licensed',
    OriginalFilename: path.basename(OUTPUT),
    FileVersion: version,
    ProductVersion: version,
  }, true);
  info.outputToResourceEntries(resources.entries);

  resources.outputResource(exe);
  await fs.writeFile(OUTPUT, Buffer.from(exe.generate()));
  console.log('  set the icon and version fields');
}

async function stripWindowsSignature() {
  const signtool = await findSigntool();
  if (!signtool) {
    console.log('  warning: signtool not found — the executable may refuse to start.');
    console.log('           Install the Windows SDK, or build on a runner that has it.');
    return;
  }
  await run(signtool, ['remove', '/s', OUTPUT]);
  console.log('  removed the Authenticode signature');
}

async function findSigntool() {
  const roots = [
    'C:/Program Files (x86)/Windows Kits/10/bin',
    'C:/Program Files/Windows Kits/10/bin',
  ];
  const arch = os.arch() === 'arm64' ? 'arm64' : 'x64';
  for (const root of roots) {
    if (!existsSync(root)) continue;
    const versions = (await fs.readdir(root)).sort().reverse();
    for (const version of versions) {
      const candidate = path.join(root, version, arch, 'signtool.exe');
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

main().catch((err) => {
  console.error(`\nbuild failed: ${err.message}`);
  process.exit(1);
});
