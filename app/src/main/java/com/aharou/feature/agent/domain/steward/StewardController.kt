package com.aharou.feature.agent.domain.steward

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管控制器：每轮 AI 回答结束（空闲）时旁路问一次「用户最初那句做完了没」，
 * 没做完就把下一步指令送回同一个会话。
 *
 * **按会话隔离**：开关、轮数、判定中标记、基准指令与轮次记账都挂在 sessionId 上，
 * A 会话开口托管、开判定、记账都不会碰到 B 会话。
 *
 * 只负责判定与轮次记账，发送动作由会话链路通过 [onAiIdle] 的 send 回调注入——
 * 控制器不反向依赖 ViewModel，也不碰界面。
 */
@Singleton
class StewardController @Inject constructor(
    private val settings: StewardSettingsStore,
    private val judge: StewardJudgePort,
) {
    /**
     * 单个会话的进程内运行态。
     *
     * 基准指令只活在内存里（重启后无从判断「最初那句」是什么），轮次记账同理；
     * 判定中标记每会话一个，多会话可以同时判定，互不互斥。
     */
    private class SessionRuntime {
        @Volatile
        var anchorInstruction: String = ""

        val loopCount = AtomicInteger(0)
        val judging = AtomicBoolean(false)
    }

    private val runtimes = ConcurrentHashMap<String, SessionRuntime>()

    private val _state = MutableStateFlow<Map<String, StewardState>>(emptyMap())

    /** 各会话的托管状态快照；没出现过的会话按未开启处理（见 [stateOf]）。 */
    val state: StateFlow<Map<String, StewardState>> = _state.asStateFlow()

    init {
        // 基准指令只活在进程内，重启后没有基准就无从判断——把所有会话的开关按实际能力收回来，
        // 免得界面显示「开着」却什么也不做。
        settings.clearAllEnabled()
    }

    /** 该会话的托管状态快照。 */
    fun stateOf(sessionId: String): StewardState = _state.value[sessionId] ?: StewardState()

    /** 该会话是否开着托管。 */
    fun isEnabled(sessionId: String): Boolean = settings.enabled(sessionId)

    /** 开托管：以 [anchorInstruction] 为该会话「做完没」的基准，轮次从零开始。 */
    fun enable(sessionId: String, anchorInstruction: String) {
        if (sessionId.isBlank()) return
        val runtime = runtime(sessionId)
        runtime.anchorInstruction = anchorInstruction
        runtime.loopCount.set(0)
        settings.setEnabled(sessionId, true)
        publish(sessionId, StewardStatus.Monitoring)
    }

    /** 关掉该会话的托管；别的会话不受影响。 */
    fun disable(sessionId: String) {
        settings.setEnabled(sessionId, false)
        // 判定中的那一轮由它自己的 finally 收尾，这里只把标记放开，免得关掉后再次开启要等一轮超时。
        runtimes[sessionId]?.judging?.set(false)
        publish(sessionId, StewardStatus.Stopped)
    }

    fun setMaxLoops(sessionId: String, value: Int) {
        settings.setMaxLoops(sessionId, value)
        publish(sessionId, stateOf(sessionId).status)
    }

    fun setUnlimited(sessionId: String, value: Boolean) {
        settings.setUnlimited(sessionId, value)
        publish(sessionId, stateOf(sessionId).status)
    }

    /**
     * 某会话的 AI 空闲（本轮结束）时调用一次。
     *
     * @param sessionId 哪个会话空了；只对它做判定与续发。
     * @param lastReport 本轮 AI 的最后汇报文本。
     * @param send 把下一步指令送进同一会话的既有发送链路。
     * @return 是否已自动续发（true = 又开了一轮）。
     */
    suspend fun onAiIdle(sessionId: String, lastReport: String, send: suspend (String) -> Unit): Boolean {
        if (sessionId.isBlank()) return false
        if (!settings.enabled(sessionId)) return false
        val runtime = runtime(sessionId)
        if (runtime.anchorInstruction.isBlank()) return false
        // 同一会话不重入；不同会话各有一把标记，可以同时判定。
        if (!runtime.judging.compareAndSet(false, true)) return false
        try {
            publish(sessionId, StewardStatus.Judging)
            val judgement = judge.judge(runtime.anchorInstruction, lastReport, sessionId)
            if (judgement == null) {
                publish(sessionId, StewardStatus.Monitoring)
                return false
            }
            if (judgement.completed) {
                FileLogger.d(TAG, "会话 $sessionId 任务判定为已完成：${judgement.reason}")
                publish(sessionId, StewardStatus.Completed)
                return false
            }
            val next = judgement.nextInstruction?.trim().orEmpty()
            if (next.isEmpty()) {
                // 判了「没完成」却给不出下一步：没有可发的指令，停在这里，不进死循环。
                FileLogger.w(TAG, "会话 $sessionId 判定未完成但没给下一步指令，停止接管")
                publish(sessionId, StewardStatus.Stopped)
                return false
            }
            val limit = if (settings.unlimited(sessionId)) HARD_MAX_LOOPS else settings.maxLoops(sessionId)
            if (runtime.loopCount.get() >= limit) {
                if (settings.unlimited(sessionId)) {
                    FileLogger.w(TAG, "会话 $sessionId 自动循环已达安全阀 $HARD_MAX_LOOPS 轮，停止接管")
                }
                publish(sessionId, StewardStatus.Stopped)
                return false
            }
            runtime.loopCount.incrementAndGet()
            publish(sessionId, StewardStatus.AutoSending)
            send(next)
            publish(sessionId, StewardStatus.Monitoring)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "会话 $sessionId 托管续发失败，跳过: ${e.message}", e)
            publish(sessionId, StewardStatus.Monitoring)
            return false
        } finally {
            runtime.judging.set(false)
        }
    }

    private fun runtime(sessionId: String): SessionRuntime =
        runtimes.computeIfAbsent(sessionId) { SessionRuntime() }

    /** 只替换该会话那一条，别的会话的状态原样保留。 */
    private fun publish(sessionId: String, status: StewardStatus) {
        val snapshot = StewardState(
            enabled = settings.enabled(sessionId),
            checking = status == StewardStatus.Judging,
            loopCount = runtimes[sessionId]?.loopCount?.get() ?: 0,
            status = status,
        )
        _state.update { current -> current + (sessionId to snapshot) }
    }

    companion object {
        private const val TAG = "StewardController"

        /** 安全阀：勾了「不限轮数」也最多自动续发这么多轮（每会话各自计数），避免空转烧额度。 */
        const val HARD_MAX_LOOPS = 50
    }
}
