package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentContext
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolCallGuardCapacityTest {
    private val context = AgentContext(null, null, "/fixture", null, sessionId = "fixture")
    private val args = mapOf("action" to JsonPrimitive("create"))

    @Test fun capacityRetriesKeepGuardAndEventuallyStartTheMember() = runBlocking {
        var invocations = 0
        val guarded = ToolCallGuard(mockk(relaxed = true), mockk(relaxed = true)).wrap(tool {
            invocations++
            if (invocations <= 3) ToolResult.Error("capacity", "MAX_SUBAGENTS_REACHED")
            else ToolResult.Success(JsonPrimitive("member"))
        })
        repeat(3) { assertEquals("MAX_SUBAGENTS_REACHED", (guarded.executeWithContext(args, context) as ToolResult.Error).code) }
        assertTrue(guarded.executeWithContext(args, context) is ToolResult.Success)
        assertEquals(4, invocations)
    }

    @Test fun genuineFailuresStillStopTheThirdIdenticalInvocation() = runBlocking {
        var invocations = 0
        val guarded = ToolCallGuard(mockk(relaxed = true), mockk(relaxed = true)).wrap(tool {
            invocations++
            ToolResult.Error("fixture failure", "INVALID_INPUT")
        })
        repeat(2) { assertEquals("INVALID_INPUT", (guarded.executeWithContext(args, context) as ToolResult.Error).code) }
        assertEquals("TOOL_CALL_GUARDED", (guarded.executeWithContext(args, context) as ToolResult.Error).code)
        assertEquals(2, invocations)
    }

    private fun tool(run: () -> ToolResult) = object : AgentTool() {
        override val name = "task"
        override val description = "capacity fixture"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult = run()
    }
}
