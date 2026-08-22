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
