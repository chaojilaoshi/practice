"""Request filters / rules engine (过滤器/规则引擎) -- opt-in.

Inspired by claude-code-hub's "编辑过滤器": an ordered list of rules that mutate
the OUTBOUND request (headers and/or JSON body) just before it is sent to the
upstream. Typical uses (from the screenshots):
    - add an ``anthropic-beta`` header to every request (enable 1M context, etc.)
    - force ``thinking.type = "adaptive"`` / ``thinking.budget_tokens = 1024`` in
      the body so an upstream's thinking-budget validation passes.

A rule is::

    {
      "name":     "补充 think",            # human label (for logs/UI)
      "enabled":  True,                     # skip when False
      "priority": 0,                        # lower runs first
      "scope":    "all" | "provider:<id>",  # limit to one provider (failover pool)
      "stage":    "pre",                    # only stage today: before sending upstream
      "action":   "set_header" | "delete_header" | "json_set" | "json_delete",
      "target":   "anthropic-beta" | "thinking.type",  # header name or dot-path
      "value":    "adaptive" | 1024 | "a,b,c"          # header/json value
    }

``value`` for body actions is interpreted as JSON when it parses (so "1024" ->
1024, "true" -> True, '["x"]' -> list); otherwise it is used as a raw string (so
"adaptive" stays the string "adaptive"). Header values are always strings.

Everything is OFF unless ``FILTERS`` / ``FILTERS_FILE`` is configured.
"""

import json

ACTIONS = {"set_header", "delete_header", "json_set", "json_delete"}


def _norm_scope(scope):
    """Normalize scope into {"type":"all"} or {"type":"provider","id":...}."""
    if not scope or scope == "all":
        return {"type": "all"}
    if isinstance(scope, str) and scope.startswith("provider:"):
        pid = scope[len("provider:"):].strip()
        if pid:
            return {"type": "provider", "id": pid}
    if isinstance(scope, dict) and scope.get("type") == "provider" and scope.get("id"):
        return {"type": "provider", "id": str(scope["id"])}
    return {"type": "all"}


def parse_filters(raw):
    """Parse the filters config from an already-parsed list OR a JSON string.
    Invalid entries are skipped; the result is sorted by ascending priority
    (stable for equal priorities) so application order is deterministic."""
    arr = raw
    if isinstance(raw, str):
        t = raw.strip()
        if t == "":
            return []
        try:
            arr = json.loads(t)
        except (ValueError, TypeError):
            return []
    if not isinstance(arr, list):
        return []

    out = []
    auto = 0
    for r in arr:
        if not isinstance(r, dict):
            continue
        action = str(r.get("action") or "").lower()
        if action not in ACTIONS:
            continue
        target = str(r.get("target")) if r.get("target") is not None else ""
        if not target:
            continue
        domain = "body" if action.startswith("json") else "header"
        priority = r.get("priority")
        out.append({
            "name": str(r["name"]) if r.get("name") else f"filter-{auto}",
            "enabled": r.get("enabled") is not False,  # default enabled
            "priority": priority if isinstance(priority, (int, float)) and not isinstance(priority, bool) else 0,
            "scope": _norm_scope(r.get("scope")),
            "stage": "pre",
            "domain": domain,
            "action": action,
            "target": target,
            "value": r.get("value"),
            "_seq": auto,
        })
        auto += 1
    out.sort(key=lambda x: (x["priority"], x["_seq"]))
    return out


def _in_scope(rule, provider_id):
    if rule["scope"]["type"] == "all":
        return True
    return provider_id is not None and rule["scope"]["id"] == provider_id


def _coerce_value(value):
    """Coerce a configured body value: JSON when parseable, else the raw string."""
    if not isinstance(value, str):
        return value
    try:
        return json.loads(value.strip())
    except (ValueError, TypeError):
        return value


def _set_path(obj, path, value):
    parts = [p for p in path.split(".") if p]
    if not parts:
        return
    cur = obj
    for k in parts[:-1]:
        if not isinstance(cur.get(k), dict):
            cur[k] = {}
        cur = cur[k]
    cur[parts[-1]] = value


def _delete_path(obj, path):
    parts = [p for p in path.split(".") if p]
    if not parts:
        return
    cur = obj
    for k in parts[:-1]:
        if not isinstance(cur.get(k), dict):
            return
        cur = cur[k]
    cur.pop(parts[-1], None)


def apply_filters(filters, ctx):
    """Apply matching, enabled filters to an outbound request.

    ctx = {"providerId", "headers", "body"}
        - headers: a mutable dict of lowercase-keyed request headers
        - body:    the parsed request body dict (or None when not JSON)
    Mutates headers/body in place and returns {"applied": [names], "bodyChanged"}.
    """
    applied = []
    body_changed = False
    if not isinstance(filters, list) or not filters:
        return {"applied": applied, "bodyChanged": body_changed}

    headers = ctx["headers"]
    body = ctx.get("body")
    provider_id = ctx.get("providerId")

    for rule in filters:
        if not rule["enabled"] or not _in_scope(rule, provider_id):
            continue
        if rule["domain"] == "header":
            key = rule["target"].lower()
            if rule["action"] == "set_header":
                for k in [k for k in headers if k.lower() == key]:
                    del headers[k]
                headers[key] = "" if rule["value"] is None else str(rule["value"])
                applied.append(rule["name"])
            elif rule["action"] == "delete_header":
                hit = False
                for k in [k for k in headers if k.lower() == key]:
                    del headers[k]
                    hit = True
                if hit:
                    applied.append(rule["name"])
        elif rule["domain"] == "body" and isinstance(body, dict):
            if rule["action"] == "json_set":
                _set_path(body, rule["target"], _coerce_value(rule["value"]))
                body_changed = True
                applied.append(rule["name"])
            elif rule["action"] == "json_delete":
                _delete_path(body, rule["target"])
                body_changed = True
                applied.append(rule["name"])
    return {"applied": applied, "bodyChanged": body_changed}


def summarize_filters(filters):
    """Compact summary for the UI / introspection (no secrets)."""
    out = []
    for f in (filters or []):
        out.append({
            "name": f["name"],
            "enabled": f["enabled"],
            "priority": f["priority"],
            "scope": "all" if f["scope"]["type"] == "all" else f"provider:{f['scope']['id']}",
            "action": f["action"],
            "target": f["target"],
        })
    return out
