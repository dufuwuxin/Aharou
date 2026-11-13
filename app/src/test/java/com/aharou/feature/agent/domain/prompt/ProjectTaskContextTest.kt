package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTaskContextTest {
    @Test fun rootContextContainsOnlyCurrentProjectFactsAndTaskIds() {
        val repository = mockk<ProjectTaskRepository>()
        val project = ProjectPlan(id = "p", workspace = "w", title = "Project", requirements = "Build", flowSessionIds = listOf("root"))
        val store = ProjectTaskSnapshot(projects = listOf(project), tasks = listOf(
            ProjectTask(id = "ours", workspace = "w", projectId = "p", title = "Our task", requirement = "Keep offline"),
            ProjectTask(id = "theirs", workspace = "w", projectId = "other", title = "PRIVATE OTHER", requirement = "hidden")))
        every { repository.snapshot("w") } returns store
        val prompt = ProjectTaskContext(repository).build("root", "w").orEmpty()
        assertTrue(prompt.contains("project=p"))
        assertTrue(prompt.contains("task=ours"))
        assertFalse(prompt.contains("PRIVATE OTHER"))
        assertFalse(prompt.contains("project_tasks"))
        assertTrue(prompt.length <= 280)
    }

    @Test fun longBranchRequirementsAreFieldCappedAndRemainWithinMemoryBudget() {
        val repository = mockk<ProjectTaskRepository>()
        val task = ProjectTask(id = "t", workspace = "w", title = "Search", requirement = "Need one. ".repeat(120),
            sessionIds = listOf("member"), projectId = "p", isRequirementBranch = true)
        every { repository.snapshot("w") } returns ProjectTaskSnapshot(tasks = listOf(task))
        val prompt = ProjectTaskContext(repository).build("member", "w").orEmpty()
        assertTrue(prompt.length <= 280)
        assertTrue(prompt, prompt.contains("requirements="))
        assertTrue(prompt.contains("Need one."))
        assertFalse(prompt.contains("Need one. ".repeat(18)))
    }

    @Test fun purposeIsBackgroundInBothRootAndTaskContextsAndNeverBecomesRequirements() {
        val repository = mockk<ProjectTaskRepository>()
        val project = ProjectPlan(id = "p", workspace = "w", title = "Project", purpose = "Research archive", flowSessionIds = listOf("root"))
        val task = ProjectTask(id = "t", workspace = "w", projectId = "p", title = "Search", requirement = "Add search", sessionIds = listOf("member"))
        every { repository.snapshot("w") } returns ProjectTaskSnapshot(projects = listOf(project), tasks = listOf(task))
        val context = ProjectTaskContext(repository)
        for (session in listOf("root", "member")) {
            val prompt = context.build(session, "w").orEmpty()
            assertFalse(prompt.contains("Research archive"))
        }
        assertTrue(context.build("root", "w").orEmpty().contains("kind=root; project=p"))
        assertTrue(context.build("member", "w").orEmpty().contains("requirement=Add search"))
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

    @Test fun brokenTaskStoreDoesNotBreakOrdinaryConversation() {
        val repository = mockk<ProjectTaskRepository>()
        every { repository.snapshot("w") } throws IllegalStateException("unsupported task format")
        assertEquals("project_task_context_status=unavailable", ProjectTaskContext(repository).build("s", "w"))
    }

    @Test fun missingSessionOrWorkspaceDoesNotReadTheProjectStore() {
        val repository = mockk<ProjectTaskRepository>()
        val context = ProjectTaskContext(repository)
        assertNull(context.build(null, "w"))
        assertNull(context.build("root", ""))
        verify(exactly = 0) { repository.snapshot(any()) }
    }

    @Test fun branchDiscussionContainsOnlyItsStoredFactsAndScope() {
        val repository = mockk<ProjectTaskRepository>()
        val branch = ProjectTask(id = "b", workspace = "w", projectId = "p", title = "Discussion", requirement = "original wording",
            sessionIds = listOf("discussion"), isRequirementBranch = true)
        every { repository.snapshot("w") } returns ProjectTaskSnapshot(tasks = listOf(branch),
            projects = listOf(ProjectPlan(id = "p", workspace = "w", title = "Project", purpose = "background")))
        every { repository.snapshot("other") } returns ProjectTaskSnapshot()
        val context = ProjectTaskContext(repository)
        val prompt = context.build("discussion", "w").orEmpty()
        assertTrue(prompt.contains("kind=requirement_branch_discussion"))
        assertTrue(prompt.contains("branch=b"))
        assertTrue(prompt.contains("original wording"))
        assertFalse(prompt.contains("Current project task"))
        assertFalse(prompt.contains("project_tasks"))
        assertFalse(prompt.contains("DRAFT"))
        assertNull(context.build("discussion", "other"))
    }
}
