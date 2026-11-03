package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TeamStageExecutorTest {
    private val role = AgentDefinition(name = "worker", description = "", prompt = "p")
    private fun task(prompt: String = "implement") = TeamTask(agent = "worker", prompt = prompt, writePaths = listOf("one.kt"))
    private fun team() = AgentTeam("team", "", listOf(
        TeamStage("prepare", listOf(task())),
        TeamStage("implement", listOf(task())),
        TeamStage("review", listOf(task(), task()), isReview = true, rejectTo = "implement")
    ), "constraints")
    private fun result(batch: JsonArray, review: String = "TEAM_REVIEW: PASS", state: String = "done") = ToolResult.Success(buildJsonObject {
        put("state", state)
        put("outputs", buildJsonObject { batch.forEach { put(it.jsonObject.getValue("id").jsonPrimitive.content, review) } })
    })
    @Test fun failedAndUnacceptedDependenciesAreNotSuccessful() {
        assertEquals("failed", TeamStageExecutor.outcome("FAILED", true))
        assertEquals("unaccepted", TeamStageExecutor.outcome("COMPLETED", false))
        assertEquals("unaccepted", TeamStageExecutor.outcome("COMPLETED", null))
        assertEquals("timeout", TeamStageExecutor.outcome(null, null))
        assertEquals("stopped", TeamStageExecutor.outcome("STOPPED", null))
        assertFalse(TeamStageExecutor.dependenciesReady(setOf("a"), mapOf("a" to "unaccepted")))
        assertFalse(TeamStageExecutor.dependenciesReady(setOf("a"), emptyMap()))
    }
    @Test fun workflowRequiresExplicitDoneBeforeAdvancingOrAcceptingRework() {
        assertFalse(WorkflowRunner.pipelineSucceeded(ToolResult.Success(buildJsonObject { put("state", "blocked") })))
        assertFalse(WorkflowRunner.pipelineSucceeded(ToolResult.Success(buildJsonObject {})))
        assertFalse(WorkflowRunner.pipelineSucceeded(ToolResult.Error("failed", "FAILED")))
        assertTrue(WorkflowRunner.pipelineSucceeded(ToolResult.Success(buildJsonObject { put("state", "done") })))
    }
    @Test fun allReviewersMustProvideOneLegalDecision() {
        assertEquals(TeamStageExecutor.Review.PASS, TeamStageExecutor.review("TEAM_REVIEW: PASS\nevidence"))
        assertEquals(TeamStageExecutor.Review.INVALID, TeamStageExecutor.review("looks good"))
        assertEquals(TeamStageExecutor.Review.INVALID, TeamStageExecutor.review("TEAM_REVIEW: PASS\nTEAM_REVIEW: REJECT reason"))
        assertEquals(TeamStageExecutor.Review.INVALID, TeamStageExecutor.review("TEAM_REVIEW: REJECT"))
    }
    @Test fun rejectionRerunsOnlyAffectedStagesAndKeepsAllReviewOutputs() = runBlocking {
        val batches = mutableListOf<JsonArray>()
        var reviewCalls = 0
        val executor = TeamStageExecutor(TeamRunRegistry())
        val output = executor.run(team(), "goal", listOf(role), 1, "parent") { batch ->
            batches += batch
            val isReview = batch.first().jsonObject.getValue("id").jsonPrimitive.content.startsWith("s3-")
            result(batch, if (isReview && reviewCalls++ == 0) "TEAM_REVIEW: REJECT fix implementation" else "TEAM_REVIEW: PASS")
        } as ToolResult.Success
        assertEquals("done", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(1, batches.count { it.first().jsonObject.getValue("id").jsonPrimitive.content.startsWith("s1-") })
        assertEquals(2, batches.count { it.first().jsonObject.getValue("id").jsonPrimitive.content.startsWith("s2-") })
        assertTrue(batches.last().first().jsonObject.getValue("prompt").jsonPrimitive.content.contains("fix implementation"))
    }
    @Test fun missingReviewerAndExhaustedReworkBlock() = runBlocking {
        val executor = TeamStageExecutor(TeamRunRegistry())
        var calls = 0
        val output = executor.run(team(), "goal", listOf(role), 1, "parent") { batch ->
            calls++
            result(batch, "TEAM_REVIEW: REJECT concrete fix")
        } as ToolResult.Success
        assertEquals("blocked", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(7, calls)
        val missing = executor.run(team(), "goal", listOf(role), 1, "parent") { batch -> result(batch, "") } as ToolResult.Success
        assertEquals("blocked", missing.data.jsonObject.getValue("state").jsonPrimitive.content)
    }
    @Test fun pipelineDoesNotSpawnDependentAfterRejectedDelivery() = runBlocking {
        val registry = TeamRunRegistry()
        val bus = com.aharou.feature.agent.domain.subagent.SubAgentEventBus(
            com.aharou.feature.agent.domain.subagent.MaxRunningSource { 5 },
            com.aharou.feature.agent.domain.subagent.CommunicationEnabledSource { true }, registry)
        val raw = buildJsonArray {
            addJsonObject { put("id", "up"); put("prompt", "produce") }
            addJsonObject { put("id", "down"); put("prompt", "consume"); put("dependsOn", buildJsonArray { add("up") }) }
        }
        var spawned = 0
        val output = TeamStageExecutor(registry).pipeline(mapOf("pipeline" to raw),
            com.aharou.feature.agent.domain.model.AgentContext(null, null, "/project", null, sessionId = "parent"), bus,
            create = {
                spawned++
                bus.emit(com.aharou.feature.agent.domain.subagent.SubAgentEvent("sub", "parent",
                    com.aharou.feature.agent.domain.subagent.SubAgentEventType.COMPLETED, accepted = false))
                ToolResult.Success(buildJsonObject { put("id", "sub") })
            }, read = { "unaccepted output" }) as ToolResult.Success
        assertEquals(1, spawned)
        assertEquals("blocked", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        val states = output.data.jsonObject.getValue("taskStates").jsonObject
        assertEquals("unaccepted", states.getValue("up").jsonPrimitive.content)
        assertEquals("blocked", states.getValue("down").jsonPrimitive.content)
    }
    @Test fun missingSecondReviewerBlocksAndLongEvidenceIsPassedVerbatim() = runBlocking {
        val evidence = "BEGIN" + "x".repeat(9000) + "END"
        var calls = 0
        val output = TeamStageExecutor(TeamRunRegistry()).run(team(), "goal", listOf(role), 1, "parent") { batch ->
            calls++
            val prompt = batch.first().jsonObject.getValue("prompt").jsonPrimitive.content
            if (calls > 1) assertTrue(prompt.contains(evidence))
            if (calls == 1) result(batch, evidence)
            else if (batch.first().jsonObject.getValue("id").jsonPrimitive.content.startsWith("s3-")) {
                ToolResult.Success(buildJsonObject {
                    put("state", "done")
                    put("outputs", buildJsonObject {
                        batch.forEachIndexed { index, task ->
                            put(task.jsonObject.getValue("id").jsonPrimitive.content,
                                if (index == 0) "TEAM_REVIEW: PASS" else "missing machine conclusion")
                        }
                    })
                })
            } else result(batch)
        } as ToolResult.Success
        assertEquals(3, calls)
        assertEquals("blocked", output.data.jsonObject.getValue("state").jsonPrimitive.content)
    }
    @Test fun everyPriorBatchIsHandedToNextStageWithoutTruncation() {
        val many = team().copy(stages = listOf(TeamStage("up", listOf(task(), task(), task())), TeamStage("down", listOf(task()))))
        val plan = TeamRunner.build(many, "goal", listOf(role), 1) as TeamRunner.Plan.Ready
        val deps = plan.pipeline.last().jsonObject.getValue("dependsOn").jsonArray
        assertEquals(3, deps.size)
        assertTrue(plan.pipeline.first().jsonObject.getValue("prompt").jsonPrimitive.content.contains("s2-b1-t1"))
    }
}
