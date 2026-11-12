# Agent 与用户同权能力台账

这张表把“用户在界面上能做的事”和“agent 有没有对应工具”逐条对齐，用来暴露缺口，不用来论证应该少给 agent 工具。**每次新增用户可见功能（设置页新分区、编辑器新操作）或增删工具时都要回来核一遍**，等新增工具后再把对应行的“无”划掉。

## 本轮新增能力

- 跨模型执行契约：主代理与成员分别使用新增的原生执行条目；六来源适配索引仅检索，旧库升级只补新 ID，用户修改／停用／删除保留。`task-execution` 是内置按需技能，`tool_search` 明确目录、激活、执行的区别；工具权限与 schema 不变。原生条目仍由记忆工具和 config 同入口管理。确定性回归可证明迁移和注入边界，不能替代真实模型行为对照。

- 世界书记忆：原生条目内持久化触发/排除、匹配方式、受众与精确角色/标签、优先级、用户标注的可信度与重要性、检索标签和关键词/标签/关系权重；预览与请求同选择器，未命中/停用/跨会话不注入。结构化表单与高级 JSON 写回同一原生条目；config 通过 `context_library.entries.<id>.worldbook` 读写。
- 记忆关系图谱：唯一可见目标解析、歧义/缺失显式显示；稳定身份保留改名引用，Canvas 有限绘制与分页关系列表不造节点，不改变权限或激活。
- 云端查询：`memory_cloud_search` 为默认关闭的显式 HTTPS 公共端点查询；`memory.cloud_search.enabled/endpoint` 与原设置页共享可观察状态。只外发明确 query/limit，不外发全库、聊天、路径或凭据；结果始终标为未核验参考，不自动保存或注入。
- 角色标签：原定义 YAML、编辑器和 `subagent.roles.<id>.tags` 共用角色保存入口；标签只收窄世界书受众，不赋予工具权限，旧 allow/deny 与脚本身份仍有效。
- 会话诊断：普通聊天与项目页面统一提供上下文、对话地图、活动回执、上下级成员四页签；项目常显上下文状态栏，不依赖普通聊天头部。占用与预算来自同次请求，当前模型预算另列；实际注入来自最终请求快照，缺快照明确说明。地图仍只覆盖已加载记录，普通正文可定位、项目替换正文只读；成员观察与回执不合并成虚构消息，按会话身份隔离。
- 原生包市场：原插件页保留已安装/Aharou包市场与导入启停卸载；新增Operit2公开市场页签，真实分页/类型筛选/当前页搜索/详情/最新版本资产及校验和，文件通过系统文档选择器保存；技能复用GLOBAL导入且不覆盖同名，取消选择不下载。ToolPkg、脚本及MCP只下载，不冒称具备Operit宿主API或已安装运行。没有发布资产时显式区分GitHub指定分支/子目录的源码ZIP，不冒充市场发布版本；缺校验和标未核验。
- WARN/MAX：预警先精简工具历史，MAX 触发压缩且在实际发送前拒绝仍超限的请求；不删除持久历史。模型目录新增官方单文件 Qwen2.5 3B Q4_K_M、7B Q3_K_M，未设备推理不冒充已测。

- 本地 JavaScript 纯计算：`eval_javascript` 经原生注册器与 `tool_search` 按需发现，使用原有工具授权系统，不增加默认注入或宿主权限。返回最后表达式的 JSON 值及 console 日志；源码、输出、日志、内存、栈与原生执行时限均有界，不提供 DOM/Node/Java/网络/文件/进程桥。没有独立脚本运行 UI，不增加影子屏；手机端加载、执行和性能尚未验证。QuickJS 1.0.15 需要 Kotlin 2.4 元数据；编译器已同步升级至 2.3.21、R8 至已发布的 9.1.57，仍需真实构建与 JNI 测试验收。

- 原生记忆库：配置页是唯一入口，`ContextLibrarySection` 提供内容库、注入预览及关系图谱；规则、临时存放、长期、角色注入、主代理注入五类支持文件夹与搜索编辑，不提供旧页面跳转或来源开关。应用管理的注入文字（含内置默认文字）先写入原生库，用户可查看、编辑、停用与删除，不从旧提示文件或角色正文回退。
- 原生条目工具：`memory` 的 `library_list/library_search/library_read/library_save/library_delete` 使用条目ID和版本；`save` 支持 `category/folder/context_kind/context_load`，临时内容绑定真实调用会话，待确认状态保留。
- 配置同权：`context_library.entries.<hash>.category/loading/kind/folder/session_id` 与页面共用仓库，`context_library.folders` 可新增空文件夹。加载只有 FULL/INDEX/SEARCH/OFF；预览是规划结果，不是历史请求记录。
- 使用边界：新库只读写原生记忆，不聚合其他持久来源或 sidecar；不机械导入旧提示文件，不删除用户数据。项目绑定、编辑版本、会话隔离和注入预算继续生效。不声称保存即跨设备同步。
- 团队执行：主代理只得到团队名称与介绍，通过 `task teams/team_run` 交付团队；不获得成员名单、阶段提示或成员消息，不直调成员。`sessions` 的列表、搜索、读取、工具调用和上下文入口同样隔离成员会话；内部成员引擎保留。
- 项目删除：长按项目行后在菜单选择删除，继续使用原有确认框与可删除条件；项目行不常显删除按钮。

- 可执行工具箱：工具页默认提供参数表单、命令风险预览、手动运行、退出码/截断、单条原格式JSON预览导入和导出分享；保存只创建、不覆盖同名，执行复用CustomCommandTool与原权限策略，不经过模型或自动安装。编辑器设置入口复用原页面，实际LSP补全仍需编辑器上下文与已安装服务。
- 代码结构查询：`code_structure` 经原注册器、只读权限和 `tool_search` 接入；`aharou-code` 随原 bin/lib 资源提取，提供 Kotlin/Rust/Python 的限额新鲜结构快照、符号及词法调用点候选，action=check提供三语言词法/括号和Python AST检查，明确非编译验证，不执行源码、不替代语义分析或编译。展开状态按会话与工作区隔离；CodeGraph 状态/索引仅为需保存的 ASK 命令草稿，安装及 MCP 接入由现有入口负责，不默认下载或授权。

