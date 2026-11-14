package com.aharou.feature.agent.data.task

import android.content.Context
import android.util.AtomicFile
import com.aharou.feature.agent.domain.task.ProjectDeletion
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectPlanner
import com.aharou.feature.agent.domain.task.ProjectRevision
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskChange
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProjectTaskSnapshot(val formatVersion: Int = 1, val version: Long = 0, val tasks: List<ProjectTask> = emptyList(),
    val projects: List<ProjectPlan> = emptyList())

/** [ruleFallback]: the model was unavailable or its answer invalid, so line rules split the tasks. */
data class ProjectPlanOutcome(val project: ProjectPlan, val ruleFallback: Boolean)

/**
 * Private AtomicFile, not Room: legacy goals and conversations are never migrated or deleted.
 * The planner is a Provider because it reaches the agent workflow, whose tools read this repository.
 */
@Singleton
class ProjectTaskRepository @Inject constructor(@ApplicationContext context: Context, private val plannerProvider: Provider<ProjectPlanner>,
    private val workspaces: Provider<com.aharou.feature.workspace.data.repository.WorkspaceRepository>) {
    private val root = File(context.filesDir, "project-tasks")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<Map<String, ProjectTaskSnapshot>>(emptyMap())
    val snapshots = mutable.asStateFlow()

    private fun file(workspace: String): AtomicFile {
        require(workspace.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(workspace.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(root, "$key.json"))
    }

    /** The synchronous prompt path already runs on the agent IO worker. Read errors propagate, never become an empty writable store. */
    @Synchronized
    fun snapshot(workspace: String): ProjectTaskSnapshot {
        mutable.value[workspace]?.let { return it }
        val disk = file(workspace)
        val loaded = if (!disk.baseFile.exists() && !File(disk.baseFile.path + ".bak").exists()) ProjectTaskSnapshot()
            else disk.openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString<ProjectTaskSnapshot>(it.readText()) }
        check(loaded.formatVersion == 1) { "UNSUPPORTED_TASK_FORMAT" }
        check(loaded.version >= 0 && loaded.tasks.all { it.version in 1..loaded.version }) { "INVALID_TASK_VERSION" }
        check(loaded.tasks.all { it.workspace == workspace }) { "CROSS_WORKSPACE" }
        check(loaded.projects.all { it.workspace == workspace && it.id.isNotBlank() && it.title.isNotBlank() &&
            (it.requirements.isNotBlank() || (it.workspaceOwnership == "owned" && it.purpose.isNotBlank() && it.requirements.isEmpty())) } &&
            loaded.projects.map { it.id }.distinct().size == loaded.projects.size) { "INVALID_PROJECT_STORE" }
        check(loaded.tasks.all { task -> task.projectId == null || loaded.projects.any { it.id == task.projectId } }) { "PROJECT_NOT_FOUND" }
        check(ProjectTaskGraph.validate(loaded.tasks) == null) { "INVALID_TASK_STORE" }
        var version = loaded.version
        val recovered = loaded.tasks.map { task ->
            val fixed = ProjectTaskGraph.recoverInterrupted(task)
            if (fixed == task) task else fixed.copy(version = ++version)
        }
        val snapshot = loaded.copy(version = version, tasks = recovered)
        if (version != loaded.version) persist(workspace, snapshot)
        mutable.value = mutable.value + (workspace to snapshot)
        return snapshot
    }

    suspend fun load(workspace: String): ProjectTaskSnapshot = withContext(Dispatchers.IO) { snapshot(workspace) }

    /** Returns false for an already absent project; failures publish no in-memory deletion. */
    suspend fun deleteProject(projectId: String, workspace: String): Boolean = withContext(Dispatchers.IO) {
        deleteProjectLocked(projectId, workspace)
    }

    @Synchronized
    private fun deleteProjectLocked(projectId: String, workspace: String): Boolean {
        val current = snapshot(workspace)
        val deletion = ProjectDeletion.plan(projectId, current.projects, current.tasks)
        if (!deletion.changed) return false
        val next = current.copy(version = current.version + 1, projects = deletion.projects, tasks = deletion.tasks)
        persist(workspace, next)
        mutable.value = mutable.value + (workspace to next)
        return true
    }

    /** Removes only the branch tree. Conversations and workspace files remain historical records. */
    suspend fun deleteRequirementBranch(projectId: String, branchId: String, workspace: String): Boolean =
        withContext(Dispatchers.IO) { deleteRequirementBranchLocked(projectId, branchId, workspace) }

    @Synchronized
    private fun deleteRequirementBranchLocked(projectId: String, branchId: String, workspace: String): Boolean {
        val current = snapshot(workspace)
        require(current.projects.any { it.id == projectId && it.workspace == workspace }) { "PROJECT_NOT_FOUND" }
        val branch = current.tasks.singleOrNull { it.id == branchId } ?: return false
        require(branch.projectId == projectId && branch.workspace == workspace && branch.isRequirementBranch) { "BRANCH_NOT_FOUND" }
        val subtree = ProjectTaskGraph.subtree(branchId, current.tasks)
        require(subtree.all { it.projectId == projectId && it.workspace == workspace }) { "CROSS_PROJECT" }
        val removed = subtree.map { it.id }.toSet()
        val retained = current.tasks.filterNot { it.id in removed }
        check(retained.none { task -> task.dependencyIds.any { it in removed } }) { "BRANCH_EXTERNAL_DEPENDENCY" }
        val next = current.copy(version = current.version + 1, tasks = retained)
        check(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(workspace, next)
        mutable.value = mutable.value + (workspace to next)
        return true
    }

    suspend fun save(task: ProjectTask): ProjectTask = withContext(Dispatchers.IO) {
        transactLocked(task.workspace) { listOf(ProjectTaskChange(task)) }.single()
    }

    /**
     * Stages every change against one locked snapshot and commits them in a single AtomicFile write. [changes] runs under
     * the store lock: it must not suspend or call a model, and a later change must not touch a task an earlier one invalidated.
     */
    suspend fun transact(workspace: String, changes: (ProjectTaskSnapshot) -> List<ProjectTaskChange>): List<ProjectTask> =
        withContext(Dispatchers.IO) { transactLocked(workspace, changes) }

    @Synchronized
    private fun transactLocked(workspace: String, build: (ProjectTaskSnapshot) -> List<ProjectTaskChange>): List<ProjectTask> {
        var current = snapshot(workspace) // A failed read prevents any write.
        val changes = build(current)
        if (changes.isEmpty()) return emptyList()
        val saved = changes.map { change ->
            require(change.task.workspace == workspace) { "CROSS_WORKSPACE" }
            val (next, updated) = stage(current, change)
            current = next
            updated
        }
        persist(workspace, current)
        mutable.value = mutable.value + (workspace to current)
        return saved
    }

    private fun stage(current: ProjectTaskSnapshot, change: ProjectTaskChange): Pair<ProjectTaskSnapshot, ProjectTask> {
        val proposed = change.task
        val old = current.tasks.firstOrNull { it.id == proposed.id }
        check(old == null || old.version == proposed.version) { "STALE_TASK_VERSION" }
        require(proposed.projectId == null || current.projects.any { it.id == proposed.projectId }) { "PROJECT_NOT_FOUND" }
        val updated = ProjectTaskGraph.revise(old, proposed, current.version + 1, change.force)
        val proposedTasks = current.tasks.filterNot { it.id == updated.id } + updated
        val invalidates = old != null && (old.requirementsVersion != updated.requirementsVersion ||
            (old.status == ProjectTaskStatus.DONE && updated.status != old.status))
        val tasks = if (invalidates) ProjectTaskGraph.invalidateDependents(proposedTasks, updated.id, updated.version) else proposedTasks
        require(ProjectTaskGraph.validate(tasks) == null) { ProjectTaskGraph.validate(tasks).orEmpty() }
        return current.copy(version = tasks.maxOfOrNull { it.version } ?: updated.version, tasks = tasks) to updated
    }

    suspend fun createOwnedProject(name: String, purpose: String, sourceWorkspace: String,
        projectId: String): ProjectPlanOutcome {
        require(name.isNotBlank() && purpose.isNotBlank() && sourceWorkspace.isNotBlank()) { "REQUIRED_FIELDS" }
        val workspace = workspaces.get().allocateProjectWorkspace(projectId, sourceWorkspace)
        val project = ProjectPlan(id = projectId, workspace = workspace.path, title = name.trim(),
            purpose = purpose.trim(), workspaceOwnership = "owned")
        return withContext(Dispatchers.IO) {
            createProjectLocked(project, emptyList())
            ProjectPlanOutcome(project, ruleFallback = false)
        }
    }

    suspend fun continueInOwnedWorkspace(sourceProjectId: String, sourceWorkspace: String,
        projectId: String): ProjectPlanOutcome {
        val source = load(sourceWorkspace)
        val original = source.projects.single { it.id == sourceProjectId && it.workspace == sourceWorkspace }
        require(original.workspaceOwnership == "legacy_shared") { "PROJECT_ALREADY_OWNED" }
        require(source.tasks.none { it.projectId == sourceProjectId && it.status == ProjectTaskStatus.RUNNING }) { "PROJECT_BUSY" }
        require(original.title.isNotBlank() && original.requirements.isNotBlank()) { "REQUIRED_FIELDS" }
        require(projectId != sourceProjectId) { "PROJECT_EXISTS" }
        val workspace = workspaces.get().allocateProjectWorkspace(projectId, sourceWorkspace)
        return planProject(ProjectPlan(id = projectId, workspace = workspace.path, title = original.title,
            requirements = original.requirements, workspaceOwnership = "owned", continuationSourceProjectId = original.id, purpose = original.purpose,
            horizon = original.horizon, overallGoal = original.overallGoal, goalAcceptance = original.goalAcceptance))
    }

    suspend fun createProject(name: String, requirements: String, workspace: String,
        projectId: String = java.util.UUID.randomUUID().toString()): ProjectPlanOutcome {
        require(!File(workspace, ".aharou-project-owner").exists()) { "PROJECT_WORKSPACE_OWNED" }
        return planProject(ProjectPlan(id = projectId, workspace = workspace, title = name.trim(), requirements = requirements))
    }

    private suspend fun planProject(project: ProjectPlan): ProjectPlanOutcome = withContext(Dispatchers.IO) {
        require(project.title.isNotBlank() && project.requirements.isNotBlank()) { "REQUIRED_FIELDS" }
        val current = snapshot(project.workspace)
        require(current.projects.none { it.id == project.id }) { "PROJECT_EXISTS" }
        require(current.projects.none { it.workspaceOwnership == "owned" } &&
            (project.workspaceOwnership != "owned" || current.projects.isEmpty())) { "PROJECT_WORKSPACE_OWNED" }
        val planner = plannerProvider.get()
        val planned = planner.plan(project, project.requirements, emptyList(), planner.members())
        createProjectLocked(project, planned.tasks)
        ProjectPlanOutcome(project, planned.ruleFallback)
    }

    @Synchronized
    private fun createProjectLocked(project: ProjectPlan, drafts: List<ProjectTask>) {
        val workspace = project.workspace
        val current = snapshot(workspace)
        require(current.projects.none { it.id == project.id }) { "PROJECT_EXISTS" }
        require(current.projects.none { it.workspaceOwnership == "owned" } &&
            (project.workspaceOwnership != "owned" || current.projects.isEmpty())) { "PROJECT_WORKSPACE_OWNED" }
        val tasks = drafts.mapIndexed { index, task -> task.copy(version = current.version + index + 1) }
        val next = current.copy(version = current.version + tasks.size, projects = current.projects + project, tasks = current.tasks + tasks)
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(workspace, next) // Only one AtomicFile commit for project and every planned task.
        mutable.value = mutable.value + (workspace to next)
    }

    /** Empty named context, committed under the same lock as ordinary task writes; never resolves the planner. */
    suspend fun createRequirementBranch(projectId: String, title: String, workspace: String,
        parentId: String? = null): ProjectTask = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "REQUIRED_FIELDS" }
        transactLocked(workspace) { current ->
            require(current.projects.any { it.id == projectId }) { "PROJECT_NOT_FOUND" }
            parentId?.let { id ->
                val parent = current.tasks.singleOrNull { it.id == id && it.projectId == projectId } ?: error("PARENT_NOT_FOUND")
                check(ProjectTaskGraph.depth(parent, current.tasks) < ProjectTaskGraph.MAX_TREE_DEPTH) { "TASK_DEPTH_LIMIT" }
            }
            listOf(ProjectTaskChange(ProjectTask(workspace = workspace, projectId = projectId, title = title.trim(),
                requirement = "", parentId = parentId, order = nextOrder(current), isRequirementBranch = true)))
        }.single()
    }

    /** Wording survives a cancelled/failed planning call. New work is confined to this container, including fallback. */
    suspend fun appendBranchRequirements(branchId: String, text: String, workspace: String): ProjectPlanOutcome =
        withContext(Dispatchers.IO) {
            require(text.isNotBlank()) { "REQUIRED_FIELDS" }
            val branch = recordBranchRequirementsLocked(branchId, text, workspace)
            val current = snapshot(workspace)
            val project = current.projects.single { it.id == branch.projectId }
            val projectTasks = current.tasks.filter { it.projectId == project.id }
            check(ProjectTaskGraph.depth(branch, projectTasks) < ProjectTaskGraph.MAX_TREE_DEPTH) { "TASK_DEPTH_LIMIT" }
            val planner = plannerProvider.get()
            val planned = planner.plan(project, text, projectTasks, planner.members(), parentId = branch.id)
            ProjectPlanOutcome(appendTasksLocked(project.id, workspace, planned.tasks, null, branch.id), planned.ruleFallback)
        }

    @Synchronized
    private fun recordBranchRequirementsLocked(branchId: String, text: String, workspace: String): ProjectTask {
        val current = snapshot(workspace)
        val branch = current.tasks.singleOrNull { it.id == branchId && it.isRequirementBranch } ?: error("BRANCH_NOT_FOUND")
        check(current.projects.any { it.id == branch.projectId }) { "PROJECT_NOT_FOUND" }
        val updated = ProjectTaskGraph.revise(branch, branch.copy(requirement =
            if (branch.requirement.isEmpty()) text else branch.requirement + "\n\n" + text,
            supplements = branch.supplements + text), current.version + 1)
        val next = current.copy(version = updated.version,
            tasks = current.tasks.map { if (it.id == branchId) updated else it })
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(workspace, next)
        mutable.value = mutable.value + (workspace to next)
        return updated
    }

    suspend fun appendRequirements(projectId: String, text: String, workspace: String): ProjectPlanOutcome =
        planInto(projectId, text, workspace, extendRequirements = true)

    /**
     * One description becomes planned tasks; the planner picks parent, dependencies and members. Project requirements stay
     * unchanged. A [parentId] fixes the parent of every new top-level task (depth limit still applies).
     */
    suspend fun addTaskByDescription(projectId: String, description: String, workspace: String, parentId: String? = null): ProjectPlanOutcome =
        planInto(projectId, description, workspace, extendRequirements = false, parentId = parentId)

    private suspend fun planInto(projectId: String, text: String, workspace: String, extendRequirements: Boolean,
        parentId: String? = null): ProjectPlanOutcome =
        withContext(Dispatchers.IO) {
            require(text.isNotBlank()) { "REQUIRED_FIELDS" }
            val current = snapshot(workspace)
            val project = current.projects.firstOrNull { it.id == projectId } ?: error("PROJECT_NOT_FOUND")
            val projectTasks = current.tasks.filter { it.projectId == projectId }
            parentId?.let { id ->
                val parent = projectTasks.firstOrNull { it.id == id } ?: error("PARENT_NOT_FOUND")
                check(ProjectTaskGraph.depth(parent, projectTasks) + 1 <= ProjectTaskGraph.MAX_TREE_DEPTH) { "TASK_DEPTH_LIMIT" }
            }
            val planner = plannerProvider.get()
            val planned = planner.plan(project, text, projectTasks, planner.members(), parentId)
            ProjectPlanOutcome(appendTasksLocked(projectId, workspace, planned.tasks, text.takeIf { extendRequirements }), planned.ruleFallback)
        }

    @Synchronized
    private fun appendTasksLocked(projectId: String, workspace: String, planned: List<ProjectTask>, appendText: String?, branchId: String? = null): ProjectPlan {
        val current = snapshot(workspace)
        val project = current.projects.first { it.id == projectId }
        if (branchId != null) {
            check(current.tasks.any { it.id == branchId && it.projectId == projectId && it.isRequirementBranch }) { "BRANCH_NOT_FOUND" }
            val inside = ProjectTaskGraph.subtree(branchId, planned + current.tasks).map { it.id }.toSet()
            check(planned.all { it.id in inside && it.projectId == projectId && it.workspace == workspace && !it.isRequirementBranch }) { "OUTSIDE_BRANCH" }
            check(planned.all { task -> task.dependencyIds.all { it in inside } }) { "OUTSIDE_BRANCH_DEPENDENCY" }
            check(planned.all { ProjectTaskGraph.depth(it, current.tasks + planned) <= ProjectTaskGraph.MAX_TREE_DEPTH }) { "TASK_DEPTH_LIMIT" }
        }
        val startOrder = nextOrder(current)
        val tasks = planned.mapIndexed { index, task -> task.copy(version = current.version + index + 1, order = startOrder + index) }
        val updated = if (appendText == null) project else project.copy(requirements =
            if (project.requirements.isEmpty()) appendText else project.requirements + "\n\n" + appendText)
        val next = current.copy(version = current.version + tasks.size,
            projects = current.projects.map { if (it.id == projectId) updated else it }, tasks = current.tasks + tasks)
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(workspace, next)
        mutable.value = mutable.value + (workspace to next)
        return updated
    }

    /**
     * Records [text] on the task first, so it survives a failed model call, then saves the planner's starting points.
     * Returns true when the planner fell back to rules, which rerun the task with the recorded supplement.
     */
    suspend fun supplement(taskId: String, text: String, workspace: String): Boolean = withContext(Dispatchers.IO) {
        if (snapshot(workspace).tasks.any { it.id == taskId && it.isRequirementBranch })
            return@withContext appendBranchRequirements(taskId, text, workspace).ruleFallback
        val supplement = text.trim()
        require(supplement.isNotBlank()) { "REQUIRED_FIELDS" }
        require(snapshot(workspace).tasks.firstOrNull { it.id == taskId }?.projectId != null) { "PROJECT_NOT_FOUND" }
        val recorded = transactLocked(workspace) { snapshot ->
            val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: error("PROJECT_TASK_NOT_FOUND")
            listOf(ProjectTaskChange(task.copy(supplements = task.supplements + supplement)))
        }.single()
        val projectId = requireNotNull(recorded.projectId)
        val current = snapshot(workspace)
        val planner = plannerProvider.get()
        val revision = planner.revise(current.projects.first { it.id == projectId }, recorded,
            current.tasks.filter { it.projectId == projectId }, supplement, planner.members())
        applyRevisionLocked(workspace, projectId, revision)
        revision.ruleFallback
    }

    /**
     * Every edit, rerun and add is staged on the result of the previous one (so dependency invalidation runs exactly as for
     * a user edit) and the batch is written once: if any change fails validation nothing of the revision is saved.
     */
    @Synchronized
    private fun applyRevisionLocked(workspace: String, projectId: String, revision: ProjectRevision) {
        val start = snapshot(workspace)
        var current = start
        for (id in revision.touched) {
            val latest = current.tasks.firstOrNull { it.id == id && it.projectId == projectId } ?: continue
            current = stage(current, revision.change(latest)).first
        }
        val order = nextOrder(current)
        revision.added.forEachIndexed { index, task -> current = stage(current, ProjectTaskChange(task.copy(order = order + index))).first }
        if (current === start) return
        persist(workspace, current)
        mutable.value = mutable.value + (workspace to current)
    }

    private fun nextOrder(snapshot: ProjectTaskSnapshot): Long =
        maxOf(System.currentTimeMillis(), (snapshot.tasks.maxOfOrNull { it.order } ?: 0L) + 1)

    /** Goal writes share the existing store lock and AtomicFile transaction, including for empty projects. */
    suspend fun saveProjectGoal(project: ProjectPlan, horizon: String, goal: String, acceptance: String) =
        withContext(Dispatchers.IO) { saveProjectGoalLocked(project, horizon, goal, acceptance) }

    @Synchronized
    private fun saveProjectGoalLocked(expected: ProjectPlan, horizon: String, goal: String, acceptance: String) {
        require(horizon in com.aharou.feature.agent.domain.task.ProjectGoalPlan.horizons) { "INVALID_HORIZON" }
        val current = snapshot(expected.workspace)
        val project = current.projects.single { it.id == expected.id }
        check(project.goalRevision == expected.goalRevision) { "PROJECT_GOAL_CHANGED" }
        check(current.tasks.none { it.projectId == project.id && it.status == ProjectTaskStatus.RUNNING }) { "PROJECT_BUSY" }
        val updated = project.copy(horizon = horizon, overallGoal = goal.trim(), goalAcceptance = acceptance.trim(),
            goalCheckpoint = "", goalRevision = project.goalRevision + 1)
        val semanticChange = project.overallGoal != updated.overallGoal || project.goalAcceptance != updated.goalAcceptance
        val revisedTasks = if (!semanticChange) current.tasks else current.tasks.map { task ->
            if (task.projectId != project.id || task.isRequirementBranch) task else task.copy(
                status = ProjectTaskStatus.DRAFT, requirementsVersion = task.requirementsVersion + 1,
                acceptedRequirementsVersion = null, processRequirementsVersion = null, processParticipants = null,
                processMembers = emptyMap(), brief = "", executionReason = "PROJECT_GOAL_CHANGED", evidenceRefs = emptyList(),
                version = current.version + 1)
        }
        val next = current.copy(version = current.version + 1, tasks = revisedTasks,
            projects = current.projects.map { if (it.id == project.id) updated else it })
        persist(expected.workspace, next)
        mutable.value = mutable.value + (expected.workspace to next)
    }

    suspend fun proposeGoalStage(expected: ProjectPlan): Boolean = withContext(Dispatchers.IO) {
        val current = snapshot(expected.workspace)
        val project = current.projects.single { it.id == expected.id }
        if (project.goalRevision != expected.goalRevision || project.goalCheckpoint == "NEXT_STAGE_CONFIRMATION_REQUIRED") return@withContext false
        val previous = current.tasks.filter { it.projectId == project.id && !it.isRequirementBranch }
        if (previous.any { !com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.delivered(it) }) return@withContext false
        val planner = plannerProvider.get()
        val request = buildString {
            append("Propose concrete next-stage tasks towards the overall goal, without claiming previous REVIEW work was accepted.\n")
            append("Overall goal: ").append(project.overallGoal).append("\nAcceptance: ").append(project.goalAcceptance)
            append("\nHorizon: ").append(project.horizon).append("\nPrevious evidence:\n")
            previous.takeLast(12).forEach { append(it.title).append(": ").append(it.brief.take(400)).append('\n') }
        }
        val planned = planner.plan(project, request, current.tasks.filter { it.projectId == project.id }, planner.members())
        if (planned.ruleFallback || planned.tasks.isEmpty() || planned.tasks.size > 6) return@withContext false
        commitGoalStage(project, planned.tasks, current.version)
    }

    @Synchronized private fun commitGoalStage(expected: ProjectPlan, proposed: List<ProjectTask>, expectedStoreVersion: Long): Boolean {
        val current = snapshot(expected.workspace)
        val project = current.projects.single { it.id == expected.id }
        if (current.version != expectedStoreVersion || project.goalRevision != expected.goalRevision || project.goalCheckpoint == "NEXT_STAGE_CONFIRMATION_REQUIRED") return false
        val start = nextOrder(current)
        val tasks = proposed.mapIndexed { index, task -> task.copy(workspace = expected.workspace, projectId = expected.id,
            status = ProjectTaskStatus.DRAFT, executionReason = "NEXT_STAGE_CONFIRMATION_REQUIRED", order = start + index) }
        val next = current.copy(version = current.version + 1, tasks = current.tasks + tasks,
            projects = current.projects.map { if (it.id == project.id) it.copy(goalCheckpoint = "NEXT_STAGE_CONFIRMATION_REQUIRED") else it })
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(expected.workspace, next); mutable.value = mutable.value + (expected.workspace to next)
        return true
    }

    suspend fun checkpointProjectGoal(project: ProjectPlan, reason: String) =
        withContext(Dispatchers.IO) { checkpointProjectGoalLocked(project, reason) }

    @Synchronized
    private fun checkpointProjectGoalLocked(expected: ProjectPlan, reason: String) {
        val current = snapshot(expected.workspace)
        val project = current.projects.singleOrNull { it.id == expected.id } ?: return
        if (project.goalRevision != expected.goalRevision) return // A newer goal cannot inherit an old run's checkpoint.
        val next = current.copy(version = current.version + 1,
            projects = current.projects.map { if (it.id == project.id) it.copy(goalCheckpoint = reason) else it })
        persist(expected.workspace, next)
        mutable.value = mutable.value + (expected.workspace to next)
    }

    suspend fun linkProjectExecution(projectId: String, workspace: String, sessionId: String) = withContext(Dispatchers.IO) {
        linkProjectExecutionLocked(projectId, workspace, sessionId)
    }

    @Synchronized
    private fun linkProjectExecutionLocked(projectId: String, workspace: String, sessionId: String) {
        val current = snapshot(workspace)
        require(current.projects.any { it.id == projectId }) { "PROJECT_NOT_FOUND" }
        val next = current.copy(version = current.version + 1, projects = current.projects.map {
            if (it.id == projectId) it.copy(flowSessionIds = (it.flowSessionIds + sessionId).distinct()) else it
        })
        persist(workspace, next)
        mutable.value = mutable.value + (workspace to next)
    }

    suspend fun requireExecutionReady(workspace: String, sessionId: String) {
        val tasks = try { load(workspace).tasks }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return } // Task creation already requires readable storage; legacy turns keep their unavailable-context diagnostic.
        val task = tasks.singleOrNull { sessionId in it.sessionIds } ?: return
        if (task.isRequirementBranch) return // A discussion session has no execution version to satisfy.
        check(ProjectTaskGraph.executionReady(task, sessionId, tasks)) { "TASK_DEPENDENCIES_OR_REQUIREMENTS_CHANGED" }
    }

    private fun persist(workspace: String, snapshot: ProjectTaskSnapshot) {
        check(root.isDirectory || root.mkdirs()) { "TASK_DIRECTORY_FAILED" }
        val disk = file(workspace)
        val output = disk.startWrite()
        try {
            output.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            disk.finishWrite(output)
        } catch (failure: Exception) {
            disk.failWrite(output)
            throw failure
        }
    }
}
