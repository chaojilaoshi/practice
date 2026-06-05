// Configuration loading from environment variables / CLI args.
//
// Env vars (all optional):
//   PROXY_PORT       proxy listen port            (default 8788)
//   UI_PORT          web UI listen port           (default 8789)
//   LOG_DIR          directory for JSONL logs     (default <module>/logs)
//   REDACT_AUTH      "0" to keep raw auth headers (default redact)
//   ANTHROPIC_UPSTREAM  override Anthropic upstream (default https://api.anthropic.com)
//   OPENAI_UPSTREAM     override OpenAI upstream    (default https://api.openai.com)
//   MAX_BODY_BYTES   max stored body size, larger is truncated (default 2_000_000)
//   ANTHROPIC_COMPAT    "chat" to translate incoming /v1/messages into OpenAI
//                       /v1/chat/completions (for vendors that only support chat).
//                       Default off (transparent pass-through).
//   MODEL_MAP        model name remap used in compat mode. JSON object
//                       (e.g. {"claude-sonnet-4-6":"gpt-4o"}) OR comma list
//                       (e.g. "claude-sonnet-4-6=gpt-4o,claude-haiku-4-5=gpt-4o-mini").
//   MODEL_MAP_FILE   path to a JSON file with the same mapping (alternative to MODEL_MAP).
//
//   --- resilience (all opt-in; default behavior is unchanged when unset) ---
//   PROVIDERS        JSON array of failover providers, each
//                    {id, group:"anthropic"|"openai", baseUrl, apiKey?}. When a
//                    group's pool is non-empty we try its providers in order.
//   PROVIDERS_FILE   path to a JSON file with the same array.
//   BREAKER          "1"/"on" to enable the circuit breaker (auto-on when a
//                    provider pool is configured).
//   BREAKER_FAILURES failures before a provider opens          (default 5)
//   BREAKER_COOLDOWN_MS  open-state cooldown in ms             (default 30000)
//   BREAKER_HALFOPEN_MAX concurrent half-open probes           (default 1)
//   FAILOVER_STATUSES    comma list of HTTP statuses that trigger failover
//                        (default 429,500,502,503,504)
//   RECTIFY          "1"/"on" to enable Anthropic thinking rectification.
//   RECTIFY_SIGNATURE / RECTIFY_BUDGET  "0" to disable one sub-rule.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseProviders } from './providers.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function intEnv(name, fallback) {
  const v = process.env[name];
  if (v === undefined || v === '') return fallback;
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) ? n : fallback;
}

// Parse the model map from a string that is either JSON or a comma list of
// `from=to` pairs. Returns a plain object (empty when nothing is configured).
export function parseModelMap(raw) {
  if (!raw || typeof raw !== 'string') return {};
  const trimmed = raw.trim();
  if (trimmed === '') return {};
  if (trimmed.startsWith('{')) {
    try {
      const obj = JSON.parse(trimmed);
      return obj && typeof obj === 'object' ? obj : {};
    } catch {
      return {};
    }
  }
  const map = {};
  for (const pair of trimmed.split(',')) {
    const i = pair.indexOf('=');
    if (i === -1) continue;
    const k = pair.slice(0, i).trim();
    const v = pair.slice(i + 1).trim();
    if (k) map[k] = v;
  }
  return map;
}

function loadModelMap() {
  if (process.env.MODEL_MAP_FILE) {
    try {
      return parseModelMap(fs.readFileSync(process.env.MODEL_MAP_FILE, 'utf8'));
    } catch (err) {
      console.error('[config] failed to read MODEL_MAP_FILE:', err.message);
    }
  }
  return parseModelMap(process.env.MODEL_MAP);
}

function boolEnv(name) {
  const v = (process.env[name] || '').toLowerCase();
  return v === '1' || v === 'on' || v === 'true' || v === 'yes';
}

function loadProviders() {
  if (process.env.PROVIDERS_FILE) {
    try {
      return parseProviders(fs.readFileSync(process.env.PROVIDERS_FILE, 'utf8'));
    } catch (err) {
      console.error('[config] failed to read PROVIDERS_FILE:', err.message);
    }
  }
  return parseProviders(process.env.PROVIDERS);
}

function parseStatuses(raw, fallback) {
  if (!raw || typeof raw !== 'string') return new Set(fallback);
  const out = new Set();
  for (const tok of raw.split(',')) {
    const n = Number.parseInt(tok.trim(), 10);
    if (Number.isFinite(n)) out.add(n);
  }
  return out.size ? out : new Set(fallback);
}

export function loadConfig(overrides = {}) {
  return {
    proxyPort: intEnv('PROXY_PORT', 8788),
    uiPort: intEnv('UI_PORT', 8789),
    logDir: process.env.LOG_DIR || path.resolve(__dirname, '..', 'logs'),
    redactAuth: process.env.REDACT_AUTH !== '0',
    maxBodyBytes: intEnv('MAX_BODY_BYTES', 2_000_000),
    upstream: {
      anthropic: process.env.ANTHROPIC_UPSTREAM || 'https://api.anthropic.com',
      openai: process.env.OPENAI_UPSTREAM || 'https://api.openai.com',
    },
    compat: {
      // When 'chat', /v1/messages is translated to /v1/chat/completions and sent
      // to the OpenAI upstream. null = transparent pass-through (default).
      anthropicTo: (process.env.ANTHROPIC_COMPAT || '').toLowerCase() === 'chat' ? 'chat' : null,
      modelMap: loadModelMap(),
    },
    providers: {
      // Grouped failover pools. Empty pools => fall back to single upstream.
      pools: loadProviders(),
    },
    breaker: {
      // Auto-enable the breaker when a provider pool exists; otherwise opt-in.
      enabled: boolEnv('BREAKER'),
      failureThreshold: intEnv('BREAKER_FAILURES', 5),
      cooldownMs: intEnv('BREAKER_COOLDOWN_MS', 30000),
      halfOpenMax: intEnv('BREAKER_HALFOPEN_MAX', 1),
      failoverStatuses: parseStatuses(process.env.FAILOVER_STATUSES, [429, 500, 502, 503, 504]),
    },
    rectifier: {
      enabled: boolEnv('RECTIFY') || boolEnv('RECTIFIER'),
      signature: process.env.RECTIFY_SIGNATURE !== '0',
      budget: process.env.RECTIFY_BUDGET !== '0',
    },
    ...overrides,
  };
}
