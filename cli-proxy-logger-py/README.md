# cli-proxy-logger (Python 学习版)

本地反向代理，用来**拦截并记录 Claude Code / Codex 这类 CLI 发出的全部 LLM API 请求**——清楚地看到：

- 发了哪些请求（URL / method / headers / body）
- 响应是什么（status / headers / body，支持流式 SSE）
- 是否调用了工具、调用了哪个工具、参数是什么

这是 Node.js 版（`../cli-proxy-logger`）的 **Python 学习移植**，结构与命名 1:1 对照，便于对比学习。**只用 Python 标准库**（`http.server` + `http.client` + `zlib` 等），零第三方依赖。

不劫持 TLS、**不需要安装根证书**：利用 Claude Code / Codex 支持「自定义 base URL」的能力，把它们指向本地代理即可。

```
Claude Code / Codex ──HTTP──▶ 本地代理 :8788 ──HTTPS──▶ api.anthropic.com / api.openai.com
                                   │
                                   ├─ 解析请求/响应 + 工具调用
                                   ├─ logs/YYYY-MM-DD.jsonl
                                   └─ Web UI :8789
```

## 运行

需要 Python >= 3.8（无第三方依赖）。

```bash
cd cli-proxy-logger-py
python -m cli_proxy_logger
# [proxy] listening on http://127.0.0.1:8788
# [ui]    open http://127.0.0.1:8789
```

打开 http://127.0.0.1:8789 浏览抓到的请求。

> 注：极少数嵌入式/精简版 Python（带 `pythonXY._pth` 的发行版）会忽略 `PYTHONPATH` 和当前目录，导致 `python -m cli_proxy_logger` 报 `No module named`。这种环境下可改用：
> ```bash
> python -c "import sys; sys.path.insert(0,'.'); from cli_proxy_logger.__main__ import main; main()"
> ```

## 接入 Claude Code

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8788
# 指向非官方 host 时 MCP tool search 默认关闭，需要可开启：
# export ENABLE_TOOL_SEARCH=true
claude
```

Claude Code 走 Anthropic Messages 格式：`POST /v1/messages`（流式 SSE）。

## 接入 Codex

编辑 `~/.codex/config.toml`：

```toml
# 方式一：直接改内置 openai provider 的 base URL
openai_base_url = "http://127.0.0.1:8788/v1"
```

或定义一个自定义 provider：

```toml
model = "gpt-5"
model_provider = "proxy"

[model_providers.proxy]
name = "local proxy"
base_url = "http://127.0.0.1:8788/v1"
env_key = "OPENAI_API_KEY"
wire_api = "responses"   # Codex 默认；也可设 "chat"
```

Codex 默认走 OpenAI Responses 格式：`POST /v1/responses`（流式）；chat 模式走 `POST /v1/chat/completions`。

## 接入 opencode（同样适用）

opencode 也支持「每个 provider 自定义 `baseURL`」，且底层走的就是本工具已支持的三种 wire 格式，所以**完全可以用这个代理拦截**。在 `opencode.json`（或 `~/.config/opencode/opencode.json`）里把 provider 的 `baseURL` 指到本地代理即可：

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    // OpenAI 兼容（/v1/chat/completions） -> 用 chat wire
    "myproxy": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Local proxy (chat)",
      "options": { "baseURL": "http://127.0.0.1:8788/v1" },
      "models": { "gpt-5": { "name": "gpt-5 via proxy" } }
    },
    // 若该 provider/模型走 /v1/responses，改用 "npm": "@ai-sdk/openai"
    // Anthropic 模型（/v1/messages）则覆盖内置 anthropic 的 baseURL：
    "anthropic": {
      "options": { "baseURL": "http://127.0.0.1:8788/v1" }
    }
  }
}
```

