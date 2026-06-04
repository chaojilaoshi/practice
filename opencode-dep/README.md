# opencode-dep

基于四个开源仓库组合的「规范驱动 + 技能方法论 + 多模型编排」端到端 AI 研发工作流的配置、使用教程与可运行 demo。

覆盖阶段:**立项(intake) → 需求(specs) → 设计(design) → 计划(writing-plans) → 编码(coding+TDD) → 测试(testing) → 审查(review) → 提交(pipeline/CI) → 归档(opsx-archive) → 发布(release)**

## 工具栈分工

| 仓库 | 角色 | 负责 |
| --- | --- | --- |
| [opencode](https://github.com/chaojilaoshi/opencode) | 运行宿主(harness) | TUI / 插件机制 `opencode.json` |
| [oh-my-openagent (OmO)](https://github.com/chaojilaoshi/oh-my-openagent) | 多模型编排层(插件) | `ultrawork` / `hyperplan` / `team`、LSP、内置 MCP |
| [superpowers](https://github.com/chaojilaoshi/superpowers) | 方法论层(skills 插件) | brainstorming→writing-plans→TDD→review→finishing |
| [OpenSpec](https://github.com/chaojilaoshi/OpenSpec) | 规范驱动层(CLI + 斜杠命令) | `/opsx:*`,proposal/specs/design/tasks + 归档 |

## 目录内容

| 文件 | 说明 |
| --- | --- |
| [`workflow-tutorial.md`](./workflow-tutorial.md) | 配置与使用完整教程(四件套安装、`opencode.json` 叠加、阶段↔命令对照、端到端示例) |
| [`extra-skills-mcp-plugins.md`](./extra-skills-mcp-plugins.md) | 可叠加的开源 Skills/MCP/插件清单(避开 OmO 已内置项;含质量门禁组合) |
| [`workflow-step-by-step-demo.md`](./workflow-step-by-step-demo.md) | 十阶段逐步操作 + 真实跑通的 demo 输出 |
| [`demo/darkmode-app/`](./demo/darkmode-app) | 可复现 demo 工程 |

## demo 工程速览(`demo/darkmode-app/`)

- `opencode.json` — 三插件 + 质量门禁 MCP(memory / sequential-thinking / semgrep / github)的参考配置
- `openspec/` — 一个走完整生命周期并已归档的变更(`add-dark-mode`):delta 已并入 `openspec/specs/dark-mode/spec.md`,变更存于 `openspec/changes/archive/2026-06-04-add-dark-mode/`
- `.opencode/` — `openspec init --tools opencode` 生成的 `/opsx:*` 命令与技能
- `src/theme.js` + `src/theme.test.js` — TDD 红绿灯示例(纯函数 `resolveTheme`)

### 复现

```bash
# 1) TDD 测试(无需任何依赖,需 Node ≥ 20.19)
cd opencode-dep/demo/darkmode-app/src
node --test                 # 3 passed

# 想看 RED→GREEN:把 theme.js 改回 throw new Error('not implemented'),再 node --test → 3 fail

# 2) OpenSpec(需先 npm i -g @fission-ai/openspec@latest)
cd opencode-dep/demo/darkmode-app
openspec list --specs       # dark-mode  requirements 2
ls openspec/changes/archive # 2026-06-04-add-dark-mode
```

> 说明:opencode / OmO / superpowers 的运行需模型鉴权,教程中给出逐字命令与预期产物。OpenSpec 与 `node --test` 部分的输出均为真实运行结果。基于上游官方仓库编写;若 fork 改过命令/内置项,以仓库实际为准。
