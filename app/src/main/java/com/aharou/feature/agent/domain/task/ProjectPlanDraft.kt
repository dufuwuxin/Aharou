package com.aharou.feature.agent.domain.task

/** Deterministic editable drafts, not AI semantic analysis. Source bodies retain list markers. */
object ProjectPlanDraft {
    private val marker = Regex("^\\s*(?:[-*+•]|\\d+[.)、]|[（(]\\d+[）)])\\s*")
    private val sentence = Regex("(?<=[。！？.!?])(?=\\s|[^\\x00-\\x7F])")

    fun preview(project: ProjectPlan?, text: String): Int =
        if (project == null || project.title.isBlank() || text.isBlank()) 0 else build(project.copy(requirements = text)).size

    fun build(project: ProjectPlan): List<ProjectTask> {
        require(project.title.isNotBlank() && project.requirements.isNotBlank()) { "REQUIRED_FIELDS" }
        val lines = project.requirements.lines()
        fun indentation(line: String) = line.takeWhile { it == ' ' || it == '\t' }
            .fold(0) { total, char -> total + if (char == '\t') 4 else 1 }
        val marked = lines.indices.filter { marker.containsMatchIn(lines[it]) }
        if (marked.isEmpty()) {
            val allocation = Regex("(?:创建|生成|分成|拆成|拆分成|拆分为|划分为|创作|编写|制作)\\s*([0-9]+|[零〇一二两三四五六七八九十百千万]+)\\s*((?:个|份|本|篇|项|套|张|部|首|封|支|段|种|款|组)[^\\s，,。；;：:！？!?]*)")
                .find(project.requirements)
            if (allocation != null) {
                val count = allocation.groupValues[1]
                val multiple = count.toLongOrNull()?.let { it > 1 }
                    ?: count.any { it in "二两三四五六七八九十百千万" }
                val target = allocation.groupValues[2]
                val sharedQualifier = Regex("每(?:一)?(?:${Regex.escape(target)}|${Regex.escape(target.take(1))}(?=都|需|应|须|必须|包含|提供|独立|[，,：:])|项(?=都|需|应|须|必须|包含|提供|独立|[，,：:]))")
                val qualifier = sharedQualifier.find(project.requirements)
                val beforeQualifier = project.requirements.take(qualifier?.range?.first ?: project.requirements.length)
                val numberedItem = Regex("[（(]?(?:[0-9]+|[一二三四五六七八九十]+)[.)、）]")
                val explicitItems = Regex("分别(?:为|是)\\s*[:：]?").find(project.requirements)?.let {
                    numberedItem.findAll(project.requirements.substring(it.range.last + 1)).take(2).count() == 2
                } == true
                val inlineList = explicitItems || Regex("分别(?:为|是)?\\s*[:：]?\\s*[^，,。；;]+(?:和|与|及|、)[^，,。；;]+")
                    .containsMatchIn(project.requirements) || numberedItem.findAll(beforeQualifier).take(2).count() == 2
                if (multiple && !inlineList && (qualifier != null || target in listOf("个任务", "项任务")))
                    return emptyList()
            }
        }
        val bodies = marked.associateWith { lines[it] }.toMutableMap()
        val sharedLines = mutableListOf<String>()
        val rootIndent = marked.minOfOrNull { indentation(lines[it]) } ?: 0
        if (marked.isNotEmpty()) lines.forEachIndexed { index, line ->
            if (index in bodies || line.isBlank()) return@forEachIndexed
            val indent = indentation(line)
            if (index < marked.first() || index > marked.last() && indent <= rootIndent) sharedLines += line
            else {
                val owner = marked.lastOrNull { it < index &&
                    (if (indent > rootIndent) indentation(lines[it]) < indent else indentation(lines[it]) <= indent) }
                    ?: marked.last { it < index }
                bodies[owner] = bodies.getValue(owner) + "\n" + line
            }
        }
        val shared = sharedLines.joinToString("\n")
        val pieces = (if (marked.isEmpty()) lines.filter { it.isNotBlank() } else marked.map(bodies::getValue)).flatMap { line ->
            if (marker.containsMatchIn(line)) listOf(line)
            else {
                val indentation = line.takeWhile { it == ' ' || it == '\t' }
                line.trimStart().split(sentence).filter { it.isNotBlank() }.map { indentation + it.trim() }
            }
        }
        val parents = java.util.ArrayDeque<Pair<Int, String>>()
        return pieces.mapIndexed { index, body ->
            val indent = body.takeWhile { it == ' ' || it == '\t' }.fold(0) { total, char -> total + if (char == '\t') 4 else 1 }
            while (parents.isNotEmpty() && checkNotNull(parents.peekLast()).first >= indent) parents.removeLast()
            val title = body.lineSequence().first().replaceFirst(marker, "").trim().take(120)
            val task = ProjectTask(workspace = project.workspace, projectId = project.id,
                title = title.ifBlank { body.trim().take(120) }, requirement = body, background = shared,
                parentId = parents.peekLast()?.second, order = project.createdAt + index)
            parents.addLast(indent to task.id)
            task
        }
    }
}
