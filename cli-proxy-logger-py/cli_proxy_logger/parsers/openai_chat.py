"""OpenAI Chat Completions parser (Codex chat mode / OpenAI-compatible APIs).

Request body (POST /v1/chat/completions):
    { model, messages:[{role, content, tool_calls, tool_call_id}], tools:[...], stream }

Streaming response (SSE, "data: {json}\\n\\n", ends with "data: [DONE]"):
    choices[].delta.content                 -> assistant text
    choices[].delta.tool_calls[]            -> { index, id, function:{ name, arguments } }
         accumulate by index: name once, arguments concatenated
    choices[].finish_reason                 -> stop reason
    usage (final chunk when stream_options.include_usage)
"""

from ..model import parse_tool_args, empty_response, safe_json_parse


def parse_request(body):
    obj = safe_json_parse(body) if isinstance(body, str) else body
    if not isinstance(obj, dict):
        return {"wire": "chat", "model": None, "system": None, "messages": [],
                "tools": [], "stream": False, "raw": obj if obj is not None else body}
    system = None
    messages = []
    for m in (obj.get("messages") if isinstance(obj.get("messages"), list) else []):
        if not isinstance(m, dict):
            continue
        if m.get("role") in ("system", "developer"):
            piece = m.get("content") if isinstance(m.get("content"), str) else ""
            system = (system + "\n" if system else "") + piece
            continue
        tool_uses = [{"id": tc.get("id"), "name": (tc.get("function") or {}).get("name"),
                      "args": parse_tool_args((tc.get("function") or {}).get("arguments"))}
                     for tc in (m.get("tool_calls") or [])]
        tool_results = ([{"toolUseId": m.get("tool_call_id"), "content": m.get("content"), "isError": False}]
                        if m.get("role") == "tool" else [])
        messages.append({"role": m.get("role"),
                          "text": m.get("content") if isinstance(m.get("content"), str) else "",
                          "toolUses": tool_uses, "toolResults": tool_results})
    tools = [{"name": (t.get("function") or {}).get("name") or t.get("name"),
              "description": (t.get("function") or {}).get("description") or t.get("description")}
             for t in (obj.get("tools") or [])]
    return {"wire": "chat", "model": obj.get("model"), "system": system, "messages": messages,
            "tools": tools, "stream": bool(obj.get("stream")), "raw": obj}


def parse_response(body):
    obj = safe_json_parse(body) if isinstance(body, str) else body
    res = empty_response()
    if not isinstance(obj, dict):
        res["raw"] = obj if obj is not None else body
        return res
    res["raw"] = obj
    res["usage"] = obj.get("usage")
    choices = obj.get("choices")
    choice = choices[0] if isinstance(choices, list) and choices else None
    if choice:
        res["stopReason"] = choice.get("finish_reason")
        msg = choice.get("message") or {}
        if isinstance(msg.get("content"), str):
            res["text"] += msg["content"]
        for tc in (msg.get("tool_calls") or []):
            res["toolCalls"].append({"id": tc.get("id"), "name": (tc.get("function") or {}).get("name"),
                                      "args": parse_tool_args((tc.get("function") or {}).get("arguments"))})
    return res


def create_stream_aggregator():
    res = empty_response()
    calls = {}  # index -> { id, name, argText }

    def feed(evt):
        raw = (evt.get("data") or "").strip()
        if raw == "" or raw == "[DONE]":
            return
        data = safe_json_parse(raw)
        if not data:
            return
        if data.get("usage"):
            res["usage"] = data["usage"]
        choices = data.get("choices")
        choice = choices[0] if isinstance(choices, list) and choices else None
        if not choice:
            return
        if choice.get("finish_reason"):
            res["stopReason"] = choice["finish_reason"]
        delta = choice.get("delta") or {}
        if isinstance(delta.get("content"), str):
            res["text"] += delta["content"]
        for tc in (delta.get("tool_calls") or []):
            idx = tc.get("index", 0)
            c = calls.get(idx)
            if not c:
                c = {"id": tc.get("id"), "name": (tc.get("function") or {}).get("name"), "argText": ""}
                calls[idx] = c
            if tc.get("id"):
                c["id"] = tc["id"]
            fn = tc.get("function") or {}
            if fn.get("name"):
                c["name"] = fn["name"]
            if fn.get("arguments"):
                c["argText"] += fn["arguments"]

    def result():
        if not res["toolCalls"] and calls:
            for _, c in sorted(calls.items(), key=lambda kv: kv[0]):
                res["toolCalls"].append({"id": c["id"], "name": c["name"],
                                          "args": parse_tool_args(c["argText"])})
        return res

    return {"feed": feed, "result": result}
