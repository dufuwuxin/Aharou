package com.aharou.core.config

import android.content.Context
import com.aharou.feature.agent.data.ConfigTeamControlFields
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.config.ConfigTool
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class TeamConfigIdentityTest {
    @Test fun mainAgentTeamDetailsUseTheAuthorizedWorkspaceRegistry() = runBlocking {
        val sessions = mockk<SessionUseCase>()
        coEvery { sessions.getSessionById("main") } returns mockk { every { parentId } returns null }
        val fields = mockk<ConfigTeamControlFields>()
        val scoped = mockk<ConfigTeamControlFields>()
        val path = "teams.project:6162.routes"
        every { fields.forWorkspace("/authorized") } returns scoped
        every { scoped.mainAgentMayRead(path) } returns true
        every { scoped.registerInto(any()) } answers {
            firstArg<ConfigRegistry>().register(com.aharou.core.config.fields.ClosureField(
                path = path, displayName = "Fixture", description = "Fixture", valueSchema = ConfigSchema.Str(),
                access = ConfigAccess.READONLY, reader = { ConfigValue.Str("authorized-workspace") },
                writer = { error("read-only fixture") }))
        }
        val result = ConfigTool(mockk<Context>(), fields, sessions).executeWithContext(mapOf(
            "action" to JsonPrimitive("get"), "path" to JsonPrimitive(path)),
            AgentContext(null, null, "/authorized", null, sessionId = "main"))
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).data.toString().contains("authorized-workspace"))
        verify(exactly = 1) { scoped.registerInto(any()) }
    }

    @Test fun missingStoredSessionIsNotAMainAgentEvenWhenItsDefinitionIsNull() = runBlocking {
        val sessions = mockk<SessionUseCase>()
        coEvery { sessions.getSessionById("missing") } returns null
        val fields = mockk<ConfigTeamControlFields>()
        val tool = ConfigTool(mockk<Context>(), fields, sessions)
        val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("get"),
            "path" to JsonPrimitive("teams.project:6162.nodes")), AgentContext(null, null, "/workspace", null, sessionId = "missing"))
        assertEquals("TEAM_DETAILS_RESTRICTED", (result as ToolResult.Error).code)
        verify { fields wasNot Called }
    }
}
