package com.aharou.feature.agent.domain.tool.container

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.CommandEvent
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ExecuteCommandToolTest {
    @Test fun structuredForegroundPreservesEngineStatusAndOrdinaryToolCompatibility() = runTest {
        val engine = mockk<CommandEngine>()
        val settings = mockk<com.aharou.feature.settings.data.repository.ContainerSettingsRepository>()
        every { settings.redactEnvOutputEnabledSnapshot() } returns true
        every { settings.redactEnvOutputState() } returns ContainerSettingsRepository.RedactionState(true, 0)
        val tool = ExecuteCommandTool(engine, mockk(), mockk(), settings)
        val context = AgentContext(null, null, "/fixture", null)
        val args = mapOf("command" to JsonPrimitive("printf data"))
        val result = CommandResult("TOKEN=private-token", 7, true, mapOf("TOKEN" to "private-token"))
        coEvery { engine.runCommandSyncWithExit("printf data", "/fixture", any()) } returns result
        assertEquals(result, tool.executeForeground(args, context))
        val returned = tool.executeWithContext(args, context) as ToolResult.Success
        val payload = (returned.data as JsonPrimitive).content
        assertTrue(payload.contains("[已隐藏]"))
        assertFalse(payload.contains("private-token"))
        assertTrue(payload.contains("[退出码: 7]"))
        val invalid = args + ("elevate" to JsonPrimitive(true))
        assertTrue(tool.executeWithContext(invalid, context) is ToolResult.Error)
        assertTrue(runCatching { tool.executeForeground(invalid, context) }.isFailure)
        assertTrue(runCatching { tool.executeForeground(args + ("background" to JsonPrimitive(true)), context) }.isFailure)
        coVerify(exactly = 2) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun streamUsesTheEnvironmentSnapshotFromEachCommandEvent() = runTest {
        val engine = mockk<CommandEngine>()
        val settings = mockk<com.aharou.feature.settings.data.repository.ContainerSettingsRepository>()
        every { settings.redactEnvOutputEnabledSnapshot() } returns true
        every { settings.redactEnvOutputState() } returns ContainerSettingsRepository.RedactionState(true, 0)
        every { engine.runCommandStream(any(), any(), any()) } returns flowOf(
            CommandEvent.Line("TOKEN=private-token", mapOf("TOKEN" to "private-token")),
            CommandEvent.Exit(0, mapOf("TOKEN" to "private-token"))
        )
        val tool = ExecuteCommandTool(engine, mockk(), mockk(), settings)
        val events = tool.executeStream(
            mapOf("command" to JsonPrimitive("printf TOKEN")),
            AgentContext(null, null, "/fixture", null)
        ).toList()
        assertEquals(2, events.size)
        assertFalse(events.first().toString().contains("private-token"))
        assertFalse(events.last().toString().contains("private-token"))
        assertTrue(events.first().toString().contains("[已隐藏]"))
    }
}
