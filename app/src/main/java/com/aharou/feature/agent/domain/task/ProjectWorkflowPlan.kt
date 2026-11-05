package com.aharou.feature.agent.domain.task

object ProjectWorkflowPlan {
    fun participants(task: ProjectTask): List<String> =
        (listOf(task.owner) + task.members).map(String::trim).filter(String::isNotBlank).ifEmpty { listOf("") }

    fun delivered(task: ProjectTask): Boolean =
        (task.processParticipants == null || task.processParticipants == participants(task)) &&
        ((task.status == ProjectTaskStatus.REVIEW && task.processRequirementsVersion == task.requirementsVersion) ||
        (task.status == ProjectTaskStatus.DONE && task.acceptedRequirementsVersion == task.requirementsVersion &&
            task.acceptance.isNotBlank() && task.evidenceRefs.isNotEmpty()))

    fun next(tasks: List<ProjectTask>): ProjectTask? {
        require(ProjectTaskGraph.validate(tasks) == null) { "INVALID_TASK_GRAPH" }
        val pending = ProjectTaskGraph.tree(tasks).map { it.task }.filterNot(::delivered)
        if (pending.isEmpty()) return null
        return pending.firstOrNull { task -> task.dependencyIds.all { id -> tasks.any { it.id == id && delivered(it) } } }
            ?: error("PROJECT_FLOW_BLOCKED")
    }

    fun prompt(task: ProjectTask, tasks: List<ProjectTask>, requirements: String,
        member: String = task.owner, ordinal: Int = 1): String = buildString {
        append("Project requirements:\n").append(requirements)
        append("\n\nTask ID: ").append(task.id).append("\nTask: ").append(task.title)
        append("\nRequirement:\n").append(task.requirement)
        append("\nBackground:\n").append(task.background)
        append("\nAssigned member: ").append(member.ifBlank { "default member" })
        append("\nParticipant: ").append(ordinal).append(" / ").append(participants(task).size)
        append("\nDeclared collaborators: ").append(task.members.joinToString(", "))
        append("\nConstraints:\n").append(task.constraints.joinToString("\n"))
        append("\nAcceptance:\n").append(task.acceptance)
        for (id in task.dependencyIds) tasks.firstOrNull { it.id == id }?.let {
            append("\n\nRequired upstream result [").append(it.id).append("]:\n").append(it.brief)
        }
        append("\n\nExecute only this task as a member of the project workflow. Return the result, evidence, and anything unresolved. Do not start a separate project flow or mark work accepted without evidence.")
    }
}
