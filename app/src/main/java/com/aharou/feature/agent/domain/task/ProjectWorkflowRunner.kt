package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.subagent.TaskTool
import com.aharou.feature.agent.presentation.MessageRole
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class ProjectWorkflowRunner @Inject constructor(
    private val repository: ProjectTaskRepository,
    private val sessions: SessionUseCase,
    private val turns: AgentTurnRunner,
    private val registry: Provider<ToolRegistry>,
    private val events: SubAgentEventBus,
    private val definitions: AgentDefinitionRepository,
    private val messages: MessagePersistenceUseCase,
    private val coordinator: ProjectTaskExecutionCoordinator
) {
    private val tasks: TaskTool get() = registry.get().getTool("task") as TaskTool
    internal var memberTimeoutMs = 30L * 60L * 1000L

    suspend fun members(): List<String> = withContext(Dispatchers.IO) { definitions.listEnabled().map { it.definition.name } }

    suspend fun run(projectId: String, workspace: String, onlyTaskId: String? = null) = withContext(Dispatchers.IO) { coordinator.withWorkspace(workspace) {
        val project = repository.load(workspace).projects.first { it.id == projectId }
        onlyTaskId?.let { id -> ProjectWorkflowPlan.runnable(repository.load(workspace).tasks.filter { it.projectId == projectId }, id) }
        val parent = turns.createSessionEntity(workspace).copy(title = project.title.take(SessionUseCase.TITLE_MAX))
        sessions.upsertSession(parent)
        repository.linkProjectExecution(projectId, workspace, parent.id)
        ProjectFlowSessions.register(parent.id)
        val context = AgentContext(null, null, workspace, null, sessionId = parent.id)
        messages.persist(parent.id, MessageRole.USER, project.requirements)
        var active: ProjectTask? = null
        var child: String? = null
        var rerunOnly = false
        try {
            while (true) {
                val snapshot = repository.load(workspace)
                val currentProject = snapshot.projects.first { it.id == projectId }
                val projectTasks = snapshot.tasks.filter { it.projectId == projectId }
                // A superseded single-task run reruns only while its own predecessors stay delivered (a new branch is not).
                if (rerunOnly && onlyTaskId != null && projectTasks.firstOrNull { it.id == onlyTaskId }
                        ?.let { ProjectWorkflowPlan.dependenciesDelivered(it, projectTasks) } != true) break
                rerunOnly = false
                val next = if (onlyTaskId == null) ProjectWorkflowPlan.next(projectTasks) ?: break
                    else ProjectWorkflowPlan.runnable(projectTasks, onlyTaskId)
                val participants = ProjectWorkflowPlan.participants(next)
                val reused = ProjectWorkflowPlan.reusableRuns(next)
                active = repository.save(next.copy(status = ProjectTaskStatus.RUNNING, executionReason = "",
                    processRequirementsVersion = null, processParticipants = participants))
                val outputs = mutableListOf<String>()
                try {
                    // Serial execution shares the existing global capacity and never drops duplicate roles.
                    for ((index, member) in participants.withIndex()) {
                        requireCurrent(workspace, next, participants)
                        val reusedId = reused.getOrNull(index)
                        if (reusedId != null) {
                            outputs += memberOutput(index, member, reusedId)
                            continue
                        }
                        while (child == null) {
                            while (events.isFull) delay(100)
                            requireCurrent(workspace, next, participants)
                            // Cancellation must not strand a created child between creation and durable linking.
                            withContext(NonCancellable) {
                                val args = buildJsonObject {
                                    put("action", "create"); put("description", next.title)
                                    put("prompt", ProjectWorkflowPlan.prompt(next, projectTasks, currentProject.requirements, member, index + 1, outputs.toList()))
                                    if (member.isNotBlank()) put("agent", member)
                                }
                                val result = tasks.executeWithContext(args, context)
                                if (result is ToolResult.Error && result.code == "MAX_SUBAGENTS_REACHED") return@withContext
                                check(result is ToolResult.Success) { (result as? ToolResult.Error)?.message ?: "PROJECT_MEMBER_START_FAILED" }
                                val id = result.data.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("PROJECT_MEMBER_ID_MISSING")
                                child = id
                                val linked = repository.load(workspace).tasks.first { it.id == next.id }
                                repository.save(linked.copy(processSessionIds = linked.processSessionIds + id,
                                    processMembers = linked.processMembers + (id to ProjectTaskMemberRun(member, index + 1, next.requirementsVersion))))
                            }
                            if (child == null) delay(100)
                        }
                        val id = requireNotNull(child)
                        // Waiting for a free member slot above is queueing, not execution time. A branch or supplement that
                        // changes this task while the member runs supersedes the member instead of ending the flow.
                        withTimeout(memberTimeoutMs) { while (events.terminalEvent(id) == null) { requireCurrent(workspace, next, participants); delay(100) } }
                        requireCurrent(workspace, next, participants)
                        val terminal = requireNotNull(events.terminalEvent(id))
                        val passed = terminal.type == SubAgentEventType.COMPLETED && terminal.accepted == true
                        val outcome = if (passed) "SUCCEEDED" else if (terminal.type == SubAgentEventType.STOPPED) "STOPPED" else "FAILED"
                        val latest = repository.load(workspace).tasks.first { it.id == next.id }
                        repository.save(latest.copy(processMembers = latest.processMembers +
                            (id to latest.processMembers.getValue(id).copy(outcome = outcome, reason = terminal.detail))))
                        child = null
                        check(passed) { if (outcome == "STOPPED") "PROJECT_MEMBER_STOPPED" else "PROJECT_MEMBER_FAILED" }
                        outputs += memberOutput(index, member, id)
                    }
                    requireCurrent(workspace, next, participants)
                } catch (_: ProjectTaskSuperseded) {
                    withContext(NonCancellable) { supersede(workspace, next.id, child, context) }
                    active = null; child = null; rerunOnly = true
                    continue
                }
                val latest = repository.load(workspace).tasks.first { it.id == next.id }
                repository.save(latest.copy(status = ProjectTaskStatus.REVIEW, processRequirementsVersion = next.requirementsVersion,
                    brief = outputs.joinToString("\n\n"), executionReason = "SUCCEEDED"))
                active = null
                if (onlyTaskId != null) break
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                val reason = when {
                    failure is TimeoutCancellationException -> "TIMEOUT"
                    failure is CancellationException || failure.message == "PROJECT_MEMBER_STOPPED" -> "STOPPED"
                    failure.message == "UPSTREAM_CHANGED" -> "UPSTREAM_CHANGED"
                    else -> "FAILED"
                }
                child?.let { id ->
                    try {
                        val stopped = tasks.executeWithContext(buildJsonObject { put("action", "stop"); put("id", id) }, context)
                        if (stopped is ToolResult.Error) failure.addSuppressed(IllegalStateException(stopped.message))
                    } catch (stopFailure: Exception) { failure.addSuppressed(stopFailure) }
                }
                active?.let { task ->
                    val latest = repository.load(workspace).tasks.first { it.id == task.id }
                    val records = latest.processMembers.toMutableMap()
                    child?.let { id -> records[id]?.let { records[id] = it.copy(outcome = reason, reason = failure.message.orEmpty()) } }
                    repository.save(latest.copy(status = if (reason == "UPSTREAM_CHANGED") ProjectTaskStatus.READY else ProjectTaskStatus.BLOCKED,
                        processRequirementsVersion = null, processMembers = records, executionReason = reason))
                }
            }
            throw failure
        }
    } }

    /** Requirements or participants changed since this attempt started: the attempt is superseded, not failed. */
    private suspend fun requireCurrent(workspace: String, started: ProjectTask, participants: List<String>) {
        val latest = repository.load(workspace).tasks.firstOrNull { it.id == started.id } ?: return
        if (latest.requirementsVersion != started.requirementsVersion || ProjectWorkflowPlan.participants(latest) != participants)
            throw ProjectTaskSuperseded()
    }

    /** Same stop path as a failed attempt, but the member record is SUPERSEDED and the task returns to the queue. */
    private suspend fun supersede(workspace: String, taskId: String, child: String?, context: AgentContext) {
        child?.let { id ->
            try { tasks.executeWithContext(buildJsonObject { put("action", "stop"); put("id", id) }, context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { } // A member that already ended needs no stop; its record is still superseded below.
        }
        val latest = repository.load(workspace).tasks.first { it.id == taskId }
        val records = latest.processMembers.toMutableMap()
        child?.let { id -> records[id]?.let { records[id] = it.copy(outcome = "SUPERSEDED", reason = "UPSTREAM_CHANGED") } }
        repository.save(latest.copy(status = ProjectTaskStatus.READY, processRequirementsVersion = null,
            processMembers = records, executionReason = "UPSTREAM_CHANGED"))
    }

    private suspend fun memberOutput(index: Int, member: String, sessionId: String): String {
        val output = messages.restoreAll(messages.getMessagesOnce(sessionId)).lastOrNull { it.role == MessageRole.ASSISTANT.name }?.content.orEmpty()
        return "[${index + 1}: ${member.ifBlank { "default member" }}]\n$output"
    }
}

private class ProjectTaskSuperseded : IllegalStateException("UPSTREAM_CHANGED")
