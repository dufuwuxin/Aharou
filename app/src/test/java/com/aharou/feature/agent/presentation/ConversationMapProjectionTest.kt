package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.presentation.component.ChatRenderItem
import com.aharou.feature.agent.presentation.component.findChatMessageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMapProjectionTest {
    private fun message(id: String, role: MessageRole, text: String = id) =
        AgentUIMessage(id = id, role = role, content = text, timestamp = id.hashCode().toLong())

    @Test fun onlyActualUserMessagesAreProjected() {
        val records = listOf(
            message("assistant", MessageRole.ASSISTANT),
            message("tool", MessageRole.TOOL),
            message("background", MessageRole.USER).copy(isBackgroundNotification = true),
            message("summary", MessageRole.USER).copy(isContextSummary = true),
            message("marker", MessageRole.USER).copy(isCompactionMarker = true),
            message("user", MessageRole.USER, "  hello\n world  ")
        )

        val result = projectConversationMap(records, false)

        assertEquals(listOf("user"), result.nodes.map { it.startMessageId })
        assertEquals("hello world", result.nodes.single().userSummary)
        assertEquals(message("user", MessageRole.USER).id.hashCode().toLong(), result.nodes.single().timestamp)
        assertEquals(records.size, result.loadedMessageCount)
        assertFalse(result.hasMore)
    }

    @Test fun userMessagesKeepStableIdsAcrossInsertionsAndReordering() {
        val original = listOf(message("u1", MessageRole.USER), message("u2", MessageRole.USER))
        val before = projectConversationMap(original, false).nodes
        val after = projectConversationMap(
            listOf(message("assistant", MessageRole.ASSISTANT), original[1], original[0]), false
        ).nodes

        assertEquals(setOf("u1", "u2"), before.map { it.startMessageId }.toSet())
        assertEquals("u2", after.first().startMessageId)
        assertEquals("u1", after.last().startMessageId)
        assertTrue(after.none { it.startMessageId == "missing" })

        val rendered = listOf("assistant", "u2", "u1").map { id ->
            val message = if (id == "assistant") message(id, MessageRole.ASSISTANT) else original.first { it.id == id }
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        }
        assertEquals(2, findChatMessageIndex(rendered, "u1"))
        assertEquals(-1, findChatMessageIndex(rendered, "missing"))
    }

    @Test fun duplicateIdsUseFirstUserOccurrence() {
        val result = projectConversationMap(
            listOf(message("u", MessageRole.USER, "first"), message("u", MessageRole.USER, "later")), false
        )
        assertEquals(1, result.nodes.size)
        assertEquals("first", result.nodes.single().userSummary)
    }

    @Test fun summariesAreBoundedAndWhitespaceIsCollapsed() {
        val result = projectConversationMap(
            listOf(message("u", MessageRole.USER, " hello\n world " + "x".repeat(2000))), false
        )
        val summary = result.nodes.single().userSummary!!
        assertTrue(summary.startsWith("hello world"))
        assertEquals(CONVERSATION_MAP_SUMMARY_LIMIT, summary.length)
    }

    @Test fun unloadedHistoryIsNotInvented() {
        val result = projectConversationMap(listOf(message("last", MessageRole.USER)), true)
        assertTrue(result.hasMore)
        assertEquals(listOf("last"), result.nodes.map { it.startMessageId })
    }
}
