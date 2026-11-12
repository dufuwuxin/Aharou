package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMemoryGraphTest {
    private fun entry(id: String, title: String = id, scope: MemoryScope = MemoryScope.GLOBAL,
                      relations: List<WorldbookRelation> = emptyList()) = ContextEntry(
        id, title, "summary", "body", scope, ContextKind.REFERENCE, ContextLoad.SEARCH,
        ContextOrigin.MEMORY, id, worldbook = WorldbookRules(relations = relations),
    )

    @Test fun duplicateHistoricalAliasStillResolvesExactlyOneEntity() {
        val target = ContextEntry("target", "renamed", "summary", "body", MemoryScope.GLOBAL,
            ContextKind.REFERENCE, ContextLoad.SEARCH, ContextOrigin.MEMORY, "source",
            graphAliases = listOf("old", "old"))
        val source = target.copy(id = "source", title = "source", graphAliases = emptyList(),
            worldbook = WorldbookRules(relations = listOf(WorldbookRelation("old"))))
        val edge = ContextMemoryGraph.project(listOf(source, target)).edges.single()
        assertEquals(ContextMemoryResolution.RESOLVED, edge.resolution)
        assertEquals(target.id, edge.targetId)
    }

    @Test fun exactIdWinsOverTitleAndUniqueTitleResolves() {
        val source = entry("source", relations = listOf(WorldbookRelation("target"), WorldbookRelation("Unique")))
        val graph = ContextMemoryGraph.project(listOf(source, entry("target", "Unique"), entry("other", "target")))
        assertEquals(listOf("target", "target"), graph.edges.map { it.targetId })
        assertTrue(graph.edges.all { it.resolution == ContextMemoryResolution.RESOLVED })
    }

    @Test fun sameTitleAcrossScopesIsAmbiguousRatherThanGuessed() {
        val graph = ContextMemoryGraph.project(listOf(
            entry("source", relations = listOf(WorldbookRelation("Same"))),
            entry("global", "Same"), entry("project", "Same", MemoryScope.PROJECT),
        ))
        assertNull(graph.edges.single().targetId)
        assertEquals(ContextMemoryResolution.AMBIGUOUS, graph.edges.single().resolution)
        assertEquals(3, graph.nodes.size)
    }

    @Test fun externalOrInvisibleTargetDoesNotCreateNode() {
        val source = entry("source", relations = listOf(WorldbookRelation("invisible")))
        val graph = ContextMemoryGraph.project(listOf(source))
        assertEquals(listOf(source), graph.nodes)
        assertNull(graph.edges.single().targetId)
        assertEquals("invisible", graph.edges.single().target)
        assertEquals(ContextMemoryResolution.MISSING, graph.edges.single().resolution)
    }

    @Test fun duplicateEdgesCollapseButDistinctTypesAndSelfEdgesSurvive() {
        val relation = WorldbookRelation("target", WorldbookRelationType.SUPPORTS)
        val graph = ContextMemoryGraph.project(listOf(entry("source", relations = listOf(
            relation, relation, relation.copy(type = WorldbookRelationType.CONTRADICTS), WorldbookRelation("source"),
        )), entry("target")))
        assertEquals(3, graph.edges.size)
        assertEquals(setOf(WorldbookRelationType.RELATED, WorldbookRelationType.SUPPORTS,
            WorldbookRelationType.CONTRADICTS), graph.edges.map { it.type }.toSet())
        assertTrue(graph.edges.any { it.sourceId == it.targetId })
    }

    @Test fun nodePaginationKeepsEveryCrossPageAndUnresolvedRelation() {
        val entries = (0 until 103).map { index -> entry("entry-$index", relations = listOf(
            WorldbookRelation("entry-${(index + 50) % 103}"), WorldbookRelation("missing-$index"),
        )) }
        val graph = ContextMemoryGraph.project(entries)
        val pages = (0 until graph.page(0).count).map { graph.page(it) }
        assertTrue(pages.all { it.nodes.size <= ContextMemoryGraph.DEFAULT_NODE_LIMIT })
        assertEquals(graph.nodes, pages.flatMap { it.nodes })
        assertEquals(graph.edges.toSet(), pages.flatMap { it.edges }.toSet())
        assertEquals(graph.edges.size, pages.sumOf { it.edges.size })
        assertTrue(pages.any { page -> page.edges.any { edge -> edge.targetId != null && page.nodes.none { it.id == edge.targetId } } })
        assertEquals(0, graph.page(-1).index)
        assertEquals(pages.last().index, graph.page(Int.MAX_VALUE).index)
        assertTrue(runCatching { graph.page(0, ContextMemoryGraph.MAX_NODE_LIMIT + 1) }.isFailure)
        assertTrue(runCatching { graph.page(0, 0) }.isFailure)
    }

    @Test fun emptyGraphAndStableIdentityDoNotInventRelationshipsOrActivation() {
        val empty = ContextMemoryGraph.project(emptyList()).page(100)
        assertEquals(0, empty.index)
        assertEquals(1, empty.count)
        assertTrue(empty.nodes.isEmpty() && empty.edges.isEmpty())
        val node = entry("stable").copy(enabled = false, loading = ContextLoad.OFF)
        val graph = ContextMemoryGraph.project(listOf(node, node))
        assertEquals(listOf(node), graph.nodes)
        assertTrue(graph.edges.isEmpty())
        assertEquals(node, graph.page(0).nodes.single())
    }
}
