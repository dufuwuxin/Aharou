package com.aharou.feature.agent.domain.steward

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管控制器：每轮 AI 回答结束（空闲）时旁路问一次「用户最初那句做完了没」，
 * 没做完就把下一步指令送回同一个会话。
 *
 * 只负责判定与轮次记账，发送动作由会话链路通过 [onAiIdle] 的 send 回调注入——
 * 控制器不反向依赖 ViewModel，也不碰界面。
 */
@Singleton
class StewardController @Inject constructor(
    private val settings: StewardSettings,
    private val judge: StewardJudge,
) {
    private val _state = MutableStateFlow(StewardState())
    val state: StateFlow<StewardState> = _state.asStateFlow()

    private val turnPolicy = StewardTurnPolicy()
    private var loopCount = 0

    /** 旧判定的 finally 不得释放重新开启后的判定占用。 */
    private var judging: StewardTurnPolicy.Ticket? = null

    val enabled: Boolean get() = settings.enabled

    init {
        // 基准指令只活在进程内，重启后没有基准就无从判断——把开关按实际能力收回来，
        // 免得界面显示「开着」却什么也不做。
        if (settings.enabled) settings.enabled = false
        publish(StewardStatus.Idle)
    }

    fun enable(anchorInstruction: String, sessionId: String? = null): Long {
        val revision = turnPolicy.enable(anchorInstruction, sessionId)
        judging = null
        loopCount = 0
        settings.enabled = true
        publish(if (anchorInstruction.isBlank()) StewardStatus.Idle else StewardStatus.Monitoring)
        return revision
    }

    fun bindAnchor(sessionId: String, instruction: String, revision: Long? = null) {
        if (revision == null) turnPolicy.bindAnchor(sessionId, instruction)
        else turnPolicy.bindAnchor(sessionId, instruction, revision)
        if (enabled && turnPolicy.instruction.isNotBlank() && _state.value.status == StewardStatus.Idle) {
            publish(StewardStatus.Monitoring)
        }
    }

    fun currentTurn(sessionId: String): StewardTurnPolicy.Ticket? =
        if (enabled) turnPolicy.ticket(sessionId) else null

    fun isCurrent(ticket: StewardTurnPolicy.Ticket): Boolean = enabled && turnPolicy.isCurrent(ticket)

    fun disable() {
        settings.enabled = false
        turnPolicy.disable()
        judging = null
        publish(StewardStatus.Stopped)
    }

    fun setMaxLoops(value: Int) {
        settings.maxLoops = value
        publish(_state.value.status)
    }

    fun setUnlimited(value: Boolean) {
        settings.unlimited = value
        publish(_state.value.status)
    }

    /**
     * AI 空闲（本轮结束）时调用一次。
     *
     * @param lastReport 本轮 AI 的最后汇报文本。
     * @param send 把下一步指令送进同一会话的既有发送链路。
     * @return 是否已自动续发（true = 又开了一轮）。
     */
    suspend fun onAiIdle(ticket: StewardTurnPolicy.Ticket, lastReport: String, send: suspend (String) -> Unit): Boolean {
        if (!isCurrent(ticket) || judging != null) return false
        judging = ticket
        try {
            publish(StewardStatus.Judging)
            val judgement = judge.judge(turnPolicy.instruction, lastReport, ticket.sessionId)
            if (!isCurrent(ticket)) return false
            if (judgement == null) {
                publish(StewardStatus.Monitoring)
                return false
            }
            if (judgement.completed) {
                FileLogger.d(TAG, "任务判定为已完成：${judgement.reason}")
                publish(StewardStatus.Completed)
                return false
            }
            val next = judgement.nextInstruction?.trim().orEmpty()
            if (next.isEmpty()) {
                // 判了「没完成」却给不出下一步：没有可发的指令，停在这里，不进死循环。
                FileLogger.w(TAG, "判定未完成但没给下一步指令，停止接管")
                publish(StewardStatus.Stopped)
                return false
            }
            val limit = if (settings.unlimited) HARD_MAX_LOOPS else settings.maxLoops
            if (loopCount >= limit) {
                if (settings.unlimited) {
                    FileLogger.w(TAG, "自动循环已达安全阀 $HARD_MAX_LOOPS 轮，停止接管")
                }
                publish(StewardStatus.Stopped)
                return false
            }
            loopCount++
            publish(StewardStatus.AutoSending)
            send(next)
            publish(StewardStatus.Monitoring)
            return true
        } catch (e: CancellationException) {
            if (isCurrent(ticket)) publish(StewardStatus.Monitoring)
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "托管续发失败，跳过: ${e.message}", e)
            if (isCurrent(ticket)) publish(StewardStatus.Monitoring)
            return false
        } finally {
            if (judging === ticket) judging = null
        }
    }

    private fun publish(status: StewardStatus) {
        _state.value = StewardState(
            enabled = settings.enabled,
            checking = status == StewardStatus.Judging,
            loopCount = loopCount,
            status = status,
        )
    }

    companion object {
        private const val TAG = "StewardController"

        /** 安全阀：勾了「不限轮数」也最多自动续发这么多轮，避免空转烧额度。 */
        const val HARD_MAX_LOOPS = 50
    }
}