- OpenAI 速度档位：供应商选项中的默认/Fast/Ultrafast 与 `config providers.<id>.service_tier` 同步；复用自定义请求头保存，实际请求体也发送 `service_tier`，Ultrafast 走 Responses，不等同于思考强度。不自动替用户选择收费档位。
- 创建项目只需名称和项目存在的意义：本地保存独立工作区中的空项目，无需 AI 供应商；创建期间防重复提交，成功后立即更新列表并进入项目，若已切换页面则不抢回选择。项目意义与需求分开展示，旧项目原有需求保留。创建后可直接在主会话提问，普通问答不规划。用户先在更多菜单或现有任务／分支下添加命名空需求分支，创建不调模型、不执行；选中后在既有完整输入栏讨论或提出需求，AI 只在该分支下生成若干执行任务，失败的规则拆分也固定父级。需求分支不计任务数、无负责人／执行状态／验收，旧普通任务和历史结果不转换、不删除。成员可用 `project_tasks` 分出研究分支或追加子任务，分支和补充让当前任务回到待执行，流程续跑而不中止；项目意义仅作背景，不作为执行需求。
- 项目完整树图/导图：节点内展开过程，负责人和成员逐个真实执行，同名角色有独立记录；修改参与者使本任务及下游交付失效。用户验收与成员交付仍区分。
- 构建日志：`aharou-build android <task> / rust / python / logs` 与四个需用户保存才启用的工具模板共用现有执行授权；本地默认日志挂载、显式覆盖及私有回退，不自动收集第三方日志或保证脱敏。Void 的 Python/Rust 已在独立rootfs实跑，Android工具链及手机挂载仍不得宣称验证通过。

## 0. 工具集合与事实来源

- 内置工具的唯一注册点是 `app/src/main/java/com/aharou/di/AgentModule.kt`，共 **40 个**：`readFile` `sendFile` `viewImage` `writeFile` `editFile` `generateImage` `Bash` `Shizuku` `terminal` `list` `search` `loadSkill` `loadRule` `knowledge_search` `askUserQuestion` `manageMcp` `tool_search` `websearch` `webfetch` `planMode` `todo` `memory` `task` `messageParent` `observe` `browser` `config` `vscreen` `a11y` `open_app` `check_dependencies` `sessions` `project_tasks` `device` `personal_context` `petAvatar` `diagnostics` `git_blame` `code_structure` `eval_javascript`。
- MCP 工具在连接时动态注册（`feature/agent/domain/mcp/McpManager.kt:266`，卸载见 `:272` / `:354`）。
- 每轮注入：工具集经成员允许清单、`ToolInjectionGate` 的 every_round/keyword/off 策略过滤，并应用兼容的描述与 schema 说明覆盖；不能把注册工具总数当作每轮实际可见数量。MCP、代码结构与 JavaScript 计算工具的延迟加载由原有检索链路（`tool_search`）处理，展开记录限定当前会话和工作区，重启或较久未使用后可重新发现。
- `config`（`di/AgentModule.kt:475`，实现 `feature/agent/domain/tool/config/ConfigTool.kt`）是一层**通用配置接口**：agent 能改什么，取决于 `ConfigRegistry` 里注册了哪些字段，注册入口集中在 `AIEditorApp.kt:409-434`（24 个 `Config*Fields`）。下文写“config `path`”即指这条路径。
- 权限档只影响“执行时要不要弹确认”（`ToolPermissionPolicyEngine`），**不影响工具有没有**；本表只统计有没有。

## 1. 自动化任务

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 查看任务列表（名称/启停/周期/下次运行/上次结果） | `feature/settings/presentation/component/ScheduledTasksSection.kt:79`、行渲染 `:132`、`:297` | 有 | `config` `action=get path=scheduledTasks`（`AgentModule.kt:475`；集合 `feature/agent/data/ConfigScheduledTaskFields.kt:75`） | 无独立任务工具，只有 config 集合；要先 `get scheduledTasks` 拿 id 再逐条 `get`，没有“一屏看全表”的动作 |
| 新建任务 | `ScheduledTasksSection.kt:147`（新建入口）、`:161`（`viewModel.save`）、编辑器 `:464` | 有 | `config` `action=add path=scheduledTasks`（`ConfigScheduledTaskFields.kt:341`） | add 只收 `name/prompt/schedule_type/...` 白名单字段，其余沿用实体默认值；漏传 `once_at_millis` 时 once 类型会被拒（`:365`） |
| 编辑任务（指令/周期/每天时刻/单次时刻/运行方式/触发方式/触发条件/目标会话/工作区/运行上限/防抖） | `ScheduledTasksSection.kt:133`（点行进编辑器） | 有 | `config` `action=set path=scheduledTasks.<id>.<字段>`（字段清单 `ConfigScheduledTaskFields.kt:88-338`） | 字段级写入会顺带重排调度（`:104`），无缺口 |
| 暂停 / 启用 | `ScheduledTasksSection.kt:132` | 有 | `config` `action=set path=scheduledTasks.<id>.enabled`（`ConfigScheduledTaskFields.kt:230` 区段） | 无 |
| 删除任务 | `ScheduledTasksSection.kt:197`（确认框）、滑删 `:238` | 有 | `config` `action=remove path=scheduledTasks id=<id>`（`ConfigScheduledTaskFields.kt:421`） | 无 |
| 看上次运行结果 / 已运行次数 / 下次运行 | `ScheduledTasksSection.kt:297`（`outcomeLabel(task.lastOutcome)`） | 有 | `config` `action=get` 只读字段 `run_count` / `next_run_at` / `last_run_at` / `last_outcome`（`ConfigScheduledTaskFields.kt:322-338`） | 无 |
| 手动跑一次 | 未找到（UI 无入口；`ScheduledTasksViewModel.kt:24-98` 只有 `save/delete/setEnabled`） | 无 | 未找到（config 通道也没有 run 动作，`ConfigTool.kt:113-150`） | 双方都没有：这条不是 agent 缺口，是功能缺口 |

## 2. 记忆

| 用户能做什么 | 界面入口 | Agent 对应能力 | 边界 |
|---|---|---|---|
| 分类、搜索与筛选条目 | `component/ContextLibrarySection.kt` | `memory` 的 `library_list/library_search` | 五类原生内容；临时条目按实际会话隔离 |
| 读取、编辑、删除条目 | `component/ContextLibraryEditor.kt` | `memory` 的 `library_read/library_save/library_delete` | 更新必须使用最新版本；项目内容校验工作区 |
| 新建条目 | `component/ContextLibraryEditor.kt` | `memory` 的 `save`，支持 category/folder/context_kind/context_load | 临时内容绑定调用会话；未确认信息保持参考属性 |
| 修改分类、加载与文件夹 | `component/ContextLibraryEditor.kt` | `config` 的 `context_library.entries` | FULL/INDEX/SEARCH/OFF，与页面共用仓库 |
| 创建空文件夹 | `component/ContextLibrarySection.kt` | `config` 的 `context_library.folders` | 文件夹不改变权限与隔离范围 |
| 删除文件夹 | `component/ContextLibrarySection.kt` | 仅可在 UI 按类别、全局/项目范围确认删除 | 条目移到父目录并保留正文；config 不开放批量移动和删除目录 |
| 查看注入规划 | `component/ContextLibraryEditor.kt` 的 `ContextLibraryPreview` | 未提供独立工具动作 | 不是历史请求记录；不代表实际模型行为 |

