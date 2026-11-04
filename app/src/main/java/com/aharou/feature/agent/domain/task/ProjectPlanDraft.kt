package com.aharou.feature.agent.domain.task

/** Deterministic editable drafts, not AI semantic analysis. Source bodies retain list markers. */
object ProjectPlanDraft {
    private val marker = Regex("^\\s*(?:[-*+•]|\\d+[.)、]|[（(]\\d+[）)])\\s*")
    private val sentence = Regex("(?<=[。！？.!?])(?=\\s|[^\\x00-\\x7F])")

    fun build(project: ProjectPlan): List<ProjectTask> {
        require(project.title.isNotBlank() && project.requirements.isNotBlank()) { "REQUIRED_FIELDS" }
        val lines = project.requirements.lines()
        val pieces = lines.filter { it.isNotBlank() }.flatMap { line ->
            if (marker.containsMatchIn(line)) listOf(line)
            else {
                val indentation = line.takeWhile { it == ' ' || it == '\t' }
                line.trimStart().split(sentence).filter { it.isNotBlank() }.map { indentation + it.trim() }
            }
        }
        val parents = java.util.ArrayDeque<Pair<Int, String>>()
        return pieces.mapIndexed { index, body ->
            val indent = body.takeWhile { it == ' ' || it == '\t' }.fold(0) { total, char -> total + if (char == '\t') 4 else 1 }
            while (parents.isNotEmpty() && parents.peekLast().first >= indent) parents.removeLast()
            val title = body.replaceFirst(marker, "").trim().take(120)
            val task = ProjectTask(workspace = project.workspace, projectId = project.id,
                title = title.ifBlank { body.trim().take(120) }, requirement = body,
                parentId = parents.peekLast()?.second, order = project.createdAt + index)
            parents.addLast(indent to task.id)
            task
        }
    }
}
