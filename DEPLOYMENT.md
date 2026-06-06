# 单文件可视化配置启动器 — 三语言打包部署完全指南

本文面向**完全不懂编程**的使用者，把 cli-proxy-logger 的三套实现（Node / Python / Java）
从「**构建 → 产物 → 分发 → 运行 → 配置**」讲清楚。目标是：**双击一个程序 → 浏览器自动弹出
配置界面 → 点开关、填表、保存 → 完事**。不用敲命令、不用编辑 JSON、不用懂代码。

> **重要前提：默认就是「透明代理」。** 三套程序刚装好、什么都不配时，请求和响应**一个字节都不改**，
> 只是在旁边把流量记录下来。只有你在配置界面里**主动打开**某个开关，对应功能才会生效。

---

## 0. 先搞清楚：你是「使用者」还是「打包者」？

| 角色 | 你要做什么 | 看哪几节 |
|---|---|---|
| **使用者**（拿到别人打好的程序，只想用） | 双击运行、在网页里配置 | 第 2、4、5、6 节 |
| **打包者**（要把程序构建出来发给别人） | 在装好工具链的机器上构建 | 第 3 节，然后把产物给使用者 |

「打包」只需做一次，在一台装好工具链（Node / Python / JDK）的机器上完成；产出的程序拷给别人后，
**对方机器什么都不用装**（Node/Python 是单文件 exe，Java 自带精简 JRE）。

---

## 1. 三套实现怎么选？

三套功能完全一致、配置界面一模一样、配置文件 `config.json` 格式也通用。区别只在**打包方式和体积**：

| | 打包方式 | 产物 | 体积 | 使用者机器需要装什么 |
|---|---|---|---|---|
| **Node 版**（推荐） | Node SEA（单可执行文件） | **一个** `cli-proxy-logger.exe` | ~70MB | 什么都不用装 |
| **Python 版** | PyInstaller `--onefile` | **一个** `cli-proxy-logger.exe` | ~8.3MB | 什么都不用装 |
| **Java 版** | jpackage app-image | **一个文件夹**（exe 启动器 + 内置 JRE） | ~147MB | 什么都不用装（JRE 已内置） |

- **只想要最小的单文件** → 选 **Python 版**（8.3MB，单文件）。
- **要最稳、零依赖、官方推荐** → 选 **Node 版**（单文件，70MB）。
- **公司是 Java 技术栈 / 想要内置 JRE 的绿色文件夹** → 选 **Java 版**。

> 三套都是**本地** HTTP 反向代理：把 Claude Code / Codex / opencode 的 base URL 指向本地代理，
> 它转发到真正的上游（Anthropic / OpenAI）并记录、按需改写。不劫持 TLS、不装根证书。

---

## 2. 快速开始（使用者，30 秒）

> 下面以 Windows 为例（最常见）。

### Node 版 / Python 版（单文件）

1. 拿到 `cli-proxy-logger.exe`，放到任意一个**空文件夹**里（程序会在它旁边生成 `config.json` 和 `logs/`）。
2. **双击它**。会弹出一个黑色控制台窗口（别关，关了程序就停了），紧接着**浏览器自动打开配置页**。
3. 配置页打开后，按第 5 节去点开关、填表、保存即可。

### Java 版（文件夹）

1. 拿到整个 `cli-proxy-logger` 文件夹（里面有 `cli-proxy-logger.exe`、`runtime/`、`app/`）。**整个文件夹一起拷贝**，不要只拷 exe。
2. 双击文件夹里的 **`cli-proxy-logger.exe`**。浏览器会自动打开配置页。

### 配置页地址（万一浏览器没自动弹出）

手动在浏览器输入：

| | 配置页地址 | 代理地址（给 CLI 用） |
|---|---|---|
| Node 版 | http://127.0.0.1:8789 | http://127.0.0.1:8788 |
| Python 版 | http://127.0.0.1:8789 | http://127.0.0.1:8788 |
| Java 版 | http://127.0.0.1:8788 | http://127.0.0.1:8788（同端口） |

