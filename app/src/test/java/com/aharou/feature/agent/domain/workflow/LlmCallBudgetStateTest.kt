package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.session.DualModelSettings
import org.junit.Assert.*
import org.junit.Test

class LlmCallBudgetStateTest {
    @Test fun eachModelGetsItsOwnRecoveryAndReturningDoesNotResetAttempts() {
        val budget = LlmCallBudgetState(DualModelSettings(), "planning", 100, 200)
        budget.compactionAttemptFailed = true
        budget.overflowRecoveryAttempted = true
        budget.selectProvider("execution")
        assertEquals(0, budget.baselineEstimate)
        assertEquals(0, budget.baselineUsage)
        assertFalse(budget.compactionAttemptFailed)
        assertFalse(budget.overflowRecoveryAttempted)
        budget.overflowRecoveryAttempted = true
        budget.selectProvider("planning")
        assertTrue(budget.compactionAttemptFailed)
        assertTrue(budget.overflowRecoveryAttempted)
        budget.selectProvider("execution")
        assertFalse(budget.compactionAttemptFailed)
        assertTrue(budget.overflowRecoveryAttempted)
    }

    @Test fun repeatedRequestsOnSameModelPreserveCalibrationAndRecoveryFlags() {
        val budget = LlmCallBudgetState(DualModelSettings(), "planning", 100, 200)
        budget.compactionAttemptFailed = true
        budget.selectProvider("planning")
        assertEquals(100, budget.baselineEstimate)
        assertEquals(200, budget.baselineUsage)
        assertTrue(budget.compactionAttemptFailed)
    }
}
