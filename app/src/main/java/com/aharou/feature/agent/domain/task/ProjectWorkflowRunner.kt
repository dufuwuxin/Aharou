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
import com.aharou.feature.agent.domain.tool.subagent.TaskTool
import com.aharou.feature.agent.presentation.MessageRole
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class ProjectWorkflowRunner @Inject constructor(
    private val repository: ProjectTaskRepository,
    private val sessions: SessionUseCase,
    private val turns: AgentTurnRunner,
    private val taskTool: Provider<TaskTool>,
    private val events: SubAgentEventBus,
    private val definitions: AgentDefinitionRepository,
    private val messages: MessagePersistenceUseCase,
    private val coordinator: ProjectTaskExecutionCoordinator
) {
    private val tasks: TaskTool get() = taskTool.get()
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
        val horizon = project.horizon
        val goal = project.overallGoal.ifBlank { project.purpose.ifBlank { project.requirements } }
        val acceptance = project.goalAcceptance
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var attempts = 0
        suspend fun checkpoint(reason: String) {
            repository.checkpointProjectGoal(project, reason)
            messages.persist(parent.id, MessageRole.ASSISTANT, "项目阶段已暂停：${ProjectGoalPlan.label(reason)}。请核对总目标与验收标准，确认后补充下一阶段需求；本次回复不代表项目完成。")
        }
        var active: ProjectTask? = null
        var child: String? = null
        try {
            while (true) {
                val snapshot = repository.load(workspace)
                val currentProject = snapshot.projects.first { it.id == projectId }
                val projectContext = ProjectGoalPlan.context(currentProject.overallGoal, currentProject.goalAcceptance, horizon)
                val projectTasks = snapshot.tasks.filter { it.projectId == projectId }
                currentCoroutineContext().ensureActive()
                if (attempts >= ProjectGoalPlan.taskBudget(horizon) ||
                    android.os.SystemClock.elapsedRealtime() - startedAt >= ProjectGoalPlan.timeBudgetMs(horizon)) {
                    checkpoint(ProjectGoalPlan.checkpoint(goal, acceptance, projectTasks, budgetReached = true))
                    break
                }
                val teamTasks = projectTasks.filter { !it.isRequirementBranch && ProjectTeamExecution.usesTeam(currentProject, it) }
                val selection = if (teamTasks.isEmpty()) null else {
                    try { ProjectTeamExecution.selection(tasks.selectProjectTeam(currentProject.executionTeam, goal, context)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        repository.invalidateTeamTasks(workspace, snapshot.version, teamTasks.map { it.id }.toSet(), true, "PROJECT_TEAM_UNAVAILABLE")
                        throw failure
                    }
                }
                if (selection != null && teamTasks.any { (ProjectWorkflowPlan.delivered(it) || it.processParticipants != null || it.processMembers.isNotEmpty()) && it.processParticipants != listOf(selection.participant) }) {
                    repository.invalidateTeamTasks(workspace, snapshot.version, teamTasks.filter {
                        (ProjectWorkflowPlan.delivered(it) || it.processParticipants != null || it.processMembers.isNotEmpty()) &&
                            it.processParticipants != listOf(selection.participant)
                    }.map { it.id }.toSet(), false, "TEAM_CONFIG_CHANGED")
                    continue
                }
                // A single-task run first runs the member branches its task now waits on, then the task itself.
                val next = if (onlyTaskId == null) ProjectWorkflowPlan.next(projectTasks)
                    else ProjectWorkflowPlan.nextWithin(projectTasks, onlyTaskId)
                if (next == null) {
                    val proposal = if (onlyTaskId == null && horizon != "short" && goal.isNotBlank() && acceptance.isNotBlank()) {
                        val remaining = ProjectGoalPlan.timeBudgetMs(horizon) - (android.os.SystemClock.elapsedRealtime() - startedAt)
                        try { withTimeout(minOf(120_000L, remaining).coerceAtLeast(1L)) { repository.proposeGoalStage(currentProject) } }
                        catch (timeout: TimeoutCancellationException) { false }
                    } else false
                    checkpoint(if (proposal) "NEXT_STAGE_CONFIRMATION_REQUIRED" else ProjectGoalPlan.checkpoint(goal, acceptance, projectTasks))
                    break
                }
                attempts++
                val selectedTeam = selection?.takeIf { ProjectTeamExecution.usesTeam(currentProject, next) }
                val participants = selectedTeam?.let { listOf(it.participant) } ?: ProjectWorkflowPlan.participants(next)
                val reused = ProjectWorkflowPlan.reusableRuns(next)
                // Marked under the store lock; a supplement or branch committed since the pick sends the loop back to pick again.
                active = repository.transact(workspace) { store ->
                    val latest = store.tasks.first { it.id == next.id }
                    if (latest.requirementsVersion != next.requirementsVersion || ProjectWorkflowPlan.participants(latest) != ProjectWorkflowPlan.participants(next)) emptyList()
                    else listOf(ProjectTaskChange(latest.copy(status = ProjectTaskStatus.RUNNING, executionReason = "",
                        processRequirementsVersion = null, processParticipants = participants,
                        runStartedAtEpochMs = System.currentTimeMillis())))
                }.singleOrNull() ?: continue
                val outputs = mutableListOf<String>()
                var teamEvidence: String? = null
                try {
                    if (selectedTeam != null) {
                        val remaining = (ProjectGoalPlan.timeBudgetMs(horizon) - (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(1L)
                        val spawned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                        try {
                            val result = ProjectTeamExecution.execute(minOf(memberTimeoutMs, remaining),
                                current = { requireCurrent(workspace, next, participants) },
                                invoke = { tasks.runProjectTeam(selectedTeam.name,
                                    ProjectWorkflowPlan.prompt(next, projectTasks, requirements = "", projectContext = projectContext),
                                    context, selectedTeam.signature, onCreated = { id, role ->
                                        repository.transact(workspace) { store ->
                                            val latest = store.tasks.firstOrNull { it.id == next.id } ?: throw ProjectTaskSuperseded()
                                            if (latest.requirementsVersion != next.requirementsVersion || !participantsCurrent(latest, participants))
                                                throw ProjectTaskSuperseded()
                                            listOf(ProjectTaskChange(latest.copy(processSessionIds = latest.processSessionIds + id,
                                                processMembers = latest.processMembers + (id to ProjectTaskMemberRun(role,
                                                    latest.processMembers.values.count { it.requirementsVersion == next.requirementsVersion } + 1,
                                                    next.requirementsVersion)))))
                                        }
                                        spawned += id
                                    }) })
                            val fresh = ProjectTeamExecution.selection(tasks.selectProjectTeam(currentProject.executionTeam, goal, context))
                            if (fresh.participant != selectedTeam.participant) throw ProjectTaskSuperseded()
                            val (evidence, output) = ProjectTeamExecution.receipt(result, selectedTeam, next.requirementsVersion)
                            teamEvidence = evidence
                            outputs += output
                        } finally {
                            val cancelled = !currentCoroutineContext().isActive
                            withContext(NonCancellable) {
                                repository.transact(workspace) { store ->
                                    val latest = store.tasks.firstOrNull { it.id == next.id } ?: return@transact emptyList()
                                    val superseded = latest.requirementsVersion != next.requirementsVersion || !participantsCurrent(latest, participants)
                                    val records = latest.processMembers.toMutableMap()
                                    for (id in spawned) {
                                        val record = records[id]?.takeIf { it.requirementsVersion == next.requirementsVersion } ?: continue
                                        val terminal = events.terminalEvent(id)
                                        val outcome = when {
                                            superseded -> "SUPERSEDED"
                                            terminal?.type == SubAgentEventType.COMPLETED && terminal.accepted == true -> "SUCCEEDED"
                                            terminal == null && cancelled -> "STOPPED"
                                            terminal == null -> "UNKNOWN"
                                            terminal.type == SubAgentEventType.STOPPED -> "STOPPED"
                                            else -> "FAILED"
                                        }
                                        records[id] = record.copy(outcome = outcome, reason = if (superseded) "UPSTREAM_CHANGED" else terminal?.detail.orEmpty())
                                    }
                                    if (records == latest.processMembers) emptyList()
                                    else listOf(ProjectTaskChange(latest.copy(processMembers = records)))
                                }
                            }
                        }
                    } else {
                    // Serial execution shares the existing global capacity and never drops duplicate roles.
                    for ((index, member) in participants.withIndex()) {
                        requireCurrent(workspace, next, participants)
                        val reusedId = reused.getOrNull(index)
                        if (reusedId != null) {
                            outputs += memberOutput(index, member, reusedId)
                            continue
                        }
                        val projectTools = projectToolsFor(member)
                        while (child == null) {
                            currentCoroutineContext().ensureActive()
                            check(android.os.SystemClock.elapsedRealtime() - startedAt < ProjectGoalPlan.timeBudgetMs(horizon)) { "PROJECT_BUDGET_REACHED" }
                            while (events.isFull) {
                                check(android.os.SystemClock.elapsedRealtime() - startedAt < ProjectGoalPlan.timeBudgetMs(horizon)) { "PROJECT_BUDGET_REACHED" }
                                delay(100)
                            }
                            requireCurrent(workspace, next, participants)
                            // Cancellation must not strand a created child between creation and durable linking.
                            withContext(NonCancellable) {
                                val result = tasks.createProjectMember(next.title,
                                    ProjectWorkflowPlan.prompt(next, projectTasks, "", member, index + 1, outputs.toList(), projectTools, projectContext),
                                    member.takeIf { it.isNotBlank() }, context)
                                if (result is ToolResult.Error && result.code == "MAX_SUBAGENTS_REACHED") return@withContext
                                check(result is ToolResult.Success) { (result as? ToolResult.Error)?.message ?: "PROJECT_MEMBER_START_FAILED" }
                                val id = result.data.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("PROJECT_MEMBER_ID_MISSING")
                                child = id
                                repository.transact(workspace) { store ->
                                    val linked = store.tasks.first { it.id == next.id }
                                    listOf(ProjectTaskChange(linked.copy(processSessionIds = linked.processSessionIds + id,
                                        processMembers = linked.processMembers + (id to ProjectTaskMemberRun(member, index + 1, next.requirementsVersion)))))
                                }
                            }
                            if (child == null) delay(100)
                        }
                        val id = requireNotNull(child)
                        // Waiting for a free member slot above is queueing, not execution time. A branch or supplement that
                        // changes this task while the member runs supersedes the member instead of ending the flow.
                        withTimeout(minOf(memberTimeoutMs, (ProjectGoalPlan.timeBudgetMs(horizon) -
                            (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(1L))) { while (events.terminalEvent(id) == null) { requireCurrent(workspace, next, participants); delay(100) } }
                        requireCurrent(workspace, next, participants)
                        val terminal = requireNotNull(events.terminalEvent(id))
                        val passed = terminal.type == SubAgentEventType.COMPLETED && terminal.accepted == true
                        val outcome = if (passed) "SUCCEEDED" else if (terminal.type == SubAgentEventType.STOPPED) "STOPPED" else "FAILED"
                        repository.transact(workspace) { store ->
                            val latest = store.tasks.first { it.id == next.id }
                            listOf(ProjectTaskChange(latest.copy(processMembers = latest.processMembers +
                                (id to latest.processMembers.getValue(id).copy(outcome = outcome, reason = terminal.detail)))))
                        }
                        child = null
                        check(passed) { if (outcome == "STOPPED") "PROJECT_MEMBER_STOPPED" else "PROJECT_MEMBER_FAILED" }
                        outputs += memberOutput(index, member, id)
                    }
                    }
                    requireCurrent(workspace, next, participants)
                } catch (_: ProjectTaskSuperseded) {
                    withContext(NonCancellable) { supersede(workspace, next, child, context) }
                    active = null; child = null
                    continue
                }
                // Compared and saved under the store lock: a supplement or branch committed after the last check is never overwritten.
                var superseded = false
                repository.transact(workspace) { store ->
                    val latest = store.tasks.firstOrNull { it.id == next.id }
                        ?: run { superseded = true; return@transact emptyList() }
                    superseded = latest.requirementsVersion != next.requirementsVersion || !participantsCurrent(latest, participants)
                    listOf(ProjectTaskChange(if (superseded) latest.copy(status = ProjectTaskStatus.READY, processRequirementsVersion = null,
                        executionReason = "UPSTREAM_CHANGED") else latest.copy(status = ProjectTaskStatus.REVIEW,
                        processRequirementsVersion = next.requirementsVersion, brief = outputs.joinToString("\n\n"),
                        evidenceRefs = latest.evidenceRefs + listOfNotNull(teamEvidence), executionReason = "SUCCEEDED",
                        lastRunDurationMs = latest.runStartedAtEpochMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
                        runStartedAtEpochMs = null)))
                }
                active = null
                if (superseded) continue
                if (onlyTaskId != null && next.id == onlyTaskId) break
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                val reason = when {
                    failure is TimeoutCancellationException -> "TIMEOUT"
                    failure is CancellationException || failure.message == "PROJECT_MEMBER_STOPPED" -> "STOPPED"
                    failure.message == "UPSTREAM_CHANGED" -> "UPSTREAM_CHANGED"
                    else -> "FAILED"
                }
                if (failure.message == "PROJECT_BUDGET_REACHED" || failure is TimeoutCancellationException) {
                    try { checkpoint(if (android.os.SystemClock.elapsedRealtime() - startedAt >= ProjectGoalPlan.timeBudgetMs(horizon) ||
                        failure.message == "PROJECT_BUDGET_REACHED") "BUDGET_REACHED" else "MEMBER_TIMEOUT") } catch (saveFailure: Exception) { failure.addSuppressed(saveFailure) }
                }
                child?.let { id ->
                    try {
                        val stopped = tasks.stopProjectMember(id, context)
                        if (stopped is ToolResult.Error) failure.addSuppressed(IllegalStateException(stopped.message))
                    } catch (stopFailure: Exception) { failure.addSuppressed(stopFailure) }
                }
                active?.let { task ->
                    repository.transact(workspace) { store ->
                        val latest = store.tasks.firstOrNull { it.id == task.id } ?: return@transact emptyList()
                        if (latest.requirementsVersion != task.requirementsVersion || !participantsCurrent(latest, task.processParticipants.orEmpty()))
                            return@transact emptyList()
                        val records = latest.processMembers.toMutableMap()
                        child?.let { id -> records[id]?.let { records[id] = it.copy(outcome = reason, reason = failure.message.orEmpty()) } }
                        listOf(ProjectTaskChange(latest.copy(status = if (reason == "UPSTREAM_CHANGED") ProjectTaskStatus.READY else ProjectTaskStatus.BLOCKED,
                            processRequirementsVersion = null, processMembers = records, executionReason = reason,
                            lastRunDurationMs = latest.runStartedAtEpochMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
                            runStartedAtEpochMs = null)))
                    }
                }
            }
            throw failure
        }
    } }

    /** Requirements or participants changed since this attempt started: the attempt is superseded, not failed. */
    private suspend fun requireCurrent(workspace: String, started: ProjectTask, participants: List<String>) {
        val latest = repository.load(workspace).tasks.firstOrNull { it.id == started.id } ?: throw ProjectTaskSuperseded()
        if (latest.requirementsVersion != started.requirementsVersion || !participantsCurrent(latest, participants))
            throw ProjectTaskSuperseded()
    }

    private fun participantsCurrent(task: ProjectTask, expected: List<String>): Boolean =
        if (expected.singleOrNull()?.startsWith("team:") == true) task.processParticipants == expected
        else ProjectWorkflowPlan.participants(task) == expected

    /** The member prompt mentions project_tasks only when the role's tool filter keeps it; the default member keeps every tool. */
    internal suspend fun projectToolsFor(member: String): Boolean {
        if (member.isBlank()) return true
        return try {
            withContext(Dispatchers.IO) { definitions.find(member) }
                ?.filterToolNames(listOf(ProjectWorkflowPlan.PROJECT_TASKS_TOOL))?.isNotEmpty() == true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    }

    /** Same stop path as a failed attempt, but the member record is SUPERSEDED and the task returns to the queue. */
    private suspend fun supersede(workspace: String, started: ProjectTask, child: String?, context: AgentContext) {
        child?.let { id ->
            try { tasks.stopProjectMember(id, context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { } // A member that already ended needs no stop; its record is still superseded below.
        }
        repository.transact(workspace) { store ->
            val latest = store.tasks.firstOrNull { it.id == started.id } ?: return@transact emptyList()
            val stillCurrent = latest.requirementsVersion == started.requirementsVersion
            val records = latest.processMembers.toMutableMap()
            child?.let { id -> records[id]?.let { records[id] = it.copy(outcome = "SUPERSEDED", reason = "UPSTREAM_CHANGED") } }
            listOf(ProjectTaskChange(latest.copy(status = if (stillCurrent) ProjectTaskStatus.READY else latest.status,
                processRequirementsVersion = if (stillCurrent) null else latest.processRequirementsVersion,
                processMembers = records, executionReason = if (stillCurrent) "UPSTREAM_CHANGED" else latest.executionReason)))
        }
    }

    private suspend fun memberOutput(index: Int, member: String, sessionId: String): String {
        val output = messages.restoreAll(messages.getMessagesOnce(sessionId)).lastOrNull { it.role == MessageRole.ASSISTANT.name }?.content.orEmpty()
        return "[${index + 1}: ${member.ifBlank { "default member" }}]\n$output"
    }
}

private class ProjectTaskSuperseded : IllegalStateException("UPSTREAM_CHANGED")
