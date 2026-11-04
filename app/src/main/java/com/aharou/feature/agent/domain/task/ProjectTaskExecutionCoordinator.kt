package com.aharou.feature.agent.domain.task

import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProjectTaskExecutionOutcome { RUNNING, SUCCEEDED, STOPPED, FAILED }

/** Application-wide root-turn coordination; child teams must not reacquire their parent's mutex. */
@Singleton
class ProjectTaskExecutionCoordinator @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val active = mutableMapOf<String, String>()
    private val outcomes = mutableMapOf<Pair<String, String>, ProjectTaskExecutionOutcome>()

    fun executionWorkspace(requested: String, bound: String?, selected: String): String =
        requested.takeIf { it.isNotBlank() } ?: bound?.takeIf { it.isNotBlank() } ?: selected.ifBlank { "unbound-root-sessions" }
    fun workspaceKey(workspace: String): String = Paths.get(workspace).normalize().toString().trimEnd('/').ifBlank { "/" }
    suspend fun <T> withWorkspace(workspace: String, action: suspend () -> T): T {
        require(workspace.isNotBlank()) { "WORKSPACE_REQUIRED" }
        return locks.computeIfAbsent(workspaceKey(workspace)) { Mutex() }.withLock { action() }
    }
    @Synchronized fun begin(sessionId: String, turnId: String) {
        active[sessionId] = turnId
        outcomes[sessionId to turnId] = ProjectTaskExecutionOutcome.RUNNING
    }
    @Synchronized fun stop(sessionId: String) {
        val turn = active[sessionId] ?: return
        outcomes[sessionId to turn] = ProjectTaskExecutionOutcome.STOPPED
    }
    @Synchronized fun finish(sessionId: String, turnId: String, outcome: ProjectTaskExecutionOutcome) {
        val key = sessionId to turnId
        val old = outcomes[key]
        if (old == ProjectTaskExecutionOutcome.STOPPED || old == ProjectTaskExecutionOutcome.FAILED) return
        outcomes[key] = outcome
    }
    @Synchronized fun failIfRunning(sessionId: String, turnId: String) {
        if (outcomes[sessionId to turnId] == ProjectTaskExecutionOutcome.RUNNING)
            finish(sessionId, turnId, ProjectTaskExecutionOutcome.FAILED)
    }
    @Synchronized fun outcome(sessionId: String, turnId: String): ProjectTaskExecutionOutcome =
        outcomes[sessionId to turnId] ?: ProjectTaskExecutionOutcome.FAILED
}
