"""Protocol translation: Anthropic Messages  <->  OpenAI Chat Completions.

WHY this module exists
----------------------
The proxy is normally a *transparent* pass-through: it forwards the client's
bytes to the upstream untouched and streams the answer back byte-for-byte. That
works when the CLI and the upstream speak the SAME wire format.

But some third-party vendors only expose ``/v1/chat/completions`` (OpenAI Chat
format). Claude Code (and opencode's anthropic provider) only speak Anthropic
``/v1/messages``. To bridge them we must TRANSLATE in BOTH directions::

    client (Anthropic /v1/messages request)
         -> [anthropic_request_to_chat]  -> vendor (OpenAI /v1/chat/completions)
    vendor (OpenAI Chat response, JSON or SSE)
         -> [chat_response_to_anthropic / ChatToAnthropicStream] -> client (Anthropic)

This is opt-in (ANTHROPIC_COMPAT=chat). When off, the transparent path is used
and this module is never touched.

The hardest part is the STREAMING response translation: OpenAI streams
``choices[].delta`` chunks (text in ``delta.content``, tool calls split across
``delta.tool_calls[].index``), while Anthropic streams a sequence of *indexed
content blocks* (message_start -> content_block_start/delta/stop ... ->
message_delta -> message_stop). We rebuild the Anthropic event sequence on the
fly. See ``ChatToAnthropicStream`` below.
"""

import json
import uuid

from .model import safe_json_parse


# ----- model mapping -------------------------------------------------------
def map_model(model, model_map):
    """Map the model name the CLI sent to the one the vendor serves.

    If the model is not present in the map we pass it through unchanged.
    """
    if not model:
        return model
    if model_map and model in model_map:
        return model_map[model]
    return model


# ----- helpers -------------------------------------------------------------
def _system_to_text(system):
    """Anthropic ``system`` may be a string OR a list of text blocks -> flatten."""
    if isinstance(system, str):
        return system
    if isinstance(system, list):
        return "".join(
            b.get("text", "")
            for b in system
            if isinstance(b, dict) and b.get("type") == "text" and isinstance(b.get("text"), str)
        )
    return ""


