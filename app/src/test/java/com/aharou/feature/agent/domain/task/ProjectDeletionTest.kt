package com.aharou.feature.agent.domain.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDeletionTest {
    private val projects = listOf(ProjectPlan("p", "/w", "p", "r"), ProjectPlan("q", "/w", "q", "r"))
    private val parent = ProjectTask(id = "parent", workspace = "/w", projectId = "p", title = "parent", requirement = "r")
    private val child = parent.copy(id = "child", parentId = "parent")
    private val other = parent.copy(id = "other", projectId = "q")
    private val standalone = parent.copy(id = "standalone", projectId = null)

    @Test fun removesOnlyTheNamedProjectAndItsWholeTaskTree() {
        val result = ProjectDeletion.plan("p", projects, listOf(parent, child, other, standalone))
        assertTrue(result.changed)
        assertEquals(listOf(projects[1]), result.projects)
        assertEquals(listOf(other, standalone), result.tasks)
        assertNull(ProjectTaskGraph.validate(result.tasks))
        assertFalse(ProjectDeletion.plan("p", result.projects, result.tasks).changed)
    }

    @Test fun deletingAnEmptyProjectStillChangesTheStore() {
        assertTrue(ProjectDeletion.plan("p", projects, emptyList()).changed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsABrokenSurvivingGraphInsteadOfCascadingIntoOtherProjects() {
        ProjectDeletion.plan("p", projects, listOf(parent, other.copy(dependencyIds = listOf(parent.id))))
    }
}
