"""Self-contained test: mock upstream + proxy, no API key needed.

Verifies fidelity (client receives upstream bytes unchanged) AND that the proxy
correctly parses tool calls for all three wire formats, streaming and
non-streaming. Run with:

    python -m tests.test_proxy      # from the cli-proxy-logger-py directory
    # or: python tests/test_proxy.py
"""

import http.client
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cli_proxy_logger.config import load_config  # noqa: E402
from cli_proxy_logger.proxy import start_proxy  # noqa: E402
from cli_proxy_logger.recorder import Recorder  # noqa: E402

ANTHROPIC_STREAM = "\n".join([
    "event: message_start",
    'data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}',
    "",
    "event: content_block_start",
    'data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}',
    "",
    "event: content_block_delta",
    'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me check the weather."}}',
    "",
    "event: content_block_stop",
    'data: {"type":"content_block_stop","index":0}',
    "",
    "event: content_block_start",
    'data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather"}}',
    "",
    "event: content_block_delta",
    'data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\": \\"San Fra"}}',
    "",
    "event: content_block_delta",
    'data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"ncisco\\"}"}}',
    "",
    "event: content_block_stop",
    'data: {"type":"content_block_stop","index":1}',
    "",
    "event: message_delta",
    'data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}',
    "",
    "event: message_stop",
    'data: {"type":"message_stop"}',
    "",
    "",
])

RESPONSES_STREAM = "\n".join([
    "event: response.created",
    'data: {"type":"response.created","response":{"id":"resp_1"}}',
    "",
    "event: response.output_item.added",
    'data: {"type":"response.output_item.added","item_id":"item_1","item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"run_shell"}}',
    "",
    "event: response.function_call_arguments.delta",
    'data: {"type":"response.function_call_arguments.delta","item_id":"item_1","delta":"{\\"cmd\\": \\"ls"}',
    "",
    "event: response.function_call_arguments.delta",
    'data: {"type":"response.function_call_arguments.delta","item_id":"item_1","delta":" -la\\"}"}',
    "",
    "event: response.function_call_arguments.done",
    'data: {"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{\\"cmd\\": \\"ls -la\\"}"}',
    "",
    "event: response.output_item.done",
    'data: {"type":"response.output_item.done","item_id":"item_1","item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"run_shell","arguments":"{\\"cmd\\": \\"ls -la\\"}"}}',
    "",
    "event: response.completed",
    'data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":5,"output_tokens":8}}}',
    "",
    "",
])

CHAT_STREAM = "\n".join([
    'data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Sure."}}]}',
    "",
    'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"search","arguments":"{\\"q\\":"}}]}}]}',
    "",
    'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"cats\\"}"}}]}}]}',
    "",
    'data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"total_tokens":42}}',
    "",
    "data: [DONE]",
    "",
    "",
])

ANTHROPIC_JSON = (
    '{"id":"msg_1","content":[{"type":"text","text":"hello"},'
    '{"type":"tool_use","id":"toolu_2","name":"lookup","input":{"key":"v"}}],'
    '"stop_reason":"tool_use","usage":{"input_tokens":3,"output_tokens":4}}'
)


def _mock_handler():
    class MockUpstream(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_POST(self):
            length = int(self.headers.get("content-length") or 0)
            body = self.rfile.read(length).decode("utf-8") if length else ""
            import json
            req = json.loads(body) if body else {}
            stream = bool(req.get("stream"))

            def send(payload, ctype):
                data = payload.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(data)
                self.close_connection = True

            if self.path.startswith("/v1/messages"):
                if stream:
                    send(ANTHROPIC_STREAM, "text/event-stream")
                else:
                    send(ANTHROPIC_JSON, "application/json")
            elif self.path.startswith("/v1/responses"):
                send(RESPONSES_STREAM, "text/event-stream")
            elif self.path.startswith("/v1/chat/completions"):
                send(CHAT_STREAM, "text/event-stream")
            else:
                self.send_response(404)
                self.send_header("Content-Length", "2")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b"no")
                self.close_connection = True

    return MockUpstream


def _post(port, path, body_obj, headers=None):
    import json
    body = json.dumps(body_obj)
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    h = {"Content-Type": "application/json", **(headers or {})}
    conn.request("POST", path, body=body, headers=h)
    res = conn.getresponse()
    data = res.read().decode("utf-8")
    conn.close()
    return {"status": res.status, "body": data}


