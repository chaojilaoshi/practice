"""Core reverse proxy.

Flow:
    1. Buffer the incoming request body (CLI request bodies are complete JSON).
    2. resolve_upstream() picks the real upstream + wire format from the path.
    3. Forward method/path/headers/body to the upstream over http(s).
    4. Stream the upstream response back to the client byte-for-byte (fidelity
       first -- the CLI must be unaffected), while teeing a decoded COPY into the
       matching parser to reconstruct text + tool calls.
    5. Record the normalized Exchange.
"""

import http.client
import json
import threading
import uuid
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from .model import safe_json_parse
from .parsers import PARSERS, openai_chat
from .sse import SSEParser
from .translate import (
    anthropic_request_to_chat,
    chat_response_to_anthropic,
    chat_error_to_anthropic,
    anthropic_message_to_sse,
    anthropic_error_sse,
    ChatToAnthropicStream,
)
from .upstream import resolve_upstream

# "Hop-by-hop" headers are meaningful only for a single transport connection
# (per RFC 7230 6.1) and must NOT be blindly relayed by a proxy. We also drop
# host/content-length here because we always recompute them for the new
# connection, and transfer-encoding because we re-frame the body ourselves.
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
}


def _redact_headers(headers, redact):
    """Mask credentials before they are written to disk. The *real* key is still
    forwarded to the upstream untouched -- only the logged copy is redacted, so
    your JSONL files never contain a usable API key.
    """
    out = {}
    for k, v in headers:
        lk = k.lower()
        if redact and lk in ("authorization", "x-api-key", "api-key"):
            s = str(v)
            # Keep a short prefix/suffix so you can tell two keys apart in logs
            # without exposing the secret (e.g. "Bearer...7912").
            out[k] = "***" if len(s) <= 12 else f"{s[:6]}...{s[-4:]}"
        else:
            out[k] = v
    return out


def _make_stream_decoder(content_encoding):
    """Return a callable ``feed(bytes) -> bytes`` that incrementally decodes a
    (possibly compressed) byte stream. Returns identity for unknown encodings so
    a decode failure can never break forwarding or logging.
    """
    enc = (content_encoding or "").lower()
    if enc == "gzip":
        obj = zlib.decompressobj(16 + zlib.MAX_WBITS)
    elif enc == "deflate":
        obj = zlib.decompressobj()
    elif enc == "br":
        try:
            import brotli  # optional; not in stdlib
            obj = brotli.Decompressor()

            def feed_br(chunk):
                try:
                    return obj.process(chunk)
                except Exception:
                    return b""
            return feed_br
        except Exception:
            return lambda chunk: b""  # cannot decode br -> skip parsing copy
    else:
        return lambda chunk: chunk

    def feed(chunk):
        try:
            return obj.decompress(chunk)
        except Exception:
            return b""
    return feed


def _truncate(buf, max_bytes):
    if len(buf) <= max_bytes:
        return buf.decode("utf-8", "replace")
    return buf[:max_bytes].decode("utf-8", "replace") + f"\n...[truncated {len(buf) - max_bytes} bytes]"


