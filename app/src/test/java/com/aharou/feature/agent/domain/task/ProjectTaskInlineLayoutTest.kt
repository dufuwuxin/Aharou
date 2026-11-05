package com.aharou.feature.agent.domain.task

import org.junit.Assert.*
import org.junit.Test

class ProjectTaskInlineLayoutTest {
    private fun task(id: String, parent: String? = null, order: Long = 0) = ProjectTask(
        id = id, workspace = "w", title = id, requirement = id, parentId = parent,
        order = order, createdAt = 1)

    @Test fun tallInternalNodeReservesItsSubtreeAndCannotCoverASibling() {
        val tasks = listOf(task("a"), task("child", "a"), task("b", order = 2))
        val layout = ProjectTaskMapLayout.build("p", tasks, "a", 360f, 560f)
        val a = layout.nodes.first { it.id == "a" }
        val b = layout.nodes.first { it.id == "b" }
        assertEquals(560f, a.height, 0.01f)
        assertTrue(a.y + a.height <= b.y || b.y + b.height <= a.y)
        assertTrue(layout.nodes.all { it.y >= 0 && it.x >= 0 })
    }

    @Test fun wideNodePushesTheFollowingDepthBeyondItsRightEdge() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a"), task("child", "a")), "a", 420f, 500f)
        val a = layout.nodes.first { it.id == "a" }
        val child = layout.nodes.first { it.id == "child" }
        assertTrue(child.x >= a.x + a.width + 48f)
        assertTrue(layout.width >= child.x + child.width)
        assertTrue(layout.height >= a.y + a.height)
    }

    @Test fun cullingIncludesAnExpandedNodeWhoseOriginIsOutsideTheViewport() {
        val layout = ProjectTaskMapLayout.build("p", listOf(task("a")), "a", 360f, 560f)
        val a = layout.nodes.first { it.id == "a" }
        assertTrue(layout.visible(a.x + 200f, a.y + 400f, a.x + 300f, a.y + 500f).any { it.id == "a" })
        assertEquals(2, ProjectTaskMapLayout.build("p", listOf(task("a"))).nodes.size)
    }
}
