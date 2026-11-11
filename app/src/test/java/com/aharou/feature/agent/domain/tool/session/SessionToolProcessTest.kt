package com.aharou.feature.agent.domain.tool.session

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.prompt.InjectedContextStore
import com.aharou.feature.agent.domain.receipt.ToolCallReceiptRepository
import com.aharou.feature.agent.domain.session.MessageArchiveStore
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SessionToolProcessTest {
    @get:Rule val temp = TemporaryFolder()
    private val sessions = mockk<SessionUseCase>()
    private val dao = mockk<ChatSessionDao>()
    private val messages = mockk<MessagePersistenceUseCase> {
        coEvery { restoreAll(any()) } coAnswers { firstArg<List<AgentMessageEntity>>() }
    }
    private val receipts = mockk<ToolCallReceiptRepository>()
    private val tool = SessionTool(receipts, InjectedContextStore(), mockk(), sessions, dao, messages)
    private val context = AgentContext(null, null, "/w", null, sessionId = "root")
    private val root = ChatSessionEntity("root", "root", 0, 0, workspacePath = "/w")
    private val member = root.copy(id = "member", parentId = "root")

    private suspend fun query(action: String, id: String = "member", offset: Int = 0,
        contentOffset: Int = 0, caller: AgentContext = context): ToolResult = tool.executeWithContext(mapOf(
        "action" to JsonPrimitive(action), "session_id" to JsonPrimitive(id),
        "offset" to JsonPrimitive(offset), "content_offset" to JsonPrimitive(contentOffset),
        "limit" to JsonPrimitive(1)), caller)

    @Test fun memberProcessIncludesSavedReasoningCallsAndResultsAcrossPages() = runTest {
        coEvery { sessions.getSessionById("root") } returns root
        coEvery { sessions.getSessionById("member") } returns member
        coEvery { messages.getMessagesOnce("member") } returns listOf(
            AgentMessageEntity("a", "member", "ASSISTANT", "answer", 1,
                toolCallsJson = "[]", reasoning = "r".repeat(2000) + "saved-tail"),
            AgentMessageEntity("t", "member", "TOOL", "result", 2,
                toolCallId = "call", toolName = "readFile", toolArgs = "{}"))
        val first = (query("read") as ToolResult.Success).data.jsonObject
        assertEquals(1, first.getValue("nextOffset").jsonPrimitive.int)
        val result = first.getValue("messages").jsonArray.single().jsonObject
        assertEquals("TOOL", result.getValue("role").jsonPrimitive.content)
        assertEquals("result", result.getValue("content").jsonPrimitive.content)
        assertEquals("readFile", result.getValue("toolName").jsonPrimitive.content)
        val second = (query("read", offset = 1) as ToolResult.Success).data.jsonObject
        assertEquals(JsonNull, second["nextOffset"])
        val assistant = second.getValue("messages").jsonArray.single().jsonObject
        assertEquals("[]", assistant.getValue("toolCalls").jsonPrimitive.content)
        assertEquals(2000, assistant.getValue("nextContentOffset").jsonPrimitive.int)
        val tail = (query("read", offset = 1, contentOffset = 2000) as ToolResult.Success)
            .data.jsonObject.getValue("messages").jsonArray.single().jsonObject
        assertEquals("saved-tail", tail.getValue("reasoning").jsonPrimitive.content)
        assertEquals(JsonNull, tail["nextContentOffset"])
    }

    @Test fun sameWorkspaceMemberCanReadRootAndInspectMemberReceipts() = runTest {
        coEvery { sessions.getSessionById("root") } returns root
        coEvery { sessions.getSessionById("member") } returns member
        coEvery { messages.getMessagesOnce("root") } returns emptyList()
        coEvery { receipts.querySession("member", "/w", null, null, null, null, 2, 0) } returns emptyList()
        assertTrue(query("read", "root", caller = context.copy(sessionId = "member")) is ToolResult.Success)
        assertTrue(query("tool_calls") is ToolResult.Success)
        assertEquals("CROSS_SESSION_DENIED", (query("context") as ToolResult.Error).code)
    }

    @Test fun listIncludesMembersAndForeignWorkspaceNeverReachesStorage() = runTest {
        coEvery { sessions.getSessionById("root") } returns root
        coEvery { dao.getAllSessionsByWorkspaceOnce("/w") } returns listOf(member)
        val listed = (query("list") as ToolResult.Success).data.toString()
        assertTrue(listed.contains("member"))
        coEvery { sessions.getSessionById("member") } returns member.copy(workspacePath = "/foreign")
        for (action in listOf("read", "tool_calls", "context")) {
            assertEquals("CROSS_WORKSPACE_DENIED", (query(action) as ToolResult.Error).code)
        }
        coVerify(exactly = 0) { messages.getMessagesOnce(any()) }
        coVerify(exactly = 0) { receipts.querySession(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun negativeProcessOffsetsAreRejected() = runTest {
        coEvery { sessions.getSessionById("root") } returns root
        coEvery { sessions.getSessionById("member") } returns member
        assertEquals("INVALID_ARGS", (query("read", offset = -1) as ToolResult.Error).code)
        assertEquals("INVALID_ARGS", (query("read", contentOffset = -1) as ToolResult.Error).code)
        coVerify(exactly = 0) { messages.getMessagesOnce(any()) }
    }
    @Test fun archivedCompactedProcessRestoresOnlyTheRequestedPageBeforeFieldSlicing() = runTest {
        val archives = MessageArchiveStore(temp.newFolder("archives"))
        val longText = "r".repeat(MessagePersistenceUseCase.MAX_CONTENT_BYTES + 5000) + "saved-archive-tail"
        val original = AgentMessageEntity("archived", "member", "ASSISTANT", "saved", 1,
            reasoning = longText, isCompacted = true)
        val stored = archives.prepareForStorage(original)
        assertNotEquals(longText, stored.reasoning)
        coEvery { sessions.getSessionById("root") } returns root
        coEvery { sessions.getSessionById("member") } returns member
        coEvery { messages.getMessagesOnce("member") } returns listOf(stored,
            AgentMessageEntity("newer", "member", "TOOL", "new result", 2))
        coEvery { messages.restoreAll(any()) } coAnswers {
            val page = firstArg<List<AgentMessageEntity>>()
            assertEquals(listOf("archived"), page.map { it.id })
            archives.restoreAll(page)
        }
        val result = (query("read", offset = 1, contentOffset = longText.length - 18) as ToolResult.Success)
            .data.jsonObject.getValue("messages").jsonArray.single().jsonObject
        assertEquals(longText.takeLast(18), result.getValue("reasoning").jsonPrimitive.content)
        assertEquals(JsonNull, result["nextContentOffset"])
        coVerify(exactly = 1) { messages.restoreAll(match { it.size == 1 && it.single().id == "archived" }) }
    }

}