def _make_handler(config, recorder):
    class ProxyHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args):
            pass  # silence default stderr access log

        def _handle(self):
            started = datetime.now(timezone.utc)

            # --- Step 1: buffer the request body -----------------------------
            # CLI requests are a single complete JSON document, so reading the
            # whole Content-Length up front is simplest. (A general-purpose
            # proxy would stream this too, but here it keeps the code readable.)
            length = int(self.headers.get("content-length") or 0)
            req_body = self.rfile.read(length) if length else b""

            # --- Step 2: pick the real upstream + wire format ----------------
            # The path alone tells us who the client is: /v1/messages == Claude
            # Code (Anthropic), /v1/responses or /v1/chat/completions == Codex.
            lower_headers = {k.lower(): v for k, v in self.headers.items()}
            info = resolve_upstream(config, self.path, lower_headers)
            wire = info["wire"]

            # Compat mode: when ANTHROPIC_COMPAT=chat and the client speaks
            # Anthropic Messages, hand off to the TRANSLATING path (Anthropic ->
            # OpenAI Chat) instead of the transparent tee. This is the only case
            # where we rewrite both the request and the response.
            compat = config.get("compat") or {}
            if (compat.get("anthropicTo") == "chat" and wire == "anthropic"
                    and self.path.split("?")[0].startswith("/v1/messages")):
                self._handle_anthropic_to_chat(config, recorder, req_body, started)
                return

            parser = PARSERS[wire]

            split = urlsplit(info["baseUrl"])
            scheme = split.scheme or "https"
            host = split.hostname
            port = split.port or (443 if scheme == "https" else 80)
            netloc = split.netloc
            upstream_url = f"{scheme}://{netloc}{self.path}"

            # --- Step 3: build the upstream request headers ------------------
            # Copy the client's headers through verbatim (including the real
            # Authorization / x-api-key) so the upstream sees an identical
            # request -- this is why CLI-specific gating (e.g. cc.freemodel.dev
            # only answering real Claude Code headers) still works through us.
            out_headers = {}
            for k, v in self.headers.items():
                lk = k.lower()
                if lk in HOP_BY_HOP:
                    continue
                if lk == "accept-encoding":
                    # Normalize to encodings the standard library can decode, so
                    # the logged copy is always introspectable without extra
                    # dependencies (the upstream may otherwise pick brotli/zstd,
                    # which stdlib cannot decompress). The client still receives
                    # a valid, correctly-encoded response.
                    out_headers[k] = "gzip, deflate"
                    continue
                out_headers[k] = v
            out_headers["Host"] = netloc
            if req_body:
                out_headers["Content-Length"] = str(len(req_body))

            # Parse the request body now (it is plain JSON) into the normalized
            # shape. Wrapped in try/except: a parse bug must never stop us from
            # forwarding the request to the upstream.
            try:
                parsed_request = parser.parse_request(req_body.decode("utf-8", "replace"))
            except Exception:
                parsed_request = {"wire": wire, "model": None, "messages": [],
                                  "tools": [], "stream": False, "raw": None}

            exchange = {
                "id": str(uuid.uuid4()),
                "ts": started.isoformat().replace("+00:00", "Z"),
                "durationMs": 0,
                "wire": wire,
                "method": self.command,
                "url": upstream_url,
                "reqHeaders": _redact_headers(self.headers.items(), config["redactAuth"]),
                "requestBodyRaw": _truncate(req_body, config["maxBodyBytes"]),
                "request": parsed_request,
                "resStatus": 0,
                "resHeaders": {},
                "response": None,
                "error": None,
            }

            # --- Step 4: open the upstream connection and send the request ---
            # http.client is the stdlib's low-level HTTP/1.1 client. Unlike
            # urllib it lets us stream the response with .read(n), which is what
            # we need to relay an SSE stream chunk-by-chunk.
            if scheme == "https":
                conn = http.client.HTTPSConnection(host, port, timeout=600)
            else:
                conn = http.client.HTTPConnection(host, port, timeout=600)

            try:
                conn.request(self.command, self.path, body=req_body or None, headers=out_headers)
                upstream_res = conn.getresponse()
            except Exception as err:
                exchange["error"] = f"upstream request error: {err}"
                exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                recorder.record(exchange)
                self.send_response(502)
                self.send_header("Content-Type", "application/json")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b'{"error":{"type":"proxy_error","message":"upstream request failed"}}')
                self.close_connection = True
                conn.close()
                return

            exchange["resStatus"] = upstream_res.status
            res_header_pairs = upstream_res.getheaders()
            exchange["resHeaders"] = {k: v for k, v in res_header_pairs}

            content_type = upstream_res.getheader("content-type") or ""
            is_sse = "text/event-stream" in content_type
            content_encoding = upstream_res.getheader("content-encoding")

            # --- Step 5: relay status + headers back to the client -----------
            # We forward the upstream's status line and headers, minus the
            # framing headers (transfer-encoding/content-length). Because we set
            # "Connection: close", the response body is delimited by EOF -- a
            # valid HTTP/1.1 framing that avoids re-implementing chunked
            # encoding and never desyncs the client's parser.
            self.send_response_only(upstream_res.status, upstream_res.reason)
            for k, v in res_header_pairs:
                if k.lower() in HOP_BY_HOP:
                    continue
                self.send_header(k, v)
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

            # --- Step 6: stream the body two ways at once --------------------
            # For an SSE response we run an incremental SSE parser whose events
            # feed a per-wire "aggregator" that rebuilds text + tool calls. For
            # a plain JSON response we just buffer the decoded bytes and parse
            # once at the end.
            sse = SSEParser() if is_sse else None
            agg = parser.create_stream_aggregator() if is_sse else None
            if sse:
                sse.on("event", lambda e: agg["feed"](e))

            # The bytes on the wire may be gzip/deflate-compressed; this decoder
            # incrementally decompresses the COPY we parse. The bytes forwarded
            # to the client are never touched.
            decode = _make_stream_decoder(content_encoding)
            raw_copy = bytearray()

            def on_decoded(buf):
                if not buf:
                    return
                if sse:
                    sse.push(buf.decode("utf-8", "replace"))
                elif len(raw_copy) < config["maxBodyBytes"]:
                    raw_copy.extend(buf)

            # The core dual-path loop. Read a chunk from the upstream, write the
            # ORIGINAL bytes to the client first (fidelity: the CLI must be
            # unaffected even if our parser later throws), then tee a decoded
            # copy into the parser. If the client hangs up we keep draining the
            # upstream so the exchange is still fully logged.
            client_alive = True
            while True:
                chunk = upstream_res.read(65536)
                if not chunk:
                    break
                if client_alive:
                    try:
                        self.wfile.write(chunk)  # forward raw bytes FIRST
                        self.wfile.flush()       # flush so SSE arrives live
                    except (BrokenPipeError, ConnectionError, OSError):
                        client_alive = False
                on_decoded(decode(chunk))  # tee a decoded COPY into the parser

            # --- Step 7: finalize the normalized response --------------------
            try:
                if sse:
                    sse.flush()  # emit any trailing event still in the buffer
                    exchange["response"] = agg["result"]()
                else:
                    exchange["response"] = parser.parse_response(bytes(raw_copy).decode("utf-8", "replace"))
            except Exception as err:
                exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                        "usage": None, "raw": None, "parseError": str(err)}

            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
            recorder.record(exchange)
            conn.close()

        # =================================================================
        # Compat path: Anthropic /v1/messages -> OpenAI /v1/chat/completions
        #
        # Unlike _handle (transparent tee), this REWRITES both directions:
        #   request  : translate.anthropic_request_to_chat() -> POST /v1/chat/completions
        #   response : OpenAI Chat (SSE or JSON) -> Anthropic events to the client
        # The client (Claude Code) never knows the vendor speaks a different
        # protocol.
        # =================================================================
        def _handle_anthropic_to_chat(self, config, recorder, req_body, started):
            anth_body = safe_json_parse(req_body.decode("utf-8", "replace")) or {}
            want_stream = bool(anth_body.get("stream"))
            original_model = anth_body.get("model")
            model_map = (config.get("compat") or {}).get("modelMap") or {}

            # 1) Translate the request body and pick the mapped model.
            chat_body = anthropic_request_to_chat(anth_body, model_map)
            mapped_model = chat_body.get("model")
            chat_body_bytes = json.dumps(chat_body).encode("utf-8")

            # 2) Build the upstream request to the OpenAI-style vendor.
            split = urlsplit(config["upstream"]["openai"])
            scheme = split.scheme or "https"
            host = split.hostname
            port = split.port or (443 if scheme == "https" else 80)
            netloc = split.netloc
            path = "/v1/chat/completions"
            upstream_url = f"{scheme}://{netloc}{path}"

            # Copy client headers minus hop-by-hop, then fix up auth + content for
            # an OpenAI vendor: Claude Code authenticates with `x-api-key`, but
            # OpenAI-style vendors expect `Authorization: Bearer`. Translate that,
            # and drop Anthropic-only headers the vendor would not understand.
            drop = {"x-api-key", "anthropic-version", "anthropic-beta",
                    "anthropic-dangerous-direct-browser-access", "content-type"}
            out_headers = {}
            for k, v in self.headers.items():
                lk = k.lower()
                if lk in HOP_BY_HOP or lk in drop:
                    continue
                out_headers[k] = v
            auth = self.headers.get("authorization")
            api_key = self.headers.get("x-api-key")
            if auth:
                out_headers["Authorization"] = auth
            elif api_key:
                out_headers["Authorization"] = f"Bearer {api_key}"
            out_headers["Host"] = netloc
            out_headers["Content-Type"] = "application/json"
            out_headers["Content-Length"] = str(len(chat_body_bytes))

            # 3) Record the exchange as an Anthropic request (what the client
            # sent) with a translation marker; response is normalized from chat.
            try:
                parsed_request = PARSERS["anthropic"].parse_request(req_body.decode("utf-8", "replace"))
            except Exception:
                parsed_request = {"wire": "anthropic", "model": original_model, "messages": [],
                                  "tools": [], "stream": want_stream, "raw": None}
            exchange = {
                "id": str(uuid.uuid4()),
                "ts": started.isoformat().replace("+00:00", "Z"),
                "durationMs": 0,
                "wire": "anthropic",
                "method": self.command,
                "url": upstream_url,
                "reqHeaders": _redact_headers(self.headers.items(), config["redactAuth"]),
                "requestBodyRaw": _truncate(req_body, config["maxBodyBytes"]),
                "request": parsed_request,
                "translation": {"from": "anthropic", "to": "chat",
                                "model": original_model, "upstreamModel": mapped_model},
                "resStatus": 0,
                "resHeaders": {},
                "response": None,
                "error": None,
            }

            # 4) Open the upstream connection and send the translated request.
            if scheme == "https":
                conn = http.client.HTTPSConnection(host, port, timeout=600)
            else:
                conn = http.client.HTTPConnection(host, port, timeout=600)
            try:
                conn.request("POST", path, body=chat_body_bytes, headers=out_headers)
                upstream_res = conn.getresponse()
            except Exception as err:
                exchange["error"] = f"upstream request error: {err}"
                exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                recorder.record(exchange)
                body = json.dumps(chat_error_to_anthropic(
                    {"error": {"type": "proxy_error", "message": "upstream request failed"}})).encode("utf-8")
                self.send_response(502)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                self.close_connection = True
                conn.close()
                return

            status = upstream_res.status
            exchange["resStatus"] = status
            exchange["resHeaders"] = {k: v for k, v in upstream_res.getheaders()}
            content_type = upstream_res.getheader("content-type") or ""
            is_sse = "text/event-stream" in content_type
            decode = _make_stream_decoder(upstream_res.getheader("content-encoding"))

            if is_sse:
                # ---- streaming translation ----
                self.send_response_only(status, upstream_res.reason)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True

                translator = ChatToAnthropicStream(original_model or mapped_model)
                chat_agg = openai_chat.create_stream_aggregator()  # for logging
                sse = SSEParser()

                def on_event(evt):
                    raw = (evt.get("data") or "").strip()
                    if raw == "" or raw == "[DONE]":
                        return
                    data = safe_json_parse(raw)
                    if not data:
                        return
                    chat_agg["feed"](evt)  # normalized response for the log
                    for frame in translator.feed(data):
                        try:
                            self.wfile.write(frame.encode("utf-8"))
                            self.wfile.flush()
                        except (BrokenPipeError, ConnectionError, OSError):
                            pass

                sse.on("event", on_event)
                while True:
                    chunk = upstream_res.read(65536)
                    if not chunk:
                        break
                    decoded = decode(chunk)
                    if decoded:
                        sse.push(decoded.decode("utf-8", "replace"))
                sse.flush()
                for frame in translator.end():
                    try:
                        self.wfile.write(frame.encode("utf-8"))
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionError, OSError):
                        pass
                try:
                    exchange["response"] = chat_agg["result"]()
                except Exception as err:
                    exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                            "usage": None, "raw": None, "parseError": str(err)}
            else:
                # ---- buffered (non-SSE) translation ----
                # Covers a plain JSON chat completion and error bodies. We may
                # still owe the client an SSE stream (if it asked for one), in
                # which case we synthesize the Anthropic event sequence.
                raw_copy = bytearray()
                while True:
                    chunk = upstream_res.read(65536)
                    if not chunk:
                        break
                    decoded = decode(chunk)
                    if decoded:
                        raw_copy.extend(decoded)
                body_text = bytes(raw_copy).decode("utf-8", "replace")
                obj = safe_json_parse(body_text)
                is_error = status >= 400 or (isinstance(obj, dict) and obj.get("error"))

                if is_error:
                    anth_err = chat_error_to_anthropic(obj if obj is not None else body_text)
                    if want_stream:
                        frame = anthropic_error_sse(obj if obj is not None else body_text).encode("utf-8")
                        self.send_response_only(status)
                        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                        self.send_header("Connection", "close")
                        self.end_headers()
                        self.wfile.write(frame)
                    else:
                        out = json.dumps(anth_err).encode("utf-8")
                        self.send_response_only(status)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Content-Length", str(len(out)))
                        self.send_header("Connection", "close")
                        self.end_headers()
                        self.wfile.write(out)
                    exchange["error"] = anth_err["error"]["message"]
                else:
                    anth_obj = chat_response_to_anthropic(obj or {}, original_model or mapped_model)
                    if want_stream:
                        self.send_response_only(status)
                        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                        self.send_header("Cache-Control", "no-cache")
                        self.send_header("Connection", "close")
                        self.end_headers()
                        for frame in anthropic_message_to_sse(anth_obj):
                            self.wfile.write(frame.encode("utf-8"))
                    else:
                        out = json.dumps(anth_obj).encode("utf-8")
                        self.send_response_only(status)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Content-Length", str(len(out)))
                        self.send_header("Connection", "close")
                        self.end_headers()
                        self.wfile.write(out)
                self.close_connection = True
                try:
                    exchange["response"] = openai_chat.parse_response(body_text)
                except Exception as err:
                    exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                            "usage": None, "raw": None, "parseError": str(err)}

            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
            recorder.record(exchange)
            conn.close()

        # BaseHTTPRequestHandler dispatches by method name (do_GET, do_POST,
        # ...). We proxy every verb identically, so point them all at _handle.
        do_GET = _handle
        do_POST = _handle
        do_PUT = _handle
        do_PATCH = _handle
        do_DELETE = _handle
        do_HEAD = _handle
        do_OPTIONS = _handle

    return ProxyHandler


def start_proxy(config, recorder):
    handler = _make_handler(config, recorder)
    server = ThreadingHTTPServer(("127.0.0.1", config["proxyPort"]), handler)
    server.daemon_threads = True
    actual_port = server.server_address[1]
    config["proxyPort"] = actual_port
    print(f"[proxy] listening on http://127.0.0.1:{actual_port}")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server
