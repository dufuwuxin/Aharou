package com.aharou.feature.agent.presentation

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.steward.StewardController
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

    fun onRequestStarted(sessionId: String, request: String, isAutoTrigger: Boolean) {
        if (!isAutoTrigger && !request.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            stewardController.bindAnchor(sessionId, request)
        }
    }

    /** 判定留在本轮 job 内；完成回调真正出队时再核验一次，关闭/换基准后不能续发旧结果。 */
    suspend fun onTurnFinished(sessionId: String): (() -> String?)? {
        val ticket = stewardController.currentTurn(sessionId) ?: return null
        return try {
            val lastReport = messagePersistenceUseCase.getMessagesOnce(sessionId)
                .lastOrNull { it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank() }
                ?.content.orEmpty()
            var next: String? = null
            stewardController.onAiIdle(ticket, lastReport) { next = it }
            next?.let { instruction ->
                { instruction.takeIf { stewardController.isCurrent(ticket) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w("StewardBridge", "托管判定失败，放行用户队列", e)
            null
        }
    }
}
