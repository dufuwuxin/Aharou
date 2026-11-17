package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTrimmerTest {

    @Test fun resumeInputDoesNotEvictTheLastRealRound() {
        val history = rounds(2)
        val resume = AgentMessage.UserMessage(content = "resume", countsAsRound = false)
        assertEquals(history.takeLast(4) + resume, (history + resume).trimToRecentRounds(1))
        val real = AgentMessage.UserMessage(content = "resume")
        assertEquals(listOf(real), (history + real).trimToRecentRounds(1))
    }

    private fun user(text: String) = AgentMessage.UserMessage(content = text)
    private fun assistant(text: String) = AgentMessage.AssistantMessage(content = text)
    private fun toolCall(id: String) = assistant("").copy(toolCalls = listOf(ToolCall(id, "readFile", emptyMap())))
    private fun toolResult(id: String) = AgentMessage.ToolResultMessage(id = id, toolName = "readFile", result = "ok")

    private fun rounds(n: Int): List<AgentMessage> = buildList {
        repeat(n) { i ->
            add(user("u$i"))
            add(toolCall("c$i"))
            add(toolResult("c$i"))
            add(assistant("a$i"))
        }
    }

    @Test
    fun zeroRounds_keepsEverything() {
        val messages = rounds(3)
        assertEquals(messages, messages.trimToRecentRounds(0))
    }

    @Test
    fun fewerRoundsThanLimit_keepsEverything() {
        val messages = rounds(2)
        assertEquals(messages, messages.trimToRecentRounds(5))
    }

    @Test
    fun keepsOnlyLastNRounds() {
        val messages = rounds(4)
        val trimmed = messages.trimToRecentRounds(2)
        assertEquals(8, trimmed.size)
        assertTrue(trimmed.first() is AgentMessage.UserMessage)
        assertEquals("u2", (trimmed.first() as AgentMessage.UserMessage).content)
    }

    @Test
    fun neverSplitsToolCallFromItsResult() {
        val messages = rounds(3)
        val trimmed = messages.trimToRecentRounds(1)
        // 只剩最后一轮：user + toolCall + toolResult + assistant，工具对完整
        assertEquals(4, trimmed.size)
        assertTrue(trimmed[1] is AgentMessage.AssistantMessage)
        assertTrue((trimmed[1] as AgentMessage.AssistantMessage).toolCalls.isNotEmpty())
        assertEquals("c2", (trimmed[2] as AgentMessage.ToolResultMessage).id)
    }
    @Test fun preservesOnlyLatestCompleteSummaryBeforeCutoff() {
        val marker = user(CONTEXT_COMPACTION_MARKER)
        val latest = listOf(marker, assistant("latest summary"))
        val recent = rounds(1)
        val messages = listOf(marker, assistant("old summary")) + rounds(1) + latest + rounds(1) + recent
        assertEquals(latest + recent, messages.trimToRecentRounds(1))
        assertEquals(recent, messages.trimToRecentRounds(1, preserveSummary = false))
        assertEquals(messages, messages.trimToRecentRounds(0, preserveSummary = false))
    }

    @Test fun markersAreNotRoundsAndRecentSummaryIsNotDuplicated() {
        val messages = rounds(1) + user(CONTEXT_COMPACTION_MARKER) + assistant("summary")
        assertEquals(messages, messages.trimToRecentRounds(1))
        val recent = rounds(1) + user(CONTEXT_COMPACTION_MARKER) + assistant("recent summary")
        assertEquals(recent, (rounds(2) + recent).trimToRecentRounds(1))
    }

    @Test fun incompleteAndToolCallingSummariesAreNotReintroduced() {
        val recent = rounds(1)
        for (incomplete in listOf(listOf(user(CONTEXT_COMPACTION_MARKER)),
            listOf(user(CONTEXT_COMPACTION_MARKER), toolCall("summary-tool")))) {
            assertEquals(recent, (rounds(1) + incomplete + recent).trimToRecentRounds(1))
        }
    }
    @Test fun retainedImagesAndToolPairsRemainUnchangedAndOrdered() {
        val image = com.aharou.feature.agent.domain.model.AgentImage("image/png", "image-bytes")
        val recent = listOf(user("with image").copy(images = listOf(image)), toolCall("pair"),
            toolResult("pair").copy(images = listOf(image)), assistant("done"))
        val summary = listOf(user(CONTEXT_COMPACTION_MARKER), assistant("summary"))
        assertEquals(summary + recent, (summary + rounds(2) + recent).trimToRecentRounds(1))
    }
}