记忆页不再提供其他页面的来源、维护与外观入口。内置注入文字也是可编辑的原生条目；默认条目删除后不自动补回，停用后不注入、不从旧资产回退。动态事实仅在已启用条目的显式模板位置展开，技能、团队、工具只展示标题摘要与结构入口，正文按需。工具注册、真实 schema 和权限仍由执行端管理，记忆文字不改变授权。

## 3. 工作区 / 文件

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 切换工作区 | `feature/workspace/presentation/component/WorkspacePicker.kt:70`（顶栏图标）、`:128`（`selectWorkspace`） | 无 | 未找到（config 无“当前工作区”字段，`AIEditorApp.kt:409-434` 全部注册项里没有） | **缺口**：会话绑死自己的 `projectRoot`（`feature/agent/domain/tool/file/FileTools.kt:98` 注释明示“本会话的文件操作只落在自己的工作区”） |
| 新建工作区 | `WorkspacePicker.kt:179` | 无 | 未找到 | **缺口** |
| 重命名工作区 | `WorkspacePicker.kt:186` | 无 | 未找到 | **缺口** |
| 删除工作区 | `WorkspacePicker.kt:184` | 无 | 未找到 | **缺口** |
| 添加外部目录作为工作区 | `WorkspacePicker.kt:151`（系统目录选择器） | 无 | 未找到 | **缺口**，且需系统 SAF 选择器，agent 原理上办不到，只能由用户点 |
| 新建文件 / 文件夹 | `feature/agent/presentation/component/ChatDrawer.kt:1004`、`:1009` → `FileBrowseController.kt:294`、`:307` | 有 | `writeFile`（`AgentModule.kt:453`）、`Bash`（`:456`） | 无；`writeFile` 的 `overwrite=false` 对应“新建”语义 |
| 重命名文件 | `ChatDrawer.kt:1014`、`:1050` → `FileBrowseController.kt:320` → `MainActivity.kt:807-811` | 部分 | 无专用 rename；只能 `Bash` `mv`（`ExecuteCommandTool`） | 无原子重命名工具，文件名校验/冲突处理要自己写命令 |
| 删除文件 | `ChatDrawer.kt:1018`、`:1075` → `FileBrowseController.kt:332`；沙盒浏览器 `feature/sandbox/FileBrowserScreen.kt:363` | 部分 | 无专用 delete；只能 `Bash` `rm` | 同上；且 `rm` 走 shell，绕开了 UI 的删除确认 |
| 复制 / 剪切 / 粘贴 | `ChatDrawer.kt:1024`、`:1028`、`:1035`（粘贴覆盖 `:404`）→ `FileBrowseController.kt:345`、`:350`、`:365` | 部分 | 无专用工具；`Bash` `cp`/`mv` | 同上 |
| 打开 / 预览文件 | `ChatDrawer.kt:938`（`onOpenFile`）→ `MainActivity.kt:795`；沙盒 `FileBrowserScreen.kt:195` → `FilePreviewScreen.kt:99` | 有 | `readFile`（`AgentModule.kt:449`）、`viewImage`（`:452`） | 无；图片走 `viewImage`，文本走 `readFile` |

## 4. 模型与供应商配置

账号供应商：设置中新增 Trae 国内版、WorkBuddy 国内版、小浣熊登录、动态模型／倍率／当前积分查询与本机退出登录。对应 `config providers.<id>.account_service`；账号令牌不接通用审计字段，避免明文进入配置历史；交互式网页登录只由用户完成，AI 不代填密码或获取验证码。账号凭据独立加密保存，不进入普通供应商备份，跨设备恢复需重新登录。端到端账号可用性以实际授权和上游返回为准，夹具测试不等于真实账号验收。

会话级双模型规划：用户在会话页选择规划、执行模型并开关；Agent 通过 `dual_model.sessions.<sessionId>.enabled / planning / execution` 配置同一份会话数据。模型字段使用 `providerId:model`，只按第一个冒号拆分。PLAN 与执行阶段按当前模式路由，不改变计划批准与工具权限；不继承到新会话或子代理。界面在运行中不可修改配置，配置通道更新在下一轮生效，本轮配置冻结。

个人中心页面及设置路由已移除；聊天身份存储与 `ConfigBuiltins.kt` 的身份配置字段保留，不清除用户已有昵称和头像。

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 新增供应商 | `component/SettingsScreen.kt:745-752`（顶栏 +） | 有 | `config` `action=add path=providers`（`domain/tool/config/ConfigTool.kt:349`；集合 `feature/settings/data/ConfigProviderFields.kt:86`、`:446`） | 无；api_key 类字段是 SENSITIVE，需弹确认 |
| 编辑供应商（baseUrl/key/多 key/模型/面板脚本/代理/自定义头/缓存开关） | `component/ProvidersAndLogSection.kt:113` → `ProviderEditorScreen.kt:169`（`:398` 保存） | 有 | `config` `action=set path=providers.<id>.<字段>`（字段表 `ConfigProviderFields.kt:85-100` 描述行） | 无 |
| 删除供应商 | `ProvidersAndLogSection.kt:114` | 有 | `config` `action=remove path=providers id=<id>`（`ConfigProviderFields.kt:470`） | 无 |
| 选当前会话的模型 | `component/ChatModelSheet.kt:56`（`ModelIconButton`）、`:89`（`onSelect`）→ `ChatInputBar.kt:584` → `AIChatPanel.kt:1505` | 无 | 未找到（`sessions` 只读，`SessionTool.kt:31`；config 无会话级模型字段） | **缺口**：只能改全局默认/角色模型，不能改当前会话 |
| 设全局角色模型（默认/标题/压缩/视觉/生图） | `component/DefaultModelsSection.kt:93`、挂载 `SettingsScreen.kt:1024` | 有 | `config` `model.roles.default` / `.default_effort` / `.title` / `.compaction` / `.vision` / `.image_gen`（`feature/settings/data/ConfigModelRoleFields.kt:41`、`:51`、`:82`、`:92`、`:102`、`:112`） | 无 |
| 改采样温度 | 未找到（用户界面也没有） | 无 | 未找到 | 双方都没有：温度由固定表决定（`feature/agent/domain/provider/AIProvider.kt:251` `fixedTemperature`），不是可配置项 |
| 改思考强度（reasoning effort） | `ChatModelSheet.kt:107`（`ReasoningEffortSelector`）→ `AIChatPanel.kt:1511` | 无 | 未找到（无 `reasoning_effort` 配置字段） | **缺口**：会话级设置，agent 无法改 |
| 测试模型/供应商连通性 | `component/ProviderModelComponents.kt:323`（`onClick = onTest`）、`:330`；`DefaultModelsSection.kt:841` | 无 | 未找到专用工具；只能 `Bash` `curl` 或 `webfetch` 自己发请求 | **缺口**：App 内有现成的测试链路（`feature/settings/data/remote/ModelApiService.kt:290`），但没暴露成工具 |

