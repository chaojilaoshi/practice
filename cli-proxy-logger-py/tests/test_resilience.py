"""Tests for the resilience features: circuit breaker, provider failover, and
the Anthropic thinking rectifier. Mirror of the Node ``test/resilience.js``.

Mix of pure unit tests and end-to-end tests against programmable mock upstreams.
No API key needed. Run with:

    python -m tests.test_resilience      # from the cli-proxy-logger-py directory
    # or: python tests/test_resilience.py
"""

import http.client
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cli_proxy_logger.config import load_config  # noqa: E402
from cli_proxy_logger.proxy import start_proxy  # noqa: E402
from cli_proxy_logger.recorder import Recorder  # noqa: E402
from cli_proxy_logger.breaker import BreakerRegistry, STATES  # noqa: E402
from cli_proxy_logger.providers import (  # noqa: E402
    parse_providers,
    resolve_candidates,
    wire_to_group,
)
from cli_proxy_logger.rectifier import (  # noqa: E402
    detect_rectification,
    rectify_signature,
    rectify_budget,
)

ANTHROPIC_JSON = json.dumps({
    "id": "msg_1",
    "type": "message",
    "role": "assistant",
    "content": [{"type": "text", "text": "ok"}],
    "stop_reason": "end_turn",
    "usage": {"input_tokens": 1, "output_tokens": 1},
})

_passed = 0


def check(name, cond):
    global _passed
    assert cond, name
    print("  ok -", name)
    _passed += 1


def make_server(responder):
    """Programmable mock upstream. ``responder(body_obj, requests) -> (status,
    body_str)``. Records each request as {"url", "headers", "body"}."""
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
            status, payload = responder(obj, requests)
            data = payload.encode("utf-8") if isinstance(payload, str) else payload
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
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
    body = json.dumps(body_obj)
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    h = {"Content-Type": "application/json", **(headers or {})}
    conn.request("POST", path, body=body, headers=h)
    res = conn.getresponse()
    data = res.read().decode("utf-8")
    conn.close()
    return {"status": res.status, "body": data}


_LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp-logs-resilience")


def start_proxy_with(**overrides):
    config = load_config(proxyPort=0, uiPort=0, logDir=_LOG_DIR, **overrides)
    recorder = Recorder(config)
    proxy = start_proxy(config, recorder)
    time.sleep(0.1)
    return {"proxy": proxy, "recorder": recorder, "port": config["proxyPort"]}