def main():
    mock = ThreadingHTTPServer(("127.0.0.1", 0), _mock_handler())
    mock.daemon_threads = True
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    mock_port = mock.server_address[1]
    mock_url = f"http://127.0.0.1:{mock_port}"

    config = load_config(
        proxyPort=0,
        uiPort=0,
        logDir=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp-logs"),
        upstream={"anthropic": mock_url, "openai": mock_url},
    )
    recorder = Recorder(config)
    start_proxy(config, recorder)
    proxy_port = config["proxyPort"]
    time.sleep(0.1)

    passed = 0

    def check(name, cond):
        nonlocal passed
        assert cond, name
        print("  ok -", name)
        passed += 1

    # 1. Anthropic streaming
    r1 = _post(proxy_port, "/v1/messages",
               {"model": "claude-x", "stream": True, "tools": [{"name": "get_weather"}],
                "messages": [{"role": "user", "content": "weather?"}]},
               {"x-api-key": "sk-secret-1234567890", "anthropic-version": "2023-06-01"})
    time.sleep(0.05)
    check("anthropic stream: client receives raw SSE", r1["body"] == ANTHROPIC_STREAM)
    e1 = recorder.recent[0]
    check("anthropic stream: wire", e1["wire"] == "anthropic")
    check("anthropic stream: text reconstructed", e1["response"]["text"] == "Let me check the weather.")
    check("anthropic stream: one tool call", len(e1["response"]["toolCalls"]) == 1)
    check("anthropic stream: tool name", e1["response"]["toolCalls"][0]["name"] == "get_weather")
    check("anthropic stream: tool args parsed", e1["response"]["toolCalls"][0]["args"]["city"] == "San Francisco")
    check("anthropic stream: stop_reason", e1["response"]["stopReason"] == "tool_use")
    check("anthropic stream: request tools captured", e1["request"]["tools"][0]["name"] == "get_weather")
    check("anthropic stream: api key redacted", "..." in e1["reqHeaders"].get("x-api-key", ""))

    # 2. Anthropic non-streaming
    r2 = _post(proxy_port, "/v1/messages",
               {"model": "claude-x", "messages": [{"role": "user", "content": "hi"}]},
               {"x-api-key": "sk-2", "anthropic-version": "2023-06-01"})
    time.sleep(0.05)
    check("anthropic json: client receives raw json", r2["body"] == ANTHROPIC_JSON)
    e2 = recorder.recent[0]
    check("anthropic json: tool call parsed",
          e2["response"]["toolCalls"][0]["name"] == "lookup" and e2["response"]["toolCalls"][0]["args"]["key"] == "v")

    # 3. OpenAI Responses streaming
    r3 = _post(proxy_port, "/v1/responses",
               {"model": "gpt-5", "stream": True, "input": [{"role": "user", "content": "list files"}]},
               {"authorization": "Bearer sk-openai-secret"})
    time.sleep(0.05)
    check("responses stream: client receives raw SSE", r3["body"] == RESPONSES_STREAM)
    e3 = recorder.recent[0]
    check("responses stream: wire", e3["wire"] == "responses")
    check("responses stream: one tool call", len(e3["response"]["toolCalls"]) == 1)
    check("responses stream: tool name", e3["response"]["toolCalls"][0]["name"] == "run_shell")
    check("responses stream: tool args parsed", e3["response"]["toolCalls"][0]["args"]["cmd"] == "ls -la")
    check("responses stream: usage captured",
          e3["response"]["usage"] and e3["response"]["usage"]["output_tokens"] == 8)
    check("responses stream: auth redacted", "..." in e3["reqHeaders"].get("authorization", ""))

    # 4. OpenAI Chat streaming
    r4 = _post(proxy_port, "/v1/chat/completions",
               {"model": "gpt-4o", "stream": True, "messages": [{"role": "user", "content": "find cats"}]},
               {"authorization": "Bearer sk-x"})
    time.sleep(0.05)
    check("chat stream: client receives raw SSE", r4["body"] == CHAT_STREAM)
    e4 = recorder.recent[0]
    check("chat stream: wire", e4["wire"] == "chat")
    check("chat stream: tool name", e4["response"]["toolCalls"][0]["name"] == "search")
    check("chat stream: tool args parsed", e4["response"]["toolCalls"][0]["args"]["q"] == "cats")
    check("chat stream: finish_reason", e4["response"]["stopReason"] == "tool_calls")

    print(f"\nAll {passed} checks passed.")
    mock.shutdown()


if __name__ == "__main__":
    try:
        main()
    except AssertionError as err:
        print("TEST FAILED:", err)
        sys.exit(1)
    except Exception as err:  # noqa: BLE001
        print("TEST ERROR:", err)
        raise
