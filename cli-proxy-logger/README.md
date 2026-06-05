# cli-proxy-logger

本地反向代理，用来**拦截并记录 Claude Code / Codex 这类 CLI 发出的全部 LLM API 请求**——清楚地看到：

- 发了哪些请求（URL / method / headers / body）
- 响应是什么（status / headers / body，支持流式 SSE）
- 是否调用了工具、调用了哪个工具、参数是什么、工具返回了什么

不劫持 TLS、**不需要安装根证书**：利用 Claude Code / Codex 支持「自定义 base URL」的能力，把它们指向本地代理即可。代理把请求**原样转发**到真实上游，同时旁路解析、落盘、并提供网页查看。

```
Claude Code / Codex ──HTTP──▶ 本地代理 :8788 ──HTTPS──▶ api.anthropic.com / api.openai.com
                                   │
                                   ├─ 解析请求/响应 + 工具调用
                                   ├─ logs/YYYY-MM-DD.jsonl
                                   └─ Web UI :8789
```

## 运行

需要 Node.js >= 18（无第三方依赖）。

```bash
cd cli-proxy-logger
npm start
# [proxy] listening on http://127.0.0.1:8788
# [ui]    open http://127.0.0.1:8789
```

打开 http://127.0.0.1:8789 浏览抓到的请求。

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

## 接入 opencode（已实测可用）

opencode 也支持「每个 provider 自定义 `baseURL`」，底层走的就是本工具已支持的三种 wire 格式，所以**完全可以用这个代理拦截**。在 `opencode.json`（或 `~/.config/opencode/opencode.json`）里把 provider 的 `baseURL` 指到本地代理即可：

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    // OpenAI 兼容（/v1/chat/completions） -> 命中 chat wire
    "myproxy-chat": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Local proxy (chat)",
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<your-key>" },
      "models": { "gpt-5": { "name": "gpt-5 via proxy (chat)" } }
    },
    // OpenAI Responses（/v1/responses） -> 命中 responses wire
    "myproxy-resp": {
      "npm": "@ai-sdk/openai",
      "name": "Local proxy (responses)",
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<your-key>" },
      "models": { "gpt-5": { "name": "gpt-5 via proxy (responses)" } }
    },
    // Anthropic 模型（/v1/messages） -> 命中 anthropic wire（覆盖内置 anthropic 的 baseURL）
    "anthropic": {
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<your-key>" }
    }
  }
}
```

opencode 的 AI SDK client 会在 `baseURL` 后拼出 `/chat/completions`、`/responses` 或 `/messages`，正好命中代理按路径选 wire 的逻辑。本会话用 `opencode run -m <provider>/<model>` 实测：chat 与 responses 两条路径都成功抓到工具调用（如 `read`、`glob`）的参数与请求/响应体；anthropic 路径同样被正确路由并落盘（注意：若上游按特定 CLI 指纹放行——如只认 Claude Code——它可能拒绝 opencode，这属于上游限制，与代理无关）。

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

## 测试

```bash
npm test
```

`test/run.js` 会启动一个 mock 上游 + 代理，覆盖三种 wire 格式的流式/非流式工具调用解析，以及「客户端收到的字节与上游完全一致」的保真性校验（无需任何 API key）。

## 日志格式

每行一条 JSON（`logs/YYYY-MM-DD.jsonl`），关键字段：

- `wire` / `method` / `url` / `resStatus` / `durationMs`
- `reqHeaders`（脱敏后）/ `requestBodyRaw`
- `request`：归一化后的请求（`model` / `system` / `messages` / `tools`）
- `response`：归一化后的响应（`text` / `toolCalls[{id,name,args}]` / `stopReason` / `usage`）
