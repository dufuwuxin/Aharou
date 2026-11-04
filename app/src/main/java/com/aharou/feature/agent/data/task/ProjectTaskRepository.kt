package com.aharou.feature.agent.data.task

import android.content.Context
import android.util.AtomicFile
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectPlanDraft
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
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

/** Private AtomicFile, not Room: legacy goals and conversations are never migrated or deleted. */
@Singleton
class ProjectTaskRepository @Inject constructor(@ApplicationContext context: Context) {
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

    suspend fun save(task: ProjectTask): ProjectTask = withContext(Dispatchers.IO) { saveLocked(task) }

    @Synchronized
    private fun saveLocked(proposed: ProjectTask): ProjectTask {
        val current = snapshot(proposed.workspace) // A failed read prevents any write.
        val old = current.tasks.firstOrNull { it.id == proposed.id }
        check(old == null || old.version == proposed.version) { "STALE_TASK_VERSION" }
        require(proposed.projectId == null || current.projects.any { it.id == proposed.projectId }) { "PROJECT_NOT_FOUND" }
        val updated = ProjectTaskGraph.revise(old, proposed, current.version + 1)
        val proposedTasks = current.tasks.filterNot { it.id == updated.id } + updated
        val invalidates = old != null && (old.requirementsVersion != updated.requirementsVersion ||
            (old.status == com.aharou.feature.agent.domain.task.ProjectTaskStatus.DONE && updated.status != old.status))
        val tasks = if (invalidates) ProjectTaskGraph.invalidateDependents(proposedTasks, updated.id, updated.version) else proposedTasks
        require(ProjectTaskGraph.validate(tasks) == null) { ProjectTaskGraph.validate(tasks).orEmpty() }
        val next = current.copy(version = tasks.maxOfOrNull { it.version } ?: updated.version, tasks = tasks)
        persist(proposed.workspace, next)
        mutable.value = mutable.value + (proposed.workspace to next)
        return updated
    }
    suspend fun createProject(name: String, requirements: String, workspace: String): ProjectPlan = withContext(Dispatchers.IO) {
        createProjectLocked(name, requirements, workspace)
    }

    @Synchronized
    private fun createProjectLocked(name: String, requirements: String, workspace: String): ProjectPlan {
        val current = snapshot(workspace)
        val project = ProjectPlan(workspace = workspace, title = name.trim(), requirements = requirements)
        val drafts = ProjectPlanDraft.build(project)
        val tasks = drafts.mapIndexed { index, task -> task.copy(version = current.version + index + 1) }
        val next = current.copy(version = current.version + tasks.size, projects = current.projects + project, tasks = current.tasks + tasks)
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
        persist(workspace, next) // Only one AtomicFile commit for project and every draft.
        mutable.value = mutable.value + (workspace to next)
        return project
    }

    suspend fun appendRequirements(projectId: String, text: String, workspace: String) = withContext(Dispatchers.IO) {
        appendRequirementsLocked(projectId, text, workspace)
    }

    @Synchronized
    private fun appendRequirementsLocked(projectId: String, text: String, workspace: String) {
        require(text.isNotBlank()) { "REQUIRED_FIELDS" }
        val current = snapshot(workspace)
        val project = current.projects.first { it.id == projectId }
        val startOrder = maxOf(System.currentTimeMillis(), (current.tasks.maxOfOrNull { it.order } ?: 0L) + 1)
        val tasks = ProjectPlanDraft.build(project.copy(requirements = text, createdAt = startOrder))
            .mapIndexed { index, task -> task.copy(version = current.version + index + 1) }
        val updated = project.copy(requirements = project.requirements + "\n\n" + text)
        val next = current.copy(version = current.version + tasks.size,
            projects = current.projects.map { if (it.id == projectId) updated else it }, tasks = current.tasks + tasks)
        require(ProjectTaskGraph.validate(next.tasks) == null) { "INVALID_TASK_GRAPH" }
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
