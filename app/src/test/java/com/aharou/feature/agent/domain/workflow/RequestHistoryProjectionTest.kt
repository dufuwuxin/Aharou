package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.settings.data.repository.ContextPreferences
import org.junit.Assert.*
import org.junit.Test

class RequestHistoryProjectionTest {
    private val context = AgentContext(null, null, "/workspace", null,
        history = listOf(AgentMessage.UserMessage(id = "task", content = "original task")),
        currentInputCountsAsRound = false)

    @Test fun emptyRetryDoesNotAppendAnInputButRealMessagesAndModeChangesStillDo() {
        assertSame(context.history, initialRequestMessages("", context, null))
        assertEquals(2, initialRequestMessages("continue", context, null).size)
        assertEquals("\n\nnew mode", (initialRequestMessages("", context, "new mode").last() as AgentMessage.UserMessage).content)
        assertEquals(2, initialRequestMessages("", context.copy(currentInputCountsAsRound = true), null).size)
    }

    private fun batch(index: Int) = listOf(
        AgentMessage.AssistantMessage(id = "assistant-$index", content = "", toolCalls = listOf(ToolCall("call-$index", "Bash", emptyMap()))),
        AgentMessage.ToolResultMessage(id = "call-$index", toolName = "Bash",
            result = "{\"output_path\":\"/workspace/$index.log\",\"preview\":\"${"x".repeat(1000)}\"}")
    )

    @Test fun changingTransientContextAndAppendingMessagesPreserveTheHistoricalPrefix() {
        val projector = RequestHistoryProjector()
        val original = context.history
        val appended = original + AgentMessage.AssistantMessage(content = "response") +
            AgentMessage.UserMessage(content = "next request")
        val first = projector.project(original, true, ContextPreferences(), false, "dynamic one")
        val next = projector.project(appended, true, ContextPreferences(), false, "dynamic two")
        assertEquals(original, first.dropLast(1))
        assertEquals(first.dropLast(1), next.take(original.size))
        assertEquals(appended, next.dropLast(1))
        assertEquals("dynamic two", (next.last() as AgentMessage.UserMessage).content)
        assertFalse((next.last() as AgentMessage.UserMessage).countsAsRound)
    }

    @Test fun foldedReceiptsDoNotMoveAsNewResultsArriveAndRawHistoryRemainsComplete() {
        val projector = RequestHistoryProjector()
        val original = context.history + (0..9).flatMap(::batch)
        val preferences = ContextPreferences(keepTools = 2)
        val first = projector.project(original, true, preferences, true)
        val next = projector.project(original + batch(10), true, preferences, true)
        assertEquals(first.filterIsInstance<AgentMessage.ToolResultMessage>(), next.filterIsInstance<AgentMessage.ToolResultMessage>().take(10))
        assertTrue(first.filterIsInstance<AgentMessage.ToolResultMessage>().first().result.length < 300)
        assertTrue(first.filterIsInstance<AgentMessage.ToolResultMessage>().last().result.contains("preview"))
        assertTrue(original.filterIsInstance<AgentMessage.ToolResultMessage>().first().result.contains("preview"))
        assertFalse((next.last() as AgentMessage.UserMessage).countsAsRound)
    }

    @Test fun receiptSnapshotsAreBoundedDoNotRetainRawBodiesAndPruneCompactedHistory() {
        val projector = RequestHistoryProjector()
        val history = context.history + (0..599).flatMap(::batch)
        projector.project(history, true, ContextPreferences(keepTools = 2), true)
        val field = RequestHistoryProjector::class.java.getDeclaredField("receipts").apply { isAccessible = true }
        val retained = field.get(projector) as Map<*, *>
        assertEquals(512, retained.size)
        assertFalse(retained.toString().contains("x".repeat(1000)))
        projector.project(context.history, true, ContextPreferences(keepTools = 2), false)
        assertTrue((field.get(projector) as Map<*, *>).isEmpty())
    }

    @Test fun compactionChangesAreNotOverwrittenByAnOlderReceiptSnapshot() {
        val projector = RequestHistoryProjector()
        val history = context.history + (0..9).flatMap(::batch)
        projector.project(history, true, ContextPreferences(keepTools = 2), true)
        val changed = history.map { if (it is AgentMessage.ToolResultMessage && it.id == "call-0") it.copy(result = "new compacted content") else it }
        val projected = projector.project(changed, true, ContextPreferences(keepTools = 2), false)
        assertEquals("new compacted content", projected.filterIsInstance<AgentMessage.ToolResultMessage>().first().result)
    }
}