def unit_tests():
    print("# breaker unit")
    # Node's clock is in ms; the Python breaker compares cooldownMs/1000 against
    # a seconds clock, so we drive `now` in seconds (cooldown 100ms = 0.1s).
    clock = {"t": 1.0}
    reg = BreakerRegistry({"failureThreshold": 2, "cooldownMs": 100, "halfOpenMax": 1},
                          now=lambda: clock["t"])
    check("breaker: starts allowed/closed", reg.can_request("a")["allowed"] is True)
    reg.record_failure("a", False)
    check("breaker: one failure still allowed", reg.can_request("a")["allowed"] is True)
    reg.record_failure("a", False)
    check("breaker: opens at threshold (denied)", reg.can_request("a")["allowed"] is False)
    check("breaker: snapshot shows open", reg.snapshot()["a"]["state"] == STATES["OPEN"])
    clock["t"] += 0.2  # past cooldown
    probe = reg.can_request("a")
    check("breaker: half-open grants one probe", probe["allowed"] is True and probe["halfOpen"] is True)
    check("breaker: half-open caps concurrent probes", reg.can_request("a")["allowed"] is False)
    reg.record_success("a", True)  # probe ok -> closed + permit released
    check("breaker: success closes",
          reg.snapshot()["a"]["state"] == STATES["CLOSED"] and reg.can_request("a")["allowed"] is True)
    # neutral must release a half-open permit without changing health.
    reg.record_failure("a", False)
    reg.record_failure("a", False)  # open
    clock["t"] += 0.2
    p2 = reg.can_request("a")
    reg.record_neutral("a", p2["halfOpen"])  # release permit, no health change
    check("breaker: neutral releases probe permit", reg.can_request("a")["allowed"] is True)

    print("# providers unit")
    pools = parse_providers('[{"id":"x","group":"openai","baseUrl":"http://a/"},{"wire":"messages","baseUrl":"http://b"}]')
    check("providers: openai pool parsed + trailing slash trimmed",
          len(pools["openai"]) == 1 and pools["openai"][0]["baseUrl"] == "http://a")
    check("providers: wire alias maps to anthropic group",
          len(pools["anthropic"]) == 1 and pools["anthropic"][0]["id"] == "anthropic-0")
    check("providers: wire_to_group maps responses->openai",
          wire_to_group("responses") == "openai" and wire_to_group("anthropic") == "anthropic")
    cands = resolve_candidates({"providers": {"pools": pools}, "upstream": {"openai": "http://u"}}, "chat")
    check("providers: resolve_candidates uses openai pool for chat wire",
          len(cands) == 1 and cands[0]["id"] == "x")
    fallback = resolve_candidates(
        {"providers": {"pools": {"anthropic": [], "openai": []}}, "upstream": {"anthropic": "http://legacy"}},
        "anthropic")
    check("providers: empty pool falls back to legacy upstream",
          len(fallback) == 1 and fallback[0]["baseUrl"] == "http://legacy")

    print("# rectifier unit")
    check("rectifier: detects signature error",
          detect_rectification(400, {"error": {"message": "thinking signature is invalid"}}) == "signature")
    check("rectifier: detects budget error",
          detect_rectification(400, {"error": {"message": "thinking.budget_tokens must be >= 1024"}}) == "budget")
    check("rectifier: ignores 5xx (failover instead)",
          detect_rectification(503, {"error": {"message": "signature"}}) is None)
    check("rectifier: respects disabled signature rule",
          detect_rectification(400, {"error": {"message": "bad signature"}},
                               {"signature": False, "budget": True}) is None)
    sig_body = {"messages": [{"role": "assistant", "content": [
        {"type": "thinking", "thinking": "x", "signature": "sig"},
        {"type": "text", "text": "hi", "signature": "sig2"}]}]}
    sig_out = rectify_signature(sig_body)
    check("rectifier: signature strips thinking blocks",
          len(sig_out["messages"][0]["content"]) == 1 and sig_out["messages"][0]["content"][0]["type"] == "text")
    check("rectifier: signature strips signature fields",
          "signature" not in sig_out["messages"][0]["content"][0])
    bud_out = rectify_budget({"max_tokens": 100})
    check("rectifier: budget enables thinking with valid budget",
          bud_out["thinking"]["type"] == "enabled" and bud_out["thinking"]["budget_tokens"] == 32000)
    check("rectifier: budget raises max_tokens above budget", bud_out["max_tokens"] == 64000)


