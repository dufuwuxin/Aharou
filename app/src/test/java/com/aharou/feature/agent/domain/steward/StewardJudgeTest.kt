package com.aharou.feature.agent.domain.steward

import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StewardJudgeTest {
    private val workflow = mockk<AgentWorkflow>()
    private val library = mockk<ContextLibraryRepository>()
    private val sessions = mockk<SessionUseCase>()
    private val roles = mockk<AgentDefinitionRepository>()
    private val judge = StewardJudge(workflow, library, sessions, roles)

    private fun prompts(system: String, input: String) {
        every { library.resolvePromptForContext(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (firstArg<String>() == "agent/steward-judge-system.md") system else input
        }
    }

    @Test fun enabledNativePromptsReachModelAndParseContinuation() = runTest {
        prompts("supervisor", "{instruction} / {report}")
        coEvery { workflow.completeOnce("supervisor", "task / partial report", null) } returns
            """{"completed":false,"reason":"unfinished","next_instruction":"next"}"""
        assertEquals(StewardJudgement(false, "unfinished", "next"), judge.judge("task", "partial report", null))
        coVerify(exactly = 1) { workflow.completeOnce("supervisor", "task / partial report", null) }
    }

    @Test fun explicitlyUnavailableNativeSystemDoesNotReviveOrCallModel() = runTest {
        prompts("", "{instruction} / {report}")
        assertNull(judge.judge("task", "report", null))
        coVerify(exactly = 0) { workflow.completeOnce(any(), any(), any()) }
    }

    @Test fun missingInputDoesNotPretendCompletion() = runTest {
        prompts("supervisor", "")
        assertNull(judge.judge("task", "report", null))
        coVerify(exactly = 0) { workflow.completeOnce(any(), any(), any()) }
    }
}
