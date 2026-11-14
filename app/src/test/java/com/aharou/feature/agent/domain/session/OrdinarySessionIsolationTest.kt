package com.aharou.feature.agent.domain.session

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.steward.StewardSettingsStore
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import io.mockk.*
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrdinarySessionIsolationTest {
    private val chats = mockk<ChatSessionDao>(relaxed = true)
    private val messages = mockk<AgentMessageDao>(relaxed = true)
    private val projects = mockk<ProjectTaskRepository>()
    private val sessions = SessionUseCase(chats, messages, mockk(relaxed = true), StewardSettingsStore.Noop).also {
        it.projectTasks = Provider { projects }
    }
    private fun session(id: String, workspace: String = "/w", parent: String? = null) =
        ChatSessionEntity(id, id, 1, 1, workspacePath = workspace, parentId = parent)
    private fun seed() {
        coEvery { projects.load("/w") } returns ProjectTaskSnapshot(projects = listOf(
            ProjectPlan("p", "/w", "project", flowSessionIds = listOf("flow"))), tasks = listOf(
            ProjectTask(workspace = "/w", projectId = "p", title = "task", requirement = "goal",
                sessionIds = listOf("task"), processSessionIds = listOf("process"))))
        coEvery { chats.getRootSessionsByWorkspaceOnce("/w") } returns
            listOf(session("flow"), session("task"), session("process"), session("ordinary"))
        coEvery { messages.hasMessages(any()) } returns false
    }

    @Test fun projectRootTaskAndProcessSessionsCannotBeReusedEvenWhenEmpty() = runTest {
        seed()
        for (id in listOf("flow", "task", "process")) {
            coEvery { chats.getById(id) } returns session(id)
            assertEquals("ordinary", sessions.getOrdinaryEmptySession("/w", id)?.id)
        }
        assertEquals("ordinary", sessions.getEmptySessionOfWorkspace("/w")?.id)
    }
    @Test fun currentChildAndOtherWorkspaceAreRejectedButOrdinaryCurrentIsPreferred() = runTest {
        seed()
        coEvery { chats.getById("child") } returns session("child", parent = "flow")
        coEvery { chats.getById("elsewhere") } returns session("elsewhere", "/other")
        coEvery { chats.getById("current") } returns session("current")
        assertEquals("ordinary", sessions.getOrdinaryEmptySession("/w", "child")?.id)
        assertEquals("ordinary", sessions.getOrdinaryEmptySession("/w", "elsewhere")?.id)
        assertEquals("current", sessions.getOrdinaryEmptySession("/w", "current")?.id)
        coEvery { messages.hasMessages("current") } returns true
        assertEquals("ordinary", sessions.getOrdinaryEmptySession("/w", "current")?.id)
    }
    @Test fun noOrdinaryEmptyCandidateReturnsNullWithoutDeletingProjectHistory() = runTest {
        seed()
        coEvery { messages.hasMessages("ordinary") } returns true
        assertNull(sessions.getOrdinaryEmptySession("/w"))
        assertEquals(0, sessions.recycleEmptySessions("/w"))
        coVerify(exactly = 0) { chats.delete(any()) }
    }
    @Test fun rootContainingOwnedProcessChildIsNotRecycled() = runTest {
        seed()
        coEvery { chats.getSubSessionsByParentOnce("ordinary") } returns listOf(session("process", parent = "ordinary"))
        assertEquals(0, sessions.recycleEmptySessions("/w"))
        coVerify(exactly = 0) { chats.delete(any()) }
    }
    @Test fun missingCurrentFallsBackAndChildOrForeignRootsNeverQualify() = runTest {
        seed()
        coEvery { chats.getById("missing") } returns null
        coEvery { chats.getRootSessionsByWorkspaceOnce("/w") } returns listOf(
            session("child", parent = "flow"), session("foreign", "/other"))
        assertNull(sessions.getOrdinaryEmptySession("/w", "missing"))
    }
    @Test fun recycleDeletesOnlyUnownedEmptyRootsAndRespectsKeepId() = runTest {
        seed()
        assertEquals(0, sessions.recycleEmptySessions("/w", "ordinary"))
        assertEquals(1, sessions.recycleEmptySessions("/w"))
        coVerify(exactly = 1) { chats.delete("ordinary") }
        for (id in listOf("flow", "task", "process")) coVerify(exactly = 0) { chats.delete(id) }
    }
}
