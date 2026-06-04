# 全流程详细使用步骤 + 可运行 Demo

> 技术栈:**opencode**(运行宿主) + **oh-my-openagent/OmO**(多模型编排) + **superpowers**(方法论 skills) + **OpenSpec**(规范驱动) + 质量门禁 MCP(Semgrep/Memory/Sequential-Thinking/GitHub)。
> 本文每个阶段给出「逐字命令 + 预期产物」,并附一个**真实跑通**的 demo(`add-dark-mode`)。Demo 中 OpenSpec / Node 测试部分的输出均为本机实跑结果;opencode/OmO 因需模型鉴权,给出命令与预期。

---

## A. 一次性安装(约 10 分钟)

```bash
# 1) 运行宿主
npm i -g opencode-ai@latest                 # 或 brew/scoop

# 2) 规范层(需 Node ≥ 20.19)
npm i -g @fission-ai/openspec@latest
openspec --version                          # 实测: 1.4.1

# 3) 进入你的项目,初始化 OpenSpec(为 opencode 生成命令/技能)
cd your-project
openspec init --tools opencode --profile core
```

`openspec init` 实测输出:
```text
OpenSpec Setup Complete
Created: OpenCode
5 skills and 5 commands in .opencode/
  Start your first change: /opsx:propose "your idea"
```
生成结构:
```text
.opencode/commands/opsx-{propose,explore,apply,sync,archive}.md
.opencode/skills/openspec-*/SKILL.md
openspec/{specs,changes/archive}/
```

```bash
# 4) 方法论层 + 编排层:写入 opencode.json(与本 demo 同款,见 ./opencode.json)
#    - plugin: superpowers(git 包) + oh-my-openagent
#    - mcp:    memory / sequential-thinking / semgrep / github(质量门禁)
# 5) 安装 OmO(交互式 TUI,完成各模型鉴权)
bunx oh-my-openagent install
# 6) 重启 opencode,验证:
#    在对话里输入:  use skill tool to list skills
#    应能看到 superpowers/* 与 openspec-* 技能
```

> 可选切换展开档(更细的命令 `/opsx:new /continue /ff /verify /bulk-archive`):
> `openspec config profile` → 选 custom → `openspec update`。

---

## B. 十阶段逐步操作(每步:做什么 → 命令 → 产物)

下表是"驾驶舱速查",其后是带 demo 的详解。

| 阶段 | 在 opencode 里输入 / 终端命令 | 产物 |
| --- | --- | --- |
| 1 立项 | `/opsx:explore` → 描述想法(brainstorming 自动把关) | 方向确定、设计草稿 |
| 2 需求 | `/opsx:propose "add dark mode"` | `proposal.md` + `specs/<cap>/spec.md`(delta) |
| 3 设计 | 同上自动产出 / 追问细化 | `design.md` |
| 4 计划 | writing-plans 技能 + `tasks.md` | 可勾选任务清单 + 计划文档 |
| 5 编码+TDD | `ultrawork` 或 `/opsx:apply`;TDD 强制 RED→GREEN | 代码 + 单测,tasks 打勾 |
| 6 测试 | 跑测试 + LSP 诊断 + `verification-before-completion` | 全绿、诊断清零 |
| 7 审查 | `requesting-code-review` / OmO `/review-work` + **Semgrep MCP** | 评审报告,Critical 阻断 |
| 8 提交/CI | `openspec validate --all` + OmO `work-with-pr` / **GitHub MCP** | PR、CI 通过 |
| 9 归档 | `/opsx:archive`(或 `openspec archive <name> -y`) | delta 并入 `specs/`,移入 `changes/archive/` |
| 10 发布 | `finishing-a-development-branch` / OmO `/publish` | 合并 / 发版 |

---

## C. 跟着 Demo 走一遍(add-dark-mode)

> Demo 工程已打包在 `darkmode-app/`,可直接 `cd darkmode-app` 复现。