> Node / Python：**配置页 8789、代理 8788** 是两个端口。
> Java：配置页和代理**共用 8788**。

---

## 3. 构建流程（打包者）

> 这一节是给「要自己把程序构建出来」的人看的。使用者可跳过。
> 三套都先 `git clone` 本仓库，再进入对应子目录构建。

### 3.1 Node 版 → 单文件 exe

**需要**：Node.js **>= 20**（用到内置 SEA 能力）。

```bash
cd cli-proxy-logger
npm install        # 安装构建期依赖（esbuild + postject）
npm run build:exe  # 产出 dist/cli-proxy-logger.exe
```

- 原理：`esbuild` 把应用打成单个 JS 包 → `node --experimental-sea-config` 生成 blob（内嵌
  `public/index.html`）→ 复制本机 `node.exe` → `postject` 把 blob 注入这份 exe。
- 产物：`cli-proxy-logger/dist/cli-proxy-logger.exe`，约 70MB，**零外部依赖**，可直接拷给别人双击。

### 3.2 Python 版 → 单文件 exe

**需要**：Python（实测 3.12）+ PyInstaller。

```bash
cd cli-proxy-logger-py
pip install pyinstaller       # 仅构建期需要
python scripts/build_exe.py   # 产出 dist/cli-proxy-logger.exe（约 8.3MB）
```

- 原理：PyInstaller `--onefile` 把 Python 运行时 + `cli_proxy_logger` 包 + 内嵌的
  `public/index.html` 压成一个自包含 exe；运行时解压到临时目录（`sys._MEIPASS`）读取 UI。
- 产物：`cli-proxy-logger-py/dist/cli-proxy-logger.exe`，约 8.3MB。

### 3.3 Java 版 → 文件夹应用（exe + 内置 JRE）

**需要**：JDK **17**（必须含 `jpackage`，本机用 Temurin 17）+ Maven。

```bash
cd cli-proxy-logger-java
# Windows：
powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
# macOS / Linux：
bash scripts/build_exe.sh
```

- 原理：先 `mvn -DskipTests clean package` 出 Spring Boot 可执行 fat jar →
  `jpackage --type app-image` 用 `jlink` 裁出精简运行时镜像（JRE）并生成原生启动器；
  Spring Boot jar 的真正入口是 `org.springframework.boot.loader.JarLauncher`（脚本已配好）。
- 产物：`cli-proxy-logger-java/dist/app-image/cli-proxy-logger/`（整个文件夹，约 147MB）。
  里面有 `cli-proxy-logger.exe`（启动器）、`runtime/`（内置 JRE）、`app/`（jar）。

> **三套都不跨平台编译**：要 Windows 的 exe 就在 Windows 上构建，要 macOS/Linux 的就在对应系统上构建。
> Java 版**构建**需要 JDK 17，但**产物自带 JRE，使用者机器无需安装任何 Java**。

---

## 4. 文件布局 & 首次启动行为

### 程序在哪里生成文件？

三套都把 `config.json` 和 `logs/` 放在**可执行程序同目录**：

```
你的文件夹/
├── cli-proxy-logger.exe        ← 你双击的程序（Java 版在 cli-proxy-logger/ 文件夹内）
├── config.json                 ← 第一次「保存」后出现；你的全部配置存在这里
└── logs/
    └── 2026-06-05.jsonl        ← 每天一个文件，记录抓到的请求/响应
```

> 所以建议把 exe 放进一个**专门的空文件夹**再运行，方便管理。

### 首次启动会发生什么？

