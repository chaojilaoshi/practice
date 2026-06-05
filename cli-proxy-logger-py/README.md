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

## 使用配置模板（Codex / Claude Code / opencode）

启动代理后（见上方「运行」），把各 CLI 的 base URL 指向本地代理即可。**最关键的区别：Codex / opencode 的 base URL 带 `/v1`；Claude Code 的不带 `/v1`**（它自己会拼 `/v1/messages`，带了会变成 `/v1/v1/messages` 而 404）。

代理按路径区分上游：`/v1/responses` 与 `/v1/chat/completions` 走 `OPENAI_UPSTREAM`，`/v1/messages` 走 `ANTHROPIC_UPSTREAM`（见本节末「启动代理时设置上游」）。

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
跑：`opencode run -m myproxy-chat/gpt-5 "..."`（或 `myproxy-resp/...`、`anthropic/...`）。chat / responses 路径都抓到工具调用参数与请求/响应体；anthropic 路径也被正确路由落盘（若上游按 CLI 指纹放行——如只认 Claude Code——可能拒绝 opencode，属上游限制，与代理无关）。

> 注：opencode 经 AI SDK 可能带 `accept-encoding: br`，本 Python 版已把转发上游的 `Accept-Encoding` 归一化成 `gzip, deflate`（标准库可解压，见下方「与 Node 版的一处差异」）。

### 启动代理时设置上游

| 用到的 CLI | 上游环境变量（默认值） |
|-----------|------------------------|
| Codex、opencode（chat / responses） | `OPENAI_UPSTREAM`（`https://api.openai.com`） |
| Claude Code、opencode（anthropic） | `ANTHROPIC_UPSTREAM`（`https://api.anthropic.com`） |

例（指向 freemodel）：
```bash
OPENAI_UPSTREAM=https://api.freemodel.dev ANTHROPIC_UPSTREAM=https://cc.freemodel.dev python -m cli_proxy_logger
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

## 内网打包与部署（离线）

本版本**纯 Python 标准库、零第三方依赖**（不需要 `pip install`），内网部署只需：拷目录 + 装好 Python 运行时。

**步骤（推荐：拷目录 + 内网 Python 运行时）**
1. **准备 Python 运行时**：内网机器装 **CPython 3.8+**（本机实测 3.12.8）。可用官方离线安装包（Windows `.exe`/嵌入式 zip、各 Linux 发行版的系统包），或 Windows 免安装的 embeddable 包。**无需 pip 安装任何包**。
2. **打包工程**：把整个 `cli-proxy-logger-py/` 目录打成 zip 拷过去（包含 `cli_proxy_logger/`、`public/`）。无 `requirements.txt`、无虚拟环境需求。
3. **运行**：
   ```bash
   cd cli-proxy-logger-py
   python -m cli_proxy_logger
   ```
   按需设置环境变量：`PROXY_PORT` / `UI_PORT` / `LOG_DIR` / `OPENAI_UPSTREAM` / `ANTHROPIC_UPSTREAM`。
   - **若 `-m` 报 `No module named cli_proxy_logger`**（某些 Windows embeddable / 嵌入式 Python 会忽略 cwd/`PYTHONPATH`），用等价的回退命令：
     ```bash
     python -c "import sys; sys.path.insert(0,'.'); from cli_proxy_logger.__main__ import main; main()"
     ```
4. **常驻后台**（可选）：
   - Linux：`nohup python -m cli_proxy_logger > proxy.out 2>&1 &`，或 systemd service。
   - Windows：`nssm` 注册服务、任务计划程序，或 `start /b python -m cli_proxy_logger`。

**可选（进阶）：单文件可执行**
用 PyInstaller（`pyinstaller -F -n cli-proxy-logger cli_proxy_logger/__main__.py`，记得用 `--add-data` 带上 `public/`）在**与内网相同 OS 的联网机器**上打成单 exe 再拷过去，免在内网装 Python。本仓库未内置打包脚本，按需自行打包。

> **网络/安全**：代理与 UI 默认监听本机端口（proxy `:8788`、UI `:8789`）。CLI 的 base URL 指向 `127.0.0.1`，**代理需与 CLI 部署在同一台机器**；不要把端口暴露到内网其他机器。

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

## 日志：存在哪、怎么命名

- **目录**：由 `LOG_DIR` 环境变量决定，**默认 `./logs`**。这是**相对路径**，相对的是「你启动 `python -m cli_proxy_logger` 时所在的工作目录」——按上面「运行」的步骤是在 `cli-proxy-logger-py/` 里启动，所以默认就是 `cli-proxy-logger-py/logs/`。想固定位置就用绝对路径，例如 `LOG_DIR=C:\proxy-logs python -m cli_proxy_logger`（PowerShell：`$env:LOG_DIR="C:\proxy-logs"; python -m cli_proxy_logger`）。
- **文件名**：按天滚动，`YYYY-MM-DD.jsonl`（UTC 日期），每天一个文件。
- **写入方式**：**追加**（append），每来一条请求就追加一行，进程重启不会清空，会继续往当天的文件追加。
- **内存 vs 磁盘**：UI 列表读的是**内存里最近 500 条**；磁盘 `.jsonl` 则是**全量持久**记录。两者独立。

### 「清空」按钮做什么

UI 顶部 refresh 旁边的 **「清空」** 按钮（带确认弹窗）只清空 **内存列表 / 当前视图**（底层是 `DELETE /api/exchanges`），**不会删除磁盘上的 `.jsonl` 文件**——磁盘日志是持久审计记录，故意保留。新开一个会话想让界面干净，点它即可。

**想彻底删除磁盘日志**：手动删文件即可，例如删当天的：
```bash
rm cli-proxy-logger-py/logs/$(date -u +%F).jsonl   # 删当天
rm -rf cli-proxy-logger-py/logs                      # 全删（下次启动自动重建目录）
```
（Windows PowerShell：`Remove-Item .\logs\*.jsonl` 或 `Remove-Item -Recurse -Force .\logs`。）

## 日志格式

每行一条 JSON（`<LOG_DIR>/YYYY-MM-DD.jsonl`），关键字段：

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
