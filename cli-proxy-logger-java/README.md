# cli-proxy-logger（Spring Boot 学习版）

与 [`../cli-proxy-logger`](../cli-proxy-logger)（Node 实际使用版）**功能等价**的 Java/Spring Boot 实现，用来学习「如何用 Servlet 阻塞式 I/O 做一个流式反向代理并解析 LLM 工具调用」。

本地反向代理，拦截并记录 Claude Code / Codex 的全部 LLM API 请求——请求/响应参数、以及调用了哪个工具、参数是什么。不劫持 TLS、不装根证书。

代理与 Web UI **共用一个端口（默认 8788）**：

```
Claude Code / Codex ──HTTP──▶ :8788  ──HTTPS──▶ api.anthropic.com / api.openai.com
                                │
                                ├─ /v1/**         代理 + 解析（ProxyController）
                                ├─ /api/exchanges 查询接口（ExchangeApiController）
                                └─ /              Web UI（static/index.html）
```

## 运行

需要 JDK 17、Maven。

```bash
cd cli-proxy-logger-java
mvn spring-boot:run
# 或： mvn -q package && java -jar target/cli-proxy-logger-1.0.0.jar
```

打开 http://127.0.0.1:8788/ 浏览抓到的请求。

## 接入

与 Node 版一致，只是端口都用 8788：

- Claude Code：`export ANTHROPIC_BASE_URL=http://127.0.0.1:8788`（走 `/v1/messages`）
- Codex（`~/.codex/config.toml`）：`openai_base_url = "http://127.0.0.1:8788/v1"`（走 `/v1/responses`，或 chat 模式 `/v1/chat/completions`）
- opencode（`opencode.json`）：把 provider 的 `options.baseURL` 指到 `http://127.0.0.1:8788/v1`。`@ai-sdk/openai-compatible` 命中 chat、`@ai-sdk/openai` 命中 responses、内置 anthropic 命中 `/v1/messages`。三种 wire 都已实测可被本代理拦截（详见 Node 版 README 的「接入 opencode」配置示例）。

## 配置（application.yml，前缀 `proxy.*`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `server.port` | `8788` | 代理 + UI 端口 |
| `proxy.anthropic-upstream` | `https://api.anthropic.com` | Anthropic 上游 |
| `proxy.openai-upstream` | `https://api.openai.com` | OpenAI 上游 |
| `proxy.log-dir` | `./logs` | JSONL 日志目录 |
| `proxy.redact-auth` | `true` | 落盘时脱敏 `x-api-key`/`authorization` |
| `proxy.max-body-bytes` | `2000000` | 单条 body 落盘上限 |

## 代码结构（控制/数据流顺序）

| 类 | 职责 |
|----|------|
| `proxy/UpstreamResolver` | 据请求路径选择上游 + wire 类型 |
| `proxy/ProxyController` | `/v1/**`：转发字节 + 旁路解析 + 记录 |
| `sse/SseParser` | 增量解析 SSE 事件 |
| `parser/WireParser` + `AnthropicParser` / `OpenAiResponsesParser` / `OpenAiChatParser` | 三种 wire 的请求/响应/工具调用解析 |
| `parser/StreamAggregator` | 流式聚合，重建文本 + 工具调用 |
| `recorder/ExchangeRecorder` | 内存最近列表 + 按天 JSONL 落盘 |
| `web/ExchangeApiController` | `/api/exchanges` 查询接口 |
| `model/*` | 统一数据模型 `Exchange` / `NormalizedRequest` / `NormalizedResponse` / `ToolCall` |

## 工具调用解析点

| wire | 路径 | 解析点 |
|------|------|--------|
| `anthropic` | `/v1/messages` | SSE `content_block_start(tool_use)` + `input_json_delta`；非流式 `content[].tool_use` |
| `responses` | `/v1/responses` | SSE `response.output_item.added(function_call)` + `function_call_arguments.delta`；非流式 `output[].function_call` |
| `chat` | `/v1/chat/completions` | SSE `choices[].delta.tool_calls[]`（按 index 聚合）；非流式 `message.tool_calls[]` |

> 实现说明：转发时不带 `Accept-Encoding`，由 `HttpURLConnection` 自行协商并透明解压 gzip，
> 因此读到/转发的都是 identity 字节，解析无需再处理压缩。
