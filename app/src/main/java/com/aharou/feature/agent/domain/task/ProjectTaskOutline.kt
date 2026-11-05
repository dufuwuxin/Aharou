package com.aharou.feature.agent.domain.task

/**
 * One-line view of a task's content, shared by the mind map (one dot per line, folded or unfolded)
 * and the member tool, so what users see on the map and what members look up stay the same.
 */
object ProjectTaskOutline {
    /** Declaration order is display order. */
    enum class Kind { REQUIREMENT, SUPPLEMENT, CONSTRAINT, ACCEPTANCE, BACKGROUND, OWNER, MEMBER, DEPENDENCY, RESULT }

    /** [taskId] is set only for [Kind.DEPENDENCY] and points at the upstream task. */
    data class Line(val kind: Kind, val text: String, val taskId: String? = null)

    /** A null [line] means the task title matched. */
    data class Hit(val taskId: String, val line: Line?)

    private val marker = Regex("^\\s*(?:[-*+•·]|\\d+[.)、](?!\\d)|[（(]\\d+[）)])\\s*")
    private val sentence = Regex("(?<=[。！？.!?])(?=\\s|[^\\x00-\\x7F])")
    private val noise = Regex("^\\s*(?:```.*|\\|?\\s*:?-{3,}.*)$")

    fun of(task: ProjectTask, tasks: List<ProjectTask>): List<Line> = lines(task, tasks.associate { it.id to it.title })

    private fun lines(task: ProjectTask, titles: Map<String, String>): List<Line> =
        buildList {
            sentences(task.requirement).forEach { add(Line(Kind.REQUIREMENT, it)) }
            task.supplements.map(String::trim).filter(String::isNotEmpty).forEach { add(Line(Kind.SUPPLEMENT, it)) }
            task.constraints.flatMap { sentences(it) }.forEach { add(Line(Kind.CONSTRAINT, it)) }
            sentences(task.acceptance).forEach { add(Line(Kind.ACCEPTANCE, it)) }
            sentences(task.background).forEach { add(Line(Kind.BACKGROUND, it)) }
            task.owner.trim().takeIf(String::isNotEmpty)?.let { add(Line(Kind.OWNER, it)) }
            task.members.map(String::trim).filter(String::isNotEmpty).forEach { add(Line(Kind.MEMBER, it)) }
            task.dependencyIds.forEach { id -> titles[id]?.let { add(Line(Kind.DEPENDENCY, it, id)) } }
            sentences(task.brief).forEach { add(Line(Kind.RESULT, it)) }
        }

    /**
     * Case-insensitive substring match on titles and outline lines; a blank query finds nothing. Dependency lines are
     * skipped: they carry the upstream title, which already matches as the upstream task's own hit.
     */
    fun find(tasks: List<ProjectTask>, query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val titles = tasks.associate { it.id to it.title }
        return tasks.flatMap { task ->
            buildList {
                if (task.title.contains(q, ignoreCase = true)) add(Hit(task.id, null))
                lines(task, titles).filter { it.kind != Kind.DEPENDENCY && it.text.contains(q, ignoreCase = true) }
                    .forEach { add(Hit(task.id, it)) }
            }
        }
    }

    internal fun sentences(text: String): List<String> = text.lines()
        .filterNot { noise.matches(it) }
        .flatMap { it.replace(marker, "").split(sentence) }
        .map(String::trim)
        .filter(String::isNotEmpty)
}