1. 程序启动，监听端口（见第 2 节表格）。
2. 此时**还没有** `config.json`：程序从环境变量「种子」出一份默认配置（默认**全部功能关闭** = 透明代理）。
3. 浏览器自动打开配置页。页面顶部会注明配置**来源**：
   - `来源: env` —— 还没存过盘，当前是默认/环境变量种子。
   - `来源: file` —— 已经从同目录的 `config.json` 读取（你之前存过）。
4. 你在界面里改任意一项、点「保存并生效」后，`config.json` 才会被写到磁盘；**下次启动自动读取它**。

---

## 5. 在界面里配置（核心用法）

打开配置页后，点 **「设置 / 配置」**。所有项都是可视化控件，**改完点最下方「保存并生效」**。
大多数改动**立即生效、无需重启**；**只有改「监听端口」需要重启程序**（界面会提示你）。

可配置的内容（三套一致）：

- **基础**：监听端口、日志目录、Anthropic / OpenAI **上游地址**（你要转发到的真实 API）。
- **工具名规范化**：总开关 + 三个子开关（请求侧 / 响应侧 / 修复被序列化成字符串的数组）+ 一张
  **映射表**（增删行，如 `todowrite → TodoWrite`）。
- **请求过滤器**：一张**规则表**（增删行）。每条规则：动作（设置请求头 / 删除请求头 / 按 JSON 路径
  设值 / 按 JSON 路径删除）+ 目标 + 值 + 优先级 + 适用范围。常见用途：给所有请求加
  `anthropic-beta` 头、强制写入 `thinking.budget_tokens`。
- **出站代理**：填 `http://` 或 `socks5://` 地址，让本程序转发到上游时走它（内网出口常用）。留空 = 直连。
- **协议翻译 + 模型映射**：Anthropic↔Chat 互转开关 + 模型名映射表。
- **供应商池 / 故障转移**：多个上游，首选失败自动切下一个。
- **熔断器**：连续失败到阈值就暂时跳过某上游，冷却后再探测恢复。
- **Thinking 整流器**：上游报 thinking 签名 / budget 错误时自动修正并重试。

> 想验证「真的生效了」？在界面里随便开一项（比如工具名规范化），保存，然后用 CLI 发个请求，
> 在配置页的请求列表里点开那条记录，能看到改写后的内容。

---

## 6. 故障排查（FAQ）

- **双击没反应 / 浏览器没弹出**
  - 手动在浏览器打开配置页地址（第 2 节表格）。
  - 确认那个黑色控制台窗口还开着（关了程序就停了）。
- **Windows 弹「Windows 已保护你的电脑 / SmartScreen」**
  - 这是因为程序没有购买代码签名证书，属正常。点 **「更多信息」→「仍要运行」**。
- **端口被占用 / 启动失败**
  - 可能 8788/8789 被别的程序占了。进配置页改「监听端口」，保存后**重启程序**；
    或先关掉占用端口的程序。
- **CLI（Claude Code / Codex）连不上**
  - 把 CLI 的 base URL 指到**代理地址**（第 2 节表格），不是配置页地址。
  - **关键**：Codex / opencode 的 base URL **带 `/v1`**；Claude Code 的 **不带 `/v1`**
    （它自己会拼 `/v1/messages`，带了会变 `/v1/v1/messages` 而 404）。各 CLI 的完整配置模板见各子目录 README。
- **改了配置没生效**
  - 确认点了「保存并生效」并看到成功提示；改「监听端口」必须重启程序。
- **日志在哪 / 怎么清**
  - 在程序同目录的 `logs/` 下，按天一个 `.jsonl` 文件，可直接删除。
- **Java 版只拷了 exe 跑不起来**
  - Java 版必须**整个文件夹**一起拷（exe 依赖同目录的 `runtime/` 和 `app/`）。

---

## 7. 高级：不开界面也能配（手改文件 / 环境变量）

可视化界面是给不懂代码的人用的；如果你愿意手动配置，也完全支持，且与界面**等价**：

### 7.1 直接编辑 `config.json`

