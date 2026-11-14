package com.aharou.feature.agent.domain.session

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.steward.StewardSettingsStore
import com.aharou.feature.agent.presentation.MessageRole
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionUseCase @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val messageArchiveStore: MessageArchiveStore,
    /** 智能托管按会话存开关：删会话时要把它的键清掉，不留垃圾。 */
    private val stewardSettings: StewardSettingsStore
) {
    @Inject lateinit var projectTasks: javax.inject.Provider<com.aharou.feature.agent.data.task.ProjectTaskRepository>

    private val workspaceBindings = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    suspend fun awaitWorkspaceBinding(sessionId: String) {
        while (true) (workspaceBindings[sessionId] ?: return).await()
    }

    suspend fun bindWorkspace(sessionId: String, path: String, canBind: () -> Boolean) {
        check(canBind()) { "SESSION_BUSY" }
        val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
        check(workspaceBindings.putIfAbsent(sessionId, pending) == null) { "SESSION_BUSY" }
        try {
            require(path.isNotBlank()) { "WORKSPACE_REQUIRED" }
            val session = chatSessionDao.getById(sessionId) ?: error("SESSION_NOT_FOUND")
            check(session.parentId == null) { "SESSION_WORKSPACE_FIXED" }
            val store = if (session.workspacePath.isBlank()) com.aharou.feature.agent.data.task.ProjectTaskSnapshot()
                else projectTasks.get().load(session.workspacePath)
            check(store.projects.none { sessionId in it.flowSessionIds } &&
                store.tasks.none { sessionId in it.sessionIds || sessionId in it.processSessionIds }) { "SESSION_WORKSPACE_FIXED" }
            check(canBind()) { "SESSION_BUSY" }
            check(chatSessionDao.bindWorkspacePath(sessionId, session.workspacePath, path) == 1) { "SESSION_CHANGED" }
        } finally {
            workspaceBindings.remove(sessionId, pending)
            pending.complete(Unit)
        }
    }

    companion object {
        private const val TAG = "SessionUseCase"
        const val TITLE_MAX = 20
        /** 工具占位行前缀：标记「执行中、结果未回」的孤儿，UI 与回放据此识别。 */
        const val PENDING_TOOL_MARKER = "[running]"
        /** 历史版本 emoji 前缀；冷启动收尾与回放仍需识别。 */
        const val LEGACY_PENDING_TOOL_MARKER = "\u23F3"
        /** 历史版本停止 emoji 前缀；UI 剥离结果文本时兼容。 */
        const val LEGACY_STOPPED_TOOL_MARKER = "\u23F9"
        const val INTERRUPTED_TOOL_TEXT = "执行被中断（应用已关闭）"
    }

    /** 冷启动收尾：上次进程被杀时若有工具正在执行，其占位行会永久显示「执行中」。 */
    suspend fun initColdStartCleanup() {
        runCatching {
            val n = listOf(PENDING_TOOL_MARKER, LEGACY_PENDING_TOOL_MARKER).sumOf { marker ->
                agentMessageDao.markPendingToolsInterrupted(
                    toolRole = MessageRole.TOOL.name,
                    pendingPrefix = "$marker%",
                    interruptedContent = INTERRUPTED_TOOL_TEXT
                )
            }
            if (n > 0) FileLogger.i(TAG, "冷启动收尾 $n 条残留「执行中」工具行为已中断")
        }.onFailure { FileLogger.e(TAG, "回收残留执行中工具行失败", it) }
    }

    fun newSessionEntity(
        workspacePath: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String = ReasoningEffort.DEFAULT.name,
        // Aharou: 新会话默认 AUTO（自动化、免逐项确认）；用户仍可在会话内手动切 BUILD/PLAN。
        mode: String = AgentMode.AUTO.name
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            workspacePath = workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort,
            mode = mode
        )
    }

    fun deriveTitle(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= TITLE_MAX) clean.ifBlank { "新对话" }
        else clean.take(TITLE_MAX) + "…"
    }

    /** 删除会话及其全部子代理会话（消息 + 会话记录一并删除）。返回被删除的会话 id 列表。 */
    suspend fun deleteSession(id: String): List<String> {
        val deleted = mutableListOf(id)
        // 递归收集子会话（v1 仅一层，循环即可）
        chatSessionDao.getSubSessionsByParentOnce(id).forEach { child ->
            agentMessageDao.deleteBySession(child.id)
            chatSessionDao.delete(child.id)
            messageArchiveStore.deleteSession(child.id)
            stewardSettings.forgetSession(child.id)
            deleted.add(child.id)
        }
        agentMessageDao.deleteBySession(id)
        chatSessionDao.delete(id)
        messageArchiveStore.deleteSession(id)
        stewardSettings.forgetSession(id)
        return deleted
    }

    /** 删除某工作区下的全部会话（含子代理会话）及其消息，返回删除的会话数。 */
    suspend fun deleteSessionsByWorkspace(workspacePath: String): Int {
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(workspacePath)
        if (sessions.isEmpty()) return 0
        sessions.forEach { session -> agentMessageDao.deleteBySession(session.id) }
        chatSessionDao.deleteByWorkspace(workspacePath)
        sessions.forEach { session -> messageArchiveStore.deleteSession(session.id) }
        sessions.forEach { session -> stewardSettings.forgetSession(session.id) }
        return sessions.size
    }

    /** 工作区改名后，把该工作区下全部会话（含子代理会话）的 workspacePath 迁到新路径，返回迁移条数。 */
    suspend fun updateSessionsWorkspacePath(oldPath: String, newPath: String): Int =
        chatSessionDao.updateWorkspacePath(oldPath, newPath)

    suspend fun getFirstSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).firstOrNull()
    }

    /**
     * 工作区里一条「还没发过消息」的根会话，供新建会话时复用，避免每点一次「新建」就多插一条空会话。
     */
    suspend fun getEmptySessionOfWorkspace(workspacePath: String): ChatSessionEntity? =
        getOrdinaryEmptySession(workspacePath)

    private suspend fun projectSessionIds(workspacePath: String): Set<String> {
        val store = projectTasks.get().load(workspacePath)
        return (store.projects.flatMap { it.flowSessionIds } +
            store.tasks.flatMap { it.sessionIds + it.processSessionIds }).toSet()
    }

    suspend fun getOrdinaryEmptySession(workspacePath: String, currentSessionId: String? = null): ChatSessionEntity? {
        val owned = projectSessionIds(workspacePath)
        suspend fun reusable(session: ChatSessionEntity): Boolean = session.workspacePath == workspacePath &&
            session.parentId == null && session.id !in owned && !agentMessageDao.hasMessages(session.id)
        currentSessionId?.let { chatSessionDao.getById(it) }?.let { if (reusable(it)) return it }
        return chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).firstOrNull { reusable(it) }
    }

    /** 工作区里最近更新的根会话（忽略置顶），供启动时进入最近会话。 */
    suspend fun getMostRecentSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getLatestRootSessionByWorkspace(workspacePath)
    }

    /** 回收工作区下多余的空会话（从未发送过消息），保留 [keepId]；保证列表最多一个空会话。 */
    suspend fun recycleEmptySessions(workspacePath: String, keepId: String? = null): Int {
        var count = 0
        val owned = projectSessionIds(workspacePath)
        chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).forEach { session ->
            if (session.id != keepId && session.id !in owned && !agentMessageDao.hasMessages(session.id) &&
                chatSessionDao.getSubSessionsByParentOnce(session.id).none { it.id in owned }) {
                deleteSession(session.id)
                count++
            }
        }
        return count
    }

    /**
     * 创建子代理会话，继承父会话的 provider/model/reasoningEffort/workspacePath。
     * @param title 子会话标题（由 task 描述派生）
     * @param parentId 父会话 id
     * @param subagentType 子代理类型 id（自定义 agent 名，或默认通用子代理标识）
     * @param providerId 覆盖 provider；null 表示继承父会话
     * @param model 覆盖模型；null 表示继承父会话
     * @param reasoningEffort 覆盖思考强度；null 表示继承父会话
     * @param mode 覆盖运行模式；null 表示继承父会话
     */
    fun newSubSessionEntity(
        title: String,
        parentId: String,
        parent: ChatSessionEntity,
        subagentType: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        mode: AgentMode? = null
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            workspacePath = parent.workspacePath,
            createdAt = now,
            updatedAt = now,
            // 默认继承父会话 mode：权限引擎按会话 mode 判定写拦截，默认 BUILD 会让 PLAN
            // 模式下派出的子代理绕过只读限制，反过来 AUTO 模式的子代理又会弹授权窗打扰用户。
            // 自定义子代理的 mode 只能更严（limitedBy），不能把父会话的权限放宽。
            mode = mode?.let { definitionMode ->
                val parentMode = runCatching { AgentMode.valueOf(parent.mode) }.getOrDefault(AgentMode.BUILD)
                definitionMode.limitedBy(parentMode).name
            } ?: parent.mode,
            providerId = providerId ?: parent.providerId,
            model = model ?: parent.model,
            reasoningEffort = reasoningEffort ?: parent.reasoningEffort,
            parentId = parentId,
            subagentType = subagentType
        )
    }

    suspend fun upsertSession(entity: ChatSessionEntity) {
        chatSessionDao.upsert(entity)
    }

    suspend fun updateTitle(sessionId: String, title: String) {
        chatSessionDao.updateTitle(sessionId, title)
    }

    suspend fun updatePinned(sessionId: String, pinned: Boolean) {
        chatSessionDao.updatePinned(sessionId, pinned)
    }

    suspend fun touch(sessionId: String, timestamp: Long) {
        chatSessionDao.touch(sessionId, timestamp)
    }

    suspend fun getSessionById(id: String): ChatSessionEntity? {
        return chatSessionDao.getById(id)
    }

    suspend fun updateMode(sessionId: String, mode: String) {
        val s = chatSessionDao.getById(sessionId) ?: return
        val newMode = runCatching { AgentMode.valueOf(mode) }.getOrNull() ?: return
        val currentMode = runCatching { AgentMode.valueOf(s.mode) }.getOrNull()
        // 用户手动进入 PLAN 时同样记下进入前的模式，退出 PLAN 时恢复（与 AI 自切保持同一套语义）。
        val modeBeforePlan = when {
            newMode == AgentMode.PLAN && currentMode != AgentMode.PLAN -> currentMode?.name
            newMode == AgentMode.PLAN -> s.modeBeforePlan
            else -> null
        }
        chatSessionDao.upsert(s.copy(mode = newMode.name, modeBeforePlan = modeBeforePlan))
    }

    suspend fun updateProviderModel(sessionId: String, providerId: String?, model: String?) {
        chatSessionDao.updateProviderModel(sessionId, providerId, model)
    }

    suspend fun updateReasoningEffort(sessionId: String, effort: String) {
        chatSessionDao.updateReasoningEffort(sessionId, effort)
    }

    suspend fun updateLastInputTokens(sessionId: String, tokens: Int) {
        chatSessionDao.updateLastInputTokens(sessionId, tokens)
    }

    suspend fun isSessionEmpty(sessionId: String): Boolean {
        return !agentMessageDao.hasMessages(sessionId)
    }
}
