"""Configuration loading from environment variables / overrides.

Env vars (all optional):
    PROXY_PORT          proxy listen port             (default 8788)
    UI_PORT             web UI listen port            (default 8789)
    LOG_DIR             directory for JSONL logs      (default <module>/logs)
    REDACT_AUTH         "0" to keep raw auth headers  (default redact)
    ANTHROPIC_UPSTREAM  override Anthropic upstream    (default https://api.anthropic.com)
    OPENAI_UPSTREAM     override OpenAI upstream       (default https://api.openai.com)
    MAX_BODY_BYTES      max stored body size, larger is truncated (default 2_000_000)
    ANTHROPIC_COMPAT    "chat" to translate incoming /v1/messages into OpenAI
                        /v1/chat/completions (for vendors that only support chat).
                        Default off (transparent pass-through).
    MODEL_MAP           model name remap used in compat mode. JSON object
                        (e.g. {"claude-sonnet-4-6":"gpt-4o"}) OR comma list
                        (e.g. "claude-sonnet-4-6=gpt-4o,claude-haiku-4-5=gpt-4o-mini").
    MODEL_MAP_FILE      path to a JSON file with the same mapping (alternative to MODEL_MAP).
"""

import json
import os
from pathlib import Path

_MODULE_ROOT = Path(__file__).resolve().parent.parent


def _int_env(name, fallback):
    v = os.environ.get(name)
    if v is None or v == "":
        return fallback
    try:
        return int(v)
    except ValueError:
        return fallback


def parse_model_map(raw):
    """Parse the model map from a string that is either JSON or a comma list of
    ``from=to`` pairs. Returns a dict (empty when nothing is configured)."""
    if not isinstance(raw, str):
        return {}
    trimmed = raw.strip()
    if trimmed == "":
        return {}
    if trimmed.startswith("{"):
        try:
            obj = json.loads(trimmed)
            return obj if isinstance(obj, dict) else {}
        except (ValueError, TypeError):
            return {}
    out = {}
    for pair in trimmed.split(","):
        if "=" not in pair:
            continue
        k, v = pair.split("=", 1)
        k = k.strip()
        if k:
            out[k] = v.strip()
    return out


def _load_model_map():
    path = os.environ.get("MODEL_MAP_FILE")
    if path:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                return parse_model_map(fh.read())
        except OSError as err:
            print(f"[config] failed to read MODEL_MAP_FILE: {err}")
    return parse_model_map(os.environ.get("MODEL_MAP"))


def load_config(**overrides):
    config = {
        "proxyPort": _int_env("PROXY_PORT", 8788),
        "uiPort": _int_env("UI_PORT", 8789),
        "logDir": os.environ.get("LOG_DIR") or str(_MODULE_ROOT / "logs"),
        "redactAuth": os.environ.get("REDACT_AUTH") != "0",
        "maxBodyBytes": _int_env("MAX_BODY_BYTES", 2_000_000),
        "upstream": {
            "anthropic": os.environ.get("ANTHROPIC_UPSTREAM") or "https://api.anthropic.com",
            "openai": os.environ.get("OPENAI_UPSTREAM") or "https://api.openai.com",
        },
        "compat": {
            # When 'chat', /v1/messages is translated to /v1/chat/completions and
            # sent to the OpenAI upstream. None = transparent pass-through (default).
            "anthropicTo": "chat" if (os.environ.get("ANTHROPIC_COMPAT") or "").lower() == "chat" else None,
            "modelMap": _load_model_map(),
        },
    }
    config.update(overrides)
    return config
