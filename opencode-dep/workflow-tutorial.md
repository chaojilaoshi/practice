# 全流程 AI 研发工作流：配置与使用完整教程

> 基于四个开源仓库组合而成的一套「规范驱动 + 技能方法论 + 多模型编排」的端到端研发流水线。
>
> 覆盖阶段：**立项(intake) → 需求(requirements/specs) → 设计(design) → 计划(writing-plans) → 编码(coding+TDD) → 测试(testing) → 审查(review) → 提交(pipeline/CI) → 归档(opsx-archive) → 发布(release)**

---

## 0. 这四个仓库各自是什么、如何分工

| 仓库 | 角色定位 | 在流水线里负责什么 |
| --- | --- | --- |
| **opencode**（`chaojilaoshi/opencode`） | 开源 AI 编码 Agent / **运行宿主（harness）** | 提供 TUI / 桌面端运行环境、`build`/`plan` 两种内置 agent、插件机制（`opencode.json`）。其它三者都"插"在它上面。 |
| **oh-my-openagent / OmO**（`chaojilaoshi/oh-my-openagent`） | **多模型编排层（插件）** | 在 opencode 之上引入 Sisyphus 等多 agent 编排、`ultrawork` / `hyperplan` / `team` 等模式、LSP、规则注入、`/publish` 等斜杠命令。负责"编码、测试、审查、发布"阶段的执行力。 |
| **superpowers**（`chaojilaoshi/superpowers`） | **方法论层（可组合 skills 插件）** | 一组强制触发的技能：brainstorming → writing-plans → TDD → code-review → finishing-branch。负责把"立项/设计/计划/编码纪律/审查"做成固定流程。 |
| **OpenSpec**（`chaojilaoshi/OpenSpec`） | **规范驱动层（CLI + 斜杠命令）** | 用 `proposal / specs / design / tasks` 四类工件管理每个变更，`/opsx:*` 命令贯穿 propose→apply→sync→archive。负责"需求/设计/计划/归档"的产物沉淀与可追溯。 |

**一句话理解分工：**
- **opencode** = 跑步的人（运行时）
- **OmO** = 给他装上的"肌肉与团队"（执行/编排）
- **superpowers** = 训练计划与纪律（怎么做才规范）
- **OpenSpec** = 训练日志与档案（做了什么、为什么、留痕归档）

> 注意：OmO 自身仓库里就内置了一份 `docs/superpowers/`，说明 OmO 在设计上就考虑了与 superpowers 方法论协同；OpenSpec 的 `openspec init` 也能直接把命令安装进 opencode 的 `.opencode/` 目录。四者天然可叠加。

---

## 1. 前置环境（Prerequisites）

| 依赖 | 版本要求 | 用途 |
| --- | --- | --- |
| **Node.js** | **≥ 20.19.0** | OpenSpec 必需；多数工具的运行基础 |
| **Bun** | 最新 | OmO **Ultimate（OpenCode 版）** 安装器需要（`bunx`） |
| **npm / npx** | 随 Node | OpenSpec 全局安装；OmO Light（Codex 版）安装 |
| **git** | 最新 | superpowers 插件以 git 包形式安装；worktree、提交、发布 |
| **opencode** | 最新（≥ 0.1.x，旧版先卸载） | 运行宿主 |

安装 opencode（任选其一）：

```bash
# 通用脚本
curl -fsSL https://opencode.ai/install | bash

# 包管理器
npm i -g opencode-ai@latest
brew install anomalyco/tap/opencode      # macOS / Linux 推荐
scoop install opencode                   # Windows
```

可选：至少准备一个模型订阅 / API（OmO 的 `ultrawork` 在以下任一组合下即可良好工作）：
- ChatGPT 订阅、Kimi Code、GLM Coding Plan，或 Anthropic / Gemini / Copilot / Z.ai / OpenCode Zen 的按量计费。

---

## 2. 安装与配置（一次性）

建议在**项目根目录**进行，使配置随项目走（也支持全局）。

### 2.1 安装 OpenSpec（规范层）

```bash
npm install -g @fission-ai/openspec@latest

cd your-project
openspec init            # 交互式：选择工具(opencode 等)、是否创建 openspec/config.yaml
```

