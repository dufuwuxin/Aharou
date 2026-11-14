package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.session.MessageArchiveStore
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.steward.StewardSettingsStore
import com.aharou.feature.agent.domain.task.ProjectPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

class ProjectSessionContinuityTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun creatingProjectKeepsCurrentWorkspaceAndDoesNotTouchSessions() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<ProjectTaskRepository>()
            val workspaces = mockk<com.aharou.feature.workspace.data.repository.WorkspaceRepository>()
            val sessions = mockk<SessionUseCase>()
            val workflow = mockk<com.aharou.feature.agent.domain.task.ProjectWorkflowRunner>()
            io.mockk.every { workspaces.workspaces } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            io.mockk.every { repository.snapshots } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
            coEvery { repository.load(any()) } returns ProjectTaskSnapshot()
            coEvery { workflow.members() } returns emptyList()
            coEvery { repository.createOwnedProject(any(), any(), any(), any()) } returns
                com.aharou.feature.agent.data.task.ProjectPlanOutcome(
                    ProjectPlan("new", "/owned", "new", purpose = "purpose", workspaceOwnership = "owned"), false)
            val flows = com.aharou.feature.agent.domain.task.ProjectFlowController(repository, Provider { workflow })
            val model = ProjectTaskViewModel(repository, sessions, mockk(), workflow, flows, Provider { workspaces })
            model.workspace("/current")
            testScheduler.runCurrent()
            model.createProject("new", "purpose")
            testScheduler.runCurrent()
            assertEquals("/current", model.state.value.workspace)
            assertEquals("new", model.state.value.createdProjectId)
            assertNull(model.state.value.selectedProjectId)
            coVerify(exactly = 0) { workspaces.activateProjectWorkspace(any(), any(), any()) }
            coVerify(exactly = 0) { sessions.upsertSession(any()) }
            coVerify(exactly = 0) { sessions.deleteSession(any()) }
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun bindingPreservesSessionFieldsAndDoesNotWriteMessages() = runTest {
        val chats = mockk<ChatSessionDao>()
        val messages = mockk<AgentMessageDao>()
        val archives = mockk<MessageArchiveStore>()
        val projects = mockk<ProjectTaskRepository>()
        val original = ChatSessionEntity("s", "history", 1L, 2L, workspacePath = "/before",
            providerId = "provider", model = "model", totalInputTokens = 19)
        var stored = original
        coEvery { chats.getById("s") } answers { stored }
        coEvery { chats.bindWorkspacePath("s", "/before", "/after") } answers {
            stored = stored.copy(workspacePath = thirdArg()); 1
        }
        coEvery { projects.load("/before") } returns ProjectTaskSnapshot()
        val sessions = SessionUseCase(chats, messages, archives, StewardSettingsStore.Noop)
        sessions.projectTasks = Provider { projects }
        sessions.bindWorkspace("s", "/after") { true }
        assertEquals(original.copy(workspacePath = "/after"), stored)
        coVerify(exactly = 0) { messages.deleteBySession(any()) }
        coVerify(exactly = 0) { archives.deleteSession(any()) }
        coVerify(exactly = 0) { chats.delete(any()) }
    }

    @Test fun executionWaitsUntilBindingWriteFinishesAndCancellationReleasesWaiters() = runTest {
        val chats = mockk<ChatSessionDao>()
        val projects = mockk<ProjectTaskRepository>()
        val original = ChatSessionEntity("s", "history", 1L, 2L, workspacePath = "/before")
        coEvery { chats.getById("s") } returns original
        coEvery { projects.load("/before") } returns ProjectTaskSnapshot()
        val writeStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { chats.bindWorkspacePath(any(), any(), any()) } coAnswers { writeStarted.complete(Unit); finishWrite.await(); 1 }
        val sessions = SessionUseCase(chats, mockk(), mockk(), StewardSettingsStore.Noop)
        sessions.projectTasks = Provider { projects }
        val binding = launch { sessions.bindWorkspace("s", "/after") { true } }
        writeStarted.await()
        val execution = async { sessions.awaitWorkspaceBinding("s"); "ready" }
        testScheduler.runCurrent()
        assertFalse(execution.isCompleted)
        finishWrite.complete(Unit)
        binding.join()
        assertEquals("ready", execution.await())
        coEvery { chats.bindWorkspacePath(any(), any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val cancelledBinding = launch { sessions.bindWorkspace("s", "/after") { true } }
        testScheduler.runCurrent()
        val waiting = async { sessions.awaitWorkspaceBinding("s"); true }
        testScheduler.runCurrent()
        assertFalse(waiting.isCompleted)
        cancelledBinding.cancel()
        cancelledBinding.join()
        assertTrue(waiting.await())
    }

    @Test fun linkedProjectAndBusySessionsCannotChangeWorkspace() = runTest {
        val chats = mockk<ChatSessionDao>(relaxed = true)
        val projects = mockk<ProjectTaskRepository>()
        val original = ChatSessionEntity("s", "history", 1L, 2L, workspacePath = "/before")
        coEvery { chats.getById("s") } returns original
        val sessions = SessionUseCase(chats, mockk(), mockk(), StewardSettingsStore.Noop)
        sessions.projectTasks = Provider { projects }
        coEvery { projects.load("/before") } returns ProjectTaskSnapshot(projects = listOf(
            ProjectPlan("p", "/before", "project", requirements = "goal", flowSessionIds = listOf("s"))))
        try { sessions.bindWorkspace("s", "/after") { true }; fail("project moved") }
        catch (expected: IllegalStateException) { assertEquals("SESSION_WORKSPACE_FIXED", expected.message) }
        coEvery { projects.load("/before") } returns ProjectTaskSnapshot()
        try { sessions.bindWorkspace("s", "/after") { false }; fail("busy session moved") }
        catch (expected: IllegalStateException) { assertEquals("SESSION_BUSY", expected.message) }
        coVerify(exactly = 0) { chats.bindWorkspacePath(any(), any(), any()) }
    }
}
