// GUI-driven settings: a single config.json is the source of truth when the app
// is launched from the packaged .exe (or `npm start`). The file mirrors the form
// shown in the web UI, so non-technical users never touch env vars or JSON by
// hand. When no file exists we seed initial settings from the environment so the
// existing env-var workflow keeps working unchanged.
//
// Two shapes are involved:
//   * "settings"  — the GUI-shaped, editable object stored in config.json.
//   * "config"    — the runtime object the proxy/UI consume (built by
//                   buildConfigFromSettings, identical in shape to loadConfig()).

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isSea } from 'node:sea';
import { parseFilters } from './filters.js';
import { parseProviders } from './providers.js';
import { createOutbound } from './outbound.js';
import { DEFAULT_TOOL_NAME_MAP } from './transform.js';
import { parseModelMap } from './config.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Where config.json lives. Priority:
//   1. CONFIG_FILE env override (explicit path)
//   2. next to the executable when running as a packaged .exe (process.pkg)
//   3. the module root in dev (`<repo>/cli-proxy-logger/config.json`)
function isPackaged() {
  try { if (isSea()) return true; } catch { /* not SEA */ }
  return !!process.pkg;
}

// The directory that the exe lives in when packaged, else the module root. Used
// to anchor config.json and the default logs/ dir beside the running program.
function baseDir() {
  return isPackaged() ? path.dirname(process.execPath) : path.resolve(__dirname, '..');
}

export function configFilePath() {
  if (process.env.CONFIG_FILE) return path.resolve(process.env.CONFIG_FILE);
  return path.join(baseDir(), 'config.json');
}

// The default editable settings (everything off = transparent pass-through).
export function defaultSettings() {
  return {
    proxyPort: 8788,
    uiPort: 8789,
    logDir: '',
    upstream: { anthropic: 'https://api.anthropic.com', openai: 'https://api.openai.com' },
    compat: { enabled: false, modelMap: {} },
    toolName: { enabled: false, request: true, response: true, repairInput: true, map: {} },
    filters: [],
    outbound: { url: '' },
    providers: { anthropic: [], openai: [] },
    breaker: { enabled: false, failureThreshold: 5, cooldownMs: 30000, halfOpenMax: 1, failoverStatuses: [429, 500, 502, 503, 504] },
    rectifier: { enabled: false, signature: true, budget: true },
  };
}

function bool(v) { return v === true || v === 1 || v === '1' || v === 'on' || v === 'true' || v === 'yes'; }

// Seed settings from environment variables (used only when no config.json yet),
// so an operator who already runs with env vars sees them pre-filled in the GUI.
function settingsFromEnv() {
  const s = defaultSettings();
  const e = process.env;
  if (e.PROXY_PORT) s.proxyPort = Number.parseInt(e.PROXY_PORT, 10) || s.proxyPort;
  if (e.UI_PORT) s.uiPort = Number.parseInt(e.UI_PORT, 10) || s.uiPort;
  if (e.LOG_DIR) s.logDir = e.LOG_DIR;
  if (e.ANTHROPIC_UPSTREAM) s.upstream.anthropic = e.ANTHROPIC_UPSTREAM;
  if (e.OPENAI_UPSTREAM) s.upstream.openai = e.OPENAI_UPSTREAM;
  s.compat.enabled = (e.ANTHROPIC_COMPAT || '').toLowerCase() === 'chat';
  s.compat.modelMap = parseModelMap(e.MODEL_MAP);
  s.toolName.enabled = bool(e.TOOL_NAME_CASE);
  s.toolName.request = e.TOOL_NAME_REQUEST !== '0';
  s.toolName.response = e.TOOL_NAME_RESPONSE !== '0';
  s.toolName.repairInput = e.TOOL_NAME_REPAIR_INPUT !== '0';
  if (e.TOOL_NAME_MAP && e.TOOL_NAME_MAP.trim().startsWith('{')) {
    try { s.toolName.map = JSON.parse(e.TOOL_NAME_MAP); } catch { /* ignore */ }
  }
  if (e.FILTERS) { try { s.filters = JSON.parse(e.FILTERS); } catch { /* ignore */ } }
  s.outbound.url = e.UPSTREAM_PROXY || e.HTTPS_PROXY || e.HTTP_PROXY || '';
  s.rectifier.enabled = bool(e.RECTIFY) || bool(e.RECTIFIER);
  return s;
}

