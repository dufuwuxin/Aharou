# 外部 CLI（rtk / Claude Code / aider / Codex）在 Aharou 里到底是哪一层

> 调研对象：rtk-ai/rtk、Anthropic Claude Code、aider、OpenAI Codex CLI。
> 对比基线：Aharou 已有的 tool_search + 延迟加载 + MCP + 容器命令。
> 只读调研，未改任何代码、未安装任何软件。

---

## ① 一句话结论

**它们都不是 `ToolRegistry` 意义上的「工具」。** 在 Aharou 里，一个外部 CLI 只可能以三种身份存在：**(a) 容器里的一条可执行命令**（经 `Bash`/`ExecuteCommandTool` 调用）、**(b) 一个 MCP server**（仅当该 CLI 自己对外提供 MCP 协议）、**(c) 一份 skill 里的 markdown 安装/使用说明**。能不能「选择添加」，取决于它有没有 MCP 接口，**跟它是不是 CLI 无关**；而 rtk 的省 token 优势在 Aharou **无法自动生效**——它靠挂在「宿主 Agent 的 Bash 工具」上的 hook 工作，Aharou 就是宿主本身、且已有一套等价的输出压缩基线，所以 rtk 净收益边际。把 Claude Code / aider / Codex 当工具集成是**负收益**（它们是与 Aharou 竞争的完整 Agent，嵌套运行只会更费 token）。

---

## ② 分层判定

Aharou 里的「扩展点」只有四种，外部东西必须落到其中之一：

| 层 | 是什么 | 关键证据（file:line） |
|---|---|---|
| **工具 Tool** | Kotlin 类继承 `AgentTool`，有 name/description/parameters/schema/permissionPolicy/capabilities，由 DI 注册进 `ToolRegistry` | `AgentTool.kt:212`（`deferredLoading`）、`ToolRegistry.kt`（`register`/`getDeferredTools`） |
| **MCP** | `manageMcp` 增删 server；本地 stdio server 由容器进程拉起，工具 `deferredLoading=true`、命中才展开 | `mcp/ManageMcpTool.kt:38,184,209`；`McpTool.kt:74,80`；`mcp/StdioTransport.kt:28`（注释：本地是 proot 容器内 `npx -y some-server`）；`LinuxContainerEngine.kt:755`（`startStdioProcess`）；`McpManager.kt:266`（动态注册） |
| **skill** | 一份 `SKILL.md`（markdown 指令正文），`loadSkill` 把正文返回给模型，模型再用 read/execute 照做 | `skill/LoadSkillTool.kt`（全文）；`skills/*/SKILL.md` |
| **容器命令** | `Bash` 工具在 proot 容器里跑任意 shell | `container/ExecuteCommandTool.kt:104`（`name="Bash"`）、`:108-109`（`ASK` + `EXECUTE_COMMANDS`） |

### 每样东西落到哪一层

- **rtk**：本质是「**宿主 Agent 的 Bash 工具输出压缩代理**」，通过 PreToolUse hook 把 `git status` 改写成 `rtk git status`（来源：rtk README / rtk-ai.app）。它支持 18 个宿主（Claude Code、Cursor、Codex、Gemini CLI…），**Aharou 不在其中**，且 Aharou 的 `Bash` 是本仓库自己的 `ExecuteCommandTool`，没有 rtk 需要的那种 hook 注入点。→ **落「容器命令」层**（只能显式敲 `rtk ...`），或落「skill」层（教模型给命令加 `rtk` 前缀）。
- **Claude Code / aider / Codex CLI**：它们各自是**完整的 Agent（自带循环+模型+工具集）**，语义上是「另一个 Aharou」，**不是工具**。→ 只能当「容器命令」被 `Bash` 拉起（变成**嵌套 Agent**），或当「skill」记录安装步骤。
- **想「选择添加」的通用答案**：唯一现成的、用户可自助添加且在容器里跑的一等公民扩展点是 **MCP**。若某个 CLI **自带 MCP server 模式**，就能用 `manageMcp add_stdio` 加进来；若不带（rtk / Claude Code / aider / Codex 都不是 MCP server），**就没有「添加」入口**，只剩容器命令 / skill 两条路。

---

## ③ 能否安装与运行（容器现实约束）

**技术上能装。** Aharou 容器是 proot 下的完整 Linux（Alpine/Ubuntu rootfs），有网络、npm/pip 走代理（`LinuxContainerEngine.kt:947`）。

- **node/npm 就位情况**：首次进终端 `provision.sh:152` 会 `ensure_runtime node node || true`（自动尝试、失败不致命）；「环境安装」菜单的 node 场景 `env-common.sh:133`（`apk:node → "nodejs npm"`）可补装。→ **通常是有的，但不是硬保证**（依赖首次 provisioning 成功 + 网络）。
- 因此 `npm i -g @anthropic-ai/claude-code`、`pip install aider-chat`、`curl … | sh`（rtk）在容器里**理论上可装可跑**。

但有几道硬约束：

