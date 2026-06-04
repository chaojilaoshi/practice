# 可叠加的开源 Skills / MCP / 插件清单（增效 + 保质）

> 目标:在 opencode + OmO + superpowers + OpenSpec 之上,补充**互补、不重复**的开源能力,覆盖你的 10 阶段流水线。
> 取舍原则:**先看现有四件套已内置了什么,只补缺口**;MCP 不是越多越好(会污染上下文/增加噪声),按需启用。

---

## 0. 先确认:你现在已经有了什么(不要重复装)

OmO(Ultimate 版)已内置以下 MCP / 能力,**无需再单独安装**:

| 已内置 | 能力 | 对应阶段 |
| --- | --- | --- |
| **Exa MCP** | Web 搜索 | 立项/需求/调研 |
| **Context7 MCP** | 官方文档检索 | 编码/设计 |
| **grep.app MCP** | 跨 GitHub 代码搜索 | 编码/设计 |
| **ast-grep MCP** | 结构化代码搜索/改写(25 语言) | 编码/重构/审查 |
| **LSP tools MCP** | 诊断/跳转/符号/重命名 | 编码/测试 |
| **git-bash MCP** | git/shell 操作 | 提交/发布 |
| **Playwright** | 浏览器自动化 | 测试(e2e) |

superpowers 已内置:`systematic-debugging`、`verification-before-completion`、`requesting/receiving-code-review`、`using-git-worktrees`、`dispatching-parallel-agents` 等方法论 skills——**这些不要用 MCP 去替代**。

> 结论:**搜索类(web/docs/code)、结构搜索、LSP、浏览器、git** 都齐了。下面只补这几块缺口:**记忆/推理、安全静态扫描、PR/CI/Issue 平台、数据库、错误监控、需求/文档平台、专项 skill 包**。

---

## 1. opencode 接入 MCP 的标准写法

