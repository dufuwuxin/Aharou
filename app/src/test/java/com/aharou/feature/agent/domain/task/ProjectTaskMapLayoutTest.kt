package com.aharou.feature.agent.domain.task

import org.junit.Assert.*
import org.junit.Test

class ProjectTaskMapLayoutTest {
    private fun task(id: String, title: String = id.uppercase(), parent: String? = null, deps: List<String> = emptyList(),
        order: Long = 0) = ProjectTask(id = id, workspace = "w", title = title, requirement = id, parentId = parent,
        dependencyIds = deps, order = order)

    private fun ProjectTaskMapLayout.Node.overlaps(other: ProjectTaskMapLayout.Node) =
        x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height

    @Test fun firstLevelFitsPhoneAtReadableZoom() {
        val viewport = 393f
        val gap = ProjectTaskMapLayout.LEVEL_GAP
        val headerLimit = (viewport - ProjectTaskMapLayout.MARGIN * 2 - gap) / 2
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a", "Review requirements")),
            rootTitle = "verify-project-tree-1105", maxHeaderWidth = headerLimit)
        val root = layout.node("project:p")!!
        val child = layout.node("a")!!
        assertTrue(root.width <= headerLimit)
        assertTrue(child.x + child.headerWidth <= viewport - ProjectTaskMapLayout.MARGIN + 0.01f)
        assertEquals(ProjectTaskMapLayout.NODE_HEIGHT, child.height, 0.01f)
    }

    @Test fun fitZoomIncludesDeepAndWideContentsAndAllowsFurtherZoomOut() {
        for ((width, height) in listOf(300000f to 800f, 800f to 300000f)) {
            val fit = ProjectTaskMapLayout.fitZoom(393f, 700f, width, height)
            assertTrue(fit.isFinite() && fit > 0f)
            assertTrue(width * fit <= 393.01f && height * fit <= 700.01f)
            val smaller = ProjectTaskMapLayout.clampZoom(fit / 2f, ProjectTaskMapLayout.minimumZoom(fit))
            assertTrue(smaller > 0f && smaller < fit)
        }
        assertEquals(2f, ProjectTaskMapLayout.clampZoom(20f, 0.01f), 0f)
    }

    @Test fun invalidViewportAndGestureScalesStayPositiveAndFinite() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val fit = ProjectTaskMapLayout.fitZoom(bad, 700f, 300000f, 800f)
            assertTrue(fit.isFinite() && fit > 0f)
            val zoom = ProjectTaskMapLayout.clampZoom(bad, ProjectTaskMapLayout.minimumZoom(fit))
            assertTrue(zoom.isFinite() && zoom > 0f && zoom <= 2f)
        }
    }

    @Test fun parentIsAtChildBranchMidline() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("b", order = 1)))
        val root = layout.nodes.first { it.id == "project:p" }
        assertEquals((layout.nodes[1].dotY + layout.nodes[2].dotY) / 2f, root.dotY, 0.01f)
        assertEquals(2, layout.edges.size)
    }

    @Test fun rootIsMarkedAndTasksAreNot() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a")), rootTitle = "Project")
        assertTrue(layout.node("project:p")!!.root)
        assertFalse(layout.node("a")!!.root)
        assertEquals("project:p", ProjectTaskMapLayout.rootId("p"))
    }

    @Test fun overviewZoomKeepsSiblingRectanglesSeparateWithoutTouchSizeFloor() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("b", order = 1)))
        val nodes = layout.nodes.filter { it.id in setOf("a", "b") }.sortedBy { it.y }
        val scale = ProjectTaskMapLayout.MIN_ZOOM
        val height = ProjectTaskMapLayout.NODE_HEIGHT * scale
        assertTrue(height > 0f && height < 48f)
        assertTrue((nodes[1].y - nodes[0].y) * scale >= height)
        val lowerOnly = layout.visible(nodes[1].x, nodes[1].y + 1f, nodes[1].x + nodes[1].width,
            nodes[1].y + ProjectTaskMapLayout.NODE_HEIGHT)
        assertFalse(lowerOnly.any { it.id == nodes[0].id })
    }

    @Test fun headerWidthFollowsTitleWithinBounds() {
        val short = ProjectTaskMapLayout.headerWidth("A")
        val medium = ProjectTaskMapLayout.headerWidth("实现登录页面与接口联调")
        val long = ProjectTaskMapLayout.headerWidth("实现".repeat(80))
        assertEquals(ProjectTaskMapLayout.NODE_MIN_WIDTH, short, 0.01f)
        assertTrue(medium > short)
        assertEquals(ProjectTaskMapLayout.NODE_MAX_WIDTH, long, 0.01f)
        assertTrue(ProjectTaskMapLayout.headerWidth("实现登录页面与接口联调", glyph = 18.2f) >= medium)
    }

    @Test fun unfoldedNodePushesItsColumnSiblingsDown() {
        val tasks = listOf(task("a"), task("b", order = 1), task("c", order = 2))
        val folded = ProjectTaskMapLayout.build("p", tasks)
        val unfolded = ProjectTaskMapLayout.build("p", tasks, heights = mapOf("a" to 400f))
        val a = unfolded.node("a")!!
        val b = unfolded.node("b")!!
        assertEquals(400f, a.height, 0.01f)
        assertTrue(a.width >= ProjectTaskMapLayout.PANEL_WIDTH)
        assertTrue(b.y >= a.y + a.height + ProjectTaskMapLayout.SIBLING_GAP - 0.01f)
        assertTrue(unfolded.node("c")!!.y - folded.node("c")!!.y >= 400f - ProjectTaskMapLayout.NODE_HEIGHT - 0.01f)
        assertEquals(ProjectTaskMapLayout.NODE_HEIGHT, folded.node("a")!!.height, 0.01f)
    }

    @Test fun noTwoNodeBoxesIntersectWithMixedUnfoldedHeights() {
        val tasks = listOf(task("a"), task("a1", parent = "a"), task("a2", parent = "a", order = 1),
            task("b", order = 1), task("b1", parent = "b"), task("c", order = 2, deps = listOf("a1")),
            task("c1", parent = "c"), task("c2", parent = "c", order = 1), task("c3", parent = "c", order = 2))
        // Default and 1.3× font scale: unfolded heights grow, header widths widen with the glyph.
        for ((glyph, scale) in listOf(14f to 1f, 18.2f to 1.3f)) {
            val heights = mapOf("a" to 520f * scale, "a2" to 90f * scale, "b1" to 300f * scale, "c" to 70f * scale)
            val layout = ProjectTaskMapLayout.build("p", tasks, heights = heights, rootTitle = "Root", glyph = glyph)
            for (i in layout.nodes.indices) for (j in i + 1 until layout.nodes.size) {
                assertFalse("${layout.nodes[i].id} overlaps ${layout.nodes[j].id} at $scale×",
                    layout.nodes[i].overlaps(layout.nodes[j]))
            }
            assertTrue(layout.nodes.all { it.y >= 0f && it.x >= 0f && it.y + it.height <= layout.height })
        }
    }

    @Test fun curvesStartAtTheForkDotAndEndOnTheChildDot() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("child", parent = "a")),
            heights = mapOf("a" to 300f))
        val a = layout.node("a")!!
        val child = layout.node("child")!!
        val curve = layout.curve(layout.edges.first { it.to == "child" })!!
        assertEquals(a.forkX, curve.x0, 0.01f)
        assertEquals(a.dotY, curve.y0, 0.01f)
        assertEquals(child.dotX - ProjectTaskMapLayout.DOT_RADIUS, curve.x3, 0.01f)
        assertEquals(child.dotY, curve.y3, 0.01f)
        assertEquals(curve.y3, curve.y2, 0.01f)
        assertTrue(curve.x0 > a.x + a.width && curve.x1 > curve.x0 && curve.x2 < curve.x3)
    }

    @Test fun childColumnStartsAfterTheWidestNodeOfTheParentColumn() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a", "实现".repeat(80)), task("b", "B", order = 1),
            task("c", parent = "b")))
        val a = layout.node("a")!!
        assertEquals(a.x, layout.node("b")!!.x, 0.01f)
        assertTrue(layout.node("c")!!.x >= a.x + a.width + ProjectTaskMapLayout.LEVEL_GAP - 0.01f)
    }

    @Test fun sameColumnDependencyBulgesOutsideBothNodes() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("b", deps = listOf("a"), order = 1)))
        val curve = layout.curve(layout.edges.single { it.dependency })!!
        val a = layout.node("a")!!
        val b = layout.node("b")!!
        assertEquals(b.forkX, curve.x3, 0.01f)
        assertEquals(b.dotY, curve.y3, 0.01f)
        assertTrue(curve.x1 > a.x + a.width && curve.x2 > b.x + b.width)
        assertTrue(curve.intersects(a.x, a.y, a.forkX, b.y + b.height))
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
