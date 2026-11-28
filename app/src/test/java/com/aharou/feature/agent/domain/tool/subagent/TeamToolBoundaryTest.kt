package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.Called
import com.aharou.feature.agent.domain.session.SessionUseCase
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamToolBoundaryTest {
    private val sessions = mockk<SessionUseCase>(relaxed = true)
    private val teams = mockk<TeamTasks>()
    private val tool = TaskTool(sessions, mockk(), mockk(), mockk(), mockk(), teams,
        mockk(), mockk(), mockk(), mockk())
    private val context = AgentContext(null, null, "", null, sessionId = "main")

    @Test fun schemaOnlyOffersTeamIntroductionAndTeamRun() {
        assertEquals(setOf("action", "team", "goal", "stageId", "taskId", "member", "sharedGoal", "taskBriefs"), tool.parameters.keys)
        assertEquals(listOf("teams", "team_run", "team_assign"), tool.parameters.getValue("action").enum)
        val schema = tool.toJsonSchema()
        assertEquals(setOf("tool_title", "action", "team", "goal", "stageId", "taskId", "member", "sharedGoal", "taskBriefs"), (schema["properties"] as Map<*, *>).keys)
        assertTrue((schema["required"] as List<*>).contains("tool_title"))
    }

    @Test fun oldActionsCannotReachMemberOperations() = runBlocking {
        for (action in listOf("create", "read", "send", "roles", "list", "team", "tell", "grant", "pipeline", "stop", "del")) {
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive(action)), context)
            assertEquals(action, "INVALID_ARGS", (result as ToolResult.Error).code)
        }
    }

    @Test fun injectedMemberParametersAreRejectedBeforeDispatch() = runBlocking {
        for (key in listOf("agent", "prompt", "workflow", "_teamRunId", "unknown")) {
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("team_run"),
                "team" to JsonPrimitive("team"), "goal" to JsonPrimitive("goal"),
                "tool_title" to JsonPrimitive("分配团队任务"), key to JsonPrimitive("injected")), context)
            assertEquals(key, "INVALID_ARGS", (result as ToolResult.Error).code)
        }
        verify { teams wasNot Called }
    }

    @Test fun listingAcceptsSchemaTitleWithoutForwardingIt() = runBlocking {
        val args = mapOf("action" to JsonPrimitive("teams"), "team" to JsonPrimitive("team"))
        val expected = ToolResult.Success(buildJsonObject { put("name", "team") })
        every { teams.list(args, context, any()) } returns expected

        val result = tool.executeWithContext(args + ("tool_title" to JsonPrimitive("查看团队介绍")), context)

        assertEquals(expected, result)
        verify(exactly = 1) { teams.list(args, context, any()) }
    }

    @Test fun teamRunAcceptsSchemaTitleWithoutForwardingIt() = runBlocking {
        val args = mapOf("action" to JsonPrimitive("team_run"),
            "team" to JsonPrimitive("team"), "goal" to JsonPrimitive("goal"))
        coEvery { teams.run(args, any(), any(), any(), any()) } returns ToolResult.Success(buildJsonObject {
            put("state", "completed"); put("runId", "run")
        })

        val result = tool.executeWithContext(args + ("tool_title" to JsonPrimitive("分配团队任务")), context)

        assertTrue(result is ToolResult.Success)
        coVerify(exactly = 1) { teams.run(args, any(), any(), any(), any()) }
    }

    @Test fun ordinaryTeamCanDeclareSharedGoalWithoutDiscoveringPrivateSteps() = runBlocking {
        val args = mapOf("action" to JsonPrimitive("team_run"), "team" to JsonPrimitive("team"), "sharedGoal" to JsonPrimitive("shared"))
        coEvery { teams.run(args, any(), any(), any(), any()) } returns ToolResult.Success(buildJsonObject { put("state", "done") })
        assertTrue(tool.executeWithContext(args, context) is ToolResult.Success)
        coVerify(exactly = 1) { teams.run(args, any(), any(), any(), any()) }
    }

    @Test fun nonMainSessionCannotSendTargetedSteps() = runBlocking {
        coEvery { sessions.getSessionById("main") } returns mockk { every { parentId } returns "real-parent" }
        val target = buildJsonObject { put("stageId", "s1"); put("taskId", "t1"); put("brief", "one exact task") }
        val args = mapOf("action" to JsonPrimitive("team_run"), "team" to JsonPrimitive("team"), "goal" to JsonPrimitive("shared"),
            "taskBriefs" to JsonArray(listOf(target)))
        val result = tool.executeWithContext(args, context) as ToolResult.Error
        assertEquals("TEAM_ADMIN_DENIED", result.code)
        verify { teams wasNot Called }
    }

    @Test fun teamReturnDoesNotExposeMemberBodiesOrExecutionHistory() = runBlocking {
        coEvery { teams.run(any(), any(), any(), any(), any()) } returns ToolResult.Success(buildJsonObject {
            put("state", "blocked"); put("runId", "run"); put("reworkRounds", 1)
            put("outputs", "PRIVATE_MEMBER_BODY"); put("history", "PRIVATE_HISTORY")
            put("reason", "PRIVATE_MEMBER_NAME")
        })
        val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("team_run"),
            "team" to JsonPrimitive("team"), "goal" to JsonPrimitive("goal")), context) as ToolResult.Success
        val data = result.data as JsonObject
        assertEquals(JsonPrimitive("blocked"), data["state"])
        assertEquals(JsonPrimitive("run"), data["runId"])
        assertFalse(data.toString().contains("PRIVATE_"))
        assertEquals(setOf("team", "state", "runId", "reworkRounds"), data.keys)
    }

    @Test fun callerCannotClaimMainAdminOrSmuggleHiddenFields() = runBlocking {
        for (key in listOf("mainSession", "admin", "includeDetails", "_teamRunId")) {
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("teams"), key to JsonPrimitive(true)), context)
            assertEquals("INVALID_ARGS", (result as ToolResult.Error).code)
        }
    }

    @Test fun mainIdentityComesFromStoredParentAndQueryUsesActualContext() = runBlocking {
        coEvery { sessions.getSessionById("main") } returns mockk { every { parentId } returns null }
        every { teams.list(any(), context, true) } returns ToolResult.Success(JsonPrimitive("managed"))
        tool.executeWithContext(mapOf("action" to JsonPrimitive("teams")), context)
        verify { teams.list(any(), context, true) }
        every { teams.list(any(), context, false) } returns ToolResult.Success(JsonPrimitive("limited"))
        coEvery { sessions.getSessionById("main") } returns mockk { every { parentId } returns "real-parent" }
        tool.executeWithContext(mapOf("action" to JsonPrimitive("teams")), context)
        verify { teams.list(any(), context, false) }
    }

    @Test fun listingIncludesCharterButNotTeamInternals() {
        val repository = mockk<AgentTeamRepository>()
        val entry = mockk<AgentTeamEntry>()
        every { entry.team } returns AgentTeam("public team", "public introduction", emptyList(), "PRIVATE_CHARTER")
        every { repository.forWorkspace(context.projectRoot) } returns repository
        every { repository.listAll() } returns listOf(entry)
        val listing = TeamTasks(repository, mockk(), mockk(), mockk(), mockk())
            .list(mapOf("team" to JsonPrimitive("public team")), context) as ToolResult.Success
        assertTrue(listing.data.toString().contains("public introduction"))
        assertTrue(listing.data.toString().contains("PRIVATE_CHARTER"))
        assertEquals(setOf("name", "description", "charter"), (listing.data as JsonObject).keys)
    }
}