opencode 在 `opencode.json` 顶层用 `mcp` 字段(注意:与 `plugin` 平级),分 `local`(本地进程)与 `remote`(远程 URL)两种:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "memory": {
      "type": "local",
      "command": ["npx", "-y", "@modelcontextprotocol/server-memory"],
      "enabled": true
    },
    "semgrep": {
      "type": "local",
      "command": ["uvx", "semgrep-mcp"],
      "enabled": true
    },
    "context7-remote-example": {
      "type": "remote",
      "url": "https://mcp.context7.com/mcp",
      "enabled": false
    }
  }
}
```
- `local`:`command` 是「可执行 + 参数」数组,可加 `environment`(环境变量)、`timeout`。
- `remote`:`url` + 可选 `headers` / `oauth`。
- 临时关掉某个 server:把 `"enabled": false`。

> Codex Light 版用 `~/.codex/` 下的 `.mcp.json`(格式见 OmO 的 `packages/omo-codex/plugin/.mcp.json`:`mcpServers` → `command/args` 或 `url`)。Claude Code 用 `.mcp.json` / `/plugin`。

---

## 2. 按「保质量」优先推荐(最该先加的)

### 2.1 Semgrep MCP —— 安全/静态扫描(审查 & CI 阶段,强烈推荐)
- 仓库:`github.com/semgrep/mcp`(官方,内置 10000+ 规则,确定性静态分析)。
- 价值:AI 生成的代码最容易藏安全漏洞;在「审查」和「CI」阶段做一道确定性体检,弥补 LLM review 的不稳定。
- 工具:`security_check`、`semgrep_scan`、`semgrep_scan_with_custom_rule`。
- 安装:
  ```json
  "semgrep": { "type": "local", "command": ["uvx", "semgrep-mcp"] }
  ```
  （需先有 Semgrep CLI;也支持 `streamable-http` 模式与 Docker 镜像 `semgrep/mcp`）
- 用法约定(写进 AGENTS.md / .omo/rules):「所有新生成代码必须经 Semgrep 扫描后才能提交」。

### 2.2 Memory MCP（知识图谱记忆）—— 跨会话留存(贯穿全流程)
- 仓库:`@modelcontextprotocol/server-memory`(官方 reference server,本地知识图谱)。
- 价值:OpenSpec 留「工件」,Memory 留「agent 的长期记忆」(架构决策、踩过的坑、约定),避免每次重新解释上下文。
- 安装:
  ```json
  "memory": { "type": "local", "command": ["npx","-y","@modelcontextprotocol/server-memory"] }
  ```

### 2.3 Sequential Thinking MCP —— 结构化推理(设计/计划/复杂调试)
- 仓库:`@modelcontextprotocol/server-sequential-thinking`(官方)。
- 价值:在「设计」「writing-plans」「systematic-debugging」阶段,把推理拆成可回溯的步骤,提升复杂问题的稳定性。
- 安装:
  ```json
  "sequential-thinking": { "type":"local", "command":["npx","-y","@modelcontextprotocol/server-sequential-thinking"] }
  ```

### 2.4 GitHub MCP Server —— PR/CI/Issue 平台(提交阶段)
- 仓库:`github.com/github/github-mcp-server`(GitHub 官方,Go;有远程托管版,支持 OAuth)。
- 价值:把「提交/CI/审查回应」搬进 agent:开 PR、读 CI 状态、回评论、管 issue。与 OmO 的 `work-with-pr` / `github-triage` skill 互补(后者是流程,前者是 API 能力)。
- 安装(远程托管版,opencode `remote` + OAuth):
  ```json
  "github": { "type":"remote", "url":"https://api.githubcopilot.com/mcp/" }
  ```
  （或本地 Docker 版,用 PAT 注入 `environment`)
- GitLab 用官方 `@modelcontextprotocol/server-gitlab`。

---

## 3. 按阶段补充推荐

| 阶段 | 推荐(开源/官方) | 作用 | 备注 |
| --- | --- | --- | --- |
| 立项/需求 | **Linear MCP** / **Notion MCP** / Atlassian(Jira)MCP | 把任务、需求、文档双向同步进 agent | 与 OpenSpec proposal 互补:平台管协作,OpenSpec 管规范留痕 |
| 需求/调研 | **Fetch MCP**（官方 `mcp-server-fetch`）、**Firecrawl MCP**、**Ref MCP** | 抓网页正文/爬取文档/精准查文档 | Exa 已覆盖搜索;Fetch/Firecrawl 补「抓全文」 |
| 设计/编码 | **Serena**（语义代码工具,基于 LSP） | 大型库的符号级理解/编辑 | ⚠️ 与 OmO 的 LSP+ast-grep 有重叠,按需 |
| 编码/测试 | **chrome-devtools-mcp**（Google 官方) | 真·Chrome 调试:性能、网络、控制台 | 比 Playwright 更偏「调试/性能诊断」,可与之并存 |
| 测试/数据 | **Postgres / SQLite MCP**（官方）、**DBHub** | 让 agent 查 schema、跑只读查询验证 | 只读账号,避免误写 |
| 审查/安全 | **Semgrep MCP**(见 2.1)、**Snyk MCP**、**Trivy**(容器/依赖漏洞) | 多层安全门禁 | Snyk 需账号 |
| CI/运维 | **Sentry MCP**(错误监控,OmO 已引用)、**Grafana MCP**、**Kubernetes MCP** | 把线上错误/指标拉进修复闭环 | 发布后回归用 |
| 归档/知识 | **DeepWiki MCP**(OmO 已引用)、Memory MCP | 自动生成/查询仓库 wiki | 归档后沉淀知识 |
| 时间/杂项 | **Time MCP**(官方 `mcp-server-time`) | 时区/时间换算 | 轻量,按需 |

---

## 4. Skills / 插件 / 市场(非 MCP)

| 来源 | 内容 | 怎么用 |
| --- | --- | --- |
| **Anthropic 官方插件市场** | `/plugin marketplace`,含 superpowers 官方版及大量 skill/插件 | Claude Code:`/plugin install <name>@claude-plugins-official` |
| **obra/superpowers-marketplace** | superpowers 作者维护的相关插件集 | `/plugin marketplace add obra/superpowers-marketplace` |
| **OpenAI Codex 插件市场**（`openai/plugins`) | Codex 的官方插件(含 Superpowers) | Codex:`/plugins` 搜索安装 |
| **GitHub spec-kit** | 另一套规范驱动框架(可对比/借鉴,与 OpenSpec 同类) | 仅作参考,不建议与 OpenSpec 并用 |
| **OpenSpec 社区 schema 包** | 第三方工作流 schema(类似 spec-kit 扩展目录) | 见 OpenSpec `docs/customization.md#community-schemas` |
| **modelcontextprotocol/registry** | MCP 官方「应用商店」,发现/检索 MCP server | 浏览选型用 |
| **awesome-mcp-servers**（apappascs / punkpeye 等) | 社区精选 MCP 清单 | 选型时按需查 |
| **自建 skill** | superpowers `writing-skills` 技能教你写并测试新 skill | 把团队私有流程固化成 skill |

