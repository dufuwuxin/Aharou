package com.aharou.feature.agent.domain.runner

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.task.ProjectTaskExecutionCoordinator
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.custom.ToolInjectionGate
import com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore
import com.aharou.feature.agent.domain.tool.question.PendingUserQuestionStore
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentTurnRunnerConcurrencyTest {
    @Test fun rootsInOneWorkspacePersistAndStartBeforeEitherFlowFinishes() = runBlocking {
        val fixture = Fixture()
        val a = fixture.start(this, "a")
        var b: Job? = null
        try {
            fixture.awaitStarted("a")
            b = fixture.start(this, "b")
            fixture.awaitStarted("b")
            assertTrue(a.isActive)
            assertTrue(b.isActive)
            assertFalse(fixture.release.getValue("a").isCompleted)
            fixture.verifyUserSaved("a")
            fixture.verifyUserSaved("b")
            coVerify(exactly = 1) { fixture.tasks.requireExecutionReady(WORKSPACE, "a") }
            coVerify(exactly = 1) { fixture.tasks.requireExecutionReady(WORKSPACE, "b") }
            fixture.release.getValue("a").complete(Unit)
            a.join()
            assertTrue(b.isActive)
            fixture.release.getValue("b").complete(Unit)
            b.join()
        } finally {
            b?.cancelAndJoin()
            a.cancelAndJoin()
        }
    }

    @Test fun cancellingRootBDoesNotStopRootAOrItsNextEvent() = runBlocking {
        val fixture = Fixture()
        val a = fixture.start(this, "a")
        var b: Job? = null
        try {
            fixture.awaitStarted("a")
            b = fixture.start(this, "b")
            fixture.awaitStarted("b")
            b.cancelAndJoin()
            assertTrue(b.isCancelled)
            assertTrue(a.isActive)
            assertFalse(fixture.release.getValue("a").isCompleted)
            fixture.release.getValue("a").complete(Unit)
            withTimeout(5_000) { a.join() }
            assertEquals(listOf(AgentEvent.AssistantDelta("a finished")), fixture.events.getValue("a"))
            assertTrue(fixture.events.getValue("b").isEmpty())
            fixture.verifyUserSaved("a")
            fixture.verifyUserSaved("b")
        } finally {
            b?.cancelAndJoin()
            a.cancelAndJoin()
        }
    }

    @Test fun childBypassesParentsWorkspaceLockAndRetainsItsOwnContextAndWriteLease() = runBlocking {
        val fixture = Fixture()
        val locked = CompletableDeferred<Unit>()
        val unlock = CompletableDeferred<Unit>()
        val owner = launch {
            fixture.coordinator.withWorkspace(WORKSPACE) {
                locked.complete(Unit)
                unlock.await()
            }
        }
        var child: Job? = null
        try {
            locked.await()
            child = fixture.start(this, "child")
            val context = fixture.awaitStarted("child")
            assertTrue(owner.isActive)
            assertFalse(unlock.isCompleted)
            assertEquals("child", context.sessionId)
            assertEquals(WORKSPACE, context.projectRoot)
            assertEquals(setOf("src/child.kt"), context.writePaths)
            fixture.verifyUserSaved("child")
            coVerify(exactly = 0) { fixture.tasks.requireExecutionReady(any(), "child") }
            verify(exactly = 1) { fixture.leases.pathsFor("child") }
        } finally {
            child?.cancelAndJoin()
            owner.cancelAndJoin()
        }
    }

    @Test fun readinessFailureStillRejectsRootBeforeSavingUserOrCallingWorkflow() = runBlocking {
        val fixture = Fixture()
        coEvery { fixture.tasks.requireExecutionReady(WORKSPACE, "b") } throws
            IllegalStateException("TASK_DEPENDENCIES_OR_REQUIREMENTS_CHANGED")
        try {
            fixture.runner.run(fixture.request("b")).collect()
            fail("stale task execution must be rejected")
        } catch (failure: IllegalStateException) {
            assertEquals("TASK_DEPENDENCIES_OR_REQUIREMENTS_CHANGED", failure.message)
        }
        coVerify(exactly = 0) {
            fixture.persistence.persist(any(), MessageRole.USER, any(), id = any(), attachments = any())
        }
        verify(exactly = 0) { fixture.workflow.executeEvents(any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.persistence.deleteMessagesFromTimestamp(any(), any()) }
    }

    @Test fun rootStillWaitsForAtomicReadinessSectionAndCanBeCancelledWhileWaiting() = runBlocking {
        val fixture = Fixture()
        val locked = CompletableDeferred<Unit>()
        val unlock = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.withWorkspace(WORKSPACE) {
                locked.complete(Unit)
                unlock.await()
            }
        }
        var b: Job? = null
        try {
            locked.await()
            b = fixture.start(this, "b")
            coVerify(exactly = 0) { fixture.tasks.requireExecutionReady(any(), "b") }
            assertFalse(fixture.started.getValue("b").isCompleted)
            coVerify(exactly = 0) {
                fixture.persistence.persist("b", MessageRole.USER, any(), id = any(), attachments = any())
            }
            b.cancelAndJoin()
            unlock.complete(Unit)
            owner.join()
            assertTrue(b.isCancelled)
            assertFalse(fixture.started.getValue("b").isCompleted)
            coVerify(exactly = 0) { fixture.tasks.requireExecutionReady(any(), "b") }
        } finally {
            b?.cancelAndJoin()
            owner.cancelAndJoin()
        }
    }

    private class Fixture {
        val coordinator = ProjectTaskExecutionCoordinator()
        val tasks = mockk<ProjectTaskRepository>(relaxed = true)
        val workflow = mockk<AgentWorkflow>()
        val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
        val leases = mockk<SubAgentWriteLease>()
        val started = listOf("a", "b", "child").associateWith { CompletableDeferred<AgentContext>() }
        val release = started.keys.associateWith { CompletableDeferred<Unit>() }
        val events = started.keys.associateWith { mutableListOf<AgentEvent>() }
        val runner: AgentTurnRunner

        init {
            val sessions = mockk<SessionUseCase>(relaxed = true)
            val registry = mockk<ToolRegistry>(relaxed = true)
            val questions = mockk<PendingUserQuestionStore>()
            val workspaces = mockk<WorkspaceRepository>()
            val gate = mockk<ToolInjectionGate>(relaxed = true)
            for (id in started.keys) {
                coEvery { sessions.getSessionById(id) } returns ChatSessionEntity(
                    id, "title $id", 1, 2, workspacePath = WORKSPACE,
                    providerId = "provider", model = "model",
                    parentId = if (id == "child") "a" else null,
                )
                coEvery { persistence.buildHistory(id, any()) } returns emptyList()
                coEvery { questions.takeAnswered(id) } returns emptyList()
                every { leases.pathsFor(id) } returns if (id == "child") setOf("src/child.kt") else null
            }
            every { workspaces.current } returns MutableStateFlow(null)
            every { workspaces.workspaces } returns MutableStateFlow(emptyList())
            coEvery { gate.snapshotForProject(any()) } returns ToolSettingsStore.Snapshot(emptyList(), emptyMap())
            every { workflow.executeEvents(any(), any(), any(), any()) } answers {
                val context = secondArg<AgentContext>()
                val id = requireNotNull(context.sessionId)
                flow {
                    started.getValue(id).complete(context)
                    release.getValue(id).await()
                    emit(AgentEvent.AssistantDelta("$id finished"))
                }
            }
            runner = AgentTurnRunner(workflow, registry, mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), sessions, coordinator, tasks, persistence,
                mockk<CheckpointManager>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                leases, workspaces, questions, gate, mockk(relaxed = true))
        }

        fun request(id: String) = AgentTurnRequest(id, "message $id", projectRoot = WORKSPACE, skipTitleUpdate = true)

        fun start(scope: CoroutineScope, id: String): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runner.run(request(id)).collect { events.getValue(id).add(it) }
        }

        suspend fun awaitStarted(id: String): AgentContext = withTimeout(5_000) { started.getValue(id).await() }

        fun verifyUserSaved(id: String) {
            coVerify(exactly = 1) {
                persistence.persist(id, MessageRole.USER, "message $id", id = any(), attachments = any())
            }
        }
    }

    private companion object {
        const val WORKSPACE = "/project"
    }
}
