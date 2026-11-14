package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TeamStageConditionTest {
    @Test fun incompleteDirectoryReadCannotBecomeLanguageAbsent() {
        val files = io.mockk.mockk<com.aharou.feature.workspace.domain.FileAccessProvider>()
        io.mockk.every { files.checked() } returns files
        io.mockk.every { files.isDirectory(any()) } returns true
        io.mockk.every { files.listFiles("~/workspace") } returns listOf(
            com.aharou.feature.workspace.domain.FileEntry("src", true, 0, 0),
            com.aharou.feature.workspace.domain.FileEntry("node_modules", true, 0, 0))
        io.mockk.every { files.listFiles("~/workspace/src") } throws java.io.IOException("unreadable")
        assertNull(AgentTeamRepository(files).projectFiles())
        io.mockk.verify(exactly = 0) { files.listFiles("~/workspace/node_modules") }
    }

    @Test fun allSkippedDoesNotInvokeAnExplicitCoordinator() {
        val role = AgentDefinition("worker", "", prompt = "work")
        val team = AgentTeam("test", "", listOf(TeamStage("skip", listOf(TeamTask(agent = "worker", prompt = "x")), condition = "never")), "")
        val ref = com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRef("test", lead = "worker")
        val flow = com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow("flow", "", listOf(ref), "")
        val plan = TeamRunner.buildWorkflowLayer(flow, 1, ref, team, "goal", "", listOf(role), 1) as TeamRunner.Plan.Ready
        assertEquals(0, plan.taskCount)
        assertTrue(plan.pipeline.isEmpty())
    }

    @Test fun detectionDistinguishesAbsentUnknownAndIgnoredFiles() {
        assertEquals(TeamStageCondition.Result.SKIP, TeamStageCondition.evaluate("rust", setOf("src/main.py")))
        assertEquals(TeamStageCondition.Result.RUN, TeamStageCondition.evaluate("rust", setOf("Cargo.toml")))
        assertEquals(TeamStageCondition.Result.SKIP, TeamStageCondition.evaluate("python", setOf(".venv/main.py")))
        assertEquals(TeamStageCondition.Result.UNKNOWN, TeamStageCondition.evaluate("python", null))
        assertEquals(TeamStageCondition.Result.UNKNOWN, TeamStageCondition.evaluate("bad", emptySet()))
    }

    @Test fun serialAndGraphSkipWithoutDispatchingOrFabricatingOutput() = runBlocking {
        val role = AgentDefinition("worker", "", prompt = "work")
        val stages = listOf(
            TeamStage("rust", listOf(TeamTask(agent = "worker", prompt = "rust", id = "t")), id = "a", condition = "rust"),
            TeamStage("next", listOf(TeamTask(agent = "worker", prompt = "next", id = "t")), id = "b")
        )
        for (graph in listOf(false, true)) {
            val team = AgentTeam("test", "", stages, "", admins = listOf("worker"), members = listOf(TeamMemberRef("worker")),
                routes = if (graph) listOf(TeamRoute(TeamFlowNode("a", "t"), TeamFlowNode("b", "t"))) else emptyList())
            val dispatched = mutableListOf<String>()
            val output = TeamStageExecutor(TeamRunRegistry()).run(team, "goal", listOf(role), 1, "parent", setOf("main.py")) { batch ->
                ToolResult.Success(buildJsonObject {
                    put("state", "done")
                    put("outputs", buildJsonObject { batch.forEach {
                        val id = it.jsonObject.getValue("id").jsonPrimitive.content
                        dispatched += id; put(id, "real delivery")
                    } })
                })
            } as ToolResult.Success
            assertEquals(listOf("s2-b1-t1"), dispatched)
            assertEquals("done", output.data.jsonObject.getValue("state").jsonPrimitive.content)
            assertEquals(setOf("s2-b1-t1"), output.data.jsonObject.getValue("outputs").jsonObject.keys)
            assertTrue(output.data.jsonObject.getValue("history").toString().contains("skipped"))
        }
    }
    @Test fun workflowPlanSkipsMissingLanguageAndKeepsNextStageDependenciesValid() {
        val role = AgentDefinition("worker", "", prompt = "work")
        val team = AgentTeam("test", "", listOf(
            TeamStage("rust", listOf(TeamTask(agent = "not-installed", prompt = "rust")), condition = "rust"),
            TeamStage("next", listOf(TeamTask(agent = "worker", prompt = "next")))
        ), "")
        val ref = com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRef(teamName = "test")
        val workflow = com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow("flow", "", listOf(ref), "")
        val plan = TeamRunner.buildWorkflowLayer(workflow, 1, ref, team, "goal", "", listOf(role), 1, setOf("main.py")) as TeamRunner.Plan.Ready
        assertEquals(listOf("w1-s2-b1-t1", "w1-judge"), plan.pipeline.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertFalse("dependsOn" in plan.pipeline.first().jsonObject)
        assertEquals(listOf("w1-s2-b1-t1"), plan.pipeline.last().jsonObject.getValue("dependsOn").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(TeamRunner.buildWorkflowLayer(workflow, 1, ref, team, "goal", "", listOf(role), 1) is TeamRunner.Plan.Rejected)
    }

    @Test fun realWorkflowRunnerSkipsWholeFirstTeamAndDispatchesFollowingTeam() = runBlocking {
        val role = AgentDefinition("worker", "", prompt = "work")
        val skipped = AgentTeam("rust-only", "", listOf(TeamStage("rust",
            listOf(TeamTask(agent = "not-installed", prompt = "rust")), condition = "rust")), "")
        val following = AgentTeam("following", "", listOf(TeamStage("work",
            listOf(TeamTask(agent = "worker", prompt = "work")))), "")
        val refs = listOf(skipped, following).map { com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRef(teamName = it.name) }
        val flow = com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow("flow", "", refs, "")
        val workflows = io.mockk.mockk<com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository>()
        io.mockk.every { workflows.find("flow") } returns flow
        val teams = io.mockk.mockk<AgentTeamRepository>()
        io.mockk.every { teams.listAll() } returns listOf(skipped, following).map { AgentTeamEntry(it, AgentDefinitionScope.GLOBAL) }
        io.mockk.every { teams.projectFiles() } returns setOf("main.py")
        val definitions = io.mockk.mockk<AgentDefinitionRepository>()
        io.mockk.every { definitions.listEnabled() } returns listOf(AgentDefinitionEntry(role, AgentDefinitionScope.GLOBAL))
        val runner = WorkflowRunner(workflows, teams, definitions, MaxRunningSource { 2 })
        val dispatched = mutableListOf<String>()
        val result = runner.run(mapOf("workflow" to JsonPrimitive("flow"), "goal" to JsonPrimitive("goal"))) { batch ->
            ToolResult.Success(buildJsonObject {
                put("state", "done")
                put("outputs", buildJsonObject { batch.forEach {
                    val id = it.jsonObject.getValue("id").jsonPrimitive.content
                    dispatched += id
                    put(id, if (id.endsWith("judge")) "[[JUDGE]] HANDLE | completed" else "delivery")
                } })
            })
        } as ToolResult.Success
        assertEquals(listOf("w2-s1-b1-t1", "w2-judge"), dispatched)
        assertEquals("done", result.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertTrue(result.data.jsonObject.getValue("report").jsonPrimitive.content.contains("skipped stages"))
    }

}