## 5. 技能

技能详情页支持为同一技能选择多个成员，成员编辑页仅展示绑定结果。界面通过 `AgentDefinitionRepository.setSkillBinding` 修改原作用域的成员定义；agent 可用文件工具编辑该成员定义中的 `skills` 列表，无新增专用工具。绑定成员默认折叠；专属技能只向绑定角色提供名称与简介。技能关键词公共模式与成员绑定互斥，编辑页和详情弹窗均支持逐行添加、移除独立关键词输入框；config 的 skills 集合继续读写 keyword_enabled 和 keywords 数组。

新建与编辑技能可直接切换关键词公共模式、填写关键词，仓库保存采用本次表单设置；开启但无有效关键词时拒绝保存。已有成员绑定时拒绝直接切换，不隐式解绑；详情页保留明确确认解绑的入口。普通模式与关键词模式都不自动注入正文，正文通过 `loadSkill` 按需读取，未命中关键词的技能不进入本轮可用清单。

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 从本机导入/安装技能 | `SettingsScreen.kt:830-838`（`showSkillAddSheet`），导入解析在 `feature/agent/domain/skill/SkillImport.kt` | 部分 | 无安装工具；按 `feature/agent/data/ConfigSkillFields.kt:22-24` 的注释，“新建/删除技能该走文件工具”——用 `writeFile` 在 `~/.aharou/skills/<名>/SKILL.md` 建目录（全局技能根见 `SkillRepository.kt:14`） | 没有“解析 zip/md 并校验 frontmatter”的能力，agent 只能手写文件，格式错了不会报 |
| 从技能市场搜索/安装 | `SettingsScreen.kt:1121`、`:1139`（`installFromMarket`） | 无 | 未找到（只能 `webfetch` 市场页面） | **缺口** |
| 启用/停用 | `component/SkillDetailSection.kt:70`、`:72` → `SettingsScreen.kt:1148` | 有 | `config` `action=set path=skill.skills.<名>.disabled`（`ConfigSkillFields.kt:54`） | 写入只改全局禁用名单，项目级名单不动（`ConfigSkillFields.kt:66`） |
| 读取技能正文 | `SkillDetailSection.kt:39`、`:121`（详情页正文） | 有 | `loadSkill`（`AgentModule.kt:461`；`domain/tool/skill/LoadSkillTool.kt:30`） | 无 |
| 编辑技能正文 | `SettingsScreen.kt:845`（编辑入口 → `SkillEditor`） | 有 | `writeFile` / `editFile`（`AgentModule.kt:453`、`:454`） | 无专用技能编辑器，但文件工具等价 |
| 删除技能 | `component/SkillsSection.kt:158`（批量删除确认） | 部分 | 无专用工具；`Bash` `rm -r` | 无目录合法性校验，容易误删（`SkillRepository.kt:247` 有“仅当目录含 SKILL.md 才删”的保护，agent 侧没有） |
| 导出技能 | `SkillDetailSection.kt:121`（`onExport`） | 无 | 未找到 | **缺口** |

## 6. MCP

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 添加 server（stdio / http） | `SettingsScreen.kt:767-775`（顶栏 + → `McpDialogRequest`） | 有 | `manageMcp` `action=add_stdio` / `add_http`（`AgentModule.kt:465`；`domain/tool/mcp/ManageMcpTool.kt:54`）；也可 `config` `action=add path=mcp.servers`（`feature/agent/data/ConfigMcpFields.kt:57`、`:202`） | 新配置下次会话生效（`ManageMcpTool.kt:117` 自述） |
| 移除 server | `component/McpSettingsSection.kt:246` | 有 | `manageMcp` `action=remove`、`config` `remove mcp.servers`（`ConfigMcpFields.kt:254`） | 无 |
| 列出 server | `McpSettingsSection.kt:245`（列表行） | 有 | `manageMcp` `action=list`、`config get mcp.servers` | 无 |
| 编辑 server 参数 | `McpSettingsSection.kt:245`（`onEdit`） | 有 | `config` `action=set path=mcp.servers.<id>.<段>`（`ConfigMcpFields.kt:67-98`） | 无 |
| 启用/停用 server | `SettingsScreen.kt:1077`（`setMcpServerEnabled`） | 有 | `config` `mcp.servers.<id>.enabled`（`ConfigMcpFields.kt:174`） | 无 |
| 重连接 / 刷新连接 | `SettingsScreen.kt:755`（`viewModel.reloadMcp()`） | 无 | 未找到（`config set enabled` 是否触发重连未核实） | **缺口**：连不上时只能改配置或等下轮会话，没有“立刻重连”的动作 |

## 7. 规则文件

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 编辑文件式全局规则 | 不提供旧页面；新版规则类条目在记忆库编辑，不自动改写文件式规则 | 有 | `config` `rules.global`（`feature/agent/data/ConfigRuleFields.kt:33`，写回生效那份 AGENTS.md/CLAUDE.md）；或用 `writeFile` 直写 `~/.aharou/AGENTS.md` | 无 |
| 编辑文件式项目规则 | 不提供旧页面；项目记忆在记忆库管理 | 有 | `config` `rules.project`（`ConfigRuleFields.kt:47`）+ `writeFile` 写 `<工作区>/AGENTS.md` | 无工作区时写入会报错（`ConfigRuleFields.kt:53`） |
| 新建 / 编辑 `rules.d` 片段 | 不提供独立旧页面 | 部分 | 无专用工具；`config rules.fragments` 是只读清单（`ConfigRuleFields.kt:57`），写入靠 `writeFile` 到 `~/.aharou/rules.d/` 或 `<工作区>/rules.d/` | 片段命名规则（`RuleFileRepository.kt:122` 正则）要自己遵守，写错不报 |
| 删除规则片段 / 清空主规则 | 不提供独立旧页面；不删除用户既存文件 | 部分 | 主规则清空可用 `config set rules.global ""`；片段删除只能 `Bash rm` | 无专用删除动作 |
| 读取文件式生效规则正文 | 不提供独立旧页面；记忆库预览仅展示新版条目 | 有 | `loadRule`（`AgentModule.kt:462`；`domain/tool/rule/LoadRuleTool.kt:29`） | 无 |

## 8. Peer collaboration removed

Peer pairing, remote-device services, settings and configuration endpoints have been removed. Existing saved user data is retained. Ordinary SSH, MCP clients and memory synchronization remain available.

## 9. Messaging bots removed

WeChat, Feishu, QQ and Telegram bot settings, services, startup restoration, configuration fields and the bot file-sending tool have been removed. Existing conversations, files and saved configuration data are retained. Agent communication, MCP and SSH are unchanged.