`openspec init` 后项目会出现：

```
openspec/
├── specs/              # 真理之源：系统当前应有的行为（按 domain 组织）
├── changes/            # 每个变更一个文件夹（proposal/design/tasks/specs delta）
│   └── archive/        # 已完成变更的归档（含日期前缀）
└── config.yaml         # 项目级配置（可选但推荐）
```

并会把斜杠命令/技能写入对应工具目录，例如 opencode：

```
.opencode/skills/openspec-*/SKILL.md
.opencode/commands/opsx-<id>.md
```

**工作流档位（重要）：** 默认是 `core` 档（命令：`propose / explore / apply / sync / archive`）。
若想要"展开档"（`new / continue / ff / verify / bulk-archive / onboard`），执行：

```bash
openspec config profile     # 选择展开档
openspec update             # 重新生成命令/技能
```

`openspec/config.yaml` 示例（把项目上下文注入到所有工件，统一规范）：

```yaml
schema: spec-driven
context: |
  Tech stack: TypeScript, React, Node.js
  Testing: Vitest（单测） + Playwright（e2e）
  Style: ESLint + Prettier, strict TypeScript
rules:
  proposal:
    - 必须包含回滚方案(rollback plan)
    - 标注受影响团队
  specs:
    - 场景统一使用 Given/When/Then
  design:
    - 复杂流程附时序图
```

### 2.2 安装 superpowers（方法论层 / opencode 插件）

编辑（全局或项目级）`opencode.json`，在 `plugin` 数组加入：

```json
{
  "plugin": [
    "superpowers@git+https://github.com/obra/superpowers.git"
  ]
}
```

> 也可对应你 fork 的地址：`superpowers@git+https://github.com/chaojilaoshi/superpowers.git`
> 可固定版本：`...superpowers.git#v5.0.3`

重启 opencode，验证：

```
use skill tool to list skills
use skill tool to load superpowers/brainstorming
```
或直接问 agent：「Tell me about your superpowers」。

superpowers 通过 `SessionStart` 钩子（`hooks/hooks.json`）在会话开始时自动加载技能，使各技能在相应阶段**自动强制触发**（不是建议，是流程门禁）。

### 2.3 安装 oh-my-openagent / OmO（编排层）

**Ultimate 版（OpenCode）——推荐让一个 LLM agent 帮你装**，因为涉及订阅识别、11 个 agent 的模型匹配、各家鉴权：

```bash
bunx oh-my-openagent install        # 启动 TUI，逐步引导
# 同时装 OpenCode + Codex：
bunx oh-my-openagent install --platform=both
```

或把下面这句粘贴进任意 agent 让它替你完成全部配置：

```
Install and configure oh-my-openagent by following the instructions here:
https://raw.githubusercontent.com/code-yeongyu/oh-my-openagent/refs/heads/dev/docs/guide/installation.md
```

安装会向 `opencode.json` 注册插件、写入 agent/模型配置、并提示各 provider 鉴权。

**Light 版（Codex CLI）**——可移植组件（rules / comment-checker / LSP / ultrawork / ulw-loop / start-work-continuation / telemetry）：

```bash
npx lazycodex-ai install
# 非交互推荐模式：
npx lazycodex-ai install --no-tui --codex-autonomous
```

> ⚠️ **不要**用 `bunx omo` / `npx omo`：`omo` 是另一个无关的 npm 包。请用 `oh-my-openagent install`（bin 别名也叫 `omo`，但调用入口请用全名）。

### 2.4 三层叠加后的 `opencode.json` 参考

```json
{
  "$schema": "https://opencode.ai/config.json",
  "plugin": [
    "oh-my-openagent",
    "superpowers@git+https://github.com/obra/superpowers.git"
  ]
}
```
> OmO 安装器通常会自动写入它自己的插件项与 agent/模型配置；你只需手动确保 superpowers 也在 `plugin` 数组里。OpenSpec 不进 `plugin`，它通过 `.opencode/commands` + `.opencode/skills` 生效。

---

## 3. 阶段 ↔ 工具命令 对照总表

