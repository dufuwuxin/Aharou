package com.aharou.feature.agent.domain.task

/**
 * Horizontal mind-map layout in logical dp floats; the UI never allocates a full-tree sized surface.
 * Gap defaults mirror the UI Spacing tokens (lg = 16, 2 × xxl = 64); the UI passes the token values explicitly.
 */
object ProjectTaskMapLayout {
    const val NODE_MIN_WIDTH = 136f
    const val NODE_MAX_WIDTH = 232f
    /** Fits two title lines plus one status line. */
    const val NODE_HEIGHT = 72f
    /** At this zoom a node is still a 48dp touch target. */
    const val MIN_ZOOM = 48f / NODE_HEIGHT
    /** labelMedium font size; the UI passes the real (font-scaled) value. */
    const val DEFAULT_GLYPH = 12f
    const val SIBLING_GAP = 16f
    const val LEVEL_GAP = 64f
    const val MARGIN = 16f
    /** Padding, status bar and rounding slack a node adds around its title text. */
    private const val NODE_CHROME = 32f
    private const val NARROW_GLYPH_EM = 0.55f
    private const val WRAP_SLACK = 1.1f
    private const val WIDE_GLYPH_START = 0x2E80
    private const val MIN_BEND = 24f
    private const val LOOP_BULGE = 32f

    data class Node(val id: String, val x: Float, val y: Float, val width: Float, val height: Float = NODE_HEIGHT,
        val root: Boolean = false)
    data class Edge(val from: String, val to: String, val dependency: Boolean = false)
    /** Cubic Bézier (start, two control points, end); its convex hull bounds the drawn curve. */
    data class Curve(val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val x2: Float, val y2: Float, val x3: Float, val y3: Float) {
        fun intersects(left: Float, top: Float, right: Float, bottom: Float): Boolean =
            maxOf(maxOf(x0, x1), maxOf(x2, x3)) >= left && minOf(minOf(x0, x1), minOf(x2, x3)) <= right &&
                maxOf(maxOf(y0, y1), maxOf(y2, y3)) >= top && minOf(minOf(y0, y1), minOf(y2, y3)) <= bottom
    }
    data class Layout(val nodes: List<Node>, val edges: List<Edge>, val width: Float, val height: Float) {
        private val byId by lazy { nodes.associateBy { it.id } }
        fun node(id: String?): Node? = id?.let { byId[it] }
        fun visible(left: Float, top: Float, right: Float, bottom: Float): List<Node> = nodes.filter {
            it.x + it.width >= left && it.x <= right && it.y + it.height >= top && it.y <= bottom
        }
        /** Forward edges leave the source's right edge and enter the target's left edge with horizontal tangents;
         *  same-column or backward dependencies enter the target's right edge, bulging outside both nodes. */
        fun curve(edge: Edge): Curve? {
            val from = byId[edge.from] ?: return null
            val to = byId[edge.to] ?: return null
            val startX = from.x + from.width
            val startY = from.y + from.height / 2f
            val endY = to.y + to.height / 2f
            if (to.x >= startX) {
                val bend = maxOf(MIN_BEND, (to.x - startX) / 2f)
                return Curve(startX, startY, startX + bend, startY, to.x - bend, endY, to.x, endY)
            }
            val endX = to.x + to.width
            val bulge = maxOf(startX, endX) + LOOP_BULGE
            return Curve(startX, startY, bulge, startY, bulge, endY, endX, endY)
        }
    }

    fun rootId(projectId: String) = "project:$projectId"

    /** Width that fits [title] in at most two lines: wide (CJK) glyphs count as one em, others as about half. */
    fun nodeWidth(title: String, glyph: Float = DEFAULT_GLYPH): Float {
        var ems = 0f
        for (ch in title) ems += if (ch.code >= WIDE_GLYPH_START) 1f else NARROW_GLYPH_EM
        val oneLine = ems * glyph + NODE_CHROME
        val fitted = if (oneLine <= NODE_MAX_WIDTH) oneLine else ems * glyph / 2f * WRAP_SLACK + NODE_CHROME
        return fitted.coerceIn(NODE_MIN_WIDTH, NODE_MAX_WIDTH)
    }

    /** [expandedId] reserves at least [expandedWidth] × [expandedHeight] for one oversized node. */
    fun build(projectId: String, tasks: List<ProjectTask>, expandedId: String? = null,
        expandedWidth: Float = 0f, expandedHeight: Float = 0f, rootTitle: String = "",
        glyph: Float = DEFAULT_GLYPH, rootGlyph: Float = glyph, siblingGap: Float = SIBLING_GAP,
        levelGap: Float = LEVEL_GAP, margin: Float = MARGIN): Layout {
        val root = rootId(projectId)
        val rows = ProjectTaskGraph.tree(tasks)
        val ids = tasks.map { it.id }.toSet()
        val children = rows.map { it.task }.groupBy { if (it.parentId in ids) it.parentId else root }
        val widths = HashMap<String, Float>(rows.size + 1)
        widths[root] = nodeWidth(rootTitle, rootGlyph)
        rows.forEach { widths[it.task.id] = nodeWidth(it.task.title, glyph) }
        if (expandedId != null && expandedId in widths) widths[expandedId] = maxOf(widths.getValue(expandedId), expandedWidth)
        fun width(id: String) = widths.getValue(id)
        fun height(id: String) = if (id == expandedId) maxOf(NODE_HEIGHT, expandedHeight) else NODE_HEIGHT
        fun span(branches: List<ProjectTask>, subtree: Map<String, Float>) =
            branches.sumOf { subtree.getValue(it.id).toDouble() }.toFloat() + maxOf(0, branches.size - 1) * siblingGap
        val subtree = HashMap<String, Float>(rows.size + 1)
        for (id in rows.asReversed().map { it.task.id } + root) {
            subtree[id] = maxOf(height(id), span(children[id].orEmpty(), subtree))
        }
        val depthWidths = rows.groupBy { it.depth + 1 }.mapValues { (_, level) -> level.maxOf { width(it.task.id) } } +
            (0 to width(root))
        val xs = mutableMapOf(0 to margin)
        for (depth in 1..((rows.maxOfOrNull { it.depth } ?: -1) + 1)) {
            xs[depth] = xs.getValue(depth - 1) + (depthWidths[depth - 1] ?: 0f) + levelGap
        }
        val tops = mutableMapOf(root to margin)
        val nodes = ArrayList<Node>(rows.size + 1)
        val all = listOf(root to 0) + rows.map { it.task.id to it.depth + 1 }
        for ((id, depth) in all) {
            val top = tops.getValue(id)
            nodes += Node(id, xs.getValue(depth), top + (subtree.getValue(id) - height(id)) / 2f, width(id), height(id), id == root)
            val branches = children[id].orEmpty()
            var next = top + (subtree.getValue(id) - span(branches, subtree)) / 2f
            for (child in branches) { tops[child.id] = next; next += subtree.getValue(child.id) + siblingGap }
        }
        val edges = rows.flatMap { row ->
            listOf(Edge(row.task.parentId?.takeIf { it in ids } ?: root, row.task.id)) +
                row.task.dependencyIds.filter { it in ids }.map { Edge(it, row.task.id, true) }
        }
        return Layout(nodes, edges, nodes.maxOf { it.x + it.width } + margin, nodes.maxOf { it.y + it.height } + margin)
    }
}
