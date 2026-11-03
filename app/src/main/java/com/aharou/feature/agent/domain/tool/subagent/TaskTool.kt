package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.SubAgentClaimVerifier
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.subagent.SubAgentProfileMatcher
import com.aharou.feature.agent.domain.subagent.SubAgentResultInspector
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.subagent.WriteLease
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理工具 `task`：统一管理子代理的生命周期。
 *
 * 通过 `action` 参数区分操作类型：
 * - `create`（默认）：创建一个子代理会话并让 AI 替用户向其发消息，子代理自动开始回复。
 * - `send`：向指定子代理发送一条消息（可反复发送）。运行中的子代理会在下一批工具结果里搭车收到，
 *   已完成的子代理会被重新唤醒；消息按发送顺序送达。
 * - `read`：读取指定子代理的最后输出（最后一条助手回复）。
 * - `stop`：停止指定子代理的执行（取消其 AI 任务）。
 * - `del`：删除指定子代理会话及其全部消息。
 * - `list`：列出当前会话的全部子代理及其状态。
 * - `team`：列出同批队友（同一父会话下的其它子代理）：谁在跑、各自负责任务与角色。
 * - `tell`：向同批队友发一条消息（id + message），供分支上的子代理互相交流。
 *
 * 最多同时允许的数目由配置决定（默认 5 个，见 SubAgentSettings；create 时检查上限），嵌套派发同样计入该上限。
 * 子代理可再派子代理，但最多嵌套 2 层（主代理 → 子代理 → 孙代理，见 [AgentDefinition.MAX_SUBAGENT_DEPTH]），
 * 再深会被拒绝。
 *
 * `create` 可用 `agent` 参数指定自定义子代理定义（`agents/<name>.md`），
 * 由定义决定该子会话的模型、工具集与系统提示词；省略则用继承父会话模型的默认通用子代理。
 */
class TaskTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val eventBus: SubAgentEventBus,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val teamTasks: TeamTasks,
    private val workflowRunner: WorkflowRunner,
    private val aiProviderRepository: AIProviderRepository,
    private val subAgentWriteLease: SubAgentWriteLease,
    private val stageExecutor: TeamStageExecutor,
    private val runRegistry: TeamRunRegistry
) : AbstractContextualTool() {

    /**
     * 派发临界区：「查写路径冲突 → 建会话 → 登记租约」必须串行。
     * 同一轮并发 create 时若不互斥，几个子代理会同时通过冲突检查（线上踩过：两个都改同一个 strings.xml 都没被拦）。
     */
    private val createMutex = Mutex()

    private companion object {
        const val TAG = "TaskTool"
        const val TASK_DESCRIPTION_MAX = 30
        /** `roles` 动作一次最多列多少个角色（避免把 140 个角色全塞进上下文）。 */
        const val ROLE_LIST_LIMIT = 30
        /** `roles` 里每条描述的截断长度。 */
        const val ROLE_DESCRIPTION_MAX = 160

        /** 能改文件/落盘的工具：出现在角色白名单里就说明它不是只读角色。 */
        val WRITE_TOOLS = setOf("writeFile", "editFile", "generateImage")
        /** 能跑命令或操作设备的工具：用在「能不能执行命令」的提示上。 */
        val EXEC_TOOLS = setOf("Bash", "terminal", "Shizuku", "vscreen", "a11y")
        /** 未指定 agent 时子会话记录的类型标识。 */
        const val DEFAULT_SUBAGENT_TYPE = "subagent"
        /** `team` 名单一次最多列多少个队友，以及单个队友标题的截断长度。 */
        const val TEAM_ROSTER_LIMIT = 20
        /** 发给队友的单条消息长度上限（与 messageParent 一致）。 */
        const val SIBLING_MESSAGE_MAX = 4000

    }

    override val name = "task"
    override val permissionPolicy = ToolPermissionPolicy.ASK

    /** 非 PLAN 模式直接放行（派子代理是默认行为）；PLAN 下仍逐次确认。 */
    override fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy =
        if (mode == AgentMode.PLAN) permissionPolicy else ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "read", "list", "send", "roles", "team", "tell", "teams" -> emptySet()
            else -> setOf(ToolCapability.MODIFY_SESSION_STATE)
        }
    }

    override val description: String get() = "管理子代理：创建、发消息、读取结果、停止、删除、列表、查角色。子代理拥有独立上下文与完整工具能力，可并行工作，最多同时运行 ${eventBus.maxRunning} 个。任务复杂或几个活能并行时默认直接派，不必等用户开口；多个独立子任务应同一轮并发发起多个 create。完成后会收到后台通知，不要轮询。用 send 可反复追加指令或对已完成子代理继续追问；子代理运行中也可能主动发消息。create 选角：优先用 department + agentQuery（部门 + 2~5 个功能关键词，中英文都可）让系统选一个最对口的角色；不确定有哪些角色名时先用 action=\"roles\" 查出来，确知角色名时用 agent 点名。" + SubAgentCommGate.descriptionHint(eventBus.communicationEnabled()) + "子代理最多嵌套 2 层。子代理写到租约外被拦时会来求助，用 action=\"grant\"（id + writePaths）运行期追加写权限即可，不必重派。团队预设（teams/*.md）可一键跑完整流程：task(action=\"team_run\", team=\"<名>\", goal=\"<本次目标>\")，阶段内并行、阶段间串行；先用 action=\"teams\" 看有哪些团队。"

    override val parameters: Map<String, ToolParameter> = TaskToolSpec.parameters

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "create" -> createSubagent(args, context)
            "pipeline" -> runPipeline(args, context)
            "send" -> sendToSubagent(args, context)
            "read" -> readSubagent(args, context)
            "stop" -> stopSubagent(args, context)
            "del" -> deleteSubagent(args, context)
            "list" -> listSubagents(context)
            "roles" -> listRoles(args)
            "team" -> if (eventBus.communicationEnabled()) listTeam(context) else SubAgentCommGate.disabledError()
            "tell" -> if (eventBus.communicationEnabled()) tellSibling(args, context) else SubAgentCommGate.disabledError()
            "grant" -> grantWrite(args, context)
            "teams" -> workflowTarget(args)?.let { workflowRunner.list(it) } ?: teamTasks.list(args)
            "team_run" -> runTeam(args, context)
            else -> ToolResult.Error("未知 action: $action，支持：create / pipeline / send / read / stop / del / list / roles / team / tell / grant / teams / team_run", "INVALID_ARGS")
        }
    }

    /** 运行期追加写权限：子代理被租约拦住来求助时，主会话放行即可，不必重派；冲突规则同派发。 */
    private suspend fun grantWrite(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val id = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (id.isEmpty()) return ToolResult.Error("grant 需要 id（子代理会话 id）", "MISSING_ID")
        // 归属校验：只能给自己派出的子代理追加写权限。缺了这条，只读子代理（writePaths=[]）
        // 可以自己 grant ["*"] 把写闸门整个拆掉，也就没有「只读」可言了。
        val target = sessionUseCase.getSessionById(id)
        if (target?.parentId != context.sessionId) {
            return ToolResult.Error("$id 不是你派出的子代理，无权追加写路径；用 action=\"list\" 看自己派出的会话。", "NOT_YOUR_SUBAGENT")
        }
        val paths = parseWritePaths(args).orEmpty()
        if (paths.isEmpty()) return ToolResult.Error("grant 需要非空 writePaths", "MISSING_WRITEPATHS")
        subAgentWriteLease.conflictFor(id, paths, eventBus.activeSubSessionIds.value)?.let { conflictId ->
            return conflictError(conflictId, paths, WriteLease.NEXT_GRANT, context.projectRoot)
        }
        val merged = subAgentWriteLease.grant(id, paths)
        FileLogger.i("TaskTool", "追加写租约 $id += ${paths.joinToString(", ")}（合并后：${merged.joinToString(", ")}）")
        return ToolResult.Success(buildJsonObject {
            put("id", JsonPrimitive(id))
            put("writePaths", buildJsonArray { merged.sorted().forEach { add(JsonPrimitive(it)) } })
        })
    }

    /** 与在跑子代理写冲突的报错：说清是谁（标题取得到就带上）、它占着什么、你申请什么。 */
    private suspend fun conflictError(conflictId: String, mine: Set<String>, next: String, root: String?): ToolResult.Error {
        val title = sessionUseCase.getSessionById(conflictId)?.title
        val theirs = subAgentWriteLease.pathsFor(conflictId)
        return ToolResult.Error(WriteLease.conflictMessage(conflictId, title, mine, theirs, next, root), "WRITE_CONFLICT")
    }

    /**
     * 依赖流水线：一次调用把整张 DAG 交出去。
     *
     * 分层（拓扑排序）后逐层执行：层内并发派发，层间串行等待。上游任务的产出会自动接在
     * 下游 prompt 前面，下游不必再自己去读一遍上游做过什么。价值在于主模型不必
     * 「派一批 → 等 → 再派下一批」地串行驱动链式任务。
     *
     * 同层任务的写范围必须互不相交（借用 [WriteLease.overlaps]，与单任务 create 同一套判定）。
     */
    private suspend fun runPipeline(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        stageExecutor.pipeline(args, context, eventBus, { createSubagent(it, context) }, { readSubagentOutput(it, context) })

    /** 读子代理最后输出；复用 read 路径，保证与手动 read 拿到的是同一份结论。 */
    private suspend fun readSubagentOutput(subSessionId: String, context: AgentContext): String {
        val readArgs = buildJsonObject {
            put("action", "read")
            put("id", subSessionId)
        }
        val result = readSubagent(readArgs, context)
        // read 返回 JsonObject（id / lastOutput / …）：取字段，不要强转 JsonPrimitive——
        // 强转永远为 null，会让「上游产出自动接到下游 prompt 前」静默失效。
        val data = (result as? ToolResult.Success)?.data as? JsonObject
        return (data?.get("lastOutput") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /** 解析 writePaths 参数；未提供返回 null（不登记租约 = 不限制）。 */
    private fun parseWritePaths(args: Map<String, JsonElement>): Set<String>? {
        val raw = args["writePaths"] as? JsonArray ?: return null
        return raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** 创建子代理并启动执行。 */
    private suspend fun createSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val parentSession = sessionUseCase.getSessionById(parentSessionId)
            ?: return ToolResult.Error("当前会话不存在", "SESSION_NOT_FOUND")

        // 递归深度上限：主代理（0）→ 子代理（1）→ 孙代理（2）为限，再深直接拒。
        // 深度按会话记录的 parentId 链回溯，模型无法用参数伪造。
        val depth = subagentDepth(parentSessionId)
        if (depth >= AgentDefinition.MAX_SUBAGENT_DEPTH) {
            return ToolResult.Error(
                "子代理最多嵌套 ${AgentDefinition.MAX_SUBAGENT_DEPTH} 层（主代理 → 子代理 → 孙代理），" +
                    "当前会话已在第 $depth 层，不能再往下派发。继续下派会让代理树无限膨胀、互相等待而卡死；" +
                    "请在本层把任务做完，或把需求用 messageParent 交回上一层统筹。",
                "MAX_SUBAGENT_DEPTH_EXCEEDED"
            )
        }

        // 检查并发上限
        if (eventBus.isFull) {
            return ToolResult.Error(
                "子代理已达上限（最多 ${eventBus.maxRunning} 个同时运行），请先等待其中某个完成或用 stop 停止后再创建",
                "MAX_SUBAGENTS_REACHED"
            )
        }

        val runId = (args["_teamRunId"] as? JsonPrimitive)?.contentOrNull
        val logicalId = (args["_teamLogicalId"] as? JsonPrimitive)?.contentOrNull
        if (runId != null && (logicalId == null || !runRegistry.belongs(runId, parentSessionId, logicalId))) {
            return ToolResult.Error("无效团队任务身份", "INVALID_TEAM_RUN")
        }
        val prompt = (args["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (prompt.isNullOrBlank()) {
            return ToolResult.Error("参数无效：prompt 不能为空", "INVALID_ARGS")
        }
        val description = (args["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(TASK_DESCRIPTION_MAX)
            ?: "子代理任务"

        // 选角顺序：agent 精确名（兼容旧用法）→ department + agentQuery 关键词匹配 → 默认通用子代理。
        // 前两条找不到定义时直接报错并给出可用部门，不静默回退成通用子代理——
        // 否则会拿着错的工具集与提示词跑完整个任务。
        val agentName = (args["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val department = (args["department"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val agentQuery = (args["agentQuery"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        var definition: AgentDefinition? = null
        if (agentName != null || department != null || agentQuery != null) {
            val enabled: List<AgentDefinition> = try {
                agentDefinitionRepository.listEnabled().map { it.definition }
            } catch (e: Exception) {
                FileLogger.e(TAG, "读取子代理定义失败", e)
                return ToolResult.Error("读取子代理定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
            }
            definition = if (agentName != null) {
                enabled.firstOrNull { it.name.equals(agentName, ignoreCase = true) }
                    ?: return ToolResult.Error(
                        "子代理定义不存在: $agentName。可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}；" +
                            "也可改用 department + agentQuery 让系统选角。",
                        "AGENT_NOT_FOUND"
                    )
            } else {
                when (val outcome = SubAgentProfileMatcher.match(enabled, department, agentQuery)) {
                    is SubAgentProfileMatcher.MatchOutcome.Matched -> outcome.definition
                    is SubAgentProfileMatcher.MatchOutcome.Unmatched -> return ToolResult.Error(outcome.reason, "AGENT_NOT_FOUND")
                }
            }
        }

        // 显式 model/provider 覆盖：传了就盖掉角色定义里的同名值，未传则沿用定义（再退到父会话继承）。
        // provider 解析不出时不静默回退，否则用户显式指定的提供商被悄悄忽略，跑在别的模型上。
        val explicitModel = (args["model"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val explicitProvider = (args["provider"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val explicitProviderId = explicitProvider?.let { resolveProviderId(it) }
        if (explicitProvider != null && explicitProviderId == null) {
            return ToolResult.Error(
                "找不到提供商: $explicitProvider（可写 providerId，或设置里显示的提供商名称）",
                "PROVIDER_NOT_FOUND"
            )
        }

        // 并行写隔离：同一时刻两个子代理写同一处会互相踩，冲突的直接拒绝，由主代理串行派发。
        val writePaths = parseWritePaths(args)
        val subSession = createMutex.withLock {
            if (writePaths != null && writePaths.isNotEmpty()) {
                subAgentWriteLease.conflictingSession(writePaths, eventBus.activeSubSessionIds.value)?.let { conflictId ->
                    return conflictError(conflictId, writePaths, WriteLease.NEXT_CREATE, context.projectRoot)
                }
            }
            sessionUseCase.newSubSessionEntity(
                title = description,
                parentId = parentSessionId,
                parent = parentSession,
                subagentType = definition?.name ?: DEFAULT_SUBAGENT_TYPE,
                providerId = explicitProviderId ?: definition?.providerId?.let { resolveProviderId(it) },
                model = explicitModel ?: definition?.model,
                reasoningEffort = definition?.reasoningEffort?.let { effort ->
                    ReasoningEffort.entries.firstOrNull { it.apiValue == effort }?.name
                },
                mode = definition?.mode
            ).also { created ->
                sessionUseCase.upsertSession(created)
                // 写路径租约：派发时声明了才登记；未声明则不限制（保持旧行为）。锁内登记，后续并发派发才看得见。
                writePaths?.let { subAgentWriteLease.register(created.id, it) }
            }
        }
        val subSessionId = subSession.id

        val queued = if (runId != null && logicalId != null) runRegistry.bind(runId, logicalId, subSessionId) else {
            runRegistry.bindStandalone(parentSessionId, subSessionId, description, eventBus.activeSubSessionIds.value)
            ""
        }
        val briefing = if (eventBus.communicationEnabled()) runRegistry.briefing(subSessionId) else ""
        val detail = "$briefing\n\n$queued\n\n---\n\n$prompt"

        // 通知 ViewModel 在子会话上启动 AI 工作流
        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.SPAWNED,
                detail = detail
            )
        )
        FileLogger.i(TAG, "子代理已创建: session=$subSessionId parent=$parentSessionId agent=${definition?.name ?: "-"}")

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "running")
                definition?.let { put("agent", it.name) }
                put("message", "子代理已创建并开始执行，任务完成后会通知。可用 task(action=\"read\", id=...) 读取输出，task(action=\"stop\", id=...) 主动关闭。")
            }
        )
    }

    /**
     * 向指定子代理发送一条消息（可反复发送）。
     *
     * 事件交由 ViewModel 按收件人状态分发：运行中的子代理把消息入通知队列、在下一批工具结果里搭车送达；
     * 已完成的子代理则被重新唤醒并起新一轮。不在工具层直接投递，以复用同一套忙碌/空闲分发逻辑。
     */
    private suspend fun sendToSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != parentSessionId) {
            return ToolResult.Error("只能向当前会话派生的子代理发消息", "NOT_YOUR_SUBAGENT")
        }
        if (!runRegistry.canMessage(subSessionId) || eventBus.terminalEvent(subSessionId)?.type == SubAgentEventType.STOPPED) {
            return ToolResult.Error("用户已停止此任务，普通消息不能重新启动", "TASK_STOPPED")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_PARENT,
                detail = message
            )
        )
        FileLogger.i(TAG, "向子代理发送消息: session=$subSessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "delivered")
                put("message", "消息已投递给子代理。运行中的会在下一批工具结果里收到，已完成的会被重新唤醒；可继续用 send 追加。")
            }
        )
    }

    /**
     * 把定义里的 provider 字段解析为真实 provider id：先按 id 精确匹配，再按名称忽略大小写匹配，
     * 让用户在 frontmatter 里能直接写设置里看到的提供商名。都匹不上则返回 null（继承父会话）。
     */
    private suspend fun resolveProviderId(raw: String): String? {
        aiProviderRepository.getProviderById(raw)?.let { return it.id }
        val all = aiProviderRepository.getAllProviders().first()
        return all.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.id
    }

    /** 读取指定子代理的最后输出。 */
    private suspend fun readSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能读取当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        val messages = messagePersistenceUseCase.getMessagesOnce(subSessionId)
        // 取最后一条有内容的助手回复（跳过 reasoning-only 的中间消息）
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        val content = when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "（子代理尚未回复）请求内容：${fallback.content.take(500)}"
            else -> "（子代理会话为空）"
        }
        val last = runCatching { messages.lastOrNull()?.timestamp ?: 0L }.getOrDefault(0L)

        // 判定与收尾通知共用同一份检查（见 SubAgentResultInspector），避免两条路径各写一套而分叉。
        val inspection = SubAgentResultInspector.inspect(subSessionId, messages)
        val report = inspection.report
        val verdict = inspection.verdict
        val outputText = if (lastAssistant != null) inspection.conclusion.ifBlank { lastAssistant.content } else content

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("title", sub.title)
                put("updatedAt", last)
                put("lastOutput", outputText)
                put("termination", verdict.termination.name)
                if (verdict.reason.isNotBlank()) put("terminationReason", verdict.reason)
                put(
                    "tokenUsage",
                    buildJsonObject {
                        put("inputTokens", inspection.inputTokens)
                        put("outputTokens", inspection.outputTokens)
                    }
                )
                report?.let { r ->
                    put("claimedStatus", r.declaredStatus)
                    put("effectiveStatus", r.effectiveStatus)
                    if (r.summary.isNotBlank()) put("claimSummary", r.summary)
                    if (r.downgraded) {
                        put(
                            "notice",
                            "子代理自报 ${r.declaredStatus}，但有验收项核验不上，按 ${r.effectiveStatus} 处理。"
                        )
                    }
                    put(
                        "verification",
                        buildJsonArray {
                            r.items.forEach { item ->
                                addJsonObject {
                                    put("claim", item.claim)
                                    put("type", item.type)
                                    put("passed", item.passed)
                                    put("note", item.note)
                                }
                            }
                        }
                    )
                    put("adjudication", SubAgentClaimVerifier.renderAdjudication(r))
                }
            }
        )
    }

    /** 停止指定子代理的执行。 */
    private suspend fun stopSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能关闭当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = context.sessionId!!,
                type = SubAgentEventType.STOPPED
            )
        )
        runRegistry.terminal(subSessionId, "stopped")
        subAgentWriteLease.release(subSessionId)
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "stopping")
                put("message", "已请求停止子代理，正在取消其 AI 任务。")
            }
        )
    }

    /** 删除指定子代理会话（含其消息）。若仍在运行先请求停止。 */
    private suspend fun deleteSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能删除当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }
        if (eventBus.activeSubSessionIds.value.contains(subSessionId)) {
            eventBus.emit(
                SubAgentEvent(
                    subSessionId = subSessionId,
                    parentSessionId = context.sessionId!!,
                    type = SubAgentEventType.DELETED
                )
            )
        }
        subAgentWriteLease.release(subSessionId)
        runRegistry.terminal(subSessionId, "deleted")
        sessionUseCase.deleteSession(subSessionId)
        FileLogger.i(TAG, "子代理已删除: session=$subSessionId parent=${context.sessionId}")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "deleted")
                put("message", "子代理已删除（含其全部消息）。")
            }
        )
    }

    /** 列出当前会话的全部子代理。 */
    private suspend fun listSubagents(context: AgentContext): ToolResult {        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val activeIds = eventBus.activeSubSessionIds.value

        val jsonArray = buildJsonArray {
            subs.forEach { entity ->
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", if (entity.id in activeIds) "running" else "completed")
                    put("createdAt", entity.createdAt)
                    put("updatedAt", entity.updatedAt)
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", jsonArray)
                put("count", subs.size)
                put("runningCount", activeIds.size)
                put("maxRunning", eventBus.maxRunning)
            }
        )
    }

    /**
     * 会话在代理树里的深度：主代理（根会话）为 0，其子代理为 1，孙代理为 2……
     * 逐级按 parentId 回溯；带跳过上限，异常数据（如成环）也不会无限递归。
     */
    private suspend fun subagentDepth(sessionId: String): Int {
        var depth = 0
        var cursor = sessionUseCase.getSessionById(sessionId)?.parentId
        while (cursor != null && depth <= AgentDefinition.MAX_SUBAGENT_DEPTH + 1) {
            depth++
            cursor = sessionUseCase.getSessionById(cursor)?.parentId
        }
        return depth
    }

    /**
     * 新子代理的团队简报：列出同批队友（同父会话的其它子代理）与协作方式，
     * 让分支上的模型知道彼此是一个团队、可以互相交流。
     */
    private suspend fun listTeam(context: AgentContext): ToolResult {
        val self = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val roster = runRegistry.roster(self)
        return ToolResult.Success(buildJsonObject {
            put("content", runRegistry.briefing(self))
            put("teammates", JsonArray(roster))
            put("count", roster.size)
        })
    }

    private suspend fun tellSibling(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val self = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val target = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (target.isBlank() || message.isBlank() || message.length > SIBLING_MESSAGE_MAX) {
            return ToolResult.Error("tell 需要 id 与 1~4000 字 message", "INVALID_ARGS")
        }
        val delivery = runRegistry.route(self, target, message)
        if (delivery.state == "blocked") return ToolResult.Error("目标不在本运行批次或任务已停止", "NOT_SIBLING")
        delivery.sessionId?.let { to ->
            if (eventBus.terminalEvent(to)?.type == SubAgentEventType.STOPPED || !eventBus.sendToSibling(self, to, message)) {
                return ToolResult.Error("目标已停止或无法投递", "SIBLING_DELIVERY_FAILED")
            }
        }
        return ToolResult.Success(buildJsonObject { put("state", delivery.state); put("to", target) })
    }

    private fun roleToolSummary(definition: AgentDefinition): String {
        val allowed = definition.allowedTools
        val disallowed = definition.disallowedTools
        val tools = when {
            allowed.isNotEmpty() -> allowed.joinToString("、")
            disallowed.isNotEmpty() -> "默认全量（禁用 ${disallowed.joinToString("、")}）"
            else -> "默认全量"
        }
        if (allowed.isEmpty()) return tools
        val notes = buildList {
            if (allowed.none { it in WRITE_TOOLS }) add("只读")
            if (allowed.none { it in EXEC_TOOLS }) add("不能执行命令")
        }
        return if (notes.isEmpty()) tools else "$tools ｜ ${notes.joinToString("、")}"
    }

    /**
     * 列出可用子代理角色（不创建）：[department] 收窄部门，[agentQuery] 走与 create 同一套选角打分排序。
     * 常驻索引里每个部门只展示 2 个代表角色，主代理想点名时先用这里把名字与用途查出来。
     */
    private suspend fun listRoles(args: Map<String, JsonElement>): ToolResult {
        val department = (args["department"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val agentQuery = (args["agentQuery"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val enabled: List<AgentDefinition> = try {
            agentDefinitionRepository.listEnabled().map { it.definition }
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取子代理定义失败", e)
            return ToolResult.Error("读取子代理定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
        }
        if (enabled.isEmpty()) {
            return ToolResult.Error("当前没有可用的子代理定义", "AGENT_LIST_EMPTY")
        }
        if (department != null && SubAgentProfileMatcher.canonicalDepartment(department) == null) {
            return ToolResult.Error(
                "未知部门：$department。可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}",
                "INVALID_ARGS"
            )
        }

        val ranked = SubAgentProfileMatcher.candidates(enabled, department, agentQuery, ROLE_LIST_LIMIT)
        if (ranked.isEmpty()) {
            return ToolResult.Error(
                "没有匹配的角色（department=${department ?: "-"}, agentQuery=${agentQuery ?: "-"}）。" +
                    "可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}",
                "AGENT_NOT_FOUND"
            )
        }
        val content = buildString {
            append("可用子代理角色")
            if (department != null) append("（department=").append(department).append("）")
            if (agentQuery != null) append("（agentQuery=\"").append(agentQuery).append("\" 排序）")
            append("，共 ").append(ranked.size).append(" 个：\n")
            ranked.forEach { (definition, _) ->
                val summary = definition.description.lineSequence().first().trim().take(ROLE_DESCRIPTION_MAX)
                append("- ").append(definition.name).append("：").append(summary)
                    .append("（工具：").append(roleToolSummary(definition)).append("）\n")
            }
            append("派发：task(action=\"create\", agent=\"<名称>\", prompt=\"…\")；不确定就用 department + agentQuery 让系统选角。")
        }
        return ToolResult.Success(
            buildJsonObject {
                put("content", content)
                put("count", ranked.size)
            }
        )
    }

    /**
     * 按预设执行：给了 `workflow` 就走多团队工作流（逐层判定 + 专家级驳回），否则按单个团队跑。
     * 两条路共用 [runPipeline]，分层调度与写冲突拦截不重复实现。
     */
    private suspend fun runTeam(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        if (workflowTarget(args) != null) {
            workflowRunner.run(args) { pipeline ->
                runPipeline(buildJsonObject { put("pipeline", pipeline) }, context)
            }
        } else {
            teamTasks.run(args, context.sessionId.orEmpty()) { pipeline ->
                runPipeline(buildJsonObject { put("pipeline", pipeline) }, context)
            }
        }

    /** `workflow` 参数：空或缺失返回 null（按单团队跑），`*` 用于列工作流清单。 */
    private fun workflowTarget(args: Map<String, JsonElement>): String? =
        (args["workflow"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}