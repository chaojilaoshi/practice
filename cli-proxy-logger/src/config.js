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

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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
    ...overrides,
  };
}
