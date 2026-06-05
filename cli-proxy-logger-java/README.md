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

## 使用配置模板（Codex / Claude Code / opencode）

与 Node 版完全一致，只是本版代理 + UI **共用 8788**。把各 CLI 的 base URL 指向本地代理即可。**最关键的区别：Codex / opencode 的 base URL 带 `/v1`；Claude Code 的不带 `/v1`**（它自己会拼 `/v1/messages`，带了会变成 `/v1/v1/messages` 而 404）。

代理按路径区分上游：`/v1/responses` 与 `/v1/chat/completions` 走 `proxy.openai-upstream`，`/v1/messages` 走 `proxy.anthropic-upstream`（见本节末「启动代理时设置上游」）。

### Codex（`~/.codex/config.toml`）

**场景 A：自定义 provider（推荐，可与官方 OpenAI 配置共存）**
```toml
model = "gpt-5"
model_provider = "proxy"

[model_providers.proxy]
name = "local proxy"            # 必填，否则报 "provider name must not be empty"
base_url = "http://127.0.0.1:8788/v1"
wire_api = "responses"          # Codex 默认走 Responses；兼容 API 可设 "chat"
env_key = "OPENAI_API_KEY"      # key 走环境变量时需要；若用 auth.json 则删掉这行
```

**场景 B：直接改内置 openai provider 的 base URL（最省事）**
```toml
openai_base_url = "http://127.0.0.1:8788/v1"
```

**key 的两种提供方式（二选一）：**
- 环境变量：provider 块保留 `env_key = "OPENAI_API_KEY"`，启动前设好 `set OPENAI_API_KEY=<key>`（PowerShell：`$env:OPENAI_API_KEY="<key>"`）。
- `~/.codex/auth.json`：写 `{ "OPENAI_API_KEY": "<key>" }`，并**删掉** provider 块里的 `env_key`（否则 Codex 强制找环境变量，报 `Missing environment variable: OPENAI_API_KEY`）。

**wire 区别：** `wire_api = "responses"` → `POST /v1/responses`（Codex 默认，流式）；`wire_api = "chat"` → `POST /v1/chat/completions`。

### Claude Code（环境变量）

```bash
# base URL 不带 /v1！Claude Code 自己拼 /v1/messages
export ANTHROPIC_BASE_URL=http://127.0.0.1:8788
export ANTHROPIC_API_KEY=<key>          # 以 x-api-key 头发出，代理原样转发、落盘脱敏
# 指向非官方 host 时 MCP tool search 默认关闭，需要可开启：
# export ENABLE_TOOL_SEARCH=true

# 模型分三档：opus / sonnet / haiku。接第三方上游时建议显式指定，
# 否则别名会解析成 Anthropic 官方模型名，上游不一定认。
export ANTHROPIC_MODEL=<主模型>                       # 覆盖当前会话主模型
export ANTHROPIC_DEFAULT_OPUS_MODEL=<opus 档模型>     # /model 切到 opus 时解析到的模型
export ANTHROPIC_DEFAULT_SONNET_MODEL=<sonnet 档模型> # /model 切到 sonnet 时解析到的模型
export ANTHROPIC_DEFAULT_HAIKU_MODEL=<haiku 档模型>   # haiku 档 + 后台任务（标题/补全等）
claude
```
Windows 用 `$env:NAME="..."`（PowerShell）或 `set NAME=...`（cmd）。

模型说明：
- `ANTHROPIC_DEFAULT_{OPUS,SONNET,HAIKU}_MODEL` 分别控制三档别名解析到的真实模型；`ANTHROPIC_MODEL` 覆盖「当前主模型」（优先级高于 `model` 设置）。
- 旧版的 `ANTHROPIC_SMALL_FAST_MODEL` 已被 `ANTHROPIC_DEFAULT_HAIKU_MODEL` 取代（仍向后兼容，对应 haiku/后台档）。
- **后台任务**（生成会话标题等）默认走 haiku 档，所以即便你只用 sonnet，也建议把 `ANTHROPIC_DEFAULT_HAIKU_MODEL` 指到一个上游可用的小模型，否则后台请求可能报错。
- 实测（freemodel）：`ANTHROPIC_MODEL=claude-sonnet-4-6` + `ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-4-5-20251001` 可用。

### opencode（`opencode.json` 或 `~/.config/opencode/opencode.json`）

