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
import threading
import uuid
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from .parsers import PARSERS
from .sse import SSEParser
from .upstream import resolve_upstream

HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
}


def _redact_headers(headers, redact):
    out = {}
    for k, v in headers:
        lk = k.lower()
        if redact and lk in ("authorization", "x-api-key", "api-key"):
            s = str(v)
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
            length = int(self.headers.get("content-length") or 0)
            req_body = self.rfile.read(length) if length else b""

            lower_headers = {k.lower(): v for k, v in self.headers.items()}
            info = resolve_upstream(config, self.path, lower_headers)
            wire = info["wire"]
            parser = PARSERS[wire]

            split = urlsplit(info["baseUrl"])
            scheme = split.scheme or "https"
            host = split.hostname
            port = split.port or (443 if scheme == "https" else 80)
            netloc = split.netloc
            upstream_url = f"{scheme}://{netloc}{self.path}"

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

            # Forward status + headers to the client, dropping framing headers so
            # we can re-frame ourselves and never desync the client's parser.
            self.send_response_only(upstream_res.status, upstream_res.reason)
            for k, v in res_header_pairs:
                if k.lower() in HOP_BY_HOP:
                    continue
                self.send_header(k, v)
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

            sse = SSEParser() if is_sse else None
            agg = parser.create_stream_aggregator() if is_sse else None
            if sse:
                sse.on("event", lambda e: agg["feed"](e))

            decode = _make_stream_decoder(content_encoding)
            raw_copy = bytearray()

            def on_decoded(buf):
                if not buf:
                    return
                if sse:
                    sse.push(buf.decode("utf-8", "replace"))
                elif len(raw_copy) < config["maxBodyBytes"]:
                    raw_copy.extend(buf)

            client_alive = True
            while True:
                chunk = upstream_res.read(65536)
                if not chunk:
                    break
                if client_alive:
                    try:
                        self.wfile.write(chunk)  # fidelity: forward raw bytes first
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionError, OSError):
                        client_alive = False
                on_decoded(decode(chunk))  # tee a decoded COPY into the parser

            try:
                if sse:
                    sse.flush()
                    exchange["response"] = agg["result"]()
                else:
                    exchange["response"] = parser.parse_response(bytes(raw_copy).decode("utf-8", "replace"))
            except Exception as err:
                exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                        "usage": None, "raw": None, "parseError": str(err)}

            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
            recorder.record(exchange)
            conn.close()

        # Dispatch every HTTP method through the same handler.
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