1. **没有「选择添加外部 CLI」的一等公民机制**：代码里只有 `manageMcp`（加 MCP server），没有 `addCliTool` 之类。要「在设置里勾选安装某 CLI」得新写 UI+安装器，目前不存在。
2. **每次装/跑都要过授权弹窗**：`Bash` 的 `permissionPolicy=ASK`（`ExecuteCommandTool.kt:108`），安装命令、运行命令都逐条让用户确认。
3. **容器重装会清掉装的东西**：`ContainerInstaller.kt:292` 注释——`INSTALL_VERSION` 一变就整体清空重装，`apk add` 装的包会丢，得重装。
4. **proot 兼容坑**：老 proot 的 seccomp 表缺 `statx`、Node 会用 statx（`LinuxContainerEngine.kt:917` 注释），个别二进制/依赖可能踩坑。

结论：**「能装能跑」= 是；「能像工具一样被模型/用户在设置里『选择添加』」= 否（MCP 除外）。**

---

## ④ token 账

**口径说明**：token 节省只针对「喂回模型的工具输出 / 工具定义」这一块，不是整段 prompt。以下数值一律给方向与成因，不编具体数字。

- **rtk**：官方口径是「常见开发命令的 bash 输出省 60–90%」（rtk README / rtk-ai.app，计量口径＝被它拦截的那部分命令输出）。但落到 Aharou：
  - hook 不生效 → 只能显式 `rtk ...` 或改造 `ExecuteCommandTool`；
  - Aharou 的 `Bash` 输出**在交给模型前已经过 `BoundedOutput` 限幅 + `ContainerOutputSanitizer` 清洗**（`ExecuteCommandTool.toModelOutput`）——**已有压缩基线**。
  - 所以 rtk 的净收益 = 它比现有清洗**多压出来的那一部分**，**未核验、大概率边际**。
- **Claude Code / aider / Codex 当嵌套 Agent 跑**：把它们自己的 system prompt + 工具 schema + 多轮历史塞进 stdout 返回给 Aharou 模型，等于**双份 Agent 上下文**，token **显著增加**而非节省。方向性成因：一次嵌套至少多一整套 system prompt + 其工具定义（量级在数千~上万 token/次，随其工具数增长；不写精确值）。
- **MCP（现有能力）**：这才是 Aharou 已验证的**省 token 路径**——`deferredLoading` 让工具 schema 平时不进每轮 tools 数组，`ToolSearchTool` 命中才展开（其注释直言「几个 server 能吃掉几十万 token」）。加外部能力优先复用这条路。

---

## ⑤ 推荐落地顺序与「不值得做」清单

**按性价比排序（从便宜到贵）：**

1. **先别加，先验证现有 MCP + tool_search 够不够**（0 成本）。多数「想装某个 CLI」的诉求，其实是想要它的某个能力，而该能力往往已有对应 MCP server。
2. **优先找该 CLI 的 MCP server 形态**，用 `manageMcp add_stdio npx …`（`ManageMcpTool.kt:184`）挂进来——**0 代码**，且天然享受 `deferredLoading` 省 token。这是「选择添加、容器里用」的正解。
3. **能力只有 CLI 有、又无 MCP**：写**一份 skill**（`SKILL.md` 里写安装命令 + 用法），让模型按需 `npm i -g` / `pip install` 后调 `Bash` 运行。成本＝一份 markdown；代价＝每次安装要用户授权；**只对「非 Agent 型」的纯工具 CLI 值得**（如某个专用格式化器/转换器）。
4. **仅当确有大量 bash 输出 token 痛点**，才评估「把 `ExecuteCommandTool` 的输出管道接到 rtk」：成本＝装 rtk 二进制 + 改 `toModelOutput` 走 rtk 过滤器 + 处理 filter 失败的 fallback；收益**未核验**。

**不值得做：**

- ❌ 把 **Claude Code / aider / Codex** 当「工具」或「选择添加」项集成：它们是与 Aharou 竞争的完整 Agent，嵌套运行**费 token**、要各自管理 API key、维护面大，还与 App 现有的权限/审批/多会话体系冲突。**收益为负或近零。**
- ❌ 为「选择添加外部 CLI」**另造一套注册体系**：MCP 已经是这套体系，重复造轮子。
- ❌ 为了 rtk 去改宿主 hook 架构：Aharou 已用 `BoundedOutput` + `ContainerOutputSanitizer` 得到压缩，追 rtk 那部分边际收益不划算（除非实测证明差距很大）。

---

## ⑥ 未核验项

- **rtk 在 Aharou 的 proot/Alpine 容器里实际能否跑**：README 称「single Rust binary, zero dependencies」，静态二进制理论上可在 Alpine 跑；但**未在 Aharou 容器实测**（本次只读，未安装）。
- **rtk 对 Aharou 的 hook 支持**：基于其 README 列出的 18 个宿主中**没有 Aharou**推断「只能显式调用、无法自动改写」；rtk 是否有面向自定义宿主的通用代理模式未逐条核验。
- **Claude Code / Codex / aider 当前的确切包名、是否需 OAuth/登录、能否在 proot 内正常鉴权**：仅确认「可装方向」，未逐一实测。
- **node/npm 是否在每台设备容器里真的就位**：依赖首次 `provision.sh` 成功 + 网络，**未在真机核验**。
- **rtk 宣称的 60–90% 对 Aharou 命令集的实际净收益**：未核验（且 Aharou 已有压缩基线，需实测对比）。
- 抓取 `https://github.com/rtk-ai/rtk` 时**直连页面超时**，内容取自搜索引擎对该仓 README / 官网的回读摘要，**未能亲自完整打开原页逐行核对**。
