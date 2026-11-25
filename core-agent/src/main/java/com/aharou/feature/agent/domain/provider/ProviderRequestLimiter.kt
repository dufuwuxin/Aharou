package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A process-wide per-provider request gate shared by every independently constructed adapter. */
@Singleton
class ProviderRequestLimiter @Inject constructor() {
    private class State {
        var configured = false
        var limit: Int = 0
        var active: Int = 0
        val waiters = ArrayDeque<Waiter>()

        fun release() {
            active -= 1
            drain()
        }

        fun drain() {
            while ((limit <= 0 || active < limit) && waiters.isNotEmpty()) {
                val waiter = waiters.removeFirst()
                val continuation = waiter.continuation
                if (!continuation.isActive) continue
                active += 1
                continuation.resume(Lease(this)) { _, lease, _ -> lease.release() }
            }
        }
    }

    private class Waiter(val continuation: CancellableContinuation<Lease>)

    private class Lease(private val state: State) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(state) { state.release() }
        }
    }

    private val states = ConcurrentHashMap<String, State>()

    fun initializeLimit(providerId: String, limit: Int) = updateLimit(providerId, limit, initialOnly = true)

    fun setLimit(providerId: String, limit: Int) = updateLimit(providerId, limit, initialOnly = false)

    private fun updateLimit(providerId: String, limit: Int, initialOnly: Boolean) {
        if (providerId.isBlank()) return
        val state = states.computeIfAbsent(providerId) { State() }
        synchronized(state) {
            if (initialOnly && state.configured) return
            state.configured = true
            state.limit = limit.coerceIn(0, com.aharou.feature.settings.domain.model.PROVIDER_MAX_CONCURRENT_REQUESTS)
            state.drain()
        }
    }

    suspend fun <T> withPermit(providerId: String, block: suspend () -> T): T {
        if (providerId.isBlank()) return block()
        return withLease(providerId, block)
    }

    private suspend fun <T> withLease(providerId: String, block: suspend () -> T): T {
        val lease = acquire(states.computeIfAbsent(providerId) { State() })
        try {
            return block()
        } finally {
            lease.release()
        }
    }

    fun wrap(providerId: String, limit: Int, delegate: AIProvider): AIProvider {
        if (providerId.isBlank()) return delegate
        initializeLimit(providerId, limit)
        return object : AIProvider by delegate {
            override suspend fun complete(
                systemPrompt: String,
                messages: List<AgentMessage>,
                tools: List<AgentTool>,
                reasoningEffort: String?
            ): AIResponse = withPermit(providerId) {
                delegate.complete(systemPrompt, messages, tools, reasoningEffort)
            }

            override fun completeStream(
                systemPrompt: String,
                messages: List<AgentMessage>,
                tools: List<AgentTool>,
                reasoningEffort: String?
            ): Flow<AIStreamChunk> = flow {
                withPermit(providerId) {
                    emitAll(delegate.completeStream(systemPrompt, messages, tools, reasoningEffort))
                }
            }
        }
    }

    private suspend fun acquire(state: State): Lease = suspendCancellableCoroutine { continuation ->
        val waiter = Waiter(continuation)
        continuation.invokeOnCancellation {
            synchronized(state) {
                state.waiters.remove(waiter)
                state.drain()
            }
        }
        synchronized(state) {
            state.waiters.addLast(waiter)
            state.drain()
        }
    }

}
