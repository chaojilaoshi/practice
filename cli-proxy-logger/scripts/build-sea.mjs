#!/usr/bin/env node
// Build a single-file Windows .exe using Node's built-in Single Executable
// Application (SEA) feature. No network access and no compiler required — it
// reuses the local node.exe as the runtime base.
//
// Pipeline:
//   1. esbuild bundles the ESM app (src/index.js) into one self-contained CJS
//      file (build/bundle.cjs). All deps are node: builtins, so nothing external.
//   2. `node --experimental-sea-config` turns that + the index.html asset into a
//      blob (build/sea-prep.blob).
//   3. Copy the current node.exe to dist/cli-proxy-logger.exe.
//   4. postject injects the blob into the copied exe.
//
// Usage: node scripts/build-sea.mjs   (or: npm run build:exe)

import { build } from 'esbuild';
import { inject } from 'postject';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const buildDir = path.join(root, 'build');
const distDir = path.join(root, 'dist');
const bundle = path.join(buildDir, 'bundle.cjs');
const blob = path.join(buildDir, 'sea-prep.blob');
const isWin = process.platform === 'win32';
const exeName = isWin ? 'cli-proxy-logger.exe' : 'cli-proxy-logger';
const outExe = path.join(distDir, exeName);
const FUSE = 'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2';

fs.mkdirSync(buildDir, { recursive: true });
fs.mkdirSync(distDir, { recursive: true });

console.log('[1/4] bundling app with esbuild ->', path.relative(root, bundle));
await build({
  entryPoints: [path.join(root, 'src', 'index.js')],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node20',
  outfile: bundle,
  // node: builtins are external on platform:node automatically.
  // In a CJS bundle import.meta.url is unavailable; shim it from __filename so
  // the few fileURLToPath(import.meta.url) call sites resolve to the exe path.
  banner: { js: '/* cli-proxy-logger SEA bundle */\nconst importMetaUrl = require("node:url").pathToFileURL(__filename).href;' },
  define: { 'import.meta.url': 'importMetaUrl' },
});

console.log('[2/4] generating SEA blob ->', path.relative(root, blob));
execFileSync(process.execPath, ['--experimental-sea-config', path.join(root, 'sea-config.json')], {
  cwd: root,
  stdio: 'inherit',
});

console.log('[3/4] copying node runtime ->', path.relative(root, outExe));
fs.copyFileSync(process.execPath, outExe);

// On Windows the copied node.exe carries Microsoft's Authenticode signature;
// postject can inject regardless, but removing it avoids a corrupt-signature
// warning. signtool is optional and skipped when unavailable.
if (isWin) {
  try {
    execFileSync('signtool', ['remove', '/s', outExe], { stdio: 'ignore' });
    console.log('      (removed existing Authenticode signature)');
  } catch { /* signtool not present; harmless */ }
}

console.log('[4/4] injecting blob with postject');
const blobData = fs.readFileSync(blob);
await inject(outExe, 'NODE_SEA_BLOB', blobData, {
  sentinelFuse: FUSE,
  ...(process.platform === 'darwin' ? { machoSegmentName: 'NODE_SEA' } : {}),
});

const sizeMb = (fs.statSync(outExe).size / 1024 / 1024).toFixed(1);
console.log(`\nDone -> ${path.relative(root, outExe)} (${sizeMb} MB)`);
console.log('Double-click it (or run it) to start the proxy and open the config UI.');
