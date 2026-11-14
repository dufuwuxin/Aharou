package com.aharou.feature.agent.domain.schedule

import com.aharou.feature.agent.domain.workflow.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class ScheduledTaskRunStateTest {
    @Test fun firstCompletionDoesNotStopOtherRuns() {
        val state = ScheduledTaskRunState()
        state.started(1)
        state.started(2)
        assertNull(state.finished(1))
        assertFalse(state.isIdle)
        assertEquals(2, state.finished(2))
        assertTrue(state.isIdle)
    }
    @Test fun newestDeliveryCanFinishFirst() {
        val state = ScheduledTaskRunState()
        state.started(1)
        state.started(2)
        assertNull(state.finished(2))
        assertEquals(2, state.finished(1))
    }
    @Test fun failureCannotBeOverwrittenByCompletedOrNormalFlowEnd() {
        val state = ScheduledTaskTerminalState()
        assertEquals(ScheduledTaskOutcome.FAILED, state.outcome)
        state.accept(AgentEvent.Failed("failure"))
        state.accept(AgentEvent.Completed)
        assertEquals(ScheduledTaskOutcome.FAILED, state.outcome)
    }
    @Test fun explicitCompletionIsRequiredAndCancellationWins() {
        val state = ScheduledTaskTerminalState()
        state.accept(AgentEvent.Completed)
        assertEquals(ScheduledTaskOutcome.COMPLETED, state.outcome)
        state.failed()
        assertEquals(ScheduledTaskOutcome.FAILED, state.outcome)
    }
}