// Deep-ish merge of a partial settings object over the defaults so older/partial
// files still load with sane values for any newly-added fields.
function mergeSettings(base, override) {
  const out = { ...base };
  for (const k of Object.keys(override || {})) {
    const v = override[k];
    if (v && typeof v === 'object' && !Array.isArray(v) && base[k] && typeof base[k] === 'object' && !Array.isArray(base[k])) {
      out[k] = mergeSettings(base[k], v);
    } else if (v !== undefined) {
      out[k] = v;
    }
  }
  return out;
}

// Read the editable settings: config.json if present, else env-seeded defaults.
export function readSettings() {
  const file = configFilePath();
  try {
    if (fs.existsSync(file)) {
      const raw = fs.readFileSync(file, 'utf8');
      const parsed = JSON.parse(raw);
      return { settings: mergeSettings(defaultSettings(), parsed), source: 'file', file };
    }
  } catch (err) {
    console.error('[settings] failed to read config.json:', err.message);
  }
  return { settings: settingsFromEnv(), source: 'env', file };
}

// Persist settings to config.json (pretty-printed for human inspection).
export function writeSettings(settings) {
  const file = configFilePath();
  const clean = mergeSettings(defaultSettings(), settings || {});
  fs.writeFileSync(file, JSON.stringify(clean, null, 2) + '\n', 'utf8');
  return { file, settings: clean };
}

// Flatten the two provider lists into the single tagged array parseProviders wants.
function flattenProviders(providers) {
  const out = [];
  for (const group of ['anthropic', 'openai']) {
    for (const p of (providers?.[group] || [])) {
      if (!p || !p.baseUrl) continue;
      out.push({ id: p.id || undefined, group, baseUrl: p.baseUrl, apiKey: p.apiKey || null });
    }
  }
  return out;
}

// Build the runtime config (same shape as loadConfig()) from editable settings.
export function buildConfigFromSettings(settings) {
  const s = mergeSettings(defaultSettings(), settings || {});
  const statuses = Array.isArray(s.breaker.failoverStatuses) && s.breaker.failoverStatuses.length
    ? new Set(s.breaker.failoverStatuses.map((n) => Number.parseInt(n, 10)).filter(Number.isFinite))
    : new Set([429, 500, 502, 503, 504]);
  return {
    proxyPort: Number.parseInt(s.proxyPort, 10) || 8788,
    uiPort: Number.parseInt(s.uiPort, 10) || 8789,
    logDir: s.logDir ? path.resolve(s.logDir) : path.join(baseDir(), 'logs'),
    redactAuth: true,
    maxBodyBytes: 2_000_000,
    upstream: {
      anthropic: s.upstream.anthropic || 'https://api.anthropic.com',
      openai: s.upstream.openai || 'https://api.openai.com',
    },
    compat: {
      anthropicTo: s.compat.enabled ? 'chat' : null,
      modelMap: s.compat.modelMap && typeof s.compat.modelMap === 'object' ? s.compat.modelMap : {},
    },
    providers: { pools: parseProviders(flattenProviders(s.providers)) },
    breaker: {
      enabled: !!s.breaker.enabled,
      failureThreshold: Number.parseInt(s.breaker.failureThreshold, 10) || 5,
      cooldownMs: Number.parseInt(s.breaker.cooldownMs, 10) || 30000,
      halfOpenMax: Number.parseInt(s.breaker.halfOpenMax, 10) || 1,
      failoverStatuses: statuses,
    },
    rectifier: {
      enabled: !!s.rectifier.enabled,
      signature: s.rectifier.signature !== false,
      budget: s.rectifier.budget !== false,
    },
    transform: {
      toolName: {
        enabled: !!s.toolName.enabled,
        request: s.toolName.request !== false,
        response: s.toolName.response !== false,
        repairInput: s.toolName.repairInput !== false,
        map: { ...DEFAULT_TOOL_NAME_MAP, ...(s.toolName.map && typeof s.toolName.map === 'object' ? s.toolName.map : {}) },
      },
    },
    filters: parseFilters(Array.isArray(s.filters) ? s.filters : []),
    outbound: createOutbound(s.outbound && s.outbound.url ? s.outbound.url : ''),
  };
}