## 10. 会话

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 创建空项目 | 项目页填写名称和项目存在的意义，保存空项目 | 无 | 无项目创建工具 | 用户主导，不调模型、不把意义当需求，不自动生成草稿或启动执行 |
| 创建命名空需求分支并讨论 | 项目更多菜单／现有任务或分支下「添加需求分支」，选中后复用现有输入栏 | 讨论可用项目工具；创建容器由界面发起 | `project_tasks` `action=plan/outline/supplement`，scope 从真实调用会话及仓库关联派生 | 分支会话按项目与工作区隔离；plan 生成任务的父级固定为当前分支，不能通过传参越权；普通问答不 plan，容器不执行、不计数、不验收 |
| 删除项目 | 长按项目行 → 删除菜单 → 原确认框（`component/ProjectListScreen.kt`） | 无 | 无项目删除工具 | 不常显删除按钮；继续遵守可删除条件 |
| 在执行中的任务下分出研究分支或追加子任务 | 执行成员通过项目工具派生可执行任务，不同于用户创建的需求分支容器 | 有（仅项目成员） | `project_tasks` `action=branch/add/outline`（outline 只读）（`feature/agent/domain/tool/session/ProjectTaskTool.kt`，注册 `di/AgentModule.kt`） | 只能写当前运行任务及其子孙，每项目成员新增上限 20、深 4 层；不能建项目、改别的任务或长按补充 |
| 切换会话 | `MainActivity.kt:838`（`selectSession`） | 无 | `sessions` 只有 list/search/read | **缺口** |
| 重命名会话 | `ChatDrawer.kt:436`、`:508` → `MainActivity.kt:840` | 无 | 未找到 | **缺口** |
| 删除会话 | `ChatDrawer.kt:450`、`:471` → `MainActivity.kt:839` | 无 | 未找到 | **缺口** |
| 置顶/取消置顶 | `ChatDrawer.kt:1391` → `MainActivity.kt:841` | 无 | 未找到 | **缺口** |
| 导出会话 | `MainActivity.kt:842-845`（`sessionExportLauncher`） | 无 | 未找到 | **缺口** |
| 归档会话 | 未找到（`ChatSessionEntity.kt:19-52` 无归档字段，只有 `isPinned`/`ghostMode`） | 无 | 未找到 | 双方都没有：这条能力不存在 |
| 浏览/搜索其它会话内容 | `ChatDrawer.kt:337`、`:556`（列表）、搜索命中 `:348` | 有（普通会话范围） | `sessions` `action=list/search/read/tool_calls/context`（`domain/tool/session/SessionTool.kt`） | 严格只读且限当前工作区；主代理与成员会话隔离，不能经此入口获取内部成员名单、消息、工具调用或注入上下文 |

## 11. 备份

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 导出备份到本地文件 | `feature/backup/presentation/BackupSection.kt:357`（`exportLauncher`）、`BackupViewModel.kt:133` | 无 | 未找到 | **缺口**：导出要系统文件选择器 + 加密口令，agent 无法代做 |
| 从备份导入/恢复 | `BackupSection.kt:380`（`viewModel.import`）、`BackupViewModel.kt:149` | 无 | 未找到 | **缺口** |
| 改导出范围（8 个开关） | `BackupSection.kt:135-195` | 有 | `config` `backup.providers` / `.remote_connections` / `.chat_history` / `.mcp_servers` / `.permission_rules` / `.app_settings` / `.workspace_files` / `.memory_and_soul`（`feature/settings/data/ConfigBackupFields.kt:54-71`） | 无 |
| 网络备份目标增删改/测试连通 | `BackupSection.kt:336`（上传入口）、`BackupViewModel.kt:345`、`:355`、`:359` | 无 | 未找到 | **缺口** |
| 上传到网络目标 / 从网络恢复 | `BackupSection.kt:336`、`:338`、`BackupViewModel.kt:374`、`:446` | 无 | 未找到 | **缺口** |

## 12. 浏览器

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 新建标签页 | `feature/browser/presentation/BrowserSheet.kt:185`（`newTabFromUI`） | 有 | `browser` `action=new_tab`（`AgentModule.kt:474`；`feature/browser/BrowserTool.kt:34-40`） | 无；上限 3 个标签 |
| 切换 / 关闭标签页 | `BrowserSheet.kt:211`（`selectTab`）、`:212`（`closeTabFromUI`） | 有 | `browser` `list_tabs` / `close_tab` | 无 |
| 改 UA 档位 / 自定义 UA | `presentation/BrowserSettingsSheet.kt:183`、`:215` | 有 | `browser` `action=set_user_agent`；`config` `browser.user_agent_profile`（`feature/browser/data/ConfigBrowserFields.kt:34`）、`browser.custom_user_agent`（`:58`） | 无 |
| 改视口（宽/高或回默认） | `BrowserSettingsSheet.kt:229-280` | 有 | `browser` `action=set_viewport`；`config` `browser.viewport_width` / `.viewport_height`（`ConfigBrowserFields.kt:74`、`:91`） | 无 |
| 改标签空闲回收时长 | `BrowserSettingsSheet.kt:323` | 有 | `config` `browser.idle_timeout_minutes`（`ConfigBrowserFields.kt:108`） | 无 |
| 页内开发者工具开关 | `presentation/BrowserMenuSheet.kt:58-67`（Eruda） | 有 | `config` `browser.devtools_enabled`（`ConfigBrowserFields.kt:134`） | 无 |

## 13. 缺口汇总

以下为其他功能的既有缺口清单；记忆页能力以第 2 节为准。页面移除后不沿用历史能力总数，其他功能未在本轮重新统计。

### A. 其他功能的工具缺口

1. 手动跑一次自动化任务 —— 双方都无（列在此处便于对照）
5. 工作区：切换（`WorkspacePicker.kt:128`）
6. 工作区：新建（`WorkspacePicker.kt:179`）
7. 工作区：重命名（`WorkspacePicker.kt:186`）
8. 工作区：删除（`WorkspacePicker.kt:184`）
9. 工作区：添加外部目录（`WorkspacePicker.kt:151`）
10. 模型：选当前会话的模型（`ChatModelSheet.kt:89`）
11. 模型：改思考强度（`ChatModelSheet.kt:107`）
12. 供应商：测试连通性（`ProviderModelComponents.kt:323`）
13. 技能：从市场搜索/安装（`SettingsScreen.kt:1121`）
14. 技能：导出（`SkillDetailSection.kt:121`）
15. MCP：重连接/刷新（`SettingsScreen.kt:755`）
18. 项目：创建及批量草稿（项目首页）
19. 会话：切换（`MainActivity.kt:838`）
20. 会话：重命名（`ChatDrawer.kt:508`）
21. 会话：删除（`ChatDrawer.kt:471`）
22. 会话：置顶（`ChatDrawer.kt:1391`）
23. 会话：导出（`MainActivity.kt:842`）
24. 备份：导出（`BackupSection.kt:357`）
25. 备份：导入/恢复（`BackupSection.kt:380`）
26. 备份：网络目标增删改/测试（`BackupViewModel.kt:345`、`:359`）
27. 备份：上传/从网络恢复（`BackupViewModel.kt:374`、`:446`）