### 阶段 1–4:立项 / 需求 / 设计 / 计划

在 opencode 里(自然语言驱动):
```text
You: /opsx:explore
You: 我想加一个深色模式,支持跟随系统,并记住用户选择
AI : (brainstorming 技能触发,逐条提问对齐意图;HARD-GATE 未批准不写码)
You: /opsx:propose add-dark-mode
AI : Created openspec/changes/add-dark-mode/
     ✓ proposal.md  ✓ specs/  ✓ design.md  ✓ tasks.md
```

**等价的 CLI 复现(本机实跑)**:
```bash
openspec new change add-dark-mode
# Created change 'add-dark-mode' at openspec/changes/add-dark-mode/
```
随后 AI(或你)按模板填好四类工件。Demo 里已填好真实内容,见:
- `openspec/changes/add-dark-mode/proposal.md`(Why / What / Capabilities / Impact)
- `openspec/changes/add-dark-mode/specs/dark-mode/spec.md`(ADDED Requirements + Given/When/Then 场景)
- `openspec/changes/add-dark-mode/design.md`(Context/Goals/Decisions/Risks)
- `openspec/changes/add-dark-mode/tasks.md`(3 组共 10 个可勾选任务,含"先写测试")

**校验工件健康度(实跑输出)**:
```text
$ openspec validate add-dark-mode
Change 'add-dark-mode' is valid

$ openspec status --change add-dark-mode
Progress: 4/4 artifacts complete
[x] proposal  [x] design  [x] specs  [x] tasks
All artifacts complete!

$ openspec list
Changes:
  add-dark-mode     0/10 tasks    just now
```

### 阶段 5–6:编码 + TDD + 测试

在 opencode 里:
```text
You: /opsx:apply        # 或直接  ultrawork
AI : 按 tasks.md 执行;test-driven-development 技能强制:
     先写失败测试(RED)→ 看它失败 → 写最小实现(GREEN)→ 看它通过 → 重构 → 提交
```

**真实 TDD demo(本机用 `node --test` 实跑,无需任何依赖)**

`src/theme.test.js`(先写测试):
```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveTheme } from './theme.js';

test('explicit dark wins over system', () => assert.equal(resolveTheme('dark', false), 'dark'));
test('explicit light wins over system', () => assert.equal(resolveTheme('light', true), 'light'));
test('system follows OS prefers-dark', () => {
  assert.equal(resolveTheme('system', true), 'dark');
  assert.equal(resolveTheme('system', false), 'light');
});
```

RED — 实现尚未完成,跑测试(实跑输出):
```text
$ node --test
# tests 3
# pass 0
# fail 3        ← 红灯:确认测试确实在测东西
```

`src/theme.js`(写最小实现):
```js
export function resolveTheme(preference, systemPrefersDark) {
  if (preference === 'dark' || preference === 'light') return preference;
  return systemPrefersDark ? 'dark' : 'light';
}
```

GREEN — 再跑(实跑输出):
```text
$ node --test
# tests 3
# pass 3
# fail 0        ← 绿灯:最小实现通过
```

> 测试阶段还可借助 OmO 内置 **LSP MCP**(诊断/跳转)与 **Playwright**(e2e),以及 superpowers 的 `verification-before-completion`(声明"完成"前必须用证据验证)。

### 阶段 7:审查(双保险)

```text
You: 触发 requesting-code-review(派评审子 agent,按严重度报告,Critical 阻断)
You: OmO /review-work        # 评审产出
# 安全门禁(质量保证):Semgrep MCP 做确定性静态扫描
```
在对话中让 agent 调用 Semgrep MCP:
```text
Run a Semgrep security_check on the changed files and summarize High/Critical findings.
```
约定:**High/Critical 未清不得进入提交**(写进 `AGENTS.md` / `.omo/rules`)。

### 阶段 8:提交 / CI

