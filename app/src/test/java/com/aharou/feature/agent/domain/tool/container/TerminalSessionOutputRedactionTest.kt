package com.aharou.feature.agent.domain.tool.container

import com.aharou.feature.agent.domain.container.ContainerEnvValueRedactor
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.terminal.domain.TabInfo
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionOutputRedactionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun terminalReadReturnsTheProviderRedactedOutput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val provider = mockk<TerminalSessionProvider>()
            val settings = mockk<ContainerSettingsRepository>(relaxed = true)
            val raw = "BUILD_LABEL=fixture-value"
            every { provider.listTabs() } returns listOf(
                TabInfo("term-1", "fixture", isBackground = true, running = true, command = null,
                    workspacePath = "/workspace")
            )
            every { provider.getTabOutput("term-1") } returns raw
            every { provider.redactTabOutput("term-1", raw) } answers {
                ContainerEnvValueRedactor.redact(secondArg<String>(), mapOf("BUILD_LABEL" to "fixture-value")).text
            }
            val tool = TerminalSessionTool(provider, settings)
            val context = AgentContext(null, null, "/workspace", null)

            val result = tool.executeWithContext(
                mapOf("action" to JsonPrimitive("read"), "tab_id" to JsonPrimitive("term-1")),
                context,
            )

            val payload = (result as ToolResult.Success).data.toString()
            assertTrue(payload.contains("[已隐藏]"))
            assertFalse(payload.contains("fixture-value"))
            verify(exactly = 1) { provider.redactTabOutput("term-1", raw) }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
