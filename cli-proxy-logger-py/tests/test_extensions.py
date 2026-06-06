"""Tests for the opt-in extensibility features (mirror of Node test/extensions.js):
  1. tool-name normalization (request/response/SSE + input repair)
  2. request filters/rules engine (set/delete header, JSON-path replace)
  3. outbound proxy (HTTP CONNECT + SOCKS5), zero-dependency

Mix of pure unit tests and end-to-end tests against local mock servers. No API
key needed. Run with:

    python tests/test_extensions.py      # from the cli-proxy-logger-py directory
"""

import http.client
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cli_proxy_logger.config import load_config  # noqa: E402
from cli_proxy_logger.proxy import start_proxy  # noqa: E402
from cli_proxy_logger.recorder import Recorder  # noqa: E402
from cli_proxy_logger.transform import (  # noqa: E402
    DEFAULT_TOOL_NAME_MAP,
    map_tool_name,
    normalize_request_tool_names,
    rewrite_response_tool_names,
    repair_tool_use_input,
    rewrite_stream_event_tool_name,
)
from cli_proxy_logger.filters import (  # noqa: E402
    parse_filters,
    apply_filters,
    summarize_filters,
)
from cli_proxy_logger.outbound import parse_proxy_url, create_outbound  # noqa: E402

_pass = 0


def check(name, cond):
    assert cond, name
    print("  ok -", name)
    global _pass
    _pass += 1


def eq(name, a, b):
    check(f"{name} (got {json.dumps(a)})", json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True))


# ---------------------------------------------------------------------------
# Local mock helpers
# ---------------------------------------------------------------------------
def make_server(responder):
    """Mock upstream. ``responder(body_obj, requests) -> (status, content_type,
    body_str)``. Records each request {"url","headers","body"}."""
    requests = []

    class Mock(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_POST(self):
            length = int(self.headers.get("content-length") or 0)
            raw = self.rfile.read(length).decode("utf-8") if length else ""
            try:
                obj = json.loads(raw) if raw else {}
            except ValueError:
                obj = {}
            requests.append({"url": self.path,
                             "headers": {k.lower(): v for k, v in self.headers.items()},
                             "body": obj})
            status, content_type, payload = responder(obj, requests)
            data = payload.encode("utf-8") if isinstance(payload, str) else payload
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Mock)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    port = srv.server_address[1]
    return {"srv": srv, "port": port, "url": f"http://127.0.0.1:{port}", "requests": requests}


def post(port, path, body_obj, headers=None):
    body = body_obj if isinstance(body_obj, str) else json.dumps(body_obj)
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    h = {"Content-Type": "application/json", **(headers or {})}
    conn.request("POST", path, body=body, headers=h)
    res = conn.getresponse()
    data = res.read().decode("utf-8")
    status = res.status
    conn.close()
    return {"status": status, "body": data}


def make_connect_proxy():
    """Minimal HTTP CONNECT forward proxy. Records the CONNECT targets."""
    seen = []
    lsock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    lsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    lsock.bind(("127.0.0.1", 0))
    lsock.listen(8)
    port = lsock.getsockname()[1]
    stop = {"v": False}

    def pump(a, b):
        try:
            while True:
                d = a.recv(65536)
                if not d:
                    break
                b.sendall(d)
        except OSError:
            pass
        finally:
            for s in (a, b):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def handle(cli):
        try:
            buf = b""
            while b"\r\n\r\n" not in buf:
                chunk = cli.recv(4096)
                if not chunk:
                    cli.close()
                    return
                buf += chunk
            request_line = buf.split(b"\r\n", 1)[0].decode("latin1")
            method, target, _ = request_line.split(" ", 2)
            if method != "CONNECT":
                cli.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                cli.close()
                return
            seen.append(target)
            host, sp = target.split(":")
            up = socket.create_connection((host, int(sp)), 10)
            cli.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            threading.Thread(target=pump, args=(cli, up), daemon=True).start()
            threading.Thread(target=pump, args=(up, cli), daemon=True).start()
        except OSError:
            try:
                cli.close()
            except OSError:
                pass

    def serve():
        while not stop["v"]:
            try:
                cli, _ = lsock.accept()
            except OSError:
                break
            threading.Thread(target=handle, args=(cli,), daemon=True).start()

    threading.Thread(target=serve, daemon=True).start()

    def close():
        stop["v"] = True
        try:
            lsock.close()
        except OSError:
            pass

    return {"port": port, "seen": seen, "close": close}


