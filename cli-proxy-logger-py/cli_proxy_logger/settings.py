"""GUI-driven settings: a single ``config.json`` is the source of truth when the
app is launched from the packaged executable (or ``python -m cli_proxy_logger``).
The file mirrors the form shown in the web UI, so non-technical users never touch
env vars or JSON by hand. When no file exists we seed initial settings from the
environment so the existing env-var workflow keeps working unchanged.

Two shapes are involved:
  * "settings"  — the GUI-shaped, editable object stored in config.json.
  * "config"    — the runtime object the proxy/UI consume (built by
                  build_config_from_settings, identical in shape to load_config()).

Mirror of the Node ``src/settings.js``.
"""

import json
import os
import sys
from pathlib import Path

from .providers import parse_providers
from .filters import parse_filters
from .transform import DEFAULT_TOOL_NAME_MAP
from .outbound import create_outbound
from .config import parse_model_map

_MODULE_ROOT = Path(__file__).resolve().parent.parent


def is_packaged():
    """True when running from a PyInstaller-built single-file/one-dir bundle."""
    return bool(getattr(sys, "frozen", False))


def base_dir():
    """Directory the executable lives in when packaged, else the module root.
    Anchors config.json and the default logs/ dir beside the running program."""
    if is_packaged():
        return Path(sys.executable).resolve().parent
    return _MODULE_ROOT


def resource_dir():
    """Directory bundled read-only assets (public/index.html) live in. PyInstaller
    extracts data files to sys._MEIPASS at runtime; in dev it's the module root."""
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        return Path(meipass)
    return _MODULE_ROOT


def config_file_path():
    """Where config.json lives. Priority:
    1. CONFIG_FILE env override (explicit path)
    2. next to the executable when packaged
    3. the module root in dev."""
    override = os.environ.get("CONFIG_FILE")
    if override:
        return Path(override).resolve()
    return base_dir() / "config.json"


def default_settings():
    """The default editable settings (everything off = transparent pass-through)."""
    return {
        "proxyPort": 8788,
        "uiPort": 8789,
        "logDir": "",
        "upstream": {"anthropic": "https://api.anthropic.com", "openai": "https://api.openai.com"},
        "compat": {"enabled": False, "modelMap": {}},
        "toolName": {"enabled": False, "request": True, "response": True, "repairInput": True, "map": {}},
        "filters": [],
        "outbound": {"url": ""},
        "providers": {"anthropic": [], "openai": []},
        "breaker": {"enabled": False, "failureThreshold": 5, "cooldownMs": 30000, "halfOpenMax": 1,
                    "failoverStatuses": [429, 500, 502, 503, 504]},
        "rectifier": {"enabled": False, "signature": True, "budget": True},
    }


def _bool(v):
    return v in (True, 1) or (isinstance(v, str) and v.lower() in ("1", "on", "true", "yes"))


def _int(v, fallback):
    try:
        return int(v)
    except (ValueError, TypeError):
        return fallback


def settings_from_env():
    """Seed settings from environment variables (only when no config.json yet),
    so an operator who already runs with env vars sees them pre-filled in the GUI."""
    s = default_settings()
    e = os.environ
    if e.get("PROXY_PORT"):
        s["proxyPort"] = _int(e.get("PROXY_PORT"), s["proxyPort"])
    if e.get("UI_PORT"):
        s["uiPort"] = _int(e.get("UI_PORT"), s["uiPort"])
    if e.get("LOG_DIR"):
        s["logDir"] = e["LOG_DIR"]
    if e.get("ANTHROPIC_UPSTREAM"):
        s["upstream"]["anthropic"] = e["ANTHROPIC_UPSTREAM"]
    if e.get("OPENAI_UPSTREAM"):
        s["upstream"]["openai"] = e["OPENAI_UPSTREAM"]
    s["compat"]["enabled"] = (e.get("ANTHROPIC_COMPAT") or "").lower() == "chat"
    s["compat"]["modelMap"] = parse_model_map(e.get("MODEL_MAP"))
    s["toolName"]["enabled"] = _bool(e.get("TOOL_NAME_CASE"))
    s["toolName"]["request"] = e.get("TOOL_NAME_REQUEST") != "0"
    s["toolName"]["response"] = e.get("TOOL_NAME_RESPONSE") != "0"
    s["toolName"]["repairInput"] = e.get("TOOL_NAME_REPAIR_INPUT") != "0"
    raw_map = e.get("TOOL_NAME_MAP")
    if raw_map and raw_map.strip().startswith("{"):
        try:
            obj = json.loads(raw_map)
            if isinstance(obj, dict):
                s["toolName"]["map"] = obj
        except (ValueError, TypeError):
            pass
    raw_filters = e.get("FILTERS")
    if raw_filters:
        try:
            arr = json.loads(raw_filters)
            if isinstance(arr, list):
                s["filters"] = arr
        except (ValueError, TypeError):
            pass
    s["outbound"]["url"] = e.get("UPSTREAM_PROXY") or e.get("HTTPS_PROXY") or e.get("HTTP_PROXY") or ""
    s["rectifier"]["enabled"] = _bool(e.get("RECTIFY")) or _bool(e.get("RECTIFIER"))
    return s


