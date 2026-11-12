package com.aharou.feature.agent.domain.context

/** A projection of the caller's visible entries, never an activation or permission engine. */
data class ContextMemoryGraph(
    val nodes: List<ContextEntry>,
    val edges: List<ContextMemoryEdge>,
) {
    private val edgesBySource by lazy { edges.groupBy { it.sourceId } }

    fun page(index: Int, nodeLimit: Int = DEFAULT_NODE_LIMIT): ContextMemoryGraphPage {
        require(nodeLimit in 1..MAX_NODE_LIMIT)
        val count = ((nodes.size + nodeLimit - 1) / nodeLimit).coerceAtLeast(1)
        val current = index.coerceIn(0, count - 1)
        val start = current * nodeLimit
        val visible = nodes.subList(start, (start + nodeLimit).coerceAtMost(nodes.size))
        // Page by source so every edge remains reachable, including off-page and unresolved targets.
        return ContextMemoryGraphPage(visible, visible.flatMap { edgesBySource[it.id].orEmpty() }, current, count)
    }

    companion object {
        const val DEFAULT_NODE_LIMIT = 8
        const val MAX_NODE_LIMIT = 16

        fun project(entries: List<ContextEntry>): ContextMemoryGraph {
            val nodes = entries.distinctBy { it.id }.sortedBy { it.id }
            val index = ContextGraphIdentity.Index(nodes)
            val edges = nodes.flatMap { source ->
                source.worldbook.relations.distinct().map { relation ->
                    val matches = index.matches(relation.target)
                    val target = matches.singleOrNull()
                    ContextMemoryEdge(source.id, relation.target, relation.type, target?.id,
                        if (target != null) ContextMemoryResolution.RESOLVED
                        else if (matches.size > 1) ContextMemoryResolution.AMBIGUOUS
                        else ContextMemoryResolution.MISSING)
                }
            }.distinct()
            return ContextMemoryGraph(nodes, edges)
        }
    }
}

enum class ContextMemoryResolution { RESOLVED, AMBIGUOUS, MISSING }

data class ContextMemoryEdge(
    val sourceId: String,
    val target: String,
    val type: WorldbookRelationType,
    val targetId: String?,
    val resolution: ContextMemoryResolution,
)

data class ContextMemoryGraphPage(
    val nodes: List<ContextEntry>,
    val edges: List<ContextMemoryEdge>,
    val index: Int,
    val count: Int,
)
