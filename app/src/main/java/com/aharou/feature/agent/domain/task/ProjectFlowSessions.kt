package com.aharou.feature.agent.domain.task

import java.util.concurrent.ConcurrentHashMap

/** Project roots are orchestration records, not idle chat agents to wake on member notifications. */
object ProjectFlowSessions {
    private val roots = ConcurrentHashMap.newKeySet<String>()
    fun register(sessionId: String) { roots.add(sessionId) }
    fun owns(sessionId: String): Boolean = sessionId in roots
}
