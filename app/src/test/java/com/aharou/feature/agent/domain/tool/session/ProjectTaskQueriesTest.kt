package com.aharou.feature.agent.domain.tool.session

import com.aharou.feature.agent.domain.task.ProjectTask
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectTaskQueriesTest {
    private val tasks = (1..5).map { ProjectTask(id = "t$it", workspace = if (it == 5) "other" else "w", title = "task", requirement = "requirement", version = it.toLong(), order = it.toLong()) }
    @Test fun workspaceIsFilteredBeforeIncrementalPagination() {
        val page = ProjectTaskQueries.query(tasks, "w", "index", setOf(), 1, 0, 2)
        assertEquals(listOf("t2", "t3"), page.getValue("tasks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(2, page.getValue("nextOffset").jsonPrimitive.int)
        assertEquals(JsonNull, ProjectTaskQueries.query(tasks, "w", "index", setOf(), 1, 2, 2)["nextOffset"])
    }
    @Test fun briefRequiresExplicitBatchAndNeverIncludesOtherWorkspace() {
        assertTrue(ProjectTaskQueries.query(tasks, "w", "brief", emptySet(), 0, 0, 20).getValue("tasks").jsonArray.isEmpty())
        val brief = ProjectTaskQueries.query(tasks, "w", "brief", setOf("t1", "t5"), 0, 0, 20)
        assertEquals(1, brief.getValue("tasks").jsonArray.size)
        assertEquals("requirement", brief.getValue("tasks").jsonArray.first().jsonObject.getValue("requirement").jsonPrimitive.content)
    }
    @Test fun pagesRequireStableVersionAndBriefHasExplicitBudgets() {
        assertTrue(ProjectTaskQueries.pageValid(8, 0, null))
        assertTrue(ProjectTaskQueries.pageValid(8, 2, 8))
        assertFalse(ProjectTaskQueries.pageValid(9, 2, 8))
        assertFalse(ProjectTaskQueries.pageValid(8, 2, null))
        val oversized = tasks.first().copy(requirement = "x".repeat(5000), background = "y".repeat(2000))
        val item = ProjectTaskQueries.query(listOf(oversized), "w", "brief", setOf("t1"), 0, 0, 200).getValue("tasks").jsonArray.single().jsonObject
        assertEquals(1600, item.getValue("requirement").jsonPrimitive.content.length)
        assertTrue(item.getValue("truncatedFields").jsonArray.any { it.jsonPrimitive.content == "requirement" })
        assertEquals("field", item.getValue("fieldRead").jsonObject.getValue("constraints").jsonObject.getValue("action").jsonPrimitive.content)
    }
    @Test fun budgetOutsideConstraintCanBeReadCompletelyWithVersionedFieldPages() {
        val constraints = (1..13).map { "constraint-$it " + "x".repeat(600) + if (it == 13) " MUST_NOT_DELETE" else "" }
        val task = tasks.first().copy(constraints = constraints)
        val full = StringBuilder(); var offset = 0
        do {
            val page = ProjectTaskQueries.field(listOf(task), "w", task.id, "constraints", offset, 500, task.version)
            full.append(page.getValue("content").jsonPrimitive.content)
            offset = page["nextOffset"]?.let { if (it is JsonNull) -1 else it.jsonPrimitive.int } ?: -1
        } while (offset >= 0)
        assertTrue(full.contains("MUST_NOT_DELETE"))
        assertEquals(constraints, Json.parseToJsonElement(full.toString()).jsonArray.map { it.jsonPrimitive.content })
        assertTrue(runCatching { ProjectTaskQueries.field(listOf(task), "other", task.id, "constraints", 0, 500, task.version) }.isFailure)
        assertTrue(runCatching { ProjectTaskQueries.field(listOf(task), "w", task.id, "constraints", 0, 500, task.version + 1) }.isFailure)
    }
}