def _tool_result_to_text(content):
    """Anthropic tool_result content (str or block list) -> a plain string,
    because OpenAI ``tool`` messages take a string."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for b in content:
            if isinstance(b, str):
                parts.append(b)
            elif isinstance(b, dict) and b.get("type") == "text":
                parts.append(b.get("text", ""))
            else:
                parts.append(json.dumps(b))
        return "".join(parts)
    if content is None:
        return ""
    return content if isinstance(content, str) else json.dumps(content)


def _user_content_to_chat(content):
    """Anthropic user content blocks -> OpenAI content (string, or parts list
    with text + image_url). Collapses to a string in the common text-only case."""
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts = []
    for b in content:
        if not isinstance(b, dict):
            continue
        if b.get("type") == "text":
            parts.append({"type": "text", "text": b.get("text", "")})
        elif b.get("type") == "image" and isinstance(b.get("source"), dict):
            src = b["source"]
            if src.get("type") == "base64":
                parts.append({"type": "image_url",
                              "image_url": {"url": f"data:{src.get('media_type')};base64,{src.get('data')}"}})
            elif src.get("type") == "url":
                parts.append({"type": "image_url", "image_url": {"url": src.get("url")}})
    if len(parts) == 1 and parts[0]["type"] == "text":
        return parts[0]["text"]
    if not parts:
        return ""
    return parts


# ----- request: Anthropic /v1/messages  ->  OpenAI /v1/chat/completions ----
def anthropic_request_to_chat(anth_body, model_map):
    """Translate an Anthropic Messages request dict into an OpenAI Chat dict.

    Anthropic request shape::
        { model, system, max_tokens, temperature, top_p, stop_sequences, stream,
          tools:[{name,description,input_schema}], tool_choice,
          messages:[{role:'user'|'assistant', content: str | block[]}] }
        block types: text | image | tool_use (assistant) | tool_result (user)

    OpenAI Chat request shape::
        { model, messages:[{role, content, tool_calls, tool_call_id}],
          tools:[{type:'function',function:{name,description,parameters}}],
          tool_choice, max_tokens, temperature, top_p, stop, stream, stream_options }
    """
    messages = []

    # 1) system prompt -> a leading system message.
    sys_text = _system_to_text(anth_body.get("system"))
    if sys_text:
        messages.append({"role": "system", "content": sys_text})

    # 2) walk the conversation turns.
    for m in anth_body.get("messages") or []:
        if not isinstance(m, dict):
            continue
        content = m.get("content")

        if m.get("role") == "assistant":
            # Assistant turn: text -> content, each tool_use -> a tool_calls entry
            # (OpenAI keeps tool calls OUT of content).
            text = ""
            tool_calls = []
            if isinstance(content, str):
                text = content
            elif isinstance(content, list):
                for b in content:
                    if not isinstance(b, dict):
                        continue
                    if b.get("type") == "text":
                        text += b.get("text", "")
                    elif b.get("type") == "tool_use":
                        tool_calls.append({
                            "id": b.get("id"),
                            "type": "function",
                            "function": {"name": b.get("name"),
                                         "arguments": json.dumps(b.get("input") or {})},
                        })
            msg = {"role": "assistant", "content": text or None}
            if tool_calls:
                msg["tool_calls"] = tool_calls
            messages.append(msg)
            continue

        # user turn: tool_result blocks each become a separate `tool` message
        # (linked back to the assistant call via tool_call_id); remaining
        # text/image blocks become one `user` message.
        if isinstance(content, list):
            tool_results = [b for b in content if isinstance(b, dict) and b.get("type") == "tool_result"]
            rest = [b for b in content if not (isinstance(b, dict) and b.get("type") == "tool_result")]
            for tr in tool_results:
                messages.append({"role": "tool", "tool_call_id": tr.get("tool_use_id"),
                                 "content": _tool_result_to_text(tr.get("content"))})
            user_content = _user_content_to_chat(rest)
            if not (user_content == "" or user_content == []):
                messages.append({"role": "user", "content": user_content})
        else:
            messages.append({"role": "user", "content": _user_content_to_chat(content)})

    out = {
        "model": map_model(anth_body.get("model"), model_map),
        "messages": messages,
        "stream": bool(anth_body.get("stream")),
    }

    # 3) sampling / limit params (only when present).
    if anth_body.get("max_tokens") is not None:
        out["max_tokens"] = anth_body["max_tokens"]
    if anth_body.get("temperature") is not None:
        out["temperature"] = anth_body["temperature"]
    if anth_body.get("top_p") is not None:
        out["top_p"] = anth_body["top_p"]
    if isinstance(anth_body.get("stop_sequences"), list) and anth_body["stop_sequences"]:
        out["stop"] = anth_body["stop_sequences"]

    # 4) tool definitions: Anthropic input_schema -> OpenAI function.parameters.
    if isinstance(anth_body.get("tools"), list) and anth_body["tools"]:
        out["tools"] = [{
            "type": "function",
            "function": {
                "name": t.get("name"),
                "description": t.get("description") or "",
                "parameters": t.get("input_schema") or {"type": "object", "properties": {}},
            },
        } for t in anth_body["tools"]]

    # 5) tool_choice: {type:'auto'|'any'|'tool', name?} -> 'auto'|'required'|{...}.
    tc = anth_body.get("tool_choice")
    if isinstance(tc, dict):
        if tc.get("type") == "auto":
            out["tool_choice"] = "auto"
        elif tc.get("type") == "any":
            out["tool_choice"] = "required"
        elif tc.get("type") == "tool" and tc.get("name"):
            out["tool_choice"] = {"type": "function", "function": {"name": tc["name"]}}

    # 6) ask the vendor to include token usage in the streamed final chunk so we
    # can forward real usage numbers back in the Anthropic message_delta event.
    if out["stream"]:
        out["stream_options"] = {"include_usage": True}

    return out


# ----- finish_reason / usage mapping --------------------------------------
def _map_stop_reason(finish_reason, had_tool_calls):
    """OpenAI finish_reason -> Anthropic stop_reason. Tool calls force 'tool_use'."""
    if had_tool_calls:
        return "tool_use"
    if finish_reason == "stop":
        return "end_turn"
    if finish_reason == "length":
        return "max_tokens"
    if finish_reason in ("tool_calls", "function_call"):
        return "tool_use"
    if finish_reason == "content_filter":
        return "end_turn"
    return "end_turn" if finish_reason else None


def _map_usage(usage):
    """OpenAI usage -> Anthropic usage (input_tokens/output_tokens). None if absent."""
    if not isinstance(usage, dict):
        return None
    return {"input_tokens": usage.get("prompt_tokens", 0),
            "output_tokens": usage.get("completion_tokens", 0)}


def _new_message_id():
    return "msg_" + uuid.uuid4().hex


def _new_tool_use_id():
    return "toolu_" + uuid.uuid4().hex


# ----- response (non-streaming): OpenAI Chat JSON -> Anthropic message ------
def chat_response_to_anthropic(chat_obj, display_model):
    choices = chat_obj.get("choices") if isinstance(chat_obj, dict) else None
    choice = choices[0] if isinstance(choices, list) and choices else {}
    msg = choice.get("message") or {}
    content = []

    if isinstance(msg.get("content"), str) and msg["content"]:
        content.append({"type": "text", "text": msg["content"]})
    tool_calls = msg.get("tool_calls") or []
    for tc in tool_calls:
        args = safe_json_parse((tc.get("function") or {}).get("arguments"))
        content.append({
            "type": "tool_use",
            "id": tc.get("id") or _new_tool_use_id(),
            "name": (tc.get("function") or {}).get("name") or "",
            "input": args if args is not None else {},
        })
    if not content:
        content.append({"type": "text", "text": ""})

    return {
        "id": (chat_obj.get("id") if isinstance(chat_obj, dict) else None) or _new_message_id(),
        "type": "message",
        "role": "assistant",
        "model": display_model or (chat_obj.get("model") if isinstance(chat_obj, dict) else "") or "",
        "content": content,
        "stop_reason": _map_stop_reason(choice.get("finish_reason"), bool(tool_calls)),
        "stop_sequence": None,
        "usage": _map_usage(chat_obj.get("usage") if isinstance(chat_obj, dict) else None)
        or {"input_tokens": 0, "output_tokens": 0},
    }


def chat_error_to_anthropic(obj):
    """OpenAI error body -> Anthropic error envelope, so Claude Code understands it."""
    e = (obj.get("error") or obj) if isinstance(obj, dict) else {}
    return {
        "type": "error",
        "error": {
            "type": e.get("type") or "api_error",
            "message": e.get("message") or (obj if isinstance(obj, str) else "upstream error"),
        },
    }


# ----- SSE serialization ---------------------------------------------------
def _sse(event, data):
    """Anthropic SSE frames name the event type on an ``event:`` line AND repeat
    it inside the JSON ``data:`` payload -- Claude Code reads both."""
    return f"event: {event}\ndata: {json.dumps(data)}\n\n"


# ----- response (streaming): OpenAI Chat SSE -> Anthropic SSE ---------------
class ChatToAnthropicStream:
    """Translate a stream of OpenAI Chat chunks into the Anthropic event sequence.

    OpenAI per-chunk: choices[0].delta = { content?, tool_calls?[{index,id,function:{name,arguments}}] }
    Anthropic blocks: block 0 is usually the assistant text; each tool call is
                      its own block. A block must be opened (content_block_start),
                      streamed (content_block_delta) and closed (content_block_stop).

    Mapping rules:
      - first chunk           -> emit message_start
      - first text delta      -> open a text block, then stream text_delta's
      - first chunk of a tool -> close the open block, open a tool_use block
                                 (carrying id+name), then stream input_json_delta's
      - stream end            -> close the open block, emit message_delta (with
                                 stop_reason + usage) then message_stop

    ``feed(chunk)`` and ``end()`` each RETURN a list of ready-to-write SSE strings.
    """

    def __init__(self, display_model):
        self.display_model = display_model or ""
        self.started = False
        self.next_index = 0       # next Anthropic block index to hand out
        self.open_index = None    # Anthropic index of the open block (or None)
        self.text_index = None    # Anthropic index of the (single) text block
        self.tools = {}           # OpenAI tool index -> { anthropicIndex }
        self.finish_reason = None
        self.usage = None
        self.out = []

    def _emit(self, event, data):
        self.out.append(_sse(event, data))

    def _flush(self):
        o = self.out
        self.out = []
        return o

    def _ensure_started(self):
        if self.started:
            return
        self.started = True
        self._emit("message_start", {
            "type": "message_start",
            "message": {
                "id": _new_message_id(),
                "type": "message",
                "role": "assistant",
                "model": self.display_model,
                "content": [],
                "stop_reason": None,
                "stop_sequence": None,
                "usage": {"input_tokens": 0, "output_tokens": 0},
            },
        })

    def _close_open(self):
        if self.open_index is not None:
            self._emit("content_block_stop", {"type": "content_block_stop", "index": self.open_index})
            if self.text_index == self.open_index:
                self.text_index = None
            self.open_index = None

    def feed(self, chunk):
        self._ensure_started()
        if chunk.get("usage"):
            self.usage = chunk["usage"]
        choices = chunk.get("choices")
        choice = choices[0] if isinstance(choices, list) and choices else None
        if not choice:
            return self._flush()
        if choice.get("finish_reason"):
            self.finish_reason = choice["finish_reason"]
        delta = choice.get("delta") or {}

        # --- text ---
        if isinstance(delta.get("content"), str) and delta["content"]:
            if self.open_index is None or self.open_index != self.text_index:
                self._close_open()
                self.text_index = self.next_index
                self.next_index += 1
                self.open_index = self.text_index
                self._emit("content_block_start", {
                    "type": "content_block_start",
                    "index": self.text_index,
                    "content_block": {"type": "text", "text": ""},
                })
            self._emit("content_block_delta", {
                "type": "content_block_delta",
                "index": self.text_index,
                "delta": {"type": "text_delta", "text": delta["content"]},
            })

        # --- tool calls ---
        for tc in (delta.get("tool_calls") or []):
            oidx = tc.get("index", 0)
            slot = self.tools.get(oidx)
            if slot is None:
                # First fragment of a new tool call: close the open block and
                # open a tool_use block carrying id + name (args stream next).
                self._close_open()
                aidx = self.next_index
                self.next_index += 1
                slot = {"anthropicIndex": aidx}
                self.tools[oidx] = slot
                self.open_index = aidx
                fn = tc.get("function") or {}
                self._emit("content_block_start", {
                    "type": "content_block_start",
                    "index": aidx,
                    "content_block": {
                        "type": "tool_use",
                        "id": tc.get("id") or _new_tool_use_id(),
                        "name": fn.get("name") or "",
                        "input": {},
                    },
                })
            arg_frag = (tc.get("function") or {}).get("arguments")
            if arg_frag:
                self._emit("content_block_delta", {
                    "type": "content_block_delta",
                    "index": slot["anthropicIndex"],
                    "delta": {"type": "input_json_delta", "partial_json": arg_frag},
                })

        return self._flush()

    def end(self):
        self._ensure_started()  # handle an empty stream gracefully
        self._close_open()
        self._emit("message_delta", {
            "type": "message_delta",
            "delta": {
                "stop_reason": _map_stop_reason(self.finish_reason, len(self.tools) > 0),
                "stop_sequence": None,
            },
            "usage": _map_usage(self.usage) or {"output_tokens": 0},
        })
        self._emit("message_stop", {"type": "message_stop"})
        return self._flush()


def anthropic_message_to_sse(anth_obj):
    """Build the full Anthropic SSE byte sequence from a COMPLETE chat completion.

    Used when the client asked for a stream but the vendor answered with a single
    JSON body -- we still owe the client an event stream.
    """
    frames = []
    frames.append(_sse("message_start", {
        "type": "message_start",
        "message": {
            "id": anth_obj["id"],
            "type": "message",
            "role": "assistant",
            "model": anth_obj["model"],
            "content": [],
            "stop_reason": None,
            "stop_sequence": None,
            "usage": {"input_tokens": (anth_obj.get("usage") or {}).get("input_tokens", 0), "output_tokens": 0},
        },
    }))
    for index, block in enumerate(anth_obj["content"]):
        if block.get("type") == "text":
            frames.append(_sse("content_block_start", {
                "type": "content_block_start", "index": index,
                "content_block": {"type": "text", "text": ""},
            }))
            frames.append(_sse("content_block_delta", {
                "type": "content_block_delta", "index": index,
                "delta": {"type": "text_delta", "text": block.get("text", "")},
            }))
        elif block.get("type") == "tool_use":
            frames.append(_sse("content_block_start", {
                "type": "content_block_start", "index": index,
                "content_block": {"type": "tool_use", "id": block.get("id"),
                                  "name": block.get("name"), "input": {}},
            }))
            frames.append(_sse("content_block_delta", {
                "type": "content_block_delta", "index": index,
                "delta": {"type": "input_json_delta", "partial_json": json.dumps(block.get("input") or {})},
            }))
        frames.append(_sse("content_block_stop", {"type": "content_block_stop", "index": index}))
    frames.append(_sse("message_delta", {
        "type": "message_delta",
        "delta": {"stop_reason": anth_obj.get("stop_reason"), "stop_sequence": anth_obj.get("stop_sequence")},
        "usage": {"output_tokens": (anth_obj.get("usage") or {}).get("output_tokens", 0)},
    }))
    frames.append(_sse("message_stop", {"type": "message_stop"}))
    return frames


def anthropic_error_sse(obj):
    """An Anthropic streaming ``error`` event (used when the vendor errors but
    the client is in streaming mode)."""
    return _sse("error", chat_error_to_anthropic(obj))
