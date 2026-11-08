package com.aharou.feature.agent.domain.task

/** The deletion boundary is projectId, never task ancestry or a linked conversation. */
object ProjectDeletion {
    data class Plan(val projects: List<ProjectPlan>, val tasks: List<ProjectTask>, val changed: Boolean)

    fun plan(projectId: String, projects: List<ProjectPlan>, tasks: List<ProjectTask>): Plan {
        require(projectId.isNotBlank()) { "PROJECT_REQUIRED" }
        val retainedProjects = projects.filterNot { it.id == projectId }
        val retainedTasks = tasks.filterNot { it.projectId == projectId }
        require(ProjectTaskGraph.validate(retainedTasks) == null) { "INVALID_TASK_GRAPH" }
        require(retainedTasks.all { task -> task.projectId == null || retainedProjects.any { it.id == task.projectId } }) {
            "PROJECT_NOT_FOUND"
        }
        return Plan(retainedProjects, retainedTasks, retainedProjects.size != projects.size || retainedTasks.size != tasks.size)
    }
}
