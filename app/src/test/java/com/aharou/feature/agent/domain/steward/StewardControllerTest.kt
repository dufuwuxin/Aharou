package com.aharou.feature.agent.domain.steward

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StewardControllerTest {
    private fun controller(judge: StewardJudgePort): StewardController {
        val flags = mutableMapOf<String, Boolean>()
        val settings = mockk<StewardSettingsStore>(relaxed = true)
        every { settings.enabled(any()) } answers { flags[firstArg()] == true }
        every { settings.setEnabled(any(), any()) } answers { flags[firstArg()] = secondArg() }
        every { settings.maxLoops(any()) } returns 5
        return StewardController(settings, judge)
    }

    @Test fun unavailableJudgeStopsInsteadOfPretendingToMonitor() = runTest {
        val judge = mockk<StewardJudgePort>()
        coEvery { judge.judge(any(), any(), any()) } returns null
        val controller = controller(judge)
        controller.enable("s", "task")
        assertFalse(controller.onAiIdle("s", "report") { fail("must not send") })
        assertEquals(StewardStatus.Stopped, controller.stateOf("s").status)
        assertFalse(controller.stateOf("s").checking)
    }

    @Test fun failedContinuationStopsInsteadOfPretendingToMonitor() = runTest {
        val judge = mockk<StewardJudgePort>()
        coEvery { judge.judge(any(), any(), any()) } returns StewardJudgement(false, nextInstruction = "next")
        val controller = controller(judge)
        controller.enable("s", "task")
        assertFalse(controller.onAiIdle("s", "report") { error("send failed") })
        assertEquals(StewardStatus.Stopped, controller.stateOf("s").status)
    }
}
