"""Configuration loading from environment variables / overrides.

Env vars (all optional):
    PROXY_PORT          proxy listen port             (default 8788)
    UI_PORT             web UI listen port            (default 8789)
    LOG_DIR             directory for JSONL logs      (default <module>/logs)
    REDACT_AUTH         "0" to keep raw auth headers  (default redact)
    ANTHROPIC_UPSTREAM  override Anthropic upstream    (default https://api.anthropic.com)
    OPENAI_UPSTREAM     override OpenAI upstream       (default https://api.openai.com)
    MAX_BODY_BYTES      max stored body size, larger is truncated (default 2_000_000)
"""

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
    }
    config.update(overrides)
    return config