def merge_settings(base, override):
    """Deep-ish merge of a partial settings object over the defaults so
    older/partial files still load with sane values for newly-added fields."""
    out = dict(base)
    for k, v in (override or {}).items():
        bv = base.get(k)
        if isinstance(v, dict) and isinstance(bv, dict):
            out[k] = merge_settings(bv, v)
        elif v is not None:
            out[k] = v
    return out


def read_settings():
    """Read the editable settings: config.json if present, else env-seeded defaults.
    Returns ``(settings, source, file)`` where source is 'file' or 'env'."""
    file = config_file_path()
    try:
        if file.exists():
            parsed = json.loads(file.read_text(encoding="utf-8"))
            return merge_settings(default_settings(), parsed), "file", file
    except (OSError, ValueError) as err:
        print(f"[settings] failed to read config.json: {err}")
    return settings_from_env(), "env", file


def write_settings(settings):
    """Persist settings to config.json (pretty-printed for human inspection)."""
    file = config_file_path()
    clean = merge_settings(default_settings(), settings or {})
    file.write_text(json.dumps(clean, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return clean, file


def _flatten_providers(providers):
    """Flatten the two provider lists into the single tagged array parse_providers wants."""
    out = []
    for group in ("anthropic", "openai"):
        for p in (providers or {}).get(group) or []:
            if not isinstance(p, dict) or not p.get("baseUrl"):
                continue
            out.append({"id": p.get("id") or None, "group": group,
                        "baseUrl": p["baseUrl"], "apiKey": p.get("apiKey") or None})
    return out


def build_config_from_settings(settings):
    """Build the runtime config (same shape as load_config()) from editable settings."""
    s = merge_settings(default_settings(), settings or {})
    raw_statuses = s["breaker"].get("failoverStatuses") or []
    statuses = {n for n in (_int(x, None) for x in raw_statuses) if isinstance(n, int)}
    if not statuses:
        statuses = {429, 500, 502, 503, 504}
    log_dir = s.get("logDir")
    return {
        "proxyPort": _int(s.get("proxyPort"), 8788),
        "uiPort": _int(s.get("uiPort"), 8789),
        "logDir": str(Path(log_dir).resolve()) if log_dir else str(base_dir() / "logs"),
        "redactAuth": True,
        "maxBodyBytes": 2_000_000,
        "upstream": {
            "anthropic": s["upstream"].get("anthropic") or "https://api.anthropic.com",
            "openai": s["upstream"].get("openai") or "https://api.openai.com",
        },
        "compat": {
            "anthropicTo": "chat" if s["compat"].get("enabled") else None,
            "modelMap": s["compat"].get("modelMap") if isinstance(s["compat"].get("modelMap"), dict) else {},
        },
        "providers": {"pools": parse_providers(_flatten_providers(s.get("providers")))},
        "breaker": {
            "enabled": bool(s["breaker"].get("enabled")),
            "failureThreshold": _int(s["breaker"].get("failureThreshold"), 5),
            "cooldownMs": _int(s["breaker"].get("cooldownMs"), 30000),
            "halfOpenMax": _int(s["breaker"].get("halfOpenMax"), 1),
            "failoverStatuses": statuses,
        },
        "rectifier": {
            "enabled": bool(s["rectifier"].get("enabled")),
            "signature": s["rectifier"].get("signature") is not False,
            "budget": s["rectifier"].get("budget") is not False,
        },
        "transform": {
            "toolName": {
                "enabled": bool(s["toolName"].get("enabled")),
                "request": s["toolName"].get("request") is not False,
                "response": s["toolName"].get("response") is not False,
                "repairInput": s["toolName"].get("repairInput") is not False,
                "map": {**DEFAULT_TOOL_NAME_MAP,
                        **(s["toolName"].get("map") if isinstance(s["toolName"].get("map"), dict) else {})},
            },
        },
        "filters": parse_filters(s.get("filters") if isinstance(s.get("filters"), list) else []),
        "outbound": create_outbound(s["outbound"].get("url") or ""),
    }
