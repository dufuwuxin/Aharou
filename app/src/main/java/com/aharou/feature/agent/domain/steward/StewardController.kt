package com.aharou.feature.agent.domain.steward

import com.aharou.core.util.FileLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Per-session supervision; each runtime owns its revision, anchor, judging ticket and loop count. */
@Singleton
class StewardController @Inject constructor(
    private val settings: StewardSettingsStore,
    private val judge: StewardJudgePort,
) {
    private class SessionRuntime {
        val policy = StewardTurnPolicy()
        var loopCount = 0
        var judging: StewardTurnPolicy.Ticket? = null
    }

    private val runtimes = ConcurrentHashMap<String, SessionRuntime>()
    private val _state = MutableStateFlow<Map<String, StewardState>>(emptyMap())
    val state: StateFlow<Map<String, StewardState>> = _state.asStateFlow()

    init {
        settings.clearAllEnabled()
    }

    fun stateOf(sessionId: String): StewardState = _state.value[sessionId] ?: StewardState()
    fun isEnabled(sessionId: String): Boolean = settings.enabled(sessionId)

    fun enable(sessionId: String, anchorInstruction: String): Long {
        require(sessionId.isNotBlank())
        val runtime = runtimes.computeIfAbsent(sessionId) { SessionRuntime() }
        return synchronized(runtime) {
            val revision = runtime.policy.enable(anchorInstruction, sessionId)
            runtime.judging = null
            runtime.loopCount = 0
            settings.setEnabled(sessionId, true)
            publish(sessionId, runtime, if (anchorInstruction.isBlank()) StewardStatus.Idle else StewardStatus.Monitoring)
            revision
        }
    }

    fun bindAnchor(sessionId: String, instruction: String, revision: Long? = null) {
        val runtime = runtimes[sessionId] ?: return
        synchronized(runtime) {
            if (revision == null) runtime.policy.bindAnchor(sessionId, instruction)
            else runtime.policy.bindAnchor(sessionId, instruction, revision)
            if (isEnabled(sessionId) && runtime.policy.instruction.isNotBlank() && stateOf(sessionId).status == StewardStatus.Idle) {
                publish(sessionId, runtime, StewardStatus.Monitoring)
            }
        }
    }

    fun currentTurn(sessionId: String): StewardTurnPolicy.Ticket? {
        val runtime = runtimes[sessionId] ?: return null
        return synchronized(runtime) { if (isEnabled(sessionId)) runtime.policy.ticket(sessionId) else null }
    }

    fun isCurrent(ticket: StewardTurnPolicy.Ticket): Boolean {
        val runtime = runtimes[ticket.sessionId] ?: return false
        return synchronized(runtime) { isEnabled(ticket.sessionId) && runtime.policy.isCurrent(ticket) }
    }

    fun disable(sessionId: String) {
        val runtime = runtimes.computeIfAbsent(sessionId) { SessionRuntime() }
        synchronized(runtime) {
            settings.setEnabled(sessionId, false)
            runtime.policy.disable()
            runtime.judging = null
            publish(sessionId, runtime, StewardStatus.Stopped)
        }
    }

    fun setMaxLoops(sessionId: String, value: Int) {
        val runtime = runtimes.computeIfAbsent(sessionId) { SessionRuntime() }
        synchronized(runtime) {
            settings.setMaxLoops(sessionId, value)
            publish(sessionId, runtime, stateOf(sessionId).status)
        }
    }

    fun setUnlimited(sessionId: String, value: Boolean) {
        val runtime = runtimes.computeIfAbsent(sessionId) { SessionRuntime() }
        synchronized(runtime) {
            settings.setUnlimited(sessionId, value)
            publish(sessionId, runtime, stateOf(sessionId).status)
        }
    }

    suspend fun onAiIdle(sessionId: String, lastReport: String, send: suspend (String) -> Unit): Boolean =
        currentTurn(sessionId)?.let { onAiIdle(it, lastReport, send) } ?: false

    suspend fun onAiIdle(ticket: StewardTurnPolicy.Ticket, lastReport: String, send: suspend (String) -> Unit): Boolean {
        val sessionId = ticket.sessionId
        val runtime = runtimes[sessionId] ?: return false
        val anchor = synchronized(runtime) {
            if (!isCurrent(ticket) || runtime.judging != null) return false
            runtime.judging = ticket
            publish(sessionId, runtime, StewardStatus.Judging)
            runtime.policy.instruction
        }
        try {
            val judgement = judge.judge(anchor, lastReport, sessionId)
            val next = synchronized(runtime) {
                if (!isCurrent(ticket)) return false
                if (judgement == null) {
                    publish(sessionId, runtime, StewardStatus.Monitoring)
                    return false
                }
                if (judgement.completed) {
                    publish(sessionId, runtime, StewardStatus.Completed)
                    return false
                }
                val instruction = judgement.nextInstruction?.trim().orEmpty()
                val limit = if (settings.unlimited(sessionId)) HARD_MAX_LOOPS else settings.maxLoops(sessionId)
                if (instruction.isEmpty() || runtime.loopCount >= limit) {
                    publish(sessionId, runtime, StewardStatus.Stopped)
                    return false
                }
                runtime.loopCount++
                publish(sessionId, runtime, StewardStatus.AutoSending)
                instruction
            }
            if (!isCurrent(ticket)) return false
            send(next)
            synchronized(runtime) {
                if (isCurrent(ticket)) publish(sessionId, runtime, StewardStatus.Monitoring)
            }
            return true
        } catch (e: CancellationException) {
            synchronized(runtime) {
                if (isCurrent(ticket)) publish(sessionId, runtime, StewardStatus.Monitoring)
            }
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "Steward continuation failed for $sessionId", e)
            synchronized(runtime) {
                if (isCurrent(ticket)) publish(sessionId, runtime, StewardStatus.Monitoring)
            }
            return false
        } finally {
            synchronized(runtime) {
                // A stale finally must not release a newer judgement for this session.
                if (runtime.judging === ticket) runtime.judging = null
            }
        }
    }

    private fun publish(sessionId: String, runtime: SessionRuntime, status: StewardStatus) {
        val snapshot = StewardState(
            enabled = isEnabled(sessionId),
            checking = status == StewardStatus.Judging,
            loopCount = runtime.loopCount,
            status = status,
        )
        _state.update { it + (sessionId to snapshot) }
    }

    companion object {
        private const val TAG = "StewardController"
        const val HARD_MAX_LOOPS = 50
    }
}
