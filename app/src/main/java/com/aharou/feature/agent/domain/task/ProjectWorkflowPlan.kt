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

    fun runnable(tasks: List<ProjectTask>, taskId: String): ProjectTask {
        val task = tasks.firstOrNull { it.id == taskId } ?: error("PROJECT_TASK_NOT_FOUND")
        check(task.dependencyIds.all { id -> tasks.any { it.id == id && delivered(it) } }) { "PROJECT_FLOW_BLOCKED" }
        return task
    }

    /**
     * Only a blocked attempt resumes, and only the unbroken leading members of its latest attempt:
     * a later member built on an earlier member's older output must run again.
     */
    fun reusableRuns(task: ProjectTask): List<String> {
        if (task.status != ProjectTaskStatus.BLOCKED) return emptyList()
        val reused = mutableListOf<String>()
        var after = -1
        for ((index, role) in participants(task).withIndex()) {
            val position = task.processSessionIds.indexOfLast { task.processMembers[it]?.ordinal == index + 1 }
            val record = task.processSessionIds.getOrNull(position)?.let(task.processMembers::get)
            if (position <= after || record == null || record.role != role || record.outcome != "SUCCEEDED" ||
                record.requirementsVersion != task.requirementsVersion) break
            reused += task.processSessionIds[position]
            after = position
        }
        return reused
    }

    fun prompt(task: ProjectTask, tasks: List<ProjectTask>, requirements: String,
        member: String = task.owner, ordinal: Int = 1, previous: List<String> = emptyList()): String = buildString {
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
        if (previous.isNotEmpty()) {
            append("\n\nEarlier participants on this task (continue from their results; do not redo finished work):\n")
            append(previous.joinToString("\n\n"))
        }
        append("\n\nExecute only this task as a member of the project workflow. Return the result, evidence, and anything unresolved. Do not start a separate project flow or mark work accepted without evidence.")
    }
}
