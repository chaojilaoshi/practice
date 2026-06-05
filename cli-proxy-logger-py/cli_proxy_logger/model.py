"""Unified data model.

All three wire formats (Anthropic Messages, OpenAI Responses, OpenAI Chat
Completions) are normalized into these shapes so the recorder and UI can treat
them uniformly. We use plain dicts (JSON-serializable) so the Web UI receives
exactly the same shapes as the Node version.

NormalizedRequest {
    wire: 'anthropic'|'responses'|'chat',
    model: str|None,
    system: str|None,
    messages: list[{ role, text, toolUses, toolResults }],
    tools: list[{ name, description }],   # tool *definitions* offered to model
    stream: bool,
    raw: any,
}

ToolCall { id, name, args }   # args = parsed object (or { _raw } if unparseable)

NormalizedResponse {
    text: str,
    toolCalls: ToolCall[],
    stopReason: str|None,
    usage: object|None,
    raw: any|None,
}
"""

import json


def safe_json_parse(s):
    """Return the parsed object, or None when the input is not valid JSON."""
    if not isinstance(s, str) or s == "":
        return None
    try:
        return json.loads(s)
    except (ValueError, TypeError):
        return None


def parse_tool_args(raw_string):
    """Parse accumulated tool-argument JSON into an object.

    Preserves the raw text under ``_raw`` when it is not valid JSON (e.g. a
    truncated stream), mirroring the Node implementation.
    """
    parsed = safe_json_parse(raw_string)
    if parsed is not None:
        return parsed
    if raw_string == "" or raw_string is None:
        return {}
    return {"_raw": raw_string}


def empty_response():
    return {"text": "", "toolCalls": [], "stopReason": None, "usage": None, "raw": None}