opencode 支持「每个 provider 自定义 `baseURL`」，底层就是本工具已覆盖的三种 wire 格式，base URL **带 `/v1`**。三种场景按 `npm` 包区分：

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    // 场景①：OpenAI 兼容 -> /v1/chat/completions -> chat wire
    "myproxy-chat": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Local proxy (chat)",
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<key>" },
      "models": { "gpt-5": { "name": "gpt-5 via proxy (chat)" } }
    },
    // 场景②：OpenAI Responses -> /v1/responses -> responses wire
    "myproxy-resp": {
      "npm": "@ai-sdk/openai",
      "name": "Local proxy (responses)",
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<key>" },
      "models": { "gpt-5": { "name": "gpt-5 via proxy (responses)" } }
    },
    // 场景③：Anthropic 模型 -> /v1/messages -> anthropic wire（覆盖内置 anthropic 的 baseURL）
    "anthropic": {
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<key>" }
    }
  }
}
```
跑：`opencode run -m myproxy-chat/gpt-5 "..."`（或 `myproxy-resp/...`、`anthropic/...`）。chat / responses 路径都抓到工具调用参数与请求/响应体；anthropic 路径也被正确路由落盘（若上游按 CLI 指纹放行——如只认 Claude Code——可能拒绝 opencode，属上游限制，与代理无关）。

### 启动代理时设置上游

本版上游用 `proxy.*` 配置（默认 `https://api.openai.com` / `https://api.anthropic.com`）。指向 freemodel 的两种写法：

```bash
# 命令行参数
mvn spring-boot:run -Dspring-boot.run.arguments="--proxy.openai-upstream=https://api.freemodel.dev --proxy.anthropic-upstream=https://cc.freemodel.dev"

# 或环境变量（Spring Boot relaxed binding）
PROXY_OPENAI_UPSTREAM=https://api.freemodel.dev PROXY_ANTHROPIC_UPSTREAM=https://cc.freemodel.dev mvn spring-boot:run
```
也可直接写进 `application.yml` 的 `proxy.openai-upstream` / `proxy.anthropic-upstream`。

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
| `web/ExchangeApiController` | `/api/exchanges` 查询接口 + `DELETE /api/exchanges` 清空内存列表 |
| `model/*` | 统一数据模型 `Exchange` / `NormalizedRequest` / `NormalizedResponse` / `ToolCall` |

## 工具调用解析点

| wire | 路径 | 解析点 |
|------|------|--------|
| `anthropic` | `/v1/messages` | SSE `content_block_start(tool_use)` + `input_json_delta`；非流式 `content[].tool_use` |
| `responses` | `/v1/responses` | SSE `response.output_item.added(function_call)` + `function_call_arguments.delta`；非流式 `output[].function_call` |
| `chat` | `/v1/chat/completions` | SSE `choices[].delta.tool_calls[]`（按 index 聚合）；非流式 `message.tool_calls[]` |

> 实现说明：转发时不带 `Accept-Encoding`，由 `HttpURLConnection` 自行协商并透明解压 gzip，
> 因此读到/转发的都是 identity 字节，解析无需再处理压缩。

## 日志：存在哪、怎么命名

- **目录**：由 `proxy.log-dir` 决定，**默认 `./logs`**。这是**相对路径**，相对的是「你启动 `mvn spring-boot:run`（或 `java -jar`）时所在的工作目录」——按上面「运行」的步骤是在 `cli-proxy-logger-java/` 里启动，所以默认就是 `cli-proxy-logger-java/logs/`。想固定位置就用绝对路径，例如 `mvn spring-boot:run -Dspring-boot.run.arguments="--proxy.log-dir=C:\proxy-logs"`，或环境变量 `PROXY_LOG_DIR=C:\proxy-logs`，或直接写进 `application.yml`。
- **文件名**：按天滚动，`YYYY-MM-DD.jsonl`（系统本地日期 `LocalDate.now()`），每天一个文件。
- **写入方式**：**追加**（`StandardOpenOption.APPEND`），每来一条请求就追加一行，进程重启不会清空，会继续往当天的文件追加。
- **内存 vs 磁盘**：UI 列表读的是**内存里最近 500 条**；磁盘 `.jsonl` 则是**全量持久**记录。两者独立。

### 「清空」按钮做什么

UI 顶部 refresh 旁边的 **「清空」** 按钮（带确认弹窗）只清空 **内存列表 / 当前视图**（底层是 `DELETE /api/exchanges` → `ExchangeRecorder.clear()`），**不会删除磁盘上的 `.jsonl` 文件**——磁盘日志是持久审计记录，故意保留。新开一个会话想让界面干净，点它即可。

**想彻底删除磁盘日志**：手动删文件即可。
```bash
rm cli-proxy-logger-java/logs/$(date +%F).jsonl   # 删当天
rm -rf cli-proxy-logger-java/logs                   # 全删（下次启动自动重建目录）
```
（Windows PowerShell：`Remove-Item .\logs\*.jsonl` 或 `Remove-Item -Recurse -Force .\logs`。）

日志每行一条 JSON（`<proxy.log-dir>/YYYY-MM-DD.jsonl`），字段与 Node/Python 版一致：`wire` / `method` / `url` / `resStatus` / `durationMs` / `reqHeaders`（脱敏）/ `requestBodyRaw` / `request` / `response`。
