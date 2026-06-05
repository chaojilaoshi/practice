"""Persists exchanges to a per-day JSONL file and keeps the most recent ones in
memory for the UI/API. One JSON object per line in logs/YYYY-MM-DD.jsonl.
"""

import json
import os
import threading
from datetime import datetime, timezone

MEM_LIMIT = 500


class Recorder:
    def __init__(self, config):
        self.config = config
        self.recent = []
        self._lock = threading.Lock()
        os.makedirs(config["logDir"], exist_ok=True)

    def _file(self):
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        return os.path.join(self.config["logDir"], f"{day}.jsonl")

    def record(self, exchange):
        with self._lock:
            self.recent.insert(0, exchange)
            if len(self.recent) > MEM_LIMIT:
                del self.recent[MEM_LIMIT:]
        try:
            with open(self._file(), "a", encoding="utf-8") as f:
                f.write(json.dumps(exchange, ensure_ascii=False) + "\n")
        except OSError as err:
            print(f"[recorder] failed to write log: {err}")

    def list(self, limit=100):
        with self._lock:
            items = list(self.recent[:limit])
        out = []
        for e in items:
            req = e.get("request") or {}
            res = e.get("response") or {}
            out.append({
                "id": e.get("id"),
                "ts": e.get("ts"),
                "durationMs": e.get("durationMs"),
                "wire": e.get("wire"),
                "method": e.get("method"),
                "url": e.get("url"),
                "model": req.get("model"),
                "stream": req.get("stream", False),
                "resStatus": e.get("resStatus"),
                "toolCallNames": [t.get("name") for t in (res.get("toolCalls") or [])],
                "error": e.get("error"),
            })
        return out

    def get(self, ex_id):
        with self._lock:
            for e in self.recent:
                if e.get("id") == ex_id:
                    return e
        return None

    def clear(self):
        # Clears the in-memory list (what the UI shows). The on-disk JSONL logs
        # are left untouched — they are the durable audit trail. Returns how many
        # in-memory entries were removed.
        with self._lock:
            n = len(self.recent)
            self.recent = []
        return n
