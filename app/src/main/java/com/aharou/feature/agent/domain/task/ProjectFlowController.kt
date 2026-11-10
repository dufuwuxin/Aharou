package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

data class ProjectRunKey(val workspace: String, val projectId: String)
data class ProjectTaskRunKey(val workspace: String, val taskId: String)
data class ProjectFlowState(
    val projects: Set<ProjectRunKey> = emptySet(),
    val tasks: Set<ProjectTaskRunKey> = emptySet(),
    val errors: Map<String, String> = emptyMap()
)

@Singleton
class ProjectFlowController @Inject constructor(
    private val repository: ProjectTaskRepository,
    private val workflow: Provider<ProjectWorkflowRunner>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val projectJobs = mutableMapOf<ProjectRunKey, Job>()
    private val taskJobs = mutableMapOf<ProjectTaskRunKey, Job>()
    private val singleTaskProjects = mutableMapOf<ProjectTaskRunKey, ProjectRunKey>()
    private val rerun = mutableSetOf<ProjectRunKey>()
    private val deleting = mutableSetOf<ProjectRunKey>()
    private val generations = mutableMapOf<ProjectRunKey, Any>()
    private val stopEpochs = ConcurrentHashMap<ProjectRunKey, AtomicLong>()
    private val mutable = MutableStateFlow(ProjectFlowState())
    val state = mutable.asStateFlow()

    fun stopEpoch(projectId: String, workspace: String): Long =
        stopEpochs[ProjectRunKey(workspace, projectId)]?.get() ?: 0L

    fun continueProject(projectId: String, workspace: String, expectedStopEpoch: Long) {
        scope.launch {
            if (stopEpoch(projectId, workspace) == expectedStopEpoch) start(ProjectRunKey(workspace, projectId), true)
        }
    }

    fun startProject(projectId: String, workspace: String, continueAfterRun: Boolean = false) {
        if (continueAfterRun) continueProject(projectId, workspace, stopEpoch(projectId, workspace))
        else scope.launch { start(ProjectRunKey(workspace, projectId), false) }
    }

    private fun start(key: ProjectRunKey, automatic: Boolean) {
        if (key.workspace.isBlank() || key in deleting || (automatic && key in singleTaskProjects.values)) return
        if (key in projectJobs) {
            if (automatic && projectJobs.getValue(key).isActive) rerun += key
            return
        }
        val generation = Any()
        generations[key] = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var completed = false
            try { workflow.get().run(key.projectId, key.workspace); completed = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(key.workspace, failure.message ?: "PROJECT_FLOW_FAILED") }
            finally {
                projectJobs.remove(key)
                publish()
                if (!completed) { rerun -= key; if (generations[key] === generation) generations.remove(key) }
            }
            try {
                if (rerun.remove(key) && completed && hasPendingTasks(key) && generations[key] === generation) start(key, true)
            } finally { if (generations[key] === generation) generations.remove(key) }
        }
        projectJobs[key] = job
        job.invokeOnCompletion {
            scope.launch {
                if (projectJobs[key] === job) {
                    projectJobs.remove(key)
                    rerun -= key
                    if (generations[key] === generation) generations.remove(key)
                    publish()
                }
            }
        }
        clearError(key.workspace)
        publish()
        job.start()
    }

    fun stopProject(projectId: String, workspace: String) {
        val key = ProjectRunKey(workspace, projectId)
        stopEpochs.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        scope.launch {
            generations.remove(key)
            rerun -= key
            projectJobs[key]?.cancel()
        }
    }

    suspend fun withProjectStopped(projectId: String, workspace: String, delete: suspend () -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            val key = ProjectRunKey(workspace, projectId)
            check(deleting.add(key)) { "PROJECT_BUSY" }
            try {
                stopEpochs.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
                generations.remove(key)
                rerun -= key
                val jobs = (listOfNotNull(projectJobs[key]) + singleTaskProjects.filterValues { it == key }
                    .keys.mapNotNull { taskJobs[it] }).distinct()
                jobs.forEach { it.cancel() }
                jobs.joinAll()
                delete()
            } finally { deleting -= key }
        }

    fun runProjectTask(task: ProjectTask) {
        if (task.isRequirementBranch) return
        val projectId = task.projectId ?: return
        scope.launch {
            val key = ProjectRunKey(task.workspace, projectId)
            val taskKey = ProjectTaskRunKey(task.workspace, task.id)
            if (key in deleting || key in projectJobs || taskKey in taskJobs) return@launch
            singleTaskProjects[taskKey] = key
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { workflow.get().run(projectId, task.workspace, task.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error(task.workspace, failure.message ?: "PROJECT_TASK_RUN_FAILED") }
                finally {
                    taskJobs.remove(taskKey)
                    singleTaskProjects.remove(taskKey)
                    publish()
                }
            }
            taskJobs[taskKey] = job
            job.invokeOnCompletion {
                scope.launch {
                    if (taskJobs[taskKey] === job) {
                        taskJobs.remove(taskKey)
                        singleTaskProjects.remove(taskKey)
                        publish()
                    }
                }
            }
            clearError(task.workspace)
            publish()
            job.start()
        }
    }

    fun cancelExecution(taskId: String, workspace: String) {
        scope.launch { taskJobs[ProjectTaskRunKey(workspace, taskId)]?.cancel() }
    }

    private suspend fun hasPendingTasks(key: ProjectRunKey): Boolean = try {
        repository.load(key.workspace).tasks.any { it.projectId == key.projectId && !it.isRequirementBranch && !ProjectWorkflowPlan.delivered(it) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { error(key.workspace, failure.message ?: "TASK_READ_FAILED"); false }

    private fun publish() {
        mutable.value = mutable.value.copy(projects = projectJobs.keys.toSet(), tasks = taskJobs.keys.toSet())
    }
    private fun clearError(workspace: String) { mutable.value = mutable.value.copy(errors = mutable.value.errors - workspace) }
    private fun error(workspace: String, message: String) {
        mutable.value = mutable.value.copy(errors = mutable.value.errors + (workspace to message))
    }
}
