"""OpenAI Responses API parser (Codex default, wire_api = "responses").

Request body (POST /v1/responses):
    { model, instructions, input:[items], tools:[...], stream }
    - input items can be messages or prior function_call / function_call_output

Streaming response (SSE, semantic events):
    response.created / response.in_progress
    response.output_item.added -> { item: { type, id, name, call_id } }
         type "function_call" -> tool call begins
    response.function_call_arguments.delta -> { item_id, delta }  (accumulate args)
    response.function_call_arguments.done  -> { item_id, arguments }
    response.output_text.delta -> { delta }   (assistant text)
    response.output_item.done / response.completed -> { response: { usage,... } }
"""

from ..model import parse_tool_args, empty_response, safe_json_parse


def _input_text(content):
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    return "".join(
        c.get("text") or ""
        for c in content
        if isinstance(c, dict) and c.get("type") in ("input_text", "output_text", "text")
    )


def parse_request(body):
    obj = safe_json_parse(body) if isinstance(body, str) else body
    if not isinstance(obj, dict):
        return {"wire": "responses", "model": None, "system": None, "messages": [],
                "tools": [], "stream": False, "raw": obj if obj is not None else body}
    items = obj.get("input") if isinstance(obj.get("input"), list) else []
    messages = []
    for it in items:
        if not isinstance(it, dict):
            continue
        if it.get("type") == "function_call" or (it.get("name") and it.get("call_id") and it.get("arguments") is not None):
            messages.append({"role": "assistant", "text": "",
                              "toolUses": [{"id": it.get("call_id"), "name": it.get("name"),
                                            "args": parse_tool_args(it.get("arguments"))}],
                              "toolResults": []})
        elif it.get("type") == "function_call_output":
            messages.append({"role": "tool", "text": "", "toolUses": [],
                              "toolResults": [{"toolUseId": it.get("call_id"),
                                               "content": it.get("output"), "isError": False}]})
        else:
            messages.append({"role": it.get("role") or "user", "text": _input_text(it.get("content")),
                              "toolUses": [], "toolResults": []})
    tools = [{"name": t.get("name") or (t.get("function") or {}).get("name"),
              "description": t.get("description") or (t.get("function") or {}).get("description")}
             for t in (obj.get("tools") or [])]
    return {"wire": "responses", "model": obj.get("model"), "system": obj.get("instructions"),
            "messages": messages, "tools": tools, "stream": bool(obj.get("stream")), "raw": obj}


def parse_response(body):
    obj = safe_json_parse(body) if isinstance(body, str) else body
    res = empty_response()
    if not isinstance(obj, dict):
        res["raw"] = obj if obj is not None else body
        return res
    res["raw"] = obj
    res["stopReason"] = obj.get("status")
    res["usage"] = obj.get("usage")
    for item in obj.get("output") or []:
        if not isinstance(item, dict):
            continue
        if item.get("type") == "function_call":
            res["toolCalls"].append({"id": item.get("call_id") or item.get("id"),
                                      "name": item.get("name"),
                                      "args": parse_tool_args(item.get("arguments"))})
        elif item.get("type") == "message" and isinstance(item.get("content"), list):
            res["text"] += _input_text(item.get("content"))
    return res


def create_stream_aggregator():
    # The Responses API streams "semantic" events that already name what is
    # happening (response.output_item.added, ...function_call_arguments.delta,
    # ...). Each output item has a stable `item_id`, so we accumulate tool-call
    # arguments per item_id and emit the ToolCall when the item is done.
    res = empty_response()
    calls = {}  # item_id -> { id, name, argText }

    def feed(evt):
        data = safe_json_parse(evt.get("data"))
        if not data:
            return
        # Event name may be on the SSE "event:" line or inside the JSON.
        type_ = evt.get("event") or data.get("type")
        if type_ == "response.output_item.added":
            # A new output item appears; remember it if it's a function call.
            item = data.get("item") or {}
            if item.get("type") == "function_call":
                key = item.get("id") or data.get("item_id")
                calls[key] = {"id": item.get("call_id") or item.get("id"),
                              "name": item.get("name"), "argText": ""}
        elif type_ == "response.function_call_arguments.delta":
            # Argument JSON streams in as fragments -> concatenate by item_id.
            c = calls.get(data.get("item_id"))
            if c:
                c["argText"] += data.get("delta") or ""
        elif type_ == "response.function_call_arguments.done":
            # Some servers send the full arguments here instead of deltas; only
            # use it as a fallback when we never received any delta fragments.
            c = calls.get(data.get("item_id"))
            if c and data.get("arguments") is not None and c["argText"] == "":
                c["argText"] = data["arguments"]
        elif type_ == "response.output_item.done":
            # Item finished -> finalize the tool call (parse accumulated args).
            item = data.get("item") or {}
            if item.get("type") == "function_call":
                key = item.get("id") or data.get("item_id")
                c = calls.get(key) or {"id": item.get("call_id") or item.get("id"),
                                        "name": item.get("name"), "argText": item.get("arguments") or ""}
                res["toolCalls"].append({"id": c["id"], "name": c["name"],
                                          "args": parse_tool_args(c["argText"])})
                calls.pop(key, None)
        elif type_ == "response.output_text.delta":
            res["text"] += data.get("delta") or ""
        elif type_ in ("response.completed", "response.incomplete", "response.failed"):
            response = data.get("response") or {}
            if response.get("usage"):
                res["usage"] = response["usage"]
            if response.get("status"):
                res["stopReason"] = response["status"]
            # Flush any function calls that never got an explicit done event.
            for c in calls.values():
                res["toolCalls"].append({"id": c["id"], "name": c["name"],
                                          "args": parse_tool_args(c["argText"])})
            calls.clear()

    return {"feed": feed, "result": lambda: res}
