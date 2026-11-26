package com.aharou.feature.agent.domain.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCallBudgetTest {
    @Test fun allowsCallsUntilTheHardLimit() {
        assertFalse(isLlmCallBudgetExhausted(0))
        assertFalse(isLlmCallBudgetExhausted(MAX_LLM_CALLS_PER_TURN - 1))
        assertTrue(isLlmCallBudgetExhausted(MAX_LLM_CALLS_PER_TURN))
        assertTrue(isLlmCallBudgetExhausted(MAX_LLM_CALLS_PER_TURN + 1))
    }
}
