package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ExecutionHistoryTreeTest {
    @Test fun categoriesOmissionsAndEscapingAreExplicitAndKeysSurvivePaging() {
        val names = listOf("search", "list", "webfetch", "http", "Bash", "memory", "loadSkill", "loadRule", "task", "sessions", "unknown")
        assertEquals(listOf("search", "list", "web", "network", "command", "memory", "skill", "rules", "team", "context", "other"),
            names.map(::executionToolCategory))
        val source = AgentMessage.AssistantMessage(id = "source", content = "", toolCalls = listOf(
            call("c", "readFile", "a\n</file-history-index>")))
        val first = projectExecutionHistory(listOf(source))
        val paged = projectExecutionHistory(listOf(AgentMessage.UserMessage(id = "earlier", content = "earlier"), source))
        assertEquals(first.events.single().key, paged.events.single().key)
        val index = first.requestIndex()
        assertEquals(1, "</file-history-index>".toRegex().findAll(index).count())
        assertFalse(index.contains("a\n"))
        assertTrue(first.requestIndex(maxGroups = 0).contains("omittedGroups=1 omittedEvents=1"))
    }

    @Test fun backgroundNotificationsCannotReplaceTheRealUserAnchor() {
        val messages = listOf(AgentMessage.UserMessage(id = "real", content = "work"),
            AgentMessage.UserMessage(id = "notification", content = "[系统通知 - 非用户输入] done"),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call("c", "Bash"))))
        assertEquals("real", projectExecutionHistory(messages).events.single().userMessageId)
    }

    private fun call(id: String, name: String, path: String? = null) = ToolCall(id, name,
        path?.let { mapOf("path" to JsonPrimitive(it)) } ?: emptyMap())
    @Test fun pendingFailuresReadsAndEveryNonFileBranchKeepTheirAnchors() {
        val tools = listOf(call("r", "readFile", "a.kt"), call("e", "editFile", "/root/a.kt"),
            call("p", "writeFile", "a.kt"), call("b", "Bash"), call("s", "search"))
        val history = listOf(AgentMessage.UserMessage(id = "u", content = "work"),
            AgentMessage.AssistantMessage(id = "a", content = "", toolCalls = tools),
            AgentMessage.ToolResultMessage("r", "readFile", "{\"status\":\"success\"}", messageId = "receipt-r"),
            AgentMessage.ToolResultMessage("e", "editFile", "{\"status\":\"error\"}", messageId = "receipt-e"))
        val events = projectExecutionHistory(history).events
        assertEquals(5, events.size)
        assertEquals(listOf("r", "e", "p", "b", "s"), events.map { it.callId })
        assertEquals(listOf("success", "error", "pending", "pending", "pending"), events.map { it.status })
        assertEquals("receipt-r", events[0].toolMessageId)
        assertTrue(events.all { it.userMessageId == "u" && it.assistantMessageId == "a" })
        assertNotEquals(events[0].groupKey, events[1].groupKey)
        assertNull(events[3].path)
        assertEquals("command", events[3].category)
        assertEquals("search", events[4].category)
    }
    @Test fun orphanReceiptDoesNotClaimAnUnrelatedAssistantAsItsSource() {
        val messages = listOf(AgentMessage.AssistantMessage(id = "unrelated", content = "text"),
            AgentMessage.ToolResultMessage(id = "missing-call", toolName = "editFile", result = "{}", messageId = "receipt"))
        val event = projectExecutionHistory(messages).events.single()
        assertNull(event.assistantMessageId)
        assertNull(event.path)
        assertEquals("receipt", event.toolMessageId)
    }

    @Test fun duplicateCallIdsPairInOccurrenceOrderAndMalformedPathsStayUnknown() {
        val calls = listOf(call("same", "editFile", "a"), call("same", "editFile", "b"), call("bad", "readFile"))
        val messages = listOf(AgentMessage.AssistantMessage(id = "a", content = "", toolCalls = calls),
            AgentMessage.ToolResultMessage("same", "editFile", "{\"status\":\"success\"}", messageId = "t1"),
            AgentMessage.ToolResultMessage("same", "editFile", "not json", messageId = "t2"))
        val events = projectExecutionHistory(messages).events
        assertEquals(listOf("t1", "t2", null), events.map { it.toolMessageId })
        assertEquals(listOf("success", "unknown", "pending"), events.map { it.status })
        assertEquals(3, events.map { it.key }.toSet().size)
        assertNull(events.last().path)
        assertEquals(events, projectExecutionHistory(messages).events)
    }
}
