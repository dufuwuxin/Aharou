package com.aharou.feature.agent.domain.tool.session

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskChange
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class MemberTaskRequest(val action: String, val title: String, val requirement: String,
    val parentId: String? = null, val acceptance: String = "")

internal class ProjectTaskToolException(val code: String, message: String) : IllegalStateException(message)

/**
 * Project task writes for workflow members (`project_tasks`). The caller's session must be a member run of a RUNNING
 * task; that task is the only anchor, and workspace and project are derived from it, never taken from arguments.
 */
class ProjectTaskTool @Inject constructor(
    private val repository: ProjectTaskRepository
) : AbstractContextualTool() {
    override val name = "project_tasks"
    override val description = "Project workflow members only: add tasks under the task you are executing. " +
        "action=branch creates a research subtask and makes your current task depend on it; end your turn right after it, " +
        "the flow runs the branch and then reruns your task with the branch result. action=add creates a follow-up subtask " +
        "under your task (or parent_id = one of its subtasks) that runs after your task, without changing your task. " +
        "Workspace and project come from your running task. Limits: $MAX_MEMBER_TASKS member-added tasks per project, " +
        "tree depth ${ProjectTaskGraph.MAX_TREE_DEPTH}."
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    // Writes only the project task store, the same class of state as todo: no workspace files, commands or network.
    override val capabilities = setOf(ToolCapability.MODIFY_TODO_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING,
            "branch: research subtask your task waits for (end your turn after it); add: follow-up subtask that runs after your task",
            true, enum = listOf("branch", "add")),
        "title" to ToolParameter("title", ParameterType.STRING, "Short task title", true),
        "requirement" to ToolParameter("requirement", ParameterType.STRING, "What the new task must find out or do", true),
        "parent_id" to ToolParameter("parent_id", ParameterType.STRING,
            "add only: your current task id (default) or a task under it; ignored for branch", false),
        "acceptance" to ToolParameter("acceptance", ParameterType.STRING, "Optional acceptance criteria", false),
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        fun text(name: String) = (args[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val action = text("action").lowercase()
        if (action != "branch" && action != "add") return ToolResult.Error("action must be branch or add", "INVALID_ACTION")
        if (text("title").isBlank() || text("requirement").isBlank()) return ToolResult.Error("title and requirement are required", "MISSING_ARGS")
        val request = MemberTaskRequest(action, text("title"), text("requirement"), text("parent_id").ifBlank { null }, text("acceptance"))
        val sessionId = context.sessionId ?: return ToolResult.Error(NOT_IN_TASK_MESSAGE, NOT_IN_TASK)
        // The runner keeps the store of every running project loaded, so a member run is found without trusting arguments.
        val workspace = repository.snapshots.value.entries.firstOrNull { (_, store) -> store.tasks.any { it.runs(sessionId) } }?.key
            ?: return ToolResult.Error(NOT_IN_TASK_MESSAGE, NOT_IN_TASK)
        return try {
            val created = repository.transact(workspace) { snapshot -> memberChanges(snapshot.tasks, sessionId, request) }.first()
            ToolResult.Success(buildJsonObject {
                put("task_id", created.id)
                put("parent_id", created.parentId)
                put("action", action)
                put("instruction", if (action == "branch")
                    "Branch created and your current task now depends on it. End this turn now with a short note of what the branch must answer; " +
                        "the flow runs the branch first and then reruns your task with its result."
                    else "Subtask added under your task; it runs after your task is delivered. Continue your current task.")
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (rejected: ProjectTaskToolException) { ToolResult.Error(rejected.message.orEmpty(), rejected.code) }
        catch (failure: Exception) { ToolResult.Error(failure.message ?: "Task store write failed", "PROJECT_TASK_WRITE_FAILED") }
    }

    internal companion object {
        /** Tasks members may add to one project in total; user and planner tasks do not count. */
        const val MAX_MEMBER_TASKS = 20
        const val NOT_IN_TASK = "NOT_IN_PROJECT_TASK"
        private const val NOT_IN_TASK_MESSAGE = "Only a member that is executing a running project task can add tasks"
        private const val TITLE_MAX = 120
        private const val TEXT_MAX = 4_000

        private fun ProjectTask.runs(sessionId: String) =
            status == ProjectTaskStatus.RUNNING && projectId != null && sessionId in processSessionIds

        /** Pure write plan: the new task first, then (branch only) the current task depending on it. */
        fun memberChanges(tasks: List<ProjectTask>, sessionId: String, request: MemberTaskRequest,
            now: Long = System.currentTimeMillis()): List<ProjectTaskChange> {
            val current = tasks.singleOrNull { it.runs(sessionId) } ?: throw ProjectTaskToolException(NOT_IN_TASK, NOT_IN_TASK_MESSAGE)
            val project = tasks.filter { it.projectId == current.projectId }
            if (project.count { it.createdBySessionId != null } >= MAX_MEMBER_TASKS)
                throw ProjectTaskToolException("MEMBER_TASK_LIMIT", "This project already has $MAX_MEMBER_TASKS member-added tasks")
            val parent = if (request.action == "branch") current else {
                val target = project.firstOrNull { it.id == (request.parentId ?: current.id) }
                target?.takeIf { it.id == current.id || ProjectTaskGraph.subtree(current.id, project).any { task -> task.id == it.id } }
                    ?: throw ProjectTaskToolException("OUTSIDE_CURRENT_TASK", "parent_id must be your current task or a task under it")
            }
            if (ProjectTaskGraph.depth(parent, project) + 1 > ProjectTaskGraph.MAX_TREE_DEPTH)
                throw ProjectTaskToolException("TASK_DEPTH_LIMIT", "Task tree depth is limited to ${ProjectTaskGraph.MAX_TREE_DEPTH} levels")
            val child = ProjectTask(workspace = current.workspace, projectId = current.projectId, parentId = parent.id,
                title = request.title.take(TITLE_MAX), requirement = request.requirement.take(TEXT_MAX),
                acceptance = request.acceptance.take(TEXT_MAX), owner = current.owner, status = ProjectTaskStatus.READY,
                order = maxOf(now, (tasks.maxOfOrNull { it.order } ?: 0L) + 1), createdBySessionId = sessionId)
            if (request.action != "branch") return listOf(ProjectTaskChange(child))
            // The dependency bumps the current task's requirements version; the runner supersedes this member and reruns later.
            return listOf(ProjectTaskChange(child), ProjectTaskChange(current.copy(dependencyIds = current.dependencyIds + child.id)))
        }
    }
}
