package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTaskContextTest {
    @Test fun rootContextIncludesRealTaskIdsAndBothNaturalLanguageActions() {
        val repository = mockk<ProjectTaskRepository>()
        val project = ProjectPlan(id = "p", workspace = "w", title = "Project", requirements = "Build", flowSessionIds = listOf("root"))
        val store = ProjectTaskSnapshot(projects = listOf(project), tasks = listOf(
            ProjectTask(id = "ours", workspace = "w", projectId = "p", title = "Our task", requirement = "Keep offline"),
            ProjectTask(id = "theirs", workspace = "w", projectId = "other", title = "PRIVATE OTHER", requirement = "hidden")))
        every { repository.snapshot("w") } returns store
        val prompt = ProjectTaskContext(repository).build("root", "w").orEmpty()
        assertTrue(prompt.contains("Project ID: p"))
        assertTrue(prompt.contains("ours"))
        assertTrue(prompt.contains("action=plan"))
        assertTrue(prompt.contains("action=supplement"))
        assertFalse(prompt.contains("PRIVATE OTHER"))
    }

    @Test fun unrelatedAndDifferentWorkspaceSessionsDoNotReceiveRootContext() {
        val repository = mockk<ProjectTaskRepository>()
        val project = ProjectPlan(id = "p", workspace = "w", title = "Project", requirements = "Build", flowSessionIds = listOf("root"))
        every { repository.snapshot("w") } returns ProjectTaskSnapshot(projects = listOf(project))
        every { repository.snapshot("other") } returns ProjectTaskSnapshot()
        val context = ProjectTaskContext(repository)
        assertNull(context.build("stranger", "w"))
        assertNull(context.build("root", "other"))
    }

    @Test fun ghostOrMissingSessionDoesNotReadTheProjectStore() {
        val repository = mockk<ProjectTaskRepository>()
        val context = ProjectTaskContext(repository)
        assertNull(context.build("root", "w", ghost = true))
        assertNull(context.build(null, "w"))
        assertNull(context.build("root", ""))
        verify(exactly = 0) { repository.snapshot(any()) }
    }
}
