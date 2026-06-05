// Persists exchanges to a per-day JSONL file and keeps the most recent ones in
// memory for the UI/API. One JSON object per line in logs/YYYY-MM-DD.jsonl.

import fs from 'node:fs';
import path from 'node:path';

const MEM_LIMIT = 500;

export class Recorder {
  constructor(config) {
    this.config = config;
    this.recent = [];
    fs.mkdirSync(config.logDir, { recursive: true });
  }

  _file() {
    const day = new Date().toISOString().slice(0, 10);
    return path.join(this.config.logDir, `${day}.jsonl`);
  }

  record(exchange) {
    this.recent.unshift(exchange);
    if (this.recent.length > MEM_LIMIT) this.recent.length = MEM_LIMIT;
    try {
      fs.appendFileSync(this._file(), JSON.stringify(exchange) + '\n');
    } catch (err) {
      console.error('[recorder] failed to write log:', err.message);
    }
  }

  list(limit = 100) {
    return this.recent.slice(0, limit).map((e) => ({
      id: e.id,
      ts: e.ts,
      durationMs: e.durationMs,
      wire: e.wire,
      method: e.method,
      url: e.url,
      model: e.request?.model ?? null,
      stream: e.request?.stream ?? false,
      resStatus: e.resStatus,
      toolCallNames: (e.response?.toolCalls || []).map((t) => t.name),
      error: e.error ?? null,
    }));
  }

  get(id) {
    return this.recent.find((e) => e.id === id) || null;
  }

  // Clears the in-memory list (what the UI shows). The on-disk JSONL logs are
  // left untouched — they are the durable audit trail. Returns how many
  // in-memory entries were removed.
  clear() {
    const n = this.recent.length;
    this.recent = [];
    return n;
  }
}
