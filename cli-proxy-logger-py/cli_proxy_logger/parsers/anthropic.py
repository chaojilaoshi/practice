"""Anthropic Messages API parser (Claude Code).

Request body (POST /v1/messages):
    { model, system, messages:[{role, content:[blocks]}], tools:[...], stream }
    - content blocks of type "tool_use"    are the model's tool calls (prior turns)
    - content blocks of type "tool_result" are the tool outputs fed back in

Streaming response (SSE):
    message_start       -> { message: { usage, ... } }
    content_block_start -> { index, content_block: { type, ... } }
         type "text"     -> accumulate text_delta
         type "tool_use" -> { id, name }, then input_json_delta partial_json
    content_block_delta -> { index, delta: { type, text|partial_json } }
    content_block_stop  -> { index }
    message_delta       -> { delta: { stop_reason }, usage }
    message_stop
"""

from ..model import parse_tool_args, empty_response, safe_json_parse


def _block_text(content):
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    return "".join(
        b.get("text", "")
        for b in content
        if isinstance(b, dict) and b.get("type") == "text" and isinstance(b.get("text"), str)
    )


def parse_request(body):
    obj = safe_json_parse(body) if isinstance(body, str) else body
    if not isinstance(obj, dict):
        return {"wire": "anthropic", "model": None, "system": None, "messages": [],
                "tools": [], "stream": False, "raw": obj if obj is not None else body}
    messages = []
    for m in obj.get("messages") or []:
        content = m.get("content")
        tool_uses = []
        tool_results = []
        if isinstance(content, list):
            for b in content:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "tool_use":
                    tool_uses.append({"id": b.get("id"), "name": b.get("name"), "args": b.get("input")})
                elif b.get("type") == "tool_result":
                    tool_results.append({"toolUseId": b.get("tool_use_id"),
                                          "content": b.get("content"),
                                          "isError": bool(b.get("is_error"))})
        messages.append({"role": m.get("role"), "text": _block_text(content),
                          "toolUses": tool_uses, "toolResults": tool_results})
    system_val = obj.get("system")
    system = system_val if isinstance(system_val, str) else _block_text(system_val)
    tools = [{"name": t.get("name"), "description": t.get("description")}
             for t in (obj.get("tools") or [])]
    return {"wire": "anthropic", "model": obj.get("model"), "system": system or None,
            "messages": messages, "tools": tools, "stream": bool(obj.get("stream")), "raw": obj}


def parse_response(body):
    obj = safe_json_parse(body) if isinstance(body, str) else body
    res = empty_response()
    if not isinstance(obj, dict):
        res["raw"] = obj if obj is not None else body
        return res
    res["raw"] = obj
    res["stopReason"] = obj.get("stop_reason")
    res["usage"] = obj.get("usage")
    for b in obj.get("content") or []:
        if not isinstance(b, dict):
            continue
        if b.get("type") == "text":
            res["text"] += b.get("text") or ""
        elif b.get("type") == "tool_use":
            res["toolCalls"].append({"id": b.get("id"), "name": b.get("name"), "args": b.get("input") or {}})
    return res


def create_stream_aggregator():
    # Anthropic streams a response as a sequence of indexed "content blocks".
    # A block is either text or a tool_use, and its payload arrives in pieces.
    # We therefore key everything by the block `index`: open a block on
    # content_block_start, append its fragments on content_block_delta, and
    # finalize it on content_block_stop.
    res = empty_response()
    blocks = {}  # index -> { type, name, id, argText }

    def feed(evt):
        type_ = evt.get("event")
        data = safe_json_parse(evt.get("data"))
        if not data:
            return
        if type_ == "message_start":
            # First event of the stream; carries the initial usage counters.
            msg = data.get("message") or {}
            if msg.get("usage"):
                res["usage"] = msg["usage"]
        elif type_ == "content_block_start":
            # A new block opens. For a tool_use we already know id+name here;
            # the arguments JSON will stream in later as partial_json fragments.
            cb = data.get("content_block") or {}
            blocks[data.get("index")] = {"type": cb.get("type"), "name": cb.get("name"),
                                          "id": cb.get("id"), "argText": ""}
        elif type_ == "content_block_delta":
            # Incremental payload for an open block.
            b = blocks.get(data.get("index"))
            d = data.get("delta") or {}
            if d.get("type") == "text_delta":
                res["text"] += d.get("text") or ""  # assistant prose, char by char
            elif d.get("type") == "input_json_delta" and b:
                # Tool arguments arrive as a JSON STRING split into fragments;
                # we just concatenate them and json-parse once the block ends.
                b["argText"] += d.get("partial_json") or ""
        elif type_ == "content_block_stop":
            # Block complete. If it was a tool call, the accumulated argText is
            # now a full JSON object -> parse it into the final ToolCall.
            b = blocks.get(data.get("index"))
            if b and b.get("type") == "tool_use":
                res["toolCalls"].append({"id": b.get("id"), "name": b.get("name"),
                                          "args": parse_tool_args(b.get("argText"))})
        elif type_ == "message_delta":
            delta = data.get("delta") or {}
            if delta.get("stop_reason"):
                res["stopReason"] = delta["stop_reason"]
            if data.get("usage"):
                res["usage"] = {**(res["usage"] or {}), **data["usage"]}

    return {"feed": feed, "result": lambda: res}