（第 1 条属于“双方都没有”，不计入 26；26 条为第 2–27 条。）

按域看，缺口最集中的是**会话（6 条）**、**工作区（5 条）**、**备份（4 条）**：这三块都是“App 自身状态管理”，工具范围里只有文件、配置、只读会话浏览三类接口，没有“改 App 内数据”的写通道。

### B. 只能靠 `Bash` / 文件工具临时替代（8 条）

文件重命名、文件删除、复制/剪切/粘贴、`rules.d` 片段新建与编辑、规则片段/主规则删除、技能导入安装、技能删除、（补）文件新建虽已用 `writeFile` 覆盖，但“文件夹新建”同样只有 `Bash`（`writeFile` 不建目录）。

共同问题：这些动作**没有专用工具，所以不在工具名层面可发现**——agent 得先知道“可以用 shell 凑”，才做得出来；也没有 UI 那层的合法性校验（片段命名、技能目录必须含 SKILL.md）。

### C. 反向缺口：agent 有 `config` 字段，用户却没有界面入口（0 条）


### D. 双方都没有（3 条）

采样温度调节、会话归档。自动化任务「手动跑一次」已明确不做（2026-11-03 主人定，调试用单次任务凑合）。写在这里是为了说明：它们不是 agent 工具缺口，不要照着补工具。

## 14. 方法说明

- **“有工具”的判据**：`di/AgentModule.kt:453-489` 里出现注册名，且工具实现里确实有对应动作（逐个读了 `MemoryTool.kt`、`ConfigTool.kt`、`SessionTool.kt`、`ManageMcpTool.kt`、`BrowserTool.kt`、`LoadSkillTool.kt`、`LoadRuleTool.kt`、`PlanModeTool.kt` 的 `name`/`description`/`parameters`）。
- **`config` 通道算不算“有工具”**：算。它同样是 agent 可调用的工具，只是能力面由字段注册决定；因此“有 config 字段”一律记为“有”，并在差距说明里注明“无独立工具、要靠 `config list` 才能在索引里发现”。
- **界面入口的判据**：从 `component/SettingsScreen.kt:123-178`（`SettingsSection` 枚举）与 `:963-1331`（分区到组件的映射）找到分区文件，再在该文件里定位真正触发调用的 `onClick`/`viewModel.*` 行；不按文件名猜。
- **没读到就不写“有”**：所有“无 / 未找到”都做了全仓 `rg`（关键词含 `rename`、`revoke`、`setPort`、`runNow`、`temperature`、`archived`、`reasoning_effort`、`testConnection` 等），并检查了 `Config*Fields` 的注册清单（`AIEditorApp.kt:409-434`）。

不确定项（列出来，不猜）：

1. **MCP 重连接**：`viewModel.reloadMcp()` 是否等价于“改 `mcp.servers.<id>.enabled` 后自动重连”，未核实 `McpManager` 的监听链路；表里按“无工具”记。
2. **技能导入**：`SkillImport.kt` 的 zip/md 解析没有对应工具，但“用 `writeFile` 手写 SKILL.md 能否被正确扫描并出现在 `skill.skills` 集合里”未实测（只读到 `SkillRepository.kt:14` 的目录约定与 `ConfigSkillFields.kt:22-24` 的注释）。
3. **记忆 compact / distill**：确认没有工具与 config 字段，但后台 `MemoryMaintenanceWorker` 的触发条件未逐行读，因此“agent 完全无法触发”是按工具范围下的结论，不等于“这两件事永远不会发生”。
4. **会话导出**（`MainActivity.kt:842-845`）的具体产物格式未核实，只确认入口存在且无对应工具。
5. `docs/` 下此前没有本文件；本表未接入任何索引（`docs/FILE_INDEX.md` 未改，任务只允许写一份文档）。

## 脚本工具与片段注入

配置页现集中供应商、默认模型、插件、连接器和凭据；机器人通讯入口已移除。自动化直达与工具页复用同一任务页，MCP 批量管理、目录、导入导出与重连保留在工具页。连接器普通返回及日志、弹窗关闭均按实际入口来源返回，不固定跳工具页。

虚拟屏仅使用指定显示屏的唤醒接口；新版安卓优先使用 InputManagerGlobal，按文字定位后仍注入该屏的原生点击；明确执行失败和投递后超时不自动重放。截图附带原帧序号、帧龄和显示状态，不把缓存文件新建等同应用响应。导图手机首层按视口限制标题宽度，仍保留平移、缩放和大于等于 48dp 的最小触控行高。

配置 → 工具的脚本工具列表与 `config script_tools` 集合共用 `ScriptToolManager`，支持增改、启停和删除，启用的版本动态注册为工具；执行复用 Bash 真实命令权限与当前会话执行器。应用管理的行为、模式与成员注入文字统一在配置 → 记忆库编辑和停用，不再从旧提示词编辑器、旧规则页或角色文件正文直拼。

条目加载和受众通过原生库及 `context_library` 通道管理；旧 `prompt_fragments` 元数据不作为请求备用注入源。旧文件保留不等于恢复旧注入链。

## Historical branch integration: custom tools and session supervision

- 设置的工具入口保留 MCP 服务器与脚本工具，并增加自定义命令工具页签；参数表生成 schema，命令模板中的值单引号转义。命令仍经 ToolRegistry、ToolCallGuard 与运行期权限链执行。
- 自定义工具与覆盖项仍是文件资产：全局 `~/.aharou/tools/`、项目 `.aharou/tools/`；在每轮工具构建前刷新。应用启动通过 `ConfigAgentControlFields` 注册工具覆盖集合，`tool.overrides.<name>.schema_json` 读写使用 store 快照和 registry 原始 schema 校验；空字符串重置 schema 覆盖，保留注入策略。
- 内置与 MCP 编辑器区分原始 schema 与当前 schema，支持重置；只允许兼容的 `description`、`title`、`examples`、`$comment` 元数据覆盖。保存和运行都校验，原始结构变化后不兼容的覆盖回退为原始 schema；实际模型请求使用校验后的工具定义。参数名、类型、required、enum 等结构不能靠覆盖改写，要变更执行能力应自建工具。
- 每轮工具集先做成员允许清单，再做 every_round/keyword/off 注入过滤和描述、schema 说明覆盖；该过滤不是执行权限撤销，包装器必须保留流式接口及全部执行授权方法。
- `git_status`、`git_diff`、`git_log`、`find_files`、`rtk_command` 是可编辑的自定义工具模板：模板只在新建自定义工具表单展开选择，不常驻工具列表；用户选中后只填入新建表单，保存才注册。默认带有效关键词并使用关键词模式，不默认全注入或安装依赖；RTK 缺失保留原生命令错误，不静默替换执行。
- 智能托管的设置和状态按会话隔离；界面设置与 `config get/set steward.sessions.<id>.enabled|max_loops|unlimited` 接入同一控制器。全局旧托管路径由显式会话路径取代，避免修改其他会话。

