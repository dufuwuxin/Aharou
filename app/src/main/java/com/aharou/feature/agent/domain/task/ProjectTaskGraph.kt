package com.aharou.feature.agent.domain.task

object ProjectTaskGraph {
    data class ContextTask(val task: ProjectTask? = null, val unavailable: Boolean = false)
    fun currentContextTask(sessionId: String?, workspace: String, ghost: Boolean, read: () -> List<ProjectTask>): ContextTask {
        if (ghost || sessionId == null || workspace.isBlank()) return ContextTask()
        return try { ContextTask(read().singleOrNull { it.workspace == workspace && sessionId in it.sessionIds }) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { ContextTask(unavailable = true) }
    }

    fun executionStatus(outcome: ProjectTaskExecutionOutcome, requirementsChanged: Boolean): ProjectTaskStatus = when {
        outcome != ProjectTaskExecutionOutcome.SUCCEEDED -> ProjectTaskStatus.BLOCKED
        requirementsChanged -> ProjectTaskStatus.READY
        else -> ProjectTaskStatus.REVIEW
    }
    fun recoverInterrupted(task: ProjectTask): ProjectTask = if (task.status == ProjectTaskStatus.RUNNING)
        task.copy(status = ProjectTaskStatus.BLOCKED, executionReason = "INTERRUPTED_PREVIOUS_PROCESS") else task

    data class Row(val task: ProjectTask, val depth: Int)

    fun validate(tasks: List<ProjectTask>): String? {
        val byId = tasks.associateBy { it.id }
        if (byId.size != tasks.size) return "DUPLICATE_TASK"
        val sessions = hashSetOf<String>()
        for (task in tasks) {
            if (task.id.isBlank() || task.workspace.isBlank() || task.title.isBlank() || task.requirement.isBlank()) return "REQUIRED_FIELDS"
            if (task.sessionIds.any { it.isBlank() || !sessions.add(it) }) return "SESSION_ALREADY_LINKED"
            if (task.dependencyIds.distinct().size != task.dependencyIds.size) return "DUPLICATE_DEPENDENCY"
            for (id in task.dependencyIds) {
                val dependency = byId[id] ?: return "DEPENDENCY_NOT_FOUND"
                if (dependency.projectId != task.projectId) return "CROSS_PROJECT_DEPENDENCY"
                if (dependency.workspace != task.workspace) return "CROSS_WORKSPACE_DEPENDENCY"
            }
            val parent = task.parentId?.let { byId[it] ?: return "PARENT_NOT_FOUND" }
            if (parent != null && parent.projectId != task.projectId) return "CROSS_PROJECT_PARENT"
            if (parent != null && parent.workspace != task.workspace) return "CROSS_WORKSPACE"
            if (task.status == ProjectTaskStatus.DONE && (task.acceptance.isBlank() || task.evidenceRefs.isEmpty() ||
                    task.acceptedRequirementsVersion != task.requirementsVersion)) return "ACCEPTANCE_REQUIRED"
        }
        if (tasks.any { it.status == ProjectTaskStatus.DONE && !dependenciesReady(it, tasks) }) return "DEPENDENCY_ACCEPTANCE_REQUIRED"
        // Iterative colouring handles deep trees without recursive stack growth.
        val done = hashSetOf<String>()
        for (task in tasks) {
            val path = hashSetOf<String>()
            var cursor: ProjectTask? = task
            while (cursor != null && cursor.id !in done) {
                if (!path.add(cursor.id)) return "TASK_CYCLE"
                cursor = cursor.parentId?.let(byId::get)
            }
            done.addAll(path)
        }
        val remaining = tasks.associate { it.id to it.dependencyIds.size }.toMutableMap()
        val dependents = tasks.flatMap { task -> task.dependencyIds.map { it to task.id } }.groupBy({ it.first }, { it.second })
        val ready = java.util.ArrayDeque<String>(); remaining.filterValues { it == 0 }.keys.forEach { ready.add(it) }
        var count = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst(); count++
            dependents[id].orEmpty().forEach { next -> remaining[next] = remaining.getValue(next) - 1; if (remaining[next] == 0) ready.add(next) }
        }
        if (count != tasks.size) return "DEPENDENCY_CYCLE"
        return null
    }

