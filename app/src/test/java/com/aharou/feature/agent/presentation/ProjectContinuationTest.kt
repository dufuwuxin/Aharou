package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.presentation.component.projectProgressNodes
import org.junit.Assert.*
import org.junit.Test

class ProjectContinuationTest {
    @Test fun projectionUsesPublicMessagesAndReplacesLiveToolWithoutDuplicatingIt() {
        val user = AgentUIMessage(id = "u", role = MessageRole.USER, content = "goal")
        val answer = AgentUIMessage(id = "a", role = MessageRole.ASSISTANT, content = "result", reasoning = "private")
        val tool = AgentUIMessage(id = "t", role = MessageRole.TOOL, content = "old")
        val nodes = projectProgressNodes(listOf(user, tool, answer), listOf(tool.copy(content = "live")), "streaming")
        assertEquals(1, nodes.count { it.id == "t" })
        assertEquals("live", nodes.first { it.id == "t" }.text)
        assertEquals("目标", nodes.first().kind)
        assertFalse(nodes.any { it.text.contains("private") })
        assertEquals("streaming", nodes.last().text)
    }
}


/** Real project JSON store; no model or workspace service is called by these operations. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class ProjectGoalStoreTest {
    @Test fun emptyProjectPersistsGoalAndDeletingBranchCannotEraseIt() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val workspace = "goal-test-${java.util.UUID.randomUUID()}"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(workspace.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val directory = java.io.File(context.filesDir, "project-tasks").also { it.mkdirs() }
        java.io.File(directory, "$hash.json").writeText(
            """{"projects":[{"id":"p","workspace":"$workspace","title":"old","requirements":"keep"}]}""")
        fun repository() = com.aharou.feature.agent.data.task.ProjectTaskRepository(context,
            javax.inject.Provider { io.mockk.mockk<com.aharou.feature.agent.domain.task.ProjectPlanner>() },
            javax.inject.Provider { io.mockk.mockk<com.aharou.feature.workspace.data.repository.WorkspaceRepository>() })
        val repo = repository()
        val old = repo.load(workspace).projects.single()
        assertTrue(repo.load(workspace).tasks.isEmpty())
        repo.saveProjectGoal(old, "long", "ship", "verified")
        val configured = repository().load(workspace).projects.single()
        assertEquals("long", configured.horizon)
        assertEquals("ship", configured.overallGoal)
        assertEquals("verified", configured.goalAcceptance)
        assertEquals("keep", configured.requirements)
        assertEquals(1L, configured.goalRevision)
        repo.checkpointProjectGoal(old, "STALE_CHECKPOINT")
        assertEquals("", repository().load(workspace).projects.single().goalCheckpoint)
        val branch = com.aharou.feature.agent.domain.task.ProjectTask(workspace = workspace, projectId = "p",
            title = "branch", requirement = "discuss", isRequirementBranch = true)
        repo.save(branch)
        repo.deleteRequirementBranch("p", branch.id, workspace)
        assertTrue(repository().load(workspace).tasks.isEmpty())
        assertEquals(configured, repository().load(workspace).projects.single())
        repo.checkpointProjectGoal(configured, "GOAL_ACCEPTANCE_REQUIRED")
        assertEquals("GOAL_ACCEPTANCE_REQUIRED", repository().load(workspace).projects.single().goalCheckpoint)
        var rejected = false
        try { repo.saveProjectGoal(old, "short", "stale", "stale") }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        val done = com.aharou.feature.agent.domain.task.ProjectTask(workspace = workspace, projectId = "p",
            title = "accepted", requirement = "old goal", status = com.aharou.feature.agent.domain.task.ProjectTaskStatus.DONE,
            acceptedRequirementsVersion = 1, brief = "old evidence", acceptance = "verified", evidenceRefs = listOf("fixture:verified"))
        repo.save(done)
        val latestGoal = repo.load(workspace).projects.single()
        repo.saveProjectGoal(latestGoal, "long", "changed semantic goal", "new acceptance")
        val invalidated = repo.load(workspace).tasks.single()
        assertEquals(com.aharou.feature.agent.domain.task.ProjectTaskStatus.DRAFT, invalidated.status)
        assertNull(invalidated.acceptedRequirementsVersion)
        assertEquals(2L, invalidated.requirementsVersion)
        assertTrue(invalidated.brief.isEmpty())
    }
}
