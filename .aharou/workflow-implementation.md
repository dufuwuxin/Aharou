# 工作流设置消费者实现

## 结论

在独立工作树 `/root/shared/aharou-workflow-settings`、分支 `feat/major-workflow-settings`、起点 `4e42df83` 补齐团队设置内的多团队工作流消费者。UI → TeamWorkflowSettingsViewModel → 既有 TeamWorkflowRepository；没有新增引擎、存储、Gradle 依赖或旧手工节点菜单。

实现已落盘；静态门禁通过，不等于编译或运行验收。当前存在一处表外旧测试构造调用待授权修正（见风险）。未在主代理工作树写文件。

## 证据

在本独立树实际运行：

- `PYTHONPATH=/root/workspace python3 -m codekit syntax app/src/main/java/com/aharou/feature/settings/presentation/TeamWorkflowSettingsViewModel.kt app/src/main/java/com/aharou/feature/settings/presentation/component/TeamWorkflowsSection.kt app/src/main/java/com/aharou/feature/settings/presentation/component/SubAgentTeamsSection.kt app/src/main/java/com/aharou/feature/agent/data/ConfigTeamControlFields.kt app/src/test/java/com/aharou/feature/settings/presentation/TeamWorkflowSettingsViewModelTest.kt` → 检查 5 个文件，0 个有问题；Kotlin 后端 `lexer-only`。
- `python3 scripts/check_architecture.py` → `[OK] 架构门禁通过：无新增禁用组件、文件行数未越基线、单中文资源与引用完整`。
- `git diff --check` → 无输出，成功。
- `python3 scripts/check_task_list.py` → `[OK] 没有任务清单文件（.aharou/task-list.md），视为无待办`。
- 修改 ConfigTeamControlFields 构造器前执行 `PYTHONPATH=/root/workspace python3 -m codekit callers ConfigTeamControlFields .`：发现唯一原显式调用 `TeamControlIntegrationTest.kt:82`。

新测试类写有 9 个测试（尚未执行）：真实仓库创建、原作用域编辑重命名与链序、非法链/重名/空引用/不存在团队、关闭草稿不写盘、删除失败保留确认及重试、IO 异常/取消不假成功、配置 add/字段读写/remove 与只读作用域、配置失败传播、删除项目覆盖后恢复全局项。测试只使用临时文件提供器与假团队仓库，不执行脚本。

## 改动或建议

1. `app/src/main/java/com/aharou/feature/settings/presentation/component/SubAgentTeamsSection.kt:78`：原团队/创建成员/详情/编辑/删除入口保留；改为整个页面共享滚动并在团队列表后接 `TeamWorkflowsSection()`，避免工作流位于不可达区域。
2. `app/src/main/java/com/aharou/feature/settings/presentation/component/TeamWorkflowsSection.kt:44`：生命周期感知收集 VM 状态，列表按仓库返回显示来源及有序团队链；创建/编辑底栏沿用 AppTextField、SettingsGroup、AdaptiveModalBottomSheet、AppSwitch，支持层选择/上移下移/删除、lead、acceptsReject、maxRejectRounds、公约；保存失败不关表单，删除失败不撤列表。底栏 IME 避让；设置页不伪造运行按钮。
3. `app/src/main/java/com/aharou/feature/settings/presentation/TeamWorkflowSettingsViewModel.kt:40`：仓库读写放 IO，单次写入状态防重入，编辑原作用域锁定；持久化成功才关闭编辑器；删除失败保留确认；CancellationException 重抛；取消/异常不会写 Saved 状态；编辑内容保留在 VM。
4. `app/src/main/java/com/aharou/feature/agent/data/ConfigTeamControlFields.kt:30`：增加真实 TeamWorkflowRepository 注入并注册 `workflows` 集合。无既有 workflows 集合，未重复已有 `teams` / `team.stages`。作用域为只读，所有增删改走同一仓库；id 是 `scope:UTF8-hex-name`，不会因名称含点破坏解析；修改风险 SENSITIVE；改名不承诺旧 id 上的撤销。
5. `app/src/main/res/values/strings.xml:4023`：添加单中文工作流文案、错误枚举映射、范围归一化说明与删除项目覆盖后全局项重新显现说明。
6. `app/src/test/java/com/aharou/feature/settings/presentation/TeamWorkflowSettingsViewModelTest.kt:40`：增加 9 个临时文件/假仓库驱动的 JVM 测试。

契约影响：无存储模型、执行器或跨端协议变更；新增 config 消费入口（非另一套工作流定义）。字段映射如下：

| config/UI | 既有 TeamWorkflowForm / 持久化 |
| --- | --- |
| name（必填 String） | name；仓库校验合法名及同作用域重名 |
| description（String，默认空） | description |
| scope（add 必填 global/project；编辑只读） | AgentDefinitionScope → workflowsRoot(scope) |
| teams（有序数组，必填） | teams；team:String、acceptsReject:Boolean 默认 true、lead:String 默认空 |
| charter（String，默认空） | charter → Markdown 正文 |
| maxRejectRounds（config Int 1..9，默认2；UI 原始文本） | maxRejectRounds:String → 既有 normalizeRounds：非数字默认2、范围夹到1..9 |

集合 `workflows` 支持 add/remove；字段 `name`/`description`/`teams`/`charter`/`maxRejectRounds` 支持读写，`scope` 只读。旧文件仍由既有解析器读取；项目同名覆盖全局的语义未改。

## 未验证

遵照任务硬边界，没有运行 Gradle、没有安装依赖、没有联网、没有下派或 push。新增 Hilt ViewModel 的生成、Kotlin 类型检查、9 个 JVM 测试执行及真机交互尚未验证；不得用 lexer-only 成功代替测试通过。没有 memory search 工具，未进行该工具查询。

未写模板给的 `~/workspace/tmpwork/feature-delivery/s1-后端.md`：团队目标明确禁止主树写入，并明确授权本报告路径；以独立树报告为唯一产物。

## 风险

- `app/src/test/java/com/aharou/feature/agent/domain/tool/subagent/TeamControlIntegrationTest.kt:82` 仍是旧单参数构造；新的 ConfigTeamControlFields 需要第二个 TeamWorkflowRepository。此文件不在初始拆解表，已向主代理申请精确授权，未越界写入。集中编译前必须修正，否则旧测试源码不能编译。
- `rules.d/30-assets-sync.md` 要求同步 docs-site 与能力台账，但拆解表未授权其路径；已上报，不擅自新增文档。不是宣称这两项已经同步。
- 既有 TeamWorkflowRepository.scan 会将读取异常降级为空列表，既有 save 的重命名旧文件删除失败只记录警告；VM 不改变这些仓库语义，无法把被仓库吞掉的失败重新识别出来。隐藏的同名全局条目不出现在 listAll 中；同作用域冲突检测沿用仓库现状，未扩展模型。
- UI 使用既有组件布局，不另造视觉规范；真机需核实长团队链、软键盘、返回/滑动关闭与删除失败确认。

## 下一步

主代理集中执行代码审查及授权后的单次编译/定向测试，核验项目工作流入口的真实启动消费；处理已点明的旧测试调用点与文档边界。不自行启动更多任务。
