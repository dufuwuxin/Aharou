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
import com.aharou.feature.agent.domain.tool.stripToolTitle
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

class TaskTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val eventBus: SubAgentEventBus,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val teamTasks: TeamTasks,
    private val aiProviderRepository: AIProviderRepository,
    private val subAgentWriteLease: SubAgentWriteLease,
    private val stageExecutor: TeamStageExecutor,
    private val runRegistry: TeamRunRegistry
) : AbstractContextualTool() {

    private val createMutex = Mutex()

    private companion object {
        const val TAG = "TaskTool"
        const val TASK_DESCRIPTION_MAX = 30

        val WRITE_TOOLS = setOf("writeFile", "editFile", "generateImage")
        val EXEC_TOOLS = setOf("Bash", "terminal", "Shizuku", "vscreen", "a11y")
        const val DEFAULT_SUBAGENT_TYPE = "subagent"

    }

    override val name = "task"
    override val permissionPolicy = ToolPermissionPolicy.ASK

    override fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy =
        if (mode == AgentMode.PLAN) permissionPolicy else ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: ""
        return when (action) {
            "teams" -> emptySet()
            else -> setOf(ToolCapability.MODIFY_SESSION_STATE)
        }
    }

    override val description: String = "查看团队名称与介绍，并根据介绍向团队分配任务。主代理不查询或直调成员，不读取成员消息；成员分工与内部交接由团队执行。"

    override val parameters: Map<String, ToolParameter> = TaskToolSpec.parameters

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val taskArgs = stripToolTitle(args)
        if (taskArgs.keys.any { it !in parameters }) {
            return ToolResult.Error("只接受团队动作、团队名称和任务目标", "INVALID_ARGS")
        }
        val action = (taskArgs["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        return when (action) {
            "teams" -> teamTasks.list(taskArgs)
            "team_run" -> publicTeamResult(runTeam(taskArgs, context), taskArgs)
            else -> ToolResult.Error("仅支持 teams / team_run", "INVALID_ARGS")
        }
    }

    private suspend fun conflictError(conflictId: String, mine: Set<String>, next: String, root: String?): ToolResult.Error {
        val title = sessionUseCase.getSessionById(conflictId)?.title
        val theirs = subAgentWriteLease.pathsFor(conflictId)
        return ToolResult.Error(WriteLease.conflictMessage(conflictId, title, mine, theirs, next, root), "WRITE_CONFLICT")
    }

    private suspend fun runPipeline(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        stageExecutor.pipeline(args, context, eventBus, { createSubagent(it, context) }, { readSubagentOutput(it, context) })

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

    private fun parseWritePaths(args: Map<String, JsonElement>): Set<String>? {
        val raw = args["writePaths"] as? JsonArray ?: return null
        return raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private suspend fun createSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val parentSession = sessionUseCase.getSessionById(parentSessionId)
            ?: return ToolResult.Error("当前会话不存在", "SESSION_NOT_FOUND")

        // 递归深度上限：主代理（0）→ 成员（1）→ 孙代理（2）为限，再深直接拒。
        // 深度按会话记录的 parentId 链回溯，模型无法用参数伪造。
        val depth = subagentDepth(parentSessionId)
        if (depth >= AgentDefinition.MAX_SUBAGENT_DEPTH) {
            return ToolResult.Error(
                "成员最多嵌套 ${AgentDefinition.MAX_SUBAGENT_DEPTH} 层（主代理 → 成员 → 孙代理），" +
                    "当前会话已在第 $depth 层，不能再往下派发。继续下派会让代理树无限膨胀、互相等待而卡死；" +
                    "请在本层把任务做完，或把需求用 messageParent 交回上一层统筹。",
                "MAX_SUBAGENT_DEPTH_EXCEEDED"
            )
        }

        // 检查并发上限
        if (eventBus.isFull) {
            return ToolResult.Error(
                "成员已达上限（最多 ${eventBus.maxRunning} 个同时运行），请先等待其中某个完成或用 stop 停止后再创建",
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
            ?: "成员任务"

        // 选角顺序：agent 精确名（兼容旧用法）→ department + agentQuery 关键词匹配 → 默认通用成员。
        // 前两条找不到定义时直接报错并给出可用部门，不静默回退成通用成员——
        // 否则会拿着错的工具集与提示词跑完整个任务。
        val agentName = (args["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val department = (args["department"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val agentQuery = (args["agentQuery"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        var definition: AgentDefinition? = null
        if (agentName != null || department != null || agentQuery != null) {
            val enabled: List<AgentDefinition> = try {
                agentDefinitionRepository.listEnabled().map { it.definition }
            } catch (e: Exception) {
                FileLogger.e(TAG, "读取成员定义失败", e)
                return ToolResult.Error("读取成员定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
            }
            definition = if (agentName != null) {
                enabled.firstOrNull { it.name.equals(agentName, ignoreCase = true) }
                    ?: return ToolResult.Error(
                        "成员定义不存在: $agentName。可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}；" +
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

        // 并行写隔离：同一时刻两个成员写同一处会互相踩，冲突的直接拒绝，由主代理串行派发。
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
        val briefing = ""
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
        FileLogger.i(TAG, "成员已创建: session=$subSessionId parent=$parentSessionId agent=${definition?.name ?: "-"}")

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "running")
                definition?.let { put("agent", it.name) }
                put("message", "成员已创建并开始执行，任务完成后会通知。可用 task(action=\"read\", id=...) 读取输出，task(action=\"stop\", id=...) 主动关闭。")
            }
        )
    }

    private suspend fun resolveProviderId(raw: String): String? {
        aiProviderRepository.getProviderById(raw)?.let { return it.id }
        val all = aiProviderRepository.getAllProviders().first()
        return all.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.id
    }

    private suspend fun readSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能读取当前会话派生的成员", "NOT_YOUR_SUBAGENT")
        }

        val messages = messagePersistenceUseCase.getMessagesOnce(subSessionId)
        // 取最后一条有内容的助手回复（跳过 reasoning-only 的中间消息）
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        val content = when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "（成员尚未回复）请求内容：${fallback.content.take(500)}"
            else -> "（成员会话为空）"
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
                            "成员自报 ${r.declaredStatus}，但有验收项核验不上，按 ${r.effectiveStatus} 处理。"
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

    private suspend fun subagentDepth(sessionId: String): Int {
        var depth = 0
        var cursor = sessionUseCase.getSessionById(sessionId)?.parentId
        while (cursor != null && depth <= AgentDefinition.MAX_SUBAGENT_DEPTH + 1) {
            depth++
            cursor = sessionUseCase.getSessionById(cursor)?.parentId
        }
        return depth
    }

    private suspend fun runTeam(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        teamTasks.run(args, context.sessionId.orEmpty()) { pipeline ->
            runPipeline(buildJsonObject { put("pipeline", pipeline) }, context)
        }

    private fun publicTeamResult(result: ToolResult, args: Map<String, JsonElement>): ToolResult {
        if (result is ToolResult.Error) return ToolResult.Error("团队执行未完成，请在团队执行记录查看详情", result.code)
        val data = (result as? ToolResult.Success)?.data as? JsonObject
            ?: return ToolResult.Error("团队未返回执行状态", "TEAM_RESULT_MISSING")
        return ToolResult.Success(buildJsonObject {
            put("team", args["team"] ?: JsonPrimitive(""))
            for (key in listOf("state", "runId", "reworkRounds")) data[key]?.let { put(key, it) }
        })
    }


}