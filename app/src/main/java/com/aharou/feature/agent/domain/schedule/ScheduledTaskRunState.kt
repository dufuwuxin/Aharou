package com.aharou.feature.agent.domain.schedule

import com.aharou.feature.agent.domain.workflow.AgentEvent

/** Owned by the service main dispatcher; the last delivery may finish first. */
internal class ScheduledTaskRunState {
    private val active = mutableSetOf<Int>()
    private var latestStartId = 0
    val isIdle: Boolean get() = active.isEmpty()
    fun started(startId: Int) {
        latestStartId = startId
        active += startId
    }
    fun finished(startId: Int): Int? {
        if (!active.remove(startId)) return null
        return latestStartId.takeIf { active.isEmpty() }
    }
}

internal class ScheduledTaskTerminalState {
    private var completed = false
    private var failure = false
    fun accept(event: AgentEvent) {
        when (event) {
            AgentEvent.Completed -> completed = true
            is AgentEvent.Failed -> failure = true
            else -> Unit
        }
    }
    fun failed() { failure = true }
    val outcome: String
        get() = if (completed && !failure) ScheduledTaskOutcome.COMPLETED else ScheduledTaskOutcome.FAILED
}
