// Lightweight web UI + JSON API for browsing captured exchanges.
//   GET    /                   -> static index.html
//   GET    /api/exchanges      -> recent exchange summaries
//   GET    /api/exchanges/:id  -> full exchange detail
//   DELETE /api/exchanges      -> clear the in-memory list (one-click "清空")
//   GET    /api/config         -> read-only snapshot of active features (no secrets)
//   GET    /api/settings       -> editable settings for the visual config form
//   POST   /api/settings       -> persist + live-apply edited settings

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isSea, getAsset } from 'node:sea';
import { summarizeFilters } from './filters.js';
import { readSettings } from './settings.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.resolve(__dirname, '..', 'public');

// When packaged as a Single Executable Application there is no public/ folder on
// disk — index.html is bundled as a SEA asset. Serve that instead.
function seaIndexHtml() {
  try {
    if (isSea()) return getAsset('index.html', 'utf8');
  } catch { /* not a SEA build */ }
  return null;
}

// Read-only snapshot of the active opt-in features, for the UI to display.
// Never includes secrets (provider apiKeys / credentials are omitted).
function configSummary(config) {
  const tn = config.transform?.toolName || {};
  const pools = config.providers?.pools || {};
  return {
    compat: { anthropicTo: config.compat?.anthropicTo || null },
    providers: {
      anthropic: (pools.anthropic || []).map((p) => ({ id: p.id, baseUrl: p.baseUrl, hasKey: !!p.apiKey })),
      openai: (pools.openai || []).map((p) => ({ id: p.id, baseUrl: p.baseUrl, hasKey: !!p.apiKey })),
    },
    breaker: {
      enabled: !!config.breaker?.enabled,
      failureThreshold: config.breaker?.failureThreshold,
      cooldownMs: config.breaker?.cooldownMs,
    },
    rectifier: {
      enabled: !!config.rectifier?.enabled,
      signature: !!config.rectifier?.signature,
      budget: !!config.rectifier?.budget,
    },
    toolName: {
      enabled: !!tn.enabled,
      request: !!tn.request,
      response: !!tn.response,
      repairInput: !!tn.repairInput,
      mapSize: tn.map ? Object.keys(tn.map).length : 0,
    },
    filters: summarizeFilters(config.filters),
    outbound: config.outbound ? { enabled: true, describe: config.outbound.describe } : { enabled: false },
  };
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(body);
}

// Read the request body (small JSON config payloads) with a hard cap.
function readBody(req, limit = 1_000_000) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let len = 0;
    req.on('data', (c) => {
      len += c.length;
      if (len > limit) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

export function startUi(config, recorder, opts = {}) {
  const { applySettings, configFile } = opts;
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;

    if (p === '/api/config') {
      return sendJson(res, 200, configSummary(config));
    }

    // Editable settings for the visual config form.
    if (p === '/api/settings') {
      if (req.method === 'GET') {
        const { settings, source, file } = readSettings();
        return sendJson(res, 200, { settings, source, file });
      }
      if (req.method === 'POST') {
        if (typeof applySettings !== 'function') {
          return sendJson(res, 501, { error: 'settings editing not enabled' });
        }
        readBody(req)
          .then((raw) => {
            let parsed;
            try { parsed = JSON.parse(raw || '{}'); } catch { return sendJson(res, 400, { error: 'invalid JSON' }); }
            const result = applySettings(parsed);
            return sendJson(res, 200, {
              ok: true,
              file: configFile,
              proxyPortChanged: !!result.proxyPortChanged,
              uiPortChanged: !!result.uiPortChanged,
            });
          })
          .catch((err) => sendJson(res, 400, { error: err.message }));
        return undefined;
      }
      return sendJson(res, 405, { error: 'method not allowed' });
    }
    if (p === '/api/exchanges') {
      // DELETE empties the in-memory list (the UI's "清空" button). Disk logs stay.
      if (req.method === 'DELETE') {
        const cleared = recorder.clear();
        return sendJson(res, 200, { cleared });
      }
      const limit = Number.parseInt(url.searchParams.get('limit') || '100', 10);
      return sendJson(res, 200, recorder.list(limit));
    }
    if (p.startsWith('/api/exchanges/')) {
      const id = decodeURIComponent(p.slice('/api/exchanges/'.length));
      const ex = recorder.get(id);
      return ex ? sendJson(res, 200, ex) : sendJson(res, 404, { error: 'not found' });
    }

    // Static files (index.html only by default).
    const file = p === '/' ? 'index.html' : p.replace(/^\/+/, '');

    // Packaged (.exe) build: serve the bundled index.html asset directly.
    if (file === 'index.html') {
      const embedded = seaIndexHtml();
      if (embedded != null) {
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
        return res.end(embedded);
      }
    }

    const full = path.join(PUBLIC_DIR, file);
    if (!full.startsWith(PUBLIC_DIR)) {
      res.writeHead(403);
      return res.end('forbidden');
    }
    fs.readFile(full, (err, data) => {
      if (err) {
        res.writeHead(404);
        return res.end('not found');
      }
      const ext = path.extname(full);
      const type = ext === '.html' ? 'text/html' : ext === '.js' ? 'text/javascript' : 'text/plain';
      res.writeHead(200, { 'content-type': `${type}; charset=utf-8` });
      res.end(data);
    });
  });

  server.listen(config.uiPort, '127.0.0.1', () => {
    console.log(`[ui]    open http://127.0.0.1:${server.address().port}`);
  });
  return server;
}
