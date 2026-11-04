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
    ), "constraints", admins = listOf("worker"), members = listOf(com.aharou.feature.agent.domain.subagent.TeamMemberRef("worker")))
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
    private fun routedTeam(): AgentTeam {
        val a = com.aharou.feature.agent.domain.subagent.TeamFlowNode("a", "t")
        val b = com.aharou.feature.agent.domain.subagent.TeamFlowNode("b", "t")
        val c = com.aharou.feature.agent.domain.subagent.TeamFlowNode("c", "t")
        val stageList = listOf("a", "b", "c").map { TeamStage(it, listOf(task().copy(id = "t")), id = it) }
        return AgentTeam("routed", "", stageList, "", admins = listOf("worker"),
            members = listOf(com.aharou.feature.agent.domain.subagent.TeamMemberRef("worker")),
            routes = listOf(com.aharou.feature.agent.domain.subagent.TeamRoute(a, b, com.aharou.feature.agent.domain.subagent.TeamRouteAction.REVIEW),
                com.aharou.feature.agent.domain.subagent.TeamRoute(b, c),
                com.aharou.feature.agent.domain.subagent.TeamRoute(b, a, com.aharou.feature.agent.domain.subagent.TeamRouteAction.REJECT)))
    }
    @Test fun routedReviewWithoutVerdictNeverStartsDownstream() = runBlocking {
        val calls = mutableListOf<JsonArray>()
        val output = TeamStageExecutor(TeamRunRegistry()).run(routedTeam(), "goal", listOf(role), 2, "parent") { batch ->
            calls += batch
            result(batch, "unstructured text")
        } as ToolResult.Success
        assertEquals("blocked", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(2, calls.size)
        assertFalse(calls.any { batch -> batch.any { it.jsonObject.getValue("id").jsonPrimitive.content.startsWith("s3-") } })
    }
    @Test fun routedRejectionsStopAtTwoRounds() = runBlocking {
        var calls = 0
        val output = TeamStageExecutor(TeamRunRegistry()).run(routedTeam(), "goal", listOf(role), 2, "parent") { batch ->
            calls++
            result(batch, if (calls % 2 == 0) "TEAM_REVIEW: REJECT fix required" else "original delivery")
        } as ToolResult.Success
        assertEquals("blocked", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(2, output.data.jsonObject.getValue("reworkRounds").jsonPrimitive.int)
        assertEquals(6, calls)
    }
    @Test fun administratorMisconfigurationStopsBeforeAnyDispatch() = runBlocking {
        var dispatched = false
        val result = TeamStageExecutor(TeamRunRegistry()).run(routedTeam().copy(admins = emptyList()), "goal", listOf(role), 2, "parent") {
            dispatched = true; result(it)
        }
        assertTrue(result is ToolResult.Error)
        assertFalse(dispatched)
    }
    @Test fun routeTargetReceivesOnlyItsImmediateRequiredPredecessors() = runBlocking {
        val a = com.aharou.feature.agent.domain.subagent.TeamFlowNode("a", "t")
        val b = com.aharou.feature.agent.domain.subagent.TeamFlowNode("b", "t")
        val c = com.aharou.feature.agent.domain.subagent.TeamFlowNode("c", "t")
        val base = routedTeam().copy(routes = listOf(com.aharou.feature.agent.domain.subagent.TeamRoute(a, b),
            com.aharou.feature.agent.domain.subagent.TeamRoute(b, c)))
        val prompts = mutableListOf<String>()
        val output = TeamStageExecutor(TeamRunRegistry()).run(base, "goal", listOf(role), 2, "parent") { batch ->
            prompts += batch.single().jsonObject.getValue("prompt").jsonPrimitive.content
            result(batch, if (prompts.size == 1) "PRIVATE_FIRST_NODE_EVIDENCE" else "DIRECT_PREDECESSOR_EVIDENCE")
        } as ToolResult.Success
        assertEquals("done", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertTrue(prompts[1].contains("PRIVATE_FIRST_NODE_EVIDENCE"))
        assertFalse(prompts[2].contains("PRIVATE_FIRST_NODE_EVIDENCE"))
        assertTrue(prompts[2].contains("DIRECT_PREDECESSOR_EVIDENCE"))
    }
    @Test fun structuredHandoffCanHoldOrSelectNoDownstreamButCannotInventOne() = runBlocking {
        val executor = TeamStageExecutor(TeamRunRegistry())
        val all = listOf(
            "TEAM_HANDOFF: {\"next\":[],\"hold\":true,\"reason\":\"missing evidence\"}" to "blocked",
            "TEAM_HANDOFF: {\"next\":[],\"hold\":false,\"reason\":\"not needed\"}" to "incomplete",
            "TEAM_HANDOFF: {\"next\":[{\"stageId\":\"c\",\"taskId\":\"t\"}],\"hold\":false,\"reason\":\"skip review\"}" to "blocked")
        for ((decision, expected) in all) {
            var calls = 0
            val output = executor.run(routedTeam(), "goal", listOf(role), 2, "parent") { batch -> calls++; result(batch, decision) } as ToolResult.Success
            assertEquals(expected, output.data.jsonObject.getValue("state").jsonPrimitive.content)
            if (expected == "incomplete") {
                assertTrue(output.data.jsonObject.getValue("unselected").jsonArray.any { it.jsonPrimitive.content.startsWith("s2-") })
                assertEquals("PENDING", output.data.jsonObject.getValue("reviewGates").jsonObject.getValue("s2-b1-t1").jsonPrimitive.content)
            }
            assertEquals(1, calls)
        }
        assertNull(TeamStageExecutor.handoff("TEAM_HANDOFF: not-json"))
        assertNull(TeamStageExecutor.handoff("TEAM_HANDOFF: {\"next\":[],\"hold\":true}"))
        assertNull(TeamStageExecutor.handoff("no marker")?.next)
    }
    @Test fun explicitFanoutSelectionDoesNotWakeAllSuccessors() = runBlocking {
        val a = com.aharou.feature.agent.domain.subagent.TeamFlowNode("a", "t")
        val b = com.aharou.feature.agent.domain.subagent.TeamFlowNode("b", "t")
        val c = com.aharou.feature.agent.domain.subagent.TeamFlowNode("c", "t")
        val team = routedTeam().copy(routes = listOf(com.aharou.feature.agent.domain.subagent.TeamRoute(a, b), com.aharou.feature.agent.domain.subagent.TeamRoute(a, c)))
        val ids = mutableListOf<String>()
        val output = TeamStageExecutor(TeamRunRegistry()).run(team, "goal", listOf(role), 2, "parent") { batch ->
            ids += batch.single().jsonObject.getValue("id").jsonPrimitive.content
            result(batch, if (ids.size == 1) "TEAM_HANDOFF: {\"next\":[{\"stageId\":\"b\",\"taskId\":\"t\"}],\"hold\":false,\"reason\":\"only b is necessary\"}" else "delivery")
        } as ToolResult.Success
        assertEquals("done", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(listOf("s1-b1-t1", "s2-b1-t1"), ids)
    }
    @Test fun twoRejectingReviewersAreBothIncludedInTargetRework() = runBlocking {
        val n = listOf("a", "b", "c").associateWith { com.aharou.feature.agent.domain.subagent.TeamFlowNode(it, "t") }
        val action = com.aharou.feature.agent.domain.subagent.TeamRouteAction.REVIEW
        val reject = com.aharou.feature.agent.domain.subagent.TeamRouteAction.REJECT
        val t = routedTeam().copy(routes = listOf(
            com.aharou.feature.agent.domain.subagent.TeamRoute(n.getValue("a"), n.getValue("b"), action),
            com.aharou.feature.agent.domain.subagent.TeamRoute(n.getValue("a"), n.getValue("c"), action),
            com.aharou.feature.agent.domain.subagent.TeamRoute(n.getValue("b"), n.getValue("a"), reject),
            com.aharou.feature.agent.domain.subagent.TeamRoute(n.getValue("c"), n.getValue("a"), reject)))
        var calls = 0; var rework = ""
        TeamStageExecutor(TeamRunRegistry()).run(t, "goal", listOf(role), 2, "parent") { batch ->
            calls++
            if (calls == 3) rework = batch.single().jsonObject.getValue("prompt").jsonPrimitive.content
            ToolResult.Success(buildJsonObject { put("state", "done"); put("outputs", buildJsonObject {
                batch.forEach { raw -> val id = raw.jsonObject.getValue("id").jsonPrimitive.content
                    put(id, if (calls == 2) "TEAM_REVIEW: REJECT reason-$id" else if (calls > 3) "TEAM_REVIEW: PASS" else "original") }
            }) })
        }
        assertTrue(rework.contains("reason-s2-b1-t1"))
        assertTrue(rework.contains("reason-s3-b1-t1"))
    }
}