def integration_tests():
    print("# failover e2e")
    p1 = make_server(lambda body, reqs: (503, json.dumps({"error": {"message": "overloaded"}})))
    p2 = make_server(lambda body, reqs: (200, ANTHROPIC_JSON))
    s = start_proxy_with(
        providers={"pools": parse_providers([
            {"id": "p1", "group": "anthropic", "baseUrl": p1["url"]},
            {"id": "p2", "group": "anthropic", "baseUrl": p2["url"]}])},
        breaker={"enabled": True, "failureThreshold": 5, "cooldownMs": 30000,
                 "halfOpenMax": 1, "failoverStatuses": {429, 500, 502, 503, 504}})
    r = post(s["port"], "/v1/messages", {"model": "claude-x", "messages": [{"role": "user", "content": "hi"}]},
             {"x-api-key": "k"})
    time.sleep(0.05)
    check("failover: client gets the 2nd provider 200", r["status"] == 200 and r["body"] == ANTHROPIC_JSON)
    check("failover: provider1 was tried once", len(p1["requests"]) == 1)
    check("failover: provider2 served the request", len(p2["requests"]) == 1)
    check("failover: breaker recorded p1 failure", s["proxy"].breakers.snapshot()["p1"]["failures"] >= 1)
    s["proxy"].shutdown(); p1["srv"].shutdown(); p2["srv"].shutdown()

    print("# breaker opens + skips e2e")
    b1 = make_server(lambda body, reqs: (500, json.dumps({"error": {"message": "boom"}})))
    b2 = make_server(lambda body, reqs: (200, ANTHROPIC_JSON))
    s2 = start_proxy_with(
        providers={"pools": parse_providers([
            {"id": "b1", "group": "anthropic", "baseUrl": b1["url"]},
            {"id": "b2", "group": "anthropic", "baseUrl": b2["url"]}])},
        breaker={"enabled": True, "failureThreshold": 2, "cooldownMs": 30000,
                 "halfOpenMax": 1, "failoverStatuses": {429, 500, 502, 503, 504}})
    for i in range(3):
        rr = post(s2["port"], "/v1/messages", {"model": "m", "messages": [{"role": "user", "content": "hi"}]},
                  {"x-api-key": "k"})
        check(f"breaker e2e: request {i + 1} still succeeds via b2", rr["status"] == 200)
        time.sleep(0.02)
    check("breaker e2e: b1 stopped being tried after opening (<=2 hits)", len(b1["requests"]) == 2)
    check("breaker e2e: b1 breaker is OPEN", s2["proxy"].breakers.snapshot()["b1"]["state"] == STATES["OPEN"])
    s2["proxy"].shutdown(); b1["srv"].shutdown(); b2["srv"].shutdown()

    print("# rectifier signature e2e")
    def sig_responder(body, reqs):
        msgs = body.get("messages")
        has_thinking = isinstance(msgs, list) and any(
            isinstance(m.get("content"), list) and any(
                b.get("type") in ("thinking", "redacted_thinking") for b in m["content"] if isinstance(b, dict))
            for m in msgs if isinstance(m, dict))
        if has_thinking:
            return (400, json.dumps({"error": {"message": "messages.0: thinking blocks have an invalid signature"}}))
        return (200, ANTHROPIC_JSON)
    sig = make_server(sig_responder)
    s3 = start_proxy_with(upstream={"anthropic": sig["url"], "openai": sig["url"]},
                          rectifier={"enabled": True, "signature": True, "budget": True})
    rr3 = post(s3["port"], "/v1/messages", {
        "model": "claude-x",
        "messages": [{"role": "assistant", "content": [
            {"type": "thinking", "thinking": "t", "signature": "abc"},
            {"type": "text", "text": "hi"}]}]}, {"x-api-key": "k"})
    time.sleep(0.05)
    check("rectify signature: client ends up with 200", rr3["status"] == 200 and rr3["body"] == ANTHROPIC_JSON)
    check("rectify signature: upstream hit twice (orig + rectified)", len(sig["requests"]) == 2)
    check("rectify signature: first attempt had thinking block",
          any(b.get("type") == "thinking" for b in sig["requests"][0]["body"]["messages"][0]["content"]))
    check("rectify signature: retried body had thinking stripped",
          not any(b.get("type") == "thinking" for b in sig["requests"][1]["body"]["messages"][0]["content"]))
    s3["proxy"].shutdown(); sig["srv"].shutdown()

    print("# rectifier budget e2e")
    def bud_responder(body, reqs):
        if len(reqs) == 1:
            return (400, json.dumps({"error": {"message": "thinking.budget_tokens: must be greater than or equal to 1024"}}))
        return (200, ANTHROPIC_JSON)
    bud = make_server(bud_responder)
    s4 = start_proxy_with(upstream={"anthropic": bud["url"], "openai": bud["url"]},
                          rectifier={"enabled": True, "signature": True, "budget": True})
    rr4 = post(s4["port"], "/v1/messages",
               {"model": "claude-x", "max_tokens": 100, "messages": [{"role": "user", "content": "hi"}]},
               {"x-api-key": "k"})
    time.sleep(0.05)
    check("rectify budget: client ends up with 200", rr4["status"] == 200)
    check("rectify budget: retried body has valid budget",
          bud["requests"][1]["body"].get("thinking") and bud["requests"][1]["body"]["thinking"]["budget_tokens"] == 32000)
    check("rectify budget: retried body raised max_tokens", bud["requests"][1]["body"]["max_tokens"] == 64000)
    s4["proxy"].shutdown(); bud["srv"].shutdown()

    print("# non-failover terminal error e2e")
    t1 = make_server(lambda body, reqs: (401, json.dumps({"error": {"message": "unauthorized"}})))
    t2 = make_server(lambda body, reqs: (200, ANTHROPIC_JSON))
    s5 = start_proxy_with(
        providers={"pools": parse_providers([
            {"id": "t1", "group": "anthropic", "baseUrl": t1["url"]},
            {"id": "t2", "group": "anthropic", "baseUrl": t2["url"]}])},
        breaker={"enabled": True, "failureThreshold": 5, "cooldownMs": 30000,
                 "halfOpenMax": 1, "failoverStatuses": {429, 500, 502, 503, 504}})
    rr5 = post(s5["port"], "/v1/messages", {"model": "m", "messages": [{"role": "user", "content": "hi"}]},
               {"x-api-key": "k"})
    time.sleep(0.05)
    check("terminal 401: returned to client as-is (no failover)", rr5["status"] == 401)
    check("terminal 401: second provider NOT tried", len(t2["requests"]) == 0)
    check("terminal 401: 401 not counted as breaker failure (neutral)",
          s5["proxy"].breakers.snapshot()["t1"]["failures"] == 0)
    s5["proxy"].shutdown(); t1["srv"].shutdown(); t2["srv"].shutdown()


def main():
    unit_tests()
    integration_tests()
    print(f"\nAll {_passed} resilience checks passed.")


if __name__ == "__main__":
    main()
