// Lightweight web UI + JSON API for browsing captured exchanges.
//   GET    /                   -> static index.html
//   GET    /api/exchanges      -> recent exchange summaries
//   GET    /api/exchanges/:id  -> full exchange detail
//   DELETE /api/exchanges      -> clear the in-memory list (one-click "清空")

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.resolve(__dirname, '..', 'public');

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(body);
}

export function startUi(config, recorder) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;

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