## 角色卡与升级部署

团队中的成员和管理员是对角色卡的引用，团队详情的名称可打开相应角色卡并返回原入口；独立创建入口统一称「创建角色卡」，不重命名稳定的 frontmatter name 或工具参数。内置角色/团队升级按历史内容 SHA-256 识别未编辑的预设，用户修改保留；退役内置原文移至 builtin-archive，禁用状态不重置。源码、APK 内容、安装哈希与实际加载分别验收，不能用文件数量代替运行证据。

## 团队运行通信与指令边界

团队编辑页每阶段提供「协作对齐」可视化编辑：勾选来源与目标成员、填对齐内容，等价在任务上写 `interfaces` 边；详情页同步展示「协作拓扑」。边只是注入双方成员 prompt 的软约束，不改变调度顺序，也不要求 agent 有对应工具——与字段文档 `stages[].tasks[].interfaces` 口径一致。

团队编辑器提供 `same_stage`、`parent_only`、`run` 三种通信策略，新建默认 `same_stage`，旧文件缺字段按 `run` 兼容。每成员每次运行消息额度默认 8，范围 1..100，重复消息拒绝；目标范围、预算与重复检查由执行层实施，不依靠模型自觉。主会话负责转达与协调，成员不能用消息扩大自己的任务或写路径授权。

阶段指引单独作为指令注入，上游结果以交付证据传入。Agent 侧团队文件配置与界面共用预设定义；`team.stages.<id>` 暴露 `communication`、`message_limit` 和 `communication_guide`，id 由作用域、团队名称的 UTF-8 十六进制和阶段序号组成，避免合法团队名称中的点干扰配置路径解析。修改只影响下次运行，不扩大原任务写权限。注册与静态调用已接入，编译、端到端交互和实际配置加载仍须单独验证，本文不构成测试通过证据。

## 本地知识库补充

用户可在知识库页创建本地库并追加 Markdown，保存后立即进入现有检索范围。Agent 对应 `config` 的 `knowledge.local`（创建/移除）和 `knowledge.documents`（追加），检索仍用 `knowledge_search`。移除会删除本地文档，必须遵循删除授权。

规则、记忆、提示词页面已补职责说明；提示词编辑页明确多条件 OR，不改变既有配置。

## 设置重组与工作区模型顺序

- 设置首页平铺供应商、默认模型、插件与连接器等入口；配置页保持独立。
- 自动化任务与工具授权合入工具页，原配置通道继续生效。
- 工作区模型顺序：聊天输入栏管理；`config` 的 `model.workspace_chain` 读取或修改当前工作区候选，每行 `providerId|model`。
- 内置技能支持列表左滑删除；删除记录在 `skills.deleted-builtins.json`，同名用户覆盖不受影响。
- 删除帮助关于及更新检查链路、连接挂载同步与 FTP 服务及其配置注册；保留既有 SSH 工作区和历史连接数据所需的共用类型。
- 宿主命令只使用 Shizuku，不再探测或调用 `su`。

### 固定中文与澄蓝

应用不再提供语言、皮肤、自定义配色或动态取色选择，config 同步移除 `app.language`、`appearance.theme_preset`、`appearance.dynamic_color` 与 `appearance.skins`；旧备份不能恢复这些能力。保留 `appearance.theme_mode` 的自动/深/浅模式及独立终端设置。

## Project tasks and team routes

| Capability | User interface | Agent channel |
| --- | --- | --- |
| Project creation, batch requirement drafts and parent tree | Project list and independent project tree/map routes | Creation and edits remain user-led; no task mutation tool is claimed |
| Multiple execution sessions, explicit start, retained historical conversations | Independent task detail route | Existing session `read` accesses original messages on demand |
| Compact task index and selected briefs | Task detail | `sessions(action="index"/"brief", task_ids=[...], since_version=..., offset=...)`, paged with `nextOffset`; no implicit message reads or model summaries |
| Current-task context | Linked execution conversation | Budget-limited project title/requirements and task context matched by workspace and session id |
| Unique team administrator and concrete stage-member routes | Team editor route map/form | Team configuration channel persists routes; `task(action="team_run")` uses routes when configured |

Legacy teams without routes run serial stages, explicitly not graph dispatch. Legacy no/multiple-admin teams remain on disk but require correction. A reviewer verdict and bounded rework are execution gates; completion alone is not approval. UI task editing is intentionally not claimed as agent-editable. No new behavior toggles, database migration or dependencies are introduced.

Routed members may emit `TEAM_HANDOFF: {"next":[{"stageId":"...","taskId":"..."}],"hold":false,"reason":"analysis"}`. Selection is restricted to saved outgoing forward routes. `hold:true` requires a reason and an empty next list. Missing markers mean backward-compatible static all-outgoing dispatch, never natural-language inference. Review verdicts are still mandatory. A successful selected branch does not approve or execute unselected branches.

Second-pass corrections: long-session creation routes to project creation; explicit turn outcomes distinguish stopped from succeeded; application-wide normalized-workspace root turns serialize (including historical root sessions), while child teams do not reacquire the root mutex. Every configured review gate is required: empty selection cannot approve it; results include unselected nodes and pending/passed gates. Department/query tasks survive editor metadata changes. Task dependencies are validated and execution waits for current accepted prerequisites. Project tree branches expand/collapse; the bounded map draws parent/dependency edges without fixed-node pagination. Full task field retrieval uses `sessions(action="field", task_id="...", field="constraints", offset=0, limit=1000, task_version=...)`; arrays are reversible JSON strings, version changes reject stale reads. Cold interrupted runs preserve sessions and record interruption; optional task context failure does not disable legacy message history, and ghost sessions skip automatic task injection.

## 项目入口与执行记录

项目列表支持删除项目：先按项目所属工作区停止并等待流程、单任务和本页执行清理，再原子删除项目与任务记录；保留工作目录、真实会话和其他项目，拒绝迟到任务写回重建项目。规划中明确拒绝删除；写入失败不移除界面项目。此操作由用户界面发起，没有新增 Agent 删除工具。

