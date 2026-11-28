package com.aharou.feature.agent.domain.runner

import android.content.Context
import com.aharou.R
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test fun exceptionSettlesOnlyPendingToolsFromTheFailedTurn() = runBlocking {
        val fixture = Fixture()
        fixture.toolRows["tool_previous"] = ToolRow("a", SessionUseCase.PENDING_TOOL_MARKER, false, callId = "previous")
        val otherStarted = CompletableDeferred<Unit>()
        fixture.turn("b") {
            emit(AgentEvent.ToolCallStarted("other", "readFile", "other args"))
            otherStarted.complete(Unit)
            awaitCancellation()
        }
        val other = fixture.start(this, "b")
        try {
            withTimeout(5_000) { otherStarted.await() }
            val failure = IllegalStateException("workflow failed after starting a tool")
            fixture.turn("a") {
                emit(AgentEvent.ToolCallStarted("finished", "readFile", "original args"))
                emit(AgentEvent.ToolCallFinished("finished", "readFile", "real result", isError = false))
                emit(AgentEvent.ToolCallStarted("unfinished", "readFile", "unfinished args"))
                fixture.assertPending("unfinished")
                throw failure
            }
            try {
                fixture.runner.run(fixture.request("a")).collect()
                fail("the workflow exception must escape the runner")
            } catch (caught: IllegalStateException) {
                assertSame(failure, caught)
            }
            fixture.assertSettled("unfinished", "a", "unfinished args")
            assertEquals(ToolRow("a", "real result", false, "original args", callId = "finished"), fixture.row("finished"))
            fixture.assertPending("previous")
            fixture.assertPending("other")
            assertTrue(other.isActive)
        } finally {
            other.cancelAndJoin()
        }
    }

    @Test fun cancellationSettlesThePersistedPlaceholderAndStillEscapesCollection() = runBlocking {
        val fixture = Fixture()
        val started = CompletableDeferred<Unit>()
        fixture.turn("a") {
            emit(AgentEvent.ToolCallStarted("cancelled", "Bash", "tool args"))
            emit(AgentEvent.ToolCallProgress("cancelled", "Bash", "partial output"))
            started.complete(Unit)
            awaitCancellation()
        }
        val cancellation = CancellationException("cancel test turn")
        val caught = CompletableDeferred<CancellationException>()
        val turn = launch {
            try {
                fixture.runner.run(fixture.request("a")).collect()
            } catch (failure: CancellationException) {
                caught.complete(failure)
                throw failure
            }
        }
        try {
            withTimeout(5_000) { started.await() }
            fixture.assertPending("cancelled")
            turn.cancel(cancellation)
            withTimeout(5_000) { turn.join() }
            assertTrue(turn.isCancelled)
            assertEquals(cancellation.message, withTimeout(5_000) { caught.await() }.message)
            fixture.assertSettled("cancelled", "a", "tool args")
            assertEquals("partial output\n\nTurn cancelled", fixture.row("cancelled").content)
        } finally {
            turn.cancelAndJoin()
        }
    }

    @Test fun failedEventThenCompletedSettlesThePendingToolWithoutChangingTerminalEvents() = runBlocking {
        val fixture = Fixture()
        val failure = AgentEvent.Failed("workflow reported a failure")
        fixture.turn("a") {
            emit(AgentEvent.ToolCallStarted("failed", "readFile", "tool args"))
            fixture.assertPending("failed")
            emit(failure)
            fixture.assertPending("failed")
            emit(AgentEvent.Completed)
        }
        val events = mutableListOf<AgentEvent>()
        fixture.runner.run(fixture.request("a")).collect { events.add(it) }
        fixture.assertSettled("failed", "a", "tool args")
        assertEquals(listOf(failure, AgentEvent.Completed), events.takeLast(2))
    }

    @Test fun concurrentTurnsKeepDistinctRowsForTheSameProviderCallId() = runBlocking {
        val fixture = Fixture()
        val firstStarted = CompletableDeferred<Unit>()
        fixture.turn("a") {
            emit(AgentEvent.ToolCallStarted("reused", "readFile", "first args"))
            firstStarted.complete(Unit)
            awaitCancellation()
        }
        val first = fixture.start(this, "a")
        try {
            withTimeout(5_000) { firstStarted.await() }
            val secondStarted = CompletableDeferred<Unit>()
            fixture.turn("b") {
                emit(AgentEvent.ToolCallStarted("reused", "readFile", "second args"))
                secondStarted.complete(Unit)
                awaitCancellation()
            }
            val second = fixture.start(this, "b")
            try {
                withTimeout(5_000) { secondStarted.await() }
                val firstId = fixture.toolRows.entries.single { it.value.sessionId == "a" }.key
                val secondRow = fixture.toolRows.entries.single { it.value.sessionId == "b" }.toPair()
                assertTrue(firstId != secondRow.first)
                first.cancelAndJoin()
                assertTrue(second.isActive)
                fixture.assertSettled("reused", "a", "first args")
                fixture.assertPending("reused", "b")
                assertEquals(secondRow.second, fixture.toolRows[secondRow.first])
            } finally {
                second.cancelAndJoin()
            }
            fixture.assertSettled("reused", "b", "second args")
        } finally {
            first.cancelAndJoin()
        }
    }

    @Test fun sequentialReuseAndFinishWithoutStartGetDistinctRowsInOneTurn() = runBlocking {
        val fixture = Fixture()
        val visibleRows = AgentTurnToolRows()
        val messageIds = mutableListOf<String>()
        fixture.turn("a") {
            emit(AgentEvent.ToolCallStarted("reused", "readFile", "first args"))
            emit(AgentEvent.ToolCallFinished("reused", "readFile", "first result", isError = false))
            emit(AgentEvent.ToolCallFinished("reused", "readFile", "second result", isError = true))
        }
        fixture.runner.run(fixture.request("a"), visibleRows).collect { event ->
            if (event is AgentEvent.ToolCallFinished) messageIds += visibleRows.messageIdFor(event.id)
        }
        assertEquals(2, messageIds.distinct().size)
        val results = messageIds.map(fixture.toolRows::getValue)
        assertEquals(listOf("first result", "second result"), results.map { it.content })
        assertTrue(results.all { it.callId == "reused" })
    }

    @Test fun cancellationDuringNormalCompletionTakesPrecedenceOverSettlementFailure() = runBlocking {
        val fixture = Fixture()
        val settlementStarted = CompletableDeferred<Unit>()
        val releaseSettlement = CompletableDeferred<Unit>()
        val writeFailure = IllegalStateException("settlement write failed")
        fixture.beforeSettlement = {
            settlementStarted.complete(Unit)
            releaseSettlement.await()
            throw writeFailure
        }
        fixture.turn("a") {
            emit(AgentEvent.ToolCallStarted("unfinished", "readFile", "args"))
            emit(AgentEvent.Completed)
        }
        val caught = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                fixture.runner.run(fixture.request("a")).collect()
            } catch (failure: Throwable) {
                caught.complete(failure)
                if (failure is CancellationException) throw failure
            }
        }
        try {
            withTimeout(5_000) { settlementStarted.await() }
            val cancellation = CancellationException("cancel during settlement")
            collector.cancel(cancellation)
            releaseSettlement.complete(Unit)
            withTimeout(5_000) { collector.join() }
            val failure = withTimeout(5_000) { caught.await() }
            assertTrue(failure is CancellationException)
            assertEquals(cancellation.message, failure.message)
            assertTrue(writeFailure in failure.suppressed)
        } finally {
            releaseSettlement.complete(Unit)
            collector.cancelAndJoin()
        }
    }

    @Test fun cancellationAfterFinishCommitDoesNotOverwriteTheRealResult() = runBlocking {
        val fixture = Fixture()
        val cancellation = CancellationException("finish committed before cancellation")
        fixture.afterToolResultWrite = { throw cancellation }
        fixture.turn("a") {
            emit(AgentEvent.ToolCallStarted("committed", "readFile", "original args"))
            emit(AgentEvent.ToolCallFinished("committed", "readFile", "actual result", isError = false))
        }
        try {
            fixture.runner.run(fixture.request("a")).collect()
            fail("the write cancellation must escape the runner")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        val row = fixture.row("committed")
        assertEquals(ToolRow("a", "actual result", false, "original args", callId = "committed"), row)
    }

    private data class ToolRow(
        val sessionId: String,
        val content: String,
        val isError: Boolean,
        val args: String? = null,
        val timestamp: Long = 0L,
        val callId: String,
    )

    private class Fixture {
        val coordinator = ProjectTaskExecutionCoordinator()
        val tasks = mockk<ProjectTaskRepository>(relaxed = true)
        val workflow = mockk<AgentWorkflow>()
        val toolRows = mutableMapOf<String, ToolRow>()
        var afterToolResultWrite: () -> Unit = {}
        var beforeSettlement: suspend () -> Unit = {}
        private var timestamp = 0L
        private val toolTurns = mutableMapOf<String, Flow<AgentEvent>>()
        val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
        val leases = mockk<SubAgentWriteLease>()
        val started = listOf("a", "b", "child").associateWith { CompletableDeferred<AgentContext>() }
        val release = started.keys.associateWith { CompletableDeferred<Unit>() }
        val events = started.keys.associateWith { mutableListOf<AgentEvent>() }
        val runner: AgentTurnRunner

        init {
            val androidContext = mockk<Context>(relaxed = true)
            every { androidContext.getString(R.string.chat_turn_cancelled) } returns "Turn cancelled"
            every { androidContext.getString(R.string.chat_tool_failed) } returns "Tool failed"
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
            every { persistence.nextTimestamp() } answers { ++timestamp }
            coEvery { persistence.persistPendingTool(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                toolRows[arg(1)] = ToolRow(arg(0), arg(5), false, arg(4), arg(6), arg(2))
            }
            coEvery { persistence.settlePendingTool(any(), any(), any(), any(), any()) } coAnswers {
                beforeSettlement()
                val id = arg<String>(1)
                val row = toolRows[id]
                if (row != null && row.sessionId == arg<String>(0) && row.callId == arg<String>(2) &&
                    row.timestamp == arg<Long>(3) && row.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER)
                ) toolRows[id] = row.copy(content = arg(4), isError = true)
            }
            coEvery {
                persistence.persist(any(), MessageRole.TOOL, any(), id = any(), toolCallId = any(),
                    toolName = any(), toolArgs = any(), isError = any(), attachments = any())
            } coAnswers {
                toolRows[arg(3)] = ToolRow(arg(0), arg(2), arg(8), arg(7), callId = arg(5))
                afterToolResultWrite()
            }
            every { workflow.executeEvents(any(), any(), any(), any()) } answers {
                val context = secondArg<AgentContext>()
                val id = requireNotNull(context.sessionId)
                toolTurns[id] ?: flow {
                    started.getValue(id).complete(context)
                    release.getValue(id).await()
                    emit(AgentEvent.AssistantDelta("$id finished"))
                }
            }
            runner = AgentTurnRunner(workflow, registry, mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), sessions, coordinator, tasks, persistence,
                mockk<CheckpointManager>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                leases, workspaces, questions, gate, androidContext)
        }

        fun turn(id: String, block: suspend FlowCollector<AgentEvent>.() -> Unit) {
            toolTurns[id] = flow(block)
        }

        fun row(id: String, sessionId: String? = null): ToolRow = toolRows.values.single {
            it.callId == id && (sessionId == null || it.sessionId == sessionId)
        }

        fun assertPending(id: String, sessionId: String? = null) {
            val row = row(id, sessionId)
            assertTrue(row.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER))
            assertFalse(row.isError)
        }

        fun assertSettled(id: String, sessionId: String, args: String) {
            val row = row(id, sessionId)
            assertEquals(sessionId, row.sessionId)
            assertEquals(args, row.args)
            assertFalse(row.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER))
            assertTrue(row.isError)
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
