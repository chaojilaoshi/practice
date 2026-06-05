"""Provider pool (供应商池) -- the ordered list of upstreams we may fail over
between, grouped by upstream group. Mirror of the Node ``src/providers.js``.

Two upstream GROUPS match the two protocol families the proxy already speaks:
  - 'anthropic' : serves the Anthropic wire  (/v1/messages)
  - 'openai'    : serves the OpenAI wires     (/v1/responses, /v1/chat/completions)

A provider entry::

    {"id": "vendorA", "group": "openai", "baseUrl": "https://a.example.com",
     "apiKey": "sk-..."}

``apiKey`` is optional: when present we REPLACE the client's auth header with it;
when absent we forward the client's own credential unchanged (the transparent
default). Config sources: PROVIDERS_FILE (path to JSON) then PROVIDERS (inline
JSON). When NO pool is configured for a group we fall back to the single legacy
upstream (config['upstream'][...]).
"""

import json


def _norm_group(g):
    s = str(g or "").lower()
    if s in ("anthropic", "messages"):
        return "anthropic"
    return "openai"


def wire_to_group(wire):
    return "anthropic" if wire == "anthropic" else "openai"


def parse_providers(raw):
    """Parse a providers array (already-parsed list OR a JSON string) into
    ``{"anthropic": [...], "openai": [...]}``. Invalid entries are skipped."""
    arr = raw
    if isinstance(raw, str):
        t = raw.strip()
        if t == "":
            return {"anthropic": [], "openai": []}
        try:
            arr = json.loads(t)
        except (ValueError, TypeError):
            return {"anthropic": [], "openai": []}
    pools = {"anthropic": [], "openai": []}
    if not isinstance(arr, list):
        return pools
    auto = 0
    for p in arr:
        if not isinstance(p, dict) or not p.get("baseUrl"):
            continue
        group = _norm_group(p.get("group") or p.get("wire") or p.get("upstream"))
        pid = p.get("id")
        if not pid:
            pid = f"{group}-{auto}"
            auto += 1
        pools[group].append({
            "id": pid,
            "group": group,
            "baseUrl": str(p["baseUrl"]).rstrip("/"),
            "apiKey": p.get("apiKey") or None,
        })
    return pools


def resolve_candidates(config, wire):
    """Ordered candidate list for a request wire. Falls back to the legacy single
    upstream when the matching pool is empty. Each candidate is
    ``{"id", "baseUrl", "apiKey"}`` where apiKey may be None."""
    group = wire_to_group(wire)
    pool = ((config.get("providers") or {}).get("pools") or {}).get(group)
    if isinstance(pool, list) and len(pool) > 0:
        return pool
    return [{"id": f"default-{group}", "baseUrl": config["upstream"][group], "apiKey": None}]
