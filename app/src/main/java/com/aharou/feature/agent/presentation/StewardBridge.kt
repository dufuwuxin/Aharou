package com.aharou.feature.agent.presentation

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.steward.StewardController
import com.aharou.feature.agent.domain.steward.StewardTurnPolicy
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 智能托管与会话链路之间的桥：把「一轮 AI 结束」翻译成一次托管判定，需要时再把下一步发回同一会话。
 *
 * 单独成类而不是写在 [AIAgentViewModel] 里：那个文件已经顶在架构门禁的行数上限，而这一层只是转接
 * （取最后一条助手消息 → 交判定 → 回调发送），留在 ViewModel 里只会让它继续膨胀。
 */
@Singleton
class StewardBridge @Inject constructor(
    private val stewardController: StewardController,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) {

    private val finishedTurns = mutableMapOf<String, StewardTurnPolicy.Ticket>()

    fun onRequestStarted(sessionId: String, request: String, isAutoTrigger: Boolean) {
        val instruction = request.takeIf { !isAutoTrigger && !it.startsWith(BACKGROUND_NOTIFICATION_PREFIX) }
        stewardController.startTurn(sessionId, instruction)
    }

    /** 判定留在本轮 job 内；完成回调真正出队时再核验一次，关闭/换基准后不能续发旧结果。 */
    suspend fun onTurnFinished(sessionId: String): (() -> String?)? {
        val pending = stewardController.currentTurn(sessionId, requireInstruction = false) ?: return null
        return try {
            val history = messagePersistenceUseCase.getMessagesOnce(sessionId)
            if (!stewardController.isCurrent(pending)) return null
            if (stewardController.currentTurn(sessionId) == null) {
                val anchor = history.lastOrNull { it.role == MessageRole.USER.name && it.content.isNotBlank() &&
                    !it.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX) }?.content.orEmpty()
                stewardController.bindAnchor(sessionId, anchor, pending.generation)
            }
            val ticket = stewardController.currentTurn(sessionId)?.takeIf { it == pending } ?: return null
            synchronized(finishedTurns) {
                if (!stewardController.isCurrent(ticket) || finishedTurns[sessionId] == ticket) return null
                finishedTurns[sessionId] = ticket
            }
            val lastReport = history.lastOrNull { it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank() }?.content.orEmpty()
            var next: String? = null
            stewardController.onAiIdle(ticket, lastReport) { next = it }
            next?.let { instruction ->
                val delivered = AtomicBoolean(false)
                return@let { instruction.takeIf { stewardController.isCurrent(ticket) && delivered.compareAndSet(false, true) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w("StewardBridge", "托管判定失败，放行用户队列", e)
            null
        }
    }
}