```bash
# 规范健康度(可放进 CI)
openspec validate --all
# 提交(Conventional Commits 单行)
git add -A
git commit -m "feat(ui): add dark mode with system preference + persistence"
```
在 opencode 里用 OmO `work-with-pr` 或 **GitHub MCP** 开 PR、读 CI、回评论:
```text
You: 用 github MCP 创建 PR,标题用上面的 commit,正文附 Semgrep 摘要;然后查 CI 状态
```
CI 建议至少包含:`openspec validate --all` + 单测 + Semgrep。

### 阶段 9:归档(opsx-archive)

实现完成、tasks 全部勾上后归档。**本机实跑全过程**:
```text
# (先把 tasks.md 里的 [ ] 全部改为 [x],代表实现完成)
$ openspec archive add-dark-mode -y
Task status: ✓ Complete
Specs to update:
  dark-mode: create
Applying changes to openspec/specs/dark-mode/spec.md:
  + 2 added
Change 'add-dark-mode' archived as '2026-06-04-add-dark-mode'.
```
归档后(实跑):
```text
$ openspec list
No active changes found.
$ openspec list --specs
Specs:
  dark-mode     requirements 2
```
变化:
- delta 已并入主规范 `openspec/specs/dark-mode/spec.md`(2 条 Requirement)
- 变更整体移入 `openspec/changes/archive/2026-06-04-add-dark-mode/`(留审计历史)

> 多个变更一次归档:`/opsx:bulk-archive`(展开档)。归档前如需先并入主 specs:`/opsx:sync`。

### 阶段 10:发布(release)

```text
You: 触发 finishing-a-development-branch
AI : 1) 验证测试全绿 → 2) 给出选项(merge / 开 PR / 保留分支 / 丢弃)→ 3) 清理 worktree
You: OmO /publish            # 或 get-unpublished-changes 查未发布内容
```

---

## D. 复现本 Demo(把上面的实跑结果在你机器上重跑)

```bash
# 1) 装 OpenSpec
npm i -g @fission-ai/openspec@latest

# 2) 解压 demo 后进入
cd darkmode-app

# 3) 规范侧:校验 / 看状态 / 列表
openspec validate add-dark-mode        # 注意:本 demo 已归档,改用下面命令看归档态
openspec list --specs                  # dark-mode  requirements 2
ls openspec/changes/archive            # 2026-06-04-add-dark-mode

# 4) TDD 侧:真实跑测试
cd src && node --test                  # 3 passed

# 想体验"从头到归档",可改名重跑:
openspec new change my-feature
# ...填工件... 
openspec validate my-feature
openspec archive my-feature -y
```

> 想看"红灯→绿灯":把 `src/theme.js` 临时改回 `throw new Error('not implemented')`,`node --test` 会变 3 fail;改回实现即 3 pass。

---

## E. 一页纸速记

```text
立项  /opsx:explore                      (brainstorming 把关, HARD-GATE)
需求  /opsx:propose <idea>               → proposal.md + delta specs
设计  (自动) design.md
计划  writing-plans + tasks.md
编码  ultrawork | /opsx:apply  + TDD(RED→GREEN→REFACTOR)
测试  node --test / vitest + LSP + verification-before-completion
审查  requesting-code-review | /review-work + Semgrep(High/Critical 阻断)
提交  openspec validate --all + git commit(conventional) + GitHub MCP 开 PR
归档  openspec archive <name> -y         → 合并进 specs/ + 移入 archive/
发布  finishing-a-development-branch | OmO /publish
```

## F. 提醒
- opencode/OmO 步骤需先完成模型鉴权(`bunx oh-my-openagent install` 里的 provider 登录),否则 `ultrawork` 等无法工作。
- 质量门禁 MCP(Semgrep 需本机 Semgrep CLI / `uvx`;GitHub MCP 远程版需 OAuth 授权)。
- 本 demo 的 OpenSpec 与 `node --test` 输出均为真实运行结果;若你的 `chaojilaoshi/*` fork 改过命令/内置项,以仓库实际为准。
