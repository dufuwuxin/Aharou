package com.aharou.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectFlowController
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

data class ProjectTaskUiState(
    val workspace: String = "", val tasks: List<ProjectTask> = emptyList(), val selectedId: String? = null,
    val loading: Boolean = true, val error: String? = null, val busy: Boolean = false,
    val createdProjectId: String? = null,
    val projectTaskCounts: Map<String, Int> = emptyMap(),
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
    val ruleFallbackProjectIds: Set<String> = emptySet(),
    val creating: Boolean = false
)

@HiltViewModel
class ProjectTaskViewModel @Inject constructor(
    private val repository: ProjectTaskRepository,
    private val sessions: SessionUseCase,
    private val runner: AgentTurnRunner,
    private val projectWorkflow: com.aharou.feature.agent.domain.task.ProjectWorkflowRunner,
    private val flows: ProjectFlowController,
    private val workspaces: javax.inject.Provider<com.aharou.feature.workspace.data.repository.WorkspaceRepository>
) : ViewModel() {
    private val mutable = MutableStateFlow(ProjectTaskUiState())
    val state = mutable.asStateFlow()
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()
    // Retrying a failed link reuses the already persisted root session.
    private val pendingProjectSessions = mutableMapOf<Pair<String, String>, String>()
    private val executing = mutableSetOf<Pair<String, String>>()
    private val deletingProjects = mutableSetOf<Pair<String, String>>()
    private val executionJobs = mutableMapOf<Pair<String, String>, kotlinx.coroutines.Job>()
    @Volatile private var selectionEpoch = 0L
    private var knownWorkspacePaths: Set<String>? = null
    private var anticipatedWorkspace: String? = null
    private var busyOwner: Any? = null
    private var creatingOwner: Any? = null
    private val createdWorkspacePaths = mutableSetOf<String>()
    private var flowErrors = emptyMap<String, String>()

    init {
        viewModelScope.launch {
            workspaces.get().workspaces.collect { known ->
                if (known.isNotEmpty()) knownWorkspacePaths = known.filter { it.available }.map { it.path }.toSet()
                createdWorkspacePaths.removeAll { path -> known.any { it.path == path } }
                refreshProjects()
                indexSessions(known.filter { it.available }.map { it.path })
            }
        }
        viewModelScope.launch { flows.state.collect { refreshExecutionState() } }
        viewModelScope.launch {
            try { val members = projectWorkflow.members(); mutable.update { it.copy(availableMembers = members) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        viewModelScope.launch { repository.snapshots.collect { stores ->
            val workspace = mutable.value.workspace
            val snapshot = stores[workspace]
            mutable.update { it.copy(tasks = snapshot?.tasks ?: it.tasks, projects = stores.filterKeys { it in createdWorkspacePaths || knownWorkspacePaths?.contains(it) != false }.values.flatMap { store -> store.projects }.sortedByDescending { project -> project.createdAt },
                projectTaskCounts = stores.values.flatMap { store -> store.tasks }.groupingBy { task -> task.projectId.orEmpty() }.eachCount(),
                projectSessionIds = stores.values.flatMap { store -> store.projects.flatMap { it.flowSessionIds } + store.tasks.flatMap { task -> task.sessionIds + task.processSessionIds } }.toSet(),
                indexedSessionWorkspaces = stores.keys) }
        } }
    }

    private fun refreshProjects() {
        val stores = repository.snapshots.value.filterKeys { it in createdWorkspacePaths || knownWorkspacePaths?.contains(it) != false }
        mutable.update { it.copy(projects = stores.values.flatMap { store -> store.projects }.sortedByDescending { it.createdAt }) }
    }

    fun workspace(path: String) {
        if (path == mutable.value.workspace && !mutable.value.loading) return
        if (path != anticipatedWorkspace) selectionEpoch++
        mutable.value = ProjectTaskUiState(workspace = path, busy = creatingOwner != null, creating = creatingOwner != null,
            createdProjectId = mutable.value.createdProjectId, projects = mutable.value.projects, projectTaskCounts = mutable.value.projectTaskCounts,
            availableMembers = mutable.value.availableMembers, projectSessionIds = mutable.value.projectSessionIds,
            indexedSessionWorkspaces = mutable.value.indexedSessionWorkspaces, failedSessionWorkspaces = mutable.value.failedSessionWorkspaces,
            planningProjectIds = mutable.value.planningProjectIds, ruleFallbackProjectIds = mutable.value.ruleFallbackProjectIds)
        refreshExecutionState()
        viewModelScope.launch {
            if (path.isBlank()) { mutable.update { it.copy(loading = false) }; return@launch }
            try {
                val snapshot = repository.load(path)
                if (mutable.value.workspace == path) mutable.update { it.copy(tasks = snapshot.tasks, loading = false) }
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

    fun selectProject(id: String?) { selectionEpoch++; mutable.update { it.copy(selectedProjectId = id, selectedId = null) } }

    fun openProject(id: String, onReady: () -> Unit) {
        val project = mutable.value.projects.singleOrNull { it.id == id } ?: return
        val epoch = ++selectionEpoch
        val source = mutable.value.workspace
        viewModelScope.launch {
            try {
                val snapshot = repository.load(project.workspace)
                check(snapshot.projects.any { it.id == id }) { "PROJECT_NOT_FOUND" }
                if (selectionEpoch != epoch) return@launch
                anticipatedWorkspace = project.workspace
                val activated = try { workspaces.get().activateProjectWorkspace(project.workspace, source) { selectionEpoch == epoch } }
                    finally { anticipatedWorkspace = null }
                if (!activated || selectionEpoch != epoch) return@launch
                anticipatedWorkspace = project.workspace
                try { workspace(project.workspace) } finally { anticipatedWorkspace = null }
                mutable.update { it.copy(tasks = snapshot.tasks, loading = false, selectedProjectId = id) }
                onReady()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure.message ?: "TASK_READ_FAILED", source) }
        }
    }

    fun canDeleteProject(project: ProjectPlan): Boolean = !mutable.value.busy && !mutable.value.loading &&
        project.id !in mutable.value.planningProjectIds && (project.workspace to project.id) !in deletingProjects

    /** Remove project metadata and links only; owned directories and conversations are retained. */
    fun deleteProject(projectId: String) {
        val project = mutable.value.projects.firstOrNull { it.id == projectId } ?: return
        if (!canDeleteProject(project)) { error("PROJECT_BUSY"); return }
        operation {
            val key = project.workspace to projectId
            if (!deletingProjects.add(key)) return@operation
            selectionEpoch++
            try {
                val taskIds = repository.load(project.workspace).tasks.filter { it.projectId == projectId }.map { it.id }.toSet()
                val jobs = executionJobs.filterKeys { it.first == project.workspace && it.second in taskIds }.values.toList()
                jobs.forEach { it.cancel() }
                flows.withProjectStopped(projectId, project.workspace) {
                    jobs.joinAll()
                    sessionMutex.withLock {
                        repository.deleteProject(projectId, project.workspace)
                        pendingProjectSessions.remove(key)
                        mutable.update { state -> state.copy(
                            selectedProjectId = state.selectedProjectId.takeUnless { state.workspace == project.workspace && it == projectId },
                            selectedId = state.selectedId.takeUnless { state.workspace == project.workspace && it in taskIds },
                            planningProjectIds = state.planningProjectIds - projectId,
                            ruleFallbackProjectIds = state.ruleFallbackProjectIds - projectId
                        ) }
                    }
                }
            } finally { deletingProjects -= key }
        }
    }

    fun createProject(name: String, purpose: String) = createOwnedProject(name, purpose)

    fun canContinueInOwnedWorkspace(project: ProjectPlan): Boolean {
        val current = mutable.value
        val execution = flows.state.value
        return project.workspaceOwnership == "legacy_shared" && !current.loading && !current.busy &&
            project.id !in current.planningProjectIds &&
            execution.projects.none { it.projectId == project.id && it.workspace == project.workspace } &&
            execution.tasks.none { key -> key.workspace == project.workspace && repository.snapshots.value[project.workspace]?.tasks
                .orEmpty().any { it.id == key.taskId && it.projectId == project.id } } &&
            repository.snapshots.value[project.workspace]?.tasks.orEmpty().none {
                it.projectId == project.id && it.status == ProjectTaskStatus.RUNNING }
    }

    fun continueInOwnedWorkspace(projectId: String, onCreated: (String) -> Unit) {
        val project = mutable.value.projects.singleOrNull { it.id == projectId } ?: return
        if (!canContinueInOwnedWorkspace(project)) { error("PROJECT_BUSY"); return }
        createOwnedProject(project.title, project.requirements, project, onCreated)
    }

    private fun createOwnedProject(name: String, purpose: String, original: ProjectPlan? = null,
        onCreated: ((String) -> Unit)? = null) {
        val current = mutable.value
        if (creatingOwner != null || busyOwner != null || current.loading || current.busy || current.workspace.isBlank()) return
        val owner = Any()
        creatingOwner = owner
        val source = current.workspace
        val projectId = java.util.UUID.randomUUID().toString()
        val epoch = selectionEpoch
        mutable.update { it.copy(creating = true, busy = true, error = null) }
        viewModelScope.launch {
            if (original != null) mutable.update { it.copy(planningProjectIds = it.planningProjectIds + projectId) }
            try {
                val outcome = if (original == null) repository.createOwnedProject(name, purpose, source, projectId)
                    else repository.continueInOwnedWorkspace(original.id, original.workspace, projectId)
                val project = outcome.project
                val target = project.workspace
                if (knownWorkspacePaths?.contains(target) != true) createdWorkspacePaths += target
                mutable.update { it.copy(createdProjectId = project.id,
                    projects = (it.projects.filterNot { prior -> prior.id == project.id } + project).sortedByDescending { item -> item.createdAt }) }
                if (original != null) {
                    mutable.update { it.copy(ruleFallbackProjectIds = if (outcome.ruleFallback) it.ruleFallbackProjectIds + project.id else it.ruleFallbackProjectIds - project.id) }
                    flows.continueProject(project.id, target, 0L)
                }
                if (selectionEpoch == epoch && mutable.value.workspace == source) {
                    anticipatedWorkspace = target
                    val activated = try { workspaces.get().activateProjectWorkspace(target, source) { selectionEpoch == epoch } }
                        finally { anticipatedWorkspace = null }
                    if (activated && selectionEpoch == epoch) {
                        anticipatedWorkspace = target
                        try { workspace(target) } finally { anticipatedWorkspace = null }
                        val snapshot = if (original == null) repository.snapshots.value[target]
                            else repository.load(target)
                        if (selectionEpoch == epoch && mutable.value.workspace == target) {
                            mutable.update { it.copy(tasks = snapshot?.tasks.orEmpty(), loading = false,
                                selectedProjectId = project.id) }
                            onCreated?.invoke(project.id)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                com.aharou.core.util.FileLogger.e("ProjectTasks", "Project creation failed", failure)
                mutable.update { it.copy(error = failure.message ?: "TASK_OPERATION_FAILED") }
            } finally {
                if (original != null) mutable.update { it.copy(planningProjectIds = it.planningProjectIds - projectId) }
                if (creatingOwner === owner) {
                    creatingOwner = null
                    mutable.update { it.copy(creating = false, busy = busyOwner != null) }
                }
            }
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
    private fun planning(projectId: String, block: suspend (String) -> Boolean) {
        val stopEpoch = flows.stopEpoch(projectId, mutable.value.workspace)
        operation(onSuccess = { workspace -> flows.continueProject(projectId, workspace, stopEpoch) }) { workspace ->
            mutable.update { it.copy(planningProjectIds = it.planningProjectIds + projectId) }
            val fallback = try { block(workspace) } finally {
                mutable.update { it.copy(planningProjectIds = it.planningProjectIds - projectId) }
            }
            mutable.update { it.copy(ruleFallbackProjectIds = if (fallback) it.ruleFallbackProjectIds + projectId else it.ruleFallbackProjectIds - projectId) }
        }
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

    private fun startProjectIn(projectId: String, workspace: String, continueAfterRun: Boolean = false) {
        flows.startProject(projectId, workspace, continueAfterRun)
    }
    fun stopProject(projectId: String) { flows.stopProject(projectId, mutable.value.workspace) }

    fun runProjectTask(task: ProjectTask) {
        if ((task.workspace to task.id) in executing) return
        flows.runProjectTask(task)
    }

    fun cancelExecution(taskId: String) {
        flows.cancelExecution(taskId, mutable.value.workspace)
        executionJobs[mutable.value.workspace to taskId]?.cancel()
    }

    private fun refreshExecutionState() {
        val shared = flows.state.value
        mutable.update { current -> current.copy(
            activeProjectIds = shared.projects.filter { it.workspace == current.workspace }.mapTo(mutableSetOf()) { it.projectId },
            activeTaskIds = executionJobs.keys.filter { it.first == current.workspace }.map { it.second }.toSet() + shared.tasks.filter { it.workspace == current.workspace }.map { it.taskId },
            error = shared.errors[current.workspace] ?: current.error.takeUnless { it == flowErrors[current.workspace] }
        ) }
        flowErrors = shared.errors
    }

    fun accept(task: ProjectTask) {
        if (task.status != ProjectTaskStatus.REVIEW ||
            (task.processSessionIds.isNotEmpty() && !com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.delivered(task))) return
        save(task.copy(status = ProjectTaskStatus.DONE, acceptedRequirementsVersion = task.requirementsVersion))
    }

    /** A new conversation is persisted and linked first. No network request until explicit start. */
    fun execute(task: ProjectTask, start: Boolean, runExecution: suspend (String, String, String) -> com.aharou.feature.agent.domain.task.ProjectTaskExecutionOutcome) = operation {
        val setupOwner = busyOwner
        if ((task.workspace to task.projectId) in deletingProjects) return@operation
        if (flows.state.value.tasks.any { it.workspace == task.workspace && it.taskId == task.id } || !executing.add(task.workspace to task.id)) return@operation
        executionJobs[task.workspace to task.id] = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        refreshExecutionState()
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
            executing.remove(task.workspace to task.id); executionJobs.remove(task.workspace to task.id)
            refreshExecutionState()
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
        if (mutable.value.loading || mutable.value.busy || creatingOwner != null || mutable.value.workspace.isBlank()) return
        val workspace = mutable.value.workspace
        val owner = Any(); busyOwner = owner
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var succeeded = false
            try { block(workspace); succeeded = true } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                com.aharou.core.util.FileLogger.e("ProjectTasks", "Project operation failed", failure)
                error(failure.message ?: "TASK_OPERATION_FAILED", workspace)
            } finally { if (busyOwner === owner) releaseSetup() }
            if (succeeded) onSuccess?.invoke(workspace)
        }
    }
}
