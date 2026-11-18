package com.aharou.feature.agent.domain.tool.container

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ExecuteCommandToolTest {
    @Test fun structuredForegroundPreservesEngineStatusAndOrdinaryToolCompatibility() = runTest {
        val engine = mockk<CommandEngine>()
        val tool = ExecuteCommandTool(engine, mockk(), mockk())
        val context = AgentContext(null, null, "/fixture", null)
        val args = mapOf("command" to JsonPrimitive("printf data"))
        val result = CommandResult("stale_value\n[退出码: 0]", 7, true)
        coEvery { engine.runCommandSyncWithExit("printf data", "/fixture", any()) } returns result
        assertEquals(result, tool.executeForeground(args, context))
        assertTrue(tool.executeWithContext(args, context) is ToolResult.Success)
        val invalid = args + ("elevate" to JsonPrimitive(true))
        assertTrue(tool.executeWithContext(invalid, context) is ToolResult.Error)
        assertTrue(runCatching { tool.executeForeground(invalid, context) }.isFailure)
        assertTrue(runCatching { tool.executeForeground(args + ("background" to JsonPrimitive(true)), context) }.isFailure)
        coVerify(exactly = 2) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }
}
