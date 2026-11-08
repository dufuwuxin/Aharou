package com.aharou.feature.agent.domain.tool.mode

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlanApprovalManagerTest {
    private val dao = mockk<ChatSessionDao>()
    private val manager = PlanApprovalManager(dao)
    private val session = ChatSessionEntity("s", "session", 0, 0, mode = "AUTO")

    @Before fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } returns Unit
    }

    @After fun tearDown() { unmockkObject(FileLogger) }

    @Test fun refineWaitsForDatabaseRollbackBeforeNextRequest() = runTest {
        var stored = session
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.getById("s") } answers { stored }
        coEvery { dao.upsert(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            stored = firstArg()
        }
        val decision = async { manager.awaitApproval("plan", "s") }
        runCurrent()
        assertEquals("AUTO", stored.mode)
        manager.resolve(manager.pendingApproval.value!!.id, PlanApprovalChoice.REFINE)
        runCurrent()
        entered.await()
        assertFalse("REFINE must wait until PLAN is persisted", decision.isCompleted)
        release.complete(Unit)
        assertEquals(PlanApprovalChoice.REFINE, decision.await())
        assertEquals("PLAN", stored.mode)
        assertEquals("PLAN", stored.mode)
        assertNull(manager.pendingApproval.value)
        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    @Test fun approvePreservesExecutionModeWithoutRollback() = runTest {
        val decision = async { manager.awaitApproval("plan", "s") }
        runCurrent()
        manager.resolve(manager.pendingApproval.value!!.id, PlanApprovalChoice.APPROVE)
        assertEquals(PlanApprovalChoice.APPROVE, decision.await())
        coVerify(exactly = 0) { dao.getById(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test fun rollbackFailureDoesNotResumeWorkflowAsRefined() = runTest {
        val failure = IllegalStateException("rollback fixture failure")
        coEvery { dao.getById("s") } returns session
        coEvery { dao.upsert(any()) } throws failure
        val decision = async { runCatching { manager.awaitApproval("plan", "s") } }
        runCurrent()
        manager.resolve(manager.pendingApproval.value!!.id, PlanApprovalChoice.REFINE)
        assertSame(failure, decision.await().exceptionOrNull())
        assertNull(manager.pendingApproval.value)
    }

    @Test fun cancellationWaitsForRollbackAndPreservesCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var restored = false
        coEvery { dao.getById("s") } returns session
        coEvery { dao.upsert(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            restored = true
        }
        val decision = async { manager.awaitApproval("plan", "s") }
        runCurrent()
        decision.cancel()
        runCurrent()
        entered.await()
        assertFalse(decision.isCompleted)
        release.complete(Unit)
        decision.join()
        assertTrue(restored)
        assertTrue(decision.isCancelled)
        assertNull(manager.pendingApproval.value)
    }

    @Test fun cancellationRetainsRollbackFailureAsSuppressed() = runTest {
        val failure = IllegalStateException("rollback fixture failure")
        coEvery { dao.getById("s") } returns session
        coEvery { dao.upsert(any()) } throws failure
        val observed = CompletableDeferred<CancellationException>()
        val decision = async {
            try {
                manager.awaitApproval("plan", "s")
            } catch (e: CancellationException) {
                observed.complete(e)
                throw e
            }
        }
        runCurrent()
        decision.cancel(CancellationException("cancel fixture"))
        decision.join()
        val suppressed = observed.await().suppressed
        assertTrue("Rollback failure must be retained as a suppressed exception", suppressed.isNotEmpty())
        assertTrue("The original rollback failure must remain reachable", suppressed.any { item ->
            generateSequence(item) { it.cause }.any { it === failure }
        })
        assertTrue(decision.isCancelled)
        assertNull(manager.pendingApproval.value)
    }
}
