package com.aharou.feature.agent.data.task

import android.content.Context
import android.util.AtomicFile
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
class ProjectTaskRepository @Inject constructor(@ApplicationContext context: Context, private val plannerProvider: Provider<ProjectPlanner>) {
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
        check(loaded.projects.all { it.workspace == workspace && it.id.isNotBlank() && it.title.isNotBlank() && it.requirements.isNotBlank() } &&
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

    /** The planner runs before any lock is taken; only the final commit is synchronized. */
    suspend fun createProject(name: String, requirements: String, workspace: String,
        projectId: String = java.util.UUID.randomUUID().toString()): ProjectPlanOutcome = withContext(Dispatchers.IO) {
        val project = ProjectPlan(id = projectId, workspace = workspace, title = name.trim(), requirements = requirements)
        require(project.title.isNotBlank() && requirements.isNotBlank()) { "REQUIRED_FIELDS" }
        snapshot(workspace) // An unreadable store rejects the request before any model call.
        val planner = plannerProvider.get()
        val planned = planner.plan(project, requirements, emptyList(), planner.members())
        createProjectLocked(project, planned.tasks)
        ProjectPlanOutcome(project, planned.ruleFallback)
    }

    @Synchronized
    private fun createProjectLocked(project: ProjectPlan, drafts: List<ProjectTask>) {
        val workspace = project.workspace
        val current = snapshot(workspace)
        require(current.projects.none { it.id == project.id }) { "PROJECT_EXISTS" }
        val tasks = drafts.mapIndexed { index, task -> task.copy(version = current.version + index + 1) }
        val next = current.copy(version = current.version + tasks.size, projects = current.projects + project, tasks = current.tasks + tasks)
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(workspace, next) // Only one AtomicFile commit for project and every planned task.
        mutable.value = mutable.value + (workspace to next)
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
    private fun appendTasksLocked(projectId: String, workspace: String, planned: List<ProjectTask>, appendText: String?): ProjectPlan {
        val current = snapshot(workspace)
        val project = current.projects.first { it.id == projectId }
        val startOrder = nextOrder(current)
        val tasks = planned.mapIndexed { index, task -> task.copy(version = current.version + index + 1, order = startOrder + index) }
        val updated = if (appendText == null) project else project.copy(requirements = project.requirements + "\n\n" + appendText)
        val next = current.copy(version = current.version + tasks.size,
            projects = current.projects.map { if (it.id == projectId) updated else it }, tasks = current.tasks + tasks)
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(workspace, next)
        mutable.value = mutable.value + (workspace to next)
        return updated
    }

    /**
     * Records [text] on the task first, so it survives a failed model call, then saves the planner's starting points.
     * Returns true when the planner fell back to appending the supplement to the task requirement.
     */
    suspend fun supplement(taskId: String, text: String, workspace: String): Boolean = withContext(Dispatchers.IO) {
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

    /** Each edit or rerun is its own save on the latest version, so dependency invalidation runs exactly as for a user edit. */
    @Synchronized
    private fun applyRevisionLocked(workspace: String, projectId: String, revision: ProjectRevision) {
        for (id in revision.touched) {
            val latest = snapshot(workspace).tasks.firstOrNull { it.id == id && it.projectId == projectId } ?: continue
            transactLocked(workspace) { listOf(revision.change(latest)) }
        }
        if (revision.added.isNotEmpty()) transactLocked(workspace) { snapshot ->
            val order = nextOrder(snapshot)
            revision.added.mapIndexed { index, task -> ProjectTaskChange(task.copy(order = order + index)) }
        }
    }

    private fun nextOrder(snapshot: ProjectTaskSnapshot): Long =
        maxOf(System.currentTimeMillis(), (snapshot.tasks.maxOfOrNull { it.order } ?: 0L) + 1)

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
