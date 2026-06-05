"""Request rectifier (请求整流器) -- opt-in auto-repair for Anthropic "thinking"
requests that some providers reject. Mirror of the Node ``src/rectifier.js``.

Only applies to the Anthropic wire (/v1/messages), because "thinking" and its
cryptographic ``signature`` fields are Anthropic concepts. The proxy detects a
specific upstream error, applies a minimal rewrite of the REQUEST body, and
retries ONCE against the same provider. If the retry still fails the normal flow
resumes (which may then fail over to another provider).

Two rectifications (each independently toggleable):
  1) signature -- strip historical thinking/redacted_thinking blocks and any
     ``signature`` fields that a different provider can't validate, then retry.
  2) budget    -- enable thinking with a safe budget (32000) and raise
     ``max_tokens`` to at least 64000 when needed, then retry.
"""

import copy
import json


def _error_text(err_obj):
    if err_obj is None:
        return ""
    if isinstance(err_obj, str):
        return err_obj
    if isinstance(err_obj, dict):
        err = err_obj.get("error")
        msg = (err.get("message") if isinstance(err, dict) else None) or err_obj.get("message") or ""
        return msg if isinstance(msg, str) and msg else json.dumps(err_obj)
    return str(err_obj)


def detect_rectification(status, err_obj, cfg=None):
    """Return 'signature' | 'budget' | None."""
    cfg = cfg or {"signature": True, "budget": True}
    # Only client-side request errors (4xx) are rectifiable; 5xx is an outage.
    if isinstance(status, int) and status >= 500:
        return None
    text = _error_text(err_obj).lower()
    if not text:
        return None
    if cfg.get("budget", True) is not False:
        if "budget_tokens" in text or ("budget" in text and "thinking" in text):
            return "budget"
    if cfg.get("signature", True) is not False:
        if "signature" in text:
            return "signature"
    return None


def _strip_signature(block):
    if not isinstance(block, dict):
        return block
    return {k: v for k, v in block.items() if k != "signature"}


def rectify_signature(body):
    clone = copy.deepcopy(body or {})
    messages = clone.get("messages")
    if isinstance(messages, list):
        for m in messages:
            if not isinstance(m, dict) or not isinstance(m.get("content"), list):
                continue
            m["content"] = [
                _strip_signature(b)
                for b in m["content"]
                if not (isinstance(b, dict) and b.get("type") in ("thinking", "redacted_thinking"))
            ]
    return clone


def rectify_budget(body):
    clone = copy.deepcopy(body or {})
    clone["thinking"] = {"type": "enabled", "budget_tokens": 32000}
    mt = clone.get("max_tokens")
    if not isinstance(mt, int) or isinstance(mt, bool) or mt < 64000:
        clone["max_tokens"] = 64000
    return clone


def apply_rectification(kind, body):
    if kind == "signature":
        return rectify_signature(body)
    if kind == "budget":
        return rectify_budget(body)
    return body
