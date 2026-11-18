package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TeamFeedbackRoutesTest {
    private fun node(stage: String) = TeamFlowNode(stage, "same-task-id")
    private val role = AgentDefinition(name = "worker", description = "", prompt = "p")
    private fun team(extra: List<TeamRoute> = listOf(TeamRoute(node("c"), node("a"), TeamRouteAction.REVIEW, true))) = AgentTeam(
        "feedback", "", listOf("a", "b", "c", "optional").map { s -> TeamStage(s,
            listOf(TeamTask(agent = "worker", prompt = "work-$s", writePaths = listOf("$s.kt"), id = "same-task-id")), id = s) },
        "", admins = listOf("worker"), members = listOf(TeamMemberRef("worker")),
        routes = listOf(TeamRoute(node("a"), node("b")), TeamRoute(node("b"), node("c"))) + extra)
    private fun request(target: String, action: String = "REVIEW") =
        """TEAM_HANDOFF: {"next":[],"hold":false,"reason":"selected feedback","feedback":{"target":{"stageId":"$target","taskId":"same-task-id"},"action":"$action"}}"""
    private fun state(result: ToolResult) = (result as ToolResult.Success).data.jsonObject.getValue("state").jsonPrimitive.content

    private suspend fun execute(team: AgentTeam, seen: MutableList<String>, output: (String, Int, Map<String, JsonElement>) -> String): ToolResult {
        val registry = TeamRunRegistry()
        val executor = TeamStageExecutor(registry)
        val bus = SubAgentEventBus(MaxRunningSource { 1 }, CommunicationEnabledSource { true }, registry)
        val texts = mutableMapOf<String, String>()
        val context = AgentContext(null, null, "/project", null, sessionId = "parent")
        return executor.run(team, "goal", listOf(role), 1, "parent") { batch ->
            executor.pipeline(mapOf("pipeline" to batch), context, bus, create = { args ->
                val logical = args.getValue("_teamLogicalId").jsonPrimitive.content
                seen += logical
                val session = "session-${seen.size}"
                texts[session] = output(logical, seen.size, args)
                registry.bind(args.getValue("_teamRunId").jsonPrimitive.content, logical, session)
                bus.emit(SubAgentEvent(session, "parent", SubAgentEventType.COMPLETED, accepted = true))
                ToolResult.Success(buildJsonObject { put("id", session) })
            }, read = { texts.getValue(it) })
        }
    }

    @Test fun thirdToFirstReviewUsesRealPipelineAndReturnsToThirdWithoutChangingFirstDelivery() = runBlocking {
        val seen = mutableListOf<String>()
        val result = execute(team(), seen) { logical, count, args ->
            assertEquals(1, args.getValue("writePaths").jsonArray.size)
            when (count) {
                3 -> request("a")
                4 -> { assertTrue(args.getValue("prompt").jsonPrimitive.content.contains("s3-b1-t1")); "TEAM_REVIEW: PASS" }
                5 -> { assertTrue(args.getValue("prompt").jsonPrimitive.content.contains("Review result:")); "continued-c" }
                else -> "original-$logical"
            }
        }
        assertEquals("done", state(result))
        assertEquals(listOf("s1-b1-t1", "s2-b1-t1", "s3-b1-t1", "s1-b1-t1", "s3-b1-t1"), seen)
        assertEquals("original-s1-b1-t1", (result as ToolResult.Success).data.jsonObject.getValue("outputs").jsonObject.getValue("s1-b1-t1").jsonPrimitive.content)
    }
    @Test fun unselectedOptionalReviewDoesNotBecomeRequiredGate() = runBlocking {
        val seen = mutableListOf<String>()
        val t = team(listOf(TeamRoute(node("c"), node("optional"), TeamRouteAction.REVIEW, true)))
        assertFalse(TeamFlowGraph.isReview(t, node("optional")))
        assertEquals("done", state(execute(t, seen) { _, _, _ -> "delivery" }))
        assertEquals(3, seen.size)
    }
    @Test fun unknownTargetAndWrongActionDoNotDispatchFeedback() = runBlocking {
        for (decision in listOf(request("missing"), request("a", "REJECT"))) {
            val seen = mutableListOf<String>()
            assertEquals("blocked", state(execute(team(), seen) { _, count, _ -> if (count == 3) decision else "delivery" }))
            assertEquals(3, seen.size)
        }
    }
    @Test fun missingOrRejectedDynamicVerdictCannotAdvanceAsPass() = runBlocking {
        for (verdict in listOf("no machine verdict", "TEAM_REVIEW: REJECT fix-c")) {
            val seen = mutableListOf<String>()
            val result = execute(team(), seen) { _, count, args -> when {
                count == 3 -> request("a")
                count == 4 -> verdict
                else -> { if (count == 5) assertTrue(args.getValue("prompt").jsonPrimitive.content.contains("fix-c")); "delivery" }
            } }
            assertEquals(if (verdict.startsWith("TEAM_REVIEW:")) "done" else "blocked", state(result))
            assertEquals(if (verdict.startsWith("TEAM_REVIEW:")) 5 else 4, seen.size)
        }
    }
    @Test fun anySourceRejectsOnlySelectedTargetClosureNotOtherConfiguredTargets() = runBlocking {
        val seen = mutableListOf<String>()
        val t = team(listOf(TeamRoute(node("c"), node("a"), TeamRouteAction.REJECT),
            TeamRoute(node("c"), node("b"), TeamRouteAction.REJECT)))
        val result = execute(t, seen) { _, count, _ -> if (count == 3) request("b", "REJECT") else "delivery" }
        assertEquals("done", state(result))
        assertEquals(listOf("s1-b1-t1", "s2-b1-t1", "s3-b1-t1", "s2-b1-t1", "s3-b1-t1"), seen)
    }
    @Test fun changingActionsCannotResetWholeRunBudget() = runBlocking {
        val seen = mutableListOf<String>()
        val t = team(listOf(TeamRoute(node("c"), node("a"), TeamRouteAction.REVIEW, true),
            TeamRoute(node("c"), node("b"), TeamRouteAction.REJECT)))
        val result = execute(t, seen) { _, count, _ -> when (count) {
            3 -> request("a")
            4 -> "TEAM_REVIEW: PASS"
            5 -> request("b", "REJECT")
            7 -> request("a")
            else -> "delivery"
        } }
        assertEquals("blocked", state(result))
        assertEquals(7, seen.size)
        assertEquals(2, (result as ToolResult.Success).data.jsonObject.getValue("reworkRounds").jsonPrimitive.int)
    }
    @Test fun cancellationEscapesRatherThanDispatchingContinuation() = runBlocking {
        val seen = mutableListOf<String>()
        try {
            execute(team(), seen) { _, count, _ -> when (count) {
                3 -> request("a")
                4 -> throw CancellationException("parent cancelled")
                else -> "delivery"
            } }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { assertEquals(4, seen.size) }
    }
    @Test fun resetRejectsLateSessionStateAndMessageRoute() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", buildJsonArray { addJsonObject { put("id", "task"); put("_teamStage", "s") } })
        registry.bind(run, "task", "old")
        registry.reset(run, setOf("task"))
        registry.bind(run, "task", "new")
        registry.terminal("old", "succeeded")
        assertNull(registry.runOf("old"))
        assertFalse(registry.canMessage("old"))
        assertEquals(run, registry.runOf("new"))
    }
    @Test fun feedbackAndLegacyRoutesSurviveDefinitionRoundtrip() {
        val original = team(listOf(TeamRoute(node("c"), node("a"), TeamRouteAction.REVIEW, true),
            TeamRoute(node("c"), node("b"), TeamRouteAction.REJECT)))
        val file = File.createTempFile("team-feedback-", ".md")
        try {
            val serialized = AgentTeamParser.serialize(original.toForm(AgentDefinitionScope.GLOBAL))
            assertEquals(1, Regex("feedback: true").findAll(serialized).count())
            assertFalse(serialized.contains("feedback: false"))
            file.writeText(serialized)
            val parsed = requireNotNull(AgentTeamParser.parse(TestFileAccessProvider(), file.absolutePath))
            assertEquals(original.routes, parsed.routes)
            assertEquals(original.stages.map { it.id }, parsed.stages.map { it.id })
            assertEquals(original.stages.flatMap { it.tasks }.map { it.prompt }, parsed.stages.flatMap { it.tasks }.map { it.prompt })
            assertEquals(original.stages.flatMap { it.tasks }.map { it.writePaths }, parsed.stages.flatMap { it.tasks }.map { it.writePaths })
            assertEquals(original.admins, parsed.admins)
            val again = AgentTeamParser.serialize(parsed.toForm(AgentDefinitionScope.GLOBAL))
            file.writeText(again)
            assertEquals(parsed.routes, requireNotNull(AgentTeamParser.parse(TestFileAccessProvider(), file.absolutePath)).routes)
            file.writeText(serialized.replace("feedback: true", "feedback: false"))
            val explicitFalse = requireNotNull(AgentTeamParser.parse(TestFileAccessProvider(), file.absolutePath))
            assertTrue(explicitFalse.routes.none { it.feedback })
            for (malformed in listOf("'true'", "null", "1")) {
                file.writeText(serialized.replace("feedback: true", "feedback: $malformed"))
                val invalid = requireNotNull(AgentTeamParser.parse(TestFileAccessProvider(), file.absolutePath))
                assertNotNull(TeamFlowGraph.validate(invalid))
                assertEquals("", invalid.routes.single { it.from.stageId.isEmpty() }.from.taskId)
            }
            assertFalse(parsed.routes.first().feedback)
            assertFalse(parsed.routes.last().feedback)
            assertTrue(parsed.routes.last().isFeedback)
        } finally { file.delete() }
    }
    @Test fun ordinaryNodeRejectsOnlySelectedClosureAndKeepsIndependentSameRoleTasks() = runBlocking {
        val t = team().copy(routes = listOf(TeamRoute(node("a"), node("b")), TeamRoute(node("optional"), node("c")),
            TeamRoute(node("c"), node("a"), TeamRouteAction.REJECT, true),
            TeamRoute(node("c"), node("b"), TeamRouteAction.REJECT, true)))
        val seen = mutableListOf<String>()
        val result = execute(t, seen) { logical, _, _ -> if (logical == "s3-b1-t1") request("b", "REJECT") else "delivery-$logical" }
        assertEquals("done", state(result))
        assertEquals(1, seen.count { it == "s1-b1-t1" })
        assertEquals(2, seen.count { it == "s2-b1-t1" })
        assertEquals(1, seen.count { it == "s3-b1-t1" })
        assertEquals(1, seen.count { it == "s4-b1-t1" })
    }
    @Test fun requiredCrossBranchReviewerRunsAgainWithRepairedEvidence() = runBlocking {
        val base = team()
        val t = base.copy(stages = base.stages.map { if (it.id == "c") it.copy(isReview = true) else it },
            routes = listOf(TeamRoute(node("a"), node("b")), TeamRoute(node("optional"), node("c")),
                TeamRoute(node("c"), node("b"), TeamRouteAction.REJECT, true)))
        val seen = mutableListOf<String>()
        var reviews = 0
        var repairs = 0
        val result = execute(t, seen) { logical, _, args -> when (logical) {
            "s2-b1-t1" -> "repair-${++repairs}"
            "s3-b1-t1" -> if (++reviews == 1) "TEAM_REVIEW: REJECT fix-b\n" + request("b", "REJECT") else {
                val prompt = args.getValue("prompt").jsonPrimitive.content
                assertTrue(prompt.contains("repair-2"))
                assertTrue(prompt.contains("fix-b"))
                "TEAM_REVIEW: PASS"
            }
            else -> "delivery-$logical"
        } }
        assertEquals("done", state(result))
        assertEquals(2, reviews)
        assertEquals(2, repairs)
        assertEquals(1, seen.count { it == "s1-b1-t1" })
        assertEquals(1, seen.count { it == "s4-b1-t1" })
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("PASSED", data.getValue("reviewGates").jsonObject.getValue("s3-b1-t1").jsonPrimitive.content)
        assertEquals(1, data.getValue("reworkRounds").jsonPrimitive.int)
    }
    @Test fun crossBranchRecheckInvalidatesConsumersOfAnOlderPassedReview() = runBlocking {
        val base = team()
        val t = base.copy(stages = listOf("a", "x", "r", "d", "b", "c").map { s ->
            base.stages.first().copy(id = s, isReview = s == "r", tasks = listOf(
                base.stages.first().tasks.first().copy(prompt = "work-$s", writePaths = listOf("$s.kt"))))
        }, routes = listOf(TeamRoute(node("a"), node("b")), TeamRoute(node("x"), node("r")),
            TeamRoute(node("r"), node("d")), TeamRoute(node("b"), node("c")), TeamRoute(node("d"), node("c")),
            TeamRoute(node("c"), node("r"), TeamRouteAction.REJECT, true),
            TeamRoute(node("r"), node("b"), TeamRouteAction.REJECT, true)))
        val seen = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        val result = execute(t, seen) { logical, _, args ->
            val count = counts.getOrDefault(logical, 0) + 1
            counts[logical] = count
            when (logical) {
                "s3-b1-t1" -> when (count) {
                    1 -> "TEAM_REVIEW: PASS\nold-review"
                    2 -> "TEAM_REVIEW: REJECT repair-b\n" + request("b", "REJECT")
                    else -> {
                        assertTrue(args.getValue("prompt").jsonPrimitive.content.contains("b-version-2"))
                        "TEAM_REVIEW: PASS\nnew-review"
                    }
                }
                "s4-b1-t1" -> {
                    val prompt = args.getValue("prompt").jsonPrimitive.content
                    assertTrue(prompt.contains(if (count == 1) "old-review" else "new-review"))
                    "consumer-$count"
                }
                "s5-b1-t1" -> "b-version-$count"
                "s6-b1-t1" -> if (count == 1) request("r", "REJECT") else "final-delivery"
                else -> "delivery-$logical"
            }
        }
        assertEquals("done", state(result))
        assertEquals(3, counts["s3-b1-t1"])
        assertEquals(2, counts["s4-b1-t1"])
        assertEquals(2, counts["s5-b1-t1"])
        assertEquals(1, counts["s1-b1-t1"])
        assertEquals(1, counts["s2-b1-t1"])
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("consumer-2", data.getValue("outputs").jsonObject.getValue("s4-b1-t1").jsonPrimitive.content)
        assertEquals(2, data.getValue("reworkRounds").jsonPrimitive.int)
    }
    @Test fun changingFromReviewToRejectDoesNotResetTheTwoRoundBudget() = runBlocking {
        val t = team(listOf(TeamRoute(node("c"), node("a"), TeamRouteAction.REVIEW, true),
            TeamRoute(node("c"), node("b"), TeamRouteAction.REJECT, true)))
        val seen = mutableListOf<String>()
        val result = execute(t, seen) { _, count, _ -> when (count) {
            3 -> request("a")
            4 -> "TEAM_REVIEW: PASS"
            5 -> request("b", "REJECT")
            7 -> request("a")
            else -> "delivery"
        } }
        assertEquals("blocked", state(result))
        assertEquals(7, seen.size)
        assertEquals(2, (result as ToolResult.Success).data.jsonObject.getValue("reworkRounds").jsonPrimitive.int)
    }

}