opencode 的 AI SDK client 会在 `baseURL` 后拼出 `/chat/completions`、`/responses` 或 `/messages`，正好命中代理按路径选 wire 的逻辑（见下表）。

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|------|------|------|
| `PROXY_PORT` | `8788` | 代理监听端口 |
| `UI_PORT` | `8789` | Web UI 端口 |
| `LOG_DIR` | `./logs` | JSONL 日志目录 |
| `REDACT_AUTH` | 开启 | 落盘时对 `x-api-key`/`authorization` 脱敏；设 `0` 关闭 |
| `ANTHROPIC_UPSTREAM` | `https://api.anthropic.com` | Anthropic 上游 |
| `OPENAI_UPSTREAM` | `https://api.openai.com` | OpenAI 上游 |
| `MAX_BODY_BYTES` | `2000000` | 单条 body 落盘上限，超出截断 |

## 工作原理（三种 wire 格式的工具调用解析点）

| wire | 触发 CLI | 请求路径 | 工具调用解析点 |
|------|----------|----------|----------------|
| `anthropic` | Claude Code | `/v1/messages` | 流式 `content_block_start(tool_use)` + `input_json_delta`；非流式 `content[].tool_use` |
| `responses` | Codex（默认） | `/v1/responses` | 流式 `response.output_item.added(function_call)` + `function_call_arguments.delta`；非流式 `output[].function_call` |
| `chat` | Codex（chat 模式）/ 兼容 API | `/v1/chat/completions` | 流式 `choices[].delta.tool_calls[]`（按 index 聚合）；非流式 `choices[].message.tool_calls[]` |

代理始终**先把上游字节原样转发给 CLI**，再把一份解码后的副本喂给解析器，因此解析报错不会影响 CLI 的正常使用。

### 与 Node 版的一处差异：压缩编码

Node 内置 Brotli 解压（`zlib.createBrotliDecompress`），而 Python 标准库**没有 brotli**。为保持零依赖，本版本把转发给上游的 `Accept-Encoding` 归一化为 `gzip, deflate`（标准库可解压），这样落盘的副本始终可解析；客户端收到的仍是一个合法、可正确解码的响应。若上游坚持返回 br/zstd，则解析副本会优雅降级为空（不影响转发与 CLI 使用）。

## 测试

```bash
cd cli-proxy-logger-py
python tests/test_proxy.py
# All 23 checks passed.
```

`tests/test_proxy.py` 会启动一个 mock 上游 + 代理，覆盖三种 wire 格式的流式/非流式工具调用解析，以及「客户端收到的字节与上游完全一致」的保真性校验（无需任何 API key）。

## 日志格式

每行一条 JSON（`logs/YYYY-MM-DD.jsonl`），关键字段：

- `wire` / `method` / `url` / `resStatus` / `durationMs`
- `reqHeaders`（脱敏后）/ `requestBodyRaw`
- `request`：归一化后的请求（`model` / `system` / `messages` / `tools`）
- `response`：归一化后的响应（`text` / `toolCalls[{id,name,args}]` / `stopReason` / `usage`）

## 目录结构（对照 Node 版）

| Python | Node | 说明 |
|--------|------|------|
| `cli_proxy_logger/__main__.py` | `src/index.js` | 入口：加载配置、起 proxy + UI |
| `cli_proxy_logger/config.py` | `src/config.js` | 环境变量配置 |
| `cli_proxy_logger/model.py` | `src/model.js` | 归一化数据模型 + 工具函数 |
| `cli_proxy_logger/sse.py` | `src/sse.js` | SSE 增量解析器 |
| `cli_proxy_logger/recorder.py` | `src/recorder.js` | JSONL 落盘 + 内存最近列表 |
| `cli_proxy_logger/upstream.py` | `src/upstream.js` | 按路径选上游 + wire |
| `cli_proxy_logger/proxy.py` | `src/proxy.js` | 反向代理核心（转发 + 旁路解析） |
| `cli_proxy_logger/ui_server.py` | `src/ui-server.js` | Web UI + JSON API |
| `cli_proxy_logger/parsers/anthropic.py` | `src/parsers/anthropic.js` | Anthropic Messages 解析 |
| `cli_proxy_logger/parsers/openai_responses.py` | `src/parsers/openaiResponses.js` | OpenAI Responses 解析 |
| `cli_proxy_logger/parsers/openai_chat.py` | `src/parsers/openaiChat.js` | OpenAI Chat 解析 |
| `public/index.html` | `public/index.html` | 同一套前端 |
| `tests/test_proxy.py` | `test/run.js` | mock 上游 + 断言 |
