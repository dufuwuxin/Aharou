package com.aharou.feature.agent.domain.task

/** All coordinates are logical floats; the UI never allocates a full-tree sized layout. */
object ProjectTaskMapLayout {
    const val NODE_WIDTH = 192f
    const val NODE_HEIGHT = 72f
    const val MIN_ZOOM = 48f / NODE_HEIGHT
    data class Node(val id: String, val x: Float, val y: Float)
    data class Edge(val from: String, val to: String, val dependency: Boolean = false)
    data class Layout(val nodes: List<Node>, val edges: List<Edge>, val width: Float, val height: Float) {
        fun visible(left: Float, top: Float, right: Float, bottom: Float): List<Node> = nodes.filter {
            it.x + NODE_WIDTH >= left && it.x <= right && it.y + NODE_HEIGHT >= top && it.y <= bottom
        }
    }
    fun build(projectId: String, tasks: List<ProjectTask>): Layout {
        val root = "project:$projectId"
        val rows = ProjectTaskGraph.tree(tasks)
        val ids = tasks.map { it.id }.toSet()
        val children = tasks.groupBy { if (it.parentId in ids) it.parentId else root }
        val ys = mutableMapOf<String, Float>()
        var leaf = 0
        // Reverse iterative preorder is a postorder suitable for child-centred parents.
        for (row in rows.asReversed()) {
            val childYs = children[row.task.id].orEmpty().mapNotNull { ys[it.id] }
            ys[row.task.id] = if (childYs.isEmpty()) (leaf++ * 104).toFloat()
                else (childYs.minOrNull()!! + childYs.maxOrNull()!!) / 2f
        }
        val roots = children[root].orEmpty().mapNotNull { ys[it.id] }
        ys[root] = if (roots.isEmpty()) 0f else (roots.minOrNull()!! + roots.maxOrNull()!!) / 2f
        val nodes = listOf(Node(root, 16f, ys.getValue(root))) + rows.map { Node(it.task.id, (it.depth + 1) * 256f + 16f, ys.getValue(it.task.id)) }
        val edges = rows.flatMap { row ->
            listOf(Edge(row.task.parentId?.takeIf { it in ids } ?: root, row.task.id)) +
                row.task.dependencyIds.filter { it in ids }.map { Edge(it, row.task.id, true) }
        }
        return Layout(nodes, edges, (nodes.maxOfOrNull { it.x } ?: 0f) + NODE_WIDTH,
            (nodes.maxOfOrNull { it.y } ?: 0f) + NODE_HEIGHT)
    }
}