def make_socks5():
    """Minimal SOCKS5 (no-auth, CONNECT). Records target host:port."""
    seen = []
    lsock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    lsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    lsock.bind(("127.0.0.1", 0))
    lsock.listen(8)
    port = lsock.getsockname()[1]
    stop = {"v": False}

    def recv_exact(s, n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c:
                raise OSError("closed")
            b += c
        return b

    def pump(a, b):
        try:
            while True:
                d = a.recv(65536)
                if not d:
                    break
                b.sendall(d)
        except OSError:
            pass
        finally:
            for s in (a, b):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def handle(cli):
        try:
            ver, nmethods = recv_exact(cli, 2)
            recv_exact(cli, nmethods)  # methods
            cli.sendall(bytes([0x05, 0x00]))  # no-auth
            head = recv_exact(cli, 4)
            atyp = head[3]
            if atyp == 0x01:
                host = ".".join(str(x) for x in recv_exact(cli, 4))
            elif atyp == 0x03:
                ln = recv_exact(cli, 1)[0]
                host = recv_exact(cli, ln).decode()
            else:
                cli.close()
                return
            p = recv_exact(cli, 2)
            tport = (p[0] << 8) | p[1]
            seen.append(f"{host}:{tport}")
            up = socket.create_connection((host, tport), 10)
            cli.sendall(bytes([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]))
            threading.Thread(target=pump, args=(cli, up), daemon=True).start()
            threading.Thread(target=pump, args=(up, cli), daemon=True).start()
        except OSError:
            try:
                cli.close()
            except OSError:
                pass

    def serve():
        while not stop["v"]:
            try:
                cli, _ = lsock.accept()
            except OSError:
                break
            threading.Thread(target=handle, args=(cli,), daemon=True).start()

    threading.Thread(target=serve, daemon=True).start()

    def close():
        stop["v"] = True
        try:
            lsock.close()
        except OSError:
            pass

    return {"port": port, "seen": seen, "close": close}


_LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp-logs-ext")


def start_test_proxy(**overrides):
    config = load_config(proxyPort=0, uiPort=0, logDir=_LOG_DIR, **overrides)
    recorder = Recorder(config)
    proxy = start_proxy(config, recorder)
    time.sleep(0.1)
    return {"proxy": proxy, "recorder": recorder, "port": config["proxyPort"]}


def _tn(**kw):
    base = {"enabled": True, "request": True, "response": False, "repairInput": False,
            "map": DEFAULT_TOOL_NAME_MAP}
    base.update(kw)
    return {"toolName": base}


# ===========================================================================
def run():
    print("transform: tool-name normalization (unit)")
    eq("map_tool_name uses built-in map", map_tool_name("todowrite", DEFAULT_TOOL_NAME_MAP), "TodoWrite")
    eq("map_tool_name capitalizes unknown", map_tool_name("read", {}), "Read")
    eq("map_tool_name case-insensitive map hit", map_tool_name("WebFetch", DEFAULT_TOOL_NAME_MAP), "WebFetch")
    eq("map_tool_name leaves non-strings", map_tool_name(None, {}), None)
    eq("explicit map overrides", map_tool_name("foo", {"foo": "BarBaz"}), "BarBaz")

    req_body = {
        "tools": [{"name": "read"}, {"name": "todowrite"}, {"name": "AlreadyPascal"}],
        "messages": [
            {"role": "assistant", "content": [{"type": "tool_use", "name": "write", "id": "t1", "input": {}}]},
            {"role": "user", "content": [{"type": "text", "text": "hi"}]},
        ],
    }
    changed = normalize_request_tool_names(req_body, DEFAULT_TOOL_NAME_MAP)
    eq("request tools renamed", [t["name"] for t in req_body["tools"]], ["Read", "TodoWrite", "AlreadyPascal"])
    eq("history tool_use renamed", req_body["messages"][0]["content"][0]["name"], "Write")
    check("normalize_request reports changed count", changed == 3)

    res_body = {"content": [{"type": "tool_use", "name": "read",
                             "input": {"paths": '["a","b"]', "note": "plain", "obj": '{"k":1}'}}]}
    rewrite_response_tool_names(res_body, DEFAULT_TOOL_NAME_MAP)
    eq("response tool_use renamed", res_body["content"][0]["name"], "Read")
    repaired = repair_tool_use_input(res_body)
    eq("input array string repaired", res_body["content"][0]["input"]["paths"], ["a", "b"])
    eq("input object string repaired", res_body["content"][0]["input"]["obj"], {"k": 1})
    eq("plain string left alone", res_body["content"][0]["input"]["note"], "plain")
    check("repair reports count", repaired == 2)

    evt = {"type": "content_block_start", "content_block": {"type": "tool_use", "name": "webfetch"}}
    did = rewrite_stream_event_tool_name(evt, DEFAULT_TOOL_NAME_MAP)
    eq("SSE content_block_start renamed", evt["content_block"]["name"], "WebFetch")
    check("SSE rewrite returns true on change", did is True)
    evt2 = {"type": "content_block_delta", "delta": {}}
    check("SSE non-start untouched", rewrite_stream_event_tool_name(evt2, {}) is False)

    print("filters: rules engine (unit)")
    filters = parse_filters(json.dumps([
        {"name": "beta", "action": "set_header", "target": "anthropic-beta", "value": "ctx-1m", "priority": 10},
        {"name": "think-type", "action": "json_set", "target": "thinking.type", "value": "adaptive", "priority": 1},
        {"name": "budget", "action": "json_set", "target": "thinking.budget_tokens", "value": "1024", "priority": 2},
        {"name": "disabled", "action": "set_header", "target": "x-no", "value": "1", "enabled": False, "priority": 20},
        {"name": "scoped", "action": "set_header", "target": "x-scoped", "value": "yes", "scope": "provider:vendorA", "priority": 30},
        {"name": "bad", "action": "nonsense", "target": "x"},
    ]))
    check("parse_filters drops invalid action", next((f for f in filters if f["name"] == "bad"), None) is None)
    eq("parse_filters sorts by priority", [f["name"] for f in filters],
       ["think-type", "budget", "beta", "disabled", "scoped"])

    headers = {}
    body = {}
    res = apply_filters(filters, {"providerId": None, "headers": headers, "body": body})
    eq("set_header applied (lowercased)", headers["anthropic-beta"], "ctx-1m")
    eq("json_set string value", body["thinking"]["type"], "adaptive")
    eq("json_set numeric coercion", body["thinking"]["budget_tokens"], 1024)
    check("disabled rule skipped", "x-no" not in headers)
    check("out-of-scope provider rule skipped", "x-scoped" not in headers)
    check("applied list excludes disabled/scoped",
          "disabled" not in res["applied"] and "scoped" not in res["applied"])

    headers2 = {}
    body2 = {}
    apply_filters(filters, {"providerId": "vendorA", "headers": headers2, "body": body2})
    eq("in-scope provider rule applied", headers2["x-scoped"], "yes")

    fdel = parse_filters([
        {"name": "del-h", "action": "delete_header", "target": "X-Remove"},
        {"name": "del-b", "action": "json_delete", "target": "metadata.user_id"},
    ])
    h3 = {"x-remove": "gone", "keep": "yes"}
    b3 = {"metadata": {"user_id": "u1", "session": "s1"}}
    apply_filters(fdel, {"providerId": None, "headers": h3, "body": b3})
    check("delete_header removes case-insensitively", "x-remove" not in h3 and h3["keep"] == "yes")
    check("json_delete removes nested key", "user_id" not in b3["metadata"] and b3["metadata"]["session"] == "s1")

    eq("summarize_filters shape", summarize_filters(fdel)[0],
       {"name": "del-h", "enabled": True, "priority": 0, "scope": "all",
        "action": "delete_header", "target": "X-Remove"})
    eq("parse_filters bad JSON -> []", parse_filters("{not json"), [])

    print("outbound: proxy URL parsing (unit)")
    eq("http proxy", parse_proxy_url("http://h:8080"),
       {"kind": "http", "hostname": "h", "port": 8080, "username": "", "password": ""})
    eq("https proxy default port", parse_proxy_url("https://h"),
       {"kind": "https", "hostname": "h", "port": 443, "username": "", "password": ""})
    eq("socks5 default port", parse_proxy_url("socks5://h"),
       {"kind": "socks5", "hostname": "h", "port": 1080, "username": "", "password": ""})
    eq("socks alias", parse_proxy_url("socks://h:1081")["kind"], "socks5")
    eq("socks5h alias", parse_proxy_url("socks5h://h")["kind"], "socks5")
    eq("auth parsed", parse_proxy_url("http://u:p@h:3128"),
       {"kind": "http", "hostname": "h", "port": 3128, "username": "u", "password": "p"})
    check("empty -> None", parse_proxy_url("") is None)
    check("unknown scheme -> None", parse_proxy_url("ftp://h") is None)
    check("create_outbound None when unset", create_outbound("") is None)

    # =======================================================================
    print("integration: tool-name request normalization (e2e)")
    up = make_server(lambda obj, reqs: (200, "application/json", json.dumps(
        {"id": "m", "type": "message", "role": "assistant",
         "content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn", "usage": {}})))
    t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]},
                         transform=_tn(request=True, response=False, repairInput=False))
    post(t["port"], "/v1/messages", {
        "model": "claude", "stream": False,
        "tools": [{"name": "read"}, {"name": "todowrite"}],
        "messages": [{"role": "assistant", "content": [{"type": "tool_use", "name": "write", "id": "t", "input": {}}]}],
    })
    got = up["requests"][-1]["body"]
    eq("upstream got PascalCase tool names", [x["name"] for x in got["tools"]], ["Read", "TodoWrite"])
    eq("upstream got PascalCase history tool_use", got["messages"][0]["content"][0]["name"], "Write")
    t["proxy"].shutdown(); up["srv"].shutdown()

    print("integration: tool-name response rewrite + input repair (e2e, JSON)")
    up = make_server(lambda obj, reqs: (200, "application/json", json.dumps(
        {"id": "m", "type": "message", "role": "assistant",
         "content": [{"type": "tool_use", "name": "read", "id": "tu", "input": {"paths": '["x.txt","y.txt"]'}}],
         "stop_reason": "tool_use", "usage": {}})))
    t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]},
                         transform=_tn(request=True, response=True, repairInput=True))
    r = post(t["port"], "/v1/messages", {"model": "claude", "stream": False, "messages": []})
    cbody = json.loads(r["body"])
    eq("client got PascalCase response name", cbody["content"][0]["name"], "Read")
    eq("client got repaired array input", cbody["content"][0]["input"]["paths"], ["x.txt", "y.txt"])
    t["proxy"].shutdown(); up["srv"].shutdown()

    print("integration: tool-name SSE response rewrite (e2e)")
    sse = "".join([
        'event: message_start\ndata: {"type":"message_start","message":{"id":"m"}}\n\n',
        'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu","name":"webfetch","input":{}}}\n\n',
        'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n',
        'event: message_stop\ndata: {"type":"message_stop"}\n\n',
    ])
    up = make_server(lambda obj, reqs: (200, "text/event-stream", sse))
    t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]},
                         transform=_tn(request=True, response=True, repairInput=True))
    r = post(t["port"], "/v1/messages", {"model": "claude", "stream": True, "messages": []})
    check("SSE name rewritten to WebFetch", '"name":"WebFetch"' in r["body"])
    check("SSE no longer carries lowercase webfetch name", '"name":"webfetch"' not in r["body"])
    check("SSE preserves other events", "message_start" in r["body"] and "message_stop" in r["body"])
    t["proxy"].shutdown(); up["srv"].shutdown()

    print("integration: filters mutate outbound request (e2e)")
    up = make_server(lambda obj, reqs: (200, "application/json", json.dumps(
        {"id": "m", "type": "message", "role": "assistant", "content": [],
         "stop_reason": "end_turn", "usage": {}})))
    t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]},
                         filters=parse_filters([
                             {"name": "beta", "action": "set_header", "target": "anthropic-beta", "value": "context-1m-2025"},
                             {"name": "think", "action": "json_set", "target": "thinking.type", "value": "adaptive"},
                             {"name": "budget", "action": "json_set", "target": "thinking.budget_tokens", "value": "1024"},
                             {"name": "drop", "action": "delete_header", "target": "x-drop-me"},
                         ]))
    post(t["port"], "/v1/messages", {"model": "claude", "stream": False, "messages": []},
         {"x-drop-me": "should-be-gone"})
    last = up["requests"][-1]
    eq("upstream received injected header", last["headers"]["anthropic-beta"], "context-1m-2025")
    eq("upstream body thinking.type set", last["body"]["thinking"]["type"], "adaptive")
    eq("upstream body budget numeric", last["body"]["thinking"]["budget_tokens"], 1024)
    check("upstream did NOT receive dropped header", "x-drop-me" not in last["headers"])
    t["proxy"].shutdown(); up["srv"].shutdown()

    print("integration: outbound proxy HTTP CONNECT (e2e)")
    up = make_server(lambda obj, reqs: (200, "application/json", json.dumps({"ok": True})))
    proxy = make_connect_proxy()
    t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]},
                         outbound=create_outbound(f"http://127.0.0.1:{proxy['port']}"))
    r = post(t["port"], "/v1/messages", {"model": "claude", "messages": []})
    check("request succeeded through http proxy", r["status"] == 200)
    check("http proxy tunneled to upstream", any(s.endswith(f":{up['port']}") for s in proxy["seen"]))
    check("upstream actually received request", len(up["requests"]) == 1)
    t["proxy"].shutdown(); up["srv"].shutdown(); proxy["close"]()

    print("integration: outbound proxy SOCKS5 (e2e)")
    up = make_server(lambda obj, reqs: (200, "application/json", json.dumps({"ok": True})))
    socks = make_socks5()
    t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]},
                         outbound=create_outbound(f"socks5://127.0.0.1:{socks['port']}"))
    r = post(t["port"], "/v1/messages", {"model": "claude", "messages": []})
    check("request succeeded through socks5 proxy", r["status"] == 200)
    check("socks5 connected to upstream", any(s.endswith(f":{up['port']}") for s in socks["seen"]))
    check("upstream received request via socks5", len(up["requests"]) == 1)
    t["proxy"].shutdown(); up["srv"].shutdown(); socks["close"]()

    print("integration: default path unchanged when nothing enabled (e2e)")
    up = make_server(lambda obj, reqs: (200, "application/json", json.dumps(
        {"id": "m", "type": "message",
         "content": [{"type": "tool_use", "name": "read", "input": {"paths": '["a"]'}}],
         "stop_reason": "tool_use", "usage": {}})))
    t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]})
    post(t["port"], "/v1/messages", {"model": "claude", "tools": [{"name": "read"}], "messages": []})
    r = post(t["port"], "/v1/messages", {"model": "claude", "messages": []})
    eq("default forwards lowercase tool name untouched", up["requests"][0]["body"]["tools"][0]["name"], "read")
    check("default leaves response bytes verbatim (string input kept)",
          json.loads(r["body"])["content"][0]["input"]["paths"] == '["a"]')
    t["proxy"].shutdown(); up["srv"].shutdown()

    print(f"\nextensions: {_pass} checks passed")


if __name__ == "__main__":
    run()