> 核心思想：**OpenSpec 管「产物与留痕」**（每阶段产出工件），**superpowers 管「该怎么做」**（强制方法论），**OmO 管「用多模型把它做完」**（执行/编排），**opencode 是运行它们的地方**。

| 流水线阶段 | 主用工具 | 关键命令 / 技能 | 产物 / 结果 |
| --- | --- | --- | --- |
| **立项 intake** | OpenSpec + superpowers | `/opsx:explore`；自动触发 `brainstorming` 技能（苏格拉底式提问，先对齐意图再动手，有 HARD-GATE） | 想法澄清、方向确定 |
| **需求 specs** | OpenSpec | `/opsx:propose`（core）或 `/opsx:new`→`/opsx:continue`（展开档） | `proposal.md` + `changes/<name>/specs/`（ADDED/MODIFIED/REMOVED delta，Given/When/Then 场景） |
| **设计 design** | OpenSpec + superpowers | `/opsx:continue` 生成 `design.md`；brainstorming 产出设计稿 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` | `design.md`（技术方案/架构决策） |
| **计划 writing-plans** | superpowers + OpenSpec | `writing-plans` 技能（拆成 2–5 分钟可执行小任务、含确切文件路径与验证步骤）；OpenSpec `tasks.md` 清单 | `docs/superpowers/plans/YYYY-MM-DD-<feature>.md` + `tasks.md`（带勾选框） |
| **编码 coding+TDD** | OmO + superpowers + OpenSpec | OmO `ultrawork`/`ulw` 或 `/start-work`；`test-driven-development` 强制 RED-GREEN-REFACTOR；`/opsx:apply` 按 tasks 实现 | 实现代码 + 单测；tasks 逐项打勾 |
| **测试 testing** | superpowers + OmO | `verification-before-completion` 技能；OmO LSP 诊断 / 导航；运行 `pnpm test` 等 | 测试通过、诊断清零 |
| **审查 review** | superpowers + OmO | `requesting-code-review` / `receiving-code-review`（按严重度分级，Critical 阻断）；OmO `/review-work`、`hyperplan`（5 个敌对评审）、`pre-publish-review` | 评审报告、问题修复 |
| **提交 pipeline/CI** | OpenSpec + OmO + git | `openspec validate --all`；OmO `work-with-pr` / `github-triage`；提交遵循 Conventional Commits（`type(scope): subject`） | PR、CI 通过 |
| **归档 opsx-archive** | OpenSpec | `/opsx:archive`（单个）/ `/opsx:bulk-archive`（多个）；`/opsx:sync` 可先把 delta 并入主 specs | delta 并入 `specs/`，变更移入 `changes/archive/<date>-<name>/` |
| **发布 release** | OmO + superpowers | superpowers `finishing-a-development-branch`（验证测试→选择 merge/PR/keep/discard→清理 worktree）；OmO `/publish`、`get-unpublished-changes` | 合并/发版 |

---

## 4. 各阶段详解与操作要点

### 4.1 立项 intake
- 不要一上来写代码。superpowers 的 `brainstorming` 技能带 **HARD-GATE**：未产出设计且未获你批准前，禁止写任何实现代码（无论需求看起来多简单）。
- 用 OpenSpec `/opsx:explore` 做"思考伙伴"：调研问题、比较方案，洞见成形后再转 `/opsx:propose`。

### 4.2 需求 specs
- `/opsx:propose "<你要做的事>"` 一步生成 `proposal.md` + delta specs +（视档位）`design.md`/`tasks.md`。
- delta specs 用 `## ADDED / ## MODIFIED / ## REMOVED Requirements` 三段表达变化，场景用 Given/When/Then。归档时：ADDED 追加、MODIFIED 替换、REMOVED 删除。

### 4.3 设计 design
- `design.md` 写"怎么做"：技术方案、架构决策、复杂流程时序图（可在 `config.yaml` 的 `rules.design` 里强制）。
- superpowers 会把设计分段呈现，让你逐段确认后再继续。

### 4.4 计划 writing-plans
- `writing-plans` 技能假设执行者"对代码库零上下文、品味存疑"，因此计划要**极其具体**：每个任务 2–5 分钟、列明要改哪些文件、完整代码、如何验证。强调 DRY / YAGNI / TDD / 频繁提交。
- 与 OpenSpec `tasks.md`（带 checkbox 的实现清单）配合，形成可勾选的执行单。

