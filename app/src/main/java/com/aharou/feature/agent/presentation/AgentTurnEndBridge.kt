package com.aharou.feature.agent.presentation

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.command.SlashCommandRegistry
import com.aharou.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aharou.feature.agent.domain.steward.StewardTurnPolicy
import com.aharou.feature.terminal.data.repository.TerminalSettingsRepository
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一轮 AI 结束后要做的旁路动作分发：托管判定（交给 [StewardBridge]）+ 临时终端标签清理。
 *
 * 为什么是「分发器」而不是在 [AIAgentViewModel] 里再写一行调用：那个 ViewModel 顶在架构门禁的
 * 行数基线上（零余量），新增收尾动作塞不进去。于是把原本唯一的 [StewardBridge.onTurnFinished]
 * 调用点升级为这一层——托管逻辑原样转交，终端清理挂在这里，之后再有收尾动作也往这儿加，
 * ViewModel 侧始终保持同一个调用点。
 *
 * 清理只动「AI 创建 + 一次性任务（notify=true）+ 已结束」的标签：用户手动开的标签、dev server
 * 之类的常驻服务、还在跑的标签一律不碰。开关关闭时整段短路，行为完全回到「标签一直留着」。
 */
@Singleton
class AgentTurnEndBridge @Inject constructor(
    private val stewardBridge: StewardBridge,
    private val slashCommands: SlashCommandRegistry,
    private val terminalSessions: TerminalSessionProvider,
    private val terminalSettings: TerminalSettingsRepository,
) {
    private companion object {
        const val TAG = "AgentTurnEndBridge"
    }

    /**
     * 清理跑在自己持有的 scope 上：ViewModel 在回合收尾后可能马上被清掉（切走聊天页/退出），
     * 挂在 viewModelScope 上会被连同取消，标签就留在那儿了。
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingSteward = mutableMapOf<String, Pair<() -> String?, String>>()

    fun consumeDeferredInstruction(sessionId: String): String? = pendingSteward.remove(sessionId)?.first?.invoke()

    fun onRequestStarted(sessionId: String, request: String, isAutoTrigger: Boolean) =
        stewardBridge.onRequestStarted(sessionId, request, isAutoTrigger)

    /** 终端清理仍独立存活，主管判定则必须等完才能放行普通队列。 */
    suspend fun onTurnFinished(sessionId: String, allowSteward: Boolean): (() -> String?)? {
        closeFinishedAgentTabs(sessionId)
        return if (allowSteward) stewardBridge.onTurnFinished(sessionId) else null
    }

    /** FIFO 只有本轮已排队请求；未来轮次通知不在这里领取，也不改变用户消息的落库正文。 */
    fun takeNextRequest(
        sessionId: String,
        queues: MutableStateFlow<Map<String, List<QueuedRequest>>>,
        nextInstruction: (() -> String?)?,
        projectRoot: String,
    ): QueuedRequest? {
        if (nextInstruction != null) pendingSteward[sessionId] = nextInstruction to projectRoot
        val queue = queues.value[sessionId].orEmpty()
        val current = queue.firstOrNull()
        if (current != null) {
            queues.value = queues.value + (sessionId to queue.drop(1))
            if (slashCommands.resolve(current.request) is ResolvedCommand.Action) return current
            return current.copy(modelRequest = StewardTurnPolicy.mergeModelRequest(current.modelRequest, consumeDeferredInstruction(sessionId)))
        }
        val nextProjectRoot = pendingSteward[sessionId]?.second ?: projectRoot
        return consumeDeferredInstruction(sessionId)?.let {
            QueuedRequest(
                id = UUID.randomUUID().toString(), request = it, currentFile = null,
                selectedCode = null, projectRoot = nextProjectRoot, isAutoTrigger = true,
            )
        }
    }

    private fun closeFinishedAgentTabs(sessionId: String) {
        cleanupScope.launch {
            val enabled = runCatching { terminalSettings.autoCloseFinishedAgentTabs() }
                .onFailure { FileLogger.w(TAG, "读取终端自动清理开关失败，跳过本轮清理", it) }
                .getOrDefault(false)
            if (!enabled) return@launch
            // TerminalSessionManager 的可变状态按约定只在主线程读写。
            val closed = runCatching {
                withContext(Dispatchers.Main) { terminalSessions.closeFinishedAgentTabs() }
            }.onFailure {
                FileLogger.e(TAG, "回合结束自动清理终端标签失败（会话 $sessionId）", it)
            }.getOrDefault(0)
            if (closed > 0) {
                FileLogger.i(TAG, "会话 $sessionId 回合结束，自动关闭 ${closed} 个已结束的临时终端标签")
            }
        }
    }
}
