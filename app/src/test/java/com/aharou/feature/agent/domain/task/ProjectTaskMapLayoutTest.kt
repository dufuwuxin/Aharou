package com.aharou.feature.agent.domain.task

import org.junit.Assert.*
import org.junit.Test

class ProjectTaskMapLayoutTest {
    private fun task(id: String, title: String = id.uppercase(), parent: String? = null, deps: List<String> = emptyList(),
        order: Long = 0) = ProjectTask(id = id, workspace = "w", title = title, requirement = id, parentId = parent,
        dependencyIds = deps, order = order)

    @Test fun parentIsAtChildBranchMidline() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("b", order = 1)))
        val root = layout.nodes.first { it.id == "project:p" }
        assertEquals((layout.nodes[1].y + layout.nodes[2].y) / 2f, root.y, 0.01f)
        assertEquals(2, layout.edges.size)
    }

    @Test fun rootIsMarkedAndTasksAreNot() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a")), rootTitle = "Project")
        assertTrue(layout.node("project:p")!!.root)
        assertFalse(layout.node("a")!!.root)
        assertEquals("project:p", ProjectTaskMapLayout.rootId("p"))
    }

    @Test fun minimumZoomKeepsSiblingHitRectanglesSeparateAndTouchable() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("b", order = 1)))
        val nodes = layout.nodes.filter { it.id in setOf("a", "b") }.sortedBy { it.y }
        val scale = ProjectTaskMapLayout.MIN_ZOOM
        val height = ProjectTaskMapLayout.NODE_HEIGHT * scale
        assertTrue(height >= 48f)
        assertTrue((nodes[1].y - nodes[0].y) * scale >= height)
        val lowerOnly = layout.visible(nodes[1].x, nodes[1].y + 1f, nodes[1].x + nodes[1].width,
            nodes[1].y + ProjectTaskMapLayout.NODE_HEIGHT)
        assertFalse(lowerOnly.any { it.id == nodes[0].id })
    }

    @Test fun nodeWidthFollowsTitleWithinBounds() {
        val short = ProjectTaskMapLayout.nodeWidth("A")
        val medium = ProjectTaskMapLayout.nodeWidth("实现登录页面与接口联调")
        val long = ProjectTaskMapLayout.nodeWidth("实现".repeat(80))
        assertEquals(ProjectTaskMapLayout.NODE_MIN_WIDTH, short, 0.01f)
        assertTrue(medium > short)
        assertEquals(ProjectTaskMapLayout.NODE_MAX_WIDTH, long, 0.01f)
        assertTrue(ProjectTaskMapLayout.nodeWidth("实现登录页面与接口联调", glyph = 16f) >= medium)
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a", "A"), task("b", "实现登录页面与接口联调", order = 1)))
        assertTrue(layout.node("b")!!.width > layout.node("a")!!.width)
    }

    @Test fun childColumnStartsAfterTheWidestNodeOfTheParentColumn() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a", "实现".repeat(80)), task("b", "B", order = 1),
            task("c", parent = "b")))
        val a = layout.node("a")!!
        assertEquals(a.x, layout.node("b")!!.x, 0.01f)
        assertTrue(layout.node("c")!!.x >= a.x + a.width + ProjectTaskMapLayout.LEVEL_GAP - 0.01f)
    }

    @Test fun forwardEdgeIsCubicWithHorizontalTangents() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("child", parent = "a")))
        val a = layout.node("a")!!
        val child = layout.node("child")!!
        val curve = layout.curve(layout.edges.first { it.to == "child" })!!
        assertEquals(a.x + a.width, curve.x0, 0.01f)
        assertEquals(child.x, curve.x3, 0.01f)
        assertEquals(curve.y0, curve.y1, 0.01f)
        assertEquals(curve.y3, curve.y2, 0.01f)
        assertTrue(curve.x1 > curve.x0 && curve.x2 < curve.x3)
    }

    @Test fun sameColumnDependencyBulgesOutsideBothNodes() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("b", deps = listOf("a"), order = 1)))
        val edge = layout.edges.single { it.dependency }
        val curve = layout.curve(edge)!!
        val a = layout.node("a")!!
        val b = layout.node("b")!!
        assertEquals(b.x + b.width, curve.x3, 0.01f)
        assertTrue(curve.x1 > a.x + a.width && curve.x2 > b.x + b.width)
        assertTrue(curve.intersects(a.x, a.y, a.x + a.width, b.y + b.height))
        assertFalse(curve.intersects(-500f, -500f, -400f, -400f))
    }

    @Test fun thousandNodeChainUsesLogicalCoordinatesAndBoundedViewport() {
        val tasks = (0..1000).map { ProjectTask(id = "n$it", workspace = "w", title = "$it", requirement = "r",
            parentId = if (it == 0) null else "n${it - 1}", order = it.toLong()) }
        val layout = ProjectTaskMapLayout.build("p", tasks)
        assertEquals(1002, layout.nodes.size)
        assertTrue(layout.width > 1001 * (ProjectTaskMapLayout.NODE_MIN_WIDTH + ProjectTaskMapLayout.LEVEL_GAP))
        assertTrue(layout.visible(0f, 0f, 400f, 800f).size < 10)
        assertTrue(layout.nodes.all { it.x.isFinite() && it.y.isFinite() })
    }
}