程序同目录的 `config.json` 就是界面里所有项的存档。可以用记事本直接改，保存后**重启程序**即可
（界面里的「保存并生效」能免重启，手改文件需要重启）。完整字段见第 8 节示例。

### 7.2 用环境变量（兼容老用法，适合命令行/服务器）

不打包、直接命令行运行时，三套都支持用环境变量 / 命令行参数配置（**首次启动也用它们做种子**）。
每个配置项都有对应的环境变量，详见各子目录 README 的「配置表」。例如：

```bash
# Node / Python（环境变量）
TOOL_NAME_CASE=1 ANTHROPIC_UPSTREAM=https://api.anthropic.com python -m cli_proxy_logger

# Java（命令行参数或环境变量均可）
java -jar cli-proxy-logger-1.0.0.jar --proxy.tool-name-case=true
```

> **优先级**：存在 `config.json` 时**以文件为准**（这是图形界面工作流）；没有文件且未打包时，回退到
> 环境变量 / 命令行参数（保持老用法字节级不变）。打包成 exe 后，始终以同目录 `config.json` 为准。

---

## 8. 示例 `config.json`（开启了若干功能）

下面是一份**打开了**工具名规范化 + 一条过滤器 + 出站代理的完整示例（默认情况下这些都是关的）。
把它放在 exe 同目录、重启程序即可生效；或在界面里照着填。**三套通用同一格式。**

```json
{
  "proxyPort": 8788,
  "uiPort": 8789,
  "logDir": "",
  "upstream": {
    "anthropic": "https://api.anthropic.com",
    "openai": "https://api.openai.com"
  },
  "compat": {
    "enabled": false,
    "modelMap": {}
  },
  "toolName": {
    "enabled": true,
    "request": true,
    "response": true,
    "repairInput": true,
    "map": { "todowrite": "TodoWrite", "webfetch": "WebFetch" }
  },
  "filters": [
    {
      "name": "beta-header",
      "enabled": true,
      "priority": 10,
      "action": "set_header",
      "target": "anthropic-beta",
      "value": "context-1m-2025-08-07"
    }
  ],
  "outbound": { "url": "socks5://127.0.0.1:1080" },
  "providers": { "anthropic": [], "openai": [] },
  "breaker": {
    "enabled": false,
    "failureThreshold": 5,
    "cooldownMs": 30000,
    "halfOpenMax": 1,
    "failoverStatuses": [429, 500, 502, 503, 504]
  },
  "rectifier": { "enabled": false, "signature": true, "budget": true }
}
```

字段说明（节选；完整含义见各子目录 README 的「配置表」）：

| 字段 | 含义 | 默认 |
|---|---|---|
| `proxyPort` / `uiPort` | 代理端口 / 配置页端口（Java 版共用 `proxyPort`） | 8788 / 8789 |
| `logDir` | 日志目录，留空 = 程序同目录的 `logs/` | `""` |
| `upstream.anthropic` / `.openai` | 真实上游地址（要转发到哪） | 官方地址 |
| `toolName.enabled` | 工具名规范化总开关 | `false` |
| `toolName.request/response/repairInput` | 三个子开关（仅在总开关开时起作用） | `true` |
| `toolName.map` | 小写名 → 规范名的映射表 | `{}` |
| `filters[]` | 请求过滤规则数组（`action`：`set_header`/`delete_header`/`json_set`/`json_delete`） | `[]` |
| `outbound.url` | 出站代理地址（`http://` 或 `socks5://`），留空 = 直连 | `""` |
| `breaker.enabled` | 熔断器开关 + 阈值/冷却 | `false` |
| `rectifier.enabled` | Thinking 整流器开关 | `false` |

> 想完全恢复「透明代理」？把所有 `enabled` 设为 `false`、`filters` 设为 `[]`、`outbound.url` 设为 `""`
> 即可（或在界面里把开关全关）。这样请求/响应逐字节原样透传。
