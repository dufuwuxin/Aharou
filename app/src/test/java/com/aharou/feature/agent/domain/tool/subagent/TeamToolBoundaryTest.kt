package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamToolBoundaryTest {
    private val teams = mockk<TeamTasks>()
    private val tool = TaskTool(mockk(), mockk(), mockk(), mockk(), mockk(), teams,
        mockk(), mockk(), mockk(), mockk())
    private val context = AgentContext(null, null, "", null, sessionId = "main")

    @Test fun schemaOnlyOffersTeamIntroductionAndTeamRun() {
        assertEquals(setOf("action", "team", "goal"), tool.parameters.keys)
        assertEquals(listOf("teams", "team_run"), tool.parameters.getValue("action").enum)
        val schema = tool.toJsonSchema()
        assertEquals(setOf("tool_title", "action", "team", "goal"), (schema["properties"] as Map<*, *>).keys)
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
        every { teams.list(args) } returns expected

        val result = tool.executeWithContext(args + ("tool_title" to JsonPrimitive("查看团队介绍")), context)

        assertEquals(expected, result)
        verify(exactly = 1) { teams.list(args) }
    }

    @Test fun teamRunAcceptsSchemaTitleWithoutForwardingIt() = runBlocking {
        val args = mapOf("action" to JsonPrimitive("team_run"),
            "team" to JsonPrimitive("team"), "goal" to JsonPrimitive("goal"))
        coEvery { teams.run(args, any(), any()) } returns ToolResult.Success(buildJsonObject {
            put("state", "completed"); put("runId", "run")
        })

        val result = tool.executeWithContext(args + ("tool_title" to JsonPrimitive("分配团队任务")), context)

        assertTrue(result is ToolResult.Success)
        coVerify(exactly = 1) { teams.run(args, any(), any()) }
    }

    @Test fun teamReturnDoesNotExposeMemberBodiesOrExecutionHistory() = runBlocking {
        coEvery { teams.run(any(), any(), any()) } returns ToolResult.Success(buildJsonObject {
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

    @Test fun listingDoesNotReadTeamInternals() {
        val repository = mockk<AgentTeamRepository>()
        val entry = mockk<AgentTeamEntry>()
        every { entry.team } returns AgentTeam("public team", "public introduction", emptyList(), "PRIVATE_CHARTER")
        every { repository.listAll() } returns listOf(entry)
        val listing = TeamTasks(repository, mockk(), mockk(), mockk())
            .list(mapOf("team" to JsonPrimitive("public team"))) as ToolResult.Success
        assertTrue(listing.data.toString().contains("public introduction"))
        assertFalse(listing.data.toString().contains("PRIVATE_CHARTER"))
        assertEquals(setOf("name", "description"), (listing.data as JsonObject).keys)
    }
}