### 4.5 编码 coding + TDD
- 最省心：在 opencode 里直接输入 `ultrawork`（或 `ulw`），OmO 会自动探索代码库、研究模式、实现、用诊断验证，直到完成。
- 想要更强编排：按 `Tab` 进入 Prometheus（访谈式规划），再 `/start-work` 触发完整编排（Sisyphus 主导，Hephaestus/Oracle/Librarian/Explore 并行）。
- **TDD 纪律（superpowers，强制）**：先写失败测试 → 看它失败（RED）→ 写最小实现 → 看它通过（GREEN）→ 重构 → 提交。"先写实现再补测试"会被删掉重来。
- 规范侧：`/opsx:apply` 按 `tasks.md` 实现，并在过程中回头更新工件（fluid，非瀑布）。

### 4.6 测试 testing
- superpowers `verification-before-completion`：声明"做完了"之前必须用证据验证（别只凭主观）。
- OmO 的 LSP 集成提供诊断/跳转/符号/重命名，编码即测即查；运行项目自身测试命令（如 `pnpm test` / `vitest`）。

### 4.7 审查 review
- `requesting-code-review`：派一个评审子 agent，按严重度报问题，Critical 阻断推进；`receiving-code-review` 指导如何回应反馈。
- OmO 强化项：`/review-work` 评审产出、`hyperplan`（Team Mode 下 5 个"敌对评审"挑刺）、`pre-publish-review`（发布前体检）。

### 4.8 提交 pipeline / CI
- `openspec validate --all`（可加 `--json`）校验变更与 specs 是否健康，可放进 CI。
- OmO `work-with-pr` / `work-with-pr-workspace` / `github-triage` 协助开 PR、处理评论、triage issue。
- 提交信息走 Conventional Commits 单行：`type(scope): subject`。

### 4.9 归档 opsx-archive
- 变更实现完且 tasks 全勾后：`/opsx:archive`（单个）或 `/opsx:bulk-archive`（一次归档多个已完成变更）。
- 归档动作 = 把 delta 合并进主 `specs/` + 变更目录移到 `changes/archive/<YYYY-MM-DD>-<name>/`，保留审计历史。`/opsx:sync` 可在归档前先把 delta 并入主 specs。

### 4.10 发布 release
- superpowers `finishing-a-development-branch`：任务完成时触发，先验证测试，再让你选 merge / 开 PR / 保留 / 丢弃，并清理 worktree。
- OmO 发布命令：`/publish`、`get-unpublished-changes`（查未发布变更）。配合上游的发版流程完成 release。

---

## 5. 一个完整的端到端示例（core 档）

```text
# 立项 + 需求 + 设计 + 计划（OpenSpec 一步到位，superpowers brainstorming 把关）
You: /opsx:propose add-dark-mode
AI:  Created openspec/changes/add-dark-mode/
     ✓ proposal.md  ✓ specs/  ✓ design.md  ✓ tasks.md

# 编码 + 测试（OmO 执行 + superpowers TDD + OpenSpec apply）
You: ultrawork        # 或 /opsx:apply
AI:  ...RED→GREEN→REFACTOR... 逐项实现并打勾，LSP 诊断清零

# 审查
You: 触发 requesting-code-review / OmO /review-work
AI:  按严重度列出问题；Critical 修复后放行

# 提交 / CI
$  openspec validate --all
$  git commit -m "feat(ui): add dark mode toggle"   # 经 OmO work-with-pr 开 PR

# 归档
You: /opsx:archive
AI:  ✓ specs 合并  ✓ 移入 changes/archive/2025-01-23-add-dark-mode/

# 发布
You: 触发 finishing-a-development-branch / OmO /publish
AI:  验证测试 → merge / PR → 清理 worktree → 发版
```

展开档（适合更可控的逐步推进）：

```text
/opsx:new add-logout-button → /opsx:ff → /opsx:apply → /opsx:verify → /opsx:archive
```

并行多变更（被紧急 bug 打断时）：

