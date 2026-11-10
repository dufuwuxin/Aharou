package com.aharou.feature.agent.domain.task

object ProjectWorkflowPlan {
    const val PROJECT_TASKS_TOOL = "project_tasks"

    fun participants(task: ProjectTask): List<String> =
        (listOf(task.owner) + task.members).map(String::trim).filter(String::isNotBlank).ifEmpty { listOf("") }

    fun delivered(task: ProjectTask): Boolean =
        !task.isRequirementBranch && (task.processParticipants == null || task.processParticipants == participants(task)) &&
        ((task.status == ProjectTaskStatus.REVIEW && task.processRequirementsVersion == task.requirementsVersion) ||
        (task.status == ProjectTaskStatus.DONE && task.acceptedRequirementsVersion == task.requirementsVersion &&
            task.acceptance.isNotBlank() && task.evidenceRefs.isNotEmpty()))

    fun next(tasks: List<ProjectTask>): ProjectTask? {
        require(ProjectTaskGraph.validate(tasks) == null) { "INVALID_TASK_GRAPH" }
        val pending = ProjectTaskGraph.tree(tasks).map { it.task }.filterNot { it.isRequirementBranch || delivered(it) }
        if (pending.isEmpty()) return null
        return pending.firstOrNull { dependenciesDelivered(it, tasks) } ?: error("PROJECT_FLOW_BLOCKED")
    }

    fun dependenciesDelivered(task: ProjectTask, tasks: List<ProjectTask>): Boolean =
        task.dependencyIds.all { id -> tasks.any { it.id == id && delivered(it) } }

    /**
     * Single-task run: the task itself once its dependencies are delivered; before that, the first member-made branch in
     * its subtree that it (transitively) waits on, is undelivered and can run now. Null when nothing there can run.
     */
    fun nextWithin(tasks: List<ProjectTask>, taskId: String): ProjectTask? {
        val task = tasks.firstOrNull { it.id == taskId } ?: return null
        if (task.isRequirementBranch) return null
        if (dependenciesDelivered(task, tasks)) return task
        val inside = ProjectTaskGraph.subtree(taskId, tasks).associateBy { it.id }
        val needed = hashSetOf<String>()
        val queue = java.util.ArrayDeque(task.dependencyIds)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val dependency = inside[id]?.takeIf { it.createdBySessionId != null && !delivered(it) } ?: continue
            if (needed.add(id)) queue.addAll(dependency.dependencyIds)
        }
        return ProjectTaskGraph.tree(tasks).map { it.task }.firstOrNull { it.id in needed && dependenciesDelivered(it, tasks) }
    }

    fun runnable(tasks: List<ProjectTask>, taskId: String): ProjectTask {
        val task = tasks.firstOrNull { it.id == taskId } ?: error("PROJECT_TASK_NOT_FOUND")
        check(!task.isRequirementBranch) { "BRANCH_NOT_EXECUTABLE" }
        check(dependenciesDelivered(task, tasks)) { "PROJECT_FLOW_BLOCKED" }
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

    /** Only the current line's ancestor containers contribute executable context, in outer-to-inner order. */
    fun requirementBranches(task: ProjectTask, tasks: List<ProjectTask>): List<ProjectTask> {
        val byId = tasks.filter { it.projectId == task.projectId && it.workspace == task.workspace }.associateBy { it.id }
        val seen = hashSetOf<String>()
        val branches = mutableListOf<ProjectTask>()
        var current: ProjectTask? = task
        while (current != null && seen.add(current.id)) {
            if (current.isRequirementBranch) branches += current
            current = current.parentId?.let(byId::get)
        }
        return branches.asReversed()
    }

    fun prompt(task: ProjectTask, tasks: List<ProjectTask>, requirements: String,
        member: String = task.owner, ordinal: Int = 1, previous: List<String> = emptyList(),
        projectTools: Boolean = false): String = buildString {
        append("Project requirements:\n").append(requirements)
        for (branch in requirementBranches(task, tasks)) {
            append("\n\nRequirement branch [").append(branch.id).append("]:\n").append(branch.requirement)
        }
        append("\n\nTask ID: ").append(task.id).append("\nTask: ").append(task.title)
        append("\nRequirement:\n").append(task.requirement)
        if (task.supplements.isNotEmpty()) append("\nUser supplements (oldest first, they refine the requirement):\n").append(task.supplements.joinToString("\n"))
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
        // Roles whose tool whitelist drops project_tasks never see instructions for a tool they cannot call.
        if (projectTools) append(" If part of this task needs separate research first, call project_tasks(action=\"branch\") and end this turn at once; the flow runs the branch and then reruns this task with its result. Use project_tasks(action=\"add\") for follow-up subtasks that run after this task, and project_tasks(action=\"outline\", query=\"...\") to look up project tasks line by line.")
    }
}
