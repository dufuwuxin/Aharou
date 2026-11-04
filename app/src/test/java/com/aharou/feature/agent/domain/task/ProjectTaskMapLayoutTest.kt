package com.aharou.feature.agent.domain.task

import org.junit.Assert.*
import org.junit.Test

class ProjectTaskMapLayoutTest {
    @Test fun parentIsAtChildBranchMidline() {
        val tasks = listOf(ProjectTask(id = "a", workspace = "w", title = "A", requirement = "a"),
            ProjectTask(id = "b", workspace = "w", title = "B", requirement = "b"))
        val layout = ProjectTaskMapLayout.build("p", tasks)
        val root = layout.nodes.first { it.id == "project:p" }
        assertEquals((layout.nodes[1].y + layout.nodes[2].y) / 2f, root.y, 0.01f)
        assertEquals(2, layout.edges.size)
    }
    @Test fun minimumZoomKeepsSiblingHitRectanglesSeparateAndTouchable() {
        val tasks = listOf(ProjectTask(id = "a", workspace = "w", title = "A", requirement = "a"),
            ProjectTask(id = "b", workspace = "w", title = "B", requirement = "b"))
        val nodes = ProjectTaskMapLayout.build("p", tasks).nodes.filter { it.id in setOf("a", "b") }.sortedBy { it.y }
        val scale = ProjectTaskMapLayout.MIN_ZOOM
        val height = ProjectTaskMapLayout.NODE_HEIGHT * scale
        assertTrue(height >= 48f)
        assertTrue((nodes[1].y - nodes[0].y) * scale >= height)
        val lowerOnly = ProjectTaskMapLayout.build("p", tasks).visible(nodes[1].x, nodes[1].y + 1f,
            nodes[1].x + ProjectTaskMapLayout.NODE_WIDTH, nodes[1].y + ProjectTaskMapLayout.NODE_HEIGHT)
        assertFalse(lowerOnly.any { it.id == nodes[0].id })
    }
    @Test fun thousandNodeChainUsesLogicalCoordinatesAndBoundedViewport() {
        val tasks = (0..1000).map { ProjectTask(id = "n$it", workspace = "w", title = "$it", requirement = "r",
            parentId = if (it == 0) null else "n${it - 1}", order = it.toLong()) }
        val layout = ProjectTaskMapLayout.build("p", tasks)
        assertEquals(1002, layout.nodes.size)
        assertTrue(layout.width > 200000f)
        assertTrue(layout.visible(0f, 0f, 400f, 800f).size < 10)
        assertTrue(layout.nodes.all { it.x.isFinite() && it.y.isFinite() })
    }
}
