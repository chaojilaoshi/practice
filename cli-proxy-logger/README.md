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

## 使用配置模板（Codex / Claude Code / opencode）

启动代理后（见上方「运行」），把各 CLI 的 base URL 指向本地代理即可。**最关键的区别：Codex / opencode 的 base URL 带 `/v1`；Claude Code 的不带 `/v1`**（它自己会拼 `/v1/messages`，带了会变成 `/v1/v1/messages` 而 404）。

代理按路径区分上游：`/v1/responses` 与 `/v1/chat/completions` 走 `OPENAI_UPSTREAM`，`/v1/messages` 走 `ANTHROPIC_UPSTREAM`。启动代理时按需设置这两个上游（见本节末「启动代理时设置上游」）。

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

Claude Code 走 Anthropic Messages 格式：`POST /v1/messages`（流式 SSE）。

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
跑：`opencode run -m myproxy-chat/gpt-5 "..."`（或 `myproxy-resp/...`、`anthropic/...`）。本工具实测：chat / responses 路径都抓到工具调用参数与请求/响应体；anthropic 路径也被正确路由落盘（若上游按 CLI 指纹放行——如只认 Claude Code——可能拒绝 opencode，属上游限制，与代理无关）。

### 启动代理时设置上游

| 用到的 CLI | 上游环境变量（默认值） |
|-----------|------------------------|
| Codex、opencode（chat / responses） | `OPENAI_UPSTREAM`（`https://api.openai.com`） |
| Claude Code、opencode（anthropic） | `ANTHROPIC_UPSTREAM`（`https://api.anthropic.com`） |

例（指向 freemodel）：
```bash
OPENAI_UPSTREAM=https://api.freemodel.dev ANTHROPIC_UPSTREAM=https://cc.freemodel.dev npm start
```

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
| `ANTHROPIC_COMPAT` | 关闭（透明直通） | 设为 `chat` 时开启「协议翻译」：把进来的 Anthropic `/v1/messages` 翻译成 OpenAI `/v1/chat/completions` 发往 `OPENAI_UPSTREAM`（见下一节） |
| `MODEL_MAP` | 空 | 模型映射表，JSON（`{"claude-sonnet-4-6":"gpt-4o"}`）或逗号分隔（`claude-sonnet-4-6=gpt-4o,claude-haiku-4-5=gpt-4o-mini`）；没命中就原样透传模型名 |
| `MODEL_MAP_FILE` | 空 | 模型映射 JSON 文件路径（优先于 `MODEL_MAP`） |

## 协议翻译：让只支持 `/v1/chat/completions` 的厂商也能跑 Claude Code

**场景**：有的第三方厂商/路由**只认 OpenAI `/v1/chat/completions`**，不支持 Anthropic `/v1/messages`。而 Claude Code（以及 opencode 的 anthropic provider）只会说 Anthropic 协议。开启**协议翻译**后，代理在中间做格式转换，Claude Code 端**完全无感**。

> 默认是**透明直通**（不翻译，原样转发）。翻译是 **opt-in**，只有设了 `ANTHROPIC_COMPAT=chat` 才开启，且只作用于 `/v1/messages`；其它路径（`/v1/responses`、`/v1/chat/completions`）仍透明转发。

**开启方式**
```bash
ANTHROPIC_COMPAT=chat \
OPENAI_UPSTREAM=https://only-chat-vendor.example.com \
MODEL_MAP='{"claude-sonnet-4-6":"gpt-4o","claude-haiku-4-5":"gpt-4o-mini"}' \
npm start
```
然后 Claude Code 照常配置（base URL 指向代理、`x-api-key` 带 key）即可，代理会自动把它翻译成 chat 请求发往上游。

**翻译都做了什么**
1. **请求**：Anthropic `/v1/messages` → OpenAI `/v1/chat/completions`：`system` → system 消息；content blocks（文本/图片）展开；`tool_use` → `tool_calls`、`tool_result` → `tool` 角色消息；`tools[].input_schema` → `function.parameters`；鉴权 `x-api-key: K` → `Authorization: Bearer K`。
2. **模型映射**：按 `MODEL_MAP`/`MODEL_MAP_FILE` 把进来的模型名换成上游模型名（正好覆盖 Claude Code 的 opus/sonnet/haiku 三档）；没命中就原样透传。
3. **响应（最难）**：把上游回来的 OpenAI chat **SSE 流**（`choices[].delta`、`delta.tool_calls[]` 按 index 聚合）**实时**翻译回 Anthropic 事件流（`message_start` / `content_block_start` / `content_block_delta`(`text_delta`、`input_json_delta`) / `content_block_stop` / `message_delta` / `message_stop`）；非流式则整包转一次，若客户端要的是流式还会把整包合成成 SSE 回放。`finish_reason` → `stop_reason`、`usage` 字段也做映射。
4. **落盘**：翻译类请求在 JSONL 里带一个 `translation` 字段（`{from, to, model, upstreamModel}`），方便排查。

> **模型映射文件示例**（`MODEL_MAP_FILE=./model-map.json`）：
> ```json
> { "claude-opus-4": "gpt-4o", "claude-sonnet-4-6": "gpt-4o", "claude-haiku-4-5": "gpt-4o-mini" }
> ```

