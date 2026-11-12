package com.aharou.feature.agent.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMapProjectionTest {
    private fun message(id: String, role: MessageRole, text: String = id) =
        AgentUIMessage(id = id, role = role, content = text, timestamp = 123L)

    @Test fun emptyPageHasNoNodes() {
        val result = projectConversationMap(emptyList(), false)
        assertTrue(result.nodes.isEmpty())
        assertEquals(0, result.loadedMessageCount)
        assertFalse(result.hasMore)
    }

    @Test fun initialAssistantAndToolRecordsArePreserved() {
        val result = projectConversationMap(listOf(message("a", MessageRole.ASSISTANT), message("t", MessageRole.TOOL)), false)
        val prelude = result.nodes.single()
        assertNull(prelude.userSummary)
        assertEquals("a", prelude.startMessageId)
        assertEquals(123L, prelude.timestamp)
        assertEquals(2, prelude.messageCount)
        assertEquals(1, prelude.assistantCount)
        assertEquals(1, prelude.toolCount)
    }

    @Test fun consecutiveUsersStartDistinctRoundsAndCountsRemainLocal() {
        val result = projectConversationMap(listOf(
            message("pre", MessageRole.ASSISTANT), message("u1", MessageRole.USER),
            message("u2", MessageRole.USER), message("a", MessageRole.ASSISTANT), message("t", MessageRole.TOOL)
        ), false)
        assertEquals(listOf("pre", "u1", "u2"), result.nodes.map { it.id })
        assertEquals(listOf(1, 1, 3), result.nodes.map { it.messageCount })
        assertEquals(listOf(1, 0, 1), result.nodes.map { it.assistantCount })
        assertEquals(listOf(0, 0, 1), result.nodes.map { it.toolCount })
    }

    @Test fun duplicateIdsUseFirstOccurrenceWithoutDuplicateKeysOrCounts() {
        val user = message("u", MessageRole.USER, "first")
        val result = projectConversationMap(listOf(user, user.copy(content = "later"), message("t", MessageRole.TOOL)), false)
        assertEquals(2, result.loadedMessageCount)
        assertEquals("first", result.nodes.single().userSummary)
        assertEquals(1, result.nodes.single().toolCount)
    }

    @Test fun summariesAreBoundedAndWhitespaceIsCollapsed() {
        val result = projectConversationMap(listOf(message("u", MessageRole.USER, " hello\n  world " + "x".repeat(2000))), false)
        val summary = result.nodes.single().userSummary!!
        assertTrue(summary.startsWith("hello world"))
        assertEquals(CONVERSATION_MAP_SUMMARY_LIMIT, summary.length)
    }

    @Test fun projectionDoesNotInventUnloadedHistory() {
        val page = listOf(message("last", MessageRole.USER))
        val result = projectConversationMap(page, true)
        assertTrue(result.hasMore)
        assertEquals(1, result.loadedMessageCount)
        assertEquals(listOf("last"), result.nodes.map { it.id })
    }

    @Test fun systemAndCompactionRecordsDoNotCreateUserRounds() {
        val records = listOf(
            message("system", MessageRole.USER).copy(isBackgroundNotification = true),
            message("summary", MessageRole.USER).copy(isContextSummary = true),
            message("user", MessageRole.USER),
            message("marker", MessageRole.USER).copy(isCompactionMarker = true)
        )
        val result = projectConversationMap(records, false)
        assertEquals(2, result.nodes.size)
        assertNull(result.nodes.first().userSummary)
        assertEquals(listOf(2, 2), result.nodes.map { it.messageCount })
    }
}
