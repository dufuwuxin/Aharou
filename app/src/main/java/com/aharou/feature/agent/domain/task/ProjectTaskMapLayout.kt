package com.aharou.feature.agent.domain.task

/** All coordinates are logical floats; the UI never allocates a full-tree sized layout. */
object ProjectTaskMapLayout {
    const val NODE_WIDTH = 192f
    const val NODE_HEIGHT = 72f
    const val MIN_ZOOM = 48f / NODE_HEIGHT
    data class Node(val id: String, val x: Float, val y: Float, val width: Float = NODE_WIDTH, val height: Float = NODE_HEIGHT)
    data class Edge(val from: String, val to: String, val dependency: Boolean = false)
    data class Layout(val nodes: List<Node>, val edges: List<Edge>, val width: Float, val height: Float) {
        fun visible(left: Float, top: Float, right: Float, bottom: Float): List<Node> = nodes.filter {
            it.x + it.width >= left && it.x <= right && it.y + it.height >= top && it.y <= bottom
        }
    }
    fun build(projectId: String, tasks: List<ProjectTask>, expandedId: String? = null,
        expandedWidth: Float = NODE_WIDTH, expandedHeight: Float = NODE_HEIGHT): Layout {
        val root = "project:$projectId"
        val rows = ProjectTaskGraph.tree(tasks)
        val ids = tasks.map { it.id }.toSet()
        val children = rows.map { it.task }.groupBy { if (it.parentId in ids) it.parentId else root }
        fun height(id: String) = if (id == expandedId) maxOf(NODE_HEIGHT, expandedHeight) else NODE_HEIGHT
        fun width(id: String) = if (id == expandedId) maxOf(NODE_WIDTH, expandedWidth) else NODE_WIDTH
        val subtree = mutableMapOf<String, Float>()
        for (id in rows.asReversed().map { it.task.id } + root) {
            val branches = children[id].orEmpty()
            val childHeight = branches.sumOf { subtree.getValue(it.id).toDouble() }.toFloat() + maxOf(0, branches.size - 1) * 32f
            subtree[id] = maxOf(height(id), childHeight)
        }
        val depthWidths = rows.groupBy { it.depth + 1 }.mapValues { (_, level) -> level.maxOf { width(it.task.id) } }
        val xs = mutableMapOf(0 to 16f)
        for (depth in 1..((rows.maxOfOrNull { it.depth } ?: -1) + 1)) {
            xs[depth] = xs.getValue(depth - 1) + (depthWidths[depth - 1] ?: NODE_WIDTH) + 64f
        }
        val tops = mutableMapOf(root to 0f)
        val nodes = mutableListOf<Node>()
        val all = listOf(root to 0) + rows.map { it.task.id to it.depth + 1 }
        for ((id, depth) in all) {
            val top = tops.getValue(id)
            nodes += Node(id, xs.getValue(depth), top + (subtree.getValue(id) - height(id)) / 2f, width(id), height(id))
            val branches = children[id].orEmpty()
            val total = branches.sumOf { subtree.getValue(it.id).toDouble() }.toFloat() + maxOf(0, branches.size - 1) * 32f
            var next = top + (subtree.getValue(id) - total) / 2f
            for (child in branches) { tops[child.id] = next; next += subtree.getValue(child.id) + 32f }
        }
        val edges = rows.flatMap { row ->
            listOf(Edge(row.task.parentId?.takeIf { it in ids } ?: root, row.task.id)) +
                row.task.dependencyIds.filter { it in ids }.map { Edge(it, row.task.id, true) }
        }
        return Layout(nodes, edges, nodes.maxOf { it.x + it.width }, nodes.maxOf { it.y + it.height })
    }
}