## 内网打包与部署（离线）

本工具**零第三方依赖**（只用 Node 内置模块），所以内网部署很简单：把目录拷进去 + 装好 Node 运行时即可，**不需要 `npm install`、不需要联网**。

**步骤（推荐：拷目录 + 离线 Node 运行时）**
1. **准备 Node 运行时离线包**：在能联网的机器上从 nodejs.org 下载与内网 OS 匹配的免安装包（Windows 用 `node-v20.x.x-win-x64.zip`，Linux 用 `node-v20.x.x-linux-x64.tar.xz`），拷进内网解压，把其中的 `node`（Windows 是 `node.exe`）所在目录加入 `PATH`。要求 **Node >= 18**（本机实测 v20.19.0）。
2. **打包工程**：用一键打包脚本生成离线包（只含 `src/`、`public/`、`package.json`、README，**没有 `node_modules`**，因为本项目无第三方依赖）：
   ```bash
   bash scripts/package.sh                # Linux/macOS → dist/cli-proxy-logger-node.tar.gz
   # 或 Windows PowerShell：
   powershell -ExecutionPolicy Bypass -File scripts\package.ps1   # → dist\cli-proxy-logger-node.zip
   ```
   把 `dist/` 里的压缩包拷进内网解压即可（也可以直接拷整个目录）。
3. **运行**：
   ```bash
   cd cli-proxy-logger
   node src/index.js            # 等价于 npm start
   ```
   按需设置环境变量（同一条命令前缀，或先 export/set）：`PROXY_PORT` / `UI_PORT` / `LOG_DIR` / `OPENAI_UPSTREAM` / `ANTHROPIC_UPSTREAM`。
4. **常驻后台**（可选，仓库已带模板）：
   - **Linux（systemd）**：用 <code>deploy/cli-proxy-logger.service</code> 模板——改好里面的路径/端口/上游，`sudo cp` 到 `/etc/systemd/system/`，再 `sudo systemctl enable --now cli-proxy-logger`。日志看 `journalctl -u cli-proxy-logger -f`。
   - **Windows（nssm）**：用 <code>deploy/install-nssm.ps1</code>——装好 [nssm](https://nssm.cc/) 后，以管理员 PowerShell 运行该脚本即可注册成开机自启服务（卸载：`nssm remove cli-proxy-logger confirm`）。
   - 临时跑也行：Linux `nohup node src/index.js > proxy.out 2>&1 &`；Windows `start /b node src/index.js`。

**可选（进阶）：单文件可执行**
Node 20 支持 SEA（Single Executable Applications）把脚本+运行时打成一个 exe，免在内网装 Node；或用 `pkg`/`nexe`。这条本仓库未内置脚本，按需自行打包。

> **网络/安全**：代理与 UI 默认监听本机端口（proxy `:8788`、UI `:8789`）。由于 CLI 的 base URL 指向 `127.0.0.1`，**代理需与 CLI 部署在同一台机器**。不要把这两个端口暴露到内网其他机器（鉴权头虽落盘脱敏，但内存/转发链路上是明文）。

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

## 日志：存在哪、怎么命名

- **目录**：由 `LOG_DIR` 环境变量决定，**默认 `./logs`**。这是**相对路径**，相对的是「你启动 `npm start` 时所在的工作目录」——按上面「运行」的步骤是在 `cli-proxy-logger/` 里启动，所以默认就是 `cli-proxy-logger/logs/`。想固定位置就用绝对路径，例如 `LOG_DIR=C:\proxy-logs npm start`（PowerShell：`$env:LOG_DIR="C:\proxy-logs"; npm start`）。
- **文件名**：按天滚动，`YYYY-MM-DD.jsonl`（UTC 日期），每天一个文件。
- **写入方式**：**追加**（append），每来一条请求就追加一行，进程重启不会清空，会继续往当天的文件追加。
- **内存 vs 磁盘**：UI 列表读的是**内存里最近 500 条**；磁盘 `.jsonl` 则是**全量持久**记录。两者独立。

### 「清空」按钮做什么

UI 顶部 refresh 旁边的 **「清空」** 按钮（带确认弹窗）只清空 **内存列表 / 当前视图**（底层是 `DELETE /api/exchanges`），**不会删除磁盘上的 `.jsonl` 文件**——磁盘日志是持久审计记录，故意保留。新开一个会话想让界面干净，点它即可。

**想彻底删除磁盘日志**：手动删文件即可，例如删当天的：
```bash
rm cli-proxy-logger/logs/$(date -u +%F).jsonl      # 删当天
rm -rf cli-proxy-logger/logs                         # 全删（下次启动自动重建目录）
```
（Windows PowerShell：`Remove-Item .\logs\*.jsonl` 或 `Remove-Item -Recurse -Force .\logs`。）

## 日志格式

每行一条 JSON（`<LOG_DIR>/YYYY-MM-DD.jsonl`），关键字段：

- `wire` / `method` / `url` / `resStatus` / `durationMs`
- `reqHeaders`（脱敏后）/ `requestBodyRaw`
- `request`：归一化后的请求（`model` / `system` / `messages` / `tools`）
- `response`：归一化后的响应（`text` / `toolCalls[{id,name,args}]` / `stopReason` / `usage`）
