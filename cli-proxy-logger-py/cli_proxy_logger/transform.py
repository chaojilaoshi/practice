"""Tool-name normalization (工具名规范化) -- opt-in.

Why: some Anthropic-compatible upstreams (e.g. anyrouter) VALIDATE tool names
and reject lowercase names that clients such as opencode emit (``read``,
``write``, ``edit``...). Renaming them to PascalCase (``Read``, ``Write``,
``Edit``) makes the upstream accept the request. This module rewrites tool names
on the request (the critical fix) and, optionally, on the response so the
round-trip stays consistent; it also repairs tool-call inputs whose array/object
values were serialized to a JSON *string* by the upstream.

All of this is OFF by default. When ``TOOL_NAME_CASE`` is enabled the proxy is no
longer a byte-for-byte transparent relay for Anthropic traffic -- that is the
whole point of the feature, and it only affects opted-in requests.
"""

import json

# Built-in special-cases where simple capitalization is not the desired name.
# Extend/override via TOOL_NAME_MAP / TOOL_NAME_MAP_FILE.
DEFAULT_TOOL_NAME_MAP = {
    "todowrite": "TodoWrite",
    "todoread": "TodoRead",
    "webfetch": "WebFetch",
    "websearch": "WebSearch",
    "google_search": "Google_Search",
    "multiedit": "MultiEdit",
    "notebookedit": "NotebookEdit",
    "notebookread": "NotebookRead",
}


def map_tool_name(name, name_map):
    """Map one tool name. Exact match in the map wins; otherwise a
    case-insensitive lookup; otherwise capitalize the first character."""
    if not isinstance(name, str) or name == "":
        return name
    m = name_map or {}
    if name in m:
        return m[name]
    lower = name.lower()
    if lower in m:
        return m[lower]
    return name[0].upper() + name[1:]


def normalize_request_tool_names(body, name_map):
    """Rewrite tool names on an Anthropic /v1/messages REQUEST body dict, in
    place. Touches ``tools[].name`` and historical ``tool_use`` blocks in
    messages. Returns the number of names changed."""
    if not isinstance(body, dict):
        return 0
    changed = 0

    def rename(obj):
        nonlocal changed
        nxt = map_tool_name(obj.get("name"), name_map)
        if nxt != obj.get("name"):
            obj["name"] = nxt
            changed += 1

    tools = body.get("tools")
    if isinstance(tools, list):
        for tool in tools:
            if isinstance(tool, dict) and isinstance(tool.get("name"), str):
                rename(tool)
    messages = body.get("messages")
    if isinstance(messages, list):
        for msg in messages:
            if not isinstance(msg, dict):
                continue
            content = msg.get("content")
            if not isinstance(content, list):
                continue
            for block in content:
                if isinstance(block, dict) and block.get("type") == "tool_use" and isinstance(block.get("name"), str):
                    rename(block)
    return changed


def rewrite_response_tool_names(body, name_map):
    """Rewrite tool_use names on an Anthropic RESPONSE body dict, in place."""
    if not isinstance(body, dict) or not isinstance(body.get("content"), list):
        return 0
    changed = 0
    for block in body["content"]:
        if isinstance(block, dict) and block.get("type") == "tool_use" and isinstance(block.get("name"), str):
            nxt = map_tool_name(block["name"], name_map)
            if nxt != block["name"]:
                block["name"] = nxt
                changed += 1
    return changed


def repair_tool_use_input(body):
    """Repair tool_use inputs where the upstream serialized an array/object value
    as a JSON *string*. Parses those strings back into real values, in place.
    Returns the number of values repaired."""
    if not isinstance(body, dict) or not isinstance(body.get("content"), list):
        return 0
    repaired = 0
    for block in body["content"]:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        inp = block.get("input")
        if not isinstance(inp, dict):
            continue
        for key in list(inp.keys()):
            val = inp[key]
            if not isinstance(val, str):
                continue
            t = val.strip()
            if not (t.startswith("[") or t.startswith("{")):
                continue
            try:
                inp[key] = json.loads(t)
                repaired += 1
            except (ValueError, TypeError):
                pass  # leave non-JSON strings untouched
    return repaired


def rewrite_stream_event_tool_name(data, name_map):
    """Rewrite the tool name carried by a parsed SSE ``content_block_start``
    event dict, in place. Returns True when the name changed."""
    if (isinstance(data, dict) and data.get("type") == "content_block_start"
            and isinstance(data.get("content_block"), dict)
            and data["content_block"].get("type") == "tool_use"
            and isinstance(data["content_block"].get("name"), str)):
        nxt = map_tool_name(data["content_block"]["name"], name_map)
        if nxt != data["content_block"]["name"]:
            data["content_block"]["name"] = nxt
            return True
    return False