底栏为「项目 / 会话 / 配置 / 设置」，默认进入项目。创建只输入项目名称和项目存在的意义，保存为空项目，不调用模型、不生成任务草稿、不启动执行。进入后可直接在主会话提问，普通问答不修改需求；更多菜单及现有任务／分支下提供「添加需求分支」，先命名并保存空容器，再选中它在既有底部输入栏讨论和提交需求，由 AI 在该分支下生成若干执行任务。空标题不能提交，创建空分支不调用规划器；模型不可用会明确显示规则拆分，规则路径同样固定父级，总述和共同规范仅作为各任务背景，不另列任务。容器不计执行任务数、不显示负责人或执行状态、不执行或验收；孩子正常遍历与执行。不新增节点内独立聊天输入框，也不转换或删除旧普通任务及其历史结果。

项目详情用紧凑操作行集中开始／停止、进度、视图和更多，不再重复显示大返回／项目名栏；视图菜单切换任务树／思维导图并按需查看图例；图内搜索默认收起，关闭清空查询，缩放改为图内小图标工具组，点击热区仍为48dp。树图显示同一组任务；层级树可展开收起，导图按父子分支向右布局并支持拖动、双指缩放及放大/缩小/复位，跨任务依赖保留虚线。点击任务在树图节点内只读展开成员执行内容、工具输出、状态、负责人及前置关系，节点内部不另建输入框；项目外层保留原聊天完整输入栏，默认显示半透明胶囊，展开可上传图片／文件、拍照、修改模型与四种模式，并排队、插话或停止。隐藏输入法、拖动树图时收起，草稿附件仍由原会话状态保存。顶栏仍可打开终端、Git、浏览器、本地和共享文件。项目外层统一开始/停止，实际派发成员子执行并按依赖流转；成员交付进入待验收，不自动视作用户已验收。新增与补充由当前主会话或选定需求分支会话中的 AI 调用项目工具，作用域从真实调用会话派生而非任意参数；真实需求保存后，非 PLAN 模式通过页面外共享执行器异步接续，PLAN 模式仅保存，按工作区与项目隔离运行及停止；进度询问只读，不重复修改需求。离开页面或重建界面不主动取消流程，显式停止清除待续请求；应用进程终止仍按中断恢复处理。点击项目根节点可查看绑定根会话的历史消息与实时输出，查看成员记录或主会话历史不会改变当前主会话／需求分支的输入目标，无关记录更新时间也不应抢走选择。主会话控制清除分支选择后切回有效项目根会话；分支讨论展示真实会话消息，仍复用既有输入栏。更多菜单及节点入口创建空需求分支，不再直接按表单描述规划任务；普通执行任务保留成果验收及原证据／版本校验，容器不显示验收入口，关闭阅读器不停止流程。

已绑定项目的会话页使用同一工作树／思维导图消息体，未绑定项目的旧会话保留聊天记录，不强制创建项目，也不提供普通聊天新建按钮。项目任务执行记录在节点内只读查看；未归属旧任务仍保留详情入口，旧独立会话不删除。旧无项目归属的任务保留在项目首页的「未归属任务」，不会自动变成项目。

## 离线安全审计与逆向能力

| 能力 | 用户入口 | agent 对应能力与限制 |
| --- | --- | --- |
| 授权本地样本的离线审计 | 技能列表中的 `security-audit` | 加载说明；仓库 checkout 内原创 Python CLI 可运行 inventory/source/tools，脚本不保证自动导出到容器 |
| ELF/PE/DEX 与 ZIP/APK 候选清单 | 明确提供文件与授权目录 | SHA-256、头字段/中央目录统计；不解压、不校验 APK 签名、不运行样本 |
| 源码与隐私/权限复核 | 明确提供单个 UTF-8 源文件 | 十类规则给行号及人工线索，不回显原文/值；零命中不代表安全 |
| 逆向工具发现 | 按技能说明检查本地环境 | 仅 PATH 存在性，不安装或执行 readelf/jadx/apktool/rizin 等工具；不改变已有授权策略 |

术语按原始意图去重，不隐瞒凭据提取、绕过控制等真实请求。网络探测、暴力破解、持久化攻击不属于此工具包。来源适配按输入记录 DSH MIT/宿主依赖、VCP CC BY-NC-SA 不复制到 GPLv3、Windows skill 平台不适用且 license 未知不复制；具体上游版本未独立核验。实现无新增依赖、无第三方代码复制。

## 角色管理与提示词编辑入口

- 底栏配置提供独立「角色管理」入口，复用已有角色卡读写、编辑删除和团队引用，不新增存储契约；团队中的角色入口打开同一列表。
- 提示词注入关键词逐项添加、修改和移除；关闭关键词或成员条件只隐藏详情，保存仍保留既有值。Agent 继续使用既有提示词配置读写能力。
- 底栏会话、项目、配置、设置根页不再重复标题与返回箭头，子页面导航和原有操作保留。
- 项目任务树、导图及任务详情在当前节点下创建命名空需求分支，父级标识经既有 ViewModel 持久化；选中分支后再通过现有输入栏提交需求，规划与规则降级均固定该父级。普通执行任务的权限、依赖校验和验收不变；需求容器不能被执行或验收。导图支持适配全图及继续缩小。

## 离线翻译与自动化入口

- 用户在配置首页切换离线自动翻译、查看模型状态并在 Wi-Fi 下重试下载；agent 可读写 `translation.enabled`，设置 `translation.download_model=true` 发起同一模型准备流程。
- 翻译只影响展示与只读预览，不回写消息、提示词、规则、记忆、知识文档或工具执行字段；技能市场复用同一端侧服务，不再通过聊天模型生成翻译 JSON。
- 自动化任务仅从配置的工具页面进入，保留既有调度器、数据库和 `scheduledTasks` 配置集合，不再维护第二个页面路由。

## 本地容器基线

默认内置 Ubuntu 24.04 LTS ARM64，用户与 agent 共用同一本地执行环境。仅接受原生 ARM64 rootfs，不再安装或启动 Alpine、x86/AMD64、32 位 ARM，不使用跨指令集转译；远程 SSH 不受此本地架构限制。旧内置迁移先验证 Ubuntu 并保留 /root 与 /home，成功后才移除旧内置系统；失败保留旧数据且不回退 Alpine。工作区、全局配置与共享目录不参与清理。此处不代表所有工具或真机构建已完成兼容性验收。

### Restored project and tool entry points

The unified tool browser and six-action creation sheet reuse existing execution and permission paths. Project deletion removes project/task metadata only, uses the project workspace, and rejects active execution; it never deletes workspace files or conversation entities. Compact project controls are newly integrated and require device verification.

本轮入口整合：项目页统一会话/项目创建，模型列表统一工作区模型顺序，思考强度保留；阶段模型及其配置通道已退役。记忆库的角色注入/主代理注入、摘要与正文仍通过原生 context_library 配置与 memory 工具读写。Beta 工作流已移除，发布仅使用 CI 与正式 Release。
