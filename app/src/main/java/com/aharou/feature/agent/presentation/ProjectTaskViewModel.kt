package com.aharou.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectTaskUiState(
    val workspace: String = "", val tasks: List<ProjectTask> = emptyList(), val selectedId: String? = null,
    val loading: Boolean = true, val error: String? = null, val busy: Boolean = false,
    val projects: List<ProjectPlan> = emptyList(), val selectedProjectId: String? = null,
    val projectSessionIds: Set<String> = emptySet(),
    val indexedSessionWorkspaces: Set<String> = emptySet(),
    val failedSessionWorkspaces: Set<String> = emptySet(),
    val appendSucceededRequest: String? = null,
    val activeTaskIds: Set<String> = emptySet(),
    val activeProjectIds: Set<String> = emptySet(),
    val availableMembers: List<String> = emptyList(),
    /** Projects whose tasks the planner is splitting or revising right now. */
    val planningProjectIds: Set<String> = emptySet(),
    /** Projects whose latest planning fell back to line rules because the model was unavailable or invalid. */
    val ruleFallbackProjectIds: Set<String> = emptySet()
)

@HiltViewModel
class ProjectTaskViewModel @Inject constructor(
    private val repository: ProjectTaskRepository,
    private val sessions: SessionUseCase,
    private val runner: AgentTurnRunner,
    private val projectWorkflow: com.aharou.feature.agent.domain.task.ProjectWorkflowRunner
) : ViewModel() {
    private val mutable = MutableStateFlow(ProjectTaskUiState())
    val state = mutable.asStateFlow()
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()
    // Retrying a failed link reuses the already persisted root session.
    private val pendingProjectSessions = mutableMapOf<Pair<String, String>, String>()
    private val executing = mutableSetOf<String>()
    private val executionJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var busyOwner: Any? = null
    private val projectJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    /** Projects with a single-task run in progress (task id -> project id); a whole-project flow is not queued behind it. */
    private val singleTaskProjects = mutableMapOf<String, String>()
    /** Projects that got new or changed tasks while their flow was running; one more pass runs when that flow ends. */
    private val rerunAfterFlow = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            try { val members = projectWorkflow.members(); mutable.update { it.copy(availableMembers = members) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        viewModelScope.launch { repository.snapshots.collect { stores ->
            val workspace = mutable.value.workspace
            val snapshot = stores[workspace]
            mutable.update { it.copy(tasks = snapshot?.tasks ?: it.tasks, projects = snapshot?.projects ?: it.projects,
                projectSessionIds = stores.values.flatMap { store -> store.projects.flatMap { it.flowSessionIds } + store.tasks.flatMap { task -> task.sessionIds + task.processSessionIds } }.toSet(),
                indexedSessionWorkspaces = stores.keys) }
        } }
    }

    fun workspace(path: String) {
        if (path == mutable.value.workspace && !mutable.value.loading) return
        mutable.value = ProjectTaskUiState(workspace = path, activeTaskIds = executionJobs.keys.toSet(), activeProjectIds = projectJobs.keys.toSet(),
            availableMembers = mutable.value.availableMembers, projectSessionIds = mutable.value.projectSessionIds,
            indexedSessionWorkspaces = mutable.value.indexedSessionWorkspaces, failedSessionWorkspaces = mutable.value.failedSessionWorkspaces,
            planningProjectIds = mutable.value.planningProjectIds, ruleFallbackProjectIds = mutable.value.ruleFallbackProjectIds)
        viewModelScope.launch {
            if (path.isBlank()) { mutable.update { it.copy(loading = false) }; return@launch }
            try {
                val snapshot = repository.load(path)
                if (mutable.value.workspace == path) mutable.update { it.copy(tasks = snapshot.tasks, projects = snapshot.projects, loading = false) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (mutable.value.workspace == path) mutable.update { it.copy(loading = false, error = failure.message ?: "TASK_READ_FAILED") }
            }
        }
    }

    suspend fun ensureProjectSession(projectId: String, workspace: String): String = sessionMutex.withLock {
        require(workspace.isNotBlank()) { "WORKSPACE_REQUIRED" }
        val project = repository.load(workspace).projects.single { it.id == projectId && it.workspace == workspace }
        val key = workspace to projectId
        val candidates = (project.flowSessionIds + listOfNotNull(pendingProjectSessions[key])).distinct()
            .mapNotNull { sessions.getSessionById(it) }
            .filter { it.parentId == null && it.workspacePath == workspace }
        val session = candidates.maxByOrNull { it.updatedAt } ?: runner.createSessionEntity(workspace).also {
            check(it.parentId == null && it.workspacePath == workspace)
            sessions.upsertSession(it)
            pendingProjectSessions[key] = it.id
        }
        if (session.id !in project.flowSessionIds) repository.linkProjectExecution(projectId, workspace, session.id)
        pendingProjectSessions.remove(key)
        session.id
    }

    fun selectProject(id: String?) { mutable.update { it.copy(selectedProjectId = id, selectedId = null) } }

    fun createProject(name: String, requirements: String) {
        val projectId = java.util.UUID.randomUUID().toString()
        planning(projectId) { workspace ->
            val outcome = repository.createProject(name, requirements, workspace, projectId)
            if (outcome.project.workspace == mutable.value.workspace) mutable.update { it.copy(selectedProjectId = outcome.project.id) }
            outcome.ruleFallback
        }
    }

    fun appendRequirements(projectId: String, text: String, requestId: String) = planning(projectId) { workspace ->
        val outcome = repository.appendRequirements(projectId, text, workspace)
        if (mutable.value.workspace == workspace) mutable.update { it.copy(appendSucceededRequest = requestId) }
        outcome.ruleFallback
    }

    /** Keeps the user's wording on the task, lets the planner choose what to redo, then continues the flow. */
    fun supplement(task: ProjectTask, text: String) {
        val projectId = task.projectId ?: return
        planning(projectId) { workspace -> repository.supplement(task.id, text, workspace) }
    }

    /** The planner decides parent (or keeps [parentId] when given), dependencies and members for one description. */
    fun addTaskByDescription(projectId: String, description: String, parentId: String? = null) = planning(projectId) { workspace ->
        repository.addTaskByDescription(projectId, description, workspace, parentId).ruleFallback
    }

    /** [block] returns whether rules replaced the model. On success the flow starts once the setup lock is released. */
    private fun planning(projectId: String, block: suspend (String) -> Boolean) = operation(onSuccess = { workspace ->
        startProjectIn(projectId, workspace, continueAfterRun = true)
    }) { workspace ->
        mutable.update { it.copy(planningProjectIds = it.planningProjectIds + projectId) }
        val fallback = try { block(workspace) } finally {
            mutable.update { it.copy(planningProjectIds = it.planningProjectIds - projectId) }
        }
        mutable.update { it.copy(ruleFallbackProjectIds = if (fallback) it.ruleFallbackProjectIds + projectId else it.ruleFallbackProjectIds - projectId) }
    }

    /** Load only known workspace stores to exclude project executions from ordinary history. */
    fun indexSessions(workspaces: List<String>) {
        viewModelScope.launch {
            for (workspace in workspaces.filter(String::isNotBlank).distinct()) {
                try {
                    repository.load(workspace)
                    mutable.update { it.copy(failedSessionWorkspaces = it.failedSessionWorkspaces - workspace) }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutable.update { it.copy(failedSessionWorkspaces = it.failedSessionWorkspaces + workspace) }
                }
            }
            mutable.update { it.copy(projectSessionIds = repository.snapshots.value.values.flatMap { store ->
                store.projects.flatMap { it.flowSessionIds } + store.tasks.flatMap { task -> task.sessionIds + task.processSessionIds }
            }.toSet()) }
        }
    }

    fun select(id: String) { mutable.update { it.copy(selectedId = id) } }

    fun save(task: ProjectTask) = operation {
        val saved = repository.save(task)
        if (mutable.value.workspace == saved.workspace) mutable.update { it.copy(selectedId = saved.id) }
    }

    fun startProject(projectId: String) {
        if (mutable.value.loading || mutable.value.busy) return
        startProjectIn(projectId, mutable.value.workspace)
    }

    /**
     * No busy check: automatic starts run right after their own setup lock is released and must not be dropped.
     * [continueAfterRun] (automatic starts) marks a running flow to take one more pass once it ends, which covers tasks
     * that arrive just as the flow finishes; a failed or stopped flow does not take that pass.
     */
    private fun startProjectIn(projectId: String, workspace: String, continueAfterRun: Boolean = false) {
        if (workspace.isBlank() || (continueAfterRun && projectId in singleTaskProjects.values)) return
        if (projectJobs[projectId]?.isActive == true) { if (continueAfterRun) rerunAfterFlow += projectId; return }
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var completed = false
            try { projectWorkflow.run(projectId, workspace); completed = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure.message ?: "PROJECT_FLOW_FAILED", workspace) }
            finally {
                projectJobs.remove(projectId)
                mutable.update { it.copy(activeProjectIds = projectJobs.keys.toSet()) }
            }
            if (rerunAfterFlow.remove(projectId) && completed && hasPendingTasks(projectId, workspace)) startProjectIn(projectId, workspace, continueAfterRun = true)
        }
        projectJobs[projectId] = job
        mutable.update { it.copy(activeProjectIds = projectJobs.keys.toSet(), error = null) }
        job.start()
    }
    fun stopProject(projectId: String) { rerunAfterFlow -= projectId; projectJobs[projectId]?.cancel() }

    private suspend fun hasPendingTasks(projectId: String, workspace: String): Boolean = try {
        repository.load(workspace).tasks.any { it.projectId == projectId && !com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.delivered(it) }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }

    fun runProjectTask(task: ProjectTask) {
        val projectId = task.projectId ?: return
        if (projectJobs[projectId]?.isActive == true || !executing.add(task.id)) return
        singleTaskProjects[task.id] = projectId
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { projectWorkflow.run(projectId, task.workspace, task.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure.message ?: "PROJECT_TASK_RUN_FAILED", task.workspace) }
            finally {
                executing.remove(task.id); executionJobs.remove(task.id); singleTaskProjects.remove(task.id)
                mutable.update { it.copy(activeTaskIds = executionJobs.keys.toSet()) }
            }
        }
        executionJobs[task.id] = job
        mutable.update { it.copy(activeTaskIds = executionJobs.keys.toSet(), error = null) }
        job.start()
    }

    fun cancelExecution(taskId: String) { executionJobs[taskId]?.cancel() }

    fun accept(task: ProjectTask) {
        if (task.status != ProjectTaskStatus.REVIEW ||
            (task.processSessionIds.isNotEmpty() && !com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.delivered(task))) return
        save(task.copy(status = ProjectTaskStatus.DONE, acceptedRequirementsVersion = task.requirementsVersion))
    }

    /** A new conversation is persisted and linked first. No network request until explicit start. */
    fun execute(task: ProjectTask, start: Boolean, runExecution: suspend (String, String, String) -> com.aharou.feature.agent.domain.task.ProjectTaskExecutionOutcome) = operation {
        val setupOwner = busyOwner
        if (!executing.add(task.id)) return@operation
        executionJobs[task.id] = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        mutable.update { it.copy(activeTaskIds = executionJobs.keys.toSet()) }
        try {
            val beforeStart = repository.load(task.workspace).tasks
            val latestTask = beforeStart.first { it.id == task.id }
            if (start && !com.aharou.feature.agent.domain.task.ProjectTaskGraph.dependenciesReady(latestTask, beforeStart)) {
                repository.save(latestTask.copy(status = ProjectTaskStatus.READY, executionReason = "WAITING_DEPENDENCIES"))
                if (busyOwner === setupOwner) releaseSetup()
                repository.snapshots.first { stores ->
                    val tasks = stores[task.workspace]?.tasks.orEmpty()
                    tasks.firstOrNull { it.id == task.id }?.let { com.aharou.feature.agent.domain.task.ProjectTaskGraph.dependenciesReady(it, tasks) } == true
                }
            }
            val current = repository.load(task.workspace).tasks.first { it.id == task.id }
            val session = withContext(Dispatchers.IO) { runner.createSessionEntity(current.workspace).copy(title = current.title.take(SessionUseCase.TITLE_MAX)) }
            sessions.upsertSession(session)
            val linked = repository.save(current.copy(sessionIds = current.sessionIds + session.id,
                executionRequirementsVersions = current.executionRequirementsVersions + (session.id to current.requirementsVersion),
                executionReason = "", status = if (start) ProjectTaskStatus.RUNNING else current.status))
            if (!start) return@operation
            if (busyOwner === setupOwner) releaseSetup() // Long execution must not freeze task editing or other workspaces.
            val completed = runExecution(session.id, linked.requirement, linked.workspace)
            // Execution completion is never acceptance. Preserve user edits made during the run.
            val currentTasks = repository.load(task.workspace).tasks
            val latest = currentTasks.first { it.id == task.id }
            val stale = latest.requirementsVersion != linked.requirementsVersion ||
                !com.aharou.feature.agent.domain.task.ProjectTaskGraph.executionReady(latest, session.id, currentTasks)
            repository.save(latest.copy(status = com.aharou.feature.agent.domain.task.ProjectTaskGraph.executionStatus(
                completed, stale), executionReason = if (stale) "UPSTREAM_CHANGED" else completed.name))
            if (completed != com.aharou.feature.agent.domain.task.ProjectTaskExecutionOutcome.SUCCEEDED) error("TASK_EXECUTION_FAILED", task.workspace)
        } catch (failure: Exception) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try {
                    val latest = repository.load(task.workspace).tasks.firstOrNull { it.id == task.id }
                    if (latest != null && (latest.status == ProjectTaskStatus.RUNNING || latest.executionReason == "WAITING_DEPENDENCIES")) repository.save(latest.copy(status = ProjectTaskStatus.BLOCKED,
                        executionReason = if (failure is CancellationException) "STOPPED" else "FAILED"))
                } catch (_: Exception) { error("TASK_EXECUTION_STATE_WRITE_FAILED", task.workspace) }
            }
            throw failure // Including CancellationException; never turn cancellation into successful completion.
        } finally {
            executing.remove(task.id); executionJobs.remove(task.id)
            mutable.update { it.copy(activeTaskIds = executionJobs.keys.toSet()) }
        }
    }

    private fun error(message: String, workspace: String = mutable.value.workspace) {
        mutable.update { if (it.workspace == workspace) it.copy(error = message) else it }
    }
    private fun releaseSetup() {
        busyOwner = null
        mutable.update { it.copy(busy = false) }
    }
    private fun operation(onSuccess: ((String) -> Unit)? = null, block: suspend (String) -> Unit) {
        if (mutable.value.loading || mutable.value.busy || mutable.value.workspace.isBlank()) return
        val workspace = mutable.value.workspace
        val owner = Any(); busyOwner = owner
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var succeeded = false
            try { block(workspace); succeeded = true } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error(failure.message ?: "TASK_OPERATION_FAILED", workspace)
            } finally { if (busyOwner === owner) releaseSetup() }
            if (succeeded) onSuccess?.invoke(workspace)
        }
    }
}
