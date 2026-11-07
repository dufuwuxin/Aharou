package com.aharou.feature.agent.domain.tool.mode

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "PlanApprovalManager"

/**
 * 管理计划审查流程的挂起/恢复：
 * 当 AI 从 PLAN 模式切回 BUILD 模式时，workflow 调用 [awaitApproval] 挂起，
 * 等 UI 上的计划审查面板得到用户决策后恢复。
 *
 * 多会话可同时挂起：按请求 id 入表，对外只投影最早进入的那条（UI 面板/通知一次只展示一个）。
 * 单槽实现下后一个会话会覆盖前一个，前者永久挂起且解析可能落到错误的会话。
 */
@Singleton
class PlanApprovalManager @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) {
    private data class Entry(
        val request: PlanApprovalRequest,
        val decision: CompletableDeferred<PlanApprovalChoice>
    )

    private val entries = LinkedHashMap<String, Entry>()

    private val _pendingApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingApproval: StateFlow<PlanApprovalRequest?> = _pendingApproval.asStateFlow()

    /** 挂起等待用户在计划审查面板中的决策。 */
    suspend fun awaitApproval(reason: String, sessionId: String? = null): PlanApprovalChoice {
        val request = PlanApprovalRequest(
            id = UUID.randomUUID().toString(),
            reason = reason,
            sessionId = sessionId.orEmpty()
        )
        val decision = CompletableDeferred<PlanApprovalChoice>()
        synchronized(entries) {
            entries[request.id] = Entry(request, decision)
            _pendingApproval.value = entries.values.first().request
        }

        try {
            val choice = decision.await()
            if (choice == PlanApprovalChoice.REFINE) rollbackModeToPlan(sessionId)
            return choice
        } catch (e: CancellationException) {
            // 用户未批准切换（点「停止」等取消路径）：SwitchModeTool 已把目标模式写入 DB，
            // 取消意味着未获批准，需回滚，否则模式会未经批准直接切过去。
            try {
                withContext(NonCancellable) { rollbackModeToPlan(sessionId) }
            } catch (failure: Exception) {
                e.addSuppressed(failure)
            }
            throw e
        } finally {
            synchronized(entries) {
                entries.remove(request.id)
                _pendingApproval.value = entries.values.firstOrNull()?.request
            }
        }
    }

    /** 按请求 id 回传用户选择（通知栏按钮走这条：陈旧的 id 解析不到，自然不会误批新请求）。 */
    fun resolve(id: String, choice: PlanApprovalChoice) {
        val entry = synchronized(entries) {
            entries[id]?.also {
                entries.remove(id)
                _pendingApproval.value = entries.values.firstOrNull()?.request
            }
        } ?: return

        entry.decision.complete(choice)
    }

    /** 回滚会话模式到 PLAN（SwitchModeTool 已提前写入目标模式，未获批准时还原）。 */
    private suspend fun rollbackModeToPlan(sessionId: String?) {
        val sid = sessionId ?: return
        runCatching {
            val entity = chatSessionDao.getById(sid)
            if (entity != null && entity.mode != "PLAN") {
                chatSessionDao.upsert(entity.copy(mode = "PLAN"))
            }
        }.onFailure { FileLogger.w(TAG, "回滚 PLAN 模式失败: $sid", it) }.getOrThrow()
    }
}

/**
 * 一次计划审批请求。
 *
 * @param id 本次请求的标识；通知栏按钮据它定位到具体请求，避免旧通知批到新请求。
 * @param reason AI 给出的切换理由（计划摘要）。
 * @param sessionId 发起审批的会话 id；用于在通知栏标注归属、点通知回到对应会话。
 */
data class PlanApprovalRequest(
    val reason: String,
    val sessionId: String = "",
    val id: String = ""
)

enum class PlanApprovalChoice {
    /** 批准计划，切换到 BUILD 模式执行 */
    APPROVE,
    /** 拒绝，留在 PLAN 模式继续反馈 */
    REFINE
}