```text
变更A: /opsx:new → /opsx:ff → /opsx:apply（进行中）
        ↓ 上下文切换
变更B: /opsx:new fix-login-redirect → /opsx:ff → /opsx:apply → /opsx:archive
回到A: /opsx:apply add-dark-mode   # 从上次的 task 继续
```

---

## 6. 常用命令速查

**OpenSpec CLI（终端）**
```bash
openspec init                 # 初始化
openspec update               # 切换/刷新命令与技能
openspec config profile       # 选择 core / 展开档
openspec list | view | show   # 浏览变更与 specs
openspec validate --all       # 校验（CI 可用）
openspec status               # 工件进度
openspec archive              # 归档
```

**OpenSpec 斜杠命令（在 agent 里）**
```
/opsx:explore  /opsx:propose  /opsx:apply  /opsx:sync  /opsx:archive          # core
/opsx:new  /opsx:continue  /opsx:ff  /opsx:verify  /opsx:bulk-archive  /opsx:onboard   # 展开档
```

**OmO（在 opencode 里）**
```
ultrawork / ulw          # 一词启动全员，做到完成
Tab → Prometheus         # 访谈式规划
/start-work              # 完整编排
/ulw-loop                # Ralph 自循环，直到 100% 完成
/review-work  /publish  /security-research  hyperplan(team)
```

**superpowers 技能（自动触发，也可手动 load）**
```
brainstorming · writing-plans · executing-plans · subagent-driven-development
test-driven-development · systematic-debugging · verification-before-completion
requesting-code-review · receiving-code-review · using-git-worktrees
finishing-a-development-branch · dispatching-parallel-agents · writing-skills
```

---

## 7. 排错与注意事项

- **OpenSpec 命令没出现 / 不是最新**：在项目里跑 `openspec update`；切档位用 `openspec config profile` 再 `openspec update`。
- **superpowers 插件没加载（opencode）**：
  - 看日志：`opencode run --print-logs "hello" 2>&1 | grep -i superpowers`
  - 确认 `opencode.json` 里 `plugin` 含 superpowers 的 git 包项；必要时清理 opencode/Bun 的包缓存或重装（git 包可能被 lockfile/缓存钉住旧 commit）。
  - **Windows**：部分构建对 `git+https` 插件有安装问题（找不到 `git.exe`/缓存路径）；可改用系统 npm 装好本地包后让 opencode 指向本地路径（见 superpowers `.opencode/INSTALL.md`）。
- **OmO 装错包**：务必 `oh-my-openagent install`，不要 `omo`。包名仍叫 `oh-my-opencode`（过渡期双发布为 `oh-my-openagent`）；`opencode.json` 内插件项优先用 `oh-my-openagent`，旧 `oh-my-opencode` 仍可加载但会告警。
- **模型选择**：OpenSpec 建议高推理模型（如 Codex 5.5 / Opus 4.7）；OmO 的 Sisyphus 在 Opus 4.7 / Kimi K2.6(或 K2.5) / GLM 5.1 上最佳，老 GPT 路由给 Hephaestus。
- **上下文卫生**：进入实现前清一下上下文窗口，全程保持干净（OpenSpec 官方强调）。
- **遥测**：均默认开启匿名遥测，可关：OpenSpec `export OPENSPEC_TELEMETRY=0`（或 `DO_NOT_TRACK=1`）；OmO 主插件 `OMO_DISABLE_POSTHOG=1`，Codex Light `OMO_CODEX_DISABLE_POSTHOG=1`。

---

## 8. 推荐落地顺序（给团队的最小化路径）

1. 装 **opencode** → 跑通空项目。
2. `openspec init` + `openspec config profile`（按需选档）→ 建 `openspec/config.yaml` 写入团队规范。
3. `opencode.json` 加 **superpowers** 插件 → 重启验证技能可用。
4. `bunx oh-my-openagent install` → 完成各模型鉴权 → `ultrawork` 跑通一次。
5. 用第 5 节的端到端示例做一个小变更（如 `add-dark-mode`），完整走一遍 立项→发布，验证四件套协同。
6. 把 `openspec validate --all` 接入 CI，把"归档"作为每个变更的收尾习惯。