    fun invalidateDependents(tasks: List<ProjectTask>, changedId: String, firstVersion: Long): List<ProjectTask> {
        val dependents = tasks.flatMap { task -> task.dependencyIds.map { it to task.id } }.groupBy({ it.first }, { it.second })
        val affected = hashSetOf<String>()
        val queue = java.util.ArrayDeque<String>(); queue.add(changedId)
        while (queue.isNotEmpty()) dependents[queue.removeFirst()].orEmpty().forEach { id ->
            if (id != changedId && affected.add(id)) queue.add(id)
        }
        var version = firstVersion
        return tasks.map { task -> if (task.id !in affected) task else task.copy(
            version = ++version, requirementsVersion = task.requirementsVersion + 1,
            acceptedRequirementsVersion = null, executionReason = "UPSTREAM_CHANGED",
            status = if (task.status in setOf(ProjectTaskStatus.RUNNING, ProjectTaskStatus.REVIEW, ProjectTaskStatus.DONE)) ProjectTaskStatus.READY else task.status)
        }
    }

    fun executionReady(task: ProjectTask, sessionId: String, tasks: List<ProjectTask>): Boolean =
        dependenciesReady(task, tasks) && task.executionRequirementsVersions[sessionId] == task.requirementsVersion

    fun dependenciesReady(task: ProjectTask, tasks: List<ProjectTask>): Boolean = task.dependencyIds.all { id ->
        tasks.any { it.id == id && it.workspace == task.workspace && it.projectId == task.projectId && it.status == ProjectTaskStatus.DONE &&
            it.acceptedRequirementsVersion == it.requirementsVersion && it.acceptance.isNotBlank() && it.evidenceRefs.isNotEmpty() }
    }

    fun visibleTree(tasks: List<ProjectTask>, query: String, status: ProjectTaskStatus?, collapsed: Set<String>): List<Row> {
        val filtered = query.isNotBlank() || status != null
        val byId = tasks.associateBy { it.id }
        val keep = mutableSetOf<String>()
        if (filtered) tasks.filter { (status == null || it.status == status) &&
            (query.isBlank() || it.title.contains(query, true) || it.requirement.contains(query, true) || it.owner.contains(query, true)) }.forEach { task ->
            var cursor: ProjectTask? = task
            val seen = mutableSetOf<String>()
            while (cursor != null && seen.add(cursor.id)) { keep += cursor.id; cursor = cursor.parentId?.let(byId::get) }
        }
        val result = mutableListOf<Row>(); var hiddenDepth: Int? = null
        for (row in tree(tasks)) {
            if (filtered) { if (row.task.id in keep) result += row; continue }
            if (hiddenDepth != null && row.depth > hiddenDepth) continue
            hiddenDepth = null; result += row
            if (row.task.id in collapsed) hiddenDepth = row.depth
        }
        return result
    }

    fun revise(old: ProjectTask?, proposed: ProjectTask, version: Long): ProjectTask {
        require(old == null || (old.id == proposed.id && old.workspace == proposed.workspace && old.projectId == proposed.projectId))
        val changed = old != null && (old.requirement != proposed.requirement || old.background != proposed.background ||
            old.acceptance != proposed.acceptance || old.constraints != proposed.constraints || old.dependencyIds != proposed.dependencyIds)
        return proposed.copy(version = version, createdAt = old?.createdAt ?: proposed.createdAt,
            order = old?.order ?: proposed.order,
            requirementsVersion = (old?.requirementsVersion ?: 1) + if (changed) 1 else 0,
            acceptedRequirementsVersion = if (changed) null else proposed.acceptedRequirementsVersion,
            executionReason = if (changed) "REQUIREMENTS_CHANGED" else proposed.executionReason,
            status = if (changed && proposed.status in setOf(ProjectTaskStatus.DONE, ProjectTaskStatus.REVIEW, ProjectTaskStatus.RUNNING)) ProjectTaskStatus.READY else proposed.status)
    }

    fun tree(tasks: List<ProjectTask>): List<Row> {
        val ids = tasks.map { it.id }.toSet()
        val children = tasks.groupBy { it.parentId }
        val sorted = compareBy<ProjectTask> { it.order }.thenBy { it.id }
        val stack = java.util.ArrayDeque<Row>()
        tasks.filter { it.parentId == null || it.parentId !in ids }.sortedWith(sorted).asReversed().forEach { stack.push(Row(it, 0)) }
        val result = mutableListOf<Row>()
        val seen = hashSetOf<String>()
        while (stack.isNotEmpty()) {
            val row = stack.pop()
            if (!seen.add(row.task.id)) continue
            result += row
            children[row.task.id].orEmpty().sortedWith(sorted).asReversed().forEach { stack.push(Row(it, row.depth + 1)) }
        }
        // Corrupt cyclic records remain visible but cannot hang the UI.
        tasks.sortedWith(sorted).filter { it.id !in seen }.forEach { result += Row(it, 0) }
        return result
    }
}
