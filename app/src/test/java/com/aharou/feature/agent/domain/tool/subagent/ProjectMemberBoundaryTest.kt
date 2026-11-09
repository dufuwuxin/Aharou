package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectMemberBoundaryTest {
    private val sessions = mockk<SessionUseCase>(relaxed = true)
    private val definitions = mockk<AgentDefinitionRepository>()
    private val registry = TeamRunRegistry()
    private val bus = SubAgentEventBus(MaxRunningSource { 1 }, CommunicationEnabledSource { true }, registry)
    private val tool = TaskTool(sessions, mockk(), mockk(), bus, definitions, mockk(), mockk(), SubAgentWriteLease(), mockk(), registry)
    private val parent = ChatSessionEntity("root", "project", 1, 1, "workspace")
    private val child = parent.copy(id = "child", parentId = parent.id, subagentType = "coder")
    private val context = AgentContext(null, null, "workspace", null, sessionId = parent.id)

    private fun prepare() {
        coEvery { sessions.getSessionById(parent.id) } returns parent
        coEvery { sessions.getSessionById(child.id) } returns child
        every { definitions.listEnabled() } returns listOf(AgentDefinitionEntry(
            AgentDefinition("coder", "writes", prompt = "role"), AgentDefinitionScope.GLOBAL))
        every { sessions.newSubSessionEntity(any(), any(), any(), any(), any(), any(), any(), any()) } returns child
    }

    @Test fun projectCreatesARealMemberWithoutTeamParametersAndStopsOnlyItsOwnChild() = runBlocking {
        prepare()
        val result = tool.createProjectMember("task", "implement", "coder", context) as ToolResult.Success
        assertEquals(child.id, result.data.jsonObject.getValue("id").jsonPrimitive.content)
        coVerify(exactly = 1) { sessions.upsertSession(child) }
        verify { sessions.newSubSessionEntity("task", parent.id, parent, "coder", null, null, null, null) }
        assertTrue(child.id in bus.activeSubSessionIds.value)
        assertEquals("NOT_YOUR_SUBAGENT", (tool.stopProjectMember(child.id, context.copy(sessionId = "other")) as ToolResult.Error).code)
        assertTrue(child.id in bus.activeSubSessionIds.value)
        assertTrue(tool.stopProjectMember(child.id, context) is ToolResult.Success)
        assertEquals(SubAgentEventType.STOPPED, bus.terminalEvent(child.id)?.type)
        assertFalse(child.id in bus.activeSubSessionIds.value)
    }

    @Test fun internalCreationKeepsDepthCapacityAndRoleValidation() = runBlocking {
        prepare()
        assertEquals("AGENT_NOT_FOUND", (tool.createProjectMember("task", "work", "missing", context) as ToolResult.Error).code)
        bus.emit(SubAgentEvent("busy", parent.id, SubAgentEventType.SPAWNED))
        assertEquals("MAX_SUBAGENTS_REACHED", (tool.createProjectMember("task", "work", null, context) as ToolResult.Error).code)
        bus.emit(SubAgentEvent("busy", parent.id, SubAgentEventType.STOPPED))
        val nested = child.copy(id = "nested", parentId = child.id)
        coEvery { sessions.getSessionById(nested.id) } returns nested
        assertEquals("MAX_SUBAGENT_DEPTH_EXCEEDED", (tool.createProjectMember("task", "work", null, context.copy(sessionId = nested.id)) as ToolResult.Error).code)
        coVerify(exactly = 0) { sessions.upsertSession(any()) }
    }

    @Test fun internalDefaultRoleWorksButPublicCreateAndStopStayRejected() = runBlocking {
        prepare()
        assertTrue(tool.createProjectMember("task", "work", null, context) is ToolResult.Success)
        for (action in listOf("create", "stop")) {
            val args = buildJsonObject { put("action", action); put("id", child.id) }
            assertEquals("INVALID_ARGS", (tool.executeWithContext(args, context) as ToolResult.Error).code)
        }
        assertTrue(child.id in bus.activeSubSessionIds.value)
        assertEquals(setOf("action", "team", "goal"), tool.parameters.keys)
        assertEquals("NO_SESSION", (tool.stopProjectMember(child.id, context.copy(sessionId = null)) as ToolResult.Error).code)
    }
    @Test fun cancellationUsesTheRealOwnedStopBoundary() = runBlocking {
        prepare()
        val started = CompletableDeferred<String>()
        val job = launch {
            val result = tool.createProjectMember("task", "work", null, context) as ToolResult.Success
            val id = result.data.jsonObject.getValue("id").jsonPrimitive.content
            try { started.complete(id); awaitCancellation() }
            finally { withContext(NonCancellable) { tool.stopProjectMember(id, context) } }
        }
        val id = started.await()
        job.cancelAndJoin()
        assertEquals(SubAgentEventType.STOPPED, bus.terminalEvent(id)?.type)
        assertFalse(id in bus.activeSubSessionIds.value)
    }

}
