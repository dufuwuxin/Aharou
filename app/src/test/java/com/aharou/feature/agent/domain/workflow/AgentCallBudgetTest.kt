package com.aharou.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aharou.feature.agent.domain.model.AgentMessage

class AgentCallBudgetTest {
    @Test fun hardLimitLeavesRoomForLongToolDrivenTasks() {
        assertEquals(64, MAX_LLM_CALLS_PER_TURN)
    }

    @Test fun allowsCallsUntilTheHardLimit() {
        assertFalse(isLlmCallBudgetExhausted(0))
        assertFalse(isLlmCallBudgetExhausted(MAX_LLM_CALLS_PER_TURN - 1))
        assertTrue(isLlmCallBudgetExhausted(MAX_LLM_CALLS_PER_TURN))
        assertTrue(isLlmCallBudgetExhausted(MAX_LLM_CALLS_PER_TURN + 1))
    }
    @Test fun exhaustedBudgetCreatesVisibleFinishedFailureState() {
        val original = StatefulAgentWorkflow.AgentSessionState(
            messages = listOf(AgentMessage.UserMessage(content = "keep this history")),
            iterations = MAX_LLM_CALLS_PER_TURN
        )
        val stopped = original.stopAtCallBudget()
        assertTrue(stopped.isFinished)
        assertEquals(LLM_CALL_BUDGET_EXCEEDED_CODE, stopped.errorCode)
        assertNotNull(stopped.error)
        assertEquals(original.messages, stopped.messages)
    }

}
