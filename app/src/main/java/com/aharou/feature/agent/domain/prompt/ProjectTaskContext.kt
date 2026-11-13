package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import javax.inject.Inject

class ProjectTaskContext @Inject constructor(private val repository: ProjectTaskRepository) {
    private companion object {
        const val MAX_CONTEXT_CHARS = 280
        const val TRUNCATION_MARKER = "[project context truncated]"
    }

    fun build(sessionId: String?, workspace: String): String? =
        buildFacts(sessionId, workspace)?.let { text ->
            if (text.length <= MAX_CONTEXT_CHARS) text
            else text.take(MAX_CONTEXT_CHARS - TRUNCATION_MARKER.length - 1) + "\n" + TRUNCATION_MARKER
        }

    private fun buildFacts(sessionId: String?, workspace: String): String? {
        if (sessionId.isNullOrBlank() || workspace.isBlank()) return null
        val snapshot = try {
            repository.snapshot(workspace)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "project_task_context_status=unavailable"
        }
        val lookup = ProjectTaskGraph.currentContextTask(sessionId, workspace) { snapshot.tasks }
        if (lookup.unavailable) return "project_task_context_status=unavailable"
        val task = lookup.task
        if (task == null) {
            val project = snapshot.projects.singleOrNull { sessionId in it.flowSessionIds } ?: return null
            return buildString {
            append("kind=root; project=").append(project.id).append('\n')
            append("requirements=").append(project.requirements.take(150)).append('\n')
            snapshot.tasks.asSequence().filter { it.projectId == project.id }.take(2).forEach {
                append("task=").append(it.id).append(':').append(it.status).append(':')
                    .append(it.title.take(20)).append(':').append(it.requirement.take(30)).append('\n')
            }
        }
        }
        if (task.isRequirementBranch) return buildString {
            append("kind=requirement_branch_discussion; project=").append(task.projectId).append(';')
            append(" branch=").append(task.id).append('\n')
            append("requirements=").append(task.requirement.take(170)).append('\n')
        }
        return buildString {
            append("kind=task; project=").append(task.projectId).append("; id=").append(task.id)
                .append("; v=").append(task.version).append("; state=").append(task.status).append('\n')
            append("requirement=").append(task.requirement.take(140)).append('\n')
            append("constraints=").append(task.constraints.joinToString("; ").take(45)).append('\n')
            append("acceptance=").append(task.acceptance.take(45))
        }
    }
}
