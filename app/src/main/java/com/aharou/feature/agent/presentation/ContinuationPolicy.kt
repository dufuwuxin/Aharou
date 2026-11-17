package com.aharou.feature.agent.presentation

internal class ContinuationGate {
    private val active = mutableSetOf<String>()

    suspend fun run(sessionId: String, target: String, current: () -> String?, busy: () -> Boolean,
                    latest: suspend () -> String?, execute: suspend () -> Unit): Boolean {
        if (sessionId != current() || busy() || !active.add(sessionId)) return false
        try {
            val last = latest()
            if (!continuationAllowed(sessionId, current(), target, last, busy())) return false
            execute()
            return true
        } finally { active.remove(sessionId) }
    }
}

internal fun continuationAllowed(sessionId: String?, currentSessionId: String?, target: String,
                                 latest: String?, busy: Boolean): Boolean =
    !sessionId.isNullOrBlank() && sessionId == currentSessionId && target == latest && !busy

internal fun latestContinuationMessageId(messages: List<AgentUIMessage>): String? =
    messages.lastOrNull {
        (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) &&
            !it.isCompactionMarker && !it.isContextSummary && !it.isBackgroundNotification &&
            !it.isCompactionFailure && !it.isSamplingLoopStop
    }?.id

internal fun showContinuationAction(state: ChatMessagesState, sessionId: String?, target: String, busy: Boolean): Boolean =
    state.loaded && continuationAllowed(state.sessionId, sessionId, target, latestContinuationMessageId(state.messages), busy)
