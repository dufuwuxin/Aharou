package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import org.junit.Assert.*
import org.junit.Test

class ExecutionHistoryProjectionTest {
    @Test fun rawAssistantAndToolRowsShareOneProjectionAndRejectOtherSessions() {
        val rows = listOf(
            AgentMessageEntity("u", "s", "USER", "work", 1),
            AgentMessageEntity("a", "s", "ASSISTANT", "", 2,
                toolCallsJson = "[{\"id\":\"c\",\"name\":\"readFile\",\"arguments\":{\"path\":\"a.kt\"}}]"),
            AgentMessageEntity("t", "s", "TOOL", "{\"status\":\"success\"}", 3, toolCallId = "c", toolName = "readFile"),
            AgentMessageEntity("other", "other", "TOOL", "private", 4, toolCallId = "x", toolName = "Bash"))
        val projection = projectSessionExecutionHistory("s", rows, true)
        val event = projection.tree.events.single()
        assertTrue(projection.hasMore)
        assertEquals("u", event.userMessageId)
        assertEquals("a", event.assistantMessageId)
        assertEquals("t", event.toolMessageId)
        assertEquals("success", event.status)
        assertEquals(setOf("u", "a", "t"), projection.sources.keys)
        assertEquals("{\"status\":\"success\"}", projection.sources.getValue("t").message.content)
        assertFalse(projection.sources.containsKey("other"))
        assertTrue(filterExecutionEvents(projection.tree.events, "a.kt").isNotEmpty())
        assertTrue(filterExecutionEvents(projection.tree.events, "not found").isEmpty())
    }
}
