package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import org.junit.Assert.*
import org.junit.Test

class RequestPrefixTrackerTest {
    @Test fun distinguishesAppendOnlyHistoryFromSystemAndHistoricalChanges() {
        val tracker = RequestPrefixTracker()
        val original = listOf(AgentMessage.UserMessage("u", "request"))
        assertNull(tracker.record("s", "stable", original, emptyList()))
        val appended = original + AgentMessage.AssistantMessage("a", "progress")
        val samePrefix = tracker.record("s", "stable", appended, emptyList())!!
        assertFalse(samePrefix.systemChanged)
        assertFalse(samePrefix.toolsChanged)
        assertEquals(1, samePrefix.unchangedMessages)
        val changed = tracker.record("s", "new system", listOf(AgentMessage.UserMessage("u", "changed")), emptyList())!!
        assertTrue(changed.systemChanged)
        assertEquals(0, changed.unchangedMessages)
        assertNull(tracker.record("other-provider", "stable", original, emptyList()))
    }
}