---

## 5. 一套「质量门禁」组合建议(开箱即用)

把以下加进 `opencode.json` 的 `mcp`,并在 `AGENTS.md` / `.omo/rules` 写明触发约定:

```json
{
  "mcp": {
    "memory":               { "type":"local", "command":["npx","-y","@modelcontextprotocol/server-memory"] },
    "sequential-thinking":  { "type":"local", "command":["npx","-y","@modelcontextprotocol/server-sequential-thinking"] },
    "semgrep":              { "type":"local", "command":["uvx","semgrep-mcp"] },
    "fetch":                { "type":"local", "command":["uvx","mcp-server-fetch"] },
    "github":               { "type":"remote", "url":"https://api.githubcopilot.com/mcp/" }
  }
}
```

规则约定示例(`.omo/rules/quality.md` 或 `AGENTS.md`):
```text
- 设计/计划阶段:遇到复杂决策先用 sequential-thinking 拆解。
- 审查阶段:必须先跑 Semgrep security_check,Critical/High 未清不得进入提交。
- 提交阶段:通过 github MCP 开 PR 并附 Semgrep 结果摘要。
- 关键架构决策与踩坑:写入 memory,便于后续会话复用。
```

阶段对应(在你的 10 阶段流水线里各加一道保险):
- 立项/需求 → Linear/Notion(协作) + Exa(已内置)
- 设计/计划 → **sequential-thinking** + **memory**
- 编码/测试 → LSP/ast-grep(已内置) + chrome-devtools(调试) + DB(只读验证)
- 审查/CI → **Semgrep**（+ Snyk/Trivy)+ **GitHub MCP**
- 归档/发布 → DeepWiki + Sentry(回归监控)

---

## 6. 注意事项

- **不要堆太多 MCP**:每个开启的 server 都会向模型暴露一批工具,工具过多会稀释注意力、增加误用。建议常开 ≤ 5~6 个,其余 `"enabled": false` 按需开。
- **避免与 OmO 内置重复**:web/docs/code 搜索、ast-grep、LSP、git、Playwright 都已具备,优先补「安全扫描 / 记忆 / 平台集成 / 数据库 / 监控」。
- **权限最小化**:数据库给只读账号;GitHub/Sentry/Snyk 用 scoped token 并放 `environment` 或密钥管理,**不要明文写进仓库**。
- **核对你的 fork**:本清单按上游官方文档与各 MCP 官方仓库编写;若你的 `chaojilaoshi/*` fork 改过内置 MCP 集合,请以仓库实际 `.mcp.json` 为准。
- **可靠性优先**:Semgrep、官方 reference servers(memory/fetch/git/sequential-thinking/time)、GitHub 官方 MCP 维护活跃、质量较稳;第三方(Firecrawl/Serena/各平台)按需评估。
