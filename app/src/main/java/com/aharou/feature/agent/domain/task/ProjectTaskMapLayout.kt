package com.aharou.feature.agent.domain.task

/**
 * Horizontal mind-map layout in logical dp floats. Every node is a "dot + one-line title" header row; an unfolded
 * node grows downward to its outline height and pushes the rest of its column (with whole subtrees) down.
 * The UI never allocates a full-tree sized surface. Gap defaults mirror the UI Spacing tokens (sm = 8,
 * 2 × xxl = 64, lg = 16); the UI passes the token values explicitly.
 */
object ProjectTaskMapLayout {
    /** Logical header height; zooming out trades detail for an overview. */
    const val NODE_HEIGHT = 64f
    const val MIN_ZOOM = 0.01f

    fun fitZoom(viewWidth: Float, viewHeight: Float, width: Float, height: Float, maxZoom: Float = 2f): Float {
        if (listOf(viewWidth, viewHeight, width, height).any { !it.isFinite() || it <= 0f }) return 1f
        return minOf(viewWidth / width, viewHeight / height, maxZoom).coerceAtLeast(java.lang.Float.MIN_NORMAL)
    }

    fun minimumZoom(fit: Float): Float =
        if (fit.isFinite() && fit > 0f) minOf(MIN_ZOOM, fit / 8f).coerceAtLeast(java.lang.Float.MIN_NORMAL) else MIN_ZOOM

    fun clampZoom(target: Float, minimum: Float, maximum: Float = 2f): Float {
        val lower = if (minimum.isFinite() && minimum > 0f) minimum.coerceAtMost(maximum) else MIN_ZOOM
        return if (target.isFinite()) target.coerceIn(lower, maximum) else lower
    }
    const val NODE_MIN_WIDTH = 120f
    const val NODE_MAX_WIDTH = 280f
    /** Minimum width of an unfolded node so its outline lines stay readable. */
    const val PANEL_WIDTH = 300f
    /** labelLarge font size; the UI passes the real (font-scaled) value. */
    const val DEFAULT_GLYPH = 14f
    const val SIBLING_GAP = 8f
    const val LEVEL_GAP = 64f
    const val MARGIN = 16f
    /** Status dot centre measured from the node's left edge (half of the 32dp dot column). */
    const val DOT_INSET = 16f
    /** Logical radius of a task's status dot; branches end on its left edge so hollow dots stay clean. */
    const val DOT_RADIUS = 5f
    /** Fork point right of the node's widest part, where branches to children and dependants fan out. */
    const val FORK_GAP = 16f
    /** Dot column, gaps, count badge, arrow and end padding around the title text. */
    private const val HEADER_CHROME = 96f
    private const val NARROW_GLYPH_EM = 0.55f
    private const val WIDE_GLYPH_START = 0x2E80
    private const val MIN_BEND = 24f
    private const val LOOP_BULGE = 32f

    data class Node(val id: String, val x: Float, val y: Float, val width: Float, val height: Float,
        val headerWidth: Float, val root: Boolean = false) {
        val dotX: Float get() = x + DOT_INSET
        val dotY: Float get() = y + NODE_HEIGHT / 2f
        val forkX: Float get() = x + width + FORK_GAP
    }
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
        /** Starts at the source's fork point and ends on the target's status dot (its left edge), arriving horizontally.
         *  Same-column or backward dependencies end at the target's fork point, bulging right of both nodes. */
        fun curve(edge: Edge): Curve? {
            val from = byId[edge.from] ?: return null
            val to = byId[edge.to] ?: return null
            val sx = from.forkX
            val sy = from.dotY
            val ey = to.dotY
            val dotEdge = to.dotX - DOT_RADIUS
            if (dotEdge > sx) {
                val bend = maxOf(MIN_BEND, (dotEdge - sx) / 2f)
                return Curve(sx, sy, sx + bend, sy, dotEdge - bend, ey, dotEdge, ey)
            }
            val ex = to.forkX
            val bulge = maxOf(sx, ex) + LOOP_BULGE
            return Curve(sx, sy, bulge, sy, bulge, ey, ex, ey)
        }
    }

    fun rootId(projectId: String) = "project:$projectId"

    /** Text width in ems: wide (CJK) glyphs count as one em, others as about half. */
    fun ems(text: String): Float {
        var ems = 0f
        for (ch in text) ems += if (ch.code >= WIDE_GLYPH_START) 1f else NARROW_GLYPH_EM
        return ems
    }

    /** Header row width for a one-line [title]; longer titles are ellipsized by the UI. */
    fun headerWidth(title: String, glyph: Float = DEFAULT_GLYPH): Float =
        (ems(title) * glyph + HEADER_CHROME).coerceIn(NODE_MIN_WIDTH, NODE_MAX_WIDTH)

    /**
     * [heights] maps unfolded node ids to their full height (measured or estimated). Those nodes are at least
     * [panelWidth] wide; their header stays on top, aligned with the centre of their children when there is room.
     */
    fun build(projectId: String, tasks: List<ProjectTask>, heights: Map<String, Float> = emptyMap(),
        rootTitle: String = "", glyph: Float = DEFAULT_GLYPH, rootGlyph: Float = glyph,
        panelWidth: Float = PANEL_WIDTH, siblingGap: Float = SIBLING_GAP, levelGap: Float = LEVEL_GAP,
        margin: Float = MARGIN, maxHeaderWidth: Float = NODE_MAX_WIDTH): Layout {
        val root = rootId(projectId)
        val rows = ProjectTaskGraph.tree(tasks)
        val ids = tasks.map { it.id }.toSet()
        val children = rows.map { it.task }.groupBy { if (it.parentId in ids) it.parentId else root }
        val headers = HashMap<String, Float>(rows.size + 1)
        val headerLimit = maxHeaderWidth.coerceIn(NODE_MIN_WIDTH, NODE_MAX_WIDTH)
        headers[root] = headerWidth(rootTitle, rootGlyph).coerceAtMost(headerLimit)
        rows.forEach { headers[it.task.id] = headerWidth(it.task.title, glyph).coerceAtMost(headerLimit) }
        fun unfolded(id: String) = id != root && heights[id] != null
        fun width(id: String) = if (unfolded(id)) maxOf(headers.getValue(id), panelWidth) else headers.getValue(id)
        fun height(id: String) = if (unfolded(id)) maxOf(NODE_HEIGHT, heights.getValue(id)) else NODE_HEIGHT
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
            val branches = children[id].orEmpty()
            val total = span(branches, subtree)
            val h = height(id)
            // Both the node box and the children block stay inside [top, top + subtree], so blocks never overlap.
            val y = if (total > h) minOf(top + total / 2f - NODE_HEIGHT / 2f, top + total - h) else top
            nodes += Node(id, xs.getValue(depth), y, width(id), h, headers.getValue(id), id == root)
            var next = if (total > h) top else maxOf(top, top + NODE_HEIGHT / 2f - total / 2f)
            for (child in branches) { tops[child.id] = next; next += subtree.getValue(child.id) + siblingGap }
        }
        val edges = rows.flatMap { row ->
            listOf(Edge(row.task.parentId?.takeIf { it in ids } ?: root, row.task.id)) +
                row.task.dependencyIds.filter { it in ids }.map { Edge(it, row.task.id, true) }
        }
        return Layout(nodes, edges, nodes.maxOf { it.forkX } + margin, nodes.maxOf { it.y + it.height } + margin)
    }
}
