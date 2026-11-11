package com.aharou.feature.agent.domain.tool.session

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.prompt.InjectedContextStore
import com.aharou.feature.agent.domain.receipt.ToolCallReceiptRepository
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionObservabilityTest {
    private val sessions = mockk<SessionUseCase>()
    private val receipts = mockk<ToolCallReceiptRepository>()
    private val contexts = InjectedContextStore()
    private val tool = SessionTool(receipts, contexts, mockk(), sessions, mockk(), mockk())
    private val context = AgentContext(null, null, "/w", null, sessionId = "current")
    private fun session(id: String, workspace: String = "/w") = ChatSessionEntity(id, id, 0, 0, workspacePath = workspace)

    @Test fun memberContextRemainsPrivateWithoutBlockingSavedProcessReads() = runBlocking {
        coEvery { sessions.getSessionById("current") } returns session("current")
        coEvery { sessions.getSessionById("member") } returns session("member").copy(parentId = "current")
        for (action in listOf("context")) {
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive(action),
                "session_id" to JsonPrimitive("member")), context)
            assertEquals(action, "CROSS_SESSION_DENIED", (result as ToolResult.Error).code)
        }
        coVerify(exactly = 0) { receipts.querySession(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun otherWorkspaceReceiptsAreRejectedBeforeQuerying() = runBlocking {
        coEvery { sessions.getSessionById("current") } returns session("current")
        coEvery { sessions.getSessionById("foreign") } returns session("foreign", "/other")
        val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("tool_calls"), "session_id" to JsonPrimitive("foreign")), context)
        assertEquals("CROSS_WORKSPACE_DENIED", (result as ToolResult.Error).code)
        coVerify(exactly = 0) { receipts.querySession(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun contextDoesNotDiscloseOtherSessionsOrPrivateHeadings() = runBlocking {
        coEvery { sessions.getSessionById("current") } returns session("current")
        coEvery { sessions.getSessionById("peer") } returns session("peer")
        contexts.recordRequest("current", com.aharou.feature.agent.domain.workflow.RequestContextComposition.capture(contexts.beginRequest("current"), "## PRIVATE_HEADING_MARKER\nPRIVATE_BODY_MARKER", emptyList(), emptyList()))
        val own = tool.executeWithContext(mapOf("action" to JsonPrimitive("context")), context)
        assertTrue(own is ToolResult.Success)
        val json = (own as ToolResult.Success).data.toString()
        assertFalse(json.contains("PRIVATE_HEADING_MARKER"))
        assertFalse(json.contains("PRIVATE_BODY_MARKER"))
        assertTrue(json.contains("section_1"))
        val other = tool.executeWithContext(mapOf("action" to JsonPrimitive("context"), "session_id" to JsonPrimitive("peer")), context)
        assertEquals("CROSS_SESSION_DENIED", (other as ToolResult.Error).code)
    }

    @Test fun invalidFiltersNeverReachStorageAndValidLimitIsBounded() = runBlocking {
        coEvery { sessions.getSessionById("current") } returns session("current")
        val invalid = tool.executeWithContext(mapOf("action" to JsonPrimitive("tool_calls"), "offset" to JsonPrimitive(-1)), context)
        assertEquals("INVALID_ARGS", (invalid as ToolResult.Error).code)
        coVerify(exactly = 0) { receipts.querySession(any(), any(), any(), any(), any(), any(), any(), any()) }
        coEvery { receipts.querySession("current", "/w", null, null, null, null, 201, 0) } returns emptyList()
        val valid = tool.executeWithContext(mapOf("action" to JsonPrimitive("tool_calls"), "limit" to JsonPrimitive(999999)), context)
        assertTrue(valid is ToolResult.Success)
        coVerify(exactly = 1) { receipts.querySession("current", "/w", null, null, null, null, 201, 0) }
    }
}
