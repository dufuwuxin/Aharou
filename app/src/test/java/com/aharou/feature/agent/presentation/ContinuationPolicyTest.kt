package com.aharou.feature.agent.presentation

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class ContinuationPolicyTest {
    @Test fun duplicateTapsDuringDatabaseAndExecutionAwaitStartOnlyOneRequest() = runTest {
        val gate = ContinuationGate()
        val database = CompletableDeferred<String?>()
        val execution = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { gate.run("s", "a", { "s" }, { false }, { database.await() }) { calls++; execution.await() } }
        runCurrent()
        assertFalse(gate.run("s", "a", { "s" }, { false }, { error("Duplicate must not query") }) { error("Duplicate must not execute") })
        database.complete("a"); runCurrent()
        assertEquals(1, calls)
        assertFalse(gate.run("s", "a", { "s" }, { false }, { "a" }) { calls++ })
        execution.complete(Unit)
        assertTrue(first.await())
        assertTrue(gate.run("s", "a", { "s" }, { false }, { "a" }) { calls++ })
        assertEquals(2, calls)
    }

    @Test fun sessionSwitchBusyStateAndChangedLatestDuringDatabaseWaitPreventExecution() = runTest {
        for (case in listOf("session", "busy", "latest")) {
            val gate = ContinuationGate()
            val database = CompletableDeferred<String?>()
            var current = "s"
            var busy = false
            val job = async { gate.run("s", "a", { current }, { busy }, { database.await() }) { error("Stale request executed") } }
            runCurrent()
            if (case == "session") current = "other"
            if (case == "busy") busy = true
            database.complete(if (case == "latest") "newer" else "a")
            assertFalse(job.await())
            assertTrue(gate.run("s", "a", { "s" }, { false }, { "a" }) {})
        }
    }

    @Test fun staleBusyAndCrossSessionSelectionsAreRejected() {
        assertTrue(continuationAllowed("s", "s", "a", "a", false))
        assertFalse(continuationAllowed("s", "s", "a", "b", false))
        assertFalse(continuationAllowed("s", "other", "a", "a", false))
        assertFalse(continuationAllowed("s", "s", "a", "a", true))
        assertFalse(continuationAllowed(null, null, "a", "a", false))
    }
    @Test fun trailingInternalFailureAndLoopStopDoNotStealTheContinuationMenu() {
        val answer = AgentUIMessage("a", MessageRole.ASSISTANT, "progress")
        val cards = listOf(answer.copy(id = "failure", isCompactionFailure = true),
            answer.copy(id = "loop", isSamplingLoopStop = true))
        assertEquals(answer.id, latestContinuationMessageId(listOf(answer) + cards))
        assertTrue(showContinuationAction(ChatMessagesState("s", listOf(answer) + cards, true), "s", answer.id, false))
    }

    @Test fun latestMeaningfulRecordNeedsNoVisibleText() {
        val user = AgentUIMessage("u", MessageRole.USER, "")
        val assistant = AgentUIMessage("a", MessageRole.ASSISTANT, "")
        val ignored = listOf(
            AgentUIMessage("t", MessageRole.TOOL, "receipt"),
            user.copy(id = "notification", isBackgroundNotification = true),
            user.copy(id = "marker", isCompactionMarker = true),
            assistant.copy(id = "summary", isContextSummary = true))
        assertEquals("a", latestContinuationMessageId(listOf(user, assistant) + ignored))
        assertEquals("u", latestContinuationMessageId(listOf(user) + ignored))
    }
}
