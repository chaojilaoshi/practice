#!/usr/bin/env node
// Entry point: load settings/config, start proxy + UI, and (when launched as a
// packaged .exe) open the browser straight to the visual config page.

import fs from 'node:fs';
import { spawn } from 'node:child_process';
import { isSea } from 'node:sea';
import { loadConfig } from './config.js';
import { readSettings, buildConfigFromSettings, writeSettings } from './settings.js';
import { Recorder } from './recorder.js';
import { startProxy } from './proxy.js';
import { startUi } from './ui-server.js';

// When a config.json exists it is the single source of truth (the GUI workflow).
// A packaged .exe also always uses the settings path (seeded from env when no
// file yet) so logs/config anchor beside the executable. Only an unpackaged run
// with no config.json falls back to full env-var behavior (loadConfig), keeping
// existing command-line / *_FILE setups working byte-for-byte.
let packaged = false;
try { packaged = isSea(); } catch { /* not SEA */ }
const { settings, source, file } = readSettings();
const config = (source === 'file' || packaged) ? buildConfigFromSettings(settings) : loadConfig();

const recorder = new Recorder(config);
const proxyServer = startProxy(config, recorder);

// Apply edited settings live: persist config.json, rebuild the runtime config,
// then mutate the shared `config` object in place so the proxy/recorder closures
// (which read config per request) pick up changes without a restart. Port changes
// re-bind the proxy listener; UI-port changes require an app restart (the page
// you're on is served by that listener) and are reported back to the caller.
function applySettings(newSettings) {
  const { settings: saved } = writeSettings(newSettings);
  const built = buildConfigFromSettings(saved);
  const proxyPortChanged = built.proxyPort !== config.proxyPort;
  const uiPortChanged = built.uiPort !== config.uiPort;

  for (const k of Object.keys(built)) config[k] = built[k];
  try { fs.mkdirSync(config.logDir, { recursive: true }); } catch { /* ignore */ }

  // Breakers share their options object by reference, so mutating it in place
  // updates every existing breaker without dropping accumulated state.
  if (proxyServer.breakers?.opts) {
    proxyServer.breakers.opts.failureThreshold = config.breaker.failureThreshold;
    proxyServer.breakers.opts.cooldownMs = config.breaker.cooldownMs;
    proxyServer.breakers.opts.halfOpenMax = config.breaker.halfOpenMax;
  }

  if (proxyPortChanged) {
    try {
      proxyServer.close();
      proxyServer.listen(config.proxyPort, '127.0.0.1');
    } catch (err) {
      console.error('[apply] failed to rebind proxy port:', err.message);
    }
  }
  return { saved, proxyPortChanged, uiPortChanged };
}

const uiServer = startUi(config, recorder, { applySettings, configFile: file });

const proxy = `http://127.0.0.1:${config.proxyPort}`;
const ui = `http://127.0.0.1:${config.uiPort}`;

// Open the default browser to the config UI. Only auto-launch when packaged as an
// .exe (the non-technical, double-click flow) or when CLI_PROXY_OPEN=1 is set, so
// dev/test runs and headless servers are never disturbed.
function openBrowser(url) {
  try {
    if (process.platform === 'win32') spawn('cmd', ['/c', 'start', '', url], { detached: true, stdio: 'ignore' }).unref();
    else if (process.platform === 'darwin') spawn('open', [url], { detached: true, stdio: 'ignore' }).unref();
    else spawn('xdg-open', [url], { detached: true, stdio: 'ignore' }).unref();
  } catch { /* best-effort only */ }
}

console.log(`
cli-proxy-logger running.
  config UI -> ${ui}
  proxy     -> ${proxy}
  settings  -> ${file} (${source})
  logs      -> ${config.logDir}

Point Claude Code at the proxy:
  export ANTHROPIC_BASE_URL=${proxy}

Point Codex at the proxy (~/.codex/config.toml):
  openai_base_url = "${proxy}/v1"
`);

const wantOpen = process.env.CLI_PROXY_OPEN === '1'
  || ((packaged || process.pkg) && process.env.CLI_PROXY_OPEN !== '0');
if (wantOpen) {
  setTimeout(() => openBrowser(ui), 600);
}

// Keep references reachable (and silence unused-var linters).
export { proxyServer, uiServer };
