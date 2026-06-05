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

import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function intEnv(name, fallback) {
  const v = process.env[name];
  if (v === undefined || v === '') return fallback;
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) ? n : fallback;
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
    ...overrides,
  };
}
