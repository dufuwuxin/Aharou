package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.every
import io.mockk.mockk
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
    @Test fun legacyTopicReachesAllIntakeMembersButNotDownstream() = runBlocking { verifyScopedLaunches(false) }

    @Test fun mappedBriefsStayWithTheirPresetStepsAcrossBatches() = runBlocking { verifyScopedLaunches(true) }

    private suspend fun verifyScopedLaunches(mapped: Boolean) {
        val intake = if (mapped) "original request PRIVATE_ORIGINAL" else "COMPATIBLE_LEGACY_TOPIC"
        val common = "CURRENT_SHARED_GOAL"
        val later = "deliver-only-result"
        val roles = (listOf(role) + listOf("builder", "reviewer").map { role.copy(name = it) })
        val configured = team().copy(
            charter = "SHARED_TEAM_RULE",
            admins = if (mapped) listOf(com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN) else team().admins,
            members = roles.map { com.aharou.feature.agent.domain.subagent.TeamMemberRef(it.name) },
            stages = listOf(
                TeamStage("prepare", listOf(
                    task("INTAKE_PRESET").copy(id = "intake"),
                    task("BUILDER_PRESET").copy(agent = "builder", writePaths = listOf("two.kt"), id = "builder"),
                    task("QUEUED_PRESET").copy(agent = "reviewer", writePaths = listOf("three.kt"), id = "queued",
                        interfaces = listOf(com.aharou.feature.agent.domain.subagent.TeamInterface("consume/final", "QUEUED_INTERFACE")))), id = "prep"),
                TeamStage("consume", listOf(task("CONSUMER_PRESET").copy(id = "final")), id = "consume")
            )
        )
        val registry = TeamRunRegistry()
        val executor = TeamStageExecutor(registry)
        val context = com.aharou.feature.agent.domain.model.AgentContext(null, null, "/project", null, sessionId = "parent")
        val bus = com.aharou.feature.agent.domain.subagent.SubAgentEventBus(
            com.aharou.feature.agent.domain.subagent.MaxRunningSource { 2 },
            com.aharou.feature.agent.domain.subagent.CommunicationEnabledSource { true }, registry)
        val launches = linkedMapOf<String, Map<String, JsonElement>>()
        val execute: suspend (JsonArray) -> ToolResult = { batch ->
            executor.pipeline(mapOf("pipeline" to batch), context, bus, create = { args ->
                val id = args.getValue("_teamLogicalId").jsonPrimitive.content
                launches[id] = args
                bus.emit(com.aharou.feature.agent.domain.subagent.SubAgentEvent("child-$id", "parent",
                    com.aharou.feature.agent.domain.subagent.SubAgentEventType.COMPLETED, accepted = true))
                ToolResult.Success(buildJsonObject { put("id", "child-$id") })
            }, read = { if (it == "child-s1-b1-t1") later else "result from $it" })
        }
        val result = if (!mapped) executor.run(configured, intake, roles, 2, "parent", exec = execute) else {
            val repository = mockk<AgentTeamRepository>()
            every { repository.forWorkspace(context.projectRoot) } returns repository
            every { repository.listAll() } returns listOf(AgentTeamEntry(configured, AgentDefinitionScope.PROJECT))
            val definitions = mockk<AgentDefinitionRepository>()
            every { definitions.listEnabled() } returns roles.map { AgentDefinitionEntry(it, AgentDefinitionScope.PROJECT) }
            val teams = TeamTasks(repository, definitions, com.aharou.feature.agent.domain.subagent.MaxRunningSource { 2 }, executor, mockk())
            fun brief(stage: String, task: String, text: String) = buildJsonObject {
                put("stageId", stage); put("taskId", task); put("brief", text)
            }
            val a = brief("prep", "intake", "A_ONLY")
            val rest = listOf(brief("prep", "builder", "B_ONLY"), brief("prep", "queued", "C_ONLY"),
                brief("consume", "final", "D_ONLY"))
            suspend fun invoke(items: List<JsonObject>) = teams.run(buildJsonObject {
                put("team", configured.name); put("goal", intake); put("sharedGoal", common); put("taskBriefs", JsonArray(items))
            }, "parent", context.projectRoot, exec = execute)
            assertEquals("INVALID_TEAM_BRIEFS", (invoke(listOf(a, a)) as ToolResult.Error).code)
            assertEquals("INVALID_TEAM_BRIEFS", (invoke(listOf(brief("prep", "unknown", "private"))) as ToolResult.Error).code)
            assertTrue(launches.isEmpty())
            invoke(listOf(a) + rest)
        }
        assertEquals("done", (result as ToolResult.Success).data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(listOf("s1-b1-t1", "s1-b1-t2", "s1-b2-t1", "s2-b1-t1"), launches.keys.toList())
        val prompts = launches.values.map { it.getValue("prompt").jsonPrimitive.content }
        assertTrue(prompts[0].contains(intake))
        if (mapped) assertFalse(prompts.drop(1).any { it.contains(intake) })
        else {
            assertTrue(prompts.take(3).all { it.contains(intake) })
            assertFalse(prompts[3].contains(intake))
        }
        listOf("INTAKE_PRESET", "BUILDER_PRESET", "QUEUED_PRESET", "CONSUMER_PRESET").forEachIndexed { index, preset ->
            assertTrue(prompts[index].contains(preset))
            assertTrue(prompts[index].contains("SHARED_TEAM_RULE"))
        }
        if (mapped) {
            listOf("A_ONLY", "B_ONLY", "C_ONLY", "D_ONLY").forEachIndexed { index, detail ->
                assertTrue(prompts[index].contains(detail))
                assertFalse(prompts.filterIndexed { i, _ -> i != index }.any { it.contains(detail) })
            }
            assertTrue(prompts.all { it.contains(common) })
        }
        assertFalse(prompts[0].contains("QUEUED_INTERFACE"))
        assertFalse(prompts[1].contains("QUEUED_INTERFACE"))
        assertTrue(prompts[2].contains("QUEUED_INTERFACE"))
        assertTrue(prompts[3].contains("QUEUED_INTERFACE"))
        assertFalse(prompts[2].contains(later))
        assertTrue(prompts[3].contains(later))
        assertEquals("reviewer", launches.getValue("s1-b2-t1").getValue("agent").jsonPrimitive.content)
        assertEquals(listOf(JsonPrimitive("three.kt")), launches.getValue("s1-b2-t1").getValue("writePaths").jsonArray)
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
        val b = com.aharou.feature.agent.domain.subagent.TeamFlowNode("a", "next")
        val c = com.aharou.feature.agent.domain.subagent.TeamFlowNode("c", "t")
        val original = routedTeam()
        val first = original.stages.first()
        val base = original.copy(stages = listOf(first.copy(tasks = first.tasks + task().copy(id = "next")), original.stages.last()),
            routes = listOf(com.aharou.feature.agent.domain.subagent.TeamRoute(a, b), com.aharou.feature.agent.domain.subagent.TeamRoute(b, c)))
        val topic = "LEGACY_ORIGINAL_REQUEST"
        val prompts = linkedMapOf<String, String>()
        val output = TeamStageExecutor(TeamRunRegistry()).run(base, topic, listOf(role), 2, "parent") { batch ->
            val step = batch.single().jsonObject
            val id = step.getValue("id").jsonPrimitive.content
            prompts[id] = step.getValue("prompt").jsonPrimitive.content
            result(batch, if (id == "s1-b1-t1") "PRIVATE_FIRST_NODE_EVIDENCE" else "DIRECT_PREDECESSOR_EVIDENCE")
        } as ToolResult.Success
        assertEquals("done", output.data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(listOf("s1-b1-t1", "s1-b1-t2", "s2-b1-t1"), prompts.keys.toList())
        assertTrue(prompts.getValue("s1-b1-t1").contains(topic))
        assertFalse(prompts.getValue("s1-b1-t2").contains(topic))
        assertTrue(prompts.getValue("s1-b1-t2").contains("PRIVATE_FIRST_NODE_EVIDENCE"))
        assertFalse(prompts.getValue("s2-b1-t1").contains("PRIVATE_FIRST_NODE_EVIDENCE"))
        assertTrue(prompts.getValue("s2-b1-t1").contains("DIRECT_PREDECESSOR_EVIDENCE"))
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
