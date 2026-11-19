package com.aharou.feature.agent.domain.model

import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class RouteGroupAllocationTest {
    private val a = RouteModelBinding("one", "A")
    private val b = RouteModelBinding("two", "B")
    private val c = RouteModelBinding("three", "C")
    private val group = ModelRouteGroup("route-1", "Ordered", listOf(
        ModelRouteMember("one", "A"), ModelRouteMember("two", "B"), ModelRouteMember("three", "C")
    ))

    @Test fun `members follow slot one onward and wrap to the main slot`() {
        val allocation = RouteGroupAllocation()
        assertEquals(listOf(b, c, a, b, c), List(5) { allocation.assign("parent", group) })
        assertEquals(b, allocation.assign("different-parent", group))
        assertEquals(List(3) { a }, List(3) { allocation.assign("one-slot", group.copy(models = group.models.take(1))) })
    }

    @Test fun `fixed bindings do not spend ordered slots or inherit synthetic ids`() {
        val allocation = RouteGroupAllocation()
        assertEquals(RouteModelBinding("fixed-provider", "fixed-model"), allocation.assign("parent", group, "fixed-provider", "fixed-model"))
        assertEquals(c, allocation.assign("parent", group, model = "C"))
        assertEquals(RouteModelBinding("fixed-provider", null), allocation.assign("parent", group, providerId = "fixed-provider"))
        assertEquals(b, allocation.assign("parent", group, providerId = "two"))
        assertEquals(RouteModelBinding(null, "unlisted"), allocation.assign("parent", group, model = "unlisted"))
        assertEquals(b, allocation.assign("parent", group))
    }

    @Test fun `editing launch order cannot rewrite allocated bindings`() {
        val allocation = RouteGroupAllocation()
        val existing = allocation.assign("parent", group)
        assertEquals(c, allocation.assign("parent", group.copy(name = "Renamed")))
        assertEquals(a, allocation.assign("parent", group.copy(models = listOf(group.models[2], group.models[0]))))
        assertNull(allocation.assign("parent", null))
        assertEquals(b, existing)
    }

    @Test fun `concurrent parent allocations have no lost advances`() {
        val allocation = RouteGroupAllocation()
        val executor = Executors.newFixedThreadPool(6)
        try {
            val results = executor.invokeAll(List(80) { Callable { allocation.assign("parent", group) } })
                .map { it.get() }.groupingBy { it }.eachCount()
            assertEquals(mapOf(a to 26, b to 27, c to 27), results)
            assertEquals(a, allocation.assign("parent", group))
        } finally {
            executor.shutdownNow()
        }
    }
}
