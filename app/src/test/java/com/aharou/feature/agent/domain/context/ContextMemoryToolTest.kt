package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.memory.MemoryTool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ContextMemoryToolTest {
    private val memories = mockk<MemoryRepository>(relaxed = true)
    private val library = mockk<ContextLibraryRepository>()
    private val tool = MemoryTool(memories, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), library)
    private val temporary = Memory("temp", "private-summary", MemoryScope.PROJECT, content = "private-session-body",
        contextCategory = ContextCategory.TEMPORARY, contextLoad = ContextLoad.FULL, contextSessionId = "s1", version = "v1")
    private fun ctx(session: String?) = AgentContext(null, null, "/workspace/a", null, sessionId = session)

    @Test fun oldReadSaveEditDeleteAndUndoCannotCrossSessionBoundary() = runBlocking {
        every { memories.findMemory("temp", any(), any()) } returns temporary
        listOf("read", "save", "edit", "delete", "undo").forEach { action ->
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive(action), "name" to JsonPrimitive("temp")), ctx("s2"))
            assertTrue("$action must reject another session", result is ToolResult.Error)
            assertFalse(result.toString().contains("private-session-body"))
        }
        verify(exactly = 0) { memories.deleteMemory(any(), any(), any()) }
    }

    @Test fun oldListHidesTemporaryEntriesInAnotherOrMissingSession() = runBlocking {
        every { memories.listMemories(any(), any()) } returns listOf(temporary)
        listOf("s2", null).forEach { session ->
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("list")), ctx(session))
            assertFalse(result.toString().contains("private-summary"))
        }
        val own = tool.executeWithContext(mapOf("action" to JsonPrimitive("list")), ctx("s1"))
        assertTrue(own.toString().contains("private-summary"))
    }

    @Test fun libraryReadExplicitlyReportsPendingAndPreservesBody() = runBlocking {
        val pending = ContextEntry("MEMORY:PROJECT:pending", "pending", "summary", "pending-body", MemoryScope.PROJECT,
            ContextKind.REFERENCE, ContextLoad.FULL, ContextOrigin.MEMORY, "source", pending = true, version = "v1")
        every { library.list(any(), any()) } returns listOf(pending)
        val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("library_read"), "name" to JsonPrimitive(pending.id)), ctx("s1"))
        assertTrue(result is ToolResult.Success)
        assertTrue(result.toString().contains("pending=true"))
        assertTrue(result.toString().contains("pending-body"))
        assertTrue(result.toString().contains("Unconfirmed"))
    }
}
