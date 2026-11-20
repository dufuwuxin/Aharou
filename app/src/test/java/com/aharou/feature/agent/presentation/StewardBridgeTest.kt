package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.steward.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StewardBridgeTest {
    private val settings = mockk<StewardSettingsStore>(relaxed = true)
    private val judge = mockk<StewardJudgePort>()
    private val messages = mockk<MessagePersistenceUseCase>()
    private val flags = mutableMapOf<String, Boolean>()
    private fun fixture(): Pair<StewardController, StewardBridge> {
        every { settings.enabled(any()) } answers { flags[firstArg()] == true }
        every { settings.setEnabled(any(), any()) } answers { flags[firstArg()] = secondArg() }
        every { settings.maxLoops(any()) } returns 5
        coEvery { messages.getMessagesOnce(any()) } returns emptyList()
        coEvery { judge.judge(any(), any(), any()) } returns StewardJudgement(false, nextInstruction = "next")
        val controller = StewardController(settings, judge)
        controller.enable("s", "old task")
        return controller to StewardBridge(controller, messages)
    }

    @Test fun duplicateCompletionCanOnlyProduceOneContinuation() = runTest {
        val (_, bridge) = fixture()
        bridge.onRequestStarted("s", "task", false)
        assertEquals("next", bridge.onTurnFinished("s")?.invoke())
        assertNull(bridge.onTurnFinished("s"))
        coVerify(exactly = 1) { judge.judge(any(), any(), "s") }
    }

    @Test fun userRequestInvalidatesDeferredInstructionAndReplacesAnchor() = runTest {
        val (_, bridge) = fixture()
        val old = bridge.onTurnFinished("s")!!
        bridge.onRequestStarted("s", "new task", false)
        assertNull(old.invoke())
        assertEquals("next", bridge.onTurnFinished("s")?.invoke())
        coVerify { judge.judge("new task", any(), "s") }
    }

    @Test fun automaticTurnKeepsAnchorAndLoopBudgetWhileAllowingNextJudgement() = runTest {
        val (controller, bridge) = fixture()
        assertEquals("next", bridge.onTurnFinished("s")?.invoke())
        bridge.onRequestStarted("s", "next", true)
        assertEquals("next", bridge.onTurnFinished("s")?.invoke())
        assertEquals(2, controller.stateOf("s").loopCount)
        coVerify(exactly = 2) { judge.judge("old task", any(), "s") }
    }

    @Test fun lateJudgeCannotContinueAfterNewUserRequest() = runTest {
        val (controller, bridge) = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { judge.judge("old task", any(), "s") } coAnswers {
            entered.complete(Unit)
            release.await()
            StewardJudgement(false, nextInstruction = "stale")
        }
        val old = async { bridge.onTurnFinished("s") }
        entered.await()
        bridge.onRequestStarted("s", "new task", false)
        release.complete(Unit)
        assertNull(old.await())
        assertEquals(StewardStatus.Monitoring, controller.stateOf("s").status)
        assertEquals("next", bridge.onTurnFinished("s")?.invoke())
    }

    @Test fun enablingJustBeforeCompletionLoadsTheAnchorInTheCompletionPath() = runTest {
        val (controller, bridge) = fixture()
        controller.enable("s", "")
        coEvery { messages.getMessagesOnce("s") } returns listOf(
            com.aharou.feature.agent.data.local.entity.AgentMessageEntity("u", "s", "USER", "current task", 1L),
            com.aharou.feature.agent.data.local.entity.AgentMessageEntity("a", "s", "ASSISTANT", "unfinished", 2L),
        )
        assertEquals("next", bridge.onTurnFinished("s")?.invoke())
        coVerify(exactly = 1) { judge.judge("current task", "unfinished", "s") }
    }

    @Test fun staleAnchorReadCannotBindAfterPermissionWasRevokedAndReenabled() = runTest {
        val (controller, bridge) = fixture()
        controller.enable("s", "")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { messages.getMessagesOnce("s") } coAnswers {
            entered.complete(Unit); release.await()
            listOf(com.aharou.feature.agent.data.local.entity.AgentMessageEntity("u", "s", "USER", "stale", 1L))
        }
        val finishing = async { bridge.onTurnFinished("s") }
        entered.await()
        controller.disable("s")
        controller.enable("s", "new task")
        release.complete(Unit)
        assertNull(finishing.await())
        coVerify(exactly = 0) { judge.judge(any(), any(), any()) }
    }

    @Test fun disablingInvalidatesDeferredInstruction() = runTest {
        val (controller, bridge) = fixture()
        val next = bridge.onTurnFinished("s")!!
        controller.disable("s")
        assertNull(next.invoke())
        assertNull(bridge.onTurnFinished("s"))
    }
}